package dev.yorkie.document.yson

import dev.yorkie.document.yson.YsonValue.YsonArray
import dev.yorkie.document.yson.YsonValue.YsonBoolean
import dev.yorkie.document.yson.YsonValue.YsonNull
import dev.yorkie.document.yson.YsonValue.YsonNumber
import dev.yorkie.document.yson.YsonValue.YsonObject
import dev.yorkie.document.yson.YsonValue.YsonString
import dev.yorkie.document.yson.YsonValue.YsonText
import dev.yorkie.document.yson.YsonValue.YsonTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins how `Text` / `Tree` node `attrs` are parsed, mirroring yorkie-js-sdk v0.7.28:
 * `if (node.attrs && typeof node.attrs === 'object') result.attrs = unescapeKeys(node.attrs)`.
 * An object (any value types) or an array is kept losslessly in `rawAttrs`; every other shape
 * (string, number, boolean, null, absent) is dropped without an error.
 *
 * Remaining differences from JS, intentionally not changed here: a constructor inside attrs
 * (`attrs:{"k":Int(3)}`) is typed on Android while JS keeps the raw marker object, and
 * `attrs:Int(3)` itself (a constructor in place of the attrs value) is dropped here.
 */
class YsonParserAttrsTest {

    private fun parseRoot(yson: String): Map<String, YsonValue> =
        (parse(yson) as YsonObject).entries

    private fun obj(vararg entries: Pair<String, YsonValue>) = YsonObject(linkedMapOf(*entries))

    private fun treeRoot(yson: String): YsonTreeNode = (parseRoot(yson)["t"] as YsonTree).root

    private fun textNode(yson: String): YsonTextNode =
        (parseRoot(yson)["t"] as YsonText).nodes.single()

    // --- Tree attrs: values of any type (D1) ---

    @Test
    fun `parse should keep non-string tree attribute values typed in rawAttrs`() {
        // given
        val yson = """{"t":Tree({"type":"p","attrs":{"class":"x","n":1,"on":true,"z":null}})}"""

        // when
        val root = treeRoot(yson)

        // then
        val expected = obj(
            "class" to YsonString("x"),
            "n" to YsonNumber(1.0),
            "on" to YsonBoolean(true),
            "z" to YsonNull,
        )
        assertEquals(expected, root.rawAttrs)
        assertEquals(mapOf("class" to "x"), root.attrs)
    }

    @Test
    fun `parse should keep all-string tree attributes as before`() {
        // given
        val yson = """{"t":Tree({"type":"p","attrs":{"class":"x","id":"y"}})}"""

        // when
        val root = treeRoot(yson)

        // then
        assertEquals(mapOf("class" to "x", "id" to "y"), root.attrs)
        assertEquals(obj("class" to YsonString("x"), "id" to YsonString("y")), root.rawAttrs)
    }

    @Test
    fun `parse should keep an escaped-looking key in non-string tree attributes`() {
        // given
        val yson = """{"t":Tree({"type":"p","attrs":{"__yson_type":5}})}"""

        // when
        val root = treeRoot(yson)

        // then
        assertEquals(obj("__yson_type" to YsonNumber(5.0)), root.rawAttrs)
    }

    // --- Array attrs (D2) ---

    @Test
    fun `parse should keep array-valued tree attributes`() {
        // given
        val yson = """{"t":Tree({"type":"doc","attrs":[1,"a"],"children":[]})}"""

        // when
        val root = treeRoot(yson)

        // then
        assertEquals(YsonArray(listOf(YsonNumber(1.0), YsonString("a"))), root.rawAttrs)
        assertNull(root.attrs)
    }

    @Test
    fun `parse should keep an empty array and an empty object as attrs`() {
        // given
        val array = textNode("""{"t":Text([{"val":"a","attrs":[]}])}""")
        val empty = textNode("""{"t":Text([{"val":"a","attrs":{}}])}""")

        // then
        assertEquals(YsonArray(emptyList()), array.rawAttrs)
        assertNull(array.attrs)
        assertEquals(YsonObject(emptyMap()), empty.rawAttrs)
        assertEquals(emptyMap<String, YsonValue>(), empty.attrs)
    }

    // --- Non-object shapes are dropped, not errors ---

