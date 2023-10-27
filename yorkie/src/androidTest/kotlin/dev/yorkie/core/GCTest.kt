package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.JsonTree.TextNode
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.gson
import dev.yorkie.util.IndexTreeNode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class GCTest {

    @Test
    fun test_gc() {
        runBlocking {
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            assertEquals("{}", document.toJson())

            document.updateAsync { root, _ ->
                root["1"] = 1
                root.setNewArray("2").apply {
                    put(1)
                    put(2)
                    put(3)
                }
                root["3"] = 3
            }.await()
            assertJsonContentEquals("""{"1":1,"2":[1,2,3],"3":3}""", document.toJson())

            document.updateAsync { root, _ ->
                root.remove("2")
            }.await()
            assertJsonContentEquals("""{"1":1,"3":3}""", document.toJson())
            assertEquals(4, document.garbageLength)
            assertEquals(4, document.garbageCollect(TimeTicket.MaxTimeTicket))
            assertEquals(0, document.garbageLength)
        }
    }

    @Test
    fun test_gc_with_text() {
        runBlocking {
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            assertEquals("{}", document.toJson())

            document.updateAsync { root, _ ->
                root.setNewText("text").apply {
                    edit(0, 0, "ABCD")
                    edit(0, 2, "12")
                }
            }.await()

            assertJsonContentEquals(
                """{"text":[{"val":"12"},{"val":"CD"}]}""",
                document.toJson(),
            )
            assertEquals(1, document.garbageLength)
            assertEquals(1, document.garbageCollect(TimeTicket.MaxTimeTicket))
            assertEquals(0, document.garbageLength)
        }
    }

    @Test
    fun test_gc_with_text_with_attributes() {
        runBlocking {
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            assertEquals("{}", document.toJson())

            document.updateAsync { root, _ ->
                root.setNewText("text").apply {
                    edit(0, 0, "Hello world", mapOf("b" to "1"))
                    edit(6, 11, "mario")
                }
            }.await()
            assertJsonContentEquals(
                """{"text":[{"attrs":{"b":"1"},"val":"Hello "},{"val":"mario"}]}""",
                document.toJson(),
            )
            assertEquals(1, document.garbageLength)

            document.updateAsync { root, _ ->
                val text = root.getAs<JsonText>("text")
                text.edit(0, 5, "Hi", mapOf("b" to "1"))
                text.edit(3, 4, "j")
                text.edit(4, 8, "ane", mapOf("b" to "1"))
            }.await()
            assertJsonContentEquals(
                """{"text":[{"attrs":{"b":"1"},"val":"Hi"},{"attrs":{"b":"1"},"val":" "},
                    |{"val":"j"},{"attrs":{"b":"1"},"val":"ane"}]}
                """.trimMargin(),
                document.toJson(),
            )
            assertEquals(4, document.garbageLength)
            assertEquals(4, document.garbageCollect(TimeTicket.MaxTimeTicket))
            assertEquals(0, document.garbageLength)
        }
    }

    @Test
    fun test_gc_with_tree() {
        runBlocking {
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)
            assertEquals("{}", document.toJson())

            document.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") {
                            element("tn") {
                                text { "a" }
                                text { "b" }
                            }
                            element("tn") {
                                text { "cd" }
                            }
                        }
                    },
                ).apply {
                    editByPath(listOf(0, 0, 0), listOf(0, 0, 2), TextNode("gh"))
                }
            }.await()
            assertEquals(
                "<doc><p><tn>gh</tn><tn>cd</tn></p></doc>",
                document.getRoot().getAs<JsonTree>("t").toXml(),
            )

            // [text(a), text(b)]
            var nodeLengthBeforeGC =
                getNodeLength(document.getRoot().getAs<JsonTree>("t").indexTree.root)
            assertEquals(2, document.garbageLength)
            assertEquals(2, document.garbageCollect(TimeTicket.MaxTimeTicket))
            assertEquals(0, document.garbageLength)
            var nodeLengthAfterGC =
                getNodeLength(document.getRoot().getAs<JsonTree>("t").indexTree.root)
            assertEquals(2, nodeLengthBeforeGC - nodeLengthAfterGC)

            document.updateAsync { root, _ ->
                root.getAs<JsonTree>("t").editByPath(
                    listOf(0, 0, 0),
                    listOf(0, 0, 2),
                    text { "cv" },
                )
            }.await()
            assertEquals(
                "<doc><p><tn>cv</tn><tn>cd</tn></p></doc>",
                document.getRoot().getAs<JsonTree>("t").toXml(),
            )

            // [text(gh)]
            nodeLengthBeforeGC =
                getNodeLength(document.getRoot().getAs<JsonTree>("t").indexTree.root)
            assertEquals(1, document.garbageLength)
            assertEquals(1, document.garbageCollect(TimeTicket.MaxTimeTicket))
            assertEquals(0, document.garbageLength)
            nodeLengthAfterGC =
                getNodeLength(document.getRoot().getAs<JsonTree>("t").indexTree.root)
            assertEquals(1, nodeLengthBeforeGC - nodeLengthAfterGC)

            document.updateAsync { root, _ ->
                root.getAs<JsonTree>("t").editByPath(
                    listOf(0),
                    listOf(1),
                    element("p") { element("tn") { text { "ab" } } },
                )
            }.await()
            assertEquals(
                "<doc><p><tn>ab</tn></p></doc>",
                document.getRoot().getAs<JsonTree>("t").toXml(),
            )

            // [p, tn, tn, text(cv), text(cd)]
            nodeLengthBeforeGC =
                getNodeLength(document.getRoot().getAs<JsonTree>("t").indexTree.root)

            assertEquals(5, document.garbageLength)
            assertEquals(5, document.garbageCollect(TimeTicket.MaxTimeTicket))
            assertEquals(0, document.garbageLength)
            nodeLengthAfterGC =
                getNodeLength(document.getRoot().getAs<JsonTree>("t").indexTree.root)
            assertEquals(5, nodeLengthBeforeGC - nodeLengthAfterGC)
        }
    }

    @Test
    fun test_gc_with_tree_for_multi_client() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") {
                            element("tn") {
                                text { "a" }
                                text { "b" }
                            }
                            element("tn") {
                                text { "cd" }
                            }
                        }
                    },
                )
            }.await()
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)

            // (0, 0) -> (1, 0): syncedseqs:(0, 0)
            c1.syncAsync().await()

            // (1, 0) -> (1, 1): syncedseqs:(0, 0)
            c2.syncAsync().await()

            d2.updateAsync { root, _ ->
                root.getAs<JsonTree>("t").editByPath(
                    listOf(0, 0, 0),
                    listOf(0, 0, 2),
                    text { "gh" },
                )
            }.await()
            assertEquals(0, d1.garbageLength)
            assertEquals(2, d2.garbageLength)

            // (1, 1) -> (1, 2): syncedseqs:(0, 1)
            c2.syncAsync().await()
            assertEquals(0, d1.garbageLength)
            assertEquals(2, d2.garbageLength)

            // (1, 2) -> (2, 2): syncedseqs:(1, 1)
            c1.syncAsync().await()
            assertEquals(2, d1.garbageLength)
            assertEquals(2, d2.garbageLength)

            // (2, 2) -> (2, 2): syncedseqs:(1, 2)
            c2.syncAsync().await()
            assertEquals(2, d1.garbageLength)
            assertEquals(2, d2.garbageLength)

            // (2, 2) -> (2, 2): syncedseqs:(2, 2): meet GC condition
            c1.syncAsync().await()
            assertEquals(0, d1.garbageLength)
            assertEquals(2, d2.garbageLength)

            // (2, 2) -> (2, 2): syncedseqs:(2, 2): meet GC condition
            c2.syncAsync().await()
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)
        }
    }

    @Test
    fun test_gc_with_container_type_for_multi_client() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root["1"] = 1
                root.setNewArray("2").apply {
                    put(1)
                    put(2)
                    put(3)
                }
                root["3"] = 3
            }.await()
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)

            // (0, 0) -> (1, 0): syncedseqs:(0, 0)
            c1.syncAsync().await()

            // (1, 0) -> (1, 1): syncedseqs:(0, 0)
            c2.syncAsync().await()

            d2.updateAsync { root, _ ->
                root.remove("2")
            }.await()
            assertEquals(0, d1.garbageLength)
            assertEquals(4, d2.garbageLength)

            // (1, 1) -> (1, 2): syncedseqs:(0, 1)
            c2.syncAsync().await()
            assertEquals(0, d1.garbageLength)
            assertEquals(4, d2.garbageLength)

            // (1, 2) -> (2, 2): syncedseqs:(1, 1)
            c1.syncAsync().await()
            assertEquals(4, d1.garbageLength)
            assertEquals(4, d2.garbageLength)

            // (2, 2) -> (2, 2): syncedseqs:(1, 2)
            c2.syncAsync().await()
            assertEquals(4, d1.garbageLength)
            assertEquals(4, d2.garbageLength)

            // (2, 2) -> (2, 2): syncedseqs:(2, 2): meet GC condition
            c1.syncAsync().await()
            assertEquals(0, d1.garbageLength)
            assertEquals(4, d2.garbageLength)

            // (2, 2) -> (2, 2): syncedseqs:(2, 2): meet GC condition
            c2.syncAsync().await()
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)
        }
    }

    @Test
    fun test_gc_with_text_for_multi_client() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "Hello World")
                root.setNewText("textWithAttr").edit(0, 0, "Hello World")
            }.await()
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)

            // (0, 0) -> (1, 0): syncedseqs:(0, 0)
            c1.syncAsync().await()

            // (1, 0) -> (1, 1): syncedseqs:(0, 0)
            c2.syncAsync().await()

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("text").apply {
                    edit(0, 1, "a")
                    edit(1, 2, "b")
                }
                root.getAs<JsonText>("textWithAttr").edit(
                    0,
                    1,
                    "a",
                    mapOf("b" to "1"),
                )
            }.await()
            assertEquals(0, d1.garbageLength)
            assertEquals(3, d2.garbageLength)

            // (1, 1) -> (1, 2): syncedseqs:(0, 1)
            c2.syncAsync().await()
            assertEquals(0, d1.garbageLength)
            assertEquals(3, d2.garbageLength)

            // (1, 2) -> (2, 2): syncedseqs:(1, 1)
            c1.syncAsync().await()
            assertEquals(3, d1.garbageLength)
            assertEquals(3, d2.garbageLength)

            // (2, 2) -> (2, 2): syncedseqs:(1, 2)
            c2.syncAsync().await()
            assertEquals(3, d1.garbageLength)
            assertEquals(3, d2.garbageLength)

            // (2, 2) -> (2, 2): syncedseqs:(2, 2): meet GC condition
            c1.syncAsync().await()
            assertEquals(0, d1.garbageLength)
            assertEquals(3, d2.garbageLength)

            // (2, 2) -> (2, 2): syncedseqs:(2, 2): meet GC condition
            c2.syncAsync().await()
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)
        }
    }

    @Test
    fun test_gc_with_detached_document() {
        withTwoClientsAndDocuments(
            detachDocuments = false,
            realTimeSync = false,
        ) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root["1"] = 1
                root.setNewArray("2").apply {
                    put(1)
                    put(2)
                    put(3)
                }
                root["3"] = 3
                root.setNewText("4").edit(0, 0, "hi")
                root.setNewText("5").edit(0, 0, "hi")
            }.await()
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)

            // (0, 0) -> (1, 0): syncedseqs:(0, 0)
            c1.syncAsync().await()

            // (1, 0) -> (1, 1): syncedseqs:(0, 0)
            c2.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.remove("2")
                root.getAs<JsonText>("4").edit(0, 1, "h")
                root.getAs<JsonText>("5").edit(
                    0,
                    1,
                    "h",
                    mapOf("b" to "1"),
                )
            }.await()
            assertEquals(6, d1.garbageLength)
            assertEquals(0, d2.garbageLength)

            // (1, 1) -> (2, 1): syncedseqs:(1, 0)
            c1.syncAsync().await()
            assertEquals(6, d1.garbageLength)
            assertEquals(0, d2.garbageLength)

            c2.detachAsync(d2).await()

            // (2, 1) -> (2, 2): syncedseqs:(1, x)
            c2.syncAsync().await()
            assertEquals(6, d1.garbageLength)
            assertEquals(6, d2.garbageLength)

            // (2, 2) -> (2, 2): syncedseqs:(2, x): meet GC condition
            c1.syncAsync().await()
            assertEquals(0, d1.garbageLength)
            assertEquals(6, d2.garbageLength)

            c1.detachAsync(d1).await()
        }
    }

    @Test
    fun test_disable_gc() = runBlocking {
        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document = Document(documentKey, Document.Options(disableGC = true))
        document.updateAsync { root, _ ->
            root["1"] = 1
            root.setNewArray("2").apply {
                put(1)
                put(2)
                put(3)
            }
            root["3"] = 3
        }.await()
        assertJsonContentEquals("""{"1":1,"2":[1,2,3],"3":3}""", document.toJson())

        document.updateAsync { root, _ ->
            root.remove("2")
        }.await()
        assertJsonContentEquals("""{"1":1, "3":3}""", document.toJson())
        assertEquals(4, document.garbageLength)
        assertEquals(0, document.garbageCollect(TimeTicket.MaxTimeTicket))
        assertEquals(4, document.garbageLength)
    }

    @Test
    fun test_collecting_removed_elements_from_root_and_clone() = runBlocking {
        data class Point(val x: Int, val y: Int)

        val client = createClient()
        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document = Document(documentKey)

        client.activateAsync().await()
        client.attachAsync(document, isRealTimeSync = false).await()

        document.updateAsync { root, _ ->
            root["point"] = gson.toJson(Point(0, 0))
        }.await()

        document.updateAsync { root, _ ->
            root["point"] = gson.toJson(Point(1, 1))
        }.await()

        document.updateAsync { root, _ ->
            root["point"] = gson.toJson(Point(2, 2))
        }.await()

        document.updateAsync { root, _ ->
            root.remove("point")
        }.await()

        assertEquals(3, document.garbageLength)
        assertEquals(3, document.garbageLengthFromClone)
    }

    private fun getNodeLength(root: IndexTreeNode<CrdtTreeNode>): Int {
        return root.allChildren.fold(root.allChildren.size) { acc, child ->
            acc + getNodeLength(child)
        }
    }
}
