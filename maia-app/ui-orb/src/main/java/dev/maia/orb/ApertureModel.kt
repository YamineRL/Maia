package dev.maia.orb

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything a renderer needs for one frame, and nothing it has to work out.
 *
 * Both renderers are pure functions of this, which is what makes "do the
 * shader and the canvas agree" a screenshot question and "is the motion
 * right" a JVM test.
 */
class Frame(
    val r: Double,
    val gapHalf: Double,
    val rim: Double,
    val bottom: Double,
    val lum: Double,
    val desat: Double,
    /** Opacity of the second concentric ring, 0 to 1. The handoff's `double`. */
    val double: Double,
    /**
     * The inner scribe circle's accent alpha. 0.10 in five states, and raised
     * only by [ApertureState.Working], which is the only state with a bright
     * inner circle and is told from every other one by that alone.
     */
    val scribe: Double,
    /** Lateral offset in dp. Only the fault shake moves it. */
    val offsetX: Double,
    /** [Signals.TAPS] values in 0..1, all zero outside listening. */
    val mic: DoubleArray,
    /** How far the rim has broken into [Signals.LOBES] lobes, 0 to 1. */
    val dash: Double,
    /** Where the thinking arc sits, in radians, and how bright it is. */
    val hotAngle: Double,
    val hotAmount: Double,
    /**
     * Whether the arc is a comet: a sharp head of [COMET_HEAD] ahead of
     * [hotAngle] and a long tail of [COMET_TAIL] behind it, instead of the
     * symmetric [HOT_WIDTH]. Live frames only; the frozen arc stays symmetric.
     */
    val comet: Boolean = false,
    /** How far a microphone tap pushes the rim out. */
    val micGain: Double = MIC_GAIN_STILL,
    /** Scales the bloom, 1 outside listening, where it follows the voice. */
    val glow: Double = 1.0,
    /** Where the speaking lobes sit, in radians. */
    val phase: Double = 0.0,
    /** How far the speaking lobe peaks swell, 0 to 1, the speech envelope. */
    val swell: Double = 0.0,
    /**
     * Progress of the one echo ring that leaves the rim on entering a state,
     * 0 to 1. At 1 or beyond there is no echo.
     */
    val echo: Double = Double.POSITIVE_INFINITY,
) {
    val hotWidth get() = HOT_WIDTH

    internal fun copy(
        r: Double = this.r,
        gapHalf: Double = this.gapHalf,
        rim: Double = this.rim,
        lum: Double = this.lum,
    ) = Frame(
        r, gapHalf, rim, bottom, lum, desat, double, scribe, offsetX, mic, dash, hotAngle, hotAmount,
        comet, micGain, glow, phase, swell, echo,
    )

    companion object {
        const val HOT_WIDTH = 0.45
        const val COMET_HEAD = 0.26
        const val COMET_TAIL = 0.95
        const val MIC_GAIN_STILL = 0.85
        const val MIC_GAIN_LIVE = 2.0
    }
}

/**
 * The aperture's motion: poses from [ApertureState], springs from [Springs],
 * and the live signals layered on top, as `poseFor` in `aperture.js` does.
 *
 * Not thread safe and not meant to be. One instance belongs to one
 * composable and is stepped once per vsync on the main thread.
 */
class ApertureModel(initial: ApertureState = ApertureState.Dormant) {
    var state = initial
        private set

    private val start = initial.pose
    private val r: Spring = Spring(start.r, Springs.body(initial))
    private val gap: Spring = Spring(start.gapHalf, Springs.gap(initial))
    private val rim: Spring = Spring(start.rim, Springs.body(initial))
    private val bottom: Spring = Spring(start.bottom, Springs.body(initial))
    private val lum: Spring = Spring(start.lum, Springs.body(initial))
    private val desat: Spring = Spring(start.desat, Springs.body(initial))
    private val ring: Spring = Spring(if (start.double) 1.0 else 0.0, Springs.body(initial))
    private val dash: Spring = Spring(if (initial == ApertureState.Speaking) 1.0 else 0.0, Springs.body(initial))
    private val hot: Spring = Spring(if (initial in HOT_STATES) HOT_AMOUNT else 0.0, Springs.body(initial))

    /** Its own spring, because the centre lights on its own timing. */
    private val scribe: Spring = Spring(start.scribe, Springs.scribe)

