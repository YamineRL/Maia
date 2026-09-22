package dev.maia.nlu.assistant

import dev.maia.nlu.Intent
import dev.maia.nlu.Specificity
import dev.maia.nlu.calc.Calc
import dev.maia.nlu.grammar.IntentPatterns
import dev.maia.nlu.grammar.Match
import dev.maia.nlu.grammar.Matcher
import dev.maia.nlu.grammar.Pattern
import dev.maia.nlu.grammar.Term
import dev.maia.nlu.text.Token
import dev.maia.nlu.text.Tokens
import dev.maia.nlu.time.Meridiem
import dev.maia.nlu.time.TemporalExtractor
import dev.maia.nlu.time.TemporalResolver
import dev.maia.nlu.time.TimeSpec

/**
 * The deterministic everyday grammar M9 adds: timers, alarms, arithmetic,
 * device facts, settings, the torch, and the handoffs to dialler, messages,
 * maps, browser, launcher and media session.
 *
 * Like [dev.maia.nlu.agent.AgentGrammar] this reads the sentence before the
 * temporal pass, on the full token list. It has to: "set a timer for five
 * minutes" loses its duration the moment the extractor runs, and "call mum
 * tonight" needs the un-eaten words to know the call was scheduled rather
 * than placed. The patterns are anchored, so a sentence that is not one of
 * these shapes comes back out untouched for the calendar grammar.
 *
 * Order is the precedence. The list is walked in declaration order and the
 * first pattern whose match the builder also accepts wins, for the same
 * reason [dev.maia.nlu.agent.AgentGrammar.addressed] is a loop: a guard can
 * refuse a match, and the sentence then belongs to the next pattern, not to
 * nothing. Two rules lean on that ordering directly:
 *
 *  - settings patterns precede the app handoff, so "open wifi settings" is a
 *    panel and not an app called "wifi settings";
 *  - the web handoff precedes navigation and app, so "go to wikipedia.org"
 *    is a page and "go to the airport" still drives there.
 */
object AssistantGrammar {

    private val please = IntentPatterns.please

    fun read(
        tokens: Tokens,
        words: List<String>,
        transcript: String,
        resolver: TemporalResolver,
    ): Intent? {
        if (tokens.isEmpty()) return null
        val t = tokens.list
        for (pattern in patterns) {
            val match = Matcher.match(pattern, tokens) ?: continue
            build(match, t, words, transcript, resolver)?.let { return it }
        }
        return null
    }

    private fun build(
        match: Match,
        t: List<Token>,
        words: List<String>,
        transcript: String,
        resolver: TemporalResolver,
    ): Intent? = when (match.pattern.name.substringBefore('.')) {
        "fact" -> Intent.DeviceFact(
            when {
                "battery" in match.pattern.name -> Intent.DeviceFact.Kind.BATTERY
                "date" in match.pattern.name -> Intent.DeviceFact.Kind.DATE
                else -> Intent.DeviceFact.Kind.TIME
            },
            transcript,
        )
        "timer" -> timer(match, t, words, transcript)
        "alarm" -> alarm(match, t, words, transcript, resolver)
        "calc" -> calculate(match, t, transcript)
        "settings" -> Intent.OpenSettings(
            when {
                "wifi" in match.pattern.name -> Intent.OpenSettings.Panel.WIFI
                "bluetooth" in match.pattern.name -> Intent.OpenSettings.Panel.BLUETOOTH
                else -> Intent.OpenSettings.Panel.MAIN
            },
            transcript,
        )
        "torch" -> Intent.SetTorch(torchState(t), transcript)
        "media" -> media(match, t, transcript)
        "dial" -> dial(match, t, words, transcript)
        "message" -> message(match, t, words, transcript)
        "web" -> web(match, t, words, transcript)
        "nav" -> navigate(match, t, words, transcript)
        "app" -> app(match, t, words, transcript)
        else -> null
    }

    // ---------------------------------------------------------------- timer

    private val unitMs = mapOf(
        "second" to 1_000L, "seconds" to 1_000L, "sec" to 1_000L, "secs" to 1_000L,
        "minute" to 60_000L, "minutes" to 60_000L, "min" to 60_000L, "mins" to 60_000L,
        "hour" to 3_600_000L, "hours" to 3_600_000L, "hr" to 3_600_000L, "hrs" to 3_600_000L,
    )

    /** A day, which is also the ceiling the remote schema puts on a timer. */
    private const val MAX_TIMER_MS = 86_400_000L

