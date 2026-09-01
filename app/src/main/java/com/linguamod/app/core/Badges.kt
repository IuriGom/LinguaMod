package com.linguamod.app.core

/** Badge catalog (Stage 2B): cosmetic only, persisted in the `badges` Room table. */
object Badges {
    const val PRIMO_PASSO = "primo_passo"
    const val DIECI_UNITA = "dieci_unita"
    const val PERFEZIONISTA = "perfezionista"
    const val SETTIMANA_ITALIANA = "settimana_italiana"

    data class BadgeDef(val id: String, val name: String, val condition: String)

    val ALL = listOf(
        BadgeDef(PRIMO_PASSO, "Primo Passo", "Pass the Unit 1 checkpoint"),
        BadgeDef(DIECI_UNITA, "Dieci Unità", "Pass the Unit 10 checkpoint"),
        BadgeDef(PERFEZIONISTA, "Perfezionista", "Score 100% on any checkpoint"),
        BadgeDef(SETTIMANA_ITALIANA, "Settimana Italiana", "Reach a 7-day streak"),
    )
}