    /**
     * Where the working arc has been stepped to, in radians, cumulative.
     *
     * A spring rather than a number so a burst of tool calls reads as motion
     * and not as teleporting, and cumulative so the arc never goes backwards:
     * an arc that retraced would be a claim that work was undone.
     */
    private val work: Spring = Spring(0.0, Springs.workStep)
    private val shake: Spring = Spring(0.0, Springs.shake)
    private val taps: Array<Spring> = Array(Signals.TAPS) { Spring(0.0, Springs.tap) }

    private val body: List<Spring> = listOf(r, rim, bottom, lum, desat, ring, dash, hot)
    private val tapTargets: DoubleArray = DoubleArray(Signals.TAPS)
    private var sinceFault = Double.POSITIVE_INFINITY

    /** The microphone level, smoothed, and how far into listening the form is. */
    private val level: Spring = Spring(0.0, Springs.follow)
    private val listening: Spring = Spring(if (initial == ApertureState.Listening) 1.0 else 0.0, Springs.follow)

    /** The speaking lobes' angle, stepped half a lobe per syllable. */
    private val phase: Spring = Spring(0.0, Springs.lobeStep)
    private var lastEnv = 0.0

    /**
     * The arc's angle before tool steps, integrated from a rate that eases
     * between thinking's and working's rather than jumping, so the hand-off
     * from one to the other is one continuous motion.
     */
    private var arc = staticArc(initial)
    private val rate: Spring = Spring(revPerSecond(initial), Springs.arcRate)
    private var flare = 0.0
    private var echo = Double.POSITIVE_INFINITY

    /**
     * One tool call: the arc steps [WORK_STEP] and no further.
     *
     * A fixed step rather than a fraction of anything, because there is no
     * denominator: nothing on the phone knows how many tool calls a turn will
     * make, and a bar that fills to an invented total is a lie the user can
     * see through the second time.
     */
    fun advanceWork() {
        work.target -= WORK_STEP
        flare = 1.0
    }

    fun setState(next: ApertureState) {
        if (next == state) return
        val was = state
        state = next
        if (next in ECHO_STATES) echo = 0.0
        if (next in HOT_STATES && was !in HOT_STATES) {
            // Born at the opening as it closes, then carried round.
            arc = PI / 2 - work.x
            rate.snap(revPerSecond(next))
        }
        body.forEach { it.use(Springs.body(next)) }
        gap.use(Springs.gap(next))
        if (next == ApertureState.Fault) {
            // The shake is a spring with a kick, not a keyframe. The kick is
            // sized so the first swing peaks at SHAKE_DP.
            shake.use(Springs.shake)
            shake.v = shakeImpulse(SHAKE_DP, Springs.shake)
            sinceFault = 0.0
        }
    }

    /**
     * Advances [dt] seconds and returns the frame for [time].
     *
     * [micLevel] and [speech] are 0..1 and matter only in listening and
     * speaking. With [reducedMotion] every channel snaps to the state's
     * static pose, the handoff's `STATIC_NOTE`, so the five states stay
     * distinguishable with nothing moving.
     */
    fun frame(
        dt: Double,
        time: Double,
        micLevel: Double = 0.0,
        speech: Double = 0.0,
        reducedMotion: Boolean = false,
    ): Frame = if (reducedMotion) still() else live(dt, time, micLevel, speech)

