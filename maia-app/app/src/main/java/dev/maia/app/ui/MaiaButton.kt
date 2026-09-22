package dev.maia.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Loud is the one thing to do on a screen, and it is lit the way the orb is:
 * the aperture's teal as a deep gradient, a bright edge along the top and a
 * glow under it that swells while it is pressed.
 * Quiet is the alternative: a hairline outline, nothing filled.
 * Faint is a quiet one that steps further back, for `Clear` and `Not now`.
 */
enum class ButtonKind { Loud, Quiet, Faint }

/** Pill height. Taller than the touch target so the label has air. */
val ButtonHeight = 56.dp

/** The round invoke control's diameter: the orb's small sibling. */
val InvokeSize = 84.dp

/**
 * The app's button. Every screen draws its actions through this, so they
 * share one shape and one press: the pill sinks to 96% on a spring, and a
 * loud one glows brighter, then both spring back on release.
 */
@Composable
fun MaiaButton(
    text: String,
    kind: ButtonKind,
    modifier: Modifier = Modifier,
    description: String? = null,
    order: Float? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    ButtonFace(
        text,
        kind,
        pressed,
        modifier
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics {
                role = Role.Button
                if (description != null) contentDescription = description
                if (order != null) traversalIndex = order
            },
    )
}

/**
 * The drawing alone, for a control that handles its own input: the invoke
 * control fires on the press rather than the release and reports [pressed]
 * itself.
 */
@Composable
fun ButtonFace(text: String, kind: ButtonKind, pressed: Boolean, modifier: Modifier = Modifier) {
    val colours = Maia.colours
    val shape = RoundedCornerShape(50)
    val sink = pressSink(pressed)
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = 1f - 0.04f * sink
                scaleX = s
                scaleY = s
            }
            .then(
                when (kind) {
                    ButtonKind.Loud -> Modifier.lit(colours, shape, sink)
                    ButtonKind.Quiet -> if (colours.isLight) {
                        // A bare outline goes grey on white, so the light
                        // quiet button is a raised card with a teal rim.
                        Modifier
                            .shadow((3f - 2f * sink).dp, shape, clip = false)
                            .clip(shape)
                            .background(Color.White)
                            .background(colours.accentAperture.copy(alpha = 0.06f * sink))
                            .border(1.dp, colours.accentAperture.copy(alpha = 0.35f + 0.3f * sink), shape)
                    } else {
                        Modifier
                            .clip(shape)
                            .background(colours.inkHigh.copy(alpha = 0.06f * sink))
                            .border(1.dp, colours.lineStrong, shape)
                    }
                    ButtonKind.Faint -> Modifier.graphicsLayer { alpha = 1f - 0.3f * sink }
                },
            )
            .heightIn(min = ButtonHeight)
            .padding(horizontal = Maia.space.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Maia.type.action,
            color = when (kind) {
                ButtonKind.Loud -> litInk(colours)
                ButtonKind.Quiet -> colours.inkHigh
                ButtonKind.Faint -> colours.inkMid
            },
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The round invoke control: a lit disc with a microphone, and [label] under
 * it in the mono label type. Speaking is what the app is for, so its button
 * is shaped like nothing else on the screen.
 */
@Composable
fun InvokeFace(label: String, pressed: Boolean, modifier: Modifier = Modifier) {
    val colours = Maia.colours
    val sink = pressSink(pressed)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(InvokeSize)
                .graphicsLayer {
                    val s = 1f - 0.06f * sink
                    scaleX = s
                    scaleY = s
                }
                .lit(colours, CircleShape, sink),
            contentAlignment = Alignment.Center,
        ) {
            Microphone(litInk(colours), Modifier.size(30.dp))
        }
        Text(
            label.uppercase(),
            style = Maia.type.label,
            color = colours.inkMid,
            modifier = Modifier.padding(top = Maia.space.md),
        )
    }
}

@Composable
private fun pressSink(pressed: Boolean): Float {
    val sink by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return sink
}

private fun litInk(colours: MaiaColours): Color = if (colours.isLight) colours.groundVoid else colours.accentCore

/**
 * The lit surface. On the dark ground it is the aperture teal mixed into the
 * ground, deeper at the bottom, with a bright top edge and a teal glow cast
 * under it. On the light ground a glow has nothing to shine against, so the
 * light comes from inside instead: a clear cyan top falling to deep teal, a
 * white sheen along the upper rim, and a teal shadow that lifts it off the
 * page. [sink] is the press, 0 to 1: the glow swells and the fill brightens.
 */
private fun Modifier.lit(colours: MaiaColours, shape: Shape, sink: Float): Modifier {
    val accent = colours.accentAperture
    return if (colours.isLight) {
        val glow = accent.copy(alpha = 0.55f + 0.25f * sink)
        this
            // Android tints shadows only faintly, so the teal halo a dark
            // ground gets for free is drawn here: the shape again, spread
            // and dropped a little, fading out in rings.
            .drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                val r = outline.bounds
                for (i in 1..HALO_RINGS) {
                    val grow = i * 2.dp.toPx()
                    val corner = CornerRadius(r.height / 2f + grow)
                    drawRoundRect(
                        accent.copy(alpha = (0.07f + 0.05f * sink) * (1f - i / (HALO_RINGS + 1f))),
                        topLeft = Offset(-grow, -grow + 4.dp.toPx()),
                        size = Size(size.width + 2 * grow, size.height + 2 * grow),
                        cornerRadius = corner,
                    )
                }
            }
            .shadow((10f - 4f * sink).dp, shape, clip = false, ambientColor = glow, spotColor = glow)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(accent, LightCyan, 0.55f + 0.15f * sink),
                        accent,
                        lerp(accent, colours.accentCore, 0.45f),
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.6f),
                    0.5f to Color.White.copy(alpha = 0.05f),
                    1f to colours.accentCore.copy(alpha = 0.5f),
                ),
                shape,
            )
    } else {
        val glow = accent.copy(alpha = 0.55f + 0.35f * sink)
        this
            .shadow((14f + 10f * sink).dp, shape, clip = false, ambientColor = glow, spotColor = glow)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(colours.groundBase, accent, 0.30f + 0.12f * sink),
                        lerp(colours.groundBase, accent, 0.10f + 0.08f * sink),
                    ),
                ),
            )
            .border(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.18f))), shape)
    }
}

/** The light theme's highlight: the orb's inner cyan, bright enough to read on white. */
private val LightCyan = Color(0xFF3CC4D6)

private const val HALO_RINGS = 6

/** A microphone, drawn so the app needs no icon set for one glyph. */
@Composable
private fun Microphone(ink: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 30f
        val cx = size.width / 2f
        val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round)
        drawRoundRect(
            ink,
            topLeft = Offset(cx - 5f * u, 2f * u),
            size = Size(10f * u, 16f * u),
            cornerRadius = CornerRadius(5f * u),
        )
        drawArc(
            ink,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - 9f * u, 5f * u),
            size = Size(18f * u, 18f * u),
            style = stroke,
        )
        drawLine(ink, Offset(cx, 23f * u), Offset(cx, 27.5f * u), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(ink, Offset(cx - 5f * u, 27.5f * u), Offset(cx + 5f * u, 27.5f * u), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}
