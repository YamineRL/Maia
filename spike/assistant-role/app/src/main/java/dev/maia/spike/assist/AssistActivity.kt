package dev.maia.spike.assist

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * G6, and the Activity host of brief section 1.4. Declared `showWhenLocked` and
 * `turnScreenOn` in the manifest, which are attributes rather than permissions:
 * the brief's section 7 note that no `DISABLE_KEYGUARD` or
 * `SYSTEM_ALERT_WINDOW` is needed rests on this.
 *
 * It prints the same lock facts as the session window, so the two can be
 * compared side by side when G4 and G6 disagree.
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val probe = probeLock(this)
        log("AssistActivity.onCreate $probe")

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(255, 20, 10, 30))
            setPadding(dp(20), dp(40), dp(20), dp(20))
            gravity = Gravity.START
        }
        column.addView(
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                text = buildString {
                    appendLine("AssistActivity (showWhenLocked, turnScreenOn)")
                    appendLine("elapsedRealtime = ${SystemClock.elapsedRealtimeNanos() / 1_000_000L} ms")
                    probe.lines().forEach { appendLine(it) }
                }
            },
        )
        column.addView(
            Button(this).apply {
                text = "Record 2 s, report peak"
                setOnClickListener {
                    PeakRecorder.record(this@AssistActivity) { line ->
                        (column.getChildAt(0) as TextView).append("\n$line")
                    }
                }
            },
        )
        column.addView(
            Button(this).apply {
                text = "Close"
                setOnClickListener { finish() }
            },
        )
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        log("AssistActivity.onResume ${probeLock(this)}")
    }

    override fun onPause() {
        log("AssistActivity.onPause ${probeLock(this)}")
        super.onPause()
    }
}
