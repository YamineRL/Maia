package dev.maia.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecificityTest {

    /**
     * The tie break reads these by ordinal, so the declaration order is part of
     * the contract rather than an accident of how the file was typed.
     */
    @Test
    fun `ranks low below medium below high`() {
        assertTrue(Specificity.Low < Specificity.Medium)
        assertTrue(Specificity.Medium < Specificity.High)
        assertEquals(Specificity.High, Specificity.entries.max())
    }
}
