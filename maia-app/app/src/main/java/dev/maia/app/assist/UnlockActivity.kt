package dev.maia.app.assist

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.maia.app.MaiaFlow
import dev.maia.app.MainActivity
import dev.maia.app.flow.FlowEvent

/**
 * The keyguard prompt, and nothing else. Row 7, [dev.maia.app.flow.Effect.RequestUnlock].
 *
 * **Why this exists at all.** The brief asks the session window to implement
 * `requestUnlock` through `requestDismissKeyguard`, and there is no overload
 * that will take a session window. Checked against the compile SDK
 * (`javap android.app.KeyguardManager`, android-36): the only signature is
 * `requestDismissKeyguard(android.app.Activity, KeyguardDismissCallback)`, and
 * `VoiceInteractionSession` hands out a `Dialog`, not an `Activity`. So the
 * session starts this, one Activity whose whole job is to ask, and the ask
 * happens inside the invocation the user made rather than at some later moment
 * of Maia's choosing.
 *
 * **It draws nothing.** Translucent, `noHistory`, `excludeFromRecents`, no
 * content view, gone as soon as the keyguard answers. It shows over the
 * keyguard because it has to be visible to be allowed to ask, which is what
 * `setShowWhenLocked` buys, and it is the one thing in Maia that is allowed to
 * be visible over a lock screen without anything of the user's on it.
 *
 * **A cancel says nothing.** [FlowEvent.Unlocked] is sent on
 * `onDismissSucceeded` alone. A wrong PIN, a back press or an error sends no
 * event at all, which is exactly the contract `Effect.RequestUnlock` states:
 * the reducer never hears back and the locked screen stays as it was, holding
 * the draft. Silence is the correct answer to a refused unlock, and it is the
 * same answer as no host being attached.
 */
class UnlockActivity : Activity() {

    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // In code rather than in the manifest because minSdk is 26 and the
            // attributes are 27. A manifest attribute the platform does not
            // know is ignored quietly; this way the version floor is written
            // where it can be read.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        ask()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        asked = false
        ask()
    }

    private fun ask() {
        if (asked) return
        asked = true
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard == null) {
            finish()
            return
        }
        if (!keyguard.isKeyguardLocked) {
            // Already open: the user unlocked while the session was on screen,
            // or there is no keyguard set at all. Same outcome as a successful
            // dismissal, so it takes the same path rather than asking for
            // something that has already happened.
            succeeded()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = succeeded()

                /** Wrong credential, back, or a device policy saying no. No event. */
                override fun onDismissCancelled() = finish()

                /** Cannot be asked here at all, per the platform. Also no event. */
                override fun onDismissError() = finish()
            },
        )
    }

    private fun succeeded() {
        MaiaFlow.controller(this).send(FlowEvent.Unlocked)

        // The reducer turns a waiting draft into a card, and the card is not
        // drawable in a session window: M2's `WhenEditor` opens a platform
        // `DatePickerDialog` (docs/M2-status.md section 0.1), so the flow needs
        // an Activity from here on. `needsActivityHost` in
        // `dev.maia.app.screens` is the same question asked of a state.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
