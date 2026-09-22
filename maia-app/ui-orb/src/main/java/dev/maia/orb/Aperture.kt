package dev.maia.orb

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The aperture. [micLevel], [speech] and [dock] are read once per frame, so
 * pass lambdas over state rather than values, or every level change
 * recomposes.
 *
 * [palette] picks light on the dark ground or ink on the light one. [dock]
 * runs from 0 at hero size to 1 in the 64 dp corner: the form is compressed
 * towards a common radius and its rim is floored at 1 dp, so every state
 * stays legible that small. [toolCalls] is a running count; each increase
 * steps the working arc once, as [ApertureModel.advanceWork] does.
 */
@Composable
fun Aperture(
    state: ApertureState,
    modifier: Modifier = Modifier,
    micLevel: () -> Float = { 0f },
    speech: () -> Float = { 0f },
    paintGround: Boolean = true,
    palette: AperturePalette = AperturePalette.Dark,
    dock: () -> Float = { 0f },
    toolCalls: Int = 0,
) = Aperture(state, modifier, micLevel, speech, paintGround, palette, dock, toolCalls, forceCanvas = false)

/**
 * The same aperture with the renderer choice exposed. [forceCanvas] draws
 * with [CanvasAperture] even where the shader is available, which is how the
 * two are put side by side for criterion 16. Internal so the public
 * signature stays as it was.
 */
@Composable
internal fun Aperture(
    state: ApertureState,
    modifier: Modifier,
    micLevel: () -> Float,
    speech: () -> Float,
    paintGround: Boolean,
    palette: AperturePalette,
    dock: () -> Float,
    toolCalls: Int,
    forceCanvas: Boolean,
) {
    val context = LocalContext.current
    val model = remember { ApertureModel(state) }
    val renderer = remember(forceCanvas) { apertureRenderer(forceCanvas) }
    val reduced = remember { reducedMotion(context) }
    var frame by remember { mutableStateOf(ApertureModel.staticFrame(state)) }
    val seenCalls = remember { intArrayOf(toolCalls) }

    LaunchedEffect(state) { model.setState(state) }
    LaunchedEffect(toolCalls) {
        // A count rather than an event, so a recomposition cannot replay a
        // step. A count that goes down is a new run starting from zero.
        repeat(toolCalls - seenCalls[0]) { model.advanceWork() }
        seenCalls[0] = toolCalls
    }
    LaunchedEffect(model, reduced) {
        var first = -1L
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (first < 0) first = now
                val dt = if (last < 0) 0.0 else (now - last) / 1e9
                last = now
                frame = model.frame(
                    dt = dt,
                    time = (now - first) / 1e9,
                    micLevel = micLevel().toDouble(),
                    speech = speech().toDouble(),
                    reducedMotion = reduced,
                )
            }
        }
    }

    Canvas(modifier) {
        val u = min(size.width, size.height) / 2.0
        renderer.draw(this, frame.docked(dock().toDouble(), u, density.toDouble()), paintGround, palette)
    }
}

/**
 * Fits a frame into the dock. At [dock] 1 the radius is pulled towards 0.44
 * of the unit, keeping half of each state's difference from dormant, so the
 * six stay apart by size while the smallest is no longer a speck; the rim is
 * floored at one dp, [density] over [u] in the frame's units. In between the
 * two are blended, so the move from hero to corner is one continuous shape.
 */
internal fun Frame.docked(dock: Double, u: Double, density: Double): Frame {
    if (dock <= 0.0 || u <= 0.0) return this
    val d = dock.coerceAtMost(1.0)
    val fitR = DOCK_R + (r - ApertureState.Dormant.pose.r) * DOCK_KEEP
    val floor = density / u
    return copy(r = r + (fitR - r) * d, rim = rim + (max(rim, floor) - rim) * d)
}

/** The docked radius dormant lands on, and how much of each state's size difference survives. */
internal const val DOCK_R = 0.44
internal const val DOCK_KEEP = 0.5

/**
 * The handoff's reduced motion trigger. Removing animations in accessibility
 * settings sets this scale to zero, which is the switch Android actually has.
 */
internal fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** One way of drawing a [Frame]. Both renderers take exactly the same inputs. */
internal interface ApertureRenderer {
    fun draw(scope: DrawScope, f: Frame, paintGround: Boolean, palette: AperturePalette = AperturePalette.Dark)
}

internal enum class RendererKind { Agsl, Canvas }

/** `RuntimeShader` arrived in Android 13. */
internal const val AGSL_MIN_SDK = 33

/**
 * Which renderer a phone on [sdkInt] should get. A pure function so the
 * split point is a JVM test rather than something only a device can show.
 */
internal fun rendererKind(sdkInt: Int, forceCanvas: Boolean): RendererKind =
    if (!forceCanvas && sdkInt >= AGSL_MIN_SDK) RendererKind.Agsl else RendererKind.Canvas

