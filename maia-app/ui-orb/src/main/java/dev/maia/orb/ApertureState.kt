package dev.maia.orb

/**
 * The six states of the aperture, with the pose each one settles into.
 *
 * Every number here is `STATES` in `docs/design/aperture.js`, copied rather
 * than chosen. `PoseTableTest` reads that file and fails if the two disagree,
 * so a design revision is a red build, not a quiet drift.
 *
 * Radius, rim and bottom are fractions of half the shorter side of the
 * drawing area. `gapHalf` is half the opening, in radians, centred at the top.
 */
enum class ApertureState(val pose: Pose) {
    Dormant(Pose(r = 0.338, gapHalf = 0.62, rim = 0.011, bottom = 0.010, lum = 0.34)),
    Listening(Pose(r = 0.62, gapHalf = 0.46, rim = 0.019, bottom = 0.013, lum = 1.0)),
    Thinking(Pose(r = 0.42, gapHalf = 0.10, rim = 0.014, bottom = 0.006, lum = 0.80)),

    /**
     * An agent is running on the user's machine, and may be for four minutes.
     *
     * Not `Thinking`, and the reason is the product's own reduced-motion rule:
     * with `animator_duration_scale = 0` every spring resolves on the first
     * frame and the poses must stay mutually distinguishable with no movement
     * at all. Frozen, a 400 millisecond parse and a four-minute agent run
     * wearing the same pose are the same picture, and they have opposite
     * affordances: one you wait through holding the phone, the other you put
     * the phone in your pocket for.
     */
    Working(Pose(r = 0.36, gapHalf = 0.06, rim = 0.012, bottom = 0.005, lum = 0.62, scribe = 1.0)),
    Speaking(Pose(r = 0.54, gapHalf = 0.20, rim = 0.017, bottom = 0.010, lum = 0.95)),
    Fault(Pose(r = 0.50, gapHalf = 0.0, rim = 0.013, bottom = 0.004, lum = 0.55, desat = 1.0, double = true)),
}

data class Pose(
    val r: Double,
    val gapHalf: Double,
    val rim: Double,
    val bottom: Double,
    val lum: Double,
    /**
     * The inner scribe circle's accent alpha, at 0.42 of the ring's radius.
     *
     * The identity draws this hairline in every frame of every state at 10
     * percent accent and no state used it for anything, which made it the one
     * free channel in the form. [ApertureState.Working] is the only state that
     * raises it, so the default leaves the other five exactly as they were.
     */
    val scribe: Double = 0.10,
    val desat: Double = 0.0,
    val double: Boolean = false,
)
