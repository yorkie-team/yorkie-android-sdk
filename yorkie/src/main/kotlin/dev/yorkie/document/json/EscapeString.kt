package dev.yorkie.document.json

/**
 * Escapes the given string.
 */
internal fun escapeString(str: String): String {
    return str.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\b", "\\b")
        .replace("\t", "\\t")
        .replace("\u000c", "\\f")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
}
