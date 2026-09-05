package com.pocketds.kbm.text

/**
 * How much to remove when a whole word is deleted at once.
 *
 * Counted from the text before the cursor, because that is all an input
 * connection will hand over — there is no "delete a word" the other side can be
 * asked to perform.
 */
object WordDelete {

    fun charsBefore(text: CharSequence): Int {
        var i = text.length

        // Trailing space goes with the word, not left behind for a second press.
        while (i > 0 && text[i - 1].isWhitespace()) i--
        if (i == 0) return text.length

        // Then one run of a single kind. Punctuation is its own run, so
        // deleting "world!!!" takes the marks first and the word after —
        // removing both at once takes more than was asked for.
        val wordly = isWordChar(text[i - 1])
        while (i > 0 && !text[i - 1].isWhitespace() && isWordChar(text[i - 1]) == wordly) i--

        return text.length - i
    }

    /** An apostrophe counts, so "don't" does not come apart into "don". */
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '\''
}
