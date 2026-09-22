package dev.maia.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RollingRateTest {
    private var now = 0L

    private fun rate() = RollingRate { now }

    @Test
    fun `no speed until half a second has passed`() {
        val r = rate()
        r.add(1_000)
        now = 100_000_000
        r.add(1_000)
        assertEquals(0.0, r.bytesPerSecond(), 0.0)
    }

    @Test
    fun `steady flow reads as its speed`() {
        val r = rate()
        repeat(21) { r.add(100_000); now += 100_000_000 }
        assertEquals(1_000_000.0, r.bytesPerSecond(), 1.0)
    }

    @Test
    fun `a slowdown shows within the window rather than being averaged away`() {
        val r = rate()
        repeat(100) { r.add(1_000_000); now += 100_000_000 } // 10 MB/s for 10 s
        repeat(60) { r.add(10_000); now += 100_000_000 } // then 100 kB/s for 6 s
        assertEquals(100_000.0, r.bytesPerSecond(), 1_000.0)
    }

    @Test
    fun `eta rounds up and is absent without a rate`() {
        assertEquals(3L, RollingRate.etaSeconds(2_500_000, 1_000_000.0))
        assertNull(RollingRate.etaSeconds(2_500_000, 0.0))
        assertEquals(0L, RollingRate.etaSeconds(0, 1.0))
    }
}
