package dev.yorkie.document.yson

import dev.yorkie.util.YorkieException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YsonParserTest {

    // --- primitives ---

    @Test
    fun `parse should decode string primitive`() {
        val result = parse("\"hello\"")
        assertEquals(YsonValue.YsonString("hello"), result)
    }

    @Test
    fun `parse should decode number primitive`() {
        val result = parse("42")
        assertEquals(YsonValue.YsonNumber(42.0), result)
    }

    @Test
    fun `parse should decode boolean primitive`() {
        assertEquals(YsonValue.YsonBoolean(true), parse("true"))
        assertEquals(YsonValue.YsonBoolean(false), parse("false"))
    }

    @Test
    fun `parse should decode null primitive`() {
        assertEquals(YsonValue.YsonNull, parse("null"))
    }

    // --- collections ---

    @Test
    fun `parse should decode array of numbers`() {
        val result = parse("[1, 2, 3]")
        assertEquals(
            YsonValue.YsonArray(
                listOf(
                    YsonValue.YsonNumber(1.0),
                    YsonValue.YsonNumber(2.0),
                    YsonValue.YsonNumber(3.0),
                ),
            ),
            result,
        )
    }

    @Test
    fun `parse should decode plain object`() {
        val result = parse("{\"name\":\"Alice\",\"age\":30}")
        val expected = YsonValue.YsonObject(
            linkedMapOf(
                "name" to YsonValue.YsonString("Alice"),
                "age" to YsonValue.YsonNumber(30.0),
            ),
        )
        assertEquals(expected, result)
    }

    @Test
    fun `parse should decode empty object`() {
        assertEquals(YsonValue.YsonObject(emptyMap()), parse("{}"))
    }

    @Test
    fun `parse should decode empty array`() {
        assertEquals(YsonValue.YsonArray(emptyList()), parse("[]"))
    }

    // --- Text CRDT ---

    @Test
    fun `parse should decode Text CRDT`() {
        val yson = "{\"content\":Text([{\"val\":\"H\"},{\"val\":\"i\"}])}"
        val result = parse(yson)

        assertTrue(isObject(result))
        val obj = result as YsonValue.YsonObject
        val content = obj.entries["content"]
        assertTrue(isText(content))

        val text = content as YsonValue.YsonText
        assertEquals(2, text.nodes.size)
        assertEquals("H", text.nodes[0].value)
        assertEquals("i", text.nodes[1].value)
    }

    @Test
    fun `parse should decode Text CRDT with attributes`() {
        val yson = "{\"content\":Text([{\"val\":\"H\",\"attrs\":{\"bold\":true}}])}"
        val result = parse(yson) as YsonValue.YsonObject
        val content = result.entries["content"] as YsonValue.YsonText

        assertEquals(1, content.nodes.size)
        val attrs = content.nodes[0].attrs
        assertNotNull(attrs)
        assertEquals(YsonValue.YsonBoolean(true), attrs!!["bold"])
    }

    // --- Tree CRDT ---

    @Test
    fun `parse should decode Tree CRDT`() {
        val yson =
            "{\"content\":Tree({\"type\":\"doc\",\"children\":" +
                "[{\"type\":\"p\",\"children\":[{\"type\":\"text\",\"value\":\"Hello\"}]}]})}"
        val result = parse(yson) as YsonValue.YsonObject
        val content = result.entries["content"]
        assertTrue(isTree(content))

        val tree = content as YsonValue.YsonTree
        assertEquals("doc", tree.root.type)
        val children = tree.root.children
        assertNotNull(children)
        assertEquals(1, children!!.size)
        assertEquals("p", children[0].type)
    }

    // --- nested ---

    @Test
    fun `parse should decode nested structures with Text inside array`() {
        val yson = "{\"users\":[{\"name\":\"Alice\",\"content\":Text([{\"val\":\"A\"}])}]}"
        val result = parse(yson) as YsonValue.YsonObject
        val users = result.entries["users"] as YsonValue.YsonArray
        val first = users.elements[0] as YsonValue.YsonObject
        assertTrue(isText(first.entries["content"]))
    }

    // --- type guards ---

    @Test
    fun `isText should identify Text objects`() {
        val text = YsonValue.YsonText(listOf(YsonTextNode("H")))
        assertTrue(isText(text))
        assertFalse(isText(YsonValue.YsonObject(emptyMap())))
        assertFalse(isText("string"))
    }

    @Test
    fun `isTree should identify Tree objects`() {
        val tree = YsonValue.YsonTree(YsonTreeNode(type = "doc", children = emptyList()))
        assertTrue(isTree(tree))
        assertFalse(isTree(YsonValue.YsonObject(emptyMap())))
    }

    @Test
    fun `isObject should identify plain objects only`() {
        assertTrue(isObject(YsonValue.YsonObject(mapOf("name" to YsonValue.YsonString("Alice")))))
        assertFalse(isObject(YsonValue.YsonText(emptyList())))
        assertFalse(isObject(YsonValue.YsonArray(emptyList())))
    }

    // --- utility ---

    @Test
    fun `textToString should concatenate node values`() {
        val text = YsonValue.YsonText(
            listOf(
                YsonTextNode("H"),
                YsonTextNode("e"),
                YsonTextNode("l"),
                YsonTextNode("l"),
                YsonTextNode("o"),
            ),
        )
        assertEquals("Hello", textToString(text))
    }

    @Test
    fun `textToString should return empty for empty text`() {
        assertEquals("", textToString(YsonValue.YsonText(emptyList())))
    }

    @Test
    fun `treeToXML should convert tree to XML`() {
        val tree = YsonValue.YsonTree(
            YsonTreeNode(
                type = "doc",
                children = listOf(
                    YsonTreeNode(
                        type = "p",
                        attrs = mapOf("class" to "paragraph"),
                        children = listOf(YsonTreeNode(type = "text", value = "Hello")),
                    ),
                ),
            ),
        )
        val xml = treeToXML(tree)
        assertTrue(xml.contains("<doc>"))
        assertTrue(xml.contains("<p class=\"paragraph\">"))
        assertTrue(xml.contains("<text>Hello</text>"))
    }

    @Test
    fun `treeToXML should emit self-closing tag for empty element`() {
        val tree = YsonValue.YsonTree(YsonTreeNode(type = "br"))
        assertEquals("<br />", treeToXML(tree))
    }

    @Test
    fun `treeToXML should escape special XML characters in text and attributes`() {
        val tree = YsonValue.YsonTree(
            YsonTreeNode(
                type = "p",
                attrs = mapOf("title" to "a & b < c"),
                children = listOf(YsonTreeNode(type = "text", value = "<hi>")),
            ),
        )
        val xml = treeToXML(tree)
        assertTrue(xml.contains("title=\"a &amp; b &lt; c\""))
        assertTrue(xml.contains("&lt;hi&gt;"))
    }

    // --- Int type ---

    @Test
    fun `parse should decode Int type`() {
        val result = parse("{\"value\":Int(42)}") as YsonValue.YsonObject
        val value = result.entries["value"]
        assertTrue(isInt(value))
        assertEquals(42, (value as YsonValue.YsonInt).value)
    }

    @Test
    fun `parse should decode negative Int`() {
        val result = parse("{\"value\":Int(-42)}") as YsonValue.YsonObject
        assertEquals(-42, (result.entries["value"] as YsonValue.YsonInt).value)
    }

    // --- Long type ---

    @Test
    fun `parse should decode Long type`() {
        val result = parse("{\"value\":Long(64)}") as YsonValue.YsonObject
        val value = result.entries["value"]
        assertTrue(isLong(value))
        assertEquals(64L, (value as YsonValue.YsonLong).value)
    }

    @Test
    fun `parse should decode Long at maximum boundary`() {
        val result = parse("{\"value\":Long(${Long.MAX_VALUE})}") as YsonValue.YsonObject
        assertEquals(Long.MAX_VALUE, (result.entries["value"] as YsonValue.YsonLong).value)
    }

    @Test
    fun `parse should throw when Int value overflows`() {
        val overflow = (Int.MAX_VALUE.toLong() + 1).toString()
        assertThrows(YorkieException::class.java) { parse("{\"value\":Int($overflow)}") }
    }

    // --- Date type ---

    @Test
    fun `parse should decode Date type`() {
        val dateStr = "2025-01-02T15:04:05.058Z"
        val result = parse("{\"value\":Date(\"$dateStr\")}") as YsonValue.YsonObject
        val value = result.entries["value"]
        assertTrue(isDate(value))
        assertEquals(dateStr, (value as YsonValue.YsonDate).value)
    }

    // --- BinData type ---

    @Test
    fun `parse should decode BinData type`() {
        val result = parse("{\"value\":BinData(\"AQID\")}") as YsonValue.YsonObject
        val value = result.entries["value"]
        assertTrue(isBinData(value))
        assertEquals("AQID", (value as YsonValue.YsonBinData).value)
    }

    // --- Counter ---

    @Test
    fun `parse should decode Counter with Int`() {
        val result = parse("{\"value\":Counter(Int(10))}") as YsonValue.YsonObject
        val value = result.entries["value"]
        assertTrue(isCounter(value))
        val inner = (value as YsonValue.YsonCounter).value
        assertTrue(isInt(inner))
        assertEquals(10, (inner as YsonValue.YsonInt).value)
    }

    @Test
    fun `parse should decode Counter with Long`() {
        val result = parse("{\"value\":Counter(Long(100))}") as YsonValue.YsonObject
        val counter = result.entries["value"] as YsonValue.YsonCounter
        assertTrue(isLong(counter.value))
        assertEquals(100L, (counter.value as YsonValue.YsonLong).value)
    }

    // --- complex document ---

    @Test
    fun `parse should decode complex document with every supported type`() {
        val yson = """
            {
              "str": "value1",
              "num": 42,
              "int": Int(42),
              "long": Long(64),
              "null": null,
              "bool": true,
              "bytes": BinData("AQID"),
              "date": Date("2025-01-02T15:04:05.058Z"),
              "counter": Counter(Int(10)),
              "text": Text([{"val":"Hello"}]),
              "tree": Tree({"type":"p","children":[{"type":"text","value":"Hello World"}]})
            }
        """.trimIndent()
        val result = parse(yson) as YsonValue.YsonObject

        assertEquals(YsonValue.YsonString("value1"), result.entries["str"])
        assertEquals(YsonValue.YsonNumber(42.0), result.entries["num"])
        assertTrue(isInt(result.entries["int"]))
        assertTrue(isLong(result.entries["long"]))
        assertEquals(YsonValue.YsonNull, result.entries["null"])
        assertEquals(YsonValue.YsonBoolean(true), result.entries["bool"])
        assertTrue(isBinData(result.entries["bytes"]))
        assertTrue(isDate(result.entries["date"]))
        assertTrue(isCounter(result.entries["counter"]))
        assertTrue(isText(result.entries["text"]))
        assertTrue(isTree(result.entries["tree"]))
    }

    // --- error handling ---

    @Test
    fun `parse should throw on invalid input`() {
        assertThrows(YorkieException::class.java) { parse("invalid yson") }
    }

    @Test
    fun `parse should throw on invalid Text node format`() {
        assertThrows(YorkieException::class.java) {
            parse("{\"content\":Text([{\"invalid\":\"node\"}])}")
        }
    }

    @Test
    fun `parse should throw on invalid Tree node format`() {
        assertThrows(YorkieException::class.java) {
            parse("{\"content\":Tree({\"invalid\":\"tree\"})}")
        }
    }

    @Test
    fun `parse should throw on unterminated string`() {
        assertThrows(YorkieException::class.java) { parse("\"unterminated") }
    }

    @Test
    fun `parse should throw on trailing comma in object`() {
        assertThrows(YorkieException::class.java) { parse("{\"a\":1,}") }
    }

    @Test
    fun `parse should throw on trailing comma in array`() {
        assertThrows(YorkieException::class.java) { parse("[1,2,]") }
    }

    @Test
    fun `parse should throw on missing colon in object`() {
        assertThrows(YorkieException::class.java) { parse("{\"key\" \"value\"}") }
    }

    @Test
    fun `parse should throw on Counter with non integer payload`() {
        assertThrows(YorkieException::class.java) {
            parse("{\"value\":Counter(Date(\"2025-01-02T15:04:05Z\"))}")
        }
    }

    @Test
    fun `YsonCounter constructor should reject non integer value`() {
        assertThrows(IllegalArgumentException::class.java) {
            YsonValue.YsonCounter(YsonValue.YsonString("nope"))
        }
    }

    // --- escapes and edge cases ---

    @Test
    fun `parse should decode string escapes`() {
        val result = parse("\"a\\n\\t\\\"\\\\b\"") as YsonValue.YsonString
        assertEquals("a\n\t\"\\b", result.value)
    }

    @Test
    fun `parse should decode unicode escape`() {
        val result = parse("\"\\u00e9\"") as YsonValue.YsonString
        assertEquals("é", result.value)
    }

    @Test
    fun `parse should decode negative zero`() {
        val result = parse("-0") as YsonValue.YsonNumber
        assertEquals(-0.0, result.value, 0.0)
    }

    @Test
    fun `parse should decode scientific notation`() {
        val result = parse("1.5e2") as YsonValue.YsonNumber
        assertEquals(150.0, result.value, 0.0)
    }

    @Test
    fun `parse should preserve object key order`() {
        val result = parse("{\"z\":1,\"a\":2,\"m\":3}") as YsonValue.YsonObject
        assertEquals(listOf("z", "a", "m"), result.entries.keys.toList())
    }

    @Test
    fun `parse should reject trailing characters`() {
        assertThrows(YorkieException::class.java) { parse("42 trailing") }
    }

    @Test
    fun `parse should reject empty input`() {
        assertThrows(YorkieException::class.java) { parse("") }
    }

    @Test
    fun `parse should ignore whitespace around tokens`() {
        val result = parse("   {  \"a\" :  1  }  ") as YsonValue.YsonObject
        assertEquals(YsonValue.YsonNumber(1.0), result.entries["a"])
    }

    @Test
    fun `parse should reject object missing closing brace`() {
        assertThrows(YorkieException::class.java) { parse("{\"a\":1") }
    }

    @Test
    fun `parse should reject array missing closing bracket`() {
        assertThrows(YorkieException::class.java) { parse("[1,2") }
    }

    @Test
    fun `Text node without optional attrs should not carry attrs`() {
        val result = parse("Text([{\"val\":\"x\"}])") as YsonValue.YsonText
        assertNull(result.nodes[0].attrs)
    }

    // --- typed parse with reified generics ---

    @Test
    fun `parseAs should narrow result to YsonObject`() {
        val yson = "{\"content\":Text([{\"val\":\"H\"},{\"val\":\"i\"}]),\"title\":\"Hello\"}"
        val result = parseAs<YsonValue.YsonObject>(yson)

        assertEquals(YsonValue.YsonString("Hello"), result.entries["title"])
        val content = result.entries["content"] as YsonValue.YsonText
        assertEquals(2, content.nodes.size)
    }

    @Test
    fun `parseAs should narrow result to YsonArray`() {
        val yson = "[{\"id\":1,\"text\":Text([{\"val\":\"A\"}])}," +
            "{\"id\":2,\"text\":Text([{\"val\":\"B\"}])}]"
        val result = parseAs<YsonValue.YsonArray>(yson)

        assertEquals(2, result.elements.size)
        val first = result.elements[0] as YsonValue.YsonObject
        assertEquals(YsonValue.YsonNumber(1.0), first.entries["id"])
        val firstText = first.entries["text"] as YsonValue.YsonText
        assertEquals("A", firstText.nodes[0].value)
    }

    @Test
    fun `parseAs should narrow result to YsonString`() {
        val result = parseAs<YsonValue.YsonString>("\"hello\"")
        assertEquals("hello", result.value)
    }

    @Test
    fun `parseAs should narrow result to YsonNumber`() {
        val result = parseAs<YsonValue.YsonNumber>("42")
        assertEquals(42.0, result.value, 0.0)
    }

    @Test
    fun `parseAs should narrow result to YsonBoolean`() {
        val result = parseAs<YsonValue.YsonBoolean>("true")
        assertTrue(result.value)
    }

    @Test
    fun `parseAs should narrow result to YsonInt`() {
        val result = parseAs<YsonValue.YsonInt>("Int(42)")
        assertEquals(42, result.value)
    }

    @Test
    fun `parseAs should narrow result to YsonLong`() {
        val result = parseAs<YsonValue.YsonLong>("Long(64)")
        assertEquals(64L, result.value)
    }

    @Test
    fun `parseAs should narrow result to YsonDate`() {
        val dateStr = "2025-01-02T15:04:05.058Z"
        val result = parseAs<YsonValue.YsonDate>("Date(\"$dateStr\")")
        assertEquals(dateStr, result.value)
    }

    @Test
    fun `parseAs should narrow result to YsonBinData`() {
        val result = parseAs<YsonValue.YsonBinData>("BinData(\"AQID\")")
        assertEquals("AQID", result.value)
    }

    @Test
    fun `parseAs should narrow result to YsonCounter`() {
        val result = parseAs<YsonValue.YsonCounter>("Counter(Int(10))")
        val inner = result.value as YsonValue.YsonInt
        assertEquals(10, inner.value)
    }

    @Test
    fun `parseAs should narrow result to YsonText`() {
        val result = parseAs<YsonValue.YsonText>("Text([{\"val\":\"H\"},{\"val\":\"i\"}])")
        assertEquals(2, result.nodes.size)
        assertEquals("H", result.nodes[0].value)
    }

    @Test
    fun `parseAs should narrow result to YsonTree`() {
        val yson = "Tree({\"type\":\"doc\",\"children\":" +
            "[{\"type\":\"p\",\"children\":[{\"type\":\"text\",\"value\":\"Hello\"}]}]})"
        val result = parseAs<YsonValue.YsonTree>(yson)
        assertEquals("doc", result.root.type)
    }

    @Test
    fun `parseAs should narrow result to YsonValue for nested mixed types`() {
        val yson = """
            {
              "text": Text([{"val":"H"}]),
              "tree": Tree({"type":"p","children":[]}),
              "counter": Counter(Int(10)),
              "timestamp": Date("2025-01-02T15:04:05.058Z")
            }
        """.trimIndent()
        val result = parseAs<YsonValue.YsonObject>(yson)

        assertTrue(isText(result.entries["text"]))
        assertTrue(isTree(result.entries["tree"]))
        assertTrue(isCounter(result.entries["counter"]))
        assertTrue(isDate(result.entries["timestamp"]))
    }

    @Test
    fun `parseAs should throw when parsing number as YsonObject`() {
        val error = assertThrows(YorkieException::class.java) {
            parseAs<YsonValue.YsonObject>("123")
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, error.code)
        assertTrue(error.errorMessage.contains("YsonObject"))
        assertTrue(error.errorMessage.contains("YsonNumber"))
    }

    @Test
    fun `parseAs should throw when parsing object as YsonArray`() {
        val error = assertThrows(YorkieException::class.java) {
            parseAs<YsonValue.YsonArray>("{}")
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, error.code)
        assertTrue(error.errorMessage.contains("YsonArray"))
        assertTrue(error.errorMessage.contains("YsonObject"))
    }

    @Test
    fun `parseAs should throw when parsing Text as YsonTree`() {
        val error = assertThrows(YorkieException::class.java) {
            parseAs<YsonValue.YsonTree>("Text([{\"val\":\"H\"}])")
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, error.code)
        assertTrue(error.errorMessage.contains("YsonTree"))
        assertTrue(error.errorMessage.contains("YsonText"))
    }

    @Test
    fun `parseAs should propagate parse errors`() {
        assertThrows(YorkieException::class.java) {
            parseAs<YsonValue.YsonObject>("invalid yson")
        }
    }
}
