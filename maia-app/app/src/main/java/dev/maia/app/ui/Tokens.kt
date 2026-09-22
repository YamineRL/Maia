package dev.maia.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maia.app.R

/**
 * The design handoff, as code.
 *
 * Values come from `docs/design/README.md` and the design system sheet beside
 * it, which says colours, sizes, weights and radii are final. Where a number
 * here disagrees with that README, the README is right and this is a bug.
 *
 * Separate from [MaiaTheme]'s MaterialTheme because the handoff forbids stock
 * Material 3 components: no Card, no TextField, no FilledButton, no ripple.
 * MaterialTheme stays for the M0 dictation screen, which is an instrument
 * rather than the product, and for the accessibility and inset plumbing that
 * comes with it. Everything in `dev.maia.app.card` draws from here instead.
 */
@Immutable
data class MaiaColours(
    val groundVoid: Color,
    val groundBase: Color,
    val surfaceSunken: Color,
    val surfaceRaised: Color,
    val surfaceField: Color,
    val lineHair: Color,
    val lineStrong: Color,
    val inkHigh: Color,
    val inkStrong: Color,
    val inkMid: Color,
    val inkLow: Color,
    val inkFaint: Color,
    val accentAperture: Color,
    val accentCore: Color,
    val faultNeutral: Color,
    /**
     * The diagonal hatch that marks a guessed row's ground.
     *
     * 7% on dark, 5% on light. The handoff is explicit that it drops in light
     * mode because at 7% on white the hatch competes with body text.
     */
    val hatch: Color,
    /**
     * Light is the one theme where elevation needs a shadow: white cannot get
     * lighter than white, so a raised surface on light also takes a hairline
     * edge. Dark keeps the handoff's "no shadows". See [raisedEdge].
     */
    val isLight: Boolean,
)

/**
 * Dark is the default and not a preference: the handoff says the product is
 * used at night and in transit far more than in daylight.
 */
val MaiaDark = MaiaColours(
    groundVoid = Color(0xFF08090B),
    groundBase = Color(0xFF0A0C0F),
    surfaceSunken = Color(0xFF0F1217),
    surfaceRaised = Color(0xFF12151B),
    surfaceField = Color(0xFF171B22),
    lineHair = Color(0xFF1E232B),
    lineStrong = Color(0xFF2A313B),
    inkHigh = Color(0xFFEDF0F4),
    inkStrong = Color(0xFFC3CAD4),
    inkMid = Color(0xFFA8B0BC),
    inkLow = Color(0xFF737C89),
    inkFaint = Color(0xFF4E5661),
    accentAperture = Color(0xFF6FD8E8),
    accentCore = Color(0xFFDDFBFF),
    faultNeutral = Color(0xFF9AA4AF),
    hatch = Color(0x12FFFFFF),
    isLight = false,
)

/**
 * The handoff authors seven light tokens and marks the variant "rarely used".
 * Those seven are exact. The rest are derived here, by the same steps the dark
 * ramp uses, and are marked as derived so that a future disagreement with the
 * designer is settled in their favour rather than by whoever wrote this line.
 *
 * Derived: groundVoid, surfaceSunken, lineHair, inkStrong, inkFaint,
 * accentCore, faultNeutral.
 *
 * Seven values are darker than the handoff, and on purpose: the handoff's
 * light ramp fails contrast. inkLow colours every 9.5 sp mono label, so it has
 * to reach 4.5:1 on groundBase, surfaceField and surfaceSunken (5.57, 5.28 and
 * 5.14 here; the handoff's #6E7681 gave 4.21 on groundBase). The orb draws in
 * inkFaint, accentAperture and accentCore, which as graphics need 3:1 (3.37,
 * 5.67 and 9.78). lineStrong edges controls and sits at 2.03; lineHair and
 * faultNeutral move with it. ContrastTest holds these numbers.
 */
val MaiaLight = MaiaColours(
    groundVoid = Color(0xFFFFFFFF),
    groundBase = Color(0xFFF4F5F7),
    surfaceSunken = Color(0xFFEAECF0),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceField = Color(0xFFEDEFF2),
    lineHair = Color(0xFFD3D8DF),
    lineStrong = Color(0xFFA7AFBA),
    inkHigh = Color(0xFF101317),
    inkStrong = Color(0xFF2C323A),
    inkMid = Color(0xFF4A5059),
    inkLow = Color(0xFF5B636E),
    inkFaint = Color(0xFF7E8691),
    accentAperture = Color(0xFF0A6B7A),
    accentCore = Color(0xFF04454F),
    faultNeutral = Color(0xFF5B636E),
    hatch = Color(0x0D101317),
    isLight = true,
)

/**
 * The 4 dp base and the two rules that are not spacing values: a row is at
 * least 72 dp tall and a touch target is at least 48 dp. Both are in the
 * handoff and both are the kind of number that quietly erodes.
 */
object MaiaSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 20.dp
    val xl = 26.dp
    val xxl = 40.dp

    /** Screen gutter. */
    val gutter = 20.dp
    val rowMinHeight = 72.dp
    val touchTarget = 48.dp

    /** Distance from the bottom edge to the last thing on the screen. */
    val footer = 26.dp
}