    private fun timer(
        match: Match,
        t: List<Token>,
        words: List<String>,
        transcript: String,
    ): Intent? {
        val range = match.capture("dur") ?: return null
        val until = range.last + 1
        val (ms, next) = duration(t, range.first, until) ?: return null
        var label: String? = null
        if (next < until) {
            if (t[next].text !in setOf("for", "called", "named", "labelled", "labeled")) return null
            label = verbatim(t, words, next + 1, until) ?: return null
        }
        if (ms <= 0 || ms > MAX_TIMER_MS) return null
        return Intent.SetTimer(ms, label, transcript)
    }

    /**
     * A run of (number, unit) pairs starting at [from]: "90 seconds", "one
     * hour and thirty minutes", "an hour and a half", "half a minute".
     * Returns the total and the index the run ended at, so the caller can
     * see whether a label followed.
     */
    private fun duration(t: List<Token>, from: Int, until: Int): Pair<Long, Int>? {
        var i = from
        var ms = 0L
        var pairs = 0
        while (i < until) {
            if (t[i].text == "and") {
                i++
                continue
            }
            if (t[i].text == "half" && i + 2 < until &&
                t[i + 1].text in setOf("a", "an") && t[i + 2].text in unitMs
            ) {
                ms += unitMs.getValue(t[i + 2].text) / 2
                i += 3
                pairs++
                continue
            }
            val v = when {
                t[i].kind == Token.Kind.Cardinal -> t[i].value?.toLong()
                t[i].text in setOf("a", "an") -> 1L
                else -> null
            } ?: break
            val unit = t.getOrNull(i + 1)?.text?.let { unitMs[it] } ?: break
            ms += v * unit
            i += 2
            pairs++
            if (i + 2 < until && t[i].text == "and" &&
                t[i + 1].text in setOf("a", "an") && t[i + 2].text == "half"
            ) {
                ms += unit / 2
                i += 3
            }
        }
        return if (pairs == 0) null else ms to i
    }

    // ---------------------------------------------------------------- alarm

    private fun alarm(
        match: Match,
        t: List<Token>,
        words: List<String>,
        transcript: String,
        resolver: TemporalResolver,
    ): Intent? {
        val range = match.capture("time") ?: return null
        var until = range.last + 1

        var label: String? = null
        for (j in range.first until until) {
            if (t[j].text in setOf("called", "named", "labelled", "labeled")) {
                label = verbatim(t, words, j + 1, until) ?: return null
                until = j
                break
            }
        }

        var tomorrow = false
        var part: String? = null
        val clock = ArrayList<Token>()
        val filler = setOf("the", "in", "at", "for", "this", "a", "an", "of", "me")
        for (j in range.first until until) {
            when (val w = t[j].text) {
                "tomorrow" -> tomorrow = true
                "today" -> {}
                "morning", "afternoon", "evening", "tonight" -> part = w
                in filler -> {}
                else -> clock += t[j]
            }
        }
        val parts = clockParts(clock) ?: return null
        var meridiem = parts.meridiem
        if (meridiem == null && parts.hour in 1..11) {
            meridiem = when (part) {
                "morning" -> Meridiem.Am
                "afternoon", "evening", "tonight" -> Meridiem.Pm
                else -> null
            }
        }
        val now = resolver.now()
        val onDate = if (tomorrow) now.toLocalDate().plusDays(1) else now.toLocalDate()
        val time = resolver.resolveTime(
            TimeSpec.Clock(parts.hour, parts.minute, meridiem),
            now,
            onDate,
        ).value
        return Intent.SetAlarm(time.hour, time.minute, label, tomorrow, transcript)
    }

    private data class ClockParts(val hour: Int, val minute: Int, val meridiem: Meridiem?)

