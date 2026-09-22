package dev.maia.audio

import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 brief criterion 10. The token shapes are the zipformer's: upper case
 * sentencepiece pieces, U+2581 opening each word.
 */
class WordTest {
    private val b = '▁'

    @Test
    fun `pieces join into words at the boundary mark`() {
        val words = Word.fromTokens(
            arrayOf("${b}DIN", "NER", "${b}WITH", "${b}SA", "M"),
            floatArrayOf(ln(0.9f), ln(0.95f), ln(0.99f), ln(0.4f), ln(0.97f)),
        )
        assertEquals(listOf("DINNER", "WITH", "SAM"), words.map { it.text })
    }

    @Test
    fun `a word is as sure as its least sure piece`() {
        val words = Word.fromTokens(
            arrayOf("${b}DIN", "NER", "${b}SA", "M"),
            floatArrayOf(ln(0.9f), ln(0.7f), ln(0.4f), ln(0.97f)),
        )
        assertEquals(0.7f, words[0].confidence, 1e-4f)
        assertEquals(0.4f, words[1].confidence, 1e-4f)
        assertTrue(words[0].confirmed)
        assertFalse(words[1].confirmed)
    }

    @Test
    fun `a leading piece without the mark still starts a word`() {
        val words = Word.fromTokens(arrayOf("AT", "${b}EIGHT"), floatArrayOf(0f, 0f))
        assertEquals(listOf("AT", "EIGHT"), words.map { it.text })
        assertEquals(1f, words[0].confidence, 0f)
    }

    @Test
    fun `a bare boundary piece opens the next word`() {
        val words = Word.fromTokens(arrayOf("${b}", "TEN"), floatArrayOf(ln(0.8f), ln(0.9f)))
        assertEquals(listOf("TEN"), words.map { it.text })
        assertEquals(0.8f, words[0].confidence, 1e-4f)
    }

    @Test
    fun `mismatched or missing scores give no words rather than wrong ones`() {
        assertTrue(Word.fromTokens(arrayOf("${b}A", "${b}B"), floatArrayOf(0f)).isEmpty())
        assertTrue(Word.fromTokens(emptyArray(), FloatArray(0)).isEmpty())
    }

    @Test
    fun `the old one argument partial still means no words`() {
        assertTrue(Transcript.Partial("DINNER").words.isEmpty())
    }
}
