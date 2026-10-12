package dev.yorkie.document.yson

import dev.yorkie.util.YorkieException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins yorkie-js-sdk `f4fd12d0` (v0.7.22, yorkie-js-sdk#1353, "Reject malformed YSON constructor
 * argument lists") as a regression contract on Android, not as a source port.
 *
 * JS `preprocessYSON` interpolated the raw text between a constructor's parentheses into a
 * `__yson_type` marker object unchecked, so a second argument could become a sibling key
 * (`Int(42,"__yson_type":"Long")` parsed as a `Long`) and a stray `}` could close the marker
 * early (`Int(1},"y":{"a":2)` invented a `y` key). The fix adds a string-aware scanner and a
 * bracket-stack argument splitter with arity checks.
 *
 * [YsonParser] has neither defect: it is a single-stage hand-rolled recursive-descent parser
 * with no `preprocessYSON` rewrite step and no `__yson_type` marker
 * object -- constructors are recognised and their arguments consumed directly by the grammar
 * (`parseIntConstructor`, `parseDedupCounterConstructor`, `parseTextConstructor`, ...), so every
 * malformed argument list fails the grammar with `YorkieException(ErrInvalidArgument)` regardless
 * of this release. This class pins the 24-case JS contract (20 rejection inputs + 4 acceptance /
 * shape assertions) so a future JS-literal port or parser refactor cannot regress it silently.
 * `git diff --stat f6037bbe -- yorkie/src/main/kotlin/dev/yorkie/document/yson` is empty for this
 * commit (RED not constructible -- structurally absent).
 *
 * Error MESSAGE texts are not a contract (unchanged): JS and
 * iOS now name the constructor in the message; Android reports the grammar failure with a
 * "Failed to parse YSON at position N:" prefix. Every case here asserts type ([YorkieException])
 * and [YorkieException.Code.ErrInvalidArgument] only, never the message text.
 *
 * Known leniency asymmetry, not pinned here: JS `postprocessValue` falls through to a plain
 * `{__yson_type, __yson_data}` object where Android's grammar rejects -- a bare number inside a
 * `Counter`/`Date`/`BinData` (`Counter(5)`, `Date(1)`, `BinData(1)`), a string value in a
 * `DedupCounter` (`DedupCounter("5","x")`), and non-integer or out-of-range `Int` arguments
 * (`Int(1e3)`, `Int(2147483648)`, `Int(1.5)`; the strict integer grammar is kept, matching
 * iOS). The asymmetry runs one way only: Android can reject what JS accepts, never accept what JS
 * rejects, so the 24 rejection/acceptance cases below hold on both sides (source-read, parity
 * unverified).
 *
 * Sibling of [YsonParserStringContentTest] (`YsonParserTest` already has 74 cases; left alone).
 * Working draft executed as `YsonConstructorArgsProbeTest` (24/24 GREEN at `f6037bbe`); ported
 * verbatim. Parity unverified -- JS not executed this session (source-read of `yson_test.ts` /
 * `parser.ts` @ v0.7.22); the Android side (this class) was executed.
 */
class YsonParserConstructorArgsTest {
    private fun rejects(input: String) {
        val e = assertThrows(YorkieException::class.java) { parse(input) }
        assertEquals(YorkieException.Code.ErrInvalidArgument, e.code)
    }

    @Test
    fun `extra argument changing the type`() = rejects("""{"v":Int(42,"__yson_type":"Long")}""")

    @Test
    fun `extra argument dropped`() = rejects("""{"v":Date("x","junk":1)}""")

    @Test
    fun `unbalanced brace inventing a parent key`() = rejects("""{"v":Int(1},"y":{"a":2)}""")

    @Test
    fun `unbalanced bracket in an argument`() =
        rejects("""{"v":Text([{"val":"a"}]],"y":[{"a":2)}""")

    @Test
    fun `mismatched bracket kinds`() = rejects("""{"v":Int([1})}""")

    @Test
    fun `missing argument Int`() = rejects("""{"v":Int()}""")

    @Test
    fun `missing argument Date`() = rejects("""{"v":Date()}""")

    @Test
    fun `missing argument Text`() = rejects("""{"v":Text()}""")

    @Test
    fun `two arguments`() = rejects("""{"v":Int(1,2)}""")

    @Test
    fun `Counter with an extra argument`() = rejects("""{"v":Counter(Int(1),"z")}""")

    @Test
    fun `extra argument inside nested constructor`() = rejects("""{"v":Counter(Int(1,2))}""")

    @Test
    fun `argument list of only a comma`() = rejects("""{"v":Int(,)}""")

    @Test
    fun `unterminated string in an argument`() = rejects("""{"v":Date("x)}""")

    @Test
    fun `DedupCounter tail escaping the marker`() =
        rejects("""{"v":DedupCounter(Int(1),"x"},"y":{"a":1)}""")

    @Test
    fun `DedupCounter with one argument`() = rejects("""{"v":DedupCounter(Int(1))}""")

    @Test
    fun `DedupCounter with three arguments`() = rejects("""{"v":DedupCounter(Int(1),"a","b")}""")

    @Test
    fun `DedupCounter registers not a string`() = rejects("""{"v":DedupCounter(Int(1),Int(2))}""")

    @Test
    fun `DedupCounter registers left unclosed`() = rejects("""{"v":DedupCounter(Int(1),"a\")}""")

    @Test
    fun `DedupCounter with no value argument`() = rejects("""{"v":DedupCounter(,"aGVsbG8=")}""")

    @Test
    fun `DedupCounter with text after its registers`() =
        rejects("""{"v":DedupCounter(Int(1),"a"b)}""")

    @Test
    fun `does not invent a key`() {
        for (input in listOf(
            """{"v":Int(1},"y":{"a":2)}""",
            """{"v":DedupCounter(Int(1),"x"},"y":{"a":1)}""",
            """{"v":Text([{"val":"a"}]],"y":[{"a":2)}""",
        )) {
            val parsed = runCatching { parse(input) }.getOrNull() ?: continue
            assertEquals(setOf("v"), (parsed as YsonValue.YsonObject).entries.keys)
        }
    }

    @Test
    fun `keeps accepting well-formed constructors`() {
        assertEquals(
            YsonValue.YsonInt(42),
            (parse("""{"v":Int( 42 )}""") as YsonValue.YsonObject).entries["v"],
        )
        assertEquals(
            YsonValue.YsonCounter(YsonValue.YsonInt(10)),
            (parse("""{"c":Counter(Int(10))}""") as YsonValue.YsonObject).entries["c"],
        )
        assertEquals(
            "ab",
            textToString(
                (parse("""{"t":Text([{"val":"a"},{"val":"b"}])}""") as YsonValue.YsonObject)
                    .entries["t"] as YsonValue.YsonText,
            ),
        )
        assertEquals(
            "a,b",
            textToString(
                (parse("""{"t":Text([{"val":"a,b"}])}""") as YsonValue.YsonObject)
                    .entries["t"] as YsonValue.YsonText,
            ),
        )
        assertEquals(
            "a)b(c",
            textToString(
                (parse("""{"t":Text([{"val":"a)b(c"}])}""") as YsonValue.YsonObject)
                    .entries["t"] as YsonValue.YsonText,
            ),
        )
    }

    @Test
    fun `keeps accepting a well-formed DedupCounter`() {
        val v = (parse("""{"c":DedupCounter(Int(15),"aGVsbG8=")}""") as YsonValue.YsonObject)
            .entries["c"] as YsonValue.YsonDedupCounter
        assertEquals(YsonValue.YsonInt(15), v.value)
        assertEquals("aGVsbG8=", v.registers)
    }

    @Test
    fun `does not treat constructor-like text in a string as a call site`() {
        for (v in listOf(
            """Int(42,\"__yson_type\":\"Long\")""",
            """Int(1},\"y\":{\"a\":2)""",
            """DedupCounter(Int(1),\"x\"},\"y\":{\"a\":1)""",
        )) {
            val parsed = parse("""{"t":Text([{"val":"$v"}])}""") as YsonValue.YsonObject
            assertEquals(
                v.replace("\\\"", "\""),
                textToString(parsed.entries["t"] as YsonValue.YsonText),
            )
        }
    }
}
