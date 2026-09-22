package dev.maia.nlu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Runs `corpus/seed.tsv` and reports field level accuracy.
 *
 * Read the header of that file before reading this number. The corpus is
 * written, not spoken, and by the same person who wrote the grammar, so it
 * measures whether the grammar is self consistent and not whether it works.
 * The recorded corpus PRD section 7 asks for does not exist yet and this is
 * not a stand in for it.
 *
 * The thresholds below are deliberately under a hundred percent. A suite that
 * demands perfection on its author's own sentences gets maintained by editing
 * the expectations, which is how a corpus stops measuring anything. A miss
 * that is genuinely a miss should be allowed to sit there and be printed.
 */
class CorpusTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-09-12T09:30:00Z"), zone)
    private val parser = Parser(clock)

    private data class Row(
        val utterance: String,
        val intent: String,
        val text: String,
        val start: String,
        val minutes: String,
        val allDay: String,
    )

    private fun rows(): List<Row> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("corpus/seed.tsv")) {
            "corpus/seed.tsv is not on the test classpath"
        }
        return stream.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .drop(1)
            .map { line ->
                val c = line.split("\t")
                Row(c[0], c[1], c[2], c[3], c[4], c[5])
            }
    }

    private fun observed(intent: Intent): Row = when (intent) {
        is Intent.CreateEvent -> row("event", intent.draft)
        is Intent.Unparsed -> row("unparsed", intent.draft)
        is Intent.CaptureNote -> Row(
            "", "note", intent.body.value,
            intent.remindAt?.value?.let(::local) ?: "-",
            "-", "-",
        )
        is Intent.Agenda -> range("agenda", intent.range)
        is Intent.Availability -> range("availability", intent.range)
        // The agent intents have no rows in this corpus and should not get
        // any: every line here is a calendar sentence, and a line that started
        // coming back as an agent instruction would be a regression rather
        // than a new expectation to write down. They are listed so that the
        // compiler keeps this exhaustive, and they carry no time and no title.
        is Intent.AgentInstruction -> Row("", "agent.instruction", intent.instruction.value, "-", "-", "-")
        is Intent.AgentFocus -> Row("", "agent.focus", "-", "-", "-", "-")
        is Intent.AgentStop -> Row("", "agent.stop", "-", "-", "-", "-")
        is Intent.AgentStatus -> Row("", "agent.status", "-", "-", "-", "-")
        // The M9 assistant intents have no rows here for the same reason the
        // agent ones do not: every line is a calendar sentence, and a line
        // that started coming back as one of these is a regression. Listed
        // so the compiler keeps this exhaustive.
        is Intent.SetTimer, is Intent.SetAlarm, is Intent.Calculate,
        is Intent.DeviceFact, is Intent.OpenSettings, is Intent.SetTorch,
        is Intent.Dial, is Intent.ComposeMessage, is Intent.Navigate,
        is Intent.OpenWeb, is Intent.OpenApp, is Intent.Media,
        is Intent.Conversation -> Row("", "assistant", "-", "-", "-", "-")
    }

    private fun row(name: String, draft: EventDraft) = Row(
        "", name, draft.title.value, local(draft.start.value),
        draft.duration.value.toMinutes().toString(),
        if (draft.allDay) "y" else "n",
    )

    private fun range(name: String, r: ClosedRange<ZonedDateTime>) = Row(
        "", name, "-", local(r.start),
        Duration.between(r.start, r.endInclusive).toMinutes().toString(),
        "-",
    )

    private fun local(t: ZonedDateTime): String =
        t.toLocalDateTime().withSecond(0).withNano(0).toString()

    @Test
    fun `the seed corpus parses at the accuracy this file claims`() {
        val rows = rows()
        assertTrue("corpus is suspiciously small: ${rows.size}", rows.size >= 60)

        var intentHits = 0
        var textHits = 0
        var textTotal = 0
        var startHits = 0
        var startTotal = 0
        var minuteHits = 0
        var minuteTotal = 0
        val misses = StringBuilder()

        for (r in rows) {
            val got = observed(parser.parse(r.utterance))
            val bad = StringBuilder()

            if (got.intent == r.intent) intentHits += 1 else bad.append(" intent=${got.intent}")
            if (r.text != "-") {
                textTotal += 1
                if (got.text == r.text) textHits += 1 else bad.append(" text=[${got.text}]")
            }
            if (r.start != "-") {
                startTotal += 1
                if (got.start == r.start) startHits += 1 else bad.append(" start=${got.start}")
            }
            if (r.minutes != "-") {
                minuteTotal += 1
                if (got.minutes == r.minutes) minuteHits += 1 else bad.append(" mins=${got.minutes}")
            }
            if (bad.isNotEmpty()) misses.append("  ${r.utterance}\n   ->$bad\n")
        }

        val n = rows.size
        println(
            buildString {
                append("\nseed corpus, $n written utterances, fixed clock 2026-09-12T11:30+02:00\n")
                append("  intent    ${pct(intentHits, n)}  ($intentHits/$n)\n")
                append("  text      ${pct(textHits, textTotal)}  ($textHits/$textTotal)\n")
                append("  start     ${pct(startHits, startTotal)}  ($startHits/$startTotal)\n")
                append("  duration  ${pct(minuteHits, minuteTotal)}  ($minuteHits/$minuteTotal)\n")
                if (misses.isNotEmpty()) append("\nmisses:\n$misses")
                append("\nWritten, not spoken. See the header of seed.tsv.\n")
            },
        )

        assertTrue("intent accuracy ${pct(intentHits, n)} is below 90 percent", intentHits * 100 >= n * 90)
        assertTrue("start accuracy ${pct(startHits, startTotal)} is below 85 percent", startHits * 100 >= startTotal * 85)
        assertTrue("text accuracy ${pct(textHits, textTotal)} is below 80 percent", textHits * 100 >= textTotal * 80)
        assertTrue("duration accuracy ${pct(minuteHits, minuteTotal)} is below 85 percent", minuteHits * 100 >= minuteTotal * 85)
    }

    private fun pct(hits: Int, total: Int): String =
        if (total == 0) "n/a" else String.format("%5.1f%%", hits * 100.0 / total)
}
