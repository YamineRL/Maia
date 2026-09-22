package dev.maia.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rescore buffer, on its own because the arithmetic of growing and
 * capping is easy to get subtly wrong and invisible when it is.
 */
class FloatArrayListTest {

    @Test
    fun `grows past the initial capacity and preserves order`() {
        val list = FloatArrayList(initialCapacity = 16)
        // Distinct values across blocks, so a lost head or tail is visible.
        repeat(10) { block -> list.addAll(FloatArray(10) { (block * 10 + it + 1).toFloat() }) }

        val array = list.toArray()
        assertEquals(100, array.size)
        // The last sample of the last frame, to catch an off-by-one in the copy.
        assertEquals(100f, array.last())
        // And the very first, to catch a lost head.
        assertEquals(1f, array.first())
        // And the whole order, not just the ends.
        assertTrue("order was not preserved", (1..100).all { array[it - 1] == it.toFloat() })
    }

    @Test
    fun `an empty list yields an empty array`() {
        assertEquals(0, FloatArrayList().toArray().size)
        assertEquals(0, FloatArrayList().size)
    }

    @Test
    fun `toArray copies, so later growth cannot alias an earlier snapshot`() {
        val list = FloatArrayList(initialCapacity = 16)
        list.addAll(floatArrayOf(1f, 2f, 3f))
        val first = list.toArray()
        list.addAll(floatArrayOf(4f, 5f, 6f))
        assertEquals(3, first.size)
        assertEquals(6, list.toArray().size)
        assertEquals(6f, list.toArray().last())
    }
}