/**
 * Builds the renderer [rendererKind] picks for this phone. The explicit
 * version check sits beside the construction so lint can see the guard. A
 * shader the platform refuses to compile, or a uniform it refuses to take,
 * lands on the canvas path instead of a crash: the brief's "and any failure".
 */
internal fun apertureRenderer(forceCanvas: Boolean): ApertureRenderer {
    if (rendererKind(Build.VERSION.SDK_INT, forceCanvas) == RendererKind.Agsl &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
        return try {
            FallbackRenderer(AgslAperture())
        } catch (e: IllegalArgumentException) {
            CanvasAperture()
        }
    }
    return CanvasAperture()
}

/**
 * Draws with [primary] until it throws [IllegalArgumentException], which is
 * what `RuntimeShader` raises for a uniform it does not know, then with a
 * [CanvasAperture] for the rest of its life.
 */
internal class FallbackRenderer(private val primary: ApertureRenderer) : ApertureRenderer {
    private var active: ApertureRenderer = primary

    override fun draw(scope: DrawScope, f: Frame, paintGround: Boolean, palette: AperturePalette) {
        try {
            active.draw(scope, f, paintGround, palette)
        } catch (e: IllegalArgumentException) {
            if (active !== primary) throw e
            active = CanvasAperture()
            active.draw(scope, f, paintGround, palette)
        }
    }
}

/**
 * The canvas path from `drawAperture` in `aperture.js`: faint scribe rings,
 * three blurred bloom layers added onto the ground, the ring itself and a
 * brighter core. With an ink [AperturePalette] the same shapes are laid over
 * the ground instead: one faint halo, a solid body whose opacity follows
 * luminance, and the core. This is the renderer below API 33 and the
 * reference the shader is checked against.
 */
internal class CanvasAperture : ApertureRenderer {
    private val outline = FloatArray(4 * (Geometry.SAMPLES + 1))
    private val path = Path()
    private val light = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blurs = HashMap<Int, BlurMaskFilter>()
    private val echoBox = RectF()

