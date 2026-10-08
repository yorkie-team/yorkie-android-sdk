package dev.yorkie.document.crdt

import dev.yorkie.OpCode.RhtCode
import dev.yorkie.Step
import dev.yorkie.TestCase
import dev.yorkie.TestOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.TIME_TICKET_SIZE
import dev.yorkie.issueTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class RhtTest {
    private lateinit var target: Rht

    @Before
    fun setUp() {
        target = Rht()
    }

    @Test
    fun `should handle set operations`() {
        assertTrue(target.toTestString().isEmpty())

        target.set(TEST_KEY, TEST_VALUE, TimeTicket.InitialTimeTicket)
        assertEquals("$TEST_KEY:$TEST_VALUE", target.toTestString())
    }

    @Test
    fun `should handle get operations`() {
        target.set(TEST_KEY, TEST_VALUE, TimeTicket.InitialTimeTicket)
        assertEquals(TEST_VALUE, target[TEST_KEY])
        assertNull(target[NON_EXISTING_KEY])
    }

    @Test
    fun `should handle has operations`() {
        target.set(TEST_KEY, TEST_VALUE, TimeTicket.InitialTimeTicket)
        assertTrue(target.has(TEST_KEY))
        assertFalse(target.has(NON_EXISTING_KEY))
    }

    @Test
    fun `verify nodeKeyValueMap is returned correctly`() {
        val testData = mapOf("key0" to "value0", "key1" to "value1", "key2" to "value2")
        testData.entries.forEach {
            target.set(it.key, it.value, TimeTicket.InitialTimeTicket)
        }

        target.nodeKeyValueMap.entries.forEachIndexed { index, obj ->
            assertEquals("key$index", obj.key)
            assertEquals("value$index", obj.value)
        }
    }

    @Test
    fun `should handle remove`() {
        target.set(TEST_KEY, TEST_VALUE, TimeTicket.InitialTimeTicket)
        assertEquals(TEST_VALUE, target[TEST_KEY])
        assertEquals(1, target.size)

        target.remove(TEST_KEY, TimeTicket.MaxTimeTicket)
        assertFalse(target.has(TEST_KEY))
        assertTrue(target.isEmpty())
    }

    @Test
    fun `should handle marshal`() {
        val tests = listOf(
            TestCase(
                "1. empty hash table",
                Step(TestOperation(RhtCode.NoOp, "", ""), "{}").toList(),
            ),
            TestCase(
                "2. only one element",
                Step(
                    TestOperation(RhtCode.Set, "hello\\\\\\\\\\\\t", "world\"\\f\\b"),
                    """{"hello\\\\\\\\\\\\t":"world\"\\f\\b"}""",
                ).toList(),
            ),
            TestCase(
                "3. non-empty hash table",
                Step(
                    TestOperation(RhtCode.Set, "hi", "test\\r"),
                    """{"hello\\\\\\\\\\\\t":"world\"\\f\\b","hi":"test\\r"}""",
                ).toList(),
            ),
        )
        tests.forEach { test ->
            test.steps.forEach { (op, expectedJson, _) ->
                if (op.code == RhtCode.Set) {
                    target.set(op.key, op.value, issueTime())
                }
                assertEquals(expectedJson, target.toJson())
            }
        }
    }

    @Test
    fun `should handle set multiple times`() {
        val tests = listOf(
            TestCase(
                "1. set elements",
                listOf(
                    Step(
                        TestOperation(RhtCode.Set, "key1", "value1"),
                        """{"key1":"value1"}""",
                        1,
                    ),
                    Step(
                        TestOperation(RhtCode.Set, "key2", "value2"),
                        """{"key1":"value1","key2":"value2"}""",
                        2,
                    ),
                ),
            ),
            TestCase(
                "2. change elements",
                listOf(
                    Step(
                        TestOperation(RhtCode.Set, "key1", "value2"),
                        """{"key1":"value2","key2":"value2"}""",
                        2,
                    ),
                    Step(
                        TestOperation(RhtCode.Set, "key2", "value1"),
                        """{"key1":"value2","key2":"value1"}""",
                        2,
                    ),
                ),
            ),
        )
        tests.forEach { test ->
            test.steps.forEach { (op, expectedJson, expectedSize) ->
                if (op.code == RhtCode.Set) {
                    target.set(op.key, op.value, issueTime())
                }
                assertEquals(expectedJson, target.toJson())
                assertEquals(expectedSize, target.size)
            }
        }
    }

    @Test
    fun `should handle remove multiple times`() {
        val tests = listOf(
            TestCase(
                "1. set elements",
                listOf(
                    Step(
                        TestOperation(RhtCode.Set, "key1", "value1"),
                        """{"key1":"value1"}""",
                        1,
                    ),
                    Step(
                        TestOperation(RhtCode.Set, "key2", "value2"),
                        """{"key1":"value1","key2":"value2"}""",
                        2,
                    ),
                ),
            ),
            TestCase(
                "2. remove element",
                Step(
                    TestOperation(RhtCode.Remove, "key1", "value1"),
                    """{"key2":"value2"}""",
                    1,
                ).toList(),
            ),
            TestCase(
                "3. set after remove",
                Step(
                    TestOperation(RhtCode.Set, "key1", "value11"),
                    """{"key1":"value11","key2":"value2"}""",
                    2,
                ).toList(),
            ),
            TestCase(
                "4. remove element",
                listOf(
                    Step(
                        TestOperation(RhtCode.Set, "key2", "value22"),
                        """{"key1":"value11","key2":"value22"}""",
                        2,
                    ),
                    Step(
                        TestOperation(RhtCode.Remove, "key1", "value11"),
                        """{"key2":"value22"}""",
                        1,
                    ),
                ),
            ),
            TestCase(
                "5. remove element again",
                Step(
                    TestOperation(RhtCode.Remove, "key1", "value11"),
                    """{"key2":"value22"}""",
                    1,
                ).toList(),
            ),
            TestCase(
                "6. remove element(cleared)",
                Step(
                    TestOperation(RhtCode.Remove, "key2", "value22"),
                    """{}""",
                    0,
                ).toList(),
            ),
            TestCase(
                "7. remove not exist key",
                Step(
                    TestOperation(RhtCode.Remove, "not-exist-key", ""),
                    """{}""",
                    0,
                ).toList(),
            ),
        )

        tests.forEach { test ->
            test.steps.forEach { (op, expectedJson, expectedSize) ->
                when (op.code) {
                    RhtCode.Set -> target.set(op.key, op.value, issueTime())
                    RhtCode.Remove -> target.remove(op.key, issueTime())
                }
                assertEquals(expectedJson, target.toJson())
                assertEquals(expectedSize, target.size)
            }
        }
    }

    @Test
    fun `should deepcopy correctly`() {
        target.set("key1", "value1", issueTime())
        target.remove("key2", issueTime())

        val copiedRht = target.deepCopy()
        assertEquals(target.toJson(), copiedRht.toJson())
        assertEquals(target.size, copiedRht.size)
    }

    // RhtNode.dataSize charges UTF-8 bytes of the logical value, times two
    // — not UTF-16 code units, and not the raw (possibly quoted-JSON)
    // stored text. Ported from JS attr_representation_test.ts "charges the
    // logical value in UTF-8 bytes, matching the server" (the
    // typed-attribute/non-string cases are N/A on Android, which has no
    // attribute parse).
    @Test
    fun `dataSize charges UTF-8 bytes of the logical value times two`() {
        val at = TimeTicket.InitialTimeTicket

        // ASCII key + ASCII value: (3 + 3) * 2 = 12 (data only; meta is a
        // fixed TIME_TICKET_SIZE checked separately below).
        assertEquals(12, RhtNode("red", "red", at, false).dataSize.data)
        // bold="true": (4 + 4) * 2 = 16 — matches the Go/JS reference figure
        // cited in #2003/#1365 for this exact attribute.
        assertEquals(16, RhtNode("bold", "true", at, false).dataSize.data)
        // color="red": (5 + 3) * 2 = 16.
        assertEquals(16, RhtNode("color", "red", at, false).dataSize.data)
        // color="빨강": each character in "빨강" is 3 UTF-8 bytes, so "빨강"
        // is 6 bytes: (5 + 6) * 2 = 22 — the UTF-16 count would have given
        // (5 + 2) * 2 = 14, the reversed-sign gap #1365 closes.
        assertEquals(22, RhtNode("color", "빨강", at, false).dataSize.data)
        // A value that is itself a well-formed JSON string literal sizes
        // by its UNWRAPPED logical value: key "k" (1 byte) + logical
        // value "1" (1 byte) = (1 + 1) * 2 = 4, not (1 + 3) * 2 = 8 for the
        // quoted `"1"` (3 raw chars).
        assertEquals(4, RhtNode("k", "\"1\"", at, false).dataSize.data)
        // An unquoted, non-JSON-string value that merely LOOKS quoted at one
        // end only (malformed) passes through raw: `"abc` (4 raw chars) +
        // key "k" (1 byte) = (1 + 4) * 2 = 10.
        assertEquals(10, RhtNode("k", "\"abc", at, false).dataSize.data)
        assertEquals(TIME_TICKET_SIZE, RhtNode("k", "v", at, false).dataSize.meta)
    }

    // logicalValue must match JSON.parse on \uXXXX escapes and surrounding
    // whitespace, and fall back to the raw stored value (not a
    // partially-unescaped one) on an escape JSON.parse would reject.
    @Test
    fun `dataSize sizes by JSON-parse-equivalent logical value for escapes and whitespace`() {
        val at = TimeTicket.InitialTimeTicket

        // JSON.parse("\"\\u00e9\"") === "é" (1 char, 2 UTF-8 bytes): key "k"
        // (1 byte) + logical value "é" (2 bytes) = (1 + 2) * 2 = 6, not
        // (1 + 6) * 2 = 14 for the raw 6-char escape sequence `\u00e9`.
        assertEquals(6, RhtNode("k", "\"\\u00e9\"", at, false).dataSize.data)
        // JSON.parse tolerates surrounding whitespace: ' "ab" ' === "ab":
        // (1 + 2) * 2 = 6, not the 6-char raw ' "ab" ' sized as (1 + 6) * 2 = 14.
        assertEquals(6, RhtNode("k", " \"ab\" ", at, false).dataSize.data)
        // An escape JSON.parse rejects (`\x` is not a valid JSON escape)
        // falls back to the RAW stored value `"\x"` (4 chars), not a
        // partially-unescaped "x" (1 char): (1 + 4) * 2 = 10.
        assertEquals(10, RhtNode("k", "\"\\x\"", at, false).dataSize.data)
    }

    // A 28-value corpus computed against node v26 running JS v0.7.23's
    // verbatim logicalValue (JSON.parse + typeof check) and
    // TextEncoder. Covers: whitespace that JSON.parse tolerates (plain
    // space/CR/LF) vs whitespace it does not (NBSP, EM SPACE); a raw
    // (unescaped) control char inside the quotes, which JSON.parse
    // rejects; a \uXXXX escape that is short, has a non-hex digit, or
    // carries a sign (all invalid, JSON.parse requires exactly four hex
    // digits); a lone surrogate, which JSON.parse accepts but TextEncoder
    // re-encodes as U+FFFD (3 bytes) rather than the JVM's 1-byte '?'; and
    // a valid surrogate pair, which both platforms encode the same way.
    @Test
    fun `dataSize matches the JS reference for escape, whitespace and UTF-8 width edge cases`() {
        val at = TimeTicket.InitialTimeTicket
        val cases = listOf(
            "\"\\u00e9\"" to 6,
            " \"ab\" " to 6,
            "\"a\tb\"" to 12,
            "\u00a0\"ab\"\u00a0" to 18,
            "\"\\u-041\"" to 18,
            "\"\\u+041\"" to 18,
            "\"\\ud83d\\ude00\"" to 10,
            "\"\\ud800\"" to 8,
            "\"\\u0041\"" to 4,
            "\"\\x\"" to 10,
            "1" to 4,
            "\"1\"" to 4,
            "\"a\\\"b\"" to 8,
            "\"" to 4,
            "\"\\u004\"" to 16,
            "\"\\u004G\"" to 18,
            "\n\"ab\"\n" to 6,
            " \"ab\" x" to 16,
            "\"\\/\"" to 4,
            "\"\u2028\"" to 8,
            "\u2003\"ab\"" to 16,
            "\"\\u00E9\"" to 6,
            "\"a\u0001b\"" to 12,
            "\t\"ab\"\r" to 6,
            "\u007f" to 4,
            "\u0080" to 6,
            "\u07ff" to 6,
            "\u0800" to 8,
        )
        cases.forEach { (stored, expected) ->
            assertEquals(
                expected,
                RhtNode("k", stored, at, false).dataSize.data,
                "mismatch for ${stored.map { "%04x".format(it.code) }}",
            )
        }
    }

    // Rht.set reports installed/revived/superseded, or empty on LWW loss,
    // instead of the legacy RhtSetResult(prev, new) read by the caller
    // after the fact.
    @Test
    fun `set reports installed, revived, superseded, or empty for the four LWW cases`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

        // 1) prev == null: a plain install, nothing revived or superseded.
        val installOnly = target.set("key", "v1", tick(1))
        assertEquals("v1", installOnly.installed?.value)
        assertNull(installOnly.revived)
        assertNull(installOnly.superseded)

        // 2) prev is LIVE: superseded carries the SAME live object (no
        // fresh tombstoned copy).
        val installed1 = installOnly.installed
        val supersede = target.set("key", "v2", tick(2))
        assertEquals("v2", supersede.installed?.value)
        kotlin.test.assertSame(installed1, supersede.superseded)
        assertNull(supersede.revived)
        assertFalse(requireNotNull(supersede.superseded).isRemoved)

        // 3) prev is REMOVED: revived carries the SAME removed object.
        target.remove("key", tick(3))
        val removed = target.getNodeMapByKey()["key"]
        val revive = target.set("key", "v3", tick(4))
        assertEquals("v3", revive.installed?.value)
        kotlin.test.assertSame(removed, revive.revived)
        assertNull(revive.superseded)

        // 4) write loses LWW (an older ticket arrives after a newer one):
        // installed/revived/superseded all null — nothing changed.
        val lwwLoss = target.set("key", "stale", tick(1))
        assertNull(lwwLoss.installed)
        assertNull(lwwLoss.revived)
        assertNull(lwwLoss.superseded)
        assertEquals("v3", target["key"])
    }

    // A write that loses LWW against an ALREADY-REMOVED predecessor (not a
    // live one, case 4 above) must also report empty and must leave the
    // existing tombstone completely untouched -- a regression here would
    // either spuriously revive the tombstone or replace it with a node
    // carrying the stale value.
    @Test
    fun `set reports empty and leaves the tombstone untouched when losing LWW against it`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

        target.set("key", "v1", tick(1))
        target.remove("key", tick(3))
        val tombstoneBefore = target.getNodeMapByKey().getValue("key")

        val lwwLoss = target.set("key", "stale", tick(2))

        assertNull(lwwLoss.installed)
        assertNull(lwwLoss.revived)
        assertNull(lwwLoss.superseded)
        kotlin.test.assertSame(tombstoneBefore, target.getNodeMapByKey().getValue("key"))
        assertEquals("v1", tombstoneBefore.value)
        assertTrue(tombstoneBefore.isRemoved)
    }

    private fun Rht.toTestString(): String {
        return nodeKeyValueMap.entries.joinToString("") { "${it.key}:${it.value}" }
    }

    companion object {
        private const val TEST_KEY = "test key"
        private const val TEST_VALUE = "test value"
        private const val NON_EXISTING_KEY = "non-existing test key"
    }
}
