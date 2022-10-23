package dev.yorkie.json

import dev.yorkie.document.json.escapeString
import org.junit.Assert.assertEquals
import org.junit.Test

class StringEscapingTest {

    @Test
    fun `should escape a string with case1`() {
        val target = "1\\2\"3'4\n5\r6\t7\b8\u000C9\u20280\u2029"
        val escaped = "1\\\\2\\\"3\\'4\\n5\\r6\\t7\\b8\\f9\\u20280\\u2029"
        assertEquals(escaped, escapeString(target))
    }

    @Test
    fun `should escape a string with case2`() {
        val target = "\\n"
        val escaped = "\\\\n"
        assertEquals(escaped, escapeString(target))
    }

    @Test
    fun `should escape a string with case3`() {
        val target = "\\\\\\t"
        val escaped = "\\\\\\\\\\\\t"
        assertEquals(escaped, escapeString(target))
    }

    @Test
    fun `should escape a string with case4`() {
        val target = "\\\\\\\t"
        val escaped = "\\\\\\\\\\\\\\t"
        assertEquals(escaped, escapeString(target))
    }

    @Test
    fun `should escape a string with case5`() {
        val target = "\\u000C"
        val escaped = "\\\\u000C"
        assertEquals(escaped, escapeString(target))
    }

    @Test
    fun `should escape a string with case6`() {
        val target = "\u000C"
        val escaped = "\\f"
        assertEquals(escaped, escapeString(target))
    }
}
