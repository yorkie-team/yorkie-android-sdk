package dev.yorkie.document.presence

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Covers [PresenceValue], the presence wire codec (RTCOLLABPLATFORM-805, yorkie#2386 /
 * `docs/design/presence.md`). The golden vectors below were generated once with node
 * (`JSON.stringify` for the encoder, `JSON.parse` for the decoder) and are checked in as
 * constants: no JSON library or Android class is used as an oracle.
 */
class PresenceValueTest {

    private fun roundTrip(value: String) =
        PresenceValue.decodeFromWire(PresenceValue.encodeForWire(value))

    // --- U1/U8: encoder, golden `JSON.stringify` output ---------------------------

    @Test
    fun `encodes a bare string as its JSON string like JSON stringify`() {
        val golden = listOf(
            "moved" to "\"moved\"",
            "" to "\"\"",
            "a\"b" to "\"a\\\"b\"",
            "back\\slash" to "\"back\\\\slash\"",
            "tab\there" to "\"tab\\there\"",
            "nl\nx" to "\"nl\\nx\"",
            "\u0001\u001f" to "\"\\u0001\\u001f\"",
            "</script>" to "\"</script>\"",
            // U+2028/2029 and U+007F stay raw, as JSON.stringify leaves them
            "\u2028\u2029" to "\"\u2028\u2029\"",
            "\uD83D\uDE00" to "\"\uD83D\uDE00\"",
            "lone\uD800hi" to "\"lone\\ud800hi\"",
            "lone\uDC00" to "\"lone\\udc00\"",
            "/slash" to "\"/slash\"",
            "\u007F" to "\"\u007F\"",
            "\u00e9\ud55c" to "\"\u00e9\ud55c\"",
            "a,b" to "\"a,b\"",
            "1,1" to "\"1,1\"",
        )
        for ((value, expected) in golden) {
            assertEquals(expected, PresenceValue.encodeString(value), "encode of $value")
            // Every one of these is sent encoded (none is valid JSON that reads back as itself),
            // except text that happens to be valid non-string JSON.
            assertEquals(expected, PresenceValue.encodeForWire(value), "wire form of $value")
        }
    }

    // --- U2: valid non-string JSON passes through ---------------------------------

    @Test
    fun `passes valid non-string JSON through unchanged`() {
        for (value in listOf(
            "{\"x\":0,\"y\":0}", "[1,2]", "123", "-1.5e3", "0", "true", "false", "null",
            " {\"a\" : [1, 2, {\"b\": null}]} ", "\t[]\n", "{}", "1E+2",
        )) {
            assertEquals(value, PresenceValue.encodeForWire(value), value)
            assertEquals(value, PresenceValue.decodeFromWire(value), value)
        }
    }

    // --- U3/U4: string literals are encoded again so they round-trip --------------

    @Test
    fun `encodes a JSON string literal so it reads back with its quotes`() {
        assertEquals("\"\\\"x\\\"\"", PresenceValue.encodeForWire("\"x\""))
        assertEquals("\"x\"", roundTrip("\"x\""))
    }

    @Test
    fun `round-trips every string exactly`() {
        val rows = listOf(
            "a", "", "\"x\"", " \"x\" ", "\t\"x\"\n", "\r\n\"x\"  ", "\"", "\"\"", "\"a\"b",
            "123", "\"123\"", "true", "null", " null ", "{\"x\":0}", "{\"x\":", "[1,]", "01",
            "moved", "1,1", "a\"b", "back\\slash", "\u0001", "\u2028", "</script>",
            "\uD83D\uDE00", "lone\uD800", "lone\uDC00", "\uDC00\uD800", "\u00e9\ud55c",
            "\"\\u0041\"", "\"\\ud800\"", "\"unterminated", "\\", "\\\"",
        )
        for (row in rows) {
            assertEquals(row, roundTrip(row), "round trip of ${row.map { it.code }}")
        }
    }

    @Test
    fun `round-trips random strings with a fixed seed`() {
        val random = Random(805)
        val pool = "ab1 \"\\{}[]:,\t\n\r-.eE0\u2028\u00e9\uD83D\uDE00\uD800\uDC00nultrue/"
        val json = listOf(
            "null",
            "true",
            "12",
            "\"s\"",
            "[]",
            "{}",
            "[1,{\"k\":\"v\"}]",
            "\"a\\nb\"",
        )
        repeat(5000) {
            val value = if (random.nextBoolean()) {
                buildString {
                    repeat(
                        random.nextInt(0, 12),
                    ) { append(pool[random.nextInt(pool.length)]) }
                }
            } else {
                // valid JSON, optionally wrapped in whitespace: the dangerous neighbourhood
                val ws = listOf("", " ", "\t", "\n", "\r ")
                ws[random.nextInt(ws.size)] + json[random.nextInt(json.size)] +
                    ws[random.nextInt(ws.size)]
            }
            assertEquals(value, roundTrip(value), "round trip of ${value.map { it.code }}")
        }
    }