    private fun live(dt: Double, time: Double, micLevel: Double, speech: Double): Frame {
        val pose = state.pose
        val env = if (state == ApertureState.Speaking) speech else 0.0
        level.target = if (state == ApertureState.Listening) micLevel else 0.0
        listening.target = if (state == ApertureState.Listening) 1.0 else 0.0
        level.step(dt)
        listening.step(dt)
        val heard = level.x.coerceAtLeast(0.0)

        r.target = pose.r
        lum.target = pose.lum
        gap.target = pose.gapHalf
        when (state) {
            ApertureState.Dormant -> {
                val b = sin(time * 2 * PI * BREATH_HZ)
                r.target = pose.r * (1 + BREATH * b)
                lum.target = pose.lum * (1 + BREATH_LUM * b)
                gap.target = pose.gapHalf - BREATH_GAP * b
            }
            ApertureState.Listening -> {
                r.target = pose.r * (1 + LISTEN_R * heard)
                gap.target = pose.gapHalf + LISTEN_GAP * heard
            }
            ApertureState.Speaking -> {
                r.target = pose.r * (1 + 0.04 * speech)
                lum.target = pose.lum * (0.82 + 0.18 * speech)
            }
            else -> Unit
        }
        rim.target = pose.rim
        bottom.target = pose.bottom
        desat.target = pose.desat
        ring.target = if (pose.double) 1.0 else 0.0
        dash.target = if (state == ApertureState.Speaking) 1.0 else 0.0
        hot.target = if (state in HOT_STATES) HOT_AMOUNT else 0.0
        scribe.target = pose.scribe

        if (state == ApertureState.Listening) {
            Signals.micProfile(time, micLevel, tapTargets)
        } else {
            tapTargets.fill(0.0)
        }

        sinceFault += dt
        if (sinceFault >= SHAKE_S) shake.use(Springs.hold)

        body.forEach { it.step(dt) }
        gap.step(dt)
        scribe.step(dt)
        work.step(dt)
        shake.step(dt)
        val mic = DoubleArray(Signals.TAPS) { i ->
            taps[i].target = tapTargets[i]
            taps[i].step(dt).coerceIn(0.0, 1.0)
        }

        // A syllable is the envelope rising through the onset; each one walks
        // the lobes half a lobe on, so they move because something was said.
        if (state == ApertureState.Speaking && lastEnv < LOBE_ONSET && env >= LOBE_ONSET) phase.target -= LOBE_STEP
        lastEnv = env
        phase.step(dt)
        flare *= exp(-FLARE_DECAY * dt)
        if (echo < 1) echo += dt / ECHO_S
        rate.target = revPerSecond(state)
        rate.step(dt)
        // Working drifts at a sixth of thinking's rate and is carried the
        // rest of the way by [advanceWork]. A long single tool call still
        // moves, so it does not look like a hang.
        if (state in HOT_STATES) arc -= rate.x * 2 * PI * dt

        return Frame(
            r = r.x,
            gapHalf = gap.x.coerceAtLeast(0.0),
            rim = rim.x,
            bottom = bottom.x,
            lum = lum.x.coerceIn(0.0, 1.0),
            desat = desat.x.coerceIn(0.0, 1.0),
            double = ring.x.coerceIn(0.0, 1.0),
            scribe = scribe.x.coerceIn(0.0, 1.0),
            offsetX = shake.x,
            mic = mic,
            dash = dash.x.coerceIn(0.0, 1.0),
            hotAngle = arc + work.x,
            hotAmount = hot.x.coerceAtLeast(0.0) * (1 + FLARE * flare),
            comet = true,
            micGain = Frame.MIC_GAIN_LIVE,
            glow = 1 - (1 - LISTEN_GLOW_FLOOR) * listening.x.coerceIn(0.0, 1.0) + LISTEN_GLOW * heard,
            phase = phase.x,
            swell = env,
            echo = echo,
        )
    }

    private fun still(): Frame {
        val f = staticFrame(state)
        r.snap(f.r); gap.snap(f.gapHalf); rim.snap(f.rim); bottom.snap(f.bottom)
        lum.snap(f.lum); desat.snap(f.desat); ring.snap(f.double); dash.snap(f.dash)
        hot.snap(f.hotAmount); shake.snap(0.0); scribe.snap(f.scribe); work.snap(work.target)
        taps.forEachIndexed { i, s -> s.snap(f.mic[i]) }
        sinceFault = Double.POSITIVE_INFINITY
        level.snap(0.0)
        listening.snap(if (state == ApertureState.Listening) 1.0 else 0.0)
        phase.snap(0.0)
        rate.snap(revPerSecond(state))
        arc = f.hotAngle - work.x
        flare = 0.0
        echo = Double.POSITIVE_INFINITY
        return f
    }