/** Rows and dividers have no radius. The aperture is the only circle. */
object MaiaRadius {
    val chip = 2.dp
    val button = 4.dp
    val card = 6.dp
    val sheet = 10.dp
    val screen = 20.dp
}

/**
 * One grotesque and one mono.
 *
 * The handoff names Archivo or Public Sans for the text face and JetBrains
 * Mono or IBM Plex Mono for the mono, and says "Not Roboto". No font files are
 * bundled yet, so these resolve to the platform faces and on a Pixel the text
 * face is Roboto: the type is the one part of the handoff this does not yet
 * honour, and it is deliberate rather than missed. Bundling the two OFL
 * families is an asset change with a licence file attached, and it belongs in
 * a commit that says so. When it happens, these two lines are the only ones
 * that change.
 *
 * The mono is load bearing regardless of which face fills it: every time,
 * date, duration and byte count in the product is set in it, which is what
 * makes digits line up down a column of rows.
 */
object MaiaFonts {
    // Archivo matches the handoff's specimens. The brief proposed Public Sans;
    // the pick is still design's, and changing it changes only these lines and
    // the files in res/font. Licences ship in assets/licences.
    val text: FontFamily = FontFamily(
        Font(R.font.archivo_regular, FontWeight.Normal),
        Font(R.font.archivo_medium, FontWeight.Medium),
    )
    val mono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    )
}

/**
 * The type scale, verbatim. Sizes are dp in the handoff and sp here, which is
 * the deliberate difference: a user who has scaled their system type is asking
 * for these to grow and the layouts below are written to let them.
 */
@Immutable
class MaiaType internal constructor() {
    val display = text(34.sp, FontWeight.Medium, (-0.022).em(34.0))
    val title = text(27.sp, FontWeight.Medium, (-0.018).em(27.0))
    val heading = text(22.sp, FontWeight.Medium, (-0.012).em(22.0))
    val read = text(25.sp, FontWeight.Normal)
    val body = text(15.sp, FontWeight.Normal)

    /** A model's answer: body 15% up, with room between lines for a paragraph. */
    val answer = text(17.25.sp, FontWeight.Normal).copy(lineHeight = 25.sp)
    val caption = text(13.sp, FontWeight.Normal)
    val action = text(16.5.sp, FontWeight.Medium)

    val dataLarge = mono(25.sp, FontWeight.Medium)
    val data = mono(20.sp, FontWeight.Medium)
    val dataSmall = mono(14.sp, FontWeight.Normal)

    /** The only positive letterspacing in the product. */
    val label = mono(9.5.sp, FontWeight.Normal, 0.20.em(9.5))

    private fun text(size: TextUnit, weight: FontWeight, tracking: TextUnit = 0.sp) =
        TextStyle(fontFamily = MaiaFonts.text, fontSize = size, fontWeight = weight, letterSpacing = tracking)

    private fun mono(size: TextUnit, weight: FontWeight, tracking: TextUnit = 0.sp) =
        TextStyle(fontFamily = MaiaFonts.mono, fontSize = size, fontWeight = weight, letterSpacing = tracking)
}

/** Tracking is quoted in em in the handoff; Compose wants it in sp. */
private fun Double.em(sizeSp: Double): TextUnit = (this * sizeSp).sp

val LocalMaiaColours = staticCompositionLocalOf { MaiaDark }
private val TypeScale = MaiaType()

object Maia {
    val colours: MaiaColours
        @Composable @ReadOnlyComposable get() = LocalMaiaColours.current

    val type: MaiaType get() = TypeScale
    val space get() = MaiaSpace
    val radius get() = MaiaRadius
}

/** Picks the palette. Nothing else in the handoff varies by theme. */
@Composable
fun maiaColours(dark: Boolean = isSystemInDarkTheme()): MaiaColours =
    if (dark) MaiaDark else MaiaLight

/**
 * The edge a raised surface needs on light and must not have on dark.
 *
 * surfaceRaised is #FFFFFF on #F4F5F7, which is 1.09:1: without help a sheet or
 * a dialog is a white shape on a near white ground. On light this adds a 1 dp
 * lineHair border and a small shadow in inkHigh, tuned towards the brief's
 * 0 1 2 at 8% plus 0 4 14 at 6% (the platform multiplies these colours by its
 * own shadow alphas, so the numbers are close rather than exact). On dark it
 * returns the modifier untouched, so dark stays pixel for pixel what it was.
 *
 * Goes before clip and background, since a shadow drawn inside the clip is
 * cut away with it.
 */
fun Modifier.raisedEdge(colours: MaiaColours, shape: Shape): Modifier =
    if (!colours.isLight) {
        this
    } else {
        this
            .shadow(3.dp, shape, clip = false, ambientColor = RaisedShadow, spotColor = RaisedShadow.copy(alpha = 0.4f))
            .border(1.dp, colours.lineHair, shape)
    }

private val RaisedShadow = Color(0xFF101317)
