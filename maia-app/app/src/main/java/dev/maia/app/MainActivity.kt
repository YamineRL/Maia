package dev.maia.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.maia.actions.CalendarRepository
import dev.maia.actions.MaiaCalendar
import dev.maia.actions.ProviderCalendars
import dev.maia.actions.notes.NotesFolderStore
import dev.maia.actions.notes.SafNotes
import dev.maia.app.card.CalendarChooser
import dev.maia.app.card.openInProtonCalendar
import dev.maia.app.feel.Haptics
import dev.maia.app.feel.Schedule
import dev.maia.app.flow.FaultReason
import dev.maia.app.flow.FlowEvent
import dev.maia.app.flow.FlowHost
import dev.maia.app.flow.FlowState
import dev.maia.app.flow.Origin
import dev.maia.app.flow.Surface
import dev.maia.app.flow.aperture
import dev.maia.app.flow.loadNotesFolder
import dev.maia.app.notes.FolderPicker
import dev.maia.app.notes.folderGone
import dev.maia.app.agent.AgentHost
import dev.maia.app.agent.aperture
import dev.maia.app.agent.toolCalls
import dev.maia.app.agent.RunFault
import dev.maia.app.answer.AnswerHost
import dev.maia.app.answer.AnswerStatus
import dev.maia.app.answer.PermNeeded
import dev.maia.app.screens.AnswerCopy
import dev.maia.app.screens.AnswerScreen
import dev.maia.app.screens.ChooserScreen
import dev.maia.app.screens.RunFaultCopy
import dev.maia.app.screens.RunFaultScreen
import dev.maia.app.screens.FlowScreen
import dev.maia.app.screens.PairingCopy
import dev.maia.app.screens.PairingNeededScreen
import dev.maia.app.screens.PairingScreen
import dev.maia.app.screens.RunAction
import dev.maia.app.screens.RunScreen
import dev.maia.app.screens.VoiceCard
import dev.maia.app.screens.aperture
import dev.maia.app.settings.EventsTo
import dev.maia.app.settings.MaiaPrefs
import dev.maia.app.settings.SettingsActivity
import dev.maia.app.ui.DownloadCopy
import dev.maia.app.ui.LocalMaiaColours
import dev.maia.app.ui.DownloadStrip
import dev.maia.app.ui.Maia
import dev.maia.app.ui.MaiaOrbHost
import dev.maia.app.ui.MaiaTheme
import dev.maia.app.ui.maiaColours
import dev.maia.audio.speech.VoiceStore
import dev.maia.orb.ApertureState
import dev.maia.nlu.EventDraft
import dev.maia.nlu.agent.ProjectRef
import dev.maia.transport.PermissionReply
import dev.maia.transport.ProjectEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * The Activity host for the one flow. M3 row 15.
 *
 * **What this replaces.** Until M3 this was M1's instrument: a
 * `DictationViewModel` with its own screen state, its own parser, its own
 * calendar repository and its own four-way `Screen` router. M2 wrote the
 * stateless screens in [dev.maia.app.screens] and never wired them in, and M3
 * put the machine behind them at process scope in [MaiaFlow]. So there were two
 * state machines in one process and the locked path, which hands over to this
 * Activity when an unlock turns a waiting draft into a card, landed on the
 * wrong one. This class is the wiring: one controller, one set of screens, and
 * a view model nowhere.
 *
 * **It renders, it does not decide.** Everything drawn comes from
 * `FlowController.session`, every touch leaves as a [FlowEvent], and the three
 * things with no event of their own (the chooser, 4i's "Check again", 4g's date
 * picker) are the host's by [FlowScreen]'s own contract. The rules stay in the
 * pure reducer where this box can test them.
 *
 * **It is the one host that can implement all three [FlowHost] methods**, and
 * [postDraftWaiting] is the one that matters. `MaiaSession` must not implement
 * it, because it is the host that is up while the phone is locked and asking
 * for `POST_NOTIFICATIONS` from a lock screen is exactly what the locked policy
 * forbids. Here the ask happens the first time a queued draft is reviewed after
 * an unlock, which is the only moment at which the reason can be stated
 * truthfully, and this Activity cannot be on screen over a keyguard.
 *
 * **It never draws a locked screen**, which is the one place it deliberately
 * departs from `MaiaSession`. It is not `showWhenLocked`, so if it is visible
 * the keyguard is down, whatever the flow last believed. A user who unlocks
 * their phone themselves sends no [FlowEvent.Unlocked] from anywhere, so
 * [onStart] sends it: the Activity being on screen is the evidence, and the
 * same road that opens a waiting draft after `UnlockActivity` opens it here.
 */
class MainActivity : ComponentActivity(), FlowHost {

    private val flow by lazy { MaiaFlow.controller(this) }

    private val notifier by lazy { DraftNotifier(this) { flow.session.value.queue } }

    /**
     * The host's own motor, for the one thing on these screens that is felt
     * and is not an effect of the flow: confirming that a pairing was
     * forgotten. Everything else that vibrates goes through `EffectRunner` or
     * `RunAlerts`, and neither of those knows about a screen.
     */
    private val haptics by lazy { Haptics(this) }

