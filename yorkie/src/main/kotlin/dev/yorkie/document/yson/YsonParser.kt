package dev.yorkie.document.yson

import dev.yorkie.util.YorkieException

/**
 * [parse] parses a YSON formatted string into a typed [YsonValue].
 *
 * YSON extends JSON with the following type constructors:
 * - `Text([...])` for the Text CRDT.
 * - `Tree({...})` for the Tree CRDT.
 * - `Int(42)` and `Long(64)` for typed integers.
 * - `Date("2025-01-02T15:04:05.058Z")` for ISO 8601 timestamps.
 * - `BinData("...")` for Base64-encoded binary data.
 * - `Counter(Int(10))` or `Counter(Long(100))` for Counter CRDTs.
 *
 * @param yson YSON formatted string.
 * @throws YorkieException with [YorkieException.Code.ErrInvalidArgument] when parsing fails.
 */
public fun parse(yson: String): YsonValue {
    val parser = YsonParser(yson)
    return try {
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            throw parser.error("Unexpected trailing characters")
        }
        value
    } catch (e: YorkieException) {
        throw e
    } catch (e: Exception) {
        throw YorkieException(
            YorkieException.Code.ErrInvalidArgument,
            "Failed to parse YSON: ${e.message}",
        )
    }
}

/**
 * [textToString] returns the concatenated character values of a [YsonValue.YsonText].
 */
public fun textToString(text: YsonValue.YsonText): String =
    text.nodes.joinToString(separator = "") { it.value }

/**
 * [treeToXML] returns an XML string representation of a [YsonValue.YsonTree].
 */
public fun treeToXML(tree: YsonValue.YsonTree): String = treeNodeToXml(tree.root)

private fun treeNodeToXml(node: YsonTreeNode): String {
    val attrs = node.attrs
        ?.entries
        ?.joinToString(separator = "") { (key, value) -> " $key=\"${escapeXml(value)}\"" }
        ?: ""

    if (node.type == "text" && node.value != null) {
        return "<${node.type}$attrs>${escapeXml(node.value)}</${node.type}>"
    }

    val children = node.children
    if (children.isNullOrEmpty()) {
        return "<${node.type}$attrs />"
    }

    val childrenXml = children.joinToString(separator = "") { treeNodeToXml(it) }
    return "<${node.type}$attrs>$childrenXml</${node.type}>"
}

private fun escapeXml(input: String): String = buildString(input.length) {
    for (ch in input) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}

/**
 * [YsonParser] is a hand-rolled recursive-descent parser for YSON.
 *
 * The parser understands all JSON productions plus YSON type constructors
 * (`Text`, `Tree`, `Int`, `Long`, `Date`, `BinData`, `Counter`). It produces a
 * typed [YsonValue] without relying on any third-party JSON library.
 */
internal class YsonParser(private val input: String) {
    private var pos: Int = 0

    fun parseValue(): YsonValue {
        skipWhitespace()
        if (atEnd()) {
            throw error("Unexpected end of input")
        }
        return when (val ch = input[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> YsonValue.YsonString(parseString())
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            'T' -> when {
                matches("Text") -> parseTextConstructor()
                matches("Tree") -> parseTreeConstructor()
                else -> throw error("Unknown keyword starting with 'T'")
            }
            'I' -> parseIntConstructor()
            'L' -> parseLongConstructor()
            'D' -> parseDateConstructor()
            'B' -> parseBinDataConstructor()
            'C' -> parseCounterConstructor()
            '-', in '0'..'9' -> parseNumber()
            else -> throw error("Unexpected character '$ch'")
        }
    }

    private fun parseObject(): YsonValue.YsonObject {
        expect('{')
        skipWhitespace()
        val entries = linkedMapOf<String, YsonValue>()
        if (peek() == '}') {
            pos++
            return YsonValue.YsonObject(entries)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') {
                throw error("Expected string key in object")
            }
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            entries[key] = value
            skipWhitespace()
            when (val ch = peekOrNull()) {
                ',' -> {
                    pos++
                    skipWhitespace()
                    if (peek() == '}') {
                        throw error("Trailing comma in object")
                    }
                }
                '}' -> {
                    pos++
                    return YsonValue.YsonObject(entries)
                }
                null -> throw error("Unterminated object")
                else -> throw error("Expected ',' or '}', got '$ch'")
            }
        }
    }