    // --- U5: decoder --------------------------------------------------------------

    @Test
    fun `decodes a JSON string literal to its content and anything else verbatim`() {
        // golden: JSON.parse result for strings, verbatim for the rest
        val golden = listOf(
            "\"a\"" to "a",
            "  \"a b\"\t" to "a b",
            "\"\\u00e9\\ud83d\\ude00\"" to "\u00e9\uD83D\uDE00",
            "\"\\ud800\"" to "\uD800",
            "\"\\n\\t\\\\\\/\"" to "\n\t\\/",
            "\"\"" to "",
            "a" to "a",
            "{\"x\":0}" to "{\"x\":0}",
            "123" to "123",
            "" to "",
            "\"unterminated" to "\"unterminated",
            "\"a\"x" to "\"a\"x",
            "\"a\nb\"" to "\"a\nb\"",
            "\"\\x\"" to "\"\\x\"",
            "\"\\u12\"" to "\"\\u12\"",
        )
        for ((wire, expected) in golden) {
            assertEquals(expected, PresenceValue.decodeFromWire(wire), wire)
        }
    }

    @Test
    fun `decodes null verbatim, unlike Go which reads an empty string`() {
        // Go DecodeValue("null") leaves "" (json.Unmarshal of null into a string is a no-op);
        // Android deliberately keeps the text so a stored null does not read back empty.
        assertEquals("null", PresenceValue.decodeFromWire("null"))
        assertEquals("null", PresenceValue.encodeForWire("null"))
        assertEquals("null", roundTrip("null"))
    }

    // --- U6: depth cap ------------------------------------------------------------

    @Test
    fun `treats nesting past 100 levels as not JSON and encodes it`() {
        val at100 = "[".repeat(100) + "]".repeat(100)
        val at101 = "[".repeat(101) + "]".repeat(101)
        assertTrue(PresenceValue.isJson(at100))
        assertEquals(at100, PresenceValue.encodeForWire(at100))
        assertFalse(PresenceValue.isJson(at101))
        assertEquals(PresenceValue.encodeString(at101), PresenceValue.encodeForWire(at101))
        assertEquals(at101, roundTrip(at101))

        // objects count the same way
        val obj100 = "{\"a\":".repeat(100) + "1" + "}".repeat(100)
        val obj101 = "{\"a\":".repeat(101) + "1" + "}".repeat(101)
        assertTrue(PresenceValue.isJson(obj100))
        assertFalse(PresenceValue.isJson(obj101))
    }

    @Test
    fun `does not count brackets inside strings toward the depth`() {
        val inString = "[\"" + "[".repeat(500) + "\"]"
        assertTrue(PresenceValue.isJson(inString))
        assertEquals(inString, PresenceValue.encodeForWire(inString))
        // siblings do not accumulate depth either
        val wide = "[" + (1..500).joinToString(",") { "[1]" } + "]"
        assertTrue(PresenceValue.isJson(wide))
        // a very deep value does not overflow the stack
        assertFalse(PresenceValue.isJson("[".repeat(200_000)))
    }

    // --- U7: strict RFC 8259 ------------------------------------------------------

    @Test
    fun `rejects what RFC 8259 rejects`() {
        for (bad in listOf(
            "", " ", "01", "-", "+1", ".5", "1.", "1e", "0x1", "NaN", "Infinity", "-Infinity",
            "[1,]", "{\"a\":1,}", "{a:1}", "{'a':1}", "'a'", "[1 2]", "{\"a\" 1}", "tru", "nul",
            "\"a", "a\"", "\"\\x\"", "\"\\u00g0\"", "\"\\u12\"", "\"a\nb\"", "\"a\tb\"",
            "\u00001", "\uFEFF1", "1 2", "[]]", "[[]", "{\"a\":}", "\"\uD800\"", "\"\uDC00\"",
            "\"\\u\uFF11\uFF11\uFF11\uFF11\"",
        )) {
            assertFalse(PresenceValue.isJson(bad), "should reject ${bad.map { it.code }}")
        }
        for (good in listOf(
            "0",
            "-0",
            "1e5",
            "1.5E-3",
            "\"\"",
            "\"\\u00e9\"",
            "\"\uD83D\uDE00\"",
        )) {
            assertTrue(PresenceValue.isJson(good), good)
        }
    }

    @Test
    fun `encodes text that is not valid JSON, empty and lone surrogates`() {
        assertEquals("\"\"", PresenceValue.encodeForWire(""))
        assertEquals("\"{\\\"x\\\":\"", PresenceValue.encodeForWire("{\"x\":"))
        assertEquals(
            "\"\uD800\"".replace("\uD800", "\\ud800"),
            PresenceValue.encodeForWire("\uD800"),
        )
        // a string literal holding a lone surrogate is not JSON on the wire (not valid UTF-8)
        assertEquals(
            PresenceValue.encodeString("\"\uD800\""),
            PresenceValue.encodeForWire("\"\uD800\""),
        )
    }
}