    /**
     * The host's own provider handle, for the three things that are the host's
     * and are not effects: listing calendars for the chooser, remembering a
     * choice, and re-reading the default target for 4i. There is no reducer
     * state for a chooser, so there is no effect for one either.
     */
    private val calendars: CalendarRepository by lazy { ProviderCalendars(this) }

    /**
     * The host's own handle on the notes folder, for the one thing about it
     * that is the host's: taking the grant the picker returns. It shares its
     * stored tree with the flow's `SafNotes` through the same preferences, so
     * the re-read after a pick sees it.
     */
    private val notes by lazy { SafNotes(this) }

    /**
     * D2's "Choose a folder". Why this Activity hosts it is in [FolderPicker].
     * After a pick the folder is re-read and sent as the same
     * [FlowEvent.NotesFolderLoaded] the note card waits for, which returns to
     * the card with the folder named; the note is written by the hold, as
     * every note is. A cancel calls nothing and the screen stays as it was.
     */
    private val folderPicker = FolderPicker(this, { notes }) {
        flow.send(loadNotesFolder(notes, LocalDate.now()))
    }

    /** Whether the no-folder screen showing is the "not there any more" one. See [folderGone]. */
    private var noFolderGone by mutableStateOf(false)

    private val keyguard: KeyguardManager?
        get() = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    private var micGranted by mutableStateOf(false)

    /** Non-null while the chooser is open. The list it is drawing, never a flag plus a list. */
    private var chooser by mutableStateOf<List<MaiaCalendar>?>(null)

    /** 4g's "Pick a date". A platform dialog, which is why this flow needs an Activity at all. */
    private var pickingDate by mutableStateOf(false)

