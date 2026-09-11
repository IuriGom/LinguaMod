package com.linguamod.app.content

/**
 * Hardcoded reference paradigms (Stage 2+, extended in Stages 5–7).
 * The conjugation cross-check test fails if any plugin grammarNotes table or
 * exercise answer contradicts these tables. This file is the source of truth
 * for Italian verb forms in LinguaMod — extend it, never "fix" it silently.
 */
object ConjugationReference {

    data class Paradigm(val persons: List<String>) {
        val io get() = persons[0]; val tu get() = persons[1]; val luiLei get() = persons[2]
        val noi get() = persons[3]; val voi get() = persons[4]; val loro get() = persons[5]
    }

    val PRESENT = mapOf(
        "essere" to Paradigm(listOf("sono", "sei", "è", "siamo", "siete", "sono")),
        "avere" to Paradigm(listOf("ho", "hai", "ha", "abbiamo", "avete", "hanno")),
        "fare" to Paradigm(listOf("faccio", "fai", "fa", "facciamo", "fate", "fanno")),
        "andare" to Paradigm(listOf("vado", "vai", "va", "andiamo", "andate", "vanno")),
        "venire" to Paradigm(listOf("vengo", "vieni", "viene", "veniamo", "venite", "vengono")),
        // Stage 5 extensions
        "parlare" to Paradigm(listOf("parlo", "parli", "parla", "parliamo", "parlate", "parlano")),
        "mangiare" to Paradigm(listOf("mangio", "mangi", "mangia", "mangiamo", "mangiate", "mangiano")),
        "abitare" to Paradigm(listOf("abito", "abiti", "abita", "abitiamo", "abitate", "abitano")),
        "lavorare" to Paradigm(listOf("lavoro", "lavori", "lavora", "lavoriamo", "lavorate", "lavorano")),
        "studiare" to Paradigm(listOf("studio", "studi", "studia", "studiamo", "studiate", "studiano")),
        "ascoltare" to Paradigm(listOf("ascolto", "ascolti", "ascolta", "ascoltiamo", "ascoltate", "ascoltano")),
        "girare" to Paradigm(listOf("giro", "giri", "gira", "giriamo", "girate", "girano")),
        "prendere" to Paradigm(listOf("prendo", "prendi", "prende", "prendiamo", "prendete", "prendono")),
        "leggere" to Paradigm(listOf("leggo", "leggi", "legge", "leggiamo", "leggete", "leggono")),
        "scrivere" to Paradigm(listOf("scrivo", "scrivi", "scrive", "scriviamo", "scrivete", "scrivono")),
        "aprire" to Paradigm(listOf("apro", "apri", "apre", "apriamo", "aprite", "aprono")),
        "dormire" to Paradigm(listOf("dormo", "dormi", "dorme", "dormiamo", "dormite", "dormono")),
        "capire" to Paradigm(listOf("capisco", "capisci", "capisce", "capiamo", "capite", "capiscono")),
        "finire" to Paradigm(listOf("finisco", "finisci", "finisce", "finiamo", "finite", "finiscono")),
        "preferire" to Paradigm(listOf("preferisco", "preferisci", "preferisce", "preferiamo", "preferite", "preferiscono")),
        "conoscere" to Paradigm(listOf("conosco", "conosci", "conosce", "conosciamo", "conoscete", "conoscono")),
        "vedere" to Paradigm(listOf("vedo", "vedi", "vede", "vediamo", "vedete", "vedono")),
        "chiamare" to Paradigm(listOf("chiamo", "chiami", "chiama", "chiamiamo", "chiamate", "chiamano")),
        "uscire" to Paradigm(listOf("esco", "esci", "esce", "usciamo", "uscite", "escono")),
        "piacere" to Paradigm(listOf("piaccio", "piaci", "piace", "piacciamo", "piacete", "piacciono")),
        // Stage 6 extensions
        "volere" to Paradigm(listOf("voglio", "vuoi", "vuole", "vogliamo", "volete", "vogliono")),
        "potere" to Paradigm(listOf("posso", "puoi", "può", "possiamo", "potete", "possono")),
        "dovere" to Paradigm(listOf("devo", "devi", "deve", "dobbiamo", "dovete", "devono")),
    )

    /** Regular past participles by conjugation (Stage 6). */
    val PARTICIPLES = mapOf(
        "-are" to "ato", "-ere" to "uto", "-ire" to "ito",
        "essere" to "stato", "avere" to "avuto", "fare" to "fatto",
        "andare" to "andato", "venire" to "venuto", "leggere" to "letto",
        "scrivere" to "scritto", "prendere" to "preso", "aprire" to "aperto",
        "dire" to "detto", "vedere" to "visto", "bere" to "bevuto",
        "chiedere" to "chiesto", "chiudere" to "chiuso", "decidere" to "deciso",
        "mettere" to "messo", "perdere" to "perso", "rispondere" to "risposto",
        "rimanere" to "rimasto", "scendere" to "sceso", "vivere" to "vissuto",
        "nascere" to "nato", "morire" to "morto",
        "offrire" to "offerto", "scegliere" to "scelto", "succedere" to "successo",
        "tradurre" to "tradotto", "vincere" to "vinto", "piacere" to "piaciuto",
        "stare" to "stato", "dovere" to "dovuto", "potere" to "potuto",
        "volere" to "voluto", "conoscere" to "conosciuto", "crescere" to "cresciuto",
    )

