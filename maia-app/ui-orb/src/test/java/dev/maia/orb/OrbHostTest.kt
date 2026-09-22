package dev.maia.orb

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reason [OrbHost] exists: one aperture that outlives the screens, so a
 * state change and a change of seat both play as springs rather than
 * arriving at rest. Each test samples a frame or two after the change and
 * requires the orb to be somewhere strictly between where it started and
 * where it settles.
 *
 * API 32 so the aperture draws with [CanvasAperture], which Robolectric's
 * native graphics can render; the shader path needs a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrbHostTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dormantToListeningIsMidSpringOnTheNextFrames() {
        var state by mutableStateOf(ApertureState.Dormant)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                OrbHost(state, Modifier.fillMaxSize()) {
                    Box(Modifier.size(300.dp).orbAnchor(OrbAnchor.Stage))
                }
            }
        }
        settle()
        val dormant = ringHalfWidth()

        state = ApertureState.Listening
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        val moving = ringHalfWidth()

        settle()
        val listening = ringHalfWidth()

        assertTrue("listening rests wider than dormant: $dormant, $listening", listening > dormant + 10)
        assertTrue(
            "four frames in, the ring is between its two rests: $dormant < $moving < $listening",
            moving > dormant + 2 && moving < listening - 2,
        )
    }

    @Test
    fun stageToDockGlidesRatherThanJumps() {
        var docked by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                OrbHost(ApertureState.Dormant, Modifier.fillMaxSize()) {
                    if (docked) {
                        Box(Modifier.offset(20.dp, 16.dp).size(64.dp).orbAnchor(OrbAnchor.Dock))
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(300.dp).orbAnchor(OrbAnchor.Stage))
                        }
                    }
                }
            }
        }
        settle()
        val stage = orbBounds()

        docked = true
        // One frame to compose and measure the dock, then a few of glide.
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        val moving = orbBounds()

        settle()
        val dock = orbBounds()

        assertTrue("the dock is smaller than the stage: $stage, $dock", dock.width < stage.width / 2)
        assertTrue(
            "mid-glide the size is between the two seats: ${stage.width} > ${moving.width} > ${dock.width}",
            moving.width < stage.width - 1 && moving.width > dock.width + 1,
        )
        assertTrue(
            "mid-glide the centre is between the two seats: ${stage.center} -> ${moving.center} -> ${dock.center}",
            moving.center.x < stage.center.x - 1 && moving.center.x > dock.center.x + 1 &&
                moving.center.y < stage.center.y - 1 && moving.center.y > dock.center.y + 1,
        )
        // And it does arrive: the dock's 64 dp box, centred on it.
        val density = rule.density.density
        assertEquals(64 * density, dock.width, 1.5f)
        assertEquals((20 + 32) * density, dock.center.x, 1.5f)
        assertEquals((16 + 32) * density, dock.center.y, 1.5f)
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
    }

    private fun orbBounds(): Rect =
        rule.onNodeWithTag(ORB_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /**
     * How far right of the orb's centre the ring reaches, in pixels, along
     * the centre row: the last pixel at least half as bright as the row's
     * brightest. Relative, because dormant is dimmer as well as smaller. The
     * opening is at the top, so the centre row always crosses the rim.
     *
     * The window is drawn into a bitmap by hand: captureToImage waits for a
     * redraw, which never comes while the test holds the clock.
     */
    private fun ringHalfWidth(): Int {
        val orb = rule.onNodeWithTag(ORB_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        val row = orb.center.y.toInt()
        val centre = orb.center.x.toInt()
        val bright = (centre until orb.right.toInt()).map { x ->
            val c = bitmap.getPixel(x, row)
            ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        }
        val peak = bright.max()
        assertTrue("the ring is drawn at all", peak > 30)
        return bright.indexOfLast { it >= peak / 2 }
    }
}
