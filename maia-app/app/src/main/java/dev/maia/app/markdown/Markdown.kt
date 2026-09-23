package dev.maia.app.markdown

/**
 * The subset of Markdown an agent's prose actually carries, parsed into
 * renderable blocks.
 *
 * This is not a CommonMark implementation and does not try to be one. The
 * input is model output on a chat surface, which means: headings, fenced
 * blocks, pipe tables, lists, quotes, rules, and the inline marks those
 * contain. Anything outside that subset falls through to plain text, which
 * is the honest rendering of it.
 *
 * Two input realities shaped the rules:
 *
 * - **It streams.** The tail [ReplyPiece][dev.maia.app.agent.ReplyPiece]
 *   grows a delta at a time, so every construct must degrade gracefully when
 *   only half of it has arrived: an unclosed fence reads as code to the end
 *   of the text, and a table is not a table until its separator row lands.
 * - **It is machine-written.** Models emit `snake_case` identifiers inside
 *   prose constantly, so `_` and `*` only count as emphasis at a word
 *   boundary; a marker buried inside a word is a character, not a mark.
 */
object Markdown {

    sealed interface Block {
        data class Paragraph(val runs: List<Run>) : Block
        data class Heading(val level: Int, val runs: List<Run>) : Block
        /** Verbatim text; the fence line itself and its language tag are gone. */
        data class Fence(val text: String) : Block
        /** Cells are raw text. Inline marks inside a cell are left alone: the
         * mono column padding counts characters, and a bold marker counted as
         * width would skew every row under it. */
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block
        data class Listing(val ordered: Boolean, val items: List<List<Run>>) : Block
        data class Quote(val runs: List<Run>) : Block
        data object Rule : Block
    }

