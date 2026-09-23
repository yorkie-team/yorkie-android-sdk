package dev.yorkie.document.yson

import dev.yorkie.util.YorkieException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins yorkie-js-sdk v0.7.19's sole behavioural change — `e56ab9a3` / yorkie-js-sdk#1335,
 * "Replace YSON regex preprocessor with a string-aware scanner" — as a regression contract on
 * Android, not as a source port.
 *
 * JS `YSON.parse` rewrites constructor literals (`Text([...])`, `Tree({...})`, ...) to plain JSON
 * via a regex preprocessor before handing the result to `JSON.parse`. That preprocessor had two
 * defects fixed by #1335: (1) a hard-coded three-level nesting ceiling for `Tree`/`Text` literals
 * — `doc > block > inline > text` is already depth four — and (2) counting `{`/`}`/`[`/`]` inside
 * string values as structure, so a document only parsed when the brackets inside its text
 * happened to balance, and a constructor-like substring inside a string got rewritten.
 *
 * [YsonParser] has neither defect: it is a single-stage hand-rolled recursive-descent parser over
 * the YSON grammar with no preprocess or rewrite step — nesting depth is bounded only by JVM
 * recursion, and its string-literal production consumes string contents verbatim, never
 * interpreting bracket characters inside a string as structure. Every case here was green against
 * the parser before this class was added (see the commit that introduced it); the class pins the
 * release's twelve-case behavioural contract so a future JS-literal port or parser refactor cannot
 * regress it silently, plus one "iOS cross-check pins" section carrying the review-round items from
 * the merged iOS squash `eb9d0b1e0c` (yorkie-ios-sdk PR #273) that apply to Android.
 */
class YsonParserStringContentTest {

    private fun parseText(yson: String): YsonValue.YsonText =
        (parse(yson) as YsonValue.YsonObject).entries["c"] as YsonValue.YsonText

    private fun parseTreeXml(yson: String): String {
        val tree = (parse(yson) as YsonValue.YsonObject).entries["c"] as YsonValue.YsonTree
        return treeToXML(tree)
    }

    // --- Deep nesting (regression: regex depth ceiling) ---

    @Test
    fun `parse should decode a Tree nested deeper than three levels`() {
        // given a Tree literal doc > block > inline > text — doc > block > inline > text is
        // already depth four, past the JS regex preprocessor's hard-coded three-level ceiling
        val yson = """
            {"c":Tree({"type":"doc","children":[{"type":"block","children":[
            {"type":"inline","children":[{"type":"text","value":"a"}]}]}]})}
        """.trimIndent()

        // when
        val xml = parseTreeXml(yson)

        // then
        assertEquals("<doc><block><inline><text>a</text></inline></block></doc>", xml)
    }

    @Test
    fun `parse should decode a Tree nested far past four levels`() {
        // given a Tree literal nested doc > l0 > ... > l7 > text, built the same way the JS
        // test builds its fixture: a loop from the innermost text node outward
        var node = """{"type":"text","value":"deep"}"""
        for (i in 7 downTo 0) {
            node = """{"type":"l$i","children":[$node]}"""
        }
        val yson = """{"c":Tree({"type":"doc","children":[$node]})}"""

        // when
        val xml = parseTreeXml(yson)

        // then
        assertTrue(xml.contains("<l0>"))
        assertTrue(xml.contains("<l7>"))
        assertTrue(xml.contains("<text>deep</text>"))
    }

    // --- Bracket characters in string values (regression: not string-aware) ---

    @Test
    fun `parse should decode a Text value with an unmatched closing bracket`() {
        val text = parseText("""{"c":Text([{"val":"a]b"}])}""")
        assertEquals("a]b", text.nodes[0].value)
    }

    @Test
    fun `parse should decode a Text value with an unmatched opening bracket`() {
        val text = parseText("""{"c":Text([{"val":"a[b"}])}""")
        assertEquals("a[b", text.nodes[0].value)
    }

    @Test
    fun `parse should decode a Tree value with an unmatched closing brace`() {
        val xml = parseTreeXml(
            """{"c":Tree({"type":"doc","children":[{"type":"text","value":"a}b"}]})}""",
        )
        assertTrue(xml.contains("a}b"))
    }

    @Test
    fun `parse should decode a Text value containing a closing paren`() {
        val text = parseText("""{"c":Text([{"val":"see f(x))"}])}""")
        assertEquals("see f(x))", text.nodes[0].value)
    }

    @Test
    fun `parse should decode a value with an escaped quote adjacent to a bracket`() {
        val text = parseText("""{"c":Text([{"val":"a\"]b"}])}""")
        assertEquals("a\"]b", text.nodes[0].value)
    }

    @Test
    fun `parse should not treat a constructor-like substring inside a string as a type`() {
        // then — no constructor rewrite: the value round-trips verbatim
        val text = parseText("""{"c":Text([{"val":"Int(42) and Tree(x)"}])}""")
        assertEquals("Int(42) and Tree(x)", text.nodes[0].value)
    }

    @Test
    fun `parse should decode a Text value ending with an escaped backslash`() {
        // given a val whose content is `a\` — an escaped backslash directly followed by the real
        // closing quote; a scanner that miscounts escape state would swallow the terminator
        val text = parseText("""{"c":Text([{"val":"a\\"}])}""")

        // then
        assertEquals("a\\", text.nodes[0].value)
    }

    // --- Text and Tree in the same root ---

    @Test
    fun `parse should decode a document holding both Text and Tree each with bracket content`() {
        val yson = """
            {"t":Text([{"val":"x]y"}]),"tr":Tree({"type":"doc","children":[
            {"type":"text","value":"p}q"}]})}
        """.trimIndent()

        // when
        val obj = parse(yson) as YsonValue.YsonObject

        // then
        val text = obj.entries["t"] as YsonValue.YsonText
        assertEquals("x]y", text.nodes[0].value)
        val tree = obj.entries["tr"] as YsonValue.YsonTree
        assertTrue(treeToXML(tree).contains("p}q"))
    }

    // --- Error Handling ---

    @Test
    fun `parse should throw on an unterminated string literal`() {
        val error = assertThrows(YorkieException::class.java) {
            parse("""{"c":Text([{"val":"a}])}""")
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, error.code)
    }

    @Test
    fun `parse should throw on unbalanced parentheses`() {
        val error = assertThrows(YorkieException::class.java) {
            parse("""{"c":Tree({"type":"doc"}""")
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, error.code)
    }

    @Test
    fun `parse should throw on a DedupCounter with the wrong argument count`() {
        // duplicates the pre-existing YsonParserTest arity case deliberately — this one carries
        // the js#1335 section, the old one stays untouched
        val error = assertThrows(YorkieException::class.java) {
            parse("""{"c":DedupCounter(Int(15))}""")
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, error.code)
    }

    // --- iOS cross-check pins (from squash eb9d0b1e0c) ---

    @Test
    fun `parse should decode DedupCounter registers containing a comma and paren`() {
        // parseString consumes the ',' and ')' inside the quoted registers argument verbatim —
        // this is why the JS scanner needed a string-aware splitTopLevelArgs
        val result = parse("""{"c":DedupCounter(Int(1),"a,b)c")}""") as YsonValue.YsonObject
        val dedup = result.entries["c"] as YsonValue.YsonDedupCounter
        assertEquals("a,b)c", dedup.registers)
    }

    @Test
    fun `parse should decode Int at both signed 32-bit endpoints`() {
        val max = parse("Int(${Int.MAX_VALUE})") as YsonValue.YsonInt
        val min = parse("Int(${Int.MIN_VALUE})") as YsonValue.YsonInt
        assertEquals(Int.MAX_VALUE, max.value)
        assertEquals(Int.MIN_VALUE, min.value)
    }

    @Test
    fun `parse should decode Long at both signed 64-bit endpoints`() {
        // the MAX half deliberately overlaps YsonParserTest's `Long at maximum boundary` case —
        // this pin records both endpoints together as the iOS review did
        val max = parse("Long(${Long.MAX_VALUE})") as YsonValue.YsonLong
        val min = parse("Long(${Long.MIN_VALUE})") as YsonValue.YsonLong
        assertEquals(Long.MAX_VALUE, max.value)
        assertEquals(Long.MIN_VALUE, min.value)
    }

    @Test
    fun `parse should throw when Int exceeds the signed 32-bit range`() {
        // the over-MAX half deliberately overlaps YsonParserTest's `Int value overflows` case —
        // this pin records the first value past each endpoint together as the iOS review did
        val overMax = (Int.MAX_VALUE.toLong() + 1).toString()
        val underMin = (Int.MIN_VALUE.toLong() - 1).toString()
        val errorOverMax = assertThrows(YorkieException::class.java) { parse("Int($overMax)") }
        val errorUnderMin = assertThrows(YorkieException::class.java) { parse("Int($underMin)") }
        assertEquals(YorkieException.Code.ErrInvalidArgument, errorOverMax.code)
        assertEquals(YorkieException.Code.ErrInvalidArgument, errorUnderMin.code)
    }

    @Test
    fun `parse should throw when Long exceeds the signed 64-bit range`() {
        // hardcoded literals — they overflow a Kotlin Long, so Long.MAX_VALUE + 1 cannot express
        // them; these are the first values past each signed 64-bit endpoint
        val errorOverMax = assertThrows(YorkieException::class.java) {
            parse("Long(9223372036854775808)")
        }
        val errorUnderMin = assertThrows(YorkieException::class.java) {
            parse("Long(-9223372036854775809)")
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, errorOverMax.code)
        assertEquals(YorkieException.Code.ErrInvalidArgument, errorUnderMin.code)
    }

    @Test
    fun `parse should throw on a non-integer Int argument`() {
        // divergence 2: Android keeps the strict integer grammar; JS v0.7.19 widened
        // postprocessValue to accept any typeof === 'number', and falls through non-numbers
        // to a plain object — Android rejects all three
        val errorFraction = assertThrows(YorkieException::class.java) { parse("Int(1.5)") }
        val errorBoolean = assertThrows(YorkieException::class.java) { parse("Int(true)") }
        val errorExponent = assertThrows(YorkieException::class.java) { parse("Int(1e10)") }
        assertEquals(YorkieException.Code.ErrInvalidArgument, errorFraction.code)
        assertEquals(YorkieException.Code.ErrInvalidArgument, errorBoolean.code)
        assertEquals(YorkieException.Code.ErrInvalidArgument, errorExponent.code)
    }

    @Test
    fun `parse should decode a Text value whose first character is a combining mark`() {
        // iOS grapheme-cluster scan issue: a Swift Character fused the opening quote with a
        // following combining mark; Kotlin String indexes by UTF-16 code unit, so the quote scan
        // is unaffected. U+0301 COMBINING ACUTE ACCENT (category Mn) first, then a surrogate-pair
        // emoji skin-tone modifier (U+1F3FB) as the second per-keystroke shape the iOS review named
        val combining = parseText("{\"c\":Text([{\"val\":\"́x\"}])}")
        assertEquals("́x", combining.nodes[0].value)

        val surrogatePair = parseText("{\"c\":Text([{\"val\":\"🏻x\"}])}")
        assertEquals("🏻x", surrogatePair.nodes[0].value)
    }
}
