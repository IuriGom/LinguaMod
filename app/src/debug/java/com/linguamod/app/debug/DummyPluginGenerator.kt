package com.linguamod.app.debug

import com.linguamod.app.plugin.CheckpointDto
import com.linguamod.app.plugin.DictEntryDto
import com.linguamod.app.plugin.ExampleDto
import com.linguamod.app.plugin.ExerciseDto
import com.linguamod.app.plugin.ExerciseTypes
import com.linguamod.app.plugin.LessonDto
import com.linguamod.app.plugin.LessonTypes
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.MetaDto
import com.linguamod.app.plugin.StoryDto
import com.linguamod.app.plugin.UnitDto
import kotlinx.serialization.json.Json

/**
 * Phase-2 readiness check (Stage 4 §6): generates a fully validator-clean
 * dummy plugin with [unitCount] units, used by the JVM readiness test and the
 * instrumented 60-unit render/jank journey. Lives in the debug source set
 * (like TestHooks/FakeClock): reachable from both test source sets, never in
 * a release APK.
 */
object DummyPluginGenerator {

    fun generate(unitCount: Int = 60): String =
        Json.encodeToString(LinguaPluginDto.serializer(), plugin(unitCount))

    fun plugin(unitCount: Int = 60): LinguaPluginDto {
        val dictionary = (1..unitCount).map { n ->
            DictEntryDto(
                id = "dw$n",
                word = "parola$n",
                translation = "word $n",
                partOfSpeech = "verb",
                examples = listOf(ExampleDto(it = "Parola $n esempio.", en = "Example $n.")),
                introducedInUnit = n,
            )
        }
        val units = (1..unitCount).map { n ->
            UnitDto(
                id = "u$n",
                number = n,
                title = "Dummy unit $n",
                lessons = LessonTypes.ORDER.mapIndexed { li, type ->
                    LessonDto(
                        id = "u${n}l${li + 1}",
                        type = type,
                        title = "Dummy lesson $n.${li + 1}",
                        // R18: mixed lessons of unit N ≥ 3 recycle an earlier word
                        dictionaryRefs = listOfNotNull(
                            "dw$n",
                            if (type == LessonTypes.MIXED && n >= 3) "dw${n - 1}" else null,
                        ),
                        exercises = (1..4).map { e -> mc("u${n}l${li + 1}e$e", "dw$n") },
                    )
                },
                checkpoint = CheckpointDto(
                    id = "u${n}cp",
                    exercises = (1..if (n == 60) 15 else 10).map { e -> mc("u${n}cpe$e", "dw$n") },
                ),
            )
        }
        val stories = listOf(
            StoryDto(id = "story1", title = "Dummy story 1", unlockAfterUnit = 5),
            StoryDto(id = "story2", title = "Dummy story 2", unlockAfterUnit = 15),
            StoryDto(id = "story3", title = "Dummy story 3", unlockAfterUnit = 28),
            StoryDto(id = "story4", title = "Dummy story 4", unlockAfterUnit = 45),
        )
        return LinguaPluginDto(
            formatVersion = 1,
            meta = MetaDto(
                id = "dummy$unitCount",
                language = "it",
                languageName = "Italian (dummy)",
                version = 1,
                description = "Generated $unitCount-unit dummy plugin for the Phase-2 readiness check.",
            ),
            units = units,
            dictionary = dictionary,
            stories = stories,
        )
    }

    private fun mc(id: String, ref: String) = ExerciseDto(
        id = id,
        type = ExerciseTypes.MULTIPLE_CHOICE,
        prompt = "Pick the translation",
        explanation = "Because.",
        dictionaryRefs = listOf(ref),
        question = "What does '$ref' mean?",
        options = listOf("alpha", "beta", "gamma", "delta"),
        correctIndex = 0,
    )
}
