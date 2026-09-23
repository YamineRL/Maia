package dev.maia.nlu.assistant

import dev.maia.nlu.Intent
import dev.maia.nlu.Parser
import dev.maia.nlu.agent.Projects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The M9 deterministic assistant grammar, through the real [Parser].
 *
 * The clock is the same Saturday the rest of the module uses, so the alarm
 * guesses can be checked against the resolver's own rules: at 11:30 a bare
 * "six thirty" is 18:30 for the same reason a bare calendar hour is.
 */
class AssistantGrammarTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-09-12T09:30:00Z"), zone)

    private val parser = Parser(clock)

    /** The phone after it has synced the registry, for the precedence cases. */
    private val synced = Parser(clock, Projects.asOf20260917)

    private fun alarm(said: String): Intent.SetAlarm {
        val intent = parser.parse(said)
        assertTrue("[$said] became $intent", intent is Intent.SetAlarm)
        return intent as Intent.SetAlarm
    }

    // -------------------------------------------------------------- timers

    @Test
    fun `a timer parses its duration`() {
        val cases = listOf(
            "set a timer for five minutes" to 300_000L,
            "timer for ninety seconds" to 90_000L,
            "set timer 10 min" to 600_000L,
            "five minute timer" to 300_000L,
            "set a timer for one hour and thirty minutes" to 5_400_000L,
            "set a timer for an hour and a half" to 5_400_000L,
            "start a timer for twenty minutes" to 1_200_000L,
        )
        for ((said, ms) in cases) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.SetTimer)
            assertEquals("[$said]", ms, (intent as Intent.SetTimer).durationMs)
            assertEquals(said, intent.transcript)
        }
    }

    @Test
    fun `a timer keeps its label`() {
        val intent = parser.parse("set a timer for 5 minutes for pasta") as Intent.SetTimer
        assertEquals(300_000L, intent.durationMs)
        assertEquals("pasta", intent.label)
    }

    @Test
    fun `a timer with no readable duration is not a timer`() {
        assertTrue(parser.parse("set a timer for the meeting") !is Intent.SetTimer)
    }

    // -------------------------------------------------------------- alarms

    @Test
    fun `an alarm parses its hour and minute`() {
        for (said in listOf("set an alarm for 7 am", "set an alarm for seven a m")) {
            val intent = alarm(said)
            assertEquals("[$said]", 7, intent.hour)
            assertEquals(0, intent.minute)
        }
        val evening = alarm("set an alarm for 7 pm")
        assertEquals(19, evening.hour)
        assertEquals(0, evening.minute)
        val half = alarm("set an alarm for seven thirty p m")
        assertEquals(19, half.hour)
        assertEquals(30, half.minute)
    }

    @Test
    fun `wake me parses as an alarm`() {
        // 11:30 on the fixture clock: "six thirty" is the evening reading for
        // the same reason a bare calendar hour guesses the next plausible one.
        val intent = alarm("wake me at six thirty")
        assertEquals(18, intent.hour)
        assertEquals(30, intent.minute)
        val up = alarm("wake me up at seven")
        assertEquals(19, up.hour)
    }

    @Test
    fun `an alarm handles colon forms and the tomorrow flag`() {
        val intent = alarm("alarm for 7:30 tomorrow morning")
        assertEquals(7, intent.hour)
        assertEquals(30, intent.minute)
        assertTrue(intent.tomorrow)
    }

    // ----------------------------------------------------------- calculate

    @Test
    fun `arithmetic arrives as a normalised expression`() {
        val cases = listOf(
            "what is 12 times 8" to "12*8",
            "whats 3 plus 4" to "3+4",
            "what's 3 plus 4" to "3+4",
            "calculate 15 percent of 200" to "15/100*200",
            "what is 10 divided by 4" to "10/4",
            "how much is 20 percent of 80" to "20/100*80",
            "what is two squared" to "2^2",
            "what is 4 times 6 minus 3" to "4*6-3",
        )
        for ((said, expr) in cases) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.Calculate)
            assertEquals("[$said]", expr, (intent as Intent.Calculate).expression)
        }
    }

    @Test
    fun `a sum said without what is is still a sum`() {
        val cases = listOf(
            "ten multiplied by eight" to "10*8",
            "ten plus five" to "10+5",
            "twelve divided by four" to "12/4",
            "calculate eight multiply by ten" to "8*10",
            "twelve divide by four" to "12/4",
        )
        for ((said, expr) in cases) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.Calculate)
            assertEquals("[$said]", expr, (intent as Intent.Calculate).expression)
        }
    }

    @Test
    fun `a number alone is not a sum`() {
        for (said in listOf("eight", "2026", "dinner at eight")) {
            assertTrue("[$said]", parser.parse(said) is Intent.CreateEvent)
        }
    }

    @Test
    fun `a what is that is not arithmetic falls to conversation`() {
        val intent = parser.parse("what is the capital of france")
        assertTrue(intent is Intent.Conversation)
        assertEquals("what is the capital of france", (intent as Intent.Conversation).text)
    }

    // --------------------------------------------------------- device facts

    @Test
    fun `time questions are device facts not conversations`() {
        for (said in listOf(
            "what time is it", "whats the time", "what's the time",
            "tell me the time", "do you have the time",
        )) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.DeviceFact)
            assertEquals(Intent.DeviceFact.Kind.TIME, (intent as Intent.DeviceFact).kind)
        }
    }

    @Test
    fun `date questions are device facts`() {
        for (said in listOf(
            "what day is today", "whats the date", "what's the date",
            "what day is it", "what is today",
        )) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.DeviceFact)
            assertEquals(Intent.DeviceFact.Kind.DATE, (intent as Intent.DeviceFact).kind)
        }
    }

    @Test
    fun `battery questions are device facts`() {
        for (said in listOf(
            "how much battery", "battery level", "whats my battery at",
            "how much battery do i have left", "battery percentage",
        )) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.DeviceFact)
            assertEquals(Intent.DeviceFact.Kind.BATTERY, (intent as Intent.DeviceFact).kind)
        }
    }

    // ------------------------------------------------------------ settings

    @Test
    fun `settings phrases map to panels`() {
        val cases = listOf(
            "open wifi settings" to Intent.OpenSettings.Panel.WIFI,
            "wifi settings" to Intent.OpenSettings.Panel.WIFI,
            "turn on wifi" to Intent.OpenSettings.Panel.WIFI,
            "turn off wifi" to Intent.OpenSettings.Panel.WIFI,
            "bluetooth settings" to Intent.OpenSettings.Panel.BLUETOOTH,
            "turn on bluetooth" to Intent.OpenSettings.Panel.BLUETOOTH,
            "open settings" to Intent.OpenSettings.Panel.MAIN,
            "settings" to Intent.OpenSettings.Panel.MAIN,
        )
        for ((said, panel) in cases) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.OpenSettings)
            assertEquals("[$said]", panel, (intent as Intent.OpenSettings).panel)
        }
    }

    // --------------------------------------------------------------- torch

    @Test
    fun `torch phrases map to on off or toggle`() {
        val cases = listOf(
            "turn on the flashlight" to true,
            "flashlight on" to true,
            "torch on" to true,
            "turn off the torch" to false,
            "turn the flashlight off" to false,
            "flashlight off" to false,
            "toggle flashlight" to null,
            "toggle the torch" to null,
        )
        for ((said, on) in cases) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.SetTorch)
            assertEquals("[$said]", on, (intent as Intent.SetTorch).on)
        }
    }

    // ---------------------------------------------------------------- dial

    @Test
    fun `call phrases keep the target verbatim`() {
        assertEquals("mom", (parser.parse("call mom") as Intent.Dial).target)
        assertEquals("555 1234", (parser.parse("dial 555 1234") as Intent.Dial).target)
        assertEquals("john smith", (parser.parse("phone john smith") as Intent.Dial).target)
    }

    @Test
    fun `a call with a time in it stays a calendar entry`() {
        val intent = parser.parse("call mum tonight")
        assertTrue("call mum tonight became $intent", intent is Intent.CreateEvent)
    }

    // ------------------------------------------------------------- message

    @Test
    fun `message phrases split recipient from body`() {
        val sarah = parser.parse("text sarah i'm running late") as Intent.ComposeMessage
        assertEquals("sarah", sarah.to)
        assertEquals("i'm running late", sarah.body)

        val john = parser.parse("send a message to john saying hello") as Intent.ComposeMessage
        assertEquals("john", john.to)
        assertEquals("hello", john.body)

        val mom = parser.parse("message mom i'll be home soon") as Intent.ComposeMessage
        assertEquals("mom", mom.to)
        assertEquals("i'll be home soon", mom.body)

        val sam = parser.parse("text sam that i am ten minutes late") as Intent.ComposeMessage
        assertEquals("sam", sam.to)
        assertEquals("i am ten minutes late", sam.body)
    }

    @Test
    fun `a two word recipient stays together when the body follows`() {
        val intent = parser.parse("text sarah jones hello") as Intent.ComposeMessage
        assertEquals("sarah jones", intent.to)
        assertEquals("hello", intent.body)
    }

    // ------------------------------------------------------------ navigate

    @Test
    fun `navigation phrases keep the destination and the mode`() {
        val airport = parser.parse("navigate to the airport") as Intent.Navigate
        assertEquals("the airport", airport.destination)
        assertEquals(null, airport.mode)
        assertEquals(
            "123 main street",
            (parser.parse("directions to 123 main street") as Intent.Navigate).destination,
        )
        assertEquals("home", (parser.parse("take me home") as Intent.Navigate).destination)
        val park = parser.parse("walking directions to the park") as Intent.Navigate
        assertEquals("the park", park.destination)
        assertEquals("walk", park.mode)
        val office = parser.parse("drive to the office") as Intent.Navigate
        assertEquals("drive", office.mode)
        assertEquals(
            "the dentist",
            (parser.parse("take me to the dentist") as Intent.Navigate).destination,
        )
    }

    // ------------------------------------------------------------------ web

    @Test
    fun `search and lookup phrases produce a query`() {
        assertEquals(
            "espresso machines",
            (parser.parse("search for espresso machines") as Intent.OpenWeb).target,
        )
        assertEquals(
            "weather in paris",
            (parser.parse("google the weather in paris") as Intent.OpenWeb).target,
        )
        assertEquals(
            "quantum computing",
            (parser.parse("look up quantum computing") as Intent.OpenWeb).target,
        )
        assertEquals(
            "train times",
            (parser.parse("search the web for train times") as Intent.OpenWeb).target,
        )
    }

    @Test
    fun `a domain after open or go to is a web handoff`() {
        assertEquals("github.com", (parser.parse("open github.com") as Intent.OpenWeb).target)
        assertEquals("wikipedia.org", (parser.parse("go to wikipedia.org") as Intent.OpenWeb).target)
    }

    // ------------------------------------------------------------------ app

    @Test
    fun `open launch and start produce an app handoff`() {
        assertEquals("spotify", (parser.parse("open spotify") as Intent.OpenApp).name)
        assertEquals("signal", (parser.parse("launch signal") as Intent.OpenApp).name)
        assertEquals("calendar", (parser.parse("start the calendar app") as Intent.OpenApp).name)
    }

    @Test
    fun `settings beat the app handoff`() {
        // "open settings" is a panel, never an app called "settings".
        assertTrue(parser.parse("open settings") is Intent.OpenSettings)
        assertTrue(parser.parse("open wifi settings") is Intent.OpenSettings)
    }

    // ---------------------------------------------------------------- media

    @Test
    fun `media phrases map to commands`() {
        val cases = listOf(
            "pause" to Intent.Media.Command.PAUSE,
            "pause the music" to Intent.Media.Command.PAUSE,
            "stop the music" to Intent.Media.Command.PAUSE,
            "resume" to Intent.Media.Command.PLAY,
            "play some jazz" to Intent.Media.Command.PLAY,
            "next track" to Intent.Media.Command.NEXT,
            "skip" to Intent.Media.Command.NEXT,
            "skip this track" to Intent.Media.Command.NEXT,
            "previous song" to Intent.Media.Command.PREVIOUS,
            "volume up" to Intent.Media.Command.VOLUME_UP,
            "turn the volume up" to Intent.Media.Command.VOLUME_UP,
            "turn the volume down" to Intent.Media.Command.VOLUME_DOWN,
            "turn it down" to Intent.Media.Command.VOLUME_DOWN,
            "louder" to Intent.Media.Command.VOLUME_UP,
            "mute" to Intent.Media.Command.MUTE,
        )
        for ((said, command) in cases) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.Media)
            assertEquals("[$said]", command, (intent as Intent.Media).command)
        }
    }

    @Test
    fun `a date that follows a transport word is still a date`() {
        // "next thursday" is the sentence the guarded tail exists for.
        assertTrue(parser.parse("next thursday") !is Intent.Media)
    }

    // ---------------------------------------------------- precedence, agent

    @Test
    fun `agent addressing still owns its sentences`() {
        assertTrue(synced.parse("project seven run the tests") is Intent.AgentInstruction)
        assertTrue(synced.parse("stop") is Intent.AgentStop)
        assertTrue(synced.parse("what is running") is Intent.AgentStatus)
        // A question about a project stays out of the conversational fallback.
        assertTrue(synced.parse("what is project seven doing") !is Intent.Conversation)
    }

    // ---------------------------------------------- precedence, calendar

    @Test
    fun `calendar notes and agenda are untouched`() {
        assertTrue(parser.parse("whats on my calendar friday") is Intent.Agenda)
        assertTrue(parser.parse("what's on my calendar friday") is Intent.Agenda)
        assertTrue(parser.parse("what do i have tomorrow") is Intent.Agenda)
        assertTrue(parser.parse("am i free thursday afternoon") is Intent.Availability)
        assertTrue(parser.parse("remind me to call the plumber") is Intent.CaptureNote)
        assertTrue(parser.parse("note to self buy milk") is Intent.CaptureNote)
        val event = parser.parse("schedule lunch with sam tomorrow at noon") as Intent.CreateEvent
        assertEquals("lunch with sam", event.draft.title.value)
        assertTrue(parser.parse("call with the accountant tuesday at eleven") is Intent.CreateEvent)
    }

    // -------------------------------------------------- the fallback rules

    @Test
    fun `a question nothing claimed is a conversation`() {
        for (said in listOf(
            "why is the sky blue",
            "how do i get to the airport",
            "tell me a joke",
            "who wrote dune",
            "what about evergreens",
        )) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.Conversation)
            assertEquals(said, (intent as Intent.Conversation).text)
        }
    }

    @Test
    fun `a request for information said as a statement is a conversation`() {
        // Heard on the phone and drafted as events before the verbless event
        // had to read like a name.
        for (said in listOf(
            "i want to know which football game are happening today in france",
            "use the devbox model to check the weather in france today",
            "weather in paris tomorrow",
            "check the score of the match tonight",
            "i wonder whether it will rain on friday",
        )) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.Conversation)
        }
    }

    @Test
    fun `a verbless event that reads like a name is still an event`() {
        for (said in listOf(
            "lunch with sam tomorrow at noon",
            "dentist on friday at three",
            "dinner with mum and dad saturday at seven",
            "football practice tomorrow at six",
            "call with the accountant tuesday at eleven",
        )) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.CreateEvent)
        }
    }

    @Test
    fun `a fragment that opens like a continuation is a conversation`() {
        // "And of germany" continues a thought already in the air: an event
        // never opens with a connective, so these are follow-ups, not
        // drafts titled after their own first word.
        for (said in listOf(
            "and of germany",
            "and tomorrow",
            "but what about the small one",
            "or the cheaper one",
        )) {
            val intent = parser.parse(said)
            assertTrue("[$said] became $intent", intent is Intent.Conversation)
            assertEquals(said, (intent as Intent.Conversation).text)
        }
    }

    @Test
    fun `a live conversation turns the unmatched fallback into a follow-up`() {
        // What a spoken follow-up leaves once the clock has taken its
        // share: nothing but a time, which no pattern can claim. With a
        // conversation live these are continuations, not cards.
        for (said in listOf("tomorrow", "next friday", "at noon")) {
            val intent = parser.parseFollowUp(said)
            assertTrue("[$said] became $intent", intent is Intent.Conversation)
        }
    }

    @Test
    fun `the neutral parse of the same fragments is unchanged`() {
        // With nothing to follow up on, "tomorrow" is still the honest
        // card: the follow-up parse is the caller saying a conversation is
        // live, and without one nothing here moves.
        for (said in listOf("tomorrow", "next friday")) {
            assertTrue("[$said] became ${parser.parse(said)}", parser.parse(said) is Intent.Unparsed)
        }
    }

    @Test
    fun `a live conversation does not steal a deterministic sentence`() {
        // Section 4's order stands inside the follow-up parse too: a timer,
        // a device fact, and a verbless title all keep their outcomes,
        // because a follow-up that meant an action still shows the card.
        assertTrue(parser.parseFollowUp("set a timer for five minutes") is Intent.SetTimer)
        assertTrue(parser.parseFollowUp("what time is it") is Intent.DeviceFact)
        assertTrue(parser.parseFollowUp("dentist friday") is Intent.CreateEvent)
    }

    @Test
    fun `a live conversation sends an untimed verbless title to the assistant`() {
        // Seen on the Pixel 2026-09-23: after a question about a city,
        // "the five best museums" drafted an event with no
        // day. Only the follow-up parse moves; a timed title keeps its card.
        for (said in listOf("The five best museums.", "the three best restaurants")) {
            val intent = parser.parseFollowUp(said)
            assertTrue("[$said] became $intent", intent is Intent.Conversation)
        }
        assertTrue(parser.parseFollowUp("dentist at five") is Intent.CreateEvent)
        assertTrue(parser.parseFollowUp("lunch with sam tomorrow at noon") is Intent.CreateEvent)
    }

    @Test
    fun `a single word is never a conversation`() {
        // "spotify" alone keeps whatever the old pipeline made of it; the rule
        // is only that it must not become a question for the devbox.
        assertTrue(parser.parse("spotify") !is Intent.Conversation)
        assertTrue(parser.parse("why") !is Intent.Conversation)
    }

    @Test
    fun `a device fact beats the conversational fallback`() {
        assertTrue(parser.parse("what time is it") is Intent.DeviceFact)
        assertTrue(parser.parse("whats the date") is Intent.DeviceFact)
    }
}
