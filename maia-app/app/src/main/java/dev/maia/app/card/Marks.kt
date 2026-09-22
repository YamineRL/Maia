package dev.maia.app.card

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.maia.app.ui.Maia
import dev.maia.app.ui.MaiaRadius
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign

/**
 * The four cues, one file.
 *
 * Every one of them is geometry or type: a rotated square, two bracket
 * characters, a dashed rule, a repeating diagonal. No colour carries meaning
 * here, no asset is loaded, and nothing needs a theme to be legible. That is
 * the point of there being four of them.
 */

/** The gutter mark. 7 dp square on its corner, so 10 dp of box to sit in. */
private val DiamondBox = 10.dp
private val DiamondSide = 7.dp

@Composable
fun GutterDiamond(mark: Mark, modifier: Modifier = Modifier) {
    val colours = Maia.colours
    // Deliberately silent to a screen reader. The mark is redundant with the
    // row's spoken description, which says "guessed" in words, and a reader
    // announcing an unnamed shape before every value is noise.
    Box(
        modifier
            .size(DiamondBox)
            .clearAndSetSemantics { }
            .drawBehind { drawDiamond(mark, colours.inkMid, colours.inkFaint) },
    )
}

private fun DrawScope.drawDiamond(mark: Mark, filled: Color, faint: Color) {
    val half = DiamondSide.toPx() * 1.41421356f / 2f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val path = Path().apply {
        moveTo(cx, cy - half)
        lineTo(cx + half, cy)
        lineTo(cx, cy + half)
        lineTo(cx - half, cy)
        close()
    }
    when (mark) {
        Mark.Heard -> drawPath(path, filled)
        Mark.Guessed -> drawPath(path, filled, style = Stroke(width = 1.dp.toPx()))
        Mark.Empty -> drawPath(
            path,
            faint,
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
            ),
        )
    }
}

/**
 * The hatched ground under a guessed row.
 *
 * 135 degree stripes, 6 dp on and 6 dp off measured across the stripe, at 7%
 * white on dark and 5% ink on light. It is the cue that survives a greyscale
 * screen and a photograph taken in sunlight, which is exactly the pair of
 * conditions a colour would not.
 */
fun Modifier.hatched(colour: Color, on: Boolean = true): Modifier =
    if (!on) this else drawBehind {
        val stripe = 6.dp.toPx()
        val period = stripe * 2f * 1.41421356f
        clipRect {
            var x = -size.height
            while (x < size.width + size.height) {
                drawLine(
                    color = colour,
                    start = Offset(x, 0f),
                    end = Offset(x + size.height, size.height),
                    strokeWidth = stripe,
                    cap = StrokeCap.Butt,
                )
                x += period
            }
        }
    }

/** The dashed baseline under a guessed or empty value. */
fun Modifier.dashedUnderline(colour: Color, thickness: Dp = 1.dp): Modifier = drawBehind {
    val y = size.height - thickness.toPx() / 2f
    drawLine(
        color = colour,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = thickness.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
    )
}

/** The word "guess" beside the label, outlined rather than filled. */
@Composable
fun GuessChip(modifier: Modifier = Modifier) {
    val colours = Maia.colours
    Text(
        text = "GUESS",
        style = Maia.type.label,
        color = colours.inkStrong,
        textAlign = TextAlign.Center,
        modifier = modifier
            .border(BorderStroke(1.dp, colours.lineStrong), RoundedCornerShape(MaiaRadius.chip))
            .hatched(colours.hatch)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The value as the card shows it: bracketed when Maia supplied it, plain when
 * the user did. The brackets are the cue that survives a monochrome e-ink
 * screenshot and a 200 character screen reader summary alike.
 */
fun bracketed(value: String, mark: Mark): String =
    if (mark == Mark.Guessed) "⟨$value⟩" else value
