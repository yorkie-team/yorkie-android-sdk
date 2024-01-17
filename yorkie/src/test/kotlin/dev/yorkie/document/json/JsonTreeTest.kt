package dev.yorkie.document.json

import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.LocalChange
import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OperationInfo.TreeEditOpInfo
import dev.yorkie.document.operation.OperationInfo.TreeStyleOpInfo
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JsonTreeTest {

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun `should be able to create from scratch and edit`() {
        val target = JsonTree(DummyContext, rootCrdtTree)
        target.edit(0, 0, element("p"))
        assertEquals("<root><p></p></root>", target.toXml())
        assertEquals(
            """{"type":"root","children":[{"type":"p","children":[]}]}""",
            target.toJson(),
        )

        target.edit(1, 1, text { "AB" })
        assertEquals("<root><p>AB</p></root>", target.toXml())
        assertEquals(
            """{"type":"root","children":[{"type":"p","children":[{"type":"text","value":"AB"}]}]}""",
            target.toJson(),
        )

        target.edit(3, 3, text { "CD" })
        assertEquals("<root><p>ABCD</p></root>", target.toXml())
        assertEquals(
            """{"type":"root","children":[{"type":"p","children":[{"type":"text","value":"AB"},{"type":"text","value":"CD"}]}]}""",
            target.toJson(),
        )

        target.edit(1, 5, text { "Yorkie" })
        assertEquals("<root><p>Yorkie</p></root>", target.toXml())
        assertEquals(
            """{"type":"root","children":[{"type":"p","children":[{"type":"text","value":"Yorkie"}]}]}""",
            target.toJson(),
        )
    }

    @Test
    fun `should be able to create with existing tree`() {
        val root = element("doc") {
            element("p") {
                text { "ab" }
            }
            element("ng") {
                element("note") {
                    text { "cd" }
                }
                element("note") {
                    text { "ef" }
                }
            }
            element("bp") {
                text { "gh" }
            }
        }
        val target = JsonTree(
            DummyContext,
            CrdtTree(JsonTree.buildRoot(root, DummyContext), InitialTimeTicket),
        )
        assertEquals(
            "<doc><p>ab</p><ng><note>cd</note><note>ef</note></ng><bp>gh</bp></doc>",
            target.toXml(),
        )
        assertEquals(18, target.size)
    }

    @Test
    fun `should be able to edit`() {
        val root = element("doc") {
            element("p") {
                text { "ab" }
            }
        }
        val target = JsonTree(
            DummyContext,
            CrdtTree(JsonTree.buildRoot(root, DummyContext), InitialTimeTicket),
        )
        assertEquals("<doc><p>ab</p></doc>", target.toXml())

        target.edit(1, 1, text { "X" })
        assertEquals("<doc><p>Xab</p></doc>", target.toXml())

        target.edit(1, 2)
        assertEquals("<doc><p>ab</p></doc>", target.toXml())

        target.edit(2, 2, text { "X" })
        assertEquals("<doc><p>aXb</p></doc>", target.toXml())

        target.edit(2, 3)
        assertEquals("<doc><p>ab</p></doc>", target.toXml())

        target.edit(3, 3, text { "X" })
        assertEquals("<doc><p>abX</p></doc>", target.toXml())

        target.edit(3, 4)
        assertEquals("<doc><p>ab</p></doc>", target.toXml())

        target.edit(2, 3)
        assertEquals("<doc><p>a</p></doc>", target.toXml())
    }

    @Test
    fun `should be able to edit with path`() {
        val root = element("doc") {
            element("tc") {
                element("p") {
                    element("tn") {
                        text { "ab" }
                    }
                }
            }
        }
        val target = JsonTree(
            DummyContext,
            CrdtTree(JsonTree.buildRoot(root, DummyContext), InitialTimeTicket),
        )
        assertEquals("<doc><tc><p><tn>ab</tn></p></tc></doc>", target.toXml())

        target.editByPath(listOf(0, 0, 0, 1), listOf(0, 0, 0, 1), text { "X" })
        assertEquals("<doc><tc><p><tn>aXb</tn></p></tc></doc>", target.toXml())

        target.editByPath(listOf(0, 0, 0, 3), listOf(0, 0, 0, 3), text { "!" })
        assertEquals("<doc><tc><p><tn>aXb!</tn></p></tc></doc>", target.toXml())

        target.editByPath(
            listOf(0, 0, 1),
            listOf(0, 0, 1),
            element("tn") {
                text { "cd" }
            },
        )
        assertEquals("<doc><tc><p><tn>aXb!</tn><tn>cd</tn></p></tc></doc>", target.toXml())

        target.editByPath(
            listOf(0, 1),
            listOf(0, 1),
            element("p") {
                element("tn") {
                    text { "q" }
                }
            },
        )
        assertEquals(
            "<doc><tc><p><tn>aXb!</tn><tn>cd</tn></p><p><tn>q</tn></p></tc></doc>",
            target.toXml(),
        )

        target.editByPath(listOf(0, 1, 0, 0), listOf(0, 1, 0, 0), text { "a" })
        assertEquals(
            "<doc><tc><p><tn>aXb!</tn><tn>cd</tn></p><p><tn>aq</tn></p></tc></doc>",
            target.toXml(),
        )

        target.editByPath(listOf(0, 1, 0, 2), listOf(0, 1, 0, 2), text { "B" })
        assertEquals(
            "<doc><tc><p><tn>aXb!</tn><tn>cd</tn></p><p><tn>aqB</tn></p></tc></doc>",
            target.toXml(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            target.editByPath(listOf(0, 0, 4), listOf(0, 0, 4), element("tn"))
        }
    }

    @Test
    fun `should be able to init with attributes`() {
        val root = element("doc") {
            element("p") {
                element("span") {
                    attrs {
                        mapOf("bold" to true, "italic" to false)
                    }
                    text { "hello" }
                }
            }
        }
        val target = JsonTree(
            DummyContext,
            CrdtTree(JsonTree.buildRoot(root, DummyContext), InitialTimeTicket),
        )
        assertEquals(
            """<doc><p><span bold="true" italic="false">hello</span></p></doc>""",
            target.toXml(),
        )
    }

    @Test
    fun `should be able to style with index`() {
        val target = createTreeWithStyle()
        assertEquals(
            """<doc><tc><p a="b"><tn></tn></p></tc></doc>""",
            target.toXml(),
        )

        target.style(1, 2, mapOf("c" to "d"))
        assertEquals(
            """<doc><tc><p a="b" c="d"><tn></tn></p></tc></doc>""",
            target.toXml(),
        )

        target.style(1, 2, mapOf("c" to "q"))
        assertEquals(
            """<doc><tc><p a="b" c="q"><tn></tn></p></tc></doc>""",
            target.toXml(),
        )

        target.style(2, 3, mapOf("z" to "m"))
        assertEquals(
            """<doc><tc><p a="b" c="q"><tn z="m"></tn></p></tc></doc>""",
            target.toXml(),
        )
    }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun `should be able to style with path`() {
        val target = createTreeWithStyle()
        assertEquals(
            """<doc><tc><p a="b"><tn></tn></p></tc></doc>""",
            target.toXml(),
        )

        target.styleByPath(listOf(0, 0), mapOf("c" to "d"))
        assertEquals(
            """<doc><tc><p a="b" c="d"><tn></tn></p></tc></doc>""",
            target.toXml(),
        )

        target.styleByPath(listOf(0, 0), mapOf("c" to "q"))
        assertEquals(
            """<doc><tc><p a="b" c="q"><tn></tn></p></tc></doc>""",
            target.toXml(),
        )

        target.styleByPath(listOf(0, 0, 0), mapOf("z" to "m"))
        assertEquals(
            """<doc><tc><p a="b" c="q"><tn z="m"></tn></p></tc></doc>""",
            target.toXml(),
        )

        assertEquals(
            """{"type":"doc","children":[{"type":"tc","children":[{"type":"p","children":[{"type":"tn","children":[{"type":"text","value":""}],"attributes":{"z":"m"}}],"attributes":{"a":"b","c":"q"}}]}]}""",
            target.toJson(),
        )
    }

    @Test
    fun `should emit Tree edit operations accordingly when edited with index`() = runTest {
        val document = Document(Document.Key(""))
        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        text { "ab" }
                    }
                },
            )
        }.await()
        assertEquals("<doc><p>ab</p></doc>", document.getRoot().tree().toXml())

        val actualOperationDeferred = async(UnconfinedTestDispatcher()) {
            document.events("$.t")
                .filterIsInstance<LocalChange>()
                .flatMapConcat { it.changeInfo.operations.asFlow() }
                .filter { it is TreeEditOpInfo || it is TreeStyleOpInfo }
                .take(2)
                .toList()
        }

        document.updateAsync { root, _ ->
            root.tree().edit(1, 1, text { "X" })
            assertEquals("<doc><p>Xab</p></doc>", root.tree().toXml())

            root.tree().style(0, 1, mapOf("a" to "b"))
        }.await()
        assertContentEquals(
            listOf(
                TreeEditOpInfo(
                    1,
                    1,
                    listOf(0, 0),
                    listOf(0, 0),
                    listOf(JsonTree.TextNode("X")),
                    0,
                    "$.t",
                ),
                // TODO(7hong13): need to check whether toPath is correctly passed
                TreeStyleOpInfo(
                    0,
                    1,
                    listOf(0),
                    listOf(0, 0),
                    mapOf("a" to "b"),
                    "$.t",
                ),
            ),
            actualOperationDeferred.await(),
        )
    }

    @Test
    fun `should emit Tree edit operations accordingly when edited with path`() = runTest {
        val document = Document(Document.Key(""))
        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("tc") {
                        element("p") {
                            element("tn") {
                                text { "ab" }
                            }
                        }
                    }
                },
            )
        }.await()
        assertEquals(
            "<doc><tc><p><tn>ab</tn></p></tc></doc>",
            document.getRoot().tree().toXml(),
        )

        val actualOperationDeferred = async(UnconfinedTestDispatcher()) {
            document.events("$.t")
                .filterIsInstance<LocalChange>()
                .flatMapConcat { it.changeInfo.operations.asFlow() }
                .filter { it is TreeEditOpInfo || it is TreeStyleOpInfo }
                .take(2)
                .toList()
        }

        document.updateAsync { root, _ ->
            root.tree().editByPath(listOf(0, 0, 0, 1), listOf(0, 0, 0, 1), text { "X" })
            assertEquals(
                "<doc><tc><p><tn>aXb</tn></p></tc></doc>",
                root.tree().toXml(),
            )

            root.tree().styleByPath(listOf(0, 0, 0), mapOf("a" to "b"))
        }.await()

        assertContentEquals(
            listOf(
                TreeEditOpInfo(
                    4,
                    4,
                    listOf(0, 0, 0, 1),
                    listOf(0, 0, 0, 1),
                    listOf(JsonTree.TextNode("X")),
                    0,
                    "$.t",
                ),
                // TODO(7hong13): need to check whether toPath is correctly passed
                TreeStyleOpInfo(
                    2,
                    3,
                    listOf(0, 0, 0),
                    listOf(0, 0, 0, 0),
                    mapOf("a" to "b"),
                    "$.t",
                ),
            ),
            actualOperationDeferred.await(),
        )
    }

    @Test
    fun `should find pos range from index range and vice versa`() = runTest {
        val document = Document(Document.Key(""))
        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("root") {
                    element("p") {
                        element("b") {
                            element("i") {
                                text { "ab" }
                            }
                        }
                    }
                },
            )
        }.await()
        assertEquals(
            """<root><p><b><i>ab</i></b></p></root>""",
            document.getRoot().tree().toXml(),
        )

        val tree = document.getRoot().tree()
        var posRange = tree.indexRangeToPosRange(0 to 5)
        assertEquals(0 to 5, tree.posRangeToIndexRange(posRange))

        posRange = tree.indexRangeToPosRange(5 to 7)
        assertEquals(5 to 7, tree.posRangeToIndexRange(posRange))
    }

    @Test
    fun `should find pos range from path and vice versa`() = runTest {
        val document = Document(Document.Key(""))
        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("root") {
                    element("p") {
                        element("b") {
                            element("i") {
                                text { "ab" }
                            }
                        }
                    }
                },
            )
        }.await()
        assertEquals(
            """<root><p><b><i>ab</i></b></p></root>""",
            document.getRoot().tree().toXml(),
        )

        val tree = document.getRoot().tree()
        var range = tree.pathRangeToPosRange(listOf(0) to listOf(0, 0, 0, 2))
        assertEquals(listOf(0) to listOf(0, 0, 0, 2), tree.posRangeToPathRange(range))

        range = tree.pathRangeToPosRange(listOf(0) to listOf(1))
        assertEquals(listOf(0) to listOf(1), tree.posRangeToPathRange(range))
    }

    @Test
    fun `should insert multiple text nodes`() = runTest {
        val document = Document(Document.Key(""))
        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        text { "ab" }
                    }
                },
            )
        }.await()
        assertEquals("<doc><p>ab</p></doc>", document.getRoot().tree().toXml())

        document.updateAsync { root, _ ->
            root.tree().edit(3, 3, text { "c" }, text { "d" })
        }.await()
        assertEquals("<doc><p>abcd</p></doc>", document.getRoot().tree().toXml())
    }

    @Test
    fun `should insert multiple element nodes`() = runTest {
        val document = Document(Document.Key(""))
        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        text { "ab" }
                    }
                },
            )
        }.await()
        assertEquals("<doc><p>ab</p></doc>", document.getRoot().tree().toXml())

        document.updateAsync { root, _ ->
            root.tree().edit(
                4,
                4,
                element("p") { text { "cd" } },
                element("i") { text { "fg" } },
            )
        }.await()
        assertEquals("<doc><p>ab</p><p>cd</p><i>fg</i></doc>", document.getRoot().tree().toXml())
    }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun `should edit content with path when multi tree nodes passed`() = runTest {
        val document = Document(Document.Key(""))
        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("tc") {
                        element("p") {
                            element("tn") {
                                text { "ab" }
                            }
                        }
                    }
                },
            )
            assertEquals(
                "<doc><tc><p><tn>ab</tn></p></tc></doc>",
                document.getRoot().tree().toXml(),
            )

            root.tree().editByPath(
                listOf(0, 0, 0, 1),
                listOf(0, 0, 0, 1),
                text { "X" },
                text { "X" },
            )
            assertEquals(
                "<doc><tc><p><tn>aXXb</tn></p></tc></doc>",
                document.getRoot().tree().toXml(),
            )

            root.tree().editByPath(
                listOf(0, 1),
                listOf(0, 1),
                element("p") {
                    element("tn") {
                        text { "te" }
                        text { "st" }
                    }
                },
                element("p") {
                    element("tn") {
                        text { "te" }
                        text { "xt" }
                    }
                },
            )
            assertEquals(
                "<doc><tc><p><tn>aXXb</tn></p><p><tn>test</tn></p><p><tn>text</tn></p></tc></doc>",
                document.getRoot().tree().toXml(),
            )

            root.tree().editByPath(
                listOf(0, 3),
                listOf(0, 3),
                element("p") {
                    element("tn") {
                        text { "te" }
                        text { "st" }
                    }
                },
                element("tn") {
                    text { "te" }
                    text { "xt" }
                },
            )
            assertEquals(
                "<doc><tc><p><tn>aXXb</tn></p><p><tn>test</tn></p><p><tn>text</tn></p><p><tn>test</tn></p><tn>text</tn></tc></doc>",
                document.getRoot().tree().toXml(),
            )
        }.await()
    }

    @Test
    fun `should delete the first text with tombstone in front of target text`() = runTest {
        val document = Document(Document.Key(""))
        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree("t").edit(
                0,
                0,
                element("p") { text { "abcdefghi" } },
            )
            assertEquals("<root><p>abcdefghi</p></root>", document.getRoot().tree().toXml())

            root.tree().edit(1, 1, text { "12345" })
            assertEquals("<root><p>12345abcdefghi</p></root>", root.tree().toXml())

            root.tree().edit(2, 5)
            assertEquals("<root><p>15abcdefghi</p></root>", root.tree().toXml())

            root.tree().edit(3, 5)
            assertEquals("<root><p>15cdefghi</p></root>", root.tree().toXml())

            root.tree().edit(2, 4)
            assertEquals("<root><p>1defghi</p></root>", root.tree().toXml())

            root.tree().edit(1, 3)
            assertEquals("<root><p>efghi</p></root>", root.tree().toXml())

            root.tree().edit(1, 2)
            assertEquals("<root><p>fghi</p></root>", root.tree().toXml())

            root.tree().edit(2, 5)
            assertEquals("<root><p>f</p></root>", root.tree().toXml())

            root.tree().edit(1, 2)
            assertEquals("<root><p></p></root>", root.tree().toXml())
        }.await()
    }

    @Test
    fun `should delete node with a text node in front whose size is bigger than 1`() = runTest {
        val document = Document(Document.Key(""))
        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree("t").edit(
                0,
                0,
                element("p") { text { "abcde" } },
            )
            assertEquals("<root><p>abcde</p></root>", document.getRoot().tree().toXml())

            root.tree().edit(6, 6, text { "f" })
            assertEquals("<root><p>abcdef</p></root>", root.tree().toXml())

            root.tree().edit(7, 7, text { "g" })
            assertEquals("<root><p>abcdefg</p></root>", root.tree().toXml())

            root.tree().edit(7, 8)
            assertEquals("<<root><p>abcdef</p></root>", root.tree().toXml())

            root.tree().edit(6, 7)
            assertEquals("<<root><p>abcde</p></root>", root.tree().toXml())

            root.tree().edit(5, 6)
            assertEquals("<<root><p>abcd</p></root>", root.tree().toXml())

            root.tree().edit(4, 5)
            assertEquals("<<root><p>abc</p></root>", root.tree().toXml())

            root.tree().edit(3, 4)
            assertEquals("<<root><p>ab</p></root>", root.tree().toXml())

            root.tree().edit(2, 3)
            assertEquals("<<root><p>a</p></root>", root.tree().toXml())

            root.tree().edit(1, 2)
            assertEquals("<<root><p></p></root>", root.tree().toXml())
        }.await()
    }

    @Test
    fun `should delete nodes correctly in a multi-level range`() = runTest {
        val document = Document(Document.Key(""))
        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        text { "ab" }
                        element("p") {
                            text { "x" }
                        }
                    }
                    element("p") {
                        element("p") {
                            text { "cd" }
                        }
                    }
                    element("p") {
                        element("p") {
                            text { "y" }
                        }
                        text { "ef" }
                    }
                },
            )
            assertEquals(
                "<doc><p>ab<p>x</p></p><p><p>cd</p></p><p><p>y</p>ef</p></doc>",
                document.getRoot().tree().toXml(),
            )

            root.tree().edit(2, 18)
            assertEquals("<doc><p>af</p></doc>", root.tree().toXml())
        }.await()
    }

    companion object {
        private val DummyContext = ChangeContext(
            ChangeID.InitialChangeID,
            CrdtRoot(CrdtObject(InitialTimeTicket)),
        )

        private val rootCrdtTree: CrdtTree
            get() = CrdtTree(rootCrdtTreeNode, InitialTimeTicket)

        private val rootCrdtTreeNode: CrdtTreeNode
            get() = CrdtTreeElement(CrdtTreeNodeID(InitialTimeTicket, 0), DEFAULT_ROOT_TYPE)

        private fun createTreeWithStyle(): JsonTree {
            val root = element("doc") {
                element("tc") {
                    element("p") {
                        element("tn") {
                            text { "" }
                        }
                        attr { "a" to "b" }
                    }
                }
            }
            return JsonTree(
                DummyContext,
                CrdtTree(JsonTree.buildRoot(root, DummyContext), InitialTimeTicket),
            )
        }
    }
}
