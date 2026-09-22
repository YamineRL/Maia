package dev.maia.app.assist

import android.Manifest
import android.app.KeyguardManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.maia.app.MaiaFlow
import dev.maia.app.MainActivity
import dev.maia.app.Warming
import dev.maia.app.flow.FlowEvent
import dev.maia.app.flow.FlowHost
import dev.maia.app.flow.FlowState
import dev.maia.app.flow.Origin
import dev.maia.app.flow.WarmMoment
import dev.maia.app.flow.aperture
import dev.maia.app.flow.hideMoment
import dev.maia.app.screens.FlowScreen
import dev.maia.app.screens.LockedBlock
import dev.maia.app.screens.LockedScreen
import dev.maia.app.screens.aperture
import dev.maia.app.screens.lockedScene
import dev.maia.app.screens.needsActivityHost
import dev.maia.app.ui.LocalMaiaColours
import dev.maia.app.ui.MaiaOrbHost
import dev.maia.app.ui.MaiaTheme
import dev.maia.app.ui.maiaColours
import kotlinx.coroutines.delay

/**
 * One invocation through the assistant role. Brief section 1.1.
 *
 * This class is the translation layer and nothing else: platform callbacks in,
 * [FlowEvent]s out. It decides nothing. Whether a locked invocation may capture,
 * what it may show and what it may write are all decided by the pure reducer in
 * `dev.maia.app.flow`, which is why those rules are provable on this box.
 *
 * **The content view, row 7.** The window draws through a `ComposeView`, which
 * needs three owners a session is not: a lifecycle, a saved state registry and
 * a view model store. They are implemented here, driven from the session's own
 * callbacks, and hung on the view tree. The lifecycle is the honest one: the
 * window is resumed while it is shown and back down to created when it is
 * hidden, so a collector started in composition stops when the window goes
 * away rather than holding the flow open behind a lock screen.
 *
 * **[FlowHost.requestUnlock] and [UnlockActivity].**
 * `KeyguardManager.requestDismissKeyguard` takes an `Activity` and there is no
 * other overload, while a session has a `Dialog`. So the ask happens in
 * [UnlockActivity], started from here inside the user's own invocation. A
 * cancelled or failed unlock sends no event, exactly as `Effect.RequestUnlock`
 * specifies, and the locked screen stays as it was with the draft still held.
 *
 * **[FlowHost.postDraftWaiting] stays a no-op here, deliberately.** The
 * notification needs `POST_NOTIFICATIONS`, and asking for a runtime permission
 * from a lock screen is precisely what brief section 4.1 forbids. The default
 * is the behaviour, not an omission.
 *
 * **What this window will not draw.** The card. M2's field editors open
 * platform dialogs (`docs/M2-status.md` section 0.1) and a session window
 * cannot host one, so when the flow reaches a state that needs an Activity
 * (`needsActivityHost`) this session hands over and takes itself down.
 *
 * **Screen context, the second half.** Brief section 1.3 asks for both halves
 * because the first is a request and the second is what holds if the request is
 * ignored. [MaiaVoiceService.onReady] makes the request with
 * `setDisabledShowContext`; [onHandleAssist] and [onHandleScreenshot] below are
 * the half that holds regardless. They drop what they are given without reading
 * it: no field is touched, nothing is stored, nothing is logged. Screen context
 * is P2 in PRD section 9 with its own permission story, and M3 must not
 * receive it by accident.
 */
