package dev.maia.orb

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The aperture as one `RuntimeShader`, the handoff's preferred path on
 * Android 13 and later.
 *
 * The shader is compiled once, here, and every frame only rewrites uniforms
 * from a reused array, so the draw path allocates nothing. Whether it holds
 * the 4 ms budget at 120 Hz is a question for the Pixel, not the JVM.
 *
 * Construction throws [IllegalArgumentException] if the platform rejects the
 * source; [apertureRenderer] catches that and draws with [CanvasAperture].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AgslAperture : ApertureRenderer {
    private val shader = RuntimeShader(AgslShader.SOURCE)
    private val brush = ShaderBrush(shader)
    private val scalars = FloatArray(AgslShader.SCALAR_COUNT)
    private val mic = FloatArray(Signals.TAPS)

    private var palette: AperturePalette? = null

    override fun draw(scope: DrawScope, f: Frame, paintGround: Boolean, palette: AperturePalette) = with(scope) {
        if (palette != this@AgslAperture.palette) {
            for ((name, colour) in AgslShader.colours(palette)) shader.setColorUniform(name, colour)
            this@AgslAperture.palette = palette
        }
        AgslShader.pack(f, size.width, size.height, density, paintGround, scalars, mic, palette.ink)
        val floats = AgslShader.FLOATS
        for (i in floats.indices) {
            val u = floats[i]
            if (u.size == 2) {
                shader.setFloatUniform(u.name, scalars[u.offset], scalars[u.offset + 1])
            } else {
                shader.setFloatUniform(u.name, scalars[u.offset])
            }
        }
        shader.setFloatUniform(AgslShader.MIC, mic)
        // With no ground the light is added onto whatever is behind, as the
        // canvas path's ADD paints are. Ink is laid over it.
        drawRect(brush, blendMode = if (paintGround || palette.ink) BlendMode.SrcOver else BlendMode.Plus)
    }
}