    /**
     * The tokens that make a clock reading: "7", "6 30", "7:30", "seven
     * thirty pm", "eight oh five", "noon", "midnight". Meridiem and o-clock
     * tokens are lifted out first; what remains must be one of those shapes
     * exactly, or the sentence was not an alarm.
     */
    private fun clockParts(clock: List<Token>): ClockParts? {
        var meridiem: Meridiem? = null
        val nums = ArrayList<Token>()
        for (t in clock) {
            when (t.kind) {
                Token.Kind.Meridiem -> {
                    if (meridiem != null) return null
                    meridiem = if (t.text == "am") Meridiem.Am else Meridiem.Pm
                }
                Token.Kind.OClock -> {}
                else -> nums += t
            }
        }
        var h: Int
        var m = 0
        when (nums.size) {
            1 -> {
                val w = nums[0]
                val hm = CLOCK_WORD.matchEntire(w.text)
                when {
                    w.text == "noon" || w.text == "midday" -> h = 12
                    w.text == "midnight" -> h = 0
                    hm != null -> {
                        h = hm.groupValues[1].toInt()
                        m = hm.groupValues[2].toInt()
                    }
                    w.kind == Token.Kind.Cardinal -> h = w.value ?: return null
                    else -> return null
                }
            }
            2 -> {
                if (nums.any { it.kind != Token.Kind.Cardinal }) return null
                h = nums[0].value ?: return null
                m = nums[1].value ?: return null
            }
            3 -> {
                // "eight oh five": the normaliser leaves it as three cardinals.
                if (nums.any { it.kind != Token.Kind.Cardinal } || nums[1].value != 0) return null
                h = nums[0].value ?: return null
                m = nums[2].value ?: return null
            }
            else -> return null
        }
        if (m !in 0..59) return null
        if (meridiem != null) {
            if (h !in 1..12) return null
        } else if (h !in 0..23) {
            return null
        }
        return ClockParts(h, m, meridiem)
    }

    private val CLOCK_WORD = Regex("(\\d{1,2}):(\\d{2})")

    // ------------------------------------------------------------ calculate

    private val EXPRESSION_WORD = Regex("[0-9+\\-*/%^().]+")

    private const val OPERATORS = "+-*/%^"

    /**
     * The captured run turned into an infix string, or null when any token
     * will not translate. "the capital of france" is not an expression, so
     * the sentence falls through to the conversational fallback rather than
     * being half-read.
     */
    private fun calculate(match: Match, t: List<Token>, transcript: String): Intent? {
        val range = match.capture("expr") ?: return null
        val expr = StringBuilder()
        var i = range.first
        while (i <= range.last) {
            val w = t[i].text
            when {
                t[i].kind == Token.Kind.Cardinal -> expr.append(w)
                w == "plus" || w == "add" -> expr.append('+')
                w == "minus" || w == "subtract" -> expr.append('-')
                w == "times" || w == "x" -> expr.append('*')
                w == "multiply" || w == "multiplied" -> {
                    expr.append('*')
                    if (t.getOrNull(i + 1)?.text == "by") i++
                }
                w == "divide" || w == "divided" -> {
                    expr.append('/')
                    if (t.getOrNull(i + 1)?.text == "by") i++
                }
                w == "over" -> expr.append('/')
                w == "modulo" || w == "mod" -> expr.append('%')
                w == "power" -> expr.append('^')
                w == "squared" -> expr.append("^2")
                w == "cubed" -> expr.append("^3")
                w == "to" && t.getOrNull(i + 1)?.text == "the" &&
                    t.getOrNull(i + 2)?.text == "power" &&
                    t.getOrNull(i + 3)?.text == "of" -> {
                    expr.append('^')
                    i += 3
                }
                w == "percent" || w == "percentage" -> {
                    if (t.getOrNull(i + 1)?.text == "of") {
                        expr.append("/100*")
                        i++
                    } else {
                        expr.append("/100")
                    }
                }
                w == "per" && t.getOrNull(i + 1)?.text == "cent" -> {
                    expr.append("/100")
                    i++
                    if (t.getOrNull(i + 1)?.text == "of") {
                        expr.append('*')
                        i++
                    }
                }
                w.matches(EXPRESSION_WORD) -> expr.append(w)
                else -> return null
            }
            i++
        }
        val s = expr.toString()
        if (s.isEmpty() || Calc.eval(s) !is Calc.Ok) return null
        if (match.pattern.name == "calc.bare" && s.none { it in OPERATORS }) return null
        return Intent.Calculate(s, transcript)
    }

    // ---------------------------------------------------------------- torch

    private fun torchState(t: List<Token>): Boolean? {
        for (token in t) {
            when (token.text) {
                "on" -> return true
                "off" -> return false
                "toggle" -> return null
            }
        }
        return null
    }

    // ---------------------------------------------------------------- media

    /**
     * The vocabulary a transport command may trail off into. Guarded on
     * purpose: "next thursday" is a date, and it only stays one because
     * "thursday" is not in this set.
     */
    private val mediaTail = setOf(
        "a", "the", "this", "that", "it", "one", "to", "up", "ahead", "back",
        "forward", "forwards", "track", "song", "music", "episode", "chapter",
        "video", "audio", "volume", "everything", "next", "previous", "please",
    )

