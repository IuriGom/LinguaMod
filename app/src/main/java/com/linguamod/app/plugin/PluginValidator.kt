package com.linguamod.app.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class ValidationError(val rule: String, val message: String, val elementId: String? = null) {
    override fun toString(): String = "[$rule] ${elementId?.let { "$it: " } ?: ""}$message"
}

data class ValidationResult(
    val plugin: LinguaPluginDto?,
    val errors: List<ValidationError>,
) {
    val isValid: Boolean get() = errors.isEmpty() && plugin != null
}

/**
 * Spec §8 validator. Pure JVM — callable from unit tests against any .lingua file.
 */
object PluginValidator {

    const val MAX_FILE_BYTES = 5 * 1024 * 1024
    const val MAX_DEPTH = 32
    const val CHECKPOINT_SIZE = 10
    const val CHECKPOINT_SIZE_UNIT60 = 15

    private val json = Json { ignoreUnknownKeys = true }

    fun validateFile(bytes: ByteArray): ValidationResult {
        if (bytes.size > MAX_FILE_BYTES) {
            return ValidationResult(null, listOf(ValidationError("R1", "file exceeds 5 MB (${bytes.size} bytes)")))
        }
        val text = try {
            bytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            return ValidationResult(null, listOf(ValidationError("R1", "not valid UTF-8")))
        }
        return validateText(text)
    }