    companion object {
        /**
         * Dormant's breath, `BREATH` in `aperture.js`: radius and luminance
         * swell together and the opening narrows on the inhale. Until
         * 2026-09-22 it was the radius alone at 2.5% and 0.16 Hz, which read
         * as a still image on the state people see most.
         */
        const val BREATH = 0.045
        const val BREATH_LUM = 0.20
        const val BREATH_GAP = 0.05
        const val BREATH_HZ = 0.20

        /** Listening, `LISTEN` in `aperture.js`: the whole form answers the voice. */
        const val LISTEN_R = 0.03
        const val LISTEN_GAP = 0.09
        const val LISTEN_GLOW_FLOOR = 0.75
        const val LISTEN_GLOW = 0.55

        /** Speaking: the lobes step this far when the envelope rises through [LOBE_ONSET]. */
        const val LOBE_STEP = PI / 12
        const val LOBE_ONSET = 0.62

        /** A tool call brightens the arc by this much, decaying at this rate per second. */
        const val FLARE = 0.6
        const val FLARE_DECAY = 3.2

        /** How long the entry echo takes to leave the rim, and the states that send one. */
        const val ECHO_S = 0.75
        val ECHO_STATES = setOf(ApertureState.Listening, ApertureState.Speaking, ApertureState.Working)

        const val THINKING_REV_S = 1.15

        /** Working's drift, a sixth of thinking's, from `aperture.js`. */
        const val WORKING_REV_S = 0.18

        /** Twenty-four degrees, in radians. */
        const val WORK_STEP = PI / 7.5

        /** The two states that carry a bright arc. */
        val HOT_STATES = setOf(ApertureState.Thinking, ApertureState.Working)
        const val HOT_AMOUNT = 1.5
        const val SHAKE_DP = 6.0
        const val SHAKE_S = 0.26

        /** `poseFor(name, t, 'static')` from `aperture.js`. */
        fun staticFrame(state: ApertureState): Frame {
            val p = state.pose
            return Frame(
                r = p.r,
                gapHalf = p.gapHalf,
                rim = p.rim,
                bottom = p.bottom,
                lum = if (state == ApertureState.Speaking) p.lum * (0.82 + 0.18 * STATIC_SPEECH) else p.lum,
                desat = p.desat,
                double = if (p.double) 1.0 else 0.0,
                scribe = p.scribe,
                offsetX = 0.0,
                mic = if (state == ApertureState.Listening) {
                    Signals.micProfile(STATIC_MIC_T, STATIC_MIC_LEVEL)
                } else {
                    DoubleArray(Signals.TAPS)
                },
                dash = if (state == ApertureState.Speaking) 1.0 else 0.0,
                hotAngle = staticArc(state),
                hotAmount = if (state in HOT_STATES) HOT_AMOUNT else 0.0,
            ).let {
                if (state == ApertureState.Speaking) it.copy(r = p.r * (1 + 0.04 * STATIC_SPEECH)) else it
            }
        }

        /**
         * Where the single bright arc sits when motion is off, from
         * `aperture.js`. Angles are the renderer's: measured from the right,
         * rising anticlockwise on screen, with the opening centred at PI / 2.
         *
         * `Working` is diametrically opposite `Thinking` and that is the whole
         * point of it. Frozen, the two share a pose that differs only in the
         * inner scribe circle, and the design system does not let a state rest
         * on one channel. Putting the arcs on opposite sides of the rim
         * separates them a second way, for a reader who cannot see a faint
         * alpha difference or who has motion turned off.
         *
         * `PoseTableTest` reads both constants out of `aperture.js` and fails
         * if either of these disagrees, which is new: until 2026-09-20 the
         * static arc was the one channel the handoff test did not cover, so
         * the prototype and this file could drift on it indefinitely.
         */
        const val ARC_THINKING = 0.7
        val ARC_WORKING = ARC_THINKING + PI

        private fun revPerSecond(state: ApertureState) =
            if (state == ApertureState.Working) WORKING_REV_S else THINKING_REV_S

        /** The frozen arc for a state, which only the two hot ones have. */
        fun staticArc(state: ApertureState): Double =
            if (state == ApertureState.Working) ARC_WORKING else ARC_THINKING

        private const val STATIC_MIC_T = 4.21
        private const val STATIC_MIC_LEVEL = 0.6
        private const val STATIC_SPEECH = 0.8

        /**
         * The initial velocity that makes an underdamped spring released from
         * rest at zero first peak at [peak]. Closed form, so the kick follows
         * the handoff's k and zeta if they change.
         */
        internal fun shakeImpulse(peak: Double, p: SpringParams): Double {
            val w = sqrt(p.k)
            val wd = w * sqrt(1 - p.zeta * p.zeta)
            val tp = atan2(wd, p.zeta * w) / wd
            return peak * wd / (exp(-p.zeta * w * tp) * sin(wd * tp))
        }
    }
}
