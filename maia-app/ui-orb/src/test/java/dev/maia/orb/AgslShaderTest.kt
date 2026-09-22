package dev.maia.orb

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the JVM can prove about the shader path without compiling AGSL: the
 * Kotlin and shader sides agree on every uniform, the frame lands in them
 * unchanged, colours live only in [ApertureColours], and the renderer split
 * falls where the platform does. Whether the source compiles is proven on
 * the Pixel.
 */
class AgslShaderTest {
    private class Declared(val type: String, val count: Int, val colour: Boolean)

    private val declared: Map<String, Declared> by lazy {
        val source = AgslShader.SOURCE.lines().joinToString("\n") { it.substringBefore("//") }
        val decl = Regex("""(layout\s*\(\s*color\s*\)\s*)?uniform\s+(\w+)\s+(\w+)\s*(?:\[\s*(\d+)\s*])?\s*;""")
        decl.findAll(source).associate { m ->
            m.groupValues[3] to Declared(
                type = m.groupValues[2],
                count = m.groupValues[4].ifEmpty { "1" }.toInt(),
                colour = m.groupValues[1].isNotEmpty(),
            )
        }
    }

    private fun components(type: String) = when (type) {
        "float", "half" -> 1
        "float2", "vec2", "half2" -> 2
        "float3", "vec3", "half3" -> 3
        "float4", "vec4", "half4" -> 4
        else -> error("unexpected uniform type " + type)
    }

    @Test
    fun `every uniform Kotlin sets is declared in the shader, and nothing else is`() {
        val set = AgslShader.FLOATS.map { it.name } + AgslShader.MIC + AgslShader.colours(AperturePalette.Dark).map { it.first }
        assertEquals("no uniform set twice", set.size, set.toSet().size)
        assertEquals(set.toSet(), declared.keys)
    }

    @Test
    fun `uniform sizes and kinds match their declarations`() {
        for (u in AgslShader.FLOATS) {
            val d = declared.getValue(u.name)
            assertFalse(u.name, d.colour)
            assertEquals(u.name, u.size, components(d.type) * d.count)
        }
        val mic = declared.getValue(AgslShader.MIC)
        assertEquals("float", mic.type)
        assertEquals(Signals.TAPS, mic.count)
        for ((name, _) in AgslShader.colours(AperturePalette.Dark)) {
            val d = declared.getValue(name)
            assertTrue("$name is layout(color)", d.colour)
            assertEquals(name, "half4", d.type)
        }
    }

    @Test
    fun `packed offsets are contiguous and fill the array`() {
        var next = 0
        for (u in AgslShader.FLOATS) {
            assertEquals(u.name, next, u.offset)
            next += u.size
        }
        assertEquals(AgslShader.SCALAR_COUNT, next)
    }

    @Test
    fun `pack writes the frame's values`() {
        for (state in ApertureState.entries) {
            val f = ApertureModel.staticFrame(state)
            val s = FloatArray(AgslShader.SCALAR_COUNT)
            val mic = FloatArray(Signals.TAPS)
            AgslShader.pack(f, 1080f, 2400f, 2.625f, paintGround = true, scalars = s, mic = mic)
            val eps = 1e-6f
            assertEquals(540f, s[AgslShader.CENTRE], eps)
            assertEquals(1200f, s[AgslShader.CENTRE + 1], eps)
            assertEquals(540f, s[AgslShader.UNIT], eps)
            assertEquals(f.r.toFloat(), s[AgslShader.R], eps)
            assertEquals(f.gapHalf.toFloat(), s[AgslShader.GAP_HALF], eps)
            assertEquals(f.rim.toFloat(), s[AgslShader.RIM], eps)
            assertEquals(f.bottom.toFloat(), s[AgslShader.BOTTOM], eps)
            assertEquals(f.lum.toFloat(), s[AgslShader.LUM], eps)
            assertEquals(f.desat.toFloat(), s[AgslShader.DESAT], eps)
            assertEquals(f.double.toFloat(), s[AgslShader.DOUBLE], eps)
            assertEquals(f.dash.toFloat(), s[AgslShader.DASH], eps)
            assertEquals(f.hotAngle.toFloat(), s[AgslShader.HOT_ANGLE], eps)
            assertEquals(f.hotAmount.toFloat(), s[AgslShader.HOT_AMOUNT], eps)
            assertEquals(Frame.HOT_WIDTH.toFloat(), s[AgslShader.HOT_WIDTH], eps)
            assertEquals(1f, s[AgslShader.GROUND_ALPHA], eps)
            for (i in 0 until Signals.TAPS) assertEquals("tap $i", f.mic[i].toFloat(), mic[i], eps)
        }
    }

