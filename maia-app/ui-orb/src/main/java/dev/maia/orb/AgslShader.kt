package dev.maia.orb

/**
 * The AGSL source for [AgslAperture] and the packing of a [Frame] into its
 * uniforms.
 *
 * Kept free of any Android type on purpose: the shader cannot be compiled on
 * the JVM, so what the JVM can prove is the contract around it. The uniform
 * names Kotlin sets are the names the source declares, the values are the
 * frame's values, and no colour is typed twice. `AgslShaderTest` holds all
 * three.
 *
 * The maths is `Geometry.base` and `Geometry.outer` per pixel instead of per
 * outline sample, in units of half the shorter side, so both renderers read
 * the same frame the same way. Bloom is the handoff's exponential glow term
 * rather than blurred layers, which is the point of the shader path.
 */
internal object AgslShader {
    /** One float uniform: its name, its component count and where it sits in the packed array. */
    class Uniform(val name: String, val size: Int, val offset: Int)

    const val CENTRE = 0
    const val UNIT = 2
    const val R = 3
    const val GAP_HALF = 4
    const val RIM = 5
    const val BOTTOM = 6
    const val LUM = 7
    const val DESAT = 8
    const val DOUBLE = 9
    const val DASH = 10
    const val HOT_ANGLE = 11
    const val HOT_AMOUNT = 12
    const val HOT_WIDTH = 13
    const val GROUND_ALPHA = 14
    const val SCRIBE = 15
    const val INK = 16
    const val COMET = 17
    const val MIC_GAIN = 18
    const val GLOW = 19
    const val PHASE = 20
    const val SWELL = 21
    const val ECHO_R = 22
    const val ECHO_ALPHA = 23
    const val SCALAR_COUNT = 24

    /** The float uniforms other than the taps, in packing order. An array so the draw loop allocates no iterator. */
    val FLOATS: Array<Uniform> = arrayOf(
        Uniform("uCentre", 2, CENTRE),
        Uniform("uUnit", 1, UNIT),
        Uniform("uR", 1, R),
        Uniform("uGapHalf", 1, GAP_HALF),
        Uniform("uRim", 1, RIM),
        Uniform("uBottom", 1, BOTTOM),
        Uniform("uLum", 1, LUM),
        Uniform("uDesat", 1, DESAT),
        Uniform("uDouble", 1, DOUBLE),
        Uniform("uDash", 1, DASH),
        Uniform("uHotAngle", 1, HOT_ANGLE),
        Uniform("uHotAmount", 1, HOT_AMOUNT),
        Uniform("uHotWidth", 1, HOT_WIDTH),
        Uniform("uGroundAlpha", 1, GROUND_ALPHA),
        Uniform("uScribe", 1, SCRIBE),
        Uniform("uInk", 1, INK),
        Uniform("uComet", 1, COMET),
        Uniform("uMicGain", 1, MIC_GAIN),
        Uniform("uGlow", 1, GLOW),
        Uniform("uPhase", 1, PHASE),
        Uniform("uSwell", 1, SWELL),
        Uniform("uEchoR", 1, ECHO_R),
        Uniform("uEchoAlpha", 1, ECHO_ALPHA),
    )

    /** The [Signals.TAPS] microphone taps, set as one float array. */
    const val MIC = "uMic"

    /**
     * Every colour the shader uses, straight from [palette]. They are
     * `layout(color)` uniforms so the platform converts them from sRGB into
     * the destination's colour space, as it does for the canvas path's paints.
     * Set only when the palette changes, not every frame.
     */
    fun colours(palette: AperturePalette): Array<Pair<String, Int>> = arrayOf(
        "uGround" to palette.groundArgb,
        "uAccent" to palette.accentArgb,
        "uAccentCore" to palette.coreArgb,
        "uFaultNeutral" to palette.faultAccentArgb,
        "uCoreNeutral" to palette.faultCoreArgb,
    )

