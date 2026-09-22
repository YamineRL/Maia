package dev.maia.orb

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Where a screen wants the orb. [Stage] is the hero seat on the flow and the
 * locked screens; [Dock] is the 64 dp corner every other screen keeps it in.
 */
enum class OrbAnchor { Stage, Dock }

/**
 * Marks where the orb sits on this screen. The element it modifies should be
 * an empty box of the size the orb takes: it draws nothing, it only reports
 * its bounds to the enclosing [OrbHost], which moves the one orb there.
 *
 * The most recently placed anchor wins, and leaving composition takes it back
 * out, so a screen swap hands the orb from the old anchor to the new one
 * without a frame where neither holds it. Outside an [OrbHost] it does
 * nothing.
 */
fun Modifier.orbAnchor(anchor: OrbAnchor): Modifier = composed {
    val anchors = LocalOrbAnchors.current
    if (anchors == null) {
        Modifier
    } else {
        val entry = remember(anchor) { AnchorEntry(anchor) }
        DisposableEffect(anchors, entry) {
            anchors.register(entry)
            onDispose { anchors.unregister(entry) }
        }
        // Position and size rather than boundsInRoot, which is clipped by
        // every parent: an anchor half under a scroll edge would read small.
        Modifier.onGloballyPositioned {
            val p = it.positionInRoot()
            entry.bounds = Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
        }
    }
}

/**
 * The one aperture in the app, over [content].
 *
 * Screens say where it goes with [orbAnchor] and never draw one themselves,
 * so the aperture and its [ApertureModel] live as long as the host does. That
 * is what lets a state change play its spring (the invoke opening, the
 * contraction, the fault shake) instead of arriving as a new orb already at
 * rest, and what lets the flow's hero orb travel to the answer's corner
 * rather than jump there.
 *
 * The centre and the size each spring to the active anchor, and [dock] eases
 * from 0 on a stage to 1 on a dock with the same spring, so the form fits the
 * corner as it arrives. The first placement snaps. With no anchor on screen
 * the orb is not drawn. It is decorative, out of the accessibility tree, and
 * takes no touches: it has no pointer input, so a tap falls through to the
 * content under it.
 */
@Composable
fun OrbHost(
    state: ApertureState,
    modifier: Modifier = Modifier,
    micLevel: () -> Float = { 0f },
    speech: () -> Float = { 0f },
    palette: AperturePalette = AperturePalette.Dark,
    toolCalls: Int = 0,
    content: @Composable () -> Unit,
) {
    val anchors = remember { OrbAnchors() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        CompositionLocalProvider(LocalOrbAnchors provides anchors, content = content)
        Box(Modifier.matchParentSize()) {
            OrbLayer(anchors, origin, state, micLevel, speech, palette, toolCalls)
        }
    }
}

@Composable
private fun OrbLayer(
    anchors: OrbAnchors,
    origin: Offset,
    state: ApertureState,
    micLevel: () -> Float,
    speech: () -> Float,
    palette: AperturePalette,
    toolCalls: Int,
) {
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    val size = remember { Animatable(0f) }
    val dock = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    // Not the effect's own scope: a new target interrupts the running spring
    // through the Animatable, which carries its velocity into the next one.
    // Cancelling the effect would end the spring and drop that velocity.
    val scope = rememberCoroutineScope()

    val entry = anchors.active
    val bounds = entry?.bounds?.translate(-origin)
    LaunchedEffect(entry == null, entry?.anchor, bounds) {
        if (entry == null) {
            // Nothing to sit on. The next anchor is a new arrival, not a move.
            placed = false
            return@LaunchedEffect
        }
        // A new anchor is registered in composition and measured a frame
        // later. Until then the orb holds its course.
        if (bounds == null) return@LaunchedEffect
        val docked = if (entry.anchor == OrbAnchor.Dock) 1f else 0f
        val side = minOf(bounds.width, bounds.height)
        if (!placed) {
            x.snapTo(bounds.center.x)
            y.snapTo(bounds.center.y)
            size.snapTo(side)
            dock.snapTo(docked)
            placed = true
        } else {
            scope.launch { x.animateTo(bounds.center.x, Glide) }
            scope.launch { y.animateTo(bounds.center.y, Glide) }
            scope.launch { size.animateTo(side, Glide) }
            scope.launch { dock.animateTo(docked, Glide) }
        }
    }

    Aperture(
        state = state,
        modifier = Modifier
            // Read in placement and measurement only, so a glide relays out
            // this one node each frame and never recomposes, and the content
            // under it is not touched at all.
            .layout { measurable, _ ->
                val side = size.value.roundToInt().coerceAtLeast(0)
                val p = measurable.measure(Constraints.fixed(side, side))
                // Zero sized itself, so the overlay never pushes back on the
                // host's constraints; the orb is placed out from its corner.
                layout(0, 0) {
                    p.place(IntOffset((x.value - side / 2f).roundToInt(), (y.value - side / 2f).roundToInt()))
                }
            }
            .graphicsLayer { alpha = if (placed && anchors.active != null) 1f else 0f }
            .testTag(ORB_TAG)
            .clearAndSetSemantics { },
        micLevel = micLevel,
        speech = speech,
        paintGround = false,
        palette = palette,
        dock = { dock.value },
        toolCalls = toolCalls,
    )
}

/** The glide between anchors: stiffness 180, damping ratio 0.9. */
private val Glide = spring<Float>(dampingRatio = 0.9f, stiffness = 180f)

internal const val ORB_TAG = "maia.orb"

private class AnchorEntry(val anchor: OrbAnchor) {
    var bounds: Rect? by mutableStateOf(null)
}

private class OrbAnchors {
    private val entries = mutableStateListOf<AnchorEntry>()

    val active: AnchorEntry? get() = entries.lastOrNull()

    fun register(entry: AnchorEntry) {
        entries.remove(entry)
        entries.add(entry)
    }

    fun unregister(entry: AnchorEntry) {
        entries.remove(entry)
    }
}

private val LocalOrbAnchors = staticCompositionLocalOf<OrbAnchors?> { null }