    @Test
    fun `pack moves the centre by the shake in pixels and clamps a negative gap`() {
        val f = Frame(
            r = 0.5, gapHalf = -0.1, rim = 0.013, bottom = 0.004, lum = 0.55, desat = 1.0,
            double = 1.0, scribe = 0.10, offsetX = 6.0, mic = DoubleArray(Signals.TAPS) { it / 24.0 },
            dash = 0.0, hotAngle = -2.0, hotAmount = 0.0,
        )
        val s = FloatArray(AgslShader.SCALAR_COUNT)
        val mic = FloatArray(Signals.TAPS)
        AgslShader.pack(f, 300f, 200f, 2f, paintGround = false, scalars = s, mic = mic)
        assertEquals(162f, s[AgslShader.CENTRE], 1e-5f)
        assertEquals(100f, s[AgslShader.CENTRE + 1], 1e-5f)
        assertEquals(100f, s[AgslShader.UNIT], 1e-5f)
        assertEquals(0f, s[AgslShader.GAP_HALF], 0f)
        assertEquals(0f, s[AgslShader.GROUND_ALPHA], 0f)
        assertEquals(23f / 24f, mic[23], 1e-6f)
    }

    @Test
    fun `every aperture colour reaches the shader as a uniform`() {
        val constants = ApertureColours::class.java.declaredFields
            // The Compose compiler adds a static `\$stable` int to every class; it is not a colour.
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && !it.name.startsWith("$") }
            .map { it.isAccessible = true; it.getInt(null) }
            .toSet()
        val sent = listOf(AperturePalette.Dark, AperturePalette.Light)
            .flatMap { p -> AgslShader.colours(p).map { it.second } }
            .toSet()
        assertEquals(constants, sent)
        for (p in listOf(AperturePalette.Dark, AperturePalette.Light)) {
            val names = AgslShader.colours(p).map { it.first }.toSet()
            assertEquals(declared.filterValues { it.colour }.keys, names)
        }
    }

    @Test
    fun `pack writes the live channels and the ink flag`() {
        val model = ApertureModel()
        model.setState(ApertureState.Listening)
        val f = model.frame(1.0 / 120, 0.0, micLevel = 0.5)
        val s = FloatArray(AgslShader.SCALAR_COUNT)
        AgslShader.pack(f, 400f, 400f, 2f, paintGround = false, scalars = s, mic = FloatArray(Signals.TAPS), ink = true)
        assertEquals(1f, s[AgslShader.INK], 0f)
        assertEquals(1f, s[AgslShader.COMET], 0f)
        assertEquals(f.micGain.toFloat(), s[AgslShader.MIC_GAIN], 1e-6f)
        assertEquals(f.glow.toFloat(), s[AgslShader.GLOW], 1e-6f)
        assertTrue("an echo on entry", s[AgslShader.ECHO_ALPHA] > 0f)
        assertTrue("the echo starts at the rim", s[AgslShader.ECHO_R] in (f.r * 1.01).toFloat()..(f.r * 1.05).toFloat())
        val still = ApertureModel.staticFrame(ApertureState.Listening)
        AgslShader.pack(still, 400f, 400f, 2f, paintGround = false, scalars = s, mic = FloatArray(Signals.TAPS))
        assertEquals(0f, s[AgslShader.INK], 0f)
        assertEquals(0f, s[AgslShader.ECHO_ALPHA], 0f)
        assertEquals(0f, s[AgslShader.COMET], 0f)
    }

    @Test
    fun `the source types no colour of its own`() {
        val src = AgslShader.SOURCE
        assertFalse("hex colour", Regex("""#[0-9a-fA-F]{3,8}\b""").containsMatchIn(src))
        assertFalse("0x literal", src.contains("0x"))
        val literalVector = Regex("""\b(?:float|half|vec)[34]\s*\(\s*-?[0-9.]+\s*(?:,\s*-?[0-9.]+\s*)*\)""")
        assertFalse("colour-shaped literal: " + literalVector.find(src)?.value, literalVector.containsMatchIn(src))
    }

    @Test
    fun `the source has no em dash`() {
        assertFalse(AgslShader.SOURCE.contains('\u2014'))
    }

    @Test
    fun `the shader is chosen from Android 13, and never when the canvas is forced`() {
        assertEquals(RendererKind.Canvas, rendererKind(26, forceCanvas = false))
        assertEquals(RendererKind.Canvas, rendererKind(32, forceCanvas = false))
        assertEquals(RendererKind.Agsl, rendererKind(33, forceCanvas = false))
        assertEquals(RendererKind.Agsl, rendererKind(36, forceCanvas = false))
        assertEquals(RendererKind.Canvas, rendererKind(33, forceCanvas = true))
        assertEquals(RendererKind.Canvas, rendererKind(36, forceCanvas = true))
    }
}