    @Test
    fun `parse should drop text attributes of a non-object shape`() {
        listOf("\"x\"", "5", "true", "false", "null", "Int(3)").forEach { shape ->
            // given
            val yson = """{"t":Text([{"val":"a","attrs":$shape}])}"""

            // when
            val node = textNode(yson)

            // then
            assertNull(shape, node.rawAttrs)
            assertNull(shape, node.attrs)
        }
    }

    @Test
    fun `parse should drop tree attributes of a non-object shape`() {
        listOf("\"x\"", "5", "true", "false", "null").forEach { shape ->
            // given
            val yson = """{"t":Tree({"type":"p","attrs":$shape})}"""

            // when
            val root = treeRoot(yson)

            // then
            assertNull(shape, root.rawAttrs)
            assertNull(shape, root.attrs)
        }
    }

    @Test
    fun `parse should keep absent attributes null`() {
        assertNull(textNode("""{"t":Text([{"val":"a"}])}""").rawAttrs)
        assertNull(treeRoot("""{"t":Tree({"type":"p"})}""").rawAttrs)
    }

    // --- Equality between parsed and hand-built nodes ---

    @Test
    fun `YsonTextNode built with legacy attrs should equal the parsed node`() {
        // given
        val parsed = textNode("""{"t":Text([{"val":"a","attrs":{"bold":true}}])}""")

        // when
        val built = YsonTextNode("a", mapOf("bold" to YsonBoolean(true)))

        // then
        assertEquals(built, parsed)
        assertEquals(built.hashCode(), parsed.hashCode())
    }

    @Test
    fun `YsonTreeNode built with legacy attrs should equal the parsed node`() {
        // given
        val parsed = treeRoot("""{"t":Tree({"type":"p","attrs":{"class":"x"}})}""")

        // when
        val built = YsonTreeNode(type = "p", attrs = mapOf("class" to "x"))

        // then
        assertEquals(built, parsed)
        assertEquals(built.hashCode(), parsed.hashCode())
    }

    @Test
    fun `parsed array-attrs node should not equal a node without attrs`() {
        // given
        val parsed = textNode("""{"t":Text([{"val":"a","attrs":[1]}])}""")

        // then
        assertNotEquals(YsonTextNode("a"), parsed)
    }

    @Test
    fun `copy of attrs should not update rawAttrs and copy of rawAttrs should`() {
        // given
        val node = YsonTreeNode(type = "p", attrs = mapOf("a" to "1"))

        // when
        val byAttrs = node.copy(attrs = mapOf("b" to "2"))
        val byRaw = node.copy(rawAttrs = obj("c" to YsonNumber(3.0)))

        // then copy(attrs=) does not throw and leaves rawAttrs untouched
        assertEquals(mapOf("b" to "2"), byAttrs.attrs)
        assertEquals(obj("a" to YsonString("1")), byAttrs.rawAttrs)
        assertEquals(obj("c" to YsonNumber(3.0)), byRaw.rawAttrs)
    }

    @Test
    fun `node classes should keep their pre-rawAttrs JVM constructors`() {
        // given the constructors released before rawAttrs was added
        // when
        val text = YsonTextNode::class.java.getConstructor(String::class.java, Map::class.java)
        val tree = YsonTreeNode::class.java.getConstructor(
            String::class.java,
            String::class.java,
            Map::class.java,
            List::class.java,
        )

        // then
        assertEquals(YsonTextNode("a", null), text.newInstance("a", null))
        assertEquals(YsonTreeNode("p"), tree.newInstance("p", null, null, null))
    }

    // --- treeToXML ---

    @Test
    fun `treeToXML should skip non-string attributes and render string ones`() {
        // given
        val yson = """{"t":Tree({"type":"p","attrs":{"class":"x","n":1,"z":null}})}"""

        // when
        val xml = treeToXML(parseRoot(yson)["t"] as YsonTree)

        // then
        assertEquals("""<p class="x" />""", xml)
    }

    @Test
    fun `treeToXML should render no attributes for array attrs`() {
        // given
        val yson = """{"t":Tree({"type":"p","attrs":["a"]})}"""

        // when
        val xml = treeToXML(parseRoot(yson)["t"] as YsonTree)

        // then
        assertEquals("<p />", xml)
    }
}
