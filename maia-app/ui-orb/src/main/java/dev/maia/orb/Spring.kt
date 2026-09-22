package dev.maia.orb

import kotlin.math.min
import kotlin.math.sqrt

data class SpringParams(val k: Double, val zeta: Double)

/**
 * `Spring` from `docs/design/aperture.js`, line for line.
 *
 * Semi-implicit Euler at a fixed 1/240 s substep, so the curve is the same at
 * 60, 90 and 120 Hz. A frame gap is clamped to 120 ms first: after a stall
 * the ring resumes from where it was instead of being flung by one huge step.
 */
class Spring(initial: Double, params: SpringParams) {
    var x = initial
        private set
    var v = 0.0
    var target = initial
    var k = params.k
        private set
    var zeta = params.zeta
        private set

    fun to(target: Double, params: SpringParams? = null) {
        this.target = target
        if (params != null) use(params)
    }

    fun use(params: SpringParams) {
        k = params.k
        zeta = params.zeta
    }

    /** Jumps to [value] at rest. Reduced motion is the only caller that should want this. */
    fun snap(value: Double) {
        x = value
        v = 0.0
        target = value
    }

    fun step(dt: Double): Double {
        var left = min(dt, MAX_DT)
        while (left > 0) {
            val h = min(left, SUBSTEP)
            val c = 2 * zeta * sqrt(k)
            v += (-k * (x - target) - c * v) * h
            x += v * h
            left -= h
        }
        return x
    }

    companion object {
        const val SUBSTEP = 1.0 / 240
        const val MAX_DT = 0.12
    }
}

/**
 * The spring table from the design handoff, keyed by the state being entered.
 *
 * The handoff specifies radius and gap for each forward transition and
 * nothing for the other channels, so rim, bottom, luminance and the effect
 * mixes ride the radius spring of the same transition. It also says nothing
 * about a cancel (listening straight to dormant), thinking to fault or a
 * retry back into listening. Keying by target covers those with the entered
 * state's own spring. Both choices are proposals awaiting the design seat,
 * recorded as the first open question in docs/M2-brief.md section 11.
 */
object Springs {
    val invokeR = SpringParams(210.0, 0.62)
    val invokeGap = SpringParams(260.0, 0.85)
    val contractR = SpringParams(320.0, 0.88)
    val contractGap = SpringParams(380.0, 0.92)
    val handoffR = SpringParams(180.0, 0.72)
    val settle = SpringParams(90.0, 1.0)

    /** Thinking to working: gives up urgency and settles in for the minutes. */
    val settleIn = SpringParams(120.0, 0.90)

    /** The centre lights, critically damped: no bounce on a channel this small. */
    val scribe = SpringParams(140.0, 1.00)

    /**
     * One 24 degree step of the working arc, per `session.next.tool.called`.
     *
     * Progress rather than a spinner: it moves because something happened. It
     * never claims to know how much is left, because nothing on the phone
     * does.
     */
    val workStep = SpringParams(200.0, 0.80)
    val shake = SpringParams(440.0, 0.42)
    val hold = SpringParams(150.0, 0.95)
    val tap = SpringParams(900.0, 0.55)

    /** The smoothed mic level and the listening weight: quick, never overshooting. */
    val follow = SpringParams(260.0, 1.0)

    /** Speaking's lobe walk, one half-lobe step per syllable. */
    val lobeStep = SpringParams(140.0, 0.85)

    /** The arc's rate, easing between thinking's and working's. */
    val arcRate = SpringParams(40.0, 1.0)

    fun body(entering: ApertureState): SpringParams = when (entering) {
        ApertureState.Dormant -> settle
        ApertureState.Listening -> invokeR
        ApertureState.Thinking -> contractR
        ApertureState.Working -> settleIn
        ApertureState.Speaking -> handoffR
        ApertureState.Fault -> hold
    }

    fun gap(entering: ApertureState): SpringParams = when (entering) {
        ApertureState.Listening -> invokeGap
        ApertureState.Thinking -> contractGap
        else -> body(entering)
    }
}
