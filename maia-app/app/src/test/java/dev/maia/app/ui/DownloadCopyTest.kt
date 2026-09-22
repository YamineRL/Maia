package dev.maia.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadCopyTest {
    private val mb = 1024L * 1024L

    @Test
    fun `the handoff's example line`() {
        val done = (32.4 * mb).toLong()
        val total = 70 * mb
        val rate = (total - done) / 40.0
        assertEquals("32.4 / 70.0 MB · ≈ 40 s left", DownloadCopy.line(done, total, rate))
    }

    @Test
    fun `no rate means no ETA rather than a wild one`() {
        assertEquals("0.0 / 70.0 MB", DownloadCopy.line(0, 70 * mb, 0.0))
        assertNull(DownloadCopy.eta(10 * mb, 0.0))
    }

    @Test
    fun `an unknown total shows only what has arrived`() {
        assertEquals("12.5 MB", DownloadCopy.line(12 * mb + mb / 2, -1, 1e6))
    }

    @Test
    fun `done never reads above total`() {
        assertEquals("70.0 / 70.0 MB", DownloadCopy.bytes(71 * mb, 70 * mb))
    }

    @Test
    fun `eta steps are coarse`() {
        assertEquals("≈ 5 s left", DownloadCopy.eta(1, 1.0))
        assertEquals("≈ 40 s left", DownloadCopy.eta(41, 1.0))
        assertEquals("≈ 1 min left", DownloadCopy.eta(60, 1.0))
        assertEquals("≈ 2 min left", DownloadCopy.eta(61, 1.0))
        assertEquals("≈ 1 h left", DownloadCopy.eta(3_600, 1.0))
        assertEquals("≈ 1 h 1 min left", DownloadCopy.eta(3_601, 1.0))
    }
}