    /**
     * Writes [f] into [scalars] (laid out by [FLOATS]) and [mic]. [width] and
     * [height] are the drawing area in pixels and [density] turns the shake's
     * dp into pixels, exactly as `CanvasAperture` does. Allocates nothing, so
     * it can run every vsync.
     */
    fun pack(
        f: Frame,
        width: Float,
        height: Float,
        density: Float,
        paintGround: Boolean,
        scalars: FloatArray,
        mic: FloatArray,
        ink: Boolean = false,
    ) {
        scalars[CENTRE] = width / 2 + (f.offsetX * density).toFloat()
        scalars[CENTRE + 1] = height / 2
        scalars[UNIT] = minOf(width, height) / 2
        scalars[R] = f.r.toFloat()
        scalars[GAP_HALF] = maxOf(0.0, f.gapHalf).toFloat()
        scalars[RIM] = f.rim.toFloat()
        scalars[BOTTOM] = f.bottom.toFloat()
        scalars[LUM] = f.lum.toFloat()
        scalars[DESAT] = f.desat.toFloat()
        scalars[DOUBLE] = f.double.toFloat()
        scalars[SCRIBE] = f.scribe.toFloat()
        scalars[DASH] = f.dash.toFloat()
        scalars[HOT_ANGLE] = f.hotAngle.toFloat()
        scalars[HOT_AMOUNT] = f.hotAmount.toFloat()
        scalars[HOT_WIDTH] = f.hotWidth.toFloat()
        scalars[GROUND_ALPHA] = if (paintGround) 1f else 0f
        scalars[INK] = if (ink) 1f else 0f
        scalars[COMET] = if (f.comet) 1f else 0f
        scalars[MIC_GAIN] = f.micGain.toFloat()
        scalars[GLOW] = f.glow.toFloat()
        scalars[PHASE] = f.phase.toFloat()
        scalars[SWELL] = f.swell.toFloat()
        if (f.echo < 1) {
            val left = 1 - f.echo
            val body = INK_OPACITY + (1 - INK_OPACITY) * f.lum
            scalars[ECHO_R] = (f.r * (1.02 + ECHO_REACH * (1 - left * left * left))).toFloat()
            scalars[ECHO_ALPHA] = (left * left * if (ink) INK_ECHO_ALPHA * body else ECHO_LIGHT * f.lum).toFloat()
        } else {
            scalars[ECHO_R] = 0f
            scalars[ECHO_ALPHA] = 0f
        }
        for (i in 0 until Signals.TAPS) mic[i] = f.mic[i].toFloat()
    }

    /**
     * Polar SDF from the handoff README, widened to carry everything the
     * canvas path draws. Alphas are `CanvasAperture`'s: 0.84 for the body,
     * 0.62 for the core band at 0.44 of the thickness, uScribe (0.10 in five
     * states, raised only by Working) and 0.055 for the
     * scribe rings at 0.42 and 1.38 of the radius, 0.5 for the fault's second
     * ring at 1.16. The taps are read in a constant loop because runtime
     * effects follow GLSL ES 2 indexing rules, where a computed index into a
     * uniform array is not guaranteed; a triangular weight on the circle is
     * the same linear interpolation `Geometry.outer` does.
     */
    private const val INK_OPACITY = 0.55
    private const val ECHO_LIGHT = 0.38
    private const val INK_ECHO_ALPHA = 0.5
    private const val ECHO_REACH = 0.42

