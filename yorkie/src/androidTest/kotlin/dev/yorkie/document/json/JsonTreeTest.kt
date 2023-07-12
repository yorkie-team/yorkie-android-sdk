package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.Document
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class JsonTreeTest {

    @Test
    fun test_tree_sync_between_replicas() {
        runBlocking {
            withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
                updateAndSync(
                    Updater(c1, d1) { root ->
                        root.setNewTree(
                            "t",
                            element("doc") {
                                element("p") {
                                    text { "hello" }
                                }
                            },
                        )
                    },
                    Updater(c2, d2),
                )
                assertTreesXmlEquals("<doc><p>hello</p></doc>", d1, d2)

                updateAndSync(
                    Updater(c1, d1) { root ->
                        root.rootTree().edit(
                            7,
                            7,
                            element("p") {
                                text { "yorkie" }
                            },
                        )
                    },
                    Updater(c2, d2),
                )
                assertTreesXmlEquals("<doc><p>hello</p><p>yorkie</p></doc>", d1, d2)
            }
        }
    }

    @Test
    fun test_inserting_text_to_same_position_concurrently() {
        runBlocking {
            withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
                updateAndSync(
                    Updater(c1, d1) { root ->
                        root.setNewTree(
                            "t",
                            element("r") {
                                element("p") {
                                    text { "12" }
                                }
                            },
                        )
                    },
                    Updater(c2, d2),
                )
                assertTreesXmlEquals("<r><p>12</p></r>", d1, d2)

                // TODO: add inserting on the leftmost after tree is fixed

                updateAndSync(
                    Updater(c1, d1) { root ->
                        root.rootTree().edit(2, 2, text { "A" })
                    },
                    Updater(c2, d2) { root ->
                        root.rootTree().edit(2, 2, text { "B" })
                    },
                ) {
                    assertEquals("<r><p>1A2</p></r>", d1.getRoot().rootTree().toXml())
                    assertEquals("<r><p>1B2</p></r>", d2.getRoot().rootTree().toXml())
                }
                assertTreesXmlEquals("<r><p>1BA2</p></r>", d1, d2)

                updateAndSync(
                    Updater(c1, d1) { root ->
                        root.rootTree().edit(5, 5, text { "C" })
                    },
                    Updater(c2, d2) { root ->
                        root.rootTree().edit(5, 5, text { "D" })
                    },
                ) {
                    assertEquals("<r><p>1BA2C</p></r>", d1.getRoot().rootTree().toXml())
                    assertEquals("<r><p>1BA2D</p></r>", d2.getRoot().rootTree().toXml())
                }
                assertTreesXmlEquals("<r><p>1BA2DC</p></r>", d1, d2)
            }
        }
    }

    @Test
    fun test_tree_with_attributes_between_replicas() {
        runBlocking {
            withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
                updateAndSync(
                    Updater(c1, d1) { root ->
                        root.setNewTree(
                            "t",
                            element("doc") {
                                element("p") {
                                    text { "hello" }
                                    attr { "italic" to true }
                                }
                            },
                        )
                    },
                    Updater(c2, d2),
                )
                assertTreesXmlEquals("""<doc><p italic="true">hello</p></doc>""", d1, d2)

                updateAndSync(
                    Updater(c1, d1) { root ->
                        root.rootTree().style(6, 7, mapOf("bold" to "true"))
                    },
                    Updater(c2, d2),
                )
                assertTreesXmlEquals(
                    """<doc><p italic="true" bold="true">hello</p></doc>""",
                    d1,
                    d2,
                )
            }
        }
    }

    companion object {

        fun JsonObject.rootTree() = getAs<JsonTree>("t")

        suspend fun assertTreesXmlEquals(expected: String, vararg documents: Document) {
            documents.forEach {
                assertEquals(expected, it.getRoot().rootTree().toXml())
            }
        }

        suspend fun updateAndSync(
            updater1: Updater,
            updater2: Updater,
            beforeSync: (suspend () -> Unit)? = null,
        ) {
            val (c1, d1, d1Updater) = updater1
            val (c2, d2, d2Updater) = updater2

            listOfNotNull(
                d1Updater?.let { d1.updateAsync(updater = it) },
                d2Updater?.let { d2.updateAsync(updater = it) },
            ).awaitAll()

            beforeSync?.invoke()

            if (d1Updater != null) {
                c1.syncAsync().await()
                c2.syncAsync().await()
            }
            if (d2Updater != null) {
                if (d1Updater == null) {
                    c2.syncAsync().await()
                }
                c1.syncAsync().await()
            }
        }

        data class Updater(
            val client: Client,
            val document: Document,
            val updater: (suspend (JsonObject) -> Unit)? = null,
        )
    }
}
