package com.linguamod.app

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 8 Part A.1 — objective content audit of the bundled Italian plugin.
 *
 * Complements PluginValidator (§8 R9/R12) with mechanical noun checks the
 * structural validator does not cover: gender/ending spot rules, plural-article
 * logic, and apostrophe/elision constraints. Every violation across all rules
 * is collected into one report; the test fails with the full list.
 *
 * Note on rule 3: the lo-rule is applied to masculine nouns only — feminine
 * nouns always take la (le in the plural) regardless of initial, e.g.
 * "la scuola", "la stazione". That matches standard Italian and §8-R12.
 */
class ObjectiveAuditTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val baseFile = File("src/main/assets/plugins/it.lingua")

    // Same character classes as PluginValidator R12.
    private val loPattern = Regex("^(s[bcdfghjklmnpqrstvwxz]|z|gn|ps|x|y).*")
    private val vowelPattern = Regex("^[aeiouàèéìòù].*")

    private val oExceptions = setOf("mano", "radio", "auto", "foto", "moto")
    private val aExceptions = setOf("problema", "sistema", "tema", "cinema", "poeta", "clima", "programma", "dramma")

    @Test
    fun `every noun passes objective article gender and elision audit`() {
        val root = json.parseToJsonElement(baseFile.readText()).jsonObject
        val violations = mutableListOf<String>()

        for (el in root["dictionary"]!!.jsonArray) {
            val e = el.jsonObject
            if (e["partOfSpeech"]?.jsonPrimitive?.content != "noun") continue
            val id = e["id"]!!.jsonPrimitive.content
            val word = e["word"]!!.jsonPrimitive.content
            val w = word.lowercase()
            val article = e["article"]?.jsonPrimitive?.content
            val gender = e["gender"]?.jsonPrimitive?.content

            // Rule 1 — every noun has article + gender m/f.
            if (article.isNullOrEmpty()) violations.add("[R1] $id/$word: missing article")
            if (gender !in setOf("m", "f")) violations.add("[R1] $id/$word: gender is '$gender', expected m/f")

            // Rule 2 — gender/ending spot rules. Skip entries whose word is
            // not a simple noun form (phrases).
            if (!w.contains(" ") && !w.contains("-")) {
                if ((w.endsWith("zione") || w.endsWith("tà") || w.endsWith("tù")) && gender != "f") {
                    violations.add("[R2] $id/$word: ends -zione/-tà/-tù must be feminine, gender is '$gender'")
                }
                if (w.endsWith("o")) {
                    if (gender == "m" && w in oExceptions)
                        violations.add("[R2] $id/$word: in -o exception list ($oExceptions) but marked masculine")
                    if (gender != "m" && w !in oExceptions)
                        violations.add("[R2] $id/$word: ends -o must be masculine (exceptions: $oExceptions), gender is '$gender'")
                }
                if (w.endsWith("a")) {
                    if (gender == "f" && w in aExceptions)
                        violations.add("[R2] $id/$word: in -a exception list ($aExceptions) but marked feminine")
                    if (gender != "f" && w !in aExceptions)
                        violations.add("[R2] $id/$word: ends -a must be feminine (exceptions: $aExceptions), gender is '$gender'")
                }
            }

            if (article == null || gender !in setOf("m", "f")) continue
            val isLo = loPattern.matches(w)
            val isVowel = vowelPattern.matches(w)

            // Rules 3/4 — article-form audit + apostrophe/elision.
            when (article) {
                // singular definite
                "lo" -> if (!(gender == "m" && isLo && !isVowel))
                    violations.add("[R3] $id/$word: 'lo' only before masculine s+consonant/z/gn/ps/x/y")
                "il" -> if (!(gender == "m" && !isLo && !isVowel))
                    violations.add("[R3] $id/$word: 'il' only before masculine non-lo consonant initial")
                "la" -> if (!(gender == "f" && !isVowel))
                    violations.add("[R3] $id/$word: 'la' only before feminine consonant initial")
                "l'" -> if (!isVowel)
                    violations.add("[R4] $id/$word: l' only before vowels")
                // plural definite: gli for the lo-words and vowel-words
                "gli" -> if (!(gender == "m" && (isLo || isVowel)))
                    violations.add("[R3] $id/$word: 'gli' only before masculine lo-class or vowel-initial plurals")
                "i" -> if (!(gender == "m" && !isLo && !isVowel))
                    violations.add("[R3] $id/$word: 'i' only before masculine consonant-initial plurals (gli for lo-/vowel-words)")
                "le" -> if (gender != "f")
                    violations.add("[R3] $id/$word: 'le' only before feminine plurals")
                // singular indefinite
                "un" -> if (!(gender == "m" && !isLo))
                    violations.add("[R4] $id/$word: 'un' only before masculine words not in the lo-class (vowel or consonant)")
                "uno" -> if (!(gender == "m" && isLo))
                    violations.add("[R4] $id/$word: 'uno' only before masculine s+consonant/z/gn/ps/x/y")
                "una" -> if (!(gender == "f" && !isVowel))
                    violations.add("[R4] $id/$word: 'una' only before feminine consonant initial")
                "un'" -> if (!(gender == "f" && isVowel))
                    violations.add("[R4] $id/$word: un' only feminine before vowel")
                else -> violations.add("[R3] $id/$word: unknown article '$article'")
            }
        }

        val byRule = violations.groupingBy { it.substringBefore(']') }.eachCount()
        assertTrue(
            "Objective audit found ${violations.size} violation(s) $byRule:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
