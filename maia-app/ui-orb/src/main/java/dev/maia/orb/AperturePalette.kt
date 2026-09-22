package dev.maia.orb

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The aperture's colours and how they are laid down.
 *
 * [Dark] is light added onto a near-black ground, as the aperture has always
 * been drawn. [Light] is ink: the same geometry laid over the ground with
 * normal blending, a solid stroke and one faint halo, because additive light
 * on a pale ground washes out to nothing. The app picks one from its theme;
 * this module cannot see the theme, so it is passed in.
 *
 * [accent] and [core] are the ring and its brighter centre band. [faultAccent]
 * and [faultCore] are where each drains to as the fault desaturates.
 */
@Immutable
class AperturePalette(
    val ink: Boolean,
    val ground: Color,
    val accent: Color,
    val core: Color,
    val faultAccent: Color,
    val faultCore: Color,
) {
    internal val groundArgb = ground.toArgb()
    internal val accentArgb = accent.toArgb()
    internal val coreArgb = core.toArgb()
    internal val faultAccentArgb = faultAccent.toArgb()
    internal val faultCoreArgb = faultCore.toArgb()

    override fun equals(other: Any?): Boolean =
        other is AperturePalette && ink == other.ink && ground == other.ground && accent == other.accent &&
            core == other.core && faultAccent == other.faultAccent && faultCore == other.faultCore

    override fun hashCode(): Int =
        listOf(ink, ground, accent, core, faultAccent, faultCore).hashCode()

    companion object {
        /** Light on the dark ground, from `aperture.js`. */
        val Dark = AperturePalette(
            ink = false,
            ground = Color(ApertureColours.GROUND),
            accent = Color(ApertureColours.ACCENT),
            core = Color(ApertureColours.ACCENT_CORE),
            faultAccent = Color(ApertureColours.FAULT_NEUTRAL),
            faultCore = Color(ApertureColours.CORE_NEUTRAL),
        )

        /** Ink on the light ground, from `INK` in `aperture.js`. */
        val Light = AperturePalette(
            ink = true,
            ground = Color(ApertureColours.INK_GROUND),
            accent = Color(ApertureColours.INK),
            core = Color(ApertureColours.INK_CORE),
            faultAccent = Color(ApertureColours.INK_FAULT),
            faultCore = Color(ApertureColours.INK_FAULT_CORE),
        )
    }
}