class MaiaSession(context: Context) :
    VoiceInteractionSession(context),
    FlowHost,
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val flow = MaiaFlow.controller(context)

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /**
     * Present because `ComposeView` wants one on the tree, and empty because
     * nothing in this window holds state of its own: the flow is process
     * scoped, and a view model here would be a second copy of something that
     * has exactly one copy on purpose.
     */
    override val viewModelStore = ViewModelStore()

    /**
     * The two facts about the phone that the flow cannot know and a lock screen
     * has to answer for. Measured here, once per invocation, in the host, for
     * the same reason the keyguard is: a reducer that asked Android would be a
     * reducer this box could not test.
     */
    private var block by mutableStateOf<LockedBlock?>(null)

    /**
     * Whether the keyguard was up when this invocation began.
     *
     * Read once, here in the host, exactly as brief section 2.2 requires, and
     * carried into the flow as a value. The reducer never asks Android what the
     * keyguard is doing, so every locked rule can be tested without a phone.
     *
     * Read at [onPrepareShow] rather than at [onShow] because that is the
     * earliest callback of the invocation, so the answer is the state of the
     * phone at the moment the user made the gesture rather than after whatever
     * the system did next.
     */
    private var lockedAtInvocation: Boolean = false

    private val keyguard: KeyguardManager? =
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    /**
     * The owners come up before anything can be drawn.
     *
     * `performRestore(null)` because a session window restores nothing: it has
     * no saved instance state to be given and holds no state worth saving. The
     * registry still has to be restored before the lifecycle passes created, or
     * it throws.
     */
    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /**
     * The earliest callback of the invocation, and the warmth table's
     * [WarmMoment.PrepareShow]: the moment just before the user speaks, which is
     * where a recorder is worth reserving.
     *
     * The warmth call was missing until the spike ran. `Warming.at` had exactly
     * one caller, `MaiaVoiceService.onReady`, so three of the table's rows were
     * written and never reached, and the tile's reservation was never released
     * by anything. Wiring this moment and [onHide] is what makes the table
     * describe the code rather than a plan for it.
     */
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        lockedAtInvocation = keyguard?.isKeyguardLocked == true
        Warming.at(WarmMoment.PrepareShow, context)
    }

    /**
     * The window itself: one `ComposeView` with the three owners on its tree.
     *
     * `LockedScreen` or `FlowScreen`, chosen by `lockedScene`, which is a pure
     * function of the flow value plus the two blockers measured in [onShow].
     * The choice is not made here, in a composable nobody can assert about
     * without a phone; it is made in a function the JVM tests call directly.
     */
    override fun onCreateContentView(): View =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@MaiaSession)
            setViewTreeSavedStateRegistryOwner(this@MaiaSession)
            setViewTreeViewModelStoreOwner(this@MaiaSession)
            setContent {
                MaiaTheme(window = window?.window) {
                    val session by flow.session.collectAsStateWithLifecycle()
                    val writtenTo by flow.writtenTo.collectAsStateWithLifecycle()
                    val scene = lockedScene(session, block)
                    val handOff = scene == null && needsActivityHost(session.state)
                    LaunchedEffect(handOff) { if (handOff) handOver() }

                    // The undo countdown is the one thing on any of these
                    // screens that changes without an event, so the clock ticks
                    // only while one is offered and the window is otherwise as
                    // still as the flow is.
                    //
                    // elapsedRealtime and not wall time, corrected in row 15:
                    // the reducer sets `undoDeadline` on `MaiaFlow`'s clock,
                    // which is elapsedRealtime, and a countdown compared
                    // against a wall clock is roughly 56 years in the future,
                    // so it reads a frozen "8 s" until the tick expires it.
                    val ticking = session.state is FlowState.Confirmed
                    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                    LaunchedEffect(ticking) {
                        while (ticking) {
                            now = SystemClock.elapsedRealtime()
                            delay(250)
                        }
                    }
                    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
                        // One orb above the swap, so a locked screen giving way
                        // to the flow, or one flow state to the next, plays its
                        // spring instead of starting a new orb at rest.
                        MaiaOrbHost(
                            state = scene?.aperture ?: session.state.aperture,
                            micLevel = { flow.micLevel.value },
                            speech = { flow.speech.value },
                        ) {
                            if (scene != null) {
                                LockedScreen(scene = scene, onEvent = flow::send)
                            } else if (!handOff) {
                                FlowScreen(
                                    state = session.state,
                                    onEvent = flow::send,
                                    now = now,
                                    writtenTo = writtenTo,
                                )
                            }
                        }
                    }
                }
            }
        }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        flow.attach(this)
        // onShow can arrive without onPrepareShow on some paths, so the read is
        // repeated rather than trusted. It is still one read per invocation and
        // still before any event is sent.
        lockedAtInvocation = keyguard?.isKeyguardLocked == true
        block = measureBlock()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        flow.send(FlowEvent.Invoke(Origin.Assistant, locked = lockedAtInvocation))
    }

    /**
     * The two things a lock screen has to answer for, read at the same moment
     * as the keyguard and in the same place.
     *
     * Order matters and is the order in which a user is blocked. A phone that
     * has not been unlocked since the reboot cannot reach credential encrypted
     * storage at all, so whether the microphone is granted is not yet a
     * question anyone can act on.
     *
     * Neither is a permission request. `RECORD_AUDIO` is checked, never asked
     * for: brief section 4.1 forbids a prompt from a lock screen, and the
     * screen that comes out of this says so in words instead.
     */
    private fun measureBlock(): LockedBlock? {
        val user = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (user?.isUserUnlocked == false) return LockedBlock.BeforeFirstUnlock
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) null else LockedBlock.MicrophoneDenied
    }

    /**
     * The window went away: dismissed, or the screen went off.
     *
     * [FlowEvent.Hidden] and not [FlowEvent.Cancel], because they mean
     * different things to the reducer. Hidden stops an open microphone and
     * leaves a queued draft in the queue; cancel is the user saying no.
     *
     * Detached before the event, so that the `HideSession` the reducer may emit
     * in reply is not delivered back to a window that is already going away.
     *
     * **The recorder stops here, and the phone is why.** G8 in
     * `spike/assistant-role/README.md`: pressing the sleep key hides the session
     * within about 10 ms and the platform does nothing to capture. The spike's
     * recorder ran on for 1.2 s afterwards and was released only because its own
     * read finished. So capture kept going past the point at which the user
     * could see anything, on a screen that was already dark. Stopping it is this
     * method's job and nobody else's.
     *
     * The stop itself is `Effect.StopCapture(discardAudio = true)`, which the
     * reducer already emits for [FlowEvent.Hidden] from the recording states.
     * That effect runs through the same runner as every other, so the audio is
     * discarded by the same path that discards it on cancel, and there is no
     * second recorder handle here to get out of step with it. The moment is read
     * before the event is sent, because sending it is what changes the state.
     */
    override fun onHide() {
        val moment = hideMoment(flow.session.value.state)
        flow.detach(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        flow.send(FlowEvent.Hidden)
        Warming.at(moment, context)
        super.onHide()
    }

    /**
     * The window is finished with. The lifecycle goes down before the view
     * model store is cleared, which is the order every other host in Android
     * uses and the order anything observing the lifecycle expects.
     */
    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }

    /**
     * Back is the user saying no, so it is a cancel, and then the window goes
     * away through the ordinary path, which produces [FlowEvent.Hidden] as
     * well. Both are correct: the user refused, and the window is gone.
     */
    override fun onBackPressed() {
        flow.send(FlowEvent.Cancel)
        super.onBackPressed()
    }

    /**
     * The one effect only this host can run. The session window is the only
     * thing in Maia the flow has to ask to go away; every other host finishes
     * itself.
     */
    override fun hideSession() {
        hide()
    }

    /** The answer or the run is drawn by `MainActivity`, so go there. */
    override fun showSurface() {
        handOver()
    }

    /**
     * Ask for the keyguard, through the one thing that is allowed to ask.
     *
     * `startAssistantActivity` rather than `startActivity`, because an
     * assistant session starting an Activity over a lock screen is exactly what
     * that method is for. The session stays up: an unlock that is cancelled
     * must leave the user looking at the screen they were looking at, holding
     * the same draft, and the reducer hears nothing either way. What moves the
     * flow on is [FlowEvent.Unlocked] from [UnlockActivity], and only success
     * sends it.
     */
    override fun requestUnlock() {
        startAssistantActivity(
            Intent(context, UnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Hand the flow to an Activity and take this window down.
     *
     * Not a decision this class makes: `needsActivityHost` is the decision, it
     * is pure, and it is tested. This is the two calls that carry it out. The
     * flow is process scoped, so nothing is passed across; the Activity renders
     * whatever the flow is already doing.
     */
    private fun handOver() {
        startAssistantActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        hide()
    }

    /** Dropped unread. See the class comment. */
    override fun onHandleAssist(state: AssistState) = Unit

    /**
     * The pre-API-30 delivery of the same thing, dropped the same way. Both are
     * overridden because the platform chooses which one to call by build, and a
     * default implementation that reads the structure is exactly the accident
     * this is here to prevent.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) = Unit

    /** Dropped unread, and never recycled into anything. */
    override fun onHandleScreenshot(screenshot: Bitmap?) = Unit
}
