package dev.yorkie.document.yson

import dev.yorkie.document.yson.YsonValue.YsonArray
import dev.yorkie.document.yson.YsonValue.YsonInt
import dev.yorkie.document.yson.YsonValue.YsonNumber
import dev.yorkie.document.yson.YsonValue.YsonObject
import dev.yorkie.document.yson.YsonValue.YsonString
import dev.yorkie.document.yson.YsonValue.YsonText
import dev.yorkie.document.yson.YsonValue.YsonTree
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins yorkie-js-sdk `JSON.parse` semantics for a duplicated object key: the LAST value wins.
 * `parseObject` writes into a [LinkedHashMap], which replaces the value and keeps the position
 * of the first insertion, same as a JS object for non-integer keys. (JS orders integer-like keys
 * ascending, so key order is only asserted for non-integer keys here.) Already at parity, so
 * this class is a guard against a future parser rewrite, not a bug fix.
 */
class YsonParserDuplicateKeyTest {

    private fun parseRoot(yson: String): Map<String, YsonValue> =
        (parse(yson) as YsonObject).entries

    private fun num(value: Int) = YsonNumber(value.toDouble())

    @Test
    fun `parse should keep the last value of a repeated key`() {
        // given
        val yson = """{"k":1,"k":2,"k":3}"""

        // when
        val root = parseRoot(yson)

        // then
        assertEquals(mapOf("k" to num(3)), root)
    }

    @Test
    fun `parse should keep the position of the first occurrence`() {
        // given non-integer keys only
        val yson = """{"a":1,"b":2,"a":3}"""

        // when
        val root = parseRoot(yson)

        // then
        assertEquals(listOf("a", "b"), root.keys.toList())
        assertEquals(num(3), root["a"])
    }

    @Test
    fun `parse should keep the last value of a repeated key at every depth`() {
        // given
        val yson = """{"a":{"k":1,"k":2},"b":[{"k":1,"k":2}]}"""

        // when
        val root = parseRoot(yson)

        // then
        assertEquals(YsonObject(mapOf("k" to num(2))), root["a"])
        assertEquals(YsonArray(listOf(YsonObject(mapOf("k" to num(2))))), root["b"])
    }

    @Test
    fun `parse should replace a repeated key even when the value type changes`() {
        // given
        val yson = """{"k":{"x":1},"k":Int(7)}"""

        // when
        val root = parseRoot(yson)

        // then
        assertEquals(YsonInt(7), root["k"])
    }

    @Test
    fun `parse should treat a differently spelled repeated key as the same key`() {
        // given the second key is spelled with a unicode escape
        val yson = """{"k":1,"\u006b":2}"""

        // when
        val root = parseRoot(yson)

        // then
        assertEquals(mapOf("k" to num(2)), root)
    }

    @Test
    fun `parse should not merge the same key in sibling objects`() {
        // given
        val yson = """[{"k":1},{"k":2}]"""

        // when
        val array = parse(yson) as YsonArray

        // then
        assertEquals(
            listOf(YsonObject(mapOf("k" to num(1))), YsonObject(mapOf("k" to num(2)))),
            array.elements,
        )
    }

    @Test
    fun `parse should keep the last value of a repeated reserved key`() {
        // given
        val yson = """{"__yson_type":"a","__yson_type":"b"}"""

        // when
        val root = parseRoot(yson)

        // then
        assertEquals(mapOf("__yson_type" to YsonString("b")), root)
    }

    @Test
    fun `parse should keep the last value of a repeated attribute key`() {
        // given
        val text = parseRoot(
            """{"t":Text([{"val":"a","attrs":{"b":1,"b":2}}])}""",
        )["t"] as YsonText
        val tree = parseRoot(
            """{"t":Tree({"type":"p","attrs":{"b":"x","b":"y"}})}""",
        )["t"] as YsonTree

        // then
        assertEquals(mapOf("b" to num(2)), text.nodes.single().attrs)
        assertEquals(mapOf("b" to "y"), tree.root.attrs)
    }

    @Test
    fun `parse should keep the last value of a repeated node field`() {
        // given
        val text = parseRoot("""{"t":Text([{"val":"a","val":"b"}])}""")["t"] as YsonText
        val tree = parseRoot(
            """{"t":Tree({"type":"x","type":"p","children":[],"children":[{"type":"i"}]})}""",
        )["t"] as YsonTree

        // then
        assertEquals("b", text.nodes.single().value)
        assertEquals("p", tree.root.type)
        assertEquals(listOf(YsonTreeNode("i")), tree.root.children)
    }

    @Test
    fun `parse should keep the last value of a repeated key inside a constructor argument`() {
        // given
        val yson = """{"t":Text([{"val":"a","attrs":{"x":1}},{"val":"b","val":"c"}])}"""

        // when
        val text = parseRoot(yson)["t"] as YsonText

        // then
        assertEquals(listOf("a", "c"), text.nodes.map { it.value })
    }
}