    /** Verbs taking essere as auxiliary in passato prossimo (Stage 6, Unit 27). */
    val ESSERE_AUXILIARY = setOf(
        "andare", "venire", "partire", "arrivare", "uscire", "essere", "nascere",
        "morire", "restare", "stare", "diventare", "salire", "scendere", "entrare",
        "tornare", "cadere", "crescere", "rimanere", "piacere", "succedere",
    )

    /** Future tense, regular pattern + irregular stems (Stage 6, Unit 31). */
    val FUTURE = mapOf(
        "parlare" to Paradigm(listOf("parlerò", "parlerai", "parlerà", "parleremo", "parlerete", "parleranno")),
        "mangiare" to Paradigm(listOf("mangerò", "mangerai", "mangerà", "mangeremo", "mangerete", "mangeranno")),
        "andare" to Paradigm(listOf("andrò", "andrai", "andrà", "andremo", "andrete", "andranno")),
        "fare" to Paradigm(listOf("farò", "farai", "farà", "faremo", "farete", "faranno")),
        "essere" to Paradigm(listOf("sarò", "sarai", "sarà", "saremo", "sarete", "saranno")),
        "avere" to Paradigm(listOf("avrò", "avrai", "avrà", "avremo", "avrete", "avranno")),
    )

    /** Conditional present (Stage 7). */
    val CONDITIONAL = mapOf(
        "parlare" to Paradigm(listOf("parlerei", "parleresti", "parlerebbe", "parleremmo", "parlereste", "parlerebbero")),
        "andare" to Paradigm(listOf("andrei", "andresti", "andrebbe", "andremmo", "andreste", "andrebbero")),
        "fare" to Paradigm(listOf("farei", "faresti", "farebbe", "faremmo", "fareste", "farebbero")),
        "essere" to Paradigm(listOf("sarei", "saresti", "sarebbe", "saremmo", "sareste", "sarebbero")),
        "avere" to Paradigm(listOf("avrei", "avresti", "avrebbe", "avremmo", "avreste", "avrebbero")),
        "volere" to Paradigm(listOf("vorrei", "vorresti", "vorrebbe", "vorremmo", "vorreste", "vorrebbero")),
        "potere" to Paradigm(listOf("potrei", "potresti", "potrebbe", "potremmo", "potreste", "potrebbero")),
        "dovere" to Paradigm(listOf("dovrei", "dovresti", "dovrebbe", "dovremmo", "dovreste", "dovrebbero")),
    )

    /** Subjunctive present (Stage 7, Units 43-44). */
    val SUBJUNCTIVE_PRESENT = mapOf(
        "essere" to Paradigm(listOf("sia", "sia", "sia", "siamo", "siate", "siano")),
        "avere" to Paradigm(listOf("abbia", "abbia", "abbia", "abbiamo", "abbiate", "abbiano")),
        "andare" to Paradigm(listOf("vada", "vada", "vada", "andiamo", "andiate", "vadano")),
        "fare" to Paradigm(listOf("faccia", "faccia", "faccia", "facciamo", "facciate", "facciano")),
        "potere" to Paradigm(listOf("possa", "possa", "possa", "possiamo", "possiate", "possano")),
        "volere" to Paradigm(listOf("voglia", "voglia", "voglia", "vogliamo", "vogliate", "vogliano")),
        "dovere" to Paradigm(listOf("debba", "debba", "debba", "dobbiamo", "dobbiate", "debbano")),
    )

    /** Imperfect subjunctive (Stage 7, Unit 48). */
    val SUBJUNCTIVE_IMPERFECT = mapOf(
        "essere" to Paradigm(listOf("fossi", "fossi", "fosse", "fossimo", "foste", "fossero")),
        "avere" to Paradigm(listOf("avessi", "avessi", "avesse", "avessimo", "aveste", "avessero")),
        "fare" to Paradigm(listOf("facessi", "facessi", "facesse", "facessimo", "faceste", "facessero")),
    )

    /** Imperfect indicative (Stage 6, Unit 29). */
    val IMPERFECT = mapOf(
        "essere" to Paradigm(listOf("ero", "eri", "era", "eravamo", "eravate", "erano")),
        "avere" to Paradigm(listOf("avevo", "avevi", "aveva", "avevamo", "avevate", "avevano")),
        "fare" to Paradigm(listOf("facevo", "facevi", "faceva", "facevamo", "facevate", "facevano")),
        "parlare" to Paradigm(listOf("parlavo", "parlavi", "parlava", "parlavamo", "parlavate", "parlavano")),
    )
}