    val SOURCE: String = """
        const float PI = 3.14159265;
        const float TAU = 6.28318531;
        const float HALF_PI = 1.57079633;
        const float TAPS = ${Signals.TAPS}.0;
        const float LOBES = ${Signals.LOBES}.0;
        const float CLOSED = ${Geometry.CLOSED};

        uniform float2 uCentre;
        uniform float uUnit;
        uniform float uR;
        uniform float uGapHalf;
        uniform float uRim;
        uniform float uBottom;
        uniform float uLum;
        uniform float uDesat;
        uniform float uDouble;
        uniform float uScribe;
        uniform float uDash;
        uniform float uHotAngle;
        uniform float uHotAmount;
        uniform float uHotWidth;
        uniform float uGroundAlpha;
        uniform float uInk;
        uniform float uComet;
        uniform float uMicGain;
        uniform float uGlow;
        uniform float uPhase;
        uniform float uSwell;
        uniform float uEchoR;
        uniform float uEchoAlpha;
        uniform float uMic[${Signals.TAPS}];

        layout(color) uniform half4 uGround;
        layout(color) uniform half4 uAccent;
        layout(color) uniform half4 uAccentCore;
        layout(color) uniform half4 uFaultNeutral;
        layout(color) uniform half4 uCoreNeutral;

        float angDist(float a, float b) {
            float d = mod(abs(a - b), TAU);
            return d > PI ? TAU - d : d;
        }

        // Geometry.base: rim plus weight gathered at the bottom, thinned
        // towards the lips of the opening.
        float baseT(float a) {
            float taper = uGapHalf < CLOSED ? 1.0 : smoothstep(uGapHalf, uGapHalf + 0.30, angDist(a, HALF_PI));
            return (uRim + uBottom * max(0.0, -sin(a))) * (0.46 + 0.54 * taper);
        }

        // Geometry.outer: mic taps, speaking lobes and the thinking arc, all
        // added outward only.
        float outerT(float a, float t) {
            float p = mod(a, TAU) / TAU * TAPS;
            float m = 0.0;
            for (int k = 0; k < ${Signals.TAPS}; k++) {
                float d = abs(p - float(k));
                d = min(d, TAPS - d);
                m += uMic[k] * max(0.0, 1.0 - d);
            }
            t *= 1.0 + uMicGain * m;
            float peak = pow(abs(sin((a - uPhase) * LOBES / 2.0)), 1.3);
            float lobed = (0.22 + 0.78 * peak) * (1.0 + uSwell * peak);
            t *= 1.0 + (lobed - 1.0) * uDash;
            float h = angDist(a, uHotAngle) / uHotWidth;
            if (uComet > 0.5) {
                float w = mod(a - uHotAngle + PI, TAU) - PI;
                h = w / (w < 0.0 ? ${Frame.COMET_HEAD} : ${Frame.COMET_TAIL});
            }
            t *= 1.0 + uHotAmount * exp(-h * h);
            return t;
        }

        // Source-over, premultiplied: [c] laid onto [dst] at [a].
        float4 over(float4 dst, float3 c, float a) {
            a = clamp(a, 0.0, 1.0);
            return float4(c * a + dst.rgb * (1.0 - a), a + dst.a * (1.0 - a));
        }

        // A circle stroked [w] wide at radius [c], antialiased over a pixel.
        float scribe(float r, float c, float w, float px) {
            return 1.0 - smoothstep(0.5 * w, 0.5 * w + px, abs(r - c));
        }

        half4 main(float2 fragCoord) {
            float px = 1.0 / uUnit;
            float2 uv = float2(fragCoord.x - uCentre.x, uCentre.y - fragCoord.y) * px;
            float r = length(uv);
            float a = r < 0.000001 ? 0.0 : atan(uv.y, uv.x);

            float b = baseT(a);
            float ti = min(max(0.3 * px, b), uR - px);
            float to = max(0.3 * px, outerT(a, b));
            float d = r - uR;
            float ring = d >= 0.0 ? d - to : -d - ti;
            float coreRing = d >= 0.0 ? d - max(0.3 * px, 0.44 * to) : -d - max(0.3 * px, 0.44 * ti);

            float gap = uGapHalf < CLOSED ? 1.0 : smoothstep(uGapHalf * 0.45, uGapHalf, angDist(a, HALF_PI));
            float body = smoothstep(0.0045, 0.0, ring);
            float core = smoothstep(px, 0.0, coreRing);

            float3 accent = mix(float3(uAccent.rgb), float3(uFaultNeutral.rgb), uDesat);
            float3 coreColour = mix(float3(uAccentCore.rgb), float3(uCoreNeutral.rgb), uDesat);

            // The echo keeps the opening, so it never reads as the fault's
            // second closed ring.
            float open = uGapHalf < CLOSED ? 1.0 : smoothstep(uGapHalf, uGapHalf + 0.01, angDist(a, HALF_PI));
            float echo = uEchoAlpha * open * scribe(r, uEchoR, max(px, 0.006), px);

            if (uInk > 0.5) {
                // Ink on a light ground: laid over it, one faint halo, the
                // faint lines heavier since no glow lifts them.
                float opacity = 0.55 + 0.45 * uLum;
                float halo = d >= 0.0 ? d - 2.2 * to : -d - 2.2 * ti;
                float line = max(0.8 * px, 0.005);
                float inner = clamp(2.2 * uLum * uScribe * scribe(r, uR * 0.42, line, px), 0.0, 1.0);
                float4 c = float4(accent * inner, inner);
                c = over(c, accent, 2.2 * uLum * 0.055 * scribe(r, uR * 1.38, line, px));
                c = over(c, accent, opacity * uDouble * scribe(r, uR * 1.16, max(1.2 * px, 0.008), px));
                c = over(c, accent, echo);
                c = over(c, accent, 0.12 * uGlow * gap * smoothstep(0.07, -0.07, halo));
                c = over(c, accent, opacity * gap * body);
                c = over(c, coreColour, 0.62 * uLum * gap * core);
                float3 ground = float3(uGround.rgb) * uGroundAlpha;
                return half4(half3(c.rgb + ground * (1.0 - c.a)), half(max(c.a, uGroundAlpha)));
            }

            float glow = exp(-max(ring, 0.0) * 30.0) * 0.40 * uGlow;
            float line = max(0.6 * px, 0.004);
            float rings = uScribe * scribe(r, uR * 0.42, line, px) + 0.055 * scribe(r, uR * 1.38, line, px);
            float second = 0.5 * uDouble * scribe(r, uR * 1.16, max(px, 0.007), px);

            float3 light = uLum * (accent * (rings + second + gap * (0.84 * body + glow))
                + coreColour * (0.62 * gap * core)) + accent * echo;

            float alpha = min(1.0, max(uGroundAlpha, max(light.r, max(light.g, light.b))));
            float3 rgb = min(float3(uGround.rgb) * uGroundAlpha + light, float3(alpha));
            return half4(half3(rgb), half(alpha));
        }
    """.trimIndent()
}
