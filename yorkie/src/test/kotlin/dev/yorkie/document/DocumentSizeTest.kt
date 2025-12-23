package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.Rht
import dev.yorkie.document.crdt.toXml
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore

class DocumentSizeTest {
    private lateinit var document: Document

    @Before
    fun setup() {
        document = Document("")
    }

    @Test
    fun `should return correct doc size with primitive type`() = runTest {
        document.updateAsync { root, _ ->
            root["k0"] = null
        }.await()
        // Root (primitive) + Primitive (null)
        assertEquals(
            expected = DataSize(
                data = 8,
                meta = 72,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            root["k1"] = true
        }.await()
        assertEquals(
            expected = DataSize(
                data = 12,
                meta = 120,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            root["k2"] = 102020
        }.await()
        assertEquals(
            expected = DataSize(
                data = 16,
                meta = 168,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            root["k3"] = 102020L
        }.await()
        assertEquals(
            expected = DataSize(
                data = 24,
                meta = 216,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            root["k4"] = 1.79
        }.await()
        assertEquals(
            expected = DataSize(
                data = 32,
                meta = 264,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            root["k5"] = "40"
        }.await()
        assertEquals(
            expected = DataSize(
                data = 36,
                meta = 312,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            root["k6"] = byteArrayOf(65, 66)
        }.await()
        assertEquals(
            expected = DataSize(
                data = 38,
                meta = 360,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            root["k7"] = Date()
        }.await()
        assertEquals(
            expected = DataSize(
                data = 46,
                meta = 408,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            root.remove("k0")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 38,
                meta = 360,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 8,
                meta = 72,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            root.remove("k1")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 34,
                meta = 312,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 12,
                meta = 144,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            root.remove("k2")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 30,
                meta = 264,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 16,
                meta = 216,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            root.remove("k3")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 22,
                meta = 216,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 24,
                meta = 288,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            root.remove("k4")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 14,
                meta = 168,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 32,
                meta = 360,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            root.remove("k5")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 10,
                meta = 120,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 36,
                meta = 432,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            root.remove("k6")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 8,
                meta = 72,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 38,
                meta = 504,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            root.remove("k7")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 0,
                meta = 24,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 46,
                meta = 576,
            ),
            actual = document.getDocSize().gc,
        )
    }

    @Test
    fun `should return correct doc size with array type`() = runTest {
        document.updateAsync { root, _ ->
            val array = root.setNewArray("arr")
            array.put("a")
        }.await()

        assertEquals(
            expected = DataSize(
                data = 2,
                meta = 96,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            val array = root.getAs<JsonArray>("arr")
            array.removeAt(0)
        }.await()

        assertEquals(
            expected = DataSize(
                data = 0,
                meta = 72,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 2,
                meta = 48,
            ),
            actual = document.getDocSize().gc,
        )
    }

    @Test
    fun `should return correct doc size with object type`() = runTest {
        document.updateAsync { root, _ ->
            val obj = root.setNewObject("obj")
            obj["k0"] = 1
        }.await()
        assertEquals(
            expected = DataSize(
                data = 4,
                meta = 120,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            val obj = root.getAs<JsonObject>("obj")
            obj.remove("k0")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 0,
                meta = 72,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 4,
                meta = 72,
            ),
            actual = document.getDocSize().gc,
        )
    }

    @Test
    fun `should return correct doc size with counter type`() = runTest {
        document.updateAsync { root, _ ->
            root.setNewCounter("counter", 0)
        }.await()
        assertEquals(
            expected = DataSize(
                data = 4,
                meta = 72,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            root.remove("counter")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 0,
                meta = 24,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 4,
                meta = 72,
            ),
            actual = document.getDocSize().gc,
        )
    }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun `should return correct doc size with text type`() = runTest {
        document.updateAsync { root, _ ->
            val text = root.setNewText("text")
            text.edit(0, 0, "helloworld")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 20,
                meta = 96,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            val text = root.getAs<JsonText>("text")
            text.edit(5, 5, " ")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 22,
                meta = 144,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            val text = root.getAs<JsonText>("text")
            text.edit(6, 11, "")
        }.await()
        assertEquals(
            expected = DataSize(
                data = 12,
                meta = 120,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 10,
                meta = 48,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            val text = root.getAs<JsonText>("text")
            text.style(0, 5, mapOf("bold" to "true"))
        }.await()
        assertEquals(
            expected = DataSize(
                data = 28,
                meta = 144,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 10,
                meta = 48,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            val text = root.getAs<JsonText>("text")
            text.edit(1, 1, "")
        }.await()
        assertEquals(
            expected = "{\"text\":[{\"attrs\":{\"bold\":\"true\"},\"val\":\"h\"},{\"attrs\":{\"bold\":\"true\"},\"val\":\"ello\"},{\"val\":\" \"}]}",
            actual = document.toJson(),
        )
        assertEquals(
            expected = DataSize(
                data = 44,
                meta = 192,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 10,
                meta = 48,
            ),
            actual = document.getDocSize().gc,
        )
    }

    @Test
    fun `should return correct doc size with tree type`() = runTest {
        document.updateAsync { root, _ ->
            root.setNewTree(
                key = "tree",
                initialRoot = element("doc") {
                    element("p")
                },
            )
            assertEquals(
                expected = root.getAs<JsonTree>("tree").toXml(),
                actual = "<doc><p></p></doc>",
            )
        }.await()
        assertEquals(
            expected = DataSize(
                data = 0,
                meta = 120,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            val tree = root.getAs<JsonTree>("tree")
            tree.edit(
                fromIndex = 1,
                toIndex = 1,
                text {
                    "helloworld"
                },
            )
            assertEquals(
                expected = root.getAs<JsonTree>("tree").toXml(),
                actual = "<doc><p>helloworld</p></doc>",
            )
        }.await()
        assertEquals(
            expected = DataSize(
                data = 20,
                meta = 144,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            val tree = root.getAs<JsonTree>("tree")
            tree.edit(
                fromIndex = 1,
                toIndex = 7,
                text {
                    "w"
                },
            )
            assertEquals(
                expected = root.getAs<JsonTree>("tree").toXml(),
                actual = "<doc><p>world</p></doc>",
            )
        }.await()
        assertEquals(
            expected = DataSize(
                data = 10,
                meta = 168,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 12,
                meta = 48,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            val tree = root.getAs<JsonTree>("tree")
            tree.edit(
                fromIndex = 7,
                toIndex = 7,
                element("p") {
                    text {
                        "abcd"
                    }
                },
            )
            assertEquals(
                expected = root.getAs<JsonTree>("tree").toXml(),
                actual = "<doc><p>world</p><p>abcd</p></doc>",
            )
        }.await()
        assertEquals(
            expected = DataSize(
                data = 18,
                meta = 216,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            val tree = root.getAs<JsonTree>("tree")
            tree.edit(7, 13)
            assertEquals(
                expected = root.getAs<JsonTree>("tree").toXml(),
                actual = "<doc><p>world</p></doc>",
            )
        }.await()
        assertEquals(
            expected = DataSize(
                data = 10,
                meta = 168,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 20,
                meta = 144,
            ),
            actual = document.getDocSize().gc,
        )

        document.updateAsync { root, _ ->
            val tree = root.getAs<JsonTree>("tree")
            tree.style(0, 7, mapOf("bold" to "true"))
            assertEquals(
                expected = root.getAs<JsonTree>("tree").toXml(),
                actual = "<doc><p bold=\"true\">world</p></doc>",
            )
        }.await()
        assertEquals(
            expected = DataSize(
                data = 26,
                meta = 192,
            ),
            actual = document.getDocSize().live,
        )

        document.updateAsync { root, _ ->
            val tree = root.getAs<JsonTree>("tree")
            tree.removeStyle(0, 7, listOf("bold"))
            assertEquals(
                expected = root.getAs<JsonTree>("tree").toXml(),
                actual = "<doc><p>world</p></doc>",
            )
        }.await()
        assertEquals(
            expected = DataSize(
                data = 10,
                meta = 168,
            ),
            actual = document.getDocSize().live,
        )
        assertEquals(
            expected = DataSize(
                data = 36,
                meta = 168,
            ),
            actual = document.getDocSize().gc,
        )
    }

    @Test
    fun `should return correct data size with node type`() {
        val root = CrdtTreeNode(
            id = CrdtTreeNodeID.InitialCrdtTreeNodeID,
            type = "r",
        )
        val para = CrdtTreeNode(
            id = CrdtTreeNodeID.InitialCrdtTreeNodeID,
            type = "p",
        )
        root.append(para)
        para.append(
            node = CrdtTreeNode(
                id = CrdtTreeNodeID.InitialCrdtTreeNodeID,
                type = "text",
                _value = "helloworld",
            ),
        )

        val left = para.children[0]
        val (rightText, diffText) = left.splitText(5, 0)
        assertEquals(
            expected = DataSize(
                data = 0,
                meta = 24,
            ),
            actual = diffText,
        )
        assertEquals(
            expected = DataSize(
                data = 10,
                meta = 24,
            ),
            actual = left.dataSize,
        )
        assertEquals(
            expected = DataSize(
                data = 10,
                meta = 24,
            ),
            actual = rightText?.dataSize,
        )

        val (rightElem, diffElem) = para.splitElement(1) {
            TimeTicket.InitialTimeTicket
        }
        assertEquals(
            expected = DataSize(
                data = 0,
                meta = 24,
            ),
            actual = diffElem,
        )
        assertEquals(
            expected = "<p>hello</p>",
            actual = para.toXml(),
        )
        assertEquals(
            expected = "<p>world</p>",
            actual = rightElem?.toXml(),
        )
    }

    @Test
    @Ignore("should be resolved after the JS SDK implementation")
    fun `test split tree node with attribute`() {
        val attributes = Rht().apply {
            set("bold", "true", TimeTicket.InitialTimeTicket)
        }

        val root = CrdtTreeNode(
            id = CrdtTreeNodeID.InitialCrdtTreeNodeID,
            type = "r",
        )
        val para = CrdtTreeNode(
            id = CrdtTreeNodeID.InitialCrdtTreeNodeID,
            type = "p",
            _attributes = attributes,
        )
        root.append(para)
        para.append(
            node = CrdtTreeNode(
                id = CrdtTreeNodeID.InitialCrdtTreeNodeID,
                type = "text",
                _value = "helloworld",
            ),
        )
        assertEquals(
            expected = "<r><p bold=\"true\">helloworld</p></r>",
            actual = root.toXml(),
        )

        val left = para.children[0]
        left.splitText(5, 0)

        val (rightElem, diffElem) = para.splitElement(1) {
            TimeTicket.InitialTimeTicket
        }
        assertEquals(
            expected = DataSize(
                data = 16,
                meta = 48,
            ),
            actual = diffElem,
        )
        assertEquals(
            expected = "<p bold=\"true\">hello</p>",
            actual = para.toXml(),
        )
        assertEquals(
            expected = "<p bold=\"true\">world</p>",
            actual = rightElem?.toXml(),
        )
    }

    @Test
    fun `should return correct doc size when deep copy`() = runTest {
        document.updateAsync { root, _ ->
            root.setNewCounter("counter", 1)
        }.await()

        val clone = document.clone?.root?.deepCopy()
        assertEquals(
            expected = clone?.docSize,
            actual = document.getDocSize(),
        )
    }

    @Test
    fun `should return correct doc size when deep copy for nested element`() = runTest {
        document.updateAsync { root, _ ->
            root.setNewArray("arr").putNewObject().apply {
                setNewCounter("counter", 1)
            }
        }.await()

        val clone = document.clone?.root?.deepCopy()
        assertEquals(
            expected = clone?.docSize,
            actual = document.getDocSize(),
        )
    }
}