    fun validateText(text: String): ValidationResult {
        val element: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ValidationResult(null, listOf(ValidationError("R1", "invalid JSON: ${e.message?.take(120)}")))
        }
        val depth = maxDepth(element)
        if (depth > MAX_DEPTH) {
            return ValidationResult(null, listOf(ValidationError("R1", "JSON nesting depth $depth exceeds $MAX_DEPTH")))
        }
        val dto = try {
            json.decodeFromJsonElement(LinguaPluginDto.serializer(), element)
        } catch (e: Exception) {
            return ValidationResult(null, listOf(ValidationError("R1", "structure/type error: ${e.message?.take(160)}")))
        }
        return validate(dto)
    }

    private fun maxDepth(e: JsonElement): Int = when (e) {
        is JsonObject -> 1 + (e.values.maxOfOrNull { maxDepth(it) } ?: 0)
        is JsonArray -> 1 + (e.maxOfOrNull { maxDepth(it) } ?: 0)
        else -> 1
    }

    fun validate(p: LinguaPluginDto): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        fun err(rule: String, msg: String, id: String? = null) { errors += ValidationError(rule, msg, id) }

        // R1: format version
        if (p.formatVersion != 1) err("R1", "formatVersion must be 1, got ${p.formatVersion}")

        // R2: meta
        val meta = p.meta
        if (meta == null) {
            err("R2", "missing meta block")
        } else {
            if (meta.id.isNullOrBlank() || !meta.id.matches(Regex("[a-z0-9-]+"))) err("R2", "meta.id missing or not lowercase ascii: ${meta.id}")
            if (meta.language.isNullOrBlank()) err("R2", "meta.language missing")
            if (meta.languageName.isNullOrBlank()) err("R2", "meta.languageName missing")
            if (meta.version == null || meta.version < 1) err("R2", "meta.version missing or < 1")
        }

        // R4: units present, numbered consecutively from 1
        if (p.units.isEmpty()) err("R4", "no units")
        p.units.forEachIndexed { i, u ->
            if (u.number != i + 1) err("R4", "unit ${u.id ?: "?"} has number ${u.number}, expected ${i + 1}", u.id)
        }

        // R3: global ID uniqueness
        val seen = HashSet<String>()
        fun checkId(id: String?, kind: String) {
            if (id.isNullOrBlank()) { err("R3", "$kind missing id"); return }
            if (!seen.add(id)) err("R3", "duplicate id '$id'", id)
        }

        val dictIds = HashSet<String>()
        val dictByUnitIntro = HashMap<String, Int>()
        val unitNumbers = p.units.mapNotNull { it.number }.toSet()

        p.units.forEach { u -> u.id?.let { checkId(it, "unit") } }
        p.units.forEach { u ->
            u.lessons?.forEach { l ->
                checkId(l.id, "lesson")
                l.exercises.forEach { e -> checkId(e.id, "exercise") }
            }
            u.checkpoint?.let { c ->
                checkId(c.id, "checkpoint")
                c.exercises.forEach { e -> checkId(e.id, "exercise") }
            }
        }
        p.dictionary.forEach { d ->
            checkId(d.id, "dictionary entry")
            d.id?.let { dictIds += it }
            if (d.id != null && d.introducedInUnit != null) dictByUnitIntro[d.id] = d.introducedInUnit
        }
        p.stories.forEach { s ->
            checkId(s.id, "story")
            val nodeIds = HashSet<String>()
            s.nodes.forEach { n ->
                if (n.id.isNullOrBlank()) err("R3", "story ${s.id}: node missing id", s.id)
                else if (!nodeIds.add(n.id)) err("R3", "story ${s.id}: duplicate node id '${n.id}'", n.id)
            }
        }

        // R5/R6: unit structure
        p.units.forEach { u ->
            val lessons = u.lessons
            if (lessons == null || lessons.size != 4) {
                err("R5", "unit ${u.number} has ${lessons?.size ?: 0} lessons, expected 4", u.id)
            } else {
                lessons.forEachIndexed { i, l ->
                    if (l.type != LessonTypes.ORDER[i]) {
                        err("R5", "unit ${u.number} lesson ${i + 1} type '${l.type}', expected '${LessonTypes.ORDER[i]}'", l.id)
                    }
                }
            }
            val cp = u.checkpoint
            if (cp == null) {
                err("R5", "unit ${u.number} missing checkpoint", u.id)
            } else {
                val expected = if (u.number == 60) CHECKPOINT_SIZE_UNIT60 else CHECKPOINT_SIZE
                if (cp.exercises.size != expected) {
                    err("R6", "checkpoint of unit ${u.number} has ${cp.exercises.size} exercises, expected $expected", cp.id)
                }
            }
        }

        // Exercise checks
        fun checkOptions(options: List<String>?, correctIndex: Int?, id: String?) {
            if (options == null || options.size != 4) { err("R7", "needs exactly 4 options, got ${options?.size ?: 0}", id); return }
            if (options.distinct().size != 4) err("R7", "options must be distinct", id)
            if (correctIndex == null || correctIndex !in 0..3) err("R7", "correctIndex $correctIndex out of range 0..3", id)
        }

        fun checkExercise(e: ExerciseDto, ownerId: String?) {
            if (e.type !in ExerciseTypes.ALL) { err("R15", "unknown exercise type '${e.type}'", e.id ?: ownerId); return }
            if (e.prompt.isNullOrBlank()) err("R8", "missing prompt", e.id)
            if (e.explanation.isNullOrBlank()) err("R8", "missing explanation", e.id)
            when (e.type) {
                ExerciseTypes.MULTIPLE_CHOICE -> {
                    if (e.question.isNullOrBlank()) err("R15", "multiple_choice missing question", e.id)
                    checkOptions(e.options, e.correctIndex, e.id)
                }
                ExerciseTypes.FILL_BLANK -> {
                    val blanks = e.sentence?.let { Regex("___").findAll(it).count() } ?: 0
                    if (blanks != 1) err("R16", "fill_blank sentence must contain exactly one '___' blank, found $blanks", e.id)
                    if (e.answers.isNullOrEmpty() || e.answers.any { it.isBlank() }) err("R15", "fill_blank needs non-empty answers", e.id)
                }
                ExerciseTypes.TRANSLATION_IT_EN -> {
                    if (e.sourceIt.isNullOrBlank()) err("R15", "translation_it_en missing sourceIt", e.id)
                    if (e.acceptedEn.isNullOrEmpty() || e.acceptedEn.any { it.isBlank() }) err("R15", "translation_it_en needs acceptedEn", e.id)
                }
                ExerciseTypes.TRANSLATION_EN_IT -> {
                    if (e.sourceEn.isNullOrBlank()) err("R15", "translation_en_it missing sourceEn", e.id)
                    if (e.acceptedIt.isNullOrEmpty() || e.acceptedIt.any { it.isBlank() }) err("R15", "translation_en_it needs acceptedIt", e.id)
                }
                ExerciseTypes.LISTENING -> {
                    if (e.speakIt.isNullOrBlank()) err("R15", "listening missing speakIt", e.id)
                    when (e.mode) {
                        "choice" -> checkOptions(e.options, e.correctIndex, e.id)
                        "type" -> if (e.acceptedIt.isNullOrEmpty() || e.acceptedIt.any { it.isBlank() }) err("R15", "listening(type) needs acceptedIt", e.id)
                        else -> err("R15", "listening mode must be 'choice' or 'type', got '${e.mode}'", e.id)
                    }
                }
                ExerciseTypes.SPEAKING -> {
                    if (e.targetIt.isNullOrBlank()) err("R15", "speaking missing targetIt", e.id)
                    val acc = e.minAccuracy
                    if (acc != null && (acc <= 0.0 || acc > 1.0)) err("R15", "speaking minAccuracy $acc out of (0,1]", e.id)
                }
                ExerciseTypes.SENTENCE_SCRAMBLE -> {
                    val tokens = e.tokens
                    val correct = e.correctSentence
                    if (tokens == null || tokens.size < 2) err("R15", "sentence_scramble needs >= 2 tokens", e.id)
                    else if (correct.isNullOrBlank()) err("R15", "sentence_scramble missing correctSentence", e.id)
                    else {
                        val norm = { s: String -> s.trim().split(Regex("\\s+")).sorted() }
                        if (norm(correct) != tokens.sorted()) err("R13", "scramble tokens are not a permutation of correctSentence", e.id)
                    }
                }
            }
        }

        p.units.forEach { u ->
            u.lessons?.forEach { l ->
                if (l.exercises.size !in 4..12) err("R15", "lesson ${l.id} has ${l.exercises.size} exercises, expected 4-12", l.id)
                l.exercises.forEach { checkExercise(it, l.id) }
            }
            u.checkpoint?.exercises?.forEach { checkExercise(it, u.checkpoint.id) }
        }

        // R9/R12: dictionary
        val allowedArticles = setOf("il", "lo", "la", "l'", "i", "gli", "le", "un", "uno", "una", "un'")
        val loPattern = Regex("^(s[bcdfghjklmnpqrstvwxz]|z|gn|ps|x|y).*")
        val vowelPattern = Regex("^[aeiouàèéìòù].*")
        p.dictionary.forEach { d ->
            if (d.word.isNullOrBlank()) err("R2", "dictionary entry missing word", d.id)
            if (d.translation.isNullOrBlank()) err("R2", "dictionary entry missing translation", d.id)
            if (d.examples.isEmpty() || d.examples.any { it.it.isNullOrBlank() || it.en.isNullOrBlank() }) {
                err("R2", "dictionary entry needs 1-3 examples with it+en", d.id)
            }
            if (d.introducedInUnit == null || d.introducedInUnit !in unitNumbers) {
                err("R10", "introducedInUnit ${d.introducedInUnit} does not reference an existing unit", d.id)
            }
            if (d.partOfSpeech == "noun") {
                if (d.gender !in setOf("m", "f")) err("R9", "noun missing gender m/f", d.id)
                val art = d.article
                if (art == null || art !in allowedArticles) {
                    err("R9", "noun missing/invalid article '$art'", d.id)
                } else if (d.gender in setOf("m", "f") && !d.word.isNullOrBlank()) {
                    val w = d.word.lowercase()
                    val isLo = loPattern.matches(w)
                    val isVowel = vowelPattern.matches(w)
                    val ok = when (art) {
                        "lo" -> d.gender == "m" && isLo && !isVowel
                        "gli" -> d.gender == "m" && (isLo || isVowel)
                        "il" -> d.gender == "m" && !isLo && !isVowel
                        "i" -> d.gender == "m"
                        "l'" -> isVowel
                        "la", "le" -> d.gender == "f" && !isVowel
                        "uno" -> d.gender == "m" && isLo
                        "un" -> d.gender == "m" && !isLo
                        "un'" -> d.gender == "f" && isVowel
                        "una" -> d.gender == "f" && !isVowel
                        else -> false
                    }
                    if (!ok) err("R12", "article '$art' inconsistent with word '${d.word}' (gender ${d.gender})", d.id)
                }
            } else {
                if (d.gender != null) err("R9", "non-noun has gender", d.id)
                if (d.article != null) err("R9", "non-noun has article", d.id)
            }
        }

        // R10/R11/R18: refs
        p.units.forEach { u ->
            val n = u.number ?: return@forEach
            u.lessons?.forEach { l ->
                l.dictionaryRefs.forEach { ref ->
                    if (ref !in dictIds) err("R10", "dangling dictionaryRef '$ref'", l.id)
                    else {
                        val intro = dictByUnitIntro[ref] ?: 0
                        if (intro > n) err("R11", "lesson in unit $n references '$ref' introduced in unit $intro", l.id)
                    }
                }
                if (l.type == LessonTypes.MIXED && n >= 3) {
                    val hasRecycled = l.dictionaryRefs.any { (dictByUnitIntro[it] ?: Int.MAX_VALUE) < n }
                    if (!hasRecycled) err("R18", "mixed lesson of unit $n must recycle at least one earlier-unit word", l.id)
                }
                l.exercises.forEach { e ->
                    e.dictionaryRefs.forEach { ref ->
                        if (ref !in dictIds) err("R10", "dangling exercise dictionaryRef '$ref'", e.id)
                    }
                }
            }
        }

        // R14: every dictionary entry used
        val usedRefs = HashSet<String>()
        p.units.forEach { u ->
            u.lessons?.forEach { l ->
                usedRefs += l.dictionaryRefs
                l.exercises.forEach { usedRefs += it.dictionaryRefs }
            }
            u.checkpoint?.exercises?.forEach { usedRefs += it.dictionaryRefs }
        }
        p.dictionary.forEach { d ->
            if (d.id != null && d.id !in usedRefs) err("R14", "dictionary entry never used by any exercise/lesson", d.id)
        }

        // R17: stories
        p.stories.forEach { s ->
            if (s.title.isNullOrBlank()) err("R2", "story missing title", s.id)
            if (s.unlockAfterUnit == null || s.unlockAfterUnit < 1) err("R2", "story unlockAfterUnit invalid", s.id)
            if (s.nodes.isEmpty()) return@forEach // registered placeholder (spec §7)
            val byId = s.nodes.mapNotNull { n -> n.id?.let { it to n } }.toMap()
            s.nodes.forEach { node ->
                if (node.textIt.isNullOrBlank()) err("R17", "story node missing textIt", node.id)
                if (node.terminal) {
                    if (node.choices.isNotEmpty()) err("R17", "terminal node must not have choices", node.id)
                } else {
                    if (node.choices.size !in 2..3) err("R17", "non-terminal node needs 2-3 choices, got ${node.choices.size}", node.id)
                    if (node.choices.count { it.correct } != 1) err("R17", "node must have exactly one correct choice", node.id)
                    node.choices.forEach { c ->
                        if (c.text.isNullOrBlank()) err("R17", "choice missing text", node.id)
                        if (c.next !in byId.keys) err("R10", "story choice dangling next '${c.next}'", node.id)
                        if (!c.correct && c.feedbackEn.isNullOrBlank()) err("R17", "wrong choice missing feedbackEn", node.id)
                    }
                }
            }
            // reachability of a terminal from every node
            val terminalIds = s.nodes.filter { it.terminal }.mapNotNull { it.id }.toSet()
            if (terminalIds.isEmpty()) {
                err("R17", "story has no terminal node", s.id)
            } else {
                s.nodes.forEach { start ->
                    val visited = mutableSetOf<String>()
                    var frontier = listOfNotNull(start.id)
                    var reaches = false
                    while (frontier.isNotEmpty() && !reaches) {
                        val next = mutableListOf<String>()
                        for (id in frontier) {
                            if (!visited.add(id)) continue
                            if (id in terminalIds) { reaches = true; break }
                            byId[id]?.choices?.mapNotNullTo(next) { it.next }
                        }
                        frontier = next
                    }
                    if (!reaches) err("R17", "node cannot reach a terminal", start.id)
                }
            }
        }

        return ValidationResult(if (errors.isEmpty()) p else null, errors)
    }
}