    /** One run of text under one set of styles. Nested marks flatten into flags. */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    )

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    ) {
        fun run(text: String) = Run(text, bold, italic, code, strike, link)
    }

    private val ESCAPABLE = "\\`*_{}[]()#+-.!~|>"

    fun blocks(source: String): List<Block> {
        val lines = source.split('\n')
        val out = mutableListOf<Block>()
        val para = StringBuilder()

        fun flushPara() {
            if (para.isNotEmpty()) {
                out += Block.Paragraph(inline(para.toString().trimEnd('\n')))
                para.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            val fence = fenceStart(line)
            if (fence != null) {
                flushPara()
                val body = StringBuilder()
                i++
                while (i < lines.size && !isFenceEnd(lines[i], fence)) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // the closing fence
                out += Block.Fence(body.toString().trimEnd('\n'))
                continue
            }

            if (line.isBlank()) {
                flushPara()
                i++
                continue
            }

            if (line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                flushPara()
                val header = cells(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += cells(lines[i])
                    i++
                }
                out += Block.Table(header, rows)
                continue
            }

            val heading = Regex("^#{1,6}\\s+").find(line)
            if (heading != null) {
                flushPara()
                val level = heading.value.trim().length
                val text = line.removeRange(0, heading.value.length).replace(Regex("\\s+#+\\s*$"), "")
                out += Block.Heading(level, inline(text))
                i++
                continue
            }

            if (Regex("^\\s{0,3}(-{3,}|_{3,}|\\*{3,})\\s*$").matches(line)) {
                flushPara()
                out += Block.Rule
                i++
                continue
            }

            if (Regex("^\\s{0,3}>\\s?").containsMatchIn(line)) {
                flushPara()
                val quote = StringBuilder()
                while (i < lines.size && Regex("^\\s{0,3}>\\s?").containsMatchIn(lines[i])) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[i].replaceFirst(Regex("^\\s{0,3}>\\s?"), ""))
                    i++
                }
                out += Block.Quote(inline(quote.toString()))
                continue
            }

            val item = Regex("^(\\s{0,3})([-*+]|\\d{1,3}[.)])\\s+").find(line)
            if (item != null) {
                flushPara()
                val ordered = item.groupValues[2].first().isDigit()
                val items = mutableListOf<List<Run>>()
                while (i < lines.size) {
                    val m = Regex("^(\\s{0,3})([-*+]|\\d{1,3}[.)])\\s+").find(lines[i]) ?: break
                    items += inline(lines[i].removeRange(0, m.value.length))
                    i++
                }
                out += Block.Listing(ordered, items)
                continue
            }

            para.append(line).append('\n')
            i++
        }
        flushPara()
        return out
    }

    /** An opening fence: three or more backticks or tildes, nothing else required. */
    private fun fenceStart(line: String): Pair<Char, Int>? {
        val m = Regex("^\\s{0,3}(`{3,}|~{3,})") .find(line) ?: return null
        return m.value.trimStart().first() to m.value.trimStart().length
    }

    private fun isFenceEnd(line: String, open: Pair<Char, Int>): Boolean {
        val m = Regex("^\\s{0,3}(" + Regex.escape(open.first.toString()) + "{${open.second},})\\s*$")
        return m.matches(line)
    }

    /** A table separator row: pipes separating groups of dashes and colons. */
    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        if (!t.contains('-')) return false
        val fields = t.removePrefix("|").removeSuffix("|").split('|')
        return fields.isNotEmpty() && fields.all { f -> f.trim().matches(Regex(":?-+:?")) }
    }

    /** Cells of a pipe row. `\|` does not split. */
    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|")
            .split(Regex("(?<!\\\\)\\|"))
            .map { it.trim().replace("\\|", "|") }

    /**
     * Inline marks in one string. Recursive: a construct's interior is scanned
     * again under the widened style, so `**bold *nested* marks**` resolves.
     */
    fun inline(source: String): List<Run> {
        val out = mutableListOf<Run>()
        scan(source, Style(), out)
        return out
    }

    private fun scan(s: String, style: Style, out: MutableList<Run>) {
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out += style.run(buf.toString())
                buf.clear()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                    buf.append(s[i + 1]); i += 2
                }
                c == '`' -> {
                    val n = runLength(s, i, '`')
                    val close = findClose(s, "`".repeat(n), i + n)
                    if (close < 0) {
                        buf.append(c); i++
                    } else {
                        flush()
                        var inner = s.substring(i + n, close)
                        if (inner.length > 1 && inner.startsWith(' ') && inner.endsWith(' ')) {
                            inner = inner.substring(1, inner.length - 1)
                        }
                        out += style.copy(code = true).run(inner)
                        i = close + n
                    }
                }
                c == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                    // An image renders as its alt text; the picture itself has
                    // no channel back to the phone and no business being fetched.
                    val end = s.indexOf(']', i + 2)
                    if (end < 0) { buf.append(c); i++ } else {
                        buf.append(s.substring(i + 2, end))
                        i = skipTarget(s, end + 1)
                    }
                }
                c == '[' -> {
                    val end = s.indexOf(']', i + 1)
                    val open = if (end > 0 && end + 1 < s.length && s[end + 1] == '(') end + 1 else -1
                    val close = if (open > 0) s.indexOf(')', open + 1) else -1
                    if (close < 0) { buf.append(c); i++ } else {
                        flush()
                        scan(s.substring(i + 1, end), style.copy(link = s.substring(open + 1, close)), out)
                        i = close + 1
                    }
                }
                c == '~' && s.startsWith("~~", i) -> {
                    val close = findClose(s, "~~", i + 2)
                    if (close < 0) { buf.append(c); i++ } else {
                        flush()
                        scan(s.substring(i + 2, close), style.copy(strike = true), out)
                        i = close + 2
                    }
                }
                c == '*' || c == '_' -> {
                    val n = minOf(runLength(s, i, c), 3)
                    var used = 0
                    var close = -1
                    // Longest first: *** is bold+italic, ** bold, * italic.
                    var tryN = n
                    while (tryN > 0 && close < 0) {
                        if (opensAt(s, i)) {
                            close = findCloseBounded(s, c.toString().repeat(tryN), i + tryN)
                        }
                        if (close >= 0) used = tryN else tryN--
                    }
                    if (used == 0) { buf.append(c); i++ } else {
                        flush()
                        val inner = when (used) {
                            1 -> style.copy(italic = true)
                            2 -> style.copy(bold = true)
                            else -> style.copy(bold = true, italic = true)
                        }
                        scan(s.substring(i + used, close), inner, out)
                        i = close + used
                    }
                }
                else -> { buf.append(c); i++ }
            }
        }
        flush()
    }

    private fun runLength(s: String, i: Int, c: Char): Int {
        var n = 0
        while (i + n < s.length && s[i + n] == c) n++
        return n
    }

    private fun findClose(s: String, delim: String, from: Int): Int = s.indexOf(delim, from)

    /** A closer for emphasis must also sit on a word boundary on the inside. */
    private fun findCloseBounded(s: String, delim: String, from: Int): Int {
        var at = s.indexOf(delim, from)
        while (at >= 0) {
            val before = if (at > 0) s[at - 1] else ' '
            val after = if (at + delim.length < s.length) s[at + delim.length] else ' '
            if (!before.isWhitespace() && !after.isLetterOrDigit()) return at
            at = s.indexOf(delim, at + 1)
        }
        return -1
    }

    /** An opener must follow whitespace or punctuation, never a letter. */
    private fun opensAt(s: String, i: Int): Boolean =
        i == 0 || !s[i - 1].isLetterOrDigit()

    /** Past `(url)` or `<url>` after a link/image's `]`, or just past `]`. */
    private fun skipTarget(s: String, i: Int): Int {
        if (i < s.length && s[i] == '(') {
            val close = s.indexOf(')', i + 1)
            if (close >= 0) return close + 1
        }
        return i
    }
}
