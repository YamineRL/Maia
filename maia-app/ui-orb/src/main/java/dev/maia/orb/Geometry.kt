package dev.maia.orb

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The ring's outline, `band()` in `docs/design/aperture.js`.
 *
 * Signal (mic, lobes, the thinking arc) is added outward only, so the inner
 * edge of the opening stays a clean circle and sound reads as pressure from
 * inside. That rule is the handoff's and the geometry test holds it.
 */
internal object Geometry {
    const val SAMPLES = 280
    /** Below this half gap the ring counts as closed and loses its taper. The shader reads it too. */
    const val CLOSED = 0.02

    /**
     * Fills [out] with the band as x, y pairs: the outer edge from one side
     * of the gap to the other, then the inner edge back. [u] is half the
     * shorter side in pixels, ([cx], [cy]) the centre. [fo] and [fi] scale
     * the outer and inner thickness, which is how the bloom layers widen.
     */
    fun band(
        f: Frame,
        u: Double,
        cx: Double,
        cy: Double,
        fo: Double,
        fi: Double,
        out: FloatArray = FloatArray(4 * (SAMPLES + 1)),
    ): FloatArray {
        val radius = f.r * u
        val gapHalf = max(0.0, f.gapHalf)
        val a0 = PI / 2 + gapHalf
        val span = 2 * PI - 2 * gapHalf
        val n = SAMPLES
        for (i in 0..n) {
            val a = a0 + span * i / n
            val t = max(0.3, outer(f, a, u, gapHalf) * fo)
            val rr = radius + t
            out[2 * i] = (cx + cos(a) * rr).toFloat()
            out[2 * i + 1] = (cy - sin(a) * rr).toFloat()
        }
        for (j in 0..n) {
            val i = n - j
            val a = a0 + span * i / n
            val t = max(0.3, base(f, a, u, gapHalf) * fi)
            val rr = max(1.0, radius - t)
            val o = 2 * (n + 1) + 2 * j
            out[o] = (cx + cos(a) * rr).toFloat()
            out[o + 1] = (cy - sin(a) * rr).toFloat()
        }
        return out
    }

    fun base(f: Frame, a: Double, u: Double, gapHalf: Double = f.gapHalf): Double {
        val taper = if (gapHalf < CLOSED) 1.0 else Signals.smoothstep(gapHalf, gapHalf + 0.30, Signals.angDist(a, PI / 2))
        return (f.rim + f.bottom * max(0.0, -sin(a))) * (0.46 + 0.54 * taper) * u
    }

    fun outer(f: Frame, a: Double, u: Double, gapHalf: Double = f.gapHalf): Double {
        var t = base(f, a, u, gapHalf)
        val n = f.mic.size
        val p = ((a % Signals.TAU) + Signals.TAU) % Signals.TAU / Signals.TAU * n
        val i = floor(p).toInt()
        val frac = p - i
        t *= 1 + f.micGain * (f.mic[i % n] * (1 - frac) + f.mic[(i + 1) % n] * frac)
        if (f.dash > 0) {
            // The lobe peaks swell with the voice while the troughs stay put.
            val peak = abs(sin((a - f.phase) * Signals.LOBES / 2)).pow(1.3)
            val lobed = (0.22 + 0.78 * peak) * (1 + f.swell * peak)
            t *= 1 + (lobed - 1) * f.dash
        }
        if (f.hotAmount > 0) {
            val d = hotWidthAt(f, a)
            t *= 1 + f.hotAmount * exp(-d * d)
        }
        return t
    }

    /**
     * Angular distance from the arc in units of its width. A comet is sharp
     * ahead, towards smaller angles since it travels clockwise, and long
     * behind.
     */
    fun hotWidthAt(f: Frame, a: Double): Double {
        if (!f.comet) return Signals.angDist(a, f.hotAngle) / f.hotWidth
        val d = wrap(a - f.hotAngle)
        return d / if (d < 0) Frame.COMET_HEAD else Frame.COMET_TAIL
    }

    /** [d] folded into -PI..PI. */
    fun wrap(d: Double): Double {
        val x = (d + PI) % Signals.TAU
        return (if (x < 0) x + Signals.TAU else x) - PI
    }
}
