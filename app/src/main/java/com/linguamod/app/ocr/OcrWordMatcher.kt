package com.linguamod.app.ocr

import com.linguamod.app.plugin.AnswerMatcher

/**
 * Turns recognized OCR text into tappable words and lemma candidates for
 * dictionary lookup (Stage 4 §1). Tokenization is exactly spec §9
 * normalization: NFC, lowercase, punctuation stripped, apostrophes act as
 * token separators ("l'amore" → "l", "amore"). Pure JVM.
 */
object OcrWordMatcher {

    /** Tappable tokens of a recognized block, in reading order. */
    fun tokens(blockText: String): List<String> =
        AnswerMatcher.normalize(blockText).split(" ").filter { it.isNotBlank() }

    /**
     * Lemma candidates for a tapped token, most specific first: the token
     * itself, then — as a fallback for OCR output that glued an article to the
     * word ("lagatta") — the token with a leading article stripped.
     */
    fun lemmaCandidates(token: String): List<String> {
        val t = AnswerMatcher.normalize(token).replace(" ", "")
        if (t.isBlank()) return emptyList()
        val candidates = mutableListOf(t)
        for (article in LEADING_ARTICLES) {
            if (t.length > article.length && t.startsWith(article)) {
                candidates += t.removePrefix(article)
            }
        }
        return candidates
    }

    // Longest first so "gli" wins over "g"-prefixes; apostrophe forms are
    // already split by §9 tokenization and never reach this list.
    private val LEADING_ARTICLES = listOf(
        "gli", "della", "dello", "delle", "alla", "allo", "alle",
        "dal", "del", "nel", "sul", "una", "uno", "il", "lo", "la", "le", "un",
    )
}
