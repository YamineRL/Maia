package dev.maia.orb

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Helpers from `docs/design/aperture.js`, ported with their names kept. */
internal object Signals {
    const val TAPS = 24
    const val LOBES = 12
    const val TAU = 2 * PI

    fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = min(1.0, max(0.0, (x - a) / (b - a)))
        return t * t * (3 - 2 * t)
    }

    fun angDist(a: Double, b: Double): Double {
        val d = abs(a - b) % TAU
        return if (d > PI) TAU - d else d
    }

    /**
     * The handoff's 24 angular microphone taps: one overall [level] in 0..1
     * spread unevenly around the rim as a deterministic function of [t].
     */
    fun micProfile(t: Double, level: Double, out: DoubleArray = DoubleArray(TAPS)): DoubleArray {
        val n = out.size
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val v = 0.5 + 0.5 * sin(t * 7.3 + p * 19.1) * sin(t * 3.1 + p * 31.7 + 1.1)
            out[i] = max(0.0, min(1.0, level * (0.35 + 0.9 * v)))
        }
        return out
    }

    /**
     * The handoff's stand-in speech envelope. Only previews use it: on the
     * phone the envelope comes from the voice, not from a formula.
     */
    fun speechEnv(t: Double): Double {
        val phrase = 3.1
        val u = t % phrase
        if (u > 2.44) return 0.35
        val k = u % 0.26
        val burst = if (k < 0.17) sin((k / 0.17) * PI) else 0.0
        val weight = 0.72 + 0.28 * sin(u * 2.1 + 1.3)
        return 0.35 + 0.65 * burst * weight
    }
}
