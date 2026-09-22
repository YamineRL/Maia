package dev.maia.transport

/**
 * The smallest JSON this module can get away with.
 *
 * Parsing yields plain Kotlin: [Map] with String keys, [List], [String],
 * [Double], [Boolean], or null. Nothing is reflected onto data classes,
 * because the payloads that matter here are an event union with eighty-odd
 * members of which we read four fields, and a session envelope of which we
 * read two.
 *
 * Numbers are all Double. JSON has one number type and pretending otherwise
 * invites a silent truncation; callers that want a timestamp ask for a Long
 * through [long], which is explicit about the narrowing.
 */
object Json {

    fun parse(text: String): Any? {
        val r = Reader(text)
        val v = r.value()
        r.skipSpace()
        if (!r.done) throw JsonException("trailing text at ${r.pos}")
        return v
    }

    /** Renders a value tree. Maps, lists, strings, numbers, booleans, null. */
    fun write(value: Any?): String = StringBuilder().also { render(value, it) }.toString()

    /**
     * Convenience for the request bodies, which are all small literal objects.
     * A null value drops its key rather than sending `null`: the server's
     * schemas are `additionalProperties: false` with optional keys, and an
     * explicit null is not the same thing as an absent one.
     */
    fun obj(vararg pairs: Pair<String, Any?>): String =
        write(linkedMapOf(*pairs).filterValues { it != null })

    private fun render(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is String -> quote(value, out)
            is Number -> {
                val d = value.toDouble()
                out.append(
                    if (!d.isInfinite() && !d.isNaN() && d == Math.floor(d)) d.toLong().toString()
                    else d.toString()
                )
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(k.toString(), out)
                    out.append(':')
                    render(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    render(v, out)
                }
                out.append(']')
            }
            else -> throw JsonException("cannot write ${value.javaClass.name}")
        }
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append("%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Reader(private val s: String) {
        var pos = 0
        val done get() = pos >= s.length

        fun value(): Any? {
            skipSpace()
            if (done) throw JsonException("empty input")
            val c = s[pos]
            return when {
                c == '{' -> obj()
                c == '[' -> arr()
                c == '"' -> str()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> num()
                else -> throw JsonException("unexpected '$c' at $pos")
            }
        }

        fun skipSpace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun expect(c: Char) {
            skipSpace()
            if (done || s[pos] != c) throw JsonException("expected '$c' at $pos")
            pos++
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipSpace()
            if (!done && s[pos] == '}') {
                pos++
                return m
            }
            while (true) {
                skipSpace()
                val k = str()
                expect(':')
                m[k] = value()
                skipSpace()
                if (done) throw JsonException("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return m
                    }
                    else -> throw JsonException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val l = ArrayList<Any?>()
            skipSpace()
            if (!done && s[pos] == ']') {
                pos++
                return l
            }
            while (true) {
                l.add(value())
                skipSpace()
                if (done) throw JsonException("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return l
                    }
                    else -> throw JsonException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                if (done) throw JsonException("unterminated string")
                val c = s[pos++]
                if (c == '"') return b.toString()
                if (c != '\\') {
                    b.append(c)
                    continue
                }
                if (done) throw JsonException("unterminated escape")
                when (val e = s[pos++]) {
                    '"', '\\', '/' -> b.append(e)
                    'b' -> b.append('\b')
                    'f' -> b.append(12.toChar())
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    't' -> b.append('\t')
                    'u' -> {
                        if (pos + 4 > s.length) throw JsonException("short unicode escape at $pos")
                        b.append(s.substring(pos, pos + 4).toInt(16).toChar())
                        pos += 4
                    }
                    else -> throw JsonException("bad escape at ${pos - 1}: $e")
                }
            }
        }

        private fun num(): Double {
            val start = pos
            if (!done && s[pos] == '-') pos++
            while (!done && (s[pos] in '0'..'9' || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull()
                ?: throw JsonException("bad number at $start")
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("bad literal at $pos")
            pos += word.length
            return v
        }
    }
}

class JsonException(message: String) : RuntimeException("json: $message")

/**
 * Walks a parsed tree by key. Missing keys and wrong types give null rather
 * than an exception: an event union this wide will grow fields we do not know
 * about, and a client that throws on the unexpected is a client that stops
 * working on a server upgrade.
 */
fun Any?.at(vararg path: String): Any? {
    var node = this
    for (key in path) {
        node = (node as? Map<*, *>)?.get(key) ?: return null
    }
    return node
}

fun Any?.string(vararg path: String): String? = at(*path) as? String

fun Any?.long(vararg path: String): Long? = (at(*path) as? Double)?.toLong()

fun Any?.list(vararg path: String): List<Any?>? = at(*path) as? List<Any?>
