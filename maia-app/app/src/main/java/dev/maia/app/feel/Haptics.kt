package dev.maia.app.feel

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Plays [Schedule] on the phone's motor, down the ladder in [rung].
 *
 * The only Android in the haptics. Everything that can be wrong about which
 * pulse plays when is in [Schedule] and [rung], and tested on the JVM.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    fun invoke() = play(Schedule.invoke)

    fun firstPartial(firstPartialMs: Long?) = play(Schedule.firstPartial(firstPartialMs))

    fun holdStep(index: Int) = play(Schedule.holdStep(index))

    fun commit() = play(Schedule.commit)

    fun fault() = play(Schedule.fault)

    // WrongConstant: every id comes from [id], which returns only
    // VibrationEffect.Composition's own constants, but lint cannot follow an
    // int through a when. Scoped to this one function.
    @SuppressLint("WrongConstant")
    fun play(pattern: Pattern) {
        val device = vibrator ?: return
        runCatching {
            val chosen = rung(device.hasVibrator(), Build.VERSION.SDK_INT, { set ->
                Build.VERSION.SDK_INT >= COMPOSITION_API &&
                    device.areAllPrimitivesSupported(*set.map(::id).toIntArray())
            }, pattern)
            val effect = when (chosen) {
                Rung.Silent -> return
                // [rung] only picks Composition at COMPOSITION_API and up; the
                // repeated check is for lint, which cannot see through it.
                Rung.Composition -> if (Build.VERSION.SDK_INT >= COMPOSITION_API) {
                    VibrationEffect.startComposition().apply {
                        pattern.pulses.forEach { addPrimitive(id(it.primitive), it.scale, it.delayMs) }
                    }.compose()
                } else {
                    return
                }
                Rung.Waveform -> VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, -1)
            }
            if (Build.VERSION.SDK_INT >= TOUCH_USAGE_API) {
                device.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                device.vibrate(effect)
            }
        }
    }

    private fun id(primitive: Primitive): Int = when (primitive) {
        Primitive.Click -> VibrationEffect.Composition.PRIMITIVE_CLICK
        Primitive.Tick -> VibrationEffect.Composition.PRIMITIVE_TICK
        Primitive.Thud -> VibrationEffect.Composition.PRIMITIVE_THUD
        Primitive.LowTick -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    }
}
