package com.linguamod.app.plugin

import kotlinx.serialization.Serializable

/**
 * Lenient DTOs for the .lingua format (docs/LINGUA_FORMAT.md).
 * All fields nullable/with defaults so the validator (§8) can report precise
 * rule violations instead of the parser failing on the first missing field.
 * Wrong-typed fields still fail decode and are reported as R1 structure errors.
 */
@Serializable
data class LinguaPluginDto(
    val formatVersion: Int? = null,
    val meta: MetaDto? = null,
    val units: List<UnitDto> = emptyList(),
    val dictionary: List<DictEntryDto> = emptyList(),
    val stories: List<StoryDto> = emptyList(),
)

@Serializable
data class MetaDto(
    val id: String? = null,
    val language: String? = null,
    val languageName: String? = null,
    val version: Int? = null,
    val description: String? = null,
)

@Serializable
data class UnitDto(
    val id: String? = null,
    val number: Int? = null,
    val title: String? = null,
    val lessons: List<LessonDto>? = null,
    val checkpoint: CheckpointDto? = null,
)

@Serializable
data class LessonDto(
    val id: String? = null,
    val type: String? = null,
    val title: String? = null,
    val dictionaryRefs: List<String> = emptyList(),
    val grammarNotes: String? = null,
    val exercises: List<ExerciseDto> = emptyList(),
)

@Serializable
data class CheckpointDto(
    val id: String? = null,
    val exercises: List<ExerciseDto> = emptyList(),
)

@Serializable
data class ExerciseDto(
    val id: String? = null,
    val type: String? = null,
    val prompt: String? = null,
    val explanation: String? = null,
    val dictionaryRefs: List<String> = emptyList(),
    // multiple_choice / listening(choice)
    val question: String? = null,
    val options: List<String>? = null,
    val correctIndex: Int? = null,
    // fill_blank
    val sentence: String? = null,
    val answers: List<String>? = null,
    // translation
    val sourceIt: String? = null,
    val sourceEn: String? = null,
    val acceptedIt: List<String>? = null,
    val acceptedEn: List<String>? = null,
    // listening
    val mode: String? = null,
    val speakIt: String? = null,
    // speaking
    val targetIt: String? = null,
    val minAccuracy: Double? = null,
    // sentence_scramble
    val tokens: List<String>? = null,
    val correctSentence: String? = null,
)

@Serializable
data class DictEntryDto(
    val id: String? = null,
    val word: String? = null,
    val article: String? = null,
    val translation: String? = null,
    val partOfSpeech: String? = null,
    val gender: String? = null,
    val examples: List<ExampleDto> = emptyList(),
    val introducedInUnit: Int? = null,
)

@Serializable
data class ExampleDto(
    val it: String? = null,
    val en: String? = null,
)

@Serializable
data class StoryDto(
    val id: String? = null,
    val title: String? = null,
    val unlockAfterUnit: Int? = null,
    val nodes: List<StoryNodeDto> = emptyList(),
)

@Serializable
data class StoryNodeDto(
    val id: String? = null,
    val speaker: String? = null,
    val textIt: String? = null,
    val textEn: String? = null,
    val choices: List<StoryChoiceDto> = emptyList(),
    val terminal: Boolean = false,
)

@Serializable
data class StoryChoiceDto(
    val text: String? = null,
    val next: String? = null,
    val correct: Boolean = false,
    val feedbackEn: String? = null,
)

object ExerciseTypes {
    const val MULTIPLE_CHOICE = "multiple_choice"
    const val FILL_BLANK = "fill_blank"
    const val TRANSLATION_IT_EN = "translation_it_en"
    const val TRANSLATION_EN_IT = "translation_en_it"
    const val LISTENING = "listening"
    const val SPEAKING = "speaking"
    const val SENTENCE_SCRAMBLE = "sentence_scramble"
    val ALL = listOf(
        MULTIPLE_CHOICE, FILL_BLANK, TRANSLATION_IT_EN, TRANSLATION_EN_IT,
        LISTENING, SPEAKING, SENTENCE_SCRAMBLE,
    )
}

object LessonTypes {
    const val VOCABULARY = "vocabulary"
    const val GRAMMAR = "grammar"
    const val MIXED = "mixed"
    const val ORAL = "oral"
    val ORDER = listOf(VOCABULARY, GRAMMAR, MIXED, ORAL)
}
