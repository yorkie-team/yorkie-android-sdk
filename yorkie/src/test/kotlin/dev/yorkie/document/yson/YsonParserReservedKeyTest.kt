package dev.yorkie.document.yson

import dev.yorkie.document.yson.YsonValue.YsonArray
import dev.yorkie.document.yson.YsonValue.YsonBoolean
import dev.yorkie.document.yson.YsonValue.YsonInt
import dev.yorkie.document.yson.YsonValue.YsonNumber
import dev.yorkie.document.yson.YsonValue.YsonObject
import dev.yorkie.document.yson.YsonValue.YsonString
import dev.yorkie.document.yson.YsonValue.YsonText
import dev.yorkie.document.yson.YsonValue.YsonTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "Reserved Marker Keys" contract of yorkie-js-sdk v0.7.28 (yorkie-js-sdk#1545) as a
 * regression contract on Android, not as a source port.
 *
 * JS `YSON.parse` rewrites constructor literals to plain JSON carrying the reserved markers
 * `__yson_type` / `__yson_data` / `__yson_registers`, and used to reinterpret ANY object holding
 * `__yson_type` as such a marker, so user data with those keys was silently turned into a typed
 * value. #1545 escapes user keys in the `__yson_` namespace before the rewrite and unescapes them
 * afterwards.
 *
 * [YsonParser] has no such rewrite step: constructors are recognised only by their leading
 * keyword (`Int`, `Text`, `Tree`, ...), and `parseObject` stores every key verbatim. There is
 * therefore nothing to escape and the bug has no Android analogue. Every case here was green
 * against the parser before this class was added (there is no red-before-fix phase); the class
 * pins the contract so a future marker-based rewrite or parser refactor cannot regress it
 * silently.
 *
 * Android differs from JS on purpose in cases 5 (representation) and 12 (no prototype semantics
 * on the JVM, sanity only); each of those tests says so.
 */
class YsonParserReservedKeyTest {

    private fun parseRoot(yson: String): Map<String, YsonValue> =
        (parse(yson) as YsonObject).entries

    private fun obj(vararg entries: Pair<String, YsonValue>) = YsonObject(linkedMapOf(*entries))

    private fun parseTreeRoot(yson: String): YsonTreeNode = (parseRoot(yson)["t"] as YsonTree).root

    private fun parseTextNodes(yson: String): List<YsonTextNode> =
        (parseRoot(yson)["t"] as YsonText).nodes

    // --- Marker-shaped user data stays plain data ---

    @Test
    fun `parse should not read a user key as a type marker`() {
        // given a user object that looks like a JS `Int` marker
        val yson = """{"x":{"__yson_type":"Int","__yson_data":7}}"""

        // when
        val result = parse(yson)

        // then it stays a plain object, not a YsonInt
        val expected = obj(
            "x" to obj("__yson_type" to YsonString("Int"), "__yson_data" to YsonNumber(7.0)),
        )
        assertEquals(expected, result)
    }

    @Test
    fun `parse should not read a user key as a DedupCounter marker`() {
        // given a user object that looks like a JS `DedupCounter` marker
        val yson = """
            {"x":{"__yson_type":"DedupCounter",
            "__yson_data":{"__yson_type":"Int","__yson_data":1},
            "__yson_registers":"aGk="}}
        """.trimIndent().replace("\n", "")

        // when
        val result = parse(yson)

        // then it stays a plain object, not a YsonDedupCounter
        val expected = obj(
            "x" to obj(
                "__yson_type" to YsonString("DedupCounter"),
                "__yson_data" to obj(
                    "__yson_type" to YsonString("Int"),
                    "__yson_data" to YsonNumber(1.0),
                ),
                "__yson_registers" to YsonString("aGk="),
            ),
        )
        assertEquals(expected, result)
    }

    @Test
    fun `parse should recognize a marker key however it is spelled`() {
        // given the same marker-shaped object as the first case, but with the keys written as
        // JSON unicode escapes. This is a raw string, so the parser receives the literal
        // backslash-u text (the Kotlin compiler does not decode it); \u005f is `_`.
        val yson = """{"x":{"\u005f\u005fyson_type":"Int","\u005f_yson_data":7}}"""
        assertTrue(yson.contains("\\u005f"))
        assertTrue(!yson.contains("__yson_type"))

        // when
        val result = parse(yson)

        // then parseString decodes the keys, and they are plain entries, not a YsonInt
        val expected = obj(
            "x" to obj("__yson_type" to YsonString("Int"), "__yson_data" to YsonNumber(7.0)),
        )
        assertEquals(expected, result)
    }

    @Test
    fun `parse should round-trip a key that collides with the escape prefix`() {
        // given keys shaped like JS's `__yson_esc_` escape prefix (Android has no escape step)
        val yson = """{"__yson_esc_x":1,"__yson_esc___yson_type":2}"""

        // when
        val result = parse(yson)

        // then keys are kept verbatim
        val expected = obj(
            "__yson_esc_x" to YsonNumber(1.0),
            "__yson_esc___yson_type" to YsonNumber(2.0),
        )
        assertEquals(expected, result)
    }

    @Test
    fun `parse should keep a reserved key that holds a constructor`() {
        // given a reserved key whose value is a constructor. Android difference: JS yields
        // `{type: 'Int', value: 3}`; Android yields a YsonInt (representation only).
        val yson = """{"__yson_data":Int(3)}"""

        // when
        val result = parse(yson)

        // then
        assertEquals(obj("__yson_data" to YsonInt(3)), result)
    }

    @Test
    fun `parse should leave a reserved name used as a string value alone`() {
        // given reserved names used as string values
        val yson = """{"v":"__yson_type","w":"__yson_esc_a"}"""

        // when
        val result = parse(yson)

        // then (weak pin: only catches gross regressions)
        val expected = obj("v" to YsonString("__yson_type"), "w" to YsonString("__yson_esc_a"))
        assertEquals(expected, result)
    }

    // --- Reserved keys in Text/Tree attributes ---

    @Test
    fun `parse should keep a reserved key in text node attributes`() {
        // given
        val yson = """{"t":Text([{"val":"a","attrs":{"__yson_type":"b"}}])}"""

        // when
        val nodes = parseTextNodes(yson)

        // then
        val expected = listOf(YsonTextNode("a", mapOf("__yson_type" to YsonString("b"))))
        assertEquals(expected, nodes)
    }

    @Test
    fun `parse should keep a reserved key in tree node attributes`() {
        // given
        val yson = """{"t":Tree({"type":"doc","attrs":{"__yson_type":"b"},"children":[]})}"""

        // when
        val root = parseTreeRoot(yson)

        // then tree attrs are Map<String, String>
        val expected = YsonTreeNode(
            type = "doc",
            attrs = mapOf("__yson_type" to "b"),
            children = emptyList(),
        )
        assertEquals(expected, root)
    }

    @Test
    fun `parse should keep a reserved key nested in a text attribute value`() {
        // given
        val yson = """{"t":Text([{"val":"a","attrs":{"k":{"__yson_type":"b"}}}])}"""

        // when
        val nodes = parseTextNodes(yson)

        // then the nested object is kept as a plain object
        val expected = listOf(
            YsonTextNode("a", mapOf("k" to obj("__yson_type" to YsonString("b")))),
        )
        assertEquals(expected, nodes)
    }

    @Test
    fun `parse should keep a reserved key nested in a tree attribute value`() {
        // given a non-string tree attribute value holding a reserved key (JS case 10)
        val yson = """
            {"t":Tree({"type":"doc","attrs":{"k":[{"__yson_type":"b"}]},"children":[]})}
        """.trimIndent()

        // when
        val root = parseTreeRoot(yson)

        // then the lossless value keeps the typed array; the legacy string view is empty
        val expected = obj("k" to YsonArray(listOf(obj("__yson_type" to YsonString("b")))))
        assertEquals(expected, root.rawAttrs)
        assertEquals(emptyMap<String, String>(), root.attrs)
    }

    @Test
    fun `parse should keep array-valued text attributes`() {
        // given array-valued text attrs (JS case 11): JS keeps the array
        val yson = """{"t":Text([{"val":"a","attrs":[{"__yson_type":"b"}]}])}"""

        // when
        val nodes = parseTextNodes(yson)

        // then
        assertEquals(
            YsonArray(listOf(obj("__yson_type" to YsonString("b")))),
            nodes.single().rawAttrs,
        )
        assertNull(nodes.single().attrs)
    }

    // --- __proto__ (sanity only) ---

    @Test
    fun `parse should keep a __proto__ key as an ordinary entry of an object`() {
        // given. Sanity only: the JVM has no prototype semantics (JS case pins an own property).
        val yson = """{"x":{"__proto__":{"polluted":true}}}"""

        // when
        val x = parseRoot(yson)["x"] as YsonObject

        // then
        assertTrue(x.entries.containsKey("__proto__"))
        assertEquals(obj("__proto__" to obj("polluted" to YsonBoolean(true))), x)
    }

    @Test
    fun `parse should keep a __proto__ attribute as an ordinary tree attribute`() {
        // given. Sanity only, see above.
        val yson = """
            {"t":Tree({"type":"doc","attrs":{"__proto__":{"polluted":true}},"children":[]})}
        """.trimIndent()

        // when
        val root = parseTreeRoot(yson)

        // then the key is kept as an ordinary attribute with its typed value (JS case 13)
        assertEquals(
            obj("__proto__" to obj("polluted" to YsonBoolean(true))),
            root.rawAttrs,
        )
        assertEquals(emptyMap<String, String>(), root.attrs)
    }
}
