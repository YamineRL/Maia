package dev.maia.app.feel

/**
 * The handoff's haptic schedule as data, with no Android in it.
 *
 * Primitives rather than raw durations, because a primitive is the vendor's
 * own calibrated envelope for that phone's motor and a duration is a guess
 * about it. The scales and delays are the handoff's and are not knobs: they
 * were chosen so that face down in a trouser pocket, while walking, a commit
 * is told from a fault ten times out of ten. That is also why commit ends on
 * THUD, the one low frequency primitive and the only thing a pocket carries.
 * Commit rises and resolves; fault falls and does not.
 */
enum class Primitive { Click, Tick, Thud, LowTick }

/** One primitive, its scale, and its delay after the previous one. */
data class Pulse(val primitive: Primitive, val scale: Float, val delayMs: Int)

/**
 * What to play, twice over: as composition primitives, and as the one-shot
 * amplitude waveform used where the motor lacks them. Timings alternate off
 * and on starting with off, as `VibrationEffect.createWaveform` reads them.
 */
data class Pattern(val pulses: List<Pulse>, val timings: LongArray, val amplitudes: IntArray) {
    init {
        require(timings.size == amplitudes.size) { "timings and amplitudes differ in length" }
        require(pulses.all { it.scale in 0f..1f && it.delayMs >= 0 }) { "a pulse is out of range" }
    }

    override fun equals(other: Any?): Boolean = other is Pattern && pulses == other.pulses &&
        timings.contentEquals(other.timings) && amplitudes.contentEquals(other.amplitudes)

    override fun hashCode(): Int =
        (pulses.hashCode() * 31 + timings.contentHashCode()) * 31 + amplitudes.contentHashCode()
}

object Schedule {
    /** A switch bottoming out. On the press, not the release. Never repeats. */
    val invoke = Pattern(
        listOf(Pulse(Primitive.Click, 0.70f, 0)),
        longArrayOf(0, 18),
        intArrayOf(0, 200),
    )

    /** Beyond this the first word was slow to come, and the tick says so a little louder. */
    const val LATE_FIRST_PARTIAL_MS = 1_200L

    /** The first word landing. Once per session; the reducer owns "once". */
    fun firstPartial(firstPartialMs: Long?): Pattern {
        val late = firstPartialMs != null && firstPartialMs > LATE_FIRST_PARTIAL_MS
        return Pattern(
            listOf(Pulse(Primitive.LowTick, if (late) 0.50f else 0.35f, 0)),
            longArrayOf(0, 10),
            intArrayOf(0, if (late) 120 else 80),
        )
    }

    /** One step of the 600 ms hold: four of them, one every [HOLD_STEP_MS]. */
    fun holdStep(index: Int): Pattern {
        val step = index.coerceIn(0, HOLD_STEPS - 1)
        return Pattern(
            listOf(Pulse(Primitive.LowTick, 0.20f + 0.10f * step, 0)),
            longArrayOf(0, 10),
            intArrayOf(0, 60 + 30 * step),
        )
    }

    const val HOLD_STEPS = 4
    const val HOLD_STEP_MS = 150L

    /** A stamp: two dry taps, then something heavy setting down. */
    val commit = Pattern(
        listOf(
            Pulse(Primitive.Tick, 0.60f, 0),
            Pulse(Primitive.Tick, 0.60f, 40),
            Pulse(Primitive.Thud, 1.00f, 90),
        ),
        longArrayOf(0, 18, 40, 18, 90, 55),
        intArrayOf(0, 150, 0, 150, 0, 255),
    )

    /** Something failing to catch. Heavy first, diminishing, no resolution. */
    val fault = Pattern(
        listOf(
            Pulse(Primitive.Thud, 0.50f, 0),
            Pulse(Primitive.Tick, 0.40f, 70),
            Pulse(Primitive.Tick, 0.25f, 45),
        ),
        longArrayOf(0, 55, 70, 18, 45, 12),
        intArrayOf(0, 180, 0, 120, 0, 80),
    )

    // The six M8 patterns. `docs/M8-copy.md` section 4.
    //
    // Section 4.1 splits the USAGE_TOUCH rule that PRD section 11 states
    // absolutely, and it splits it on the reasoning the rule already gives.
    // "Every pattern is a direct consequence of the user's hand" stops being
    // true here: three of these fire minutes after the user last touched the
    // phone, and one can fire at three in the morning because a build
    // finished. Those three are notifications in the exact sense the attribute
    // means, so they are the notification channel's own vibration and Do Not
    // Disturb governs them. The first three below are still the hand's, now,
    // and stay USAGE_TOUCH. [notification] says which is which; nothing here
    // plays anything, so the split is data and not a second code path.

