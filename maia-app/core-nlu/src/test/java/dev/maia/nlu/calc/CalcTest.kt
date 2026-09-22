package dev.maia.nlu.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind [dev.maia.nlu.Intent.Calculate].
 *
 * The grammar is small on purpose and the limits are the point of the type:
 * a remote model proposes the expression and the phone decides whether it is
 * arithmetic at all.
 */
class CalcTest {

    private fun ok(s: String): Double = (Calc.eval(s) as Calc.Ok).value

    @Test
    fun `operators respect precedence`() {
        assertEquals(11.0, ok("3+4*2"), 1e-9)
        assertEquals(14.0, ok("(3+4)*2"), 1e-9)
        assertEquals(96.0, ok("12*8"), 1e-9)
        assertEquals(3.0, ok("10-4-3"), 1e-9)
    }

    @Test
    fun `power is right associative and above unary minus`() {
        assertEquals(512.0, ok("2^3^2"), 1e-9)
        assertEquals(-4.0, ok("-2^2"), 1e-9)
        assertEquals(0.125, ok("2^-3"), 1e-9)
    }

    @Test
    fun `unary minus and plus work anywhere a value can start`() {
        assertEquals(2.0, ok("-3+5"), 1e-9)
        assertEquals(-7.0, ok("-(3+4)"), 1e-9)
        assertEquals(3.0, ok("--3"), 1e-9)
        assertEquals(5.0, ok("+5"), 1e-9)
    }

    @Test
    fun `division and remainder accept decimals`() {
        assertEquals(2.5, ok("10/4"), 1e-9)
        assertEquals(1.0, ok("10%3"), 1e-9)
        assertEquals(3.75, ok("1.5+2.25"), 1e-9)
        assertEquals(30.0, ok("15/100*200"), 1e-9)
    }

    @Test
    fun `division or remainder by zero is an error`() {
        assertTrue(Calc.eval("10/0") is Calc.Err)
        assertTrue(Calc.eval("10%0") is Calc.Err)
        assertTrue(Calc.eval("0/0") is Calc.Err)
    }

    @Test
    fun `malformed input is an error`() {
        for (s in listOf("", "1+", "(1+2", "1 2", "abc", "1..2", "^2", "2^", "()", "*3")) {
            assertTrue("[$s] should fail", Calc.eval(s) is Calc.Err)
        }
    }

    @Test
    fun `an expression over the length limit is refused`() {
        assertTrue(Calc.eval("1+".repeat(33)) is Calc.Err)
        assertTrue(Calc.eval("1".repeat(65)) is Calc.Err)
    }

    @Test
    fun `nesting past the depth limit is refused`() {
        assertTrue(Calc.eval("(".repeat(16) + "1" + ")".repeat(16)) is Calc.Ok)
        assertTrue(Calc.eval("(".repeat(17) + "1" + ")".repeat(17)) is Calc.Err)
    }

    @Test
    fun `intermediate results past the magnitude limit are refused`() {
        assertTrue(Calc.eval("1000000000000000") is Calc.Ok)
        assertTrue(Calc.eval("999999999999999+2") is Calc.Err)
        assertTrue(Calc.eval("10^20") is Calc.Err)
    }

    @Test
    fun `format renders whole numbers without a decimal point`() {
        assertEquals("30", Calc.format(30.0))
        assertEquals("0", Calc.format(-0.0))
        assertEquals("-7", Calc.format(-7.0))
    }

    @Test
    fun `format caps fraction digits and strips trailing zeros`() {
        assertEquals("2.5", Calc.format(2.5))
        assertEquals("0.333333", Calc.format(1.0 / 3.0))
        assertEquals("0.125", Calc.format(0.125))
        assertEquals("123456.7891", Calc.format(123456.7891))
    }
}
