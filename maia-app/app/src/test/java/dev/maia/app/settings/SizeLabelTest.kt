package dev.maia.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SizeLabelTest {
    @Test
    fun `under a gigabyte reads in whole megabytes`() {
        assertEquals("639 MB", sizeLabel(670_478_772L))
    }

    @Test
    fun `a gigabyte and above reads in tenths of a gigabyte`() {
        assertEquals("2.9 GB", sizeLabel(3_113_545_589L))
    }

    @Test
    fun `nothing reads as zero`() {
        assertEquals("0 MB", sizeLabel(0L))
    }
}
