package com.linguamod.app.core

/** Badge catalog (Stage 2B): cosmetic only, persisted in the `badges` Room table. */
object Badges {
    const val PRIMO_PASSO = "primo_passo"
    const val DIECI_UNITA = "dieci_unita"
    const val PERFEZIONISTA = "perfezionista"
    const val SETTIMANA_ITALIANA = "settimana_italiana"

    /** Stage 4: per-story and per-boss badges, awarded once each. */
    const val NARRATORE_PREFIX = "narratore_"
    const val BOSS_PREFIX = "boss_champion_"

    fun narratoreId(storyId: String) = "$NARRATORE_PREFIX$storyId"
    fun bossChampionId(bossUnit: Int) = "$BOSS_PREFIX$bossUnit"

    /** Display name for any badge, including dynamic story/boss ones. */
    fun displayNameFor(id: String): String = when {
        id.startsWith(NARRATORE_PREFIX) -> "Narratore"
        id.startsWith(BOSS_PREFIX) -> "Boss Champion ${id.removePrefix(BOSS_PREFIX)}"
        else -> ALL.firstOrNull { it.id == id }?.name ?: id
    }

    data class BadgeDef(val id: String, val name: String, val condition: String)

    val ALL = listOf(
        BadgeDef(PRIMO_PASSO, "Primo Passo", "Pass the Unit 1 checkpoint"),
        BadgeDef(DIECI_UNITA, "Dieci Unità", "Pass the Unit 10 checkpoint"),
        BadgeDef(PERFEZIONISTA, "Perfezionista", "Score 100% on any checkpoint"),
        BadgeDef(SETTIMANA_ITALIANA, "Settimana Italiana", "Reach a 7-day streak"),
    )
}
