package dev.maia.spike.assist

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The only reason this Activity exists: RECORD_AUDIO has to be granted before
 * anything locked is attempted, because a runtime prompt cannot be answered
 * from a lock screen. It doubles as the settingsActivity the assistant role and
 * the recognition service meta-data both have to name.
 *
 * It also prints, in one place, the two facts the checklist keeps asking for:
 * whether this app currently holds the role, and what
 * `Settings.Secure.voice_recognition_service` says (G7).
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            gravity = Gravity.START
        }

        status = TextView(this).apply { textSize = 13f }
        column.addView(status)

        column.addView(
            Button(this).apply {
                text = "Grant RECORD_AUDIO"
                setOnClickListener {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                }
            },
        )
        column.addView(
            Button(this).apply {
                text = "Open assistant settings"
                setOnClickListener {
                    // The chooser G1 is about. VOICE_INPUT_SETTINGS is the
                    // stable way in; on some builds it lands one screen above
                    // the assistant picker, which the checklist notes.
                    val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        startActivity(intent)
                    } catch (t: Throwable) {
                        status.append("\ncould not open settings: ${t.javaClass.simpleName}")
                    }
                }
            },
        )
        column.addView(
            Button(this).apply {
                text = "Refresh"
                setOnClickListener { render() }
            },
        )
        column.addView(
            Button(this).apply {
                text = "Record 2 s, report peak"
                setOnClickListener {
                    PeakRecorder.record(this@MainActivity) { line ->
                        status.append("\n$line")
                    }
                }
            },
        )

        setContentView(
            ScrollView(this).apply {
                addView(
                    column,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            } as View,
        )
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        log("MainActivity: RECORD_AUDIO grant result=${grantResults.firstOrNull()}")
        render()
    }

    private fun render() {
        val holdsRole = VoiceInteractionService.isActiveService(
            this,
            ComponentName(this, SpikeVoiceService::class.java),
        )
        val recogniser = try {
            Settings.Secure.getString(contentResolver, "voice_recognition_service") ?: "(unset)"
        } catch (t: Throwable) {
            "unreadable: ${t.javaClass.simpleName}"
        }
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val text = buildString {
            appendLine("Maia assist spike")
            appendLine()
            appendLine("holds assistant role = $holdsRole")
            appendLine("voice_recognition_service = $recogniser")
            appendLine("RECORD_AUDIO granted = $granted")
            appendLine("voice service bound  = ${SpikeVoiceService.instance != null}")
            appendLine()
            probeLock(this@MainActivity).lines().forEach { appendLine(it) }
            appendLine()
            appendLine("Build.FINGERPRINT:")
            appendLine(android.os.Build.FINGERPRINT)
        }
        status.text = text
        log("MainActivity: role=$holdsRole recogniser=$recogniser recordAudio=$granted")
    }
}
