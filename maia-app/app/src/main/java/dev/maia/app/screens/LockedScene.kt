package dev.maia.app.screens

import dev.maia.app.card.Mark
import dev.maia.app.card.bracketed
import dev.maia.app.flow.FaultReason
import dev.maia.app.flow.FlowSession
import dev.maia.app.flow.FlowState
import dev.maia.app.flow.LockedSummary
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Provenance

/**
 * Which locked screen, decided away from Compose. M3 brief section 4.1, and
 * `docs/M3-copy.md` sections 2 to 7.
 *
 * The same split the rest of `screens/` uses: the composables draw and this
 * decides. The reason it matters more here than anywhere else is that the
 * locked policy is the one thing in M3 that a JVM test can actually prove. A
 * screen chosen inside a `when` in a composable is a screen nobody can assert
 * about without a phone; a screen chosen by this function is criterion J3 and
 * J10 in a unit test.
 *
 * Everything this function reads is either the pure flow value or a fact the
 * host measured about the phone itself. It reads no provider, no calendar and
 * no account, and it cannot: [FlowSession] carries none.
 */
sealed interface LockedScene {

    /**
     * A sentence was heard and kept. `docs/M3-copy.md` section 2.
     *
     * [summary] is the only thing with words in it, which is the whole locked
     * policy stated as a type: a locked screen renders what came through
     * [LockedSummary] and nothing else.
     *
     * [whenGuessed] is not a second channel for content. It is a boolean, it
     * has nowhere to put a calendar name, and it carries one fact about the
     * user's own sentence: whether Maia supplied the day rather than hearing
     * it. It exists because `docs/M3-copy.md` section 9 item 2 keeps the angle
     * brackets on a guessed value even though brief section 4.1 says plain
     * text, and brackets are chroma-free, layout-free and disclose nothing.
     *
     * [note] is the same kind of fact (M4 row 8, D3): what the user said was a
     * note, which picks `KEPT AS A NOTE` and the note descriptions. A boolean
     * on purpose, so the locked note pose has nowhere to put a folder or a file
     * name, which is the copy's stranger test stated as a type.
     */
    data class Kept(val summary: LockedSummary, val whenGuessed: Boolean, val note: Boolean = false) : LockedScene

    /**
     * A question that cannot be answered while locked. Section 4.3, copy
     * section 3. Constant: no branch here on any calendar value, because a
     * refusal that shortens on an empty day is itself a disclosure.
     */
    data class ReadBack(val summary: LockedSummary) : LockedScene

    /** Section 4.4. Credential encrypted storage is not readable yet. */
    data object BeforeFirstUnlock : LockedScene

    /** Section 7. `RECORD_AUDIO` cannot be prompted for from a lock screen. */
    data object NoMicrophone : LockedScene

    /** Section 2.3. A 70 MB download is not started from a lock screen. */
    data object FirstRun : LockedScene

    /** Section 4.2. The sixth locked capture, refused before the microphone opens. */
    data class QueueFull(val waiting: Int) : LockedScene
}

/**
 * The two things the flow cannot know and the host must measure.
 *
 * Both are read once, by the host, at the moment the session is shown, exactly
 * as the keyguard is (brief section 2.2). Neither is in [FlowSession] because
 * neither is a fact about the flow, and a reducer that asked Android would be
 * a reducer this box could not test.
 */
enum class LockedBlock {
    /** `UserManager.isUserUnlocked` is false: after a reboot, before the first unlock. */
    BeforeFirstUnlock,

    /** `RECORD_AUDIO` is not granted, and a lock screen is where it cannot be asked for. */
    MicrophoneDenied,
}

/**
 * The locked screen for this session, or null when the ordinary [FlowScreen]
 * is the right one.
 *
 * Null is the common answer and is not a gap. Listening, the live partial and
 * the capture failure are the same screens locked or not, because everything
 * on them came out of the user's mouth a second ago. What the lock changes is
 * where a finished sentence goes, and that is the four screens above.
 *
 * The order is the order in which a user is blocked. A phone that has not been
 * unlocked since the reboot cannot reach the models, so it cannot ask for the
 * microphone and cannot download anything; a phone with no microphone
 * permission cannot get as far as a missing model mattering.
 */
fun lockedScene(session: FlowSession, block: LockedBlock? = null): LockedScene? {
    if (!session.locked) return null
    when (block) {
        LockedBlock.BeforeFirstUnlock -> return LockedScene.BeforeFirstUnlock
        LockedBlock.MicrophoneDenied -> return LockedScene.NoMicrophone
        null -> Unit
    }
    return when (val state = session.state) {
        is FlowState.FirstRun -> LockedScene.FirstRun
        is FlowState.Queued -> when {
            state.draft != null -> LockedScene.Kept(state.summary, whenGuessed = whenGuessed(state.draft))
            // M4 D3: a kept note is M3's kept screen with its own parsed block.
            state.note != null -> LockedScene.Kept(
                state.summary,
                whenGuessed = state.note.remindAt?.let { it.provenance != Provenance.Heard } == true,
                note = true,
            )
            else -> LockedScene.ReadBack(state.summary)
        }
        is FlowState.Fault ->
            if (state.reason == FaultReason.QueueFull) LockedScene.QueueFull(session.queue.size) else null
        else -> null
    }
}

/**
 * Whether Maia supplied the day rather than hearing it.
 *
 * A function of the draft, which is a function of the sentence. Nothing was
 * read to compute it, which is why it is safe on a lock screen for the same
 * reason the when text itself is.
 */
fun whenGuessed(draft: EventDraft): Boolean = draft.start.provenance != Provenance.Heard

/**
 * The when text as the locked screen prints it: bracketed when Maia guessed
 * it, plain when the user said it.
 *
 * [bracketed] rather than two characters written out here, so there is one
 * definition of the cue in the product and the locked screen cannot drift away
 * from the card. The other three cues (the hollow diamond, the dashed
 * underline, the hatch) stay on the card: the locked screen has no rows and no
 * gutter to hang them in, and copy section 9 item 2 keeps the brackets alone
 * deliberately.
 */
fun lockedWhen(summary: LockedSummary, guessed: Boolean): String =
    if (summary.whenText.isBlank()) "" else bracketed(summary.whenText, if (guessed) Mark.Guessed else Mark.Heard)

/**
 * Whether what the flow is showing now needs an Activity rather than the
 * session window.
 *
 * Brief section 1.4 leaves this open and `docs/M2-status.md` section 0.1
 * closes it: M2's `WhenEditor` opens `android.app.DatePickerDialog` and the
 * other field editors open Compose `Dialog`s, and a platform dialog cannot be
 * shown from inside a voice interaction session's window. So the card is not
 * drawn here. When an unlock turns a kept draft into a card, the session hands
 * over to `MainActivity` and takes its own window down.
 *
 * The no-calendar screen and the no-date fault go the same way: the first
 * offers a chooser and the second a date picker, and both are dialogs.
 *
 * So does the no-folder screen (M4 row 7), for a different reason: the folder
 * picker returns its Uri as an activity result, and a session window has no
 * Activity to return it to.
 */
fun needsActivityHost(state: FlowState): Boolean = when (state) {
    is FlowState.Preview, is FlowState.NoCalendar, is FlowState.NoFolder -> true
    is FlowState.Fault -> state.reason == FaultReason.NoDateHeard
    else -> false
}
