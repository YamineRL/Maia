package dev.maia.nlu

import dev.maia.nlu.agent.ProjectRef
import java.time.ZonedDateTime

/**
 * What the sentence turned out to be.
 *
 * Three families and a fallback. The calendar and the note are the M1 five:
 * [CreateEvent], [CaptureNote], [Agenda], [Availability] and [Unparsed]. The
 * four addressed intents are the agent surface M8 adds. The rest are the
 * everyday assistant actions M9 adds: timers and alarms, arithmetic, device
 * facts, settings and torch, handoffs to the dialler, messages, maps, the
 * browser and the launcher, media control, and [Conversation] for the general
 * question that goes to the devbox assistant.
 *
 * [Unparsed] is not an error. PRD principle 4 says degrade, never fail: a
 * sentence Maia could not read still has to arrive somewhere the user can act
 * on, which is [Unparsed] carrying a draft whose title is what they actually
 * said. Nothing on this path throws.
 */
sealed interface Intent {

    /** A new event, ready for the card. */
    data class CreateEvent(val draft: EventDraft) : Intent

    /**
     * Something to keep, with no time attached, or a time that is a reminder
     * rather than an appointment.
     */
    data class CaptureNote(
        val body: Field<String>,
        val remindAt: Field<ZonedDateTime>? = null,
        val transcript: String = "",
    ) : Intent

    /** "what do I have tomorrow". A window to read back. */
    data class Agenda(
        val range: ClosedRange<ZonedDateTime>,
        val transcript: String = "",
    ) : Intent

    /** "am I free thursday afternoon". The same window, a different answer. */
    data class Availability(
        val range: ClosedRange<ZonedDateTime>,
        val transcript: String = "",
    ) : Intent

    /**
     * Something to say to an agent on the devbox, and which project to say it
     * to (M8 PRD section 3).
     *
     * [instruction] is free text and is forwarded verbatim. The parser found
     * where it starts and stopped: it does not know what "run the tests and
     * tell me what fails" means and must never learn, because the agent is the
     * thing that understands instructions and this module is the thing that
     * understands addresses.
     *
     * Delivery is not on this type. Section 13 answer 2 settles it as queue by
     * default, with stopping a separate spoken command, so there is no
     * modifier here for the flow machine to read.
     */
    data class AgentInstruction(
        val project: ProjectRef,
        val instruction: Field<String>,
        val transcript: String = "",
    ) : Intent

    /**
     * A project named with nothing to do in it.
     *
     * "project seven", or a bare "fourteen" answering the numbered list. One
     * project streams at a time and naming another moves the stream (section 13
     * answer 1), so an address on its own is a complete utterance: it moves the
     * stream and waits. It is never sent as an empty prompt.
     */
    data class AgentFocus(
        val project: ProjectRef,
        val transcript: String = "",
    ) : Intent

    /**
     * Stop what is running. Calls `interrupt` on the session, section 6.
     *
     * No project: the one that is streaming is the one that stops, because
     * there is only ever one.
     */
    data class AgentStop(val transcript: String = "") : Intent

    /** "what is running". A question about the agent, answered on screen. */
    data class AgentStatus(val transcript: String = "") : Intent

    /**
     * "set a timer for five minutes".
     *
     * Milliseconds, because `AlarmClock.ACTION_SET_TIMER` takes seconds and
     * the grammar hears minutes: the conversion happens here once so the app
     * never has to guess which unit a number arrived in.
     */
    data class SetTimer(
        val durationMs: Long,
        val label: String? = null,
        val transcript: String = "",
    ) : Intent

    /**
     * "wake me at six thirty", "set an alarm for seven a m".
     *
     * Hour and minute in 24 hour time, the meridiem already applied. Which
     * day the alarm lands on is the app's call: `ACTION_SET_ALARM` asks for
     * exactly these fields, and the parser reports only [tomorrow] as the
     * day handling the user actually spoke.
     */
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String? = null,
        val tomorrow: Boolean = false,
        val transcript: String = "",
    ) : Intent

    /**
     * "what is twelve times eight".
     *
     * [expression] is the arithmetic with the words already turned into
     * operators ("15 percent of 200" arrives as `15/100*200`), ready for
     * [dev.maia.nlu.calc.Calc] to evaluate. It is never the raw sentence.
     */
    data class Calculate(val expression: String, val transcript: String = "") : Intent

    /** "what time is it", "what's the date", "how much battery". */
    data class DeviceFact(val kind: Kind, val transcript: String = "") : Intent {
        enum class Kind { TIME, DATE, BATTERY }
    }

    /**
     * "open wifi settings", "turn on bluetooth".
     *
     * Android does not let a third party app toggle these radios, so the
     * intent is always to open the matching settings panel, never to claim
     * the toggle happened.
     */
    data class OpenSettings(val panel: Panel, val transcript: String = "") : Intent {
        enum class Panel { WIFI, BLUETOOTH, MAIN }
    }

    /**
     * "turn on the flashlight". A null [on] is a toggle: "toggle the torch"
     * said nothing about which way.
     */
    data class SetTorch(val on: Boolean?, val transcript: String = "") : Intent

    /**
     * "call mum", "dial 555 1234". A populated dial screen, never a placed
     * call. [target] is the name or the digits verbatim; contact resolution
     * belongs to the app and its consent flow.
     */
    data class Dial(val target: String, val transcript: String = "") : Intent

    /**
     * "text sam that i am late". A populated compose screen, never a sent
     * message. Either field may be missing: "text sam" has no body yet.
     */
    data class ComposeMessage(
        val to: String?,
        val body: String?,
        val transcript: String = "",
    ) : Intent

    /**
     * "directions to the airport", "take me home". [mode] is one of walk,
     * drive, bike or transit when the phrasing named one.
     */
    data class Navigate(
        val destination: String,
        val mode: String? = null,
        val transcript: String = "",
    ) : Intent

    /**
     * "search for espresso machines", "open github.com". [target] is a
     * search query or a domain verbatim; which of the two is visible in the
     * string and is the browser's problem either way.
     */
    data class OpenWeb(val target: String, val transcript: String = "") : Intent

    /** "open spotify". A launcher handoff; the name is what the user said. */
    data class OpenApp(val name: String, val transcript: String = "") : Intent

    /** "pause the music", "next track", "volume up". */
    data class Media(val command: Command, val transcript: String = "") : Intent {
        enum class Command { PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, MUTE }
    }

    /**
     * A general question or request for information that nothing
     * deterministic claimed, bound for the conversational answer screen.
     * [text] is the utterance verbatim.
     */
    data class Conversation(val text: String, val transcript: String = "") : Intent

    /**
     * Heard, not understood.
     *
     * The draft is real and editable. Its title is the raw transcript and its
     * time is whatever could be salvaged, so the card that opens is one the
     * user can correct in two taps rather than a dead end that asks them to
     * say it again.
     */
    data class Unparsed(val draft: EventDraft) : Intent
}
