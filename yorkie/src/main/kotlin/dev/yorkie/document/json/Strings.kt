package dev.yorkie.document.json

/**
 * Escapes string.
 */
fun escapeString(str: String): String {
    return str.map {
        when (it) {
            '\\' -> "\\" + it
            '\"' -> "\\\""
            '\'' -> "\\'"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\b' -> "\\b"
            '\t' -> "\\t"
            '\u000c' -> "\\f"
            '\u2028' -> "\\u2028"
            '\u2029' -> "\\u2029"
            else -> it
        }
    }.joinToString("")
}