    /**
     * An invocation the microphone permission got in the way of, replayed once
     * the user answers. Dropped on a denial: the fault the capture would take
     * would say less than the dialog just did.
     */
    private var deferredInvoke: FlowEvent? = null

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted = granted
            val deferred = deferredInvoke
            deferredInvoke = null
            if (granted && deferred != null) flow.send(deferred)
        }

    /**
     * Asked for when the first card opens, not at launch: the sentence that
     * needs a calendar has just been spoken, which is the one moment the
     * question explains itself. The answer re-reads the target either way,
     * because a denied read must not be mistaken for a phone with no calendars.
     */
    private val requestCalendar =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            reloadTarget()
        }

    /** Asked at most once per process, and only when something is actually waiting. */
    private var notificationsAsked = false

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Granted or not, the notifier decides what it may do. A denial is
            // a behaviour and not a fault: the drafts are still in the queue.
            notifier.post()
        }

    /**
     * The answer surface's permission ask, M9. Fired from the Grant button a
     * `Permission` fault draws: camera for the torch, contacts for a spoken
     * name, calendar for a read the provider refused. A grant re-runs the
     * same command through `retry`, which is the whole recovery path; a
     * denial leaves the explanation on screen and changes nothing.
     */
    private val requestAssistantPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) AnswerHost.current()?.retry()
        }

    /** Whether the run screen has the window. Set by a run, cleared by back. */
    private var runOpen by mutableStateOf(false)

    /**
     * Whether the answer surface has the window. Set by a non-idle
     * [AnswerStatus], cleared by back or by a run taking the window back:
     * the two surfaces are siblings and never drawn together, and the latest
     * sentence wins.
     */
    private var answerOpen by mutableStateOf(false)

    /**
     * The voice offer's session flag (M9 PRD section 9). `Not now` is an
     * in-memory refusal only: it lasts the session, it is never written to
     * preferences, and the offer may come back next time, because refusing
     * the voice must leave show-only conversation fully working rather than
     * permanently decide anything.
     */
    private var voiceDeclined by mutableStateOf(false)

    /**
     * What the voice card shows while a download is in flight or just
     * failed. Null means the offer is at rest: either not earned yet or not
     * needed.
     */
    private var voiceCard by mutableStateOf<VoiceCard?>(null)

    /**
     * Whether the voice model is on the phone, checked lazily when the first
     * answer lands: a handful of file stats, kept off the frame path the
     * same way the preferences reads are.
     */
    private var voiceReady by mutableStateOf(false)

    /** The one in-flight voice download, so `Download` cannot be pressed twice. */
    private var voiceJob: Job? = null

    /** The voice directory, shared with the speaker's in `AndroidEffects`. */
    private val voiceStore by lazy { VoiceStore(File(filesDir, "voice")) }

    /**
     * The run screen's two controls (section 1.4).
     *
     * Stop goes straight to the driver rather than through the flow: it is an
     * interrupt on a stream this process is holding open, and routing it
     * through a reducer that knows nothing about the stream would only add a
     * hop. Ask again is the ordinary invocation, which takes the window back
     * and leaves the run where it is.
     */
    private fun onRunAction(action: RunAction) {
        when (action) {
            RunAction.Stop -> AgentHost.current()?.stop()
            RunAction.AskAgain -> onEvent(FlowEvent.Press)
            // The three answers, which go straight to the driver for the same
            // reason `Stop` does: they are a reply on a stream this process is
            // holding open, and a reducer that knows nothing about the stream
            // would only add a hop.
            RunAction.Allow -> AgentHost.current()?.answer(PermissionReply.ONCE)
            RunAction.AllowSession -> AgentHost.current()?.answer(PermissionReply.ALWAYS)
            is RunAction.Refuse -> AgentHost.current()?.answer(PermissionReply.REJECT, action.note)
            // Section 5.18, by the same road: an answer to a question is a
            // reply on the open stream, and the phone decides nothing about
            // it that the run machine does not decide.
            is RunAction.Option -> AgentHost.current()?.option(action.index)
            RunAction.SendAnswer -> AgentHost.current()?.sendAnswer()
            RunAction.SkipQuestion -> AgentHost.current()?.skipQuestion()
            RunAction.DropQuestion -> AgentHost.current()?.dropQuestion()
            RunAction.SendAgain -> AgentHost.current()?.sendAgain()
            RunAction.SayAgain -> AgentHost.current()?.sayAgain()
        }
    }

    /**
     * A project was chosen from the list, by tap or by number.
     *
     * The held instruction goes as soon as the choice is made, which is what
     * `m8_list_held_note` promised on the screen above it. With nothing held
     * the user was moving the stream rather than sending, so the stream moves
     * and nothing is sent.
     *
     * The spoken transcript is the number, because a tap is how the user said
     * it. That string only ever reaches `YOU SAID` on a later chooser, and a
     * choice that resolves will not raise one.
     */
    private fun pickProject(entry: ProjectEntry) {
        val driver = AgentHost.current() ?: return
        val held = AgentHost.state.value.chooser?.held
        val ref = ProjectRef.Numbered(entry.number)
        if (held.isNullOrBlank()) {
            driver.focus(ref, "${entry.number}")
        } else {
            driver.instruct(ref, held, "${entry.number}")
        }
    }

    /**
     * `Discard`, and the back gesture that means the same thing.
     *
     * Nothing is sent and the words are forgotten, which is
     * `m8_list_discard_cd` word for word. The run screen closes with it: what
     * was on it was the question, and the question has been answered with no.
     */
    private fun discardChoice() {
        AgentHost.current()?.dismiss()
        runOpen = false
    }

    /**
     * The single action on a fault screen, which is a different thing on each.
     *
     * `m8_tunnel_off_action` opens the tunnel app, which is a separate
     * application the user already has: this is the one place in Maia that
     * hands off to it, and a phone without it installed gets nothing rather
     * than a crash. `m8_no_server_action` is `Try again`, and trying again is
     * the same sentence sent once more, which the user has to say: what this
     * does is clear the screen so the microphone is theirs again.
     */
    private fun onFaultAction(fault: RunFault?) {
        when (fault) {
            RunFault.TunnelOff -> {
                val intent = packageManager.getLaunchIntentForPackage(TUNNEL_PACKAGE)
                if (intent != null) startActivity(intent) else discardChoice()
            }
            else -> discardChoice()
        }
    }

    /**
     * `m8_auth_action`: the passphrase, entered again.
     *
     * Straight into [AgentHost.repair], which puts it together with the
     * address already stored and drops the wiring so no stream outlives the
     * credential it was opened with. The typed text is not held anywhere in
     * this class: it arrives as an argument and leaves in the same
     * expression, and the address never comes up here at all.
     */
    private fun retryPassphrase(passphrase: String) {
        if (AgentHost.repair(this, passphrase)) {
            // Nothing to dismiss: storing a pairing drops the wiring and the
            // run state with it, so the fault screen has already gone. All
            // that is left is to stop holding the run surface open over a run
            // that no longer exists.
            runOpen = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micGranted = granted(Manifest.permission.RECORD_AUDIO)

        // Two kinds of getting-ready, kept off the frame path.
        //
        // The controller is built on a background thread so that the
        // SharedPreferences opens and model-file checks inside
        // `AndroidEffects.forApp` and `modelsPresent` are not paid inside the
        // first composition. [flow] is still the lazy below: if composition
        // reaches it first the same synchronized build happens there, which is
        // exactly what happened before this line existed.
        lifecycleScope.launch(Dispatchers.Default) { MaiaFlow.controller(applicationContext) }
        // The engine warm waits for the window's first draw: the load is
        // seconds of work on a background thread and must not compete with
        // the frame it is buying time for. `warmEngineIfPresent` rather than
        // `warmEngine`, because a fresh install has no model to load and its
        // download is the first-run screen's to start.
        window.decorView.doOnPreDraw {
            EngineHolder.warmEngineIfPresent(this)
            EngineHolder.fetchLocalModel(this)
        }

        setContent {
            MaiaTheme {
                val session by flow.session.collectAsStateWithLifecycle()
                val writtenTo by flow.writtenTo.collectAsStateWithLifecycle()
                val warmth by EngineHolder.warmth.collectAsStateWithLifecycle()
                val state = session.state

                // The undo countdown is the only thing on any of these screens
                // that changes without an event, so the clock ticks only while
                // one is offered. elapsedRealtime and not wall time, because
                // that is the clock the reducer set the deadline on.
                val ticking = state is FlowState.Confirmed
                var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                LaunchedEffect(ticking) {
                    while (ticking) {
                        now = SystemClock.elapsedRealtime()
                        delay(250)
                    }
                }

                // The card and the no-calendar screen both need to have read
                // the provider. Asked when one of them opens, for the reason
                // above.
                val needsCalendar = state is FlowState.Preview || state is FlowState.NoCalendar
                LaunchedEffect(needsCalendar) {
                    if (!needsCalendar) return@LaunchedEffect
                    // Events go to Proton by the user's choice (Settings, or
                    // the no-calendar screen): the draft goes straight to
                    // Proton's own new-event screen, where it is edited and
                    // saved, and this card never opens. No permission asked,
                    // because nothing here reads or writes the provider.
                    val draft = (state as? FlowState.Preview)?.draft ?: (state as? FlowState.NoCalendar)?.draft
                    if (draft != null && MaiaPrefs(this@MainActivity).eventsTo == EventsTo.Proton) {
                        openInProtonCalendar(this@MainActivity, draft)
                        flow.send(FlowEvent.Cancel)
                        return@LaunchedEffect
                    }
                    if (!calendarGranted()) requestCalendar.launch(CALENDAR_PERMISSIONS)
                }

                // 4g's picker, and only over 4g: a fault that moved on while
                // the dialog was up takes the dialog with it.
                val dateDraft = (state as? FlowState.Fault)
                    ?.takeIf { it.reason == FaultReason.NoDateHeard }
                    ?.draft
                if (pickingDate && dateDraft != null) DatePicker(dateDraft)

                // D2's two first lines. Read from the store when the screen
                // opens, off the main thread, because the state does not say.
                val noFolder = state is FlowState.NoFolder
                LaunchedEffect(noFolder) {
                    if (noFolder) {
                        noFolderGone = withContext(Dispatchers.IO) {
                            runCatching {
                                folderGone(NotesFolderStore.sharedPreferences(this@MainActivity).treeUri())
                            }.getOrDefault(false)
                        }
                    }
                }

                // The run screen, which is the only surface in Maia whose
                // life is minutes rather than one sentence. It opens on the
                // instruction and stays until the user goes back: closing it
                // stops nothing, because the stream is the driver's and the
                // foreground service's, not this window's.
                val run by AgentHost.state.collectAsStateWithLifecycle()
                LaunchedEffect(run.project?.number, run.turn?.startedAt, run.chooser, run.fault) {
                    if (run.project != null || run.chooser != null || run.fault != null) {
                        runOpen = true
                        // The latest sentence wins: a run and an answer never
                        // share the window.
                        answerOpen = false
                    }
                }

                // The answer surface, M9's twin of the run screen above. It
                // opens on the first non-idle status and takes the window
                // back from a run for the same reason the run takes it back
                // from the flow: the user just spoke and is owed the reply.
                val answer by AnswerHost.state.collectAsStateWithLifecycle()
                LaunchedEffect(answer.status) {
                    if (answer.status != AnswerStatus.Idle) {
                        answerOpen = true
                        runOpen = false
                    }
                }

                // The voice offer, section 9. Readiness is a handful of file
                // stats on the voice directory, done once per answer landing
                // and off the frame path. The offer exists only while there
                // is an answer worth voicing, the files are not already
                // there, and the user has not declined this session.
                LaunchedEffect(answer.status) {
                    if (answer.status == AnswerStatus.Answered && !voiceReady) {
                        voiceReady = withContext(Dispatchers.IO) { voiceStore.isComplete }
                    }
                }
                val voiceOffer = voiceCard ?: if (
                    answer.status == AnswerStatus.Answered &&
                    answer.answer.isNotBlank() &&
                    !voiceReady &&
                    !voiceDeclined
                ) {
                    VoiceCard()
                } else {
                    null
                }
                val listReach by AgentHost.listReach.collectAsStateWithLifecycle()
                // Section 5.15's one condition, read cold the first time so a
                // fault screen drawn before any driver exists still knows
                // whether this pairing has ever worked.
                val everSucceeded by AgentHost.everSucceeded.collectAsStateWithLifecycle()
                // A SharedPreferences read, so off the main thread this
                // effect runs on: first open of the file is disk.
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) { AgentHost.everSucceeded(this@MainActivity) }
                }
                // Null on every fault that is not a screen of its own: a turn
                // that died mid-run keeps the run screen and its partial
                // reply, and a number that does not exist is a heading on the
                // list rather than a screen.
                val faultPanel = RunFaultCopy.panel(run.fault, everSucceeded)

                // Pairing. `needsPairing` is raised by an agent sentence
                // arriving on a phone that has never been paired, which used
                // to be silence: see `AgentHost.needsPairing`. It is checked
                // before the run screen because there is no run, and before
                // the flow screen because the user just spoke and is owed an
                // answer to the sentence they said.
                val needsPairing by AgentHost.needsPairing.collectAsStateWithLifecycle()
                var pairOpen by remember { mutableStateOf(false) }
                // Not `rememberSaveable`: the address embeds a pre-shared key
                // and a saved instance state bundle is written to disk by the
                // system. A rotation costs the user a re-paste and that is the
                // cheaper side of this trade.
                var draftAddress by remember { mutableStateOf("") }
                // Section 5.15: `m8_pair_forgotten` appears once, where
                // `m8_pair_address_stored` was. Held here and not in the
                // screen because the screen is redrawn from storage and a
                // confirmation that survived a recomposition would stop being
                // "once".
                var forgotten by remember { mutableStateOf(false) }

                CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
                    val open = chooser
                    // The one orb, over every screen below. Its pose is chosen by
                    // the same ladder that chooses the screen; the screens with no
                    // seat (the pairing prompt, the project list, the run faults,
                    // the calendar chooser) hide it, so their line only has to be
                    // something.
                    val runShown = runOpen && state is FlowState.Idle && run.chooser == null && faultPanel == null
                    val orbState = when {
                        pairOpen || needsPairing -> ApertureState.Dormant
                        runShown -> run.aperture
                        runOpen && state is FlowState.Idle -> ApertureState.Dormant
                        answerOpen && state is FlowState.Idle -> answer.aperture
                        open != null -> ApertureState.Dormant
                        else -> state.aperture
                    }
                    MaiaOrbHost(
                        state = orbState,
                        micLevel = { flow.micLevel.value },
                        speech = { flow.speech.value },
                        // Passed whichever screen is up: the count only climbs
                        // within a turn, so coming back to the run is not a
                        // burst of steps, and only the working poses show it.
                        toolCalls = run.toolCalls,
                    ) {
                        if (pairOpen) {
                            BackHandler { pairOpen = false }
                            PairingScreen(
                                panel = PairingCopy.panel(
                                    // Read on each composition rather than held:
                                    // the step is three enum values and re-reading
                                    // it is how storing a pairing moves the screen
                                    // on without a second source of truth about it.
                                    // Read inside this branch, where it is used, so
                                    // a preferences read is not paid on every
                                    // composition of every other screen.
                                    AgentHost.step(this@MainActivity),
                                    addressDraft = draftAddress.isNotBlank(),
                                    forgotten = forgotten,
                                    // Read on each composition, as the step is:
                                    // storing the credential below moves the
                                    // field to its stored caption without a
                                    // second source of truth about it.
                                    assistantStored = AnswerHost.assistantReady(this@MainActivity),
                                ),
                                showCopied = PairingCopy.showCopied(Build.VERSION.SDK_INT),
                                // Both halves or neither: `AgentHost.pair` refuses
                                // a half and returns false, and the refusal is
                                // what brings the passphrase field out. Nothing
                                // stores an address on its own.
                                onContinue = { address, passphrase ->
                                    forgotten = false
                                    if (!AgentHost.pair(this@MainActivity, address, passphrase)) {
                                        draftAddress = address
                                    }
                                },
                                // The gateway credential (M9 section 8.1), stored
                                // by the one road it has into this process. Only
                                // ever called with a non-blank field.
                                onAssistant = {
                                    AnswerHost.setAssistantPassphrase(this@MainActivity, it)
                                },
                                onCopyKey = ::copyNodeKey,
                                onDone = { pairOpen = false },
                                onForget = {
                                    AgentHost.unpair(this@MainActivity)
                                    // The credential goes with the pairing
                                    // (`clear` removes its key); this call is
                                    // for the other half, dropping the answer
                                    // wiring so no channel outlives it.
                                    AnswerHost.forgetAssistant(this@MainActivity)
                                    draftAddress = ""
                                    forgotten = true
                                    // Section 4.2's stop-landed pattern: one THUD
                                    // and nothing after it. Explicitly not the
                                    // commit pattern, because nothing was written.
                                    haptics.play(Schedule.agentStopped)
                                },
                            )
                        } else if (needsPairing) {
                            BackHandler { AgentHost.pairingSeen() }
                            PairingNeededScreen(onSetUp = {
                                AgentHost.pairingSeen()
                                pairOpen = true
                            })
                        } else if (runOpen && state is FlowState.Idle && run.chooser != null) {
                            // The list, and the three headings over it. Back is
                            // the same as `Discard`: the user is saying no to the
                            // question Maia asked, and leaving the held sentence
                            // behind to be answered later would be a sentence that
                            // sends itself the next time the screen opens.
                            BackHandler { discardChoice() }
                            ChooserScreen(
                                chooser = run.chooser!!,
                                reach = listReach,
                                onPick = ::pickProject,
                                onDiscard = ::discardChoice,
                                onSync = AgentHost::refreshList,
                            )
                        } else if (runOpen && state is FlowState.Idle && faultPanel != null) {
                            BackHandler { discardChoice() }
                            RunFaultScreen(
                                panel = faultPanel,
                                retired = run.retired,
                                onAction = { onFaultAction(run.fault) },
                                onPassphrase = ::retryPassphrase,
                            )
                        } else if (runOpen && state is FlowState.Idle) {
                            // Back leaves the run on screen in every sense that
                            // matters: it is still streaming, the notification is
                            // still there, and coming back finds it where it was.
                            BackHandler { runOpen = false }
                            // Section 5.17: the blocked row is cancelled when the
                            // run screen is opened on that project, and with it
                            // section 4.2's twenty second repeat. On every resume
                            // rather than once, because the case this exists for
                            // is a user who tapped the notification, and because a
                            // phone put down and picked up again is the same
                            // person arriving. It sends nothing and leaves the
                            // block alone: the two controls are on this screen.
                            LifecycleResumeEffect(Unit) {
                                AgentHost.current()?.seen()
                                // The window going is the one thing that closes a
                                // question's microphone on its own. Android will
                                // not: a capture that outlives the foreground
                                // returns silence rather than an error, so the
                                // screen would go on saying the microphone is
                                // open over a phone in a pocket.
                                onPauseOrDispose { AgentHost.current()?.hidden() }
                            }
                            RunScreen(
                                state = run,
                                onAction = ::onRunAction,
                            )
                        } else if (answerOpen && state is FlowState.Idle) {
                            // The answer surface, sibling to the run screen and
                            // behind the same two rules: it draws only over an
                            // idle flow, and back gives the window back without
                            // destroying anything. `hidden` releases the voice,
                            // which is the only thing on this surface that can
                            // keep going after the window is gone; the
                            // conversation itself survives, or a power-button
                            // follow-up would have nothing to follow.
                            BackHandler {
                                answerOpen = false
                                AnswerHost.current()?.hidden()
                            }
                            LifecycleResumeEffect(Unit) {
                                AnswerHost.current()?.seen()
                                onPauseOrDispose { AnswerHost.current()?.hidden() }
                            }
                            AnswerScreen(
                                state = answer,
                                // The same invoke the Speak button sends, which is
                                // what "ask another" is: a fresh capture, routed
                                // through the flow like every other sentence.
                                // The surface closes so the listening screen can
                                // show, and re-opens when the new status lands.
                                onAskAnother = {
                                    answerOpen = false
                                    onEvent(FlowEvent.Press)
                                },
                                onStop = { AnswerHost.current()?.stop() },
                                onConfirm = { AnswerHost.current()?.confirm() },
                                onRetry = { AnswerHost.current()?.retry() },
                                onClear = { AnswerHost.current()?.clear() },
                                onTyped = { AnswerHost.current()?.typed(it) },
                                onGrant = { which ->
                                    requestAssistantPermission.launch(
                                        when (which) {
                                            PermNeeded.Camera -> Manifest.permission.CAMERA
                                            PermNeeded.Contacts -> Manifest.permission.READ_CONTACTS
                                            PermNeeded.Calendar -> Manifest.permission.READ_CALENDAR
                                        },
                                    )
                                },
                                voice = voiceOffer,
                                onVoiceDownload = ::downloadVoice,
                                onVoiceDecline = {
                                    voiceDeclined = true
                                    voiceCard = null
                                },
                            )
                        } else if (open != null) {
                            BackHandler { chooser = null }
                            CalendarChooser(
                                calendars = open,
                                currentId = (state as? FlowState.Preview)?.target?.calendar?.id,
                                onChoose = ::choose,
                                onBack = { chooser = null },
                            )
                        } else {
                            // Back is the user saying no, which is a Cancel, on
                            // every screen that has something to say no to. On Idle
                            // and on first run it is the user leaving the app, and
                            // the system default does that better than any event.
                            BackHandler(enabled = state !is FlowState.Idle && state !is FlowState.FirstRun) {
                                flow.send(FlowEvent.Cancel)
                            }
                            Box(Modifier.fillMaxSize()) {
                                FlowScreen(
                                    state = state,
                                    onEvent = ::onEvent,
                                    now = now,
                                    // The download's byte counts are already in the
                                    // state, put there by DownloadProgress. Only the
                                    // rate is not, because no event carries it, so it
                                    // is read from the one place that measures it.
                                    bytesPerSecond = (warmth as? Warmth.Downloading)?.bytesPerSecond ?: 0.0,
                                    writtenTo = writtenTo,
                                    onOpenChooser = ::openChooser,
                                    onCheckCalendars = ::reloadTarget,
                                    onPickDate = { pickingDate = true },
                                    folderGone = noFolderGone,
                                    onChooseFolder = { folderPicker.launch() },
                                )
                                // D5's way in (row 7). On the idle screen only: the
                                // one moment nothing is being said or held.
                                if (state is FlowState.Idle) {
                                    SettingsEntry(Modifier.align(Alignment.TopEnd))
                                    val downloads by EngineHolder.downloads.collectAsState()
                                    DownloadStrip(
                                        downloads.values,
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .windowInsetsPadding(WindowInsets.safeDrawing)
                                            .padding(top = Maia.space.touchTarget + Maia.space.md),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Take the host, and settle the two things only a visible Activity knows.
     *
     * The keyguard first: this Activity cannot be shown over one, so being here
     * is proof it is down. The flow may still believe otherwise, because a user
     * who unlocks their phone in the ordinary way tells Maia nothing, and
     * [FlowEvent.Unlocked] is what turns the oldest waiting draft into a card.
     *
     * Then the notification, which is the same moment stated the other way
     * round: a queued draft is about to be reviewed on an unlocked screen, so
     * this is where the count is corrected and where the permission may be
     * asked for. `MaiaSession` cannot do either.
     */
    override fun onStart() {
        super.onStart()
        flow.attach(this)
        // Re-read rather than trusted: the user may have granted it in
        // Settings while this Activity was in the background.
        micGranted = granted(Manifest.permission.RECORD_AUDIO)
        if (flow.session.value.locked && keyguard?.isKeyguardLocked != true) {
            flow.send(FlowEvent.Unlocked)
        }
        postDraftWaiting(flow.session.value.queue.size)
    }

    override fun onStop() {
        flow.detach(this)
        super.onStop()
    }

    /**
     * Every event the screens send, with the two things a screen cannot know
     * added: which door this is, and whether the microphone has been granted.
     *
     * M2's screens send `Invoke(Launcher, locked = false)` and a bare M2
     * `Press` means the same thing. Both are re-stamped here with the keyguard
     * as this host reads it at the moment of the press, which is brief section
     * 2.2's single read, in the host, carried as a value from there on.
     */
    private fun onEvent(event: FlowEvent) {
        val invoke = when (event) {
            // copy, not a fresh Invoke: a rebuilt event would drop the family.
            // The surface is stamped the same way [locked] is: the run screen
            // is up exactly when `runOpen`, and only this window knows that.
            is FlowEvent.Invoke -> event.copy(
                locked = keyguard?.isKeyguardLocked == true,
                surface = if (runOpen) Surface.Agent else event.surface,
            )
            FlowEvent.Press -> FlowEvent.Invoke(
                Origin.Launcher,
                locked = keyguard?.isKeyguardLocked == true,
                surface = if (runOpen) Surface.Agent else Surface.Neutral,
            )
            else -> null
        }
        if (invoke == null) {
            flow.send(event)
            return
        }
        if (!micGranted) {
            // A capture with no microphone permission is a capture that fails,
            // and a fault reading "permission denied" one frame after a press
            // is worse than the question. Asked here and not at launch: the
            // press is the moment it explains itself.
            deferredInvoke = invoke
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        flow.send(invoke)
    }

    // -------------------------------------------------------------- the host

    /**
     * Ask for the keyguard, the way the brief originally asked for it.
     *
     * An Activity is what `requestDismissKeyguard` takes, so unlike
     * `MaiaSession` (which had to start `UnlockActivity` because a session
     * holds a `Dialog` and not an `Activity`) this host can call it directly. A
     * cancel, a wrong credential or a device policy refusal sends no event at
     * all, which is `Effect.RequestUnlock`'s whole contract.
     *
     * Reachable in practice only if the keyguard comes up under a live card,
     * since [onStart] has already cleared the flow's lock flag.
     */
    override fun requestUnlock() {
        val manager = keyguard ?: return
        if (!manager.isKeyguardLocked) {
            flow.send(FlowEvent.Unlocked)
            return
        }
        manager.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    flow.send(FlowEvent.Unlocked)
                }
            },
        )
    }

    /**
     * The flow has come to rest on a session that came through the assistant
     * door, so the window it was handed is finished with.
     *
     * `FlowHost` says an Activity leaves this a no-op because it finishes
     * itself, and that was written before there was a host that gets handed a
     * card by a session window. `Effect.HideSession` is only ever emitted for
     * `Origin.Assistant`, so a launcher run never reaches this, and when an
     * assistant run does the honest answer is the same as the session's: get
     * out of the way.
     */
    override fun hideSession() {
        if (!isFinishing) finish()
    }

    /**
     * The waiting-draft notification, and the one permission ask in the product
     * that has to happen at an exact moment.
     *
     * `POST_NOTIFICATIONS` is asked for here, on an unlocked screen, with a
     * queued draft in front of the user, and only when something is actually
     * still waiting: a count of zero has nothing to post, so asking would be
     * asking for nothing. Once per process, because a second dialog the user
     * has already refused is not a second chance, it is nagging.
     */
    override fun postDraftWaiting(count: Int) {
        if (count > 0 && needsNotificationPermission() && !notificationsAsked) {
            notificationsAsked = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        notifier.post()
    }

    // ------------------------------------------------------------ the pieces

    /**
     * The way into settings (D5), in the idle screen's top corner.
     *
     * `SETTINGS`, now that it holds more than the notes folder: where events
     * go is the second row. Opens [SettingsActivity], which is not exported.
     */
    @Composable
    private fun SettingsEntry(modifier: Modifier) {
        Text(
            stringResource(R.string.settings_entry),
            style = Maia.type.label,
            color = Maia.colours.inkMid,
            modifier = modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .clickable(indication = null, interactionSource = null) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .heightIn(min = Maia.space.touchTarget)
                .padding(horizontal = Maia.space.gutter, vertical = Maia.space.md),
        )
    }

    /**
     * 4g's date picker: date, then time, then one [FlowEvent.DatePicked].
     *
     * The same two platform dialogs the card's `WhenEditor` uses, which is why
     * the fault screen needs an Activity host at all. A cancel at either step
     * sends nothing and leaves the fault exactly as it was, holding the
     * sentence.
     */
    @Composable
    private fun DatePicker(draft: EventDraft) {
        val start = draft.start.value
        DisposableEffect(start) {
            val date = DatePickerDialog(
                this@MainActivity,
                { _, year, month, day ->
                    val moved = start.withYear(year).withMonth(month + 1).withDayOfMonth(day)
                    TimePickerDialog(
                        this@MainActivity,
                        { _, hour, minute ->
                            pickingDate = false
                            flow.send(FlowEvent.DatePicked(moved.withHour(hour).withMinute(minute)))
                        },
                        start.hour,
                        start.minute,
                        true,
                    ).apply { setOnCancelListener { pickingDate = false } }.show()
                },
                start.year,
                start.monthValue - 1,
                start.dayOfMonth,
            )
            date.setOnCancelListener { pickingDate = false }
            date.show()
            onDispose { date.dismiss() }
        }
    }

    private fun openChooser() = lifecycleScope.launch {
        chooser = runCatching { calendars.calendars() }.getOrDefault(emptyList())
    }

    /**
     * A calendar chosen. Remembered by the provider, and then re-read rather
     * than assumed: what the card must show is where a commit would now land,
     * which is the provider's answer and not the chooser's argument.
     */
    private fun choose(calendarId: Long) {
        chooser = null
        lifecycleScope.launch {
            runCatching { calendars.chooseTarget(calendarId) }
            reload()
        }
    }

    /**
     * 4i's "Check again", and the answer to a calendar permission dialog.
     *
     * The same read `Effect.LoadTarget` runs, reported back as the same event,
     * because the reducer's no-calendar screen listens for exactly one thing.
     */
    private fun reloadTarget() {
        lifecycleScope.launch { reload() }
    }

    private suspend fun reload() {
        runCatching { calendars.defaultTarget() }
            .onSuccess { flow.send(FlowEvent.TargetLoaded(it)) }
            .onFailure {
                flow.send(FlowEvent.TargetUnreadable(it.message ?: "the calendar could not be read"))
            }
    }

    /**
     * Per item I4 the intent is recorded and nothing is read out of it. The
     * flow is process scoped, so a door that reopens this Activity has already
     * told the controller what it wanted; this renders whatever it finds.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * This phone's public node key, onto the clipboard, and nowhere else.
     *
     * The public half is what the devbox operator puts in the `--allow` list,
     * so it is safe to share and the copy says so in as many words. The
     * private half is not involved: `AgentHost.publicKey` derives the public
     * text and the private text never leaves `AgentSecrets`.
     *
     * The key never passes through composition. The screen calls this and the
     * string is read, put on the clipboard and dropped, so it is not in a
     * remembered value, a recomposition, or a saved instance state bundle.
     *
     * `ClipData.newPlainText` and not a sensitive-content flag: the value is
     * public, and flagging it as sensitive would tell the user the opposite of
     * what `m8_pair_key_caption` is carefully telling them.
     */
    private fun copyNodeKey() {
        val key = AgentHost.publicKey(this) ?: return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.m8_pair_key_label), key))
    }

    /**
     * The voice offer's `Download` (M9 PRD section 9).
     *
     * Collected on this Activity's scope rather than inside the composition,
     * so leaving the answer surface mid-download does not throw the fetch
     * away: `.part` files resume, but an interrupted file restarts at its
     * last byte and the user paid for those bytes once already.
     *
     * On `Ready` the card simply dismisses and nothing else happens, which
     * is the deferred speaker doing its job: `AndroidEffects` asks the voice
     * directory on every sentence, so the next answer speaks without a
     * restart and without rewiring anything here. A failure keeps the card,
     * now carrying the error, and `Not now` still dismisses it. Nothing in
     * this path touches the ringer policy: speech or silence stays
     * `RingerAwareSpeaker`'s decision per sentence.
     */
    private fun downloadVoice() {
        if (voiceJob?.isActive == true) return
        voiceJob = lifecycleScope.launch {
            try {
                voiceStore.ensure().collect { progress ->
                    when (progress) {
                        is VoiceStore.Progress.Downloading -> voiceCard = VoiceCard(
                            downloading = true,
                            progress = AnswerCopy.voiceLine(
                                progress.index,
                                progress.count,
                                progress.file,
                                progress.bytes,
                                progress.totalBytes,
                                progress.bytesPerSecond,
                            ),
                        )
                        VoiceStore.Progress.Ready -> {
                            voiceReady = true
                            voiceCard = null
                        }
                    }
                }
            } catch (e: CancellationException) {
                voiceCard = null
                throw e
            } catch (e: Exception) {
                voiceCard = VoiceCard(error = e.message ?: e.toString())
            }
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun calendarGranted(): Boolean = CALENDAR_PERMISSIONS.all { granted(it) }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)

    companion object {
        /**
         * The tunnel app, which is a separate application with its own icon
         * and its own life. `m8_tunnel_off_action` opens it and Maia does
         * nothing else to it: it is not driven, not bound to and not started
         * in the background, because the tunnel is the user's to turn on.
         */
        private const val TUNNEL_PACKAGE = "dev.maia.tunnel"

        private val CALENDAR_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
    }
}