    private fun media(match: Match, t: List<Token>, transcript: String): Intent? {
        val name = match.pattern.name
        val command = when {
            "pause" in name || "stop" in name -> Intent.Media.Command.PAUSE
            "play" in name -> Intent.Media.Command.PLAY
            "next" in name -> Intent.Media.Command.NEXT
            "previous" in name || "back" in name -> Intent.Media.Command.PREVIOUS
            "volume" in name -> {
                val down = t.any { it.text in setOf("down", "lower", "quieter", "softer") }
                if (down) Intent.Media.Command.VOLUME_DOWN else Intent.Media.Command.VOLUME_UP
            }
            "mute" in name -> Intent.Media.Command.MUTE
            else -> return null
        }
        if (command == Intent.Media.Command.NEXT ||
            command == Intent.Media.Command.PREVIOUS ||
            command == Intent.Media.Command.MUTE
        ) {
            val rest = match.capture("rest") ?: IntRange.EMPTY
            if (!rest.all { t[it].text in mediaTail }) return null
        }
        return Intent.Media(command, transcript)
    }

    // ----------------------------------------------------------------- dial

    private fun dial(match: Match, t: List<Token>, words: List<String>, transcript: String): Intent? {
        val target = capture(t, words, match, "target") ?: return null
        // A call with a time in it is a calendar entry: "call mum tonight".
        if (taken(match, t, "target")) return null
        return Intent.Dial(target, transcript)
    }

    // -------------------------------------------------------------- message

    /**
     * Words that can begin the body of a message. When there is no "saying"
     * or "that" to split on, the recipient is one word, or two when the
     * second is not one of these: "text sarah jones hello" names two words
     * of person and one of body.
     */
    private val bodyStarters = setOf(
        "i", "im", "i'm", "ill", "i'll", "ive", "i've", "me", "my", "the", "a",
        "an", "that", "this", "it", "its", "it's", "we", "you", "your", "he",
        "she", "they", "there", "here", "please", "sorry", "hey", "hi", "hello",
        "yes", "no", "yeah", "not", "dont", "don't", "cant", "can't", "wont",
        "won't", "am", "is", "are", "was", "were", "be", "do", "does", "did",
        "have", "has", "had", "can", "could", "will", "would", "should", "might",
        "must", "about", "for", "on", "in", "at", "to", "from", "with", "if",
        "when", "where", "what", "how", "why", "and", "but", "or", "so", "just",
        "almost", "nearly", "soon", "now", "today", "tomorrow", "tonight",
        "running", "coming", "going", "leaving", "getting", "picking", "call",
        "tell", "say", "meet", "buy", "pick", "bring", "take", "get", "go",
        "come", "leave", "remember",
    )

    private fun message(
        match: Match,
        t: List<Token>,
        words: List<String>,
        transcript: String,
    ): Intent? {
        val range = match.capture("rest") ?: return null
        var from = range.first
        if (match.pattern.name == "message.send") {
            // "send a message to john saying hello". The noun is what keeps
            // "send the report" out of the messaging intent.
            if (t.getOrNull(from)?.text in setOf("a", "an", "the")) from++
            if (t.getOrNull(from)?.text !in setOf("message", "text", "sms")) return null
            from++
            if (t.getOrNull(from)?.text == "to") from++
        }
        var delim = -1
        for (j in from..range.last) {
            if (t[j].text == "saying" || t[j].text == "that") {
                delim = j
                break
            }
        }
        val toEnd = if (delim >= 0) {
            delim
        } else {
            from + recipientLength(t, from, range.last)
        }
        val to = verbatim(t, words, from, toEnd)
        val body = verbatim(t, words, if (delim >= 0) delim + 1 else toEnd, range.last + 1)
        if (to == null && body == null) return null
        return Intent.ComposeMessage(to, body, transcript)
    }

    private fun recipientLength(t: List<Token>, from: Int, last: Int): Int {
        if (from > last) return 0
        if (from + 1 <= last && t[from + 1].text !in bodyStarters) return 2
        return 1
    }

    // ------------------------------------------------------------------ web

    private val DOMAIN = Regex("[a-z0-9][a-z0-9-]*(\\.[a-z0-9][a-z0-9-]*)+")

    private fun web(match: Match, t: List<Token>, words: List<String>, transcript: String): Intent? {
        val target = capture(t, words, match, "target") ?: return null
        // The open form is a web handoff only for something that looks like a
        // domain; "open spotify" belongs to the launcher two patterns later.
        if (match.pattern.name == "web.open" && !DOMAIN.matches(target)) return null
        return Intent.OpenWeb(target, transcript)
    }