    /** One dry tap: it left the phone. The analogue of the first word landing. */
    val agentSent = Pattern(
        listOf(Pulse(Primitive.Tick, 0.50f, 0)),
        longArrayOf(0, 18),
        intArrayOf(0, 125),
    )

    /** Two identical taps, long gap. The gap is the meaning: second in line. */
    val agentQueued = Pattern(
        listOf(
            Pulse(Primitive.Tick, 0.50f, 0),
            Pulse(Primitive.Tick, 0.50f, 140),
        ),
        longArrayOf(0, 18, 140, 18),
        intArrayOf(0, 125, 0, 125),
    )

    /**
     * One low, soft thump and nothing after it. The only single THUD in the
     * product, and the distinct cancel haptic PRD section 7 requires and
     * section 11 leaves undefined.
     */
    val agentStopped = Pattern(
        listOf(Pulse(Primitive.Thud, 0.35f, 0)),
        longArrayOf(0, 55),
        intArrayOf(0, 90),
    )

    /**
     * Rises and resolves, like commit, but slower and softer at the start:
     * something finished, elsewhere. Deliberately not commit itself. A buzz
     * from a pocket four minutes after a conversation that felt identical to a
     * commit would read as "Maia just wrote to my calendar", which is the
     * wrong thought. Same grammar, different texture.
     */
    val agentEnded = Pattern(
        listOf(
            Pulse(Primitive.LowTick, 0.40f, 0),
            Pulse(Primitive.Tick, 0.55f, 110),
            Pulse(Primitive.Thud, 0.90f, 90),
        ),
        longArrayOf(0, 10, 110, 18, 90, 55),
        intArrayOf(0, 95, 0, 140, 0, 230),
    )

    /**
     * A knock: heavy, then two equal taps that do not diminish.
     *
     * THUD first and then no landing, against [agentEnded]'s rise into a THUD
     * last. That pair is the one the user is actually asked to tell apart with
     * the phone in a pocket, and low frequency is the only thing denim
     * transmits, so the discriminator is where the THUD sits.
     */
    val agentBlocked = Pattern(
        listOf(
            Pulse(Primitive.Thud, 0.70f, 0),
            Pulse(Primitive.Tick, 0.45f, 90),
            Pulse(Primitive.Tick, 0.45f, 90),
        ),
        longArrayOf(0, 55, 90, 18, 90, 18),
        intArrayOf(0, 180, 0, 115, 0, 115),
    )

    /**
     * [agentBlocked] plays again this long after, once, and then never again.
     *
     * The only repeating pattern in the product, because it is the only event
     * where a human being is actually needed right now by something that has
     * stopped. A third would be nagging, and a pattern that nags gets the
     * channel turned off, which costs the user every other notification too.
     */
    const val BLOCKED_REPEAT_MS = 20_000L

    /**
     * True for the patterns that arrive later and unprompted, which are the
     * notification's own vibration rather than the hand's. Section 4.1.
     */
    fun notification(pattern: Pattern): Boolean =
        pattern == agentEnded || pattern == agentBlocked || pattern == fault
}

/** How a phone plays a [Pattern]. */
enum class Rung { Composition, Waveform, Silent }

/**
 * The degradation ladder, as a function of what the phone reports.
 *
 * Silent when there is no motor. There is deliberately no rung for "the user
 * turned touch feedback off": on API 33 and later the system applies that
 * setting itself, because every vibration is sent as `USAGE_TOUCH`, and
 * second-guessing it here would be an override rather than a fallback.
 */
fun rung(hasVibrator: Boolean, sdkInt: Int, primitivesSupported: (Set<Primitive>) -> Boolean, pattern: Pattern): Rung = when {
    !hasVibrator -> Rung.Silent
    sdkInt >= COMPOSITION_API && primitivesSupported(pattern.pulses.map { it.primitive }.toSet()) -> Rung.Composition
    else -> Rung.Waveform
}

/** `VibrationEffect.Composition` arrived in API 30. */
const val COMPOSITION_API = 30

/** `VibrationAttributes.USAGE_TOUCH` on `vibrate` arrived in API 33. */
const val TOUCH_USAGE_API = 33