    private fun parseArray(): YsonValue.YsonArray {
        expect('[')
        skipWhitespace()
        val elements = mutableListOf<YsonValue>()
        if (peek() == ']') {
            pos++
            return YsonValue.YsonArray(elements)
        }
        while (true) {
            val value = parseValue()
            elements.add(value)
            skipWhitespace()
            when (val ch = peekOrNull()) {
                ',' -> {
                    pos++
                    skipWhitespace()
                    if (peek() == ']') {
                        throw error("Trailing comma in array")
                    }
                }
                ']' -> {
                    pos++
                    return YsonValue.YsonArray(elements)
                }
                null -> throw error("Unterminated array")
                else -> throw error("Expected ',' or ']', got '$ch'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (pos < input.length) {
            when (val ch = input[pos]) {
                '"' -> {
                    pos++
                    return builder.toString()
                }
                '\\' -> {
                    pos++
                    if (pos >= input.length) {
                        throw error("Unterminated escape sequence")
                    }
                    when (val esc = input[pos]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (pos + 4 >= input.length) {
                                throw error("Truncated unicode escape")
                            }
                            val hex = input.substring(pos + 1, pos + 5)
                            val codePoint = hex.toIntOrNull(16)
                                ?: throw error("Invalid unicode escape \\u$hex")
                            builder.append(codePoint.toChar())
                            pos += 4
                        }
                        else -> throw error("Invalid escape sequence \\$esc")
                    }
                    pos++
                }
                '\n', '\r' -> throw error("Unterminated string literal")
                else -> {
                    builder.append(ch)
                    pos++
                }
            }
        }
        throw error("Unterminated string literal")
    }

    private fun parseBoolean(): YsonValue.YsonBoolean {
        if (matches("true")) {
            pos += 4
            return YsonValue.YsonBoolean(true)
        }
        if (matches("false")) {
            pos += 5
            return YsonValue.YsonBoolean(false)
        }
        throw error("Invalid boolean literal")
    }

    private fun parseNull(): YsonValue {
        if (matches("null")) {
            pos += 4
            return YsonValue.YsonNull
        }
        throw error("Invalid null literal")
    }

    private fun parseNumber(): YsonValue.YsonNumber {
        val start = pos
        if (peek() == '-') pos++
        while (pos < input.length && input[pos].isDigit()) pos++
        if (pos < input.length && input[pos] == '.') {
            pos++
            while (pos < input.length && input[pos].isDigit()) pos++
        }
        if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
            pos++
            if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
            while (pos < input.length && input[pos].isDigit()) pos++
        }
        val literal = input.substring(start, pos)
        val parsed = literal.toDoubleOrNull()
            ?: throw error("Invalid number literal '$literal'")
        return YsonValue.YsonNumber(parsed)
    }

    private fun parseIntConstructor(): YsonValue.YsonInt {
        expectKeyword("Int")
        expect('(')
        val value = parseSignedInteger()
        expect(')')
        val parsed = value.toIntOrNull()
            ?: throw error("Int value out of range: $value")
        return YsonValue.YsonInt(parsed)
    }

    private fun parseLongConstructor(): YsonValue.YsonLong {
        expectKeyword("Long")
        expect('(')
        val value = parseSignedInteger()
        expect(')')
        val parsed = value.toLongOrNull()
            ?: throw error("Long value out of range: $value")
        return YsonValue.YsonLong(parsed)
    }

    private fun parseDateConstructor(): YsonValue.YsonDate {
        expectKeyword("Date")
        expect('(')
        skipWhitespace()
        if (peek() != '"') {
            throw error("Date expects a string literal")
        }
        val value = parseString()
        skipWhitespace()
        expect(')')
        return YsonValue.YsonDate(value)
    }

    private fun parseBinDataConstructor(): YsonValue.YsonBinData {
        expectKeyword("BinData")
        expect('(')
        skipWhitespace()
        if (peek() != '"') {
            throw error("BinData expects a string literal")
        }
        val value = parseString()
        skipWhitespace()
        expect(')')
        return YsonValue.YsonBinData(value)
    }

    private fun parseCounterConstructor(): YsonValue.YsonCounter {
        expectKeyword("Counter")
        expect('(')
        skipWhitespace()
        val inner = parseValue()
        if (inner !is YsonValue.YsonInt && inner !is YsonValue.YsonLong) {
            throw YorkieException(
                YorkieException.Code.ErrInvalidArgument,
                "Counter must contain Int or Long",
            )
        }
        skipWhitespace()
        expect(')')
        return YsonValue.YsonCounter(inner)
    }