    // ------------------------------------------------------------- navigate

    private fun navigate(
        match: Match,
        t: List<Token>,
        words: List<String>,
        transcript: String,
    ): Intent? {
        val range = match.capture("dest") ?: return null
        val dest = verbatim(t, words, range.first, range.last + 1) ?: return null
        // "take me to dinner friday" is an evening plan, not a drive.
        if (range.any { it in TemporalExtractor.extract(Tokens(t)).consumed }) return null
        // The mode lives in front of the destination: "walking directions",
        // "drive to". A "bike" inside the destination is a shop, not a mode.
        var mode: String? = null
        for (j in 0 until range.first) {
            mode = when (t[j].text) {
                "walking", "walk" -> "walk"
                "driving", "drive" -> "drive"
                "cycling", "cycle", "biking", "bike", "ride" -> "bike"
                "transit" -> "transit"
                else -> mode
            }
        }
        return Intent.Navigate(dest, mode, transcript)
    }

    // ------------------------------------------------------------------ app

    private val reservedApps = setOf(
        "settings", "wifi", "bluetooth", "torch", "flashlight", "volume", "music",
    )

    private fun app(match: Match, t: List<Token>, words: List<String>, transcript: String): Intent? {
        val range = match.capture("name") ?: return null
        var until = range.last + 1
        if (t[until - 1].text in setOf("app", "application")) until--
        val name = verbatim(t, words, range.first, until) ?: return null
        if (name in reservedApps || DOMAIN.matches(name)) return null
        return Intent.OpenApp(name, transcript)
    }

    // --------------------------------------------------------------- common

    /** The captured run read back out of the original words, like the parser's title. */
    private fun capture(t: List<Token>, words: List<String>, match: Match, name: String): String? {
        val range = match.capture(name) ?: return null
        return verbatim(t, words, range.first, range.last + 1)
    }

    private fun verbatim(t: List<Token>, words: List<String>, from: Int, until: Int): String? {
        if (from >= until || until > t.size || t.isEmpty()) return null
        val first = t[from].span.first
        val last = t[until - 1].span.last
        if (first > last || last >= words.size) return null
        return words.slice(first..last).joinToString(" ").ifBlank { null }
    }

    /** True when the temporal extractor claims any token inside the capture. */
    private fun taken(match: Match, t: List<Token>, name: String): Boolean {
        val range = match.capture(name) ?: return false
        val consumed = TemporalExtractor.extract(Tokens(t)).consumed
        return range.any { it in consumed }
    }

    // -------------------------------------------------------------- patterns

