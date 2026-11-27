package dev.yorkie.document.presence

/**
 * The presence value codec: Yorkie's wire contract is that a presence value on the wire is the
 * JSON encoding of the value (`docs/design/presence.md`, enforced by the server since 0.7.29,
 * yorkie#2386; JS `JSON.stringify`/`parseAttrValue`, Go `presence.EncodeValue`/`DecodeValue`).
 * The public presence type stays `Map<String, String>`: an app sets text and reads text, and the
 * SDK encodes at the wire boundary ([dev.yorkie.api.toPBPresence]) and decodes there
 * ([dev.yorkie.api.toPresence]); everything inside the SDK holds the decoded app form.
 *
 * [decodeFromWire] matches Go `DecodeValue` (a JSON string literal decodes to its content, any
 * other text is returned as it arrived) with ONE deliberate difference: a bare `null` is
 * returned as the text `null`. Go's `json.Unmarshal("null", &string)` is a no-op that leaves
 * `""`, which would make a stored `null` read back empty; Android keeps `null` verbatim.
 *
 * [encodeForWire] is coupled to the decoder so every string round-trips exactly: a value is sent
 * as it is only if it is valid JSON AND the decoder would return it unchanged (objects, arrays,
 * numbers, `true`, `false`, `null`, which therefore still reach JS peers typed, as today); every
 * other value, including a JSON string literal such as `"x"` or ` "x" `, is JSON-encoded.
 * Unlike Go's `Set` (always encodes) this passes valid non-string JSON through, as Go's
 * `SetJSON` does, because Android's single `put` serves both uses.
 */
internal object PresenceValue {

    /** Mirrors the server's repair (`withinDepth`): nesting deeper than this is not "JSON". */
    internal const val MAX_DEPTH = 100

    fun encodeForWire(value: String): String {
        // "valid JSON and decodeFromWire(value) == value": for valid JSON the decoder changes
        // the text exactly when it is a string literal (its content is shorter than the
        // literal), i.e. when the first non-whitespace character is a quote, so the second
        // parse is skipped.
        if (!isJson(value)) return encodeString(value)
        val first = value.firstOrNull { it != ' ' && it != '\t' && it != '\n' && it != '\r' }
        return if (first == '"') encodeString(value) else value
    }

    fun decodeFromWire(wire: String): String {
        // Go's json.Unmarshal maps an escaped lone surrogate ("\ud800") to U+FFFD; this decoder
        // (like JS JSON.parse) keeps the surrogate. Harmless: encodeString writes a lone
        // surrogate back as \udXXX, so the value still round-trips exactly.
        return decodeJsonString(wire) ?: wire
    }

    /**
     * `JSON.stringify(value)` for a string: escapes `"`, `\`, U+0000..U+001F (short forms for
     * `\b \f \n \r \t`, else lowercase `\u00xx`) and lone surrogates (`\udXXX`, well-formed
     * `JSON.stringify`); everything else, including U+2028/2029, `/` and `<`, stays raw.
     */
    fun encodeString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(hex4(c.code))
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    out.append(c).append(value[i + 1])
                    i++
                }
                c.isSurrogate() -> out.append("\\u").append(hex4(c.code))
                else -> out.append(c)
            }
            i++
        }
        return out.append('"').toString()
    }

    /**
     * Strict RFC 8259 validation (surrounding whitespace allowed, raw control characters and
     * lone surrogates rejected), nesting capped at [MAX_DEPTH]. Recursion is bounded by the cap.
     */
    fun isJson(text: String): Boolean {
        val parser = Parser(text)
        parser.skipWhitespace()
        if (!parser.value(depth = 0)) return false
        parser.skipWhitespace()
        return parser.atEnd()
    }

    /**
     * Returns the content of [text] if it is exactly one JSON string literal (surrounding JSON
     * whitespace allowed), or null if it is anything else.
     */
    private fun decodeJsonString(text: String): String? {
        val parser = Parser(text)
        parser.skipWhitespace()
        if (parser.peek() != '"') return null
        val out = StringBuilder()
        if (!parser.string(out)) return null
        parser.skipWhitespace()
        return if (parser.atEnd()) out.toString() else null
    }

    private fun hex4(code: Int): String = code.toString(16).padStart(4, '0')

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun peek(): Char? = s.getOrNull(i)

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) {
                i++
            }
        }

        fun value(depth: Int): Boolean {
            return when (peek()) {
                '{' -> container(depth, '}') { objectMember(depth + 1) }
                '[' -> container(depth, ']') { value(depth + 1).also { skipWhitespace() } }
                '"' -> string(null)
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        private inline fun container(
            depth: Int,
            close: Char,
            element: () -> Boolean,
        ): Boolean {
            if (depth + 1 > MAX_DEPTH) return false
            i++ // opening bracket
            skipWhitespace()
            if (peek() == close) {
                i++
                return true
            }
            while (true) {
                skipWhitespace()
                if (!element()) return false
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    close -> {
                        i++
                        return true
                    }
                    else -> return false
                }
            }
        }

        private fun objectMember(depth: Int): Boolean {
            if (peek() != '"' || !string(null)) return false
            skipWhitespace()
            if (peek() != ':') return false
            i++
            skipWhitespace()
            return value(depth).also { skipWhitespace() }
        }

        private fun literal(word: String): Boolean {
            if (!s.startsWith(word, i)) return false
            i += word.length
            return true
        }

        private fun number(): Boolean {
            if (peek() == '-') i++
            when (peek()) {
                '0' -> i++
                in '1'..'9' -> digits()
                else -> return false
            }
            if (peek() == '.') {
                i++
                if (!digits()) return false
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (!digits()) return false
            }
            return true
        }

        private fun digits(): Boolean {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            return i > start
        }

        /** Consumes one string literal at [i]; appends its decoded content to [out] if given. */
        fun string(out: StringBuilder?): Boolean {
            if (peek() != '"') return false
            i++
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return true
                    }
                    c < ' ' -> return false
                    c == '\\' -> {
                        i++
                        if (!escape(out)) return false
                    }
                    c.isHighSurrogate() -> {
                        if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return false
                        out?.append(c)?.append(s[i + 1])
                        i += 2
                    }
                    c.isLowSurrogate() -> return false
                    else -> {
                        out?.append(c)
                        i++
                    }
                }
            }
            return false
        }

        private fun escape(out: StringBuilder?): Boolean {
            val c = peek() ?: return false
            i++
            val decoded = when (c) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (i + 4 > s.length) return false
                    var code = 0
                    for (k in 0 until 4) {
                        val d = Character.digit(s[i + k], 16)
                        if (d < 0 || s[i + k] > 'f') return false
                        code = code * 16 + d
                    }
                    i += 4
                    code.toChar()
                }
                else -> return false
            }
            out?.append(decoded)
            return true
        }
    }
}