    private fun parseTextConstructor(): YsonValue.YsonText {
        expectKeyword("Text")
        expect('(')
        skipWhitespace()
        if (peek() != '[') {
            throw error("Text expects an array")
        }
        val nodes = parseArray().elements.map { element ->
            parseTextNode(element)
        }
        skipWhitespace()
        expect(')')
        return YsonValue.YsonText(nodes)
    }

    private fun parseTreeConstructor(): YsonValue.YsonTree {
        expectKeyword("Tree")
        expect('(')
        skipWhitespace()
        val inner = parseValue()
        skipWhitespace()
        expect(')')
        return YsonValue.YsonTree(parseTreeNode(inner))
    }

    private fun parseTextNode(value: YsonValue): YsonTextNode {
        if (value !is YsonValue.YsonObject) {
            throw YorkieException(
                YorkieException.Code.ErrInvalidArgument,
                "invalid text node format",
            )
        }
        val rawVal = value.entries["val"]
        if (rawVal !is YsonValue.YsonString) {
            throw YorkieException(
                YorkieException.Code.ErrInvalidArgument,
                "invalid text node format",
            )
        }
        val attrs = value.entries["attrs"]
        val attrMap: Map<String, YsonValue>? = when (attrs) {
            null -> null
            is YsonValue.YsonObject -> attrs.entries
            else -> throw YorkieException(
                YorkieException.Code.ErrInvalidArgument,
                "invalid text node format",
            )
        }
        return YsonTextNode(rawVal.value, attrMap)
    }

    private fun parseTreeNode(value: YsonValue): YsonTreeNode {
        if (value !is YsonValue.YsonObject) {
            throw YorkieException(
                YorkieException.Code.ErrInvalidArgument,
                "invalid tree node format",
            )
        }
        val typeRaw = value.entries["type"]
        if (typeRaw !is YsonValue.YsonString) {
            throw YorkieException(
                YorkieException.Code.ErrInvalidArgument,
                "invalid tree node format",
            )
        }
        val type = typeRaw.value
        val nodeValueRaw = value.entries["value"]
        if (type == "text" && nodeValueRaw is YsonValue.YsonString) {
            return YsonTreeNode(type = type, value = nodeValueRaw.value)
        }

        val attrs = value.entries["attrs"]
        val attrMap: Map<String, String>? = when (attrs) {
            null -> null
            is YsonValue.YsonObject -> attrs.entries.mapValues { (_, attrVal) ->
                when (attrVal) {
                    is YsonValue.YsonString -> attrVal.value
                    else -> attrVal.toString()
                }
            }
            else -> null
        }

        val children = value.entries["children"]
        val childList: List<YsonTreeNode>? = when (children) {
            null -> null
            is YsonValue.YsonArray -> children.elements.map { parseTreeNode(it) }
            else -> null
        }

        return YsonTreeNode(type = type, attrs = attrMap, children = childList)
    }

    private fun parseSignedInteger(): String {
        skipWhitespace()
        val start = pos
        if (peek() == '-') pos++
        val digitStart = pos
        while (pos < input.length && input[pos].isDigit()) pos++
        if (pos == digitStart) {
            throw error("Expected integer literal")
        }
        val literal = input.substring(start, pos)
        skipWhitespace()
        return literal
    }

    private fun expectKeyword(keyword: String) {
        if (!matches(keyword)) {
            throw error("Expected '$keyword'")
        }
        pos += keyword.length
    }

    private fun matches(keyword: String): Boolean {
        if (pos + keyword.length > input.length) return false
        for (i in keyword.indices) {
            if (input[pos + i] != keyword[i]) return false
        }
        return true
    }

    private fun expect(ch: Char) {
        skipWhitespace()
        if (atEnd() || input[pos] != ch) {
            val found = if (atEnd()) "end of input" else "'${input[pos]}'"
            throw error("Expected '$ch', got $found")
        }
        pos++
    }

    private fun peek(): Char {
        if (atEnd()) throw error("Unexpected end of input")
        return input[pos]
    }

    private fun peekOrNull(): Char? = if (atEnd()) null else input[pos]

    fun skipWhitespace() {
        while (pos < input.length && input[pos].isWhitespace()) pos++
    }

    fun atEnd(): Boolean = pos >= input.length

    fun error(message: String): YorkieException = YorkieException(
        YorkieException.Code.ErrInvalidArgument,
        "Failed to parse YSON at position $pos: $message",
    )
}
