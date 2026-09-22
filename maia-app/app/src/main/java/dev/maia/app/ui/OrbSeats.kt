package dev.maia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.maia.orb.AperturePalette
import dev.maia.orb.ApertureState
import dev.maia.orb.OrbAnchor
import dev.maia.orb.OrbHost
import dev.maia.orb.orbAnchor

/**
 * [OrbHost] with the palette [Maia.colours] asks for: ink on the light ground,
 * light on the dark one. Each entry point wraps all of its screens in exactly
 * one of these, inside its [LocalMaiaColours] provider; a preview wraps its
 * screen in one so the orb shows there too.
 */
@Composable
fun MaiaOrbHost(
    state: ApertureState,
    modifier: Modifier = Modifier.fillMaxSize(),
    micLevel: () -> Float = { 0f },
    speech: () -> Float = { 0f },
    toolCalls: Int = 0,
    content: @Composable () -> Unit,
) = OrbHost(
    state = state,
    modifier = modifier,
    micLevel = micLevel,
    speech = speech,
    palette = if (Maia.colours.isLight) AperturePalette.Light else AperturePalette.Dark,
    toolCalls = toolCalls,
    content = content,
)

/** The hero seat's side. It is the orb's drawing square, not the ring's diameter. */
val StageSide = 372.dp

/** How much larger than [StageSide] the stage orb is drawn. */
const val StageScale = 1.2f

/** The corner seat: 64 dp, 16 dp under the status bar. */
val DockSide = 64.dp
val DockTop = 16.dp

/**
 * The frame the flow and the locked screens share: the orb's seat, then the
 * words under it.
 *
 * The seat is a [StageSide] square centred across the window with its centre
 * at 38% of the window's height, and it never moves, whatever the words below
 * are doing. The words start where the seat ends and, when there are more of
 * them than fit, scroll under a fade at their top edge rather than pushing the
 * orb up. The frame is expected to fill the window, which every caller does.
 *
 * On a window narrower than [StageSide] the seat shrinks to the window's width
 * rather than hanging off both edges.
 */
@Composable
fun OrbStage(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = min(StageSide, maxWidth)
        val top = (maxHeight * STAGE_CENTRE - side / 2).coerceAtLeast(0.dp)
        // The orb draws larger than the seat it lays out, about the same
        // centre, so it grows without pushing the words down. Its square may
        // run past the window's sides; only the faint outer glow reaches that far.
        val drawn = side * StageScale
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = top + side / 2 - drawn / 2)
                .requiredSize(drawn)
                .orbAnchor(OrbAnchor.Stage),
        )
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = top + side)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Only once something has gone under it, so resting words
                    // are never dimmed.
                    if (scroll.value > 0) {
                        val fade = Maia.space.lg.toPx()
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = fade),
                            size = Size(size.width, fade),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                .verticalScroll(scroll)
                .padding(horizontal = Maia.space.gutter)
                .padding(top = Maia.space.lg, bottom = Maia.space.xxl),
            verticalArrangement = Arrangement.spacedBy(Maia.space.lg),
            content = content,
        )
    }
}

/**
 * The corner seat, for every screen that is not a stage. Place it at the
 * screen's left gutter; it adds the status bar and [DockTop] above itself,
 * unless [inset] is false because its row already has.
 */
@Composable
fun OrbDock(modifier: Modifier = Modifier, inset: Boolean = true) {
    Box(
        modifier
            .then(if (inset) Modifier.dockInset() else Modifier)
            .size(DockSide)
            .orbAnchor(OrbAnchor.Dock),
    )
}

/** The status bar and [DockTop]: what sits above the corner seat. */
fun Modifier.dockInset(): Modifier = statusBarsPadding().padding(top = DockTop)

/** Where the stage's centre sits, as a fraction of the window's height. */
private const val STAGE_CENTRE = 0.38f
