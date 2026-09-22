package dev.maia.audio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunksTest {

    @Test
    fun `blank and empty text produce nothing`() {
        assertEquals(emptyList<String>(), SpeechChunks.split(""))
        assertEquals(emptyList<String>(), SpeechChunks.split("   \n  "))
    }

    @Test
    fun `a single sentence under the limit is one piece`() {
        assertEquals(
            listOf("Saved, dinner with Sam, Thursday at eight."),
            SpeechChunks.split("Saved, dinner with Sam, Thursday at eight."),
        )
    }

    @Test
    fun `sentences that fit together stay together`() {
        assertEquals(
            listOf("The sky is blue. Rain arrives at six."),
            SpeechChunks.split("The sky is blue. Rain arrives at six.", maxChars = 60),
        )
    }

    @Test
    fun `sentences split at their boundaries when they do not fit together`() {
        val pieces = SpeechChunks.split(
            "Leaves change colour as daylight shortens. Evergreens keep theirs.",
            maxChars = 45,
        )
        assertEquals(
            listOf("Leaves change colour as daylight shortens.", "Evergreens keep theirs."),
            pieces,
        )
        assertTrue(pieces.all { it.length <= 45 })
    }

    @Test
    fun `every piece stays within the limit even for a long answer`() {
        val text = "Photosynthesis is how plants turn light into sugar. " +
            "Chlorophyll absorbs red and blue light and reflects green, which is why leaves look green. " +
            "In autumn the chlorophyll breaks down first, so the yellows and oranges that were always " +
            "there finally show through."
        val pieces = SpeechChunks.split(text, maxChars = SpeechChunks.MAX_CHARS)
        assertTrue(pieces.size > 1)
        assertTrue("a piece escaped the limit: $pieces", pieces.all { it.length <= SpeechChunks.MAX_CHARS })
        // Nothing but whitespace was lost.
        assertEquals(text.filterNot { it.isWhitespace() }, pieces.joinToString("").filterNot { it.isWhitespace() })
    }

    @Test
    fun `an overlong sentence is cut at a word boundary`() {
        val words = (1..40).joinToString(" ") { "word$it" }
        val pieces = SpeechChunks.split(words, maxChars = 60)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= 60 })
        assertEquals(words, pieces.joinToString(" "))
    }

    @Test
    fun `a run with no spaces at all is cut at the limit rather than left whole`() {
        val run = "x".repeat(500)
        val pieces = SpeechChunks.split(run, maxChars = 200)
        assertEquals(listOf("x".repeat(200), "x".repeat(200), "x".repeat(100)), pieces)
    }

    @Test
    fun `a terminator inside a token is not a boundary`() {
        assertEquals(
            listOf("The version is 3.14 and it still rocks."),
            SpeechChunks.split("The version is 3.14 and it still rocks.", maxChars = 60),
        )
    }

    @Test
    fun `a closing quote stays with the sentence it closes`() {
        assertEquals(
            listOf("She said \"go now.\"", "Then she left."),
            SpeechChunks.split("She said \"go now.\" Then she left.", maxChars = 25),
        )
    }

    @Test
    fun `a newline is a boundary`() {
        assertEquals(
            listOf("First line", "Second line"),
            SpeechChunks.split("First line\nSecond line", maxChars = 12),
        )
    }

    @Test
    fun `leading and trailing whitespace never reaches a piece`() {
        assertEquals(
            listOf("Timer set for twelve minutes."),
            SpeechChunks.split("   Timer set for twelve minutes.   "),
        )
    }
}
