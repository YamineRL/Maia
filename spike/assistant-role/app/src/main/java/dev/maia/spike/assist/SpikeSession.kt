package dev.maia.spike.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One invocation. The content view is built in Kotlin against the framework:
 * no Compose, no layout XML, no library. M3 brief section 1.4 will choose
 * between this window and an Activity, and it chooses on what G4 and G6 see
 * here.
 */
class SpikeSession(context: Context) : VoiceInteractionSession(context) {

    private lateinit var facts: TextView
    private lateinit var result: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var lastShowFlags = 0
    private var lastShowAt = 0L
    private var shown = false

    private val tick = object : Runnable {
        override fun run() {
            renderFacts()
            if (shown) handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Section 1.3, the second half: ask the system not to send context, and
        // also drop it if the request is ignored. Both, because only one of
        // them is a request.
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        log("Session.onCreate")
    }

    override fun onCreateContentView(): View {
        val context = context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 16, 16, 20))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            gravity = Gravity.START
        }

        facts = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setTextIsSelectable(false)
        }
        root.addView(facts)

        result = TextView(context).apply {
            setTextColor(Color.argb(255, 160, 220, 255))
            textSize = 13f
            text = "ready"
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(result)

        // G5: two seconds of VOICE_RECOGNITION, peak sample reported.
        root.addView(
            Button(context).apply {
                text = "Record 2 s, report peak"
                setOnClickListener {
                    result.text = "..."
                    PeakRecorder.record(context) { line -> result.text = line }
                }
            },
        )

        // G6: startAssistantActivity to a showWhenLocked Activity.
        root.addView(
            Button(context).apply {
                text = "startAssistantActivity"
                setOnClickListener {
                    val intent = Intent(context, AssistActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    try {
                        startAssistantActivity(intent)
                        log("Session: startAssistantActivity called, ${probeLock(context)}")
                        result.text = "startAssistantActivity called, look at the screen"
                    } catch (t: Throwable) {
                        log("Session: startAssistantActivity threw ${t.javaClass.simpleName}: ${t.message}")
                        result.text = "threw ${t.javaClass.simpleName}: ${t.message}"
                    }
                }
            },
        )

        root.addView(
            Button(context).apply {
                text = "Hide session"
                setOnClickListener { hide() }
            },
        )

        renderFacts()
        return root
    }

    private fun renderFacts() {
        if (!::facts.isInitialized) return
        val probe = probeLock(context)
        val sinceShow = if (lastShowAt == 0L) "-" else
            "${(SystemClock.elapsedRealtimeNanos() - lastShowAt) / 1_000_000L} ms"
        facts.text = buildString {
            appendLine("Maia assist spike session")
            appendLine("showFlags        = ${describeShowFlags(lastShowFlags)}")
            appendLine("elapsedRealtime  = ${SystemClock.elapsedRealtimeNanos() / 1_000_000L} ms")
            appendLine("since onShow     = $sinceShow")
            probe.lines().forEach { appendLine(it) }
        }
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        log("Session.onPrepareShow flags=${describeShowFlags(showFlags)} ${probeLock(context)}")
        super.onPrepareShow(args, showFlags)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lastShowFlags = showFlags
        lastShowAt = SystemClock.elapsedRealtimeNanos()
        shown = true
        // G10 reads this line. It is the first instant the session knows it is
        // visible, so the gesture-to-onShow latency is this timestamp minus the
        // button press on the video, or minus onPrepareToShowSession in the log.
        log("Session.onShow flags=${describeShowFlags(showFlags)} args=${args?.keySet()} ${probeLock(context)}")
        renderFacts()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onLockscreenShown() {
        log("Session.onLockscreenShown ${probeLock(context)}")
        super.onLockscreenShown()
    }

    override fun onHide() {
        shown = false
        handler.removeCallbacks(tick)
        log("Session.onHide ${probeLock(context)}")
        super.onHide()
    }

    override fun onBackPressed() {
        log("Session.onBackPressed")
        super.onBackPressed()
    }

    override fun onDestroy() {
        shown = false
        handler.removeCallbacks(tick)
        log("Session.onDestroy")
        super.onDestroy()
    }

    // Section 1.3. Dropped without being read: not logged, not measured, not
    // counted. Only the fact that something arrived.
    override fun onHandleAssist(state: AssistState) {
        log("Session.onHandleAssist called, dropped unread")
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?,
    ) {
        log("Session.onHandleAssist(legacy) called, dropped unread")
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        log("Session.onHandleScreenshot called, dropped unread")
    }
}