    /**
     * Every phrase the assistant knows, in precedence order. Anchored and
     * whole-sentence, exactly like the calendar patterns they run ahead of.
     */
    private val patterns: List<Pattern> = listOf(
        // ---------------------------------------------------- device facts
        Pattern(
            "fact.time.what",
            Specificity.High,
            please,
            Term.AnyOf("what", "whats", "what's"),
            Term.Literal("time"),
            Term.Optional(Term.AnyOf("is", "s")),
            Term.Optional(Term.AnyOf("it", "now")),
        ),
        Pattern(
            "fact.time.the",
            Specificity.High,
            please,
            Term.AnyOf("what", "whats", "what's"),
            Term.Optional(Term.AnyOf("is", "s")),
            Term.Literal("the"),
            Term.Literal("time"),
            Term.Optional(Term.Literal("now")),
        ),
        Pattern(
            "fact.time.tell",
            Specificity.High,
            please,
            Term.Literal("tell"),
            Term.Optional(Term.Literal("me")),
            Term.Literal("the"),
            Term.Literal("time"),
        ),
        Pattern(
            "fact.time.have",
            Specificity.High,
            please,
            Term.Literal("do"),
            Term.Literal("you"),
            Term.Literal("have"),
            Term.Literal("the"),
            Term.Literal("time"),
        ),
        Pattern(
            "fact.time.current",
            Specificity.High,
            please,
            Term.Optional(Term.Literal("the")),
            Term.Literal("current"),
            Term.Literal("time"),
        ),
        Pattern(
            "fact.date.what",
            Specificity.High,
            please,
            Term.AnyOf("what", "whats", "what's"),
            Term.Optional(Term.AnyOf("is", "s")),
            Term.Optional(Term.Literal("the")),
            Term.Literal("date"),
            Term.Optional(Term.Literal("today")),
        ),
        Pattern(
            "fact.date.day",
            Specificity.High,
            please,
            Term.AnyOf("what", "whats", "what's"),
            Term.Literal("day"),
            Term.Optional(Term.AnyOf("is", "s")),
            Term.Optional(Term.AnyOf("it", "today")),
        ),
        Pattern(
            "fact.date.today",
            Specificity.High,
            please,
            Term.AnyOf("what", "whats", "what's"),
            Term.Optional(Term.AnyOf("is", "s")),
            Term.Literal("today"),
        ),
        Pattern(
            "fact.date.todays",
            Specificity.High,
            please,
            Term.AnyOf("todays", "today's"),
            Term.Literal("date"),
        ),
        Pattern(
            "fact.date.tell",
            Specificity.High,
            please,
            Term.Literal("tell"),
            Term.Optional(Term.Literal("me")),
            Term.Literal("the"),
            Term.Literal("date"),
        ),
        Pattern(
            "fact.battery.howmuch",
            Specificity.High,
            please,
            Term.Literal("how"),
            Term.Literal("much"),
            Term.AnyOf("battery", "charge", "power"),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "fact.battery.what",
            Specificity.High,
            please,
            Term.AnyOf("what", "whats", "what's"),
            Term.Optional(Term.AnyOf("is", "s")),
            Term.Optional(Term.AnyOf("the", "my")),
            Term.Literal("battery"),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "fact.battery.how",
            Specificity.High,
            please,
            Term.AnyOf("hows", "how's", "how"),
            Term.Optional(Term.AnyOf("is", "s")),
            Term.Optional(Term.AnyOf("my", "the")),
            Term.Literal("battery"),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "fact.battery.bare",
            Specificity.Medium,
            please,
            Term.Optional(Term.AnyOf("the", "my")),
            Term.Literal("battery"),
            Term.Optional(Term.AnyOf("level", "percentage", "status", "life", "left", "charge")),
        ),

        // ------------------------------------------------------------- timer
        Pattern(
            "timer.set",
            Specificity.High,
            please,
            Term.AnyOf("set", "start", "create", "add"),
            Term.Optional(Term.AnyOf("a", "an", "the", "new", "another")),
            Term.Literal("timer"),
            Term.Optional(Term.Literal("for")),
            Term.Capture("dur"),
        ),
        Pattern(
            "timer.bare",
            Specificity.High,
            please,
            Term.Literal("timer"),
            Term.Optional(Term.Literal("for")),
            Term.Capture("dur"),
        ),
        // "five minute timer". Declared last of the three so the verb forms
        // are tried first; a false capture here costs nothing either way,
        // because a duration that will not parse rejects the match.
        Pattern(
            "timer.lead",
            Specificity.Medium,
            please,
            Term.Capture("dur"),
            Term.Literal("timer"),
        ),

        // ------------------------------------------------------------- alarm
        Pattern(
            "alarm.set",
            Specificity.High,
            please,
            Term.AnyOf("set", "create", "add", "schedule"),
            Term.Optional(Term.AnyOf("a", "an", "the", "new")),
            Term.Literal("alarm"),
            Term.Optional(Term.AnyOf("for", "at")),
            Term.Capture("time"),
        ),
        Pattern(
            "alarm.bare",
            Specificity.High,
            please,
            Term.Literal("alarm"),
            Term.Optional(Term.AnyOf("for", "at")),
            Term.Capture("time"),
        ),
        Pattern(
            "alarm.wake",
            Specificity.High,
            please,
            Term.Literal("wake"),
            Term.Literal("me"),
            Term.Optional(Term.Literal("up")),
            Term.Optional(Term.AnyOf("at", "by")),
            Term.Capture("time"),
        ),

        // --------------------------------------------------------- calculate
        Pattern(
            "calc.verb",
            Specificity.High,
            please,
            Term.AnyOf("calculate", "compute", "evaluate", "solve"),
            Term.Capture("expr"),
        ),
        Pattern(
            "calc.howmuch",
            Specificity.High,
            please,
            Term.Literal("how"),
            Term.Literal("much"),
            Term.Literal("is"),
            Term.Capture("expr"),
        ),
        // The broadest of the three and deliberately last: every "what is X"
        // that is not arithmetic falls through to the conversational answer.
        Pattern(
            "calc.what",
            Specificity.Medium,
            please,
            Term.AnyOf("what", "whats", "what's"),
            Term.Optional(Term.AnyOf("is", "s")),
            Term.Capture("expr"),
        ),
        // A sum said on its own, "ten multiplied by eight". The recogniser
        // drops a short "what's" often enough that this is the same request.
        // It only counts with an operator in it, so a bare "eight" or a
        // sentence that is all numbers still goes to the calendar.
        Pattern(
            "calc.bare",
            Specificity.Medium,
            please,
            Term.Capture("expr"),
        ),

        // ---------------------------------------------------------- settings
        Pattern(
            "settings.wifi.toggle",
            Specificity.High,
            please,
            Term.AnyOf("turn", "switch"),
            Term.AnyOf("on", "off"),
            Term.Optional(Term.AnyOf("the", "my")),
            Term.AnyOf("wifi", "wi-fi"),
            Term.Optional(Term.AnyOf("settings", "panel")),
        ),
        Pattern(
            "settings.wifi.open",
            Specificity.High,
            please,
            Term.Optional(Term.AnyOf("open", "show", "launch", "go", "enable", "disable")),
            Term.Optional(Term.AnyOf("the", "my", "to", "on", "off")),
            Term.AnyOf("wifi", "wi-fi"),
            Term.Optional(Term.AnyOf("settings", "panel")),
        ),
        Pattern(
            "settings.bluetooth.toggle",
            Specificity.High,
            please,
            Term.AnyOf("turn", "switch"),
            Term.AnyOf("on", "off"),
            Term.Optional(Term.AnyOf("the", "my")),
            Term.AnyOf("bluetooth", "bt"),
            Term.Optional(Term.AnyOf("settings", "panel")),
        ),
        Pattern(
            "settings.bluetooth.open",
            Specificity.High,
            please,
            Term.Optional(Term.AnyOf("open", "show", "launch", "go", "enable", "disable")),
            Term.Optional(Term.AnyOf("the", "my", "to", "on", "off")),
            Term.AnyOf("bluetooth", "bt"),
            Term.Optional(Term.AnyOf("settings", "panel")),
        ),
        Pattern(
            "settings.main",
            Specificity.High,
            please,
            Term.Optional(Term.AnyOf("open", "show", "go", "launch")),
            Term.Optional(Term.AnyOf("the", "my", "to")),
            Term.Optional(Term.AnyOf("system", "phone", "device", "main")),
            Term.Literal("settings"),
            Term.Optional(Term.AnyOf("app", "panel")),
        ),

        // ------------------------------------------------------------- torch
        Pattern(
            "torch.verb",
            Specificity.High,
            please,
            Term.AnyOf("turn", "switch"),
            Term.AnyOf("on", "off"),
            Term.Optional(Term.AnyOf("the", "my")),
            Term.AnyOf("flashlight", "torch", "light"),
        ),
        Pattern(
            "torch.verb.late",
            Specificity.High,
            please,
            Term.AnyOf("turn", "switch"),
            Term.Optional(Term.AnyOf("the", "my")),
            Term.AnyOf("flashlight", "torch", "light"),
            Term.AnyOf("on", "off"),
        ),
        Pattern(
            "torch.state",
            Specificity.High,
            please,
            Term.Optional(Term.AnyOf("the", "my")),
            Term.AnyOf("flashlight", "torch"),
            Term.Optional(Term.AnyOf("on", "off")),
        ),
        // "light" on its own is too weak a claim; it needs a direction.
        Pattern(
            "torch.light",
            Specificity.Medium,
            please,
            Term.Optional(Term.AnyOf("the", "my")),
            Term.Literal("light"),
            Term.AnyOf("on", "off"),
        ),
        Pattern(
            "torch.toggle",
            Specificity.High,
            please,
            Term.Literal("toggle"),
            Term.Optional(Term.AnyOf("the", "my")),
            Term.AnyOf("flashlight", "torch", "light"),
        ),

        // ------------------------------------------------------------- media
        Pattern(
            "media.pause",
            Specificity.High,
            please,
            Term.Literal("pause"),
            Term.Capture("rest", min = 0),
        ),
        // A bare "stop" never gets here: the agent grammar owns it first.
        Pattern(
            "media.stop",
            Specificity.High,
            please,
            Term.Literal("stop"),
            Term.Optional(Term.AnyOf("the", "this", "that")),
            Term.AnyOf("music", "song", "track", "playing", "audio", "everything"),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "media.play",
            Specificity.High,
            please,
            Term.AnyOf("play", "resume", "unpause"),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "media.next",
            Specificity.High,
            please,
            Term.AnyOf("next", "skip"),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "media.previous",
            Specificity.High,
            please,
            Term.AnyOf("previous", "last", "back"),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "media.back",
            Specificity.Medium,
            please,
            Term.Literal("go"),
            Term.Literal("back"),
            Term.Capture("rest", min = 0),
        ),
        // One pattern serves both directions; the direction word inside it
        // is what the handler reads. "turn up the volume", "volume down",
        // "turn it up", "louder" all fit the same frame.
        Pattern(
            "media.volume",
            Specificity.High,
            please,
            Term.Optional(Term.AnyOf("turn", "switch", "make", "put", "take")),
            Term.Optional(Term.AnyOf("the", "my", "it")),
            Term.Optional(Term.Literal("volume")),
            Term.AnyOf("up", "higher", "louder", "down", "lower", "quieter", "softer"),
            Term.Optional(Term.AnyOf("the", "my")),
            Term.Optional(Term.Literal("volume")),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "media.mute",
            Specificity.High,
            please,
            Term.AnyOf("mute", "unmute", "silence"),
            Term.Capture("rest", min = 0),
        ),

        // -------------------------------------------------------------- dial
        Pattern(
            "dial.call",
            Specificity.High,
            please,
            Term.AnyOf("call", "phone", "dial", "ring"),
            Term.Optional(Term.AnyOf("up", "to")),
            Term.Capture("target"),
        ),

        // ----------------------------------------------------------- message
        Pattern(
            "message.verb",
            Specificity.High,
            please,
            Term.AnyOf("text", "message", "sms"),
            Term.Optional(Term.Literal("to")),
            Term.Capture("rest"),
        ),
        Pattern(
            "message.send",
            Specificity.High,
            please,
            Term.Literal("send"),
            Term.Capture("rest"),
        ),

        // --------------------------------------------------------------- web
        Pattern(
            "web.search",
            Specificity.High,
            please,
            Term.AnyOf("search", "google", "bing"),
            Term.Optional(Term.AnyOf("the", "for", "web", "a")),
            Term.Optional(Term.AnyOf("the", "for", "web", "a")),
            Term.Optional(Term.AnyOf("the", "for", "web", "a")),
            Term.Capture("target"),
        ),
        Pattern(
            "web.lookup.up",
            Specificity.High,
            please,
            Term.Literal("look"),
            Term.AnyOf("up", "for"),
            Term.Optional(Term.AnyOf("the", "a", "for")),
            Term.Optional(Term.AnyOf("the", "a")),
            Term.Capture("target"),
        ),
        Pattern(
            "web.lookup.word",
            Specificity.High,
            please,
            Term.Literal("lookup"),
            Term.Optional(Term.AnyOf("the", "a", "for")),
            Term.Capture("target"),
        ),
        Pattern(
            "web.open",
            Specificity.High,
            please,
            Term.AnyOf("open", "go", "visit", "browse"),
            Term.Optional(Term.AnyOf("to", "the")),
            Term.Capture("target"),
        ),

        // ---------------------------------------------------------- navigate
        Pattern(
            "nav.directions",
            Specificity.High,
            please,
            Term.Optional(Term.AnyOf("get", "give", "show", "find")),
            Term.Optional(Term.AnyOf("me", "us")),
            Term.Optional(Term.AnyOf("the", "a")),
            Term.Optional(
                Term.AnyOf("walking", "driving", "cycling", "biking", "transit", "hiking"),
            ),
            Term.AnyOf("directions", "direction", "route", "navigation", "way"),
            Term.Optional(Term.AnyOf("to", "for")),
            Term.Capture("dest"),
        ),
        Pattern(
            "nav.nav",
            Specificity.High,
            please,
            Term.AnyOf("navigate", "navigation"),
            Term.Optional(Term.Literal("to")),
            Term.Capture("dest"),
        ),
        Pattern(
            "nav.take",
            Specificity.High,
            please,
            Term.Literal("take"),
            Term.Literal("me"),
            Term.Optional(Term.Literal("to")),
            Term.Capture("dest"),
        ),
        // The motion verbs need "to": "walk the dog" is a plan, "walk to the
        // park" is a trip. "go" is here too; a domain after it was already
        // claimed by the web handoff above.
        Pattern(
            "nav.go",
            Specificity.High,
            please,
            Term.AnyOf("drive", "walk", "cycle", "bike", "go", "head", "ride"),
            Term.Literal("to"),
            Term.Capture("dest"),
        ),

        // --------------------------------------------------------------- app
        Pattern(
            "app.open",
            Specificity.Medium,
            please,
            Term.AnyOf("open", "launch", "start", "load"),
            Term.Optional(Term.AnyOf("the", "my")),
            Term.Capture("name"),
        ),
    )
}
