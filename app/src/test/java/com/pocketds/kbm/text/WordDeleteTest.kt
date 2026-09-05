package com.pocketds.kbm.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How much to remove when a whole word is deleted at once.
 *
 * Counted from the text before the cursor, because that is all an input
 * connection will hand over — there is no "delete word" the other side can be
 * asked to do.
 */
class WordDeleteTest {

    @Test
    fun `deletes the word the cursor sits against`() {
        assertEquals(5, WordDelete.charsBefore("hello world"))
    }

    @Test
    fun `takes the space along with the word`() {
        // Deleting "world" but leaving its space behind means a second press
        // is needed to tidy up, which is not what anyone means by delete word.
        assertEquals(6, WordDelete.charsBefore("hello world "))
    }

    @Test
    fun `takes several spaces along with the word`() {
        assertEquals(8, WordDelete.charsBefore("hello world   "))
    }

    @Test
    fun `a single word is all of it`() {
        assertEquals(5, WordDelete.charsBefore("hello"))
    }

    @Test
    fun `nothing before the cursor deletes nothing`() {
        assertEquals(0, WordDelete.charsBefore(""))
    }

    @Test
    fun `whitespace alone is just the whitespace`() {
        assertEquals(3, WordDelete.charsBefore("   "))
    }

    @Test
    fun `punctuation is its own run`() {
        // Deleting "world!!!" whole would take more than asked for; the
        // punctuation goes first, then the word on the next press.
        assertEquals(3, WordDelete.charsBefore("hello world!!!"))
    }

    @Test
    fun `punctuation before a word is not swallowed with it`() {
        assertEquals(5, WordDelete.charsBefore("hello, world"))
    }

    @Test
    fun `a newline counts as whitespace`() {
        assertEquals(6, WordDelete.charsBefore("hello\nworld\n"))
    }

    @Test
    fun `an apostrophe stays inside its word`() {
        // "don't" is one word; deleting it should not leave "don" behind.
        assertEquals(5, WordDelete.charsBefore("don't"))
    }

    @Test
    fun `digits count as part of a word`() {
        assertEquals(4, WordDelete.charsBefore("port 8080"))
        // And do not split a run that mixes them.
        assertEquals(8, WordDelete.charsBefore("port8080"))
    }

    @Test
    fun `never asks to delete more than it was shown`() {
        val text = "abc"
        assertEquals(true, WordDelete.charsBefore(text) <= text.length)
    }
}
