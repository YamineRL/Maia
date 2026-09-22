package dev.maia.app.screens

import dev.maia.app.answer.AnswerFault
import dev.maia.app.answer.AnswerSource
import dev.maia.app.answer.AnswerState
import dev.maia.app.answer.AnswerStatus
import dev.maia.app.answer.HandoffSpec
import dev.maia.app.answer.PermNeeded
import dev.maia.app.ui.DownloadCopy
import dev.maia.audio.speech.VoiceStore
import dev.maia.orb.ApertureState

/**
 * What the answer screen says, decided without Compose. M9 PRD section 10.
 *
 * The same split as [FlowCopy] and [RunCopy]: [AnswerScreen] only draws, and
 * every choice about which words appear, which pose the orb takes and which
 * control is offered is made here, where a JVM test can pin it.
 *
 * Two disciplines the rest of the product already keeps, kept here too. The
 * status labels are the orb's text twin, mandatory because the orb is below
 * widget size on this screen. And the source row is a factual disclosure of
 * where text was interpreted, section 10's own words, which is why it is a
 * plain label and not a badge and why it never says "AI".
 */
object AnswerCopy {

    /** The header over the user's own sentence, section 10 item 1. */
    const val YOU_ASKED = "you asked"

    /** The two halves of a collapsed history row. */
    const val EARLIER_ASKED = "you asked"
    const val EARLIER_SAID = "it said"

    const val STOP_SPEAKING = "Stop speaking"
    const val ASK_ANOTHER = "Ask another"
    const val CLEAR = "Clear conversation"
    const val TRY_AGAIN = "Try again"
    const val CONFIRM = "Confirm"
    const val GRANT = "Grant permission"

    const val FOLLOW_UP_LABEL = "type a follow-up"
    const val FOLLOW_UP_HINT = "Ask a follow-up"

    const val VOICE_TITLE = "Speak answers aloud"

    /**
     * Section 9's shown-before-fetching line: the model's name, its size and
     * its purpose, and where its licence lives. The name is [VoiceStore]'s
     * constant rather than a guess; `AnswerCopyTest` pins the two together
     * so a voice swap that forgets the sentence is a red build.
     */
    const val VOICE_BODY = "Piper voice " + VoiceStore.VOICE + ", about 64 MB, downloaded once. " +
        "It turns answers into speech on this phone; nothing is sent away for it. " +
        "Its model card and licence come down with the files."

    const val VOICE_DOWNLOAD = "Download"
    const val VOICE_NOT_NOW = "Not now"
    const val VOICE_DOWNLOADING = "Downloading. The answer screen keeps working without it."

    /**
     * The status label, the orb's text twin and the screen's one live region.
     *
     * `Idle` reads `STOPPED` because it is the only way the screen reaches
     * it in practice: the surface opens on a non-idle status, and the machine
     * lands on `Idle` when the user stops whatever was live.
     */
    fun status(status: AnswerStatus): String = when (status) {
        AnswerStatus.Idle -> "STOPPED"
        AnswerStatus.Working -> "THINKING"
        AnswerStatus.Previewing -> "PREVIEW"
        AnswerStatus.Answered -> "ANSWERED"
        AnswerStatus.HandedOff -> "OPENED"
        AnswerStatus.Failed -> "NO ANSWER"
    }

    /**
     * The source row, section 10 item 3: where the words on screen were
     * interpreted. Both local kinds read the same because the distinction
     * that matters to the user is phone versus devbox, and a failed ask has
     * no source, which is said rather than omitted.
     */
    fun source(state: AnswerState): String? = when {
        state.status == AnswerStatus.Failed -> "UNAVAILABLE"
        state.source == AnswerSource.Phone || state.source == AnswerSource.Calendar -> "ON THIS PHONE"
        state.source == AnswerSource.Devbox -> "YOUR DEVBOX"
        state.source == AnswerSource.PhoneModel -> "THIS PHONE'S MODEL"
        else -> null
    }

    /**
     * The handoff card's second line: what it opens, named by the spec's own
     * target word.
     */
    fun opensTarget(spec: HandoffSpec): String = "Opens ${spec.target}"

    /** The words over a fault, section 11's honest sentences. */
    data class FaultCopy(val title: String, val body: String)

    fun fault(fault: AnswerFault): FaultCopy = when (fault) {
        AnswerFault.NotSetUp -> FaultCopy(
            "The devbox is not set up",
            "This question needs your devbox, and its assistant credential is not stored on " +
                "this phone. Pairing holds the field. Everything Maia can answer alone still works.",
        )
        AnswerFault.Unreachable -> FaultCopy(
            "The devbox cannot be reached",
            "The tunnel or the assistant service is not answering. " +
                "Everything that runs on this phone still works.",
        )
        AnswerFault.Busy -> FaultCopy(
            "The devbox is busy",
            "It is still working on something else. Your question is kept here; " +
                "try again in a moment.",
        )
        AnswerFault.Unusable -> FaultCopy(
            "The reply could not be used",
            "The devbox answered, but not in a shape Maia can use. " +
                "Trying again asks it once more.",
        )
        AnswerFault.NoTarget -> FaultCopy(
            "Nothing on this phone would take that",
            "The action was ready and no installed app accepted it. What was typed is kept above.",
        )
        is AnswerFault.Permission -> permission(fault.which)
    }

    /**
     * The grant's name, and what it was wanted for. Section 11: the missing
     * permission is named and explained, and nothing else changes.
     */
    private fun permission(which: PermNeeded): FaultCopy = when (which) {
        PermNeeded.Calendar -> FaultCopy(
            "Calendar permission is needed",
            "Reading your agenda needs the calendar permission, which was not granted.",
        )
        PermNeeded.Contacts -> FaultCopy(
            "Contacts permission is needed",
            "That name needs the contacts permission, which was not granted. " +
                "A spoken number still works.",
        )
        PermNeeded.Camera -> FaultCopy(
            "Camera permission is needed",
            "The flashlight is the camera's torch, and Android asks for the camera " +
                "permission to switch it. The camera never opens and no image is taken.",
        )
    }

    /**
     * The download card's mono line while files land: which file, and the
     * byte count in the same units [DownloadCopy] uses everywhere else.
     */
    fun voiceLine(
        index: Int,
        count: Int,
        file: String,
        done: Long,
        total: Long,
        bytesPerSecond: Double,
    ): String = "file $index of $count · $file · " + DownloadCopy.line(done, total, bytesPerSecond)
}

/**
 * The orb, section 10's four poses.
 *
 * `Speaking` is earned by [AnswerState.spokenText], which the machine holds
 * only while the voice is actually live, and the envelope arrives through
 * the screen's `speech` parameter exactly as the run screen's does. `Fault`
 * is only the ended-without-an-answer status: a queue note is words, not a
 * pose, and a stopped surface rests `Dormant` rather than telling the user
 * off for using a control.
 */
val AnswerState.aperture: ApertureState
    get() = when {
        status == AnswerStatus.Failed -> ApertureState.Fault
        spokenText != null -> ApertureState.Speaking
        status == AnswerStatus.Working || status == AnswerStatus.Previewing -> ApertureState.Thinking
        else -> ApertureState.Dormant
    }
