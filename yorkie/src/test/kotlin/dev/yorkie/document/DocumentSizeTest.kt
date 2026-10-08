package dev.yorkie.document

import dev.yorkie.api.toCrdtElement
import dev.yorkie.api.toPBJsonObject
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
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
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Before

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

        val (rightElem, diffElem) = para.splitElement(1, null) {
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

        val (rightElem, diffElem) = para.splitElement(1, null) {
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

    /**
     * AC8: `deepCopy()` of a root holding a removed non-empty container
     * reports exactly the live root's [DocSize]. Before yorkie-js-sdk#1350
     * the constructor replayed
     * [dev.yorkie.document.crdt.CrdtRoot.registerRemovedElement] for every
     * tombstone it found, refunding one [TimeTicket.TIME_TICKET_SIZE] of
     * live meta the incremental history never credited (#1349 item 3);
     * registration now adopts tombstones without a refund.
     */
    @Test
    fun `deep copy of a removed non-empty container matches the live root`() = runTest {
        document.updateAsync { root, _ ->
            root.setNewObject("k")["a"] = "1"
        }.await()
        document.updateAsync { root, _ -> root.remove("k") }.await()

        val clone = requireNotNull(document.clone).root.deepCopy()
        assertEquals(document.getDocSize(), clone.docSize)
    }

    /**
     * Pins yorkie-js-sdk `649fe5c6` (v0.7.22, yorkie-js-sdk#1350) case 3:
     * "rebuilding a document that holds a tombstone". A [CrdtRoot] rebuilt
     * from the encoded root (`toPBJsonObject().toCrdtElement()`, the same
     * path a snapshot decode takes) reports the same [DocSize][dev.yorkie.util.DocSize]
     * as the live document, a deep copy agrees too, and the rebuilt root's
     * tombstone is still collectable. Already on Android via `b55bfc02`;
     * RED not constructible (production code already present). Parity
     * unverified -- JS not executed this session.
     */
    @Test
    fun `rebuilding a document that holds a tombstone`() = runTest {
        document.updateAsync { root, _ ->
            root.setNewObject("k").apply {
                this["a"] = "1"
                this["b"] = "2"
            }
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonObject>("k").remove("b") }.await()

        val rebuilt =
            CrdtRoot(document.getRootObject().toPBJsonObject().toCrdtElement() as CrdtObject)
        assertEquals(document.getDocSize(), rebuilt.docSize)
        assertEquals(
            document.getDocSize(),
            CrdtRoot(document.getRootObject().deepCopy() as CrdtObject).docSize,
        )
        assertEquals(1, rebuilt.garbageLength)
        assertEquals(1, rebuilt.garbageCollect(maxVectorOf(listOf(document.changeID.actor))))
        assertEquals(DataSize(0, 0), rebuilt.docSize.gc)
    }

    /**
     * Ports JS `test/unit/document/document_size_test.ts` "accounts for the
     * element a split creates" (v0.7.23, #1359, `8cb96f51`, server twin
     * yorkie#1998). A split mints a new element node, and the phase that
     * does it dropped the size its own `split` call reported, so `live`
     * never carried it. A split and the merge that undoes it then did not
     * cancel out: `live.meta` walked down by a ticket per cycle, without
     * bound. RED at `5959dac0`: before `CrdtTree.edit`'s step-04 diff
     * accumulation, `live` after the split was `{20, 192}` (the discarded
     * ticket), not `{20, 216}`, and the 100-cycle loop drifted.
     */
    @Test
    fun `accounts for the element a split creates`() = runTest {
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("span") { text { "abcdefghij" } }
                    }
                },
            )
        }.await()
        assertEquals(DataSize(data = 20, meta = 168), document.getDocSize().live)

        // Split after `a`: a new <span> and a text split, one ticket each.
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 0, 1), listOf(0, 0, 1), splitLevel = 1)
        }.await()
        assertEquals(
            "<doc><p><span>a</span><span>bcdefghij</span></p></doc>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )
        assertEquals(DataSize(data = 20, meta = 216), document.getDocSize().live)

        // Merge the boundary back. The <span> the split created is
        // tombstoned, so its size moves to gc. The text stays two nodes,
        // which is why live keeps the ticket the text split added rather
        // than returning to its pre-split value -- the expectation #1998
        // states.
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 0, 1), listOf(0, 1, 0))
        }.await()
        assertEquals(
            "<doc><p><span>abcdefghij</span></p></doc>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )
        assertEquals(DataSize(data = 20, meta = 192), document.getDocSize().live)
        assertEquals(DataSize(data = 0, meta = 48), document.getDocSize().gc)

        // Every further cycle needs no text split, so live returns to the
        // same two values instead of drifting -- the 100-cycle stability
        // #1998 requires.
        repeat(100) {
            document.updateAsync { root, _ ->
                root.getAs<JsonTree>("t")
                    .editByPath(listOf(0, 0, 1), listOf(0, 0, 1), splitLevel = 1)
            }.await()
            assertEquals(DataSize(data = 20, meta = 216), document.getDocSize().live)
            document.updateAsync { root, _ ->
                root.getAs<JsonTree>("t").editByPath(listOf(0, 0, 1), listOf(0, 1, 0))
            }.await()
            assertEquals(DataSize(data = 20, meta = 192), document.getDocSize().live)
        }
    }

    /**
     * Ports JS `test/unit/document/document_size_test.ts` "charges live only
     * for attribute values it was holding" (v0.7.23, #1359, `8cb96f51`). RHT
     * mints a tombstone even for a key the element never carried -- so a
     * remove arriving before its set still wins -- and supersedes an
     * existing tombstone when the same key is removed twice or set again.
     * None of those replace a live value, yet `live` was debited for each,
     * so toggling one key walked it down without bound. RED at `5959dac0`
     * (the live figure drifts: the pre-existing `removeStyle` main-node and
     * split-sibling call sites did not distinguish a live attribute from an
     * absent/tombstoned one); GREEN with the `attrGcPair` gc-only routing
     * this spec adds.
     *
     * Android's raw-string `bold:'true'` live figure is 22 (`(4 + 4) * 2`
     * UTF-8 bytes), matching the JS v0.7.23 tag's own expectation (the old
     * JSON-quoted figure of 26 was superseded by JS #1365, spec 032 --
     * not re-derived here; Android never counted the quotes).
     */
    @Test
    fun `charges live only for attribute values it was holding`() = runTest {
        suspend fun newDoc(): Document {
            val doc = Document("")
            doc.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") { text { "abc" } }
                    },
                )
            }.await()
            assertEquals(DataSize(data = 6, meta = 144), doc.getDocSize().live)
            return doc
        }

        val absent = newDoc()
        absent.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").removeStyleByPath(listOf(0), listOf(1), listOf("never-set"))
        }.await()
        assertEquals(DataSize(data = 6, meta = 144), absent.getDocSize().live)
        assertEquals(DataSize(data = 18, meta = 24), absent.getDocSize().gc)

        val twice = newDoc()
        twice.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(1), mapOf("bold" to "true"))
        }.await()
        assertEquals(DataSize(data = 22, meta = 168), twice.getDocSize().live)
        repeat(2) {
            twice.updateAsync { root, _ ->
                root.getAs<JsonTree>("t").removeStyleByPath(listOf(0), listOf(1), listOf("bold"))
            }.await()
            assertEquals(DataSize(data = 6, meta = 144), twice.getDocSize().live)
        }

        // Toggling was already correct here -- the restyle credits live for
        // the node it revives, which cancels the debit -- and has to stay
        // that way.
        val toggled = newDoc()
        repeat(100) {
            toggled.updateAsync { root, _ ->
                root.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(1), mapOf("bold" to "true"))
            }.await()
            assertEquals(DataSize(data = 22, meta = 168), toggled.getDocSize().live)
            toggled.updateAsync { root, _ ->
                root.getAs<JsonTree>("t").removeStyleByPath(listOf(0), listOf(1), listOf("bold"))
            }.await()
            assertEquals(DataSize(data = 6, meta = 144), toggled.getDocSize().live)
        }
    }
}