    override fun draw(scope: DrawScope, f: Frame, paintGround: Boolean, palette: AperturePalette) = with(scope) {
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            val w = size.width.toDouble()
            val h = size.height.toDouble()
            if (paintGround) canvas.drawColor(palette.groundArgb)
            val u = min(w, h) / 2
            val cx = w / 2 + f.offsetX * density
            val cy = h / 2
            val accent = ApertureColours.mix(palette.accentArgb, palette.faultAccentArgb, f.desat)
            val core = ApertureColours.mix(palette.coreArgb, palette.faultCoreArgb, f.desat)
            val radius = (f.r * u).toFloat()
            val ink = palette.ink
            // Ink is laid over the ground; light is added onto it.
            val mode = if (ink) null else light
            fill.xfermode = mode
            stroke.xfermode = mode
            // Ink has no glow to lift it, so its faint lines are drawn heavier.
            val faint = if (ink) INK_FAINT else 1.0
            val body = INK_OPACITY + (1 - INK_OPACITY) * f.lum

            stroke.maskFilter = null
            stroke.strokeWidth = max(if (ink) 0.8 else 0.6, u * if (ink) 0.005 else 0.004).toFloat()
            // The inner ring's alpha is [Frame.scribe], which is 0.10 in five
            // states and raised only by Working. The outer one is fixed.
            stroke.color = ApertureColours.withAlpha(accent, f.scribe * f.lum * faint)
            canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius * SCRIBE_INNER, stroke)
            stroke.color = ApertureColours.withAlpha(accent, SCRIBE_OUTER_ALPHA * f.lum * faint)
            canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius * SCRIBE_OUTER, stroke)
            if (f.double > 0) {
                stroke.strokeWidth = max(if (ink) 1.2 else 1.0, u * if (ink) 0.008 else 0.007).toFloat()
                val alpha = if (ink) body * f.double else 0.5 * f.lum * f.double
                stroke.color = ApertureColours.withAlpha(accent, alpha)
                canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius * 1.16f, stroke)
            }
            if (f.echo < 1) {
                val left = 1 - f.echo
                stroke.strokeWidth = max(1.0, u * 0.006).toFloat()
                stroke.color = ApertureColours.withAlpha(
                    accent,
                    left * left * if (ink) INK_ECHO_ALPHA * body else ECHO_ALPHA * f.lum,
                )
                drawEcho(canvas, f, radius, cx.toFloat(), cy.toFloat())
            }

            if (ink) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    fill.maskFilter = blurFor(INK_HALO_BLUR * u)
                    fill.color = ApertureColours.withAlpha(accent, INK_HALO_ALPHA * f.glow)
                    canvas.drawPath(band(f, u, cx, cy, INK_HALO), fill)
                }
                fill.maskFilter = null
                fill.color = ApertureColours.withAlpha(accent, body)
                canvas.drawPath(band(f, u, cx, cy, 1.0), fill)
            } else {
                for ((scale, blur, alpha) in BLOOM) {
                    val sigma = blur * u
                    // BlurMaskFilter needs hardware support that arrived in API
                    // 28. Below that a "blurred" layer draws as a solid band two
                    // and a half times the ring's width, which is worse than no
                    // bloom, so those phones get the ring and core only.
                    if (sigma > 0 && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) continue
                    fill.maskFilter = if (sigma > 0) blurFor(sigma) else null
                    // The glow follows the voice in listening; the body does not.
                    val glow = if (sigma > 0) f.glow else 1.0
                    fill.color = ApertureColours.withAlpha(accent, alpha * f.lum * glow)
                    canvas.drawPath(band(f, u, cx, cy, scale), fill)
                }
            }
            fill.maskFilter = null
            fill.color = ApertureColours.withAlpha(core, 0.62 * f.lum)
            canvas.drawPath(band(f, u, cx, cy, 0.44), fill)
        }
    }

    /**
     * One ring leaving the rim, eased out to [ECHO_REACH] beyond it. It
     * keeps the opening, so it reads as the form breathing out, not as a
     * second closed ring, which is the fault's.
     */
    private fun drawEcho(canvas: android.graphics.Canvas, f: Frame, radius: Float, cx: Float, cy: Float) {
        val out = 1 - (1 - f.echo).pow(3)
        val r = radius * (1.02 + ECHO_REACH * out).toFloat()
        echoBox.set(cx - r, cy - r, cx + r, cy + r)
        val gap = max(0.0, f.gapHalf)
        if (gap < Geometry.CLOSED) {
            canvas.drawCircle(cx, cy, r, stroke)
        } else {
            // Android measures clockwise from the right on screen; the
            // opening is centred straight up, at -90 degrees.
            val gapDeg = Math.toDegrees(gap).toFloat()
            canvas.drawArc(echoBox, -90f + gapDeg, 360f - 2 * gapDeg, false, stroke)
        }
    }

    private fun band(f: Frame, u: Double, cx: Double, cy: Double, scale: Double): Path {
        Geometry.band(f, u, cx, cy, scale, scale, outline)
        path.rewind()
        path.moveTo(outline[0], outline[1])
        var i = 2
        while (i < outline.size) {
            path.lineTo(outline[i], outline[i + 1])
            i += 2
        }
        path.close()
        return path
    }

    /**
     * CSS `blur(n px)` is a Gaussian with standard deviation n, while
     * BlurMaskFilter takes a radius that Skia maps to sigma as
     * 0.57735 * radius + 0.5. Cached by whole pixel so a settling spring does
     * not allocate a filter every frame.
     */
    private fun blurFor(sigma: Double): BlurMaskFilter {
        val radius = max(1, ((sigma - 0.5) / 0.57735).roundToInt())
        return blurs.getOrPut(radius) { BlurMaskFilter(radius.toFloat(), BlurMaskFilter.Blur.NORMAL) }
    }

    private companion object {
        const val SCRIBE_INNER = 0.42f
        const val SCRIBE_OUTER = 1.38f
        const val SCRIBE_OUTER_ALPHA = 0.055
        const val ECHO_ALPHA = 0.38
        const val INK_ECHO_ALPHA = 0.5
        const val ECHO_REACH = 0.42
        const val INK_FAINT = 2.2
        const val INK_OPACITY = 0.55
        const val INK_HALO = 2.2
        const val INK_HALO_BLUR = 0.05
        const val INK_HALO_ALPHA = 0.12
        val BLOOM = listOf(
            Triple(2.5, 0.105, 0.36),
            Triple(1.62, 0.040, 0.44),
            Triple(1.14, 0.012, 0.56),
            Triple(1.0, 0.0, 0.84),
        )
    }
}

/** The only saturated colours in the product, from `aperture.js`. ARGB ints. */
internal object ApertureColours {
    const val GROUND = 0xFF0A0C0F.toInt()
    const val ACCENT = 0xFF6FD8E8.toInt()
    const val ACCENT_CORE = 0xFFDDFBFF.toInt()
    const val FAULT_NEUTRAL = 0xFF9AA4AF.toInt()
    /** The core's desaturated end, `[186, 194, 204]` in `aperture.js`. */
    const val CORE_NEUTRAL = 0xFFBAC2CC.toInt()

    /** The ink set, `INK` in `aperture.js`, for the light ground. */
    const val INK_GROUND = 0xFFF4F5F7.toInt()
    const val INK = 0xFF0A6B7A.toInt()
    const val INK_CORE = 0xFF04454F.toInt()
    const val INK_FAULT = 0xFF5B636E.toInt()
    const val INK_FAULT_CORE = 0xFF3A4049.toInt()

    fun mix(a: Int, b: Int, t: Double): Int {
        if (t <= 0) return a
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).roundToInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun withAlpha(rgb: Int, alpha: Double): Int =
        ((alpha.coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)
}
