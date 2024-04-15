package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.reflect.TypeToken
import dev.yorkie.TreeBasicTest
import dev.yorkie.TreeTest
import dev.yorkie.core.Client
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.GENERAL_TIMEOUT
import dev.yorkie.core.Presence
import dev.yorkie.core.createClient
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.LocalChange
import dev.yorkie.document.Document.Event.RemoteChange
import dev.yorkie.document.json.JsonTree.ElementNode
import dev.yorkie.document.json.JsonTree.TreeNode
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.operation.OperationInfo.SetOpInfo
import dev.yorkie.document.operation.OperationInfo.TreeEditOpInfo
import dev.yorkie.gson
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@TreeTest
@TreeBasicTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class JsonTreeTest {

    @Test
    fun test_tree_sync_between_replicas() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
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
                Updater(c1, d1) { root, _ ->
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

    @Test
    fun test_inserting_text_to_same_position_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
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

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, text { "A" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, text { "B" })
                },
            ) {
                assertEquals("<r><p>1A2</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>1B2</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>1BA2</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(5, 5, text { "C" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(5, 5, text { "D" })
                },
            ) {
                assertEquals("<r><p>1BA2C</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>1BA2D</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>1BA2DC</p></r>", d1, d2)
        }
    }

    @Test
    fun test_tree_with_attributes_between_replicas() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
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
                Updater(c1, d1) { root, _ ->
                    root.rootTree().style(0, 1, mapOf("bold" to "true"))
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

    @Test
    fun test_deleting_overlapping_elements_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p")
                            element("i")
                            element("b")
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p></p><i></i><b></b></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(0, 4)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 6)
                },
            ) {
                assertEquals("<r><b></b></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r></r>", d1, d2)
        }
    }

    @Test
    fun test_deleting_overlapping_text_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "abcd" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>abcd</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 4)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 5)
                },
            ) {
                assertEquals("<r><p>d</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>a</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_and_delete_contained_elements_of_the_same_depth_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "1234" }
                            }
                            element("p") {
                                text { "abcd" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>1234</p><p>abcd</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(6, 6, element("p"))
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(0, 12)
                },
            ) {
                assertEquals(
                    "<r><p>1234</p><p></p><p>abcd</p></r>",
                    d1.getRoot().rootTree().toXml(),
                )
                assertEquals("<r></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_multiple_insert_and_delete_contained_elements_of_the_same_depth_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "1234" }
                            }
                            element("p") {
                                text { "abcd" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>1234</p><p>abcd</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(6, 6, element("p"))
                    root.rootTree().edit(8, 8, element("p"))
                    root.rootTree().edit(10, 10, element("p"))
                    root.rootTree().edit(12, 12, element("p"))
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(0, 12)
                },
            ) {
                assertEquals(
                    "<r><p>1234</p><p></p><p></p><p></p><p></p><p>abcd</p></r>",
                    d1.getRoot().rootTree().toXml(),
                )
                assertEquals("<r></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p></p><p></p><p></p><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_detecting_error_when_inserting_and_deleting_contained_elements_at_different_depths() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                element("i")
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p><i></i></p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, element("i"))
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 3)
                },
            ) {
                assertEquals("<r><p><i><i></i></i></p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_deleting_contained_elements_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                element("i") {
                                    text { "1234" }
                                }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p><i>1234</i></p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(0, 8)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 7)
                },
            ) {
                assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_and_delete_contained_text_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "1234" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>1234</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 5)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, text { "a" })
                },
            ) {
                assertEquals("<r><p></p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>12a34</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>a</p></r>", d1, d2)
        }
    }

    @Test
    fun test_deleting_contained_text_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "1234" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>1234</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 5)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 4)
                },
            ) {
                assertEquals("<r><p></p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>14</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_and_delete_contained_text_and_elements_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "1234" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>1234</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(0, 6)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, text { "a" })
                },
            ) {
                assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>12a34</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r></r>", d1, d2)
        }
    }

    @Test
    fun test_delete_contained_text_and_elements_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "1234" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>1234</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(0, 6)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 5)
                },
            ) {
                assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_side_by_side_elements_into_right_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p")
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p></p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, element("b"))
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, element("i"))
                },
            ) {
                assertEquals("<r><p></p><b></b></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p></p><i></i></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p></p><i></i><b></b></r>", d1, d2)
        }
    }

    @Test
    fun test_deleting_side_by_side_elements_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                element("b")
                                element("i")
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p><b></b><i></i></p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 3)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 5)
                },
            ) {
                assertEquals("<r><p><i></i></p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p><b></b></p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_text_to_the_same_left_position_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
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

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 1, text { "A" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 1, text { "B" })
                },
            ) {
                assertEquals("<r><p>A12</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>B12</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>BA12</p></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_text_to_the_same_middle_position_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
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

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, text { "A" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, text { "B" })
                },
            ) {
                assertEquals("<r><p>1A2</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>1B2</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>1BA2</p></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_text_to_the_same_right_position_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
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

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 3, text { "A" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, text { "B" })
                },
            ) {
                assertEquals("<r><p>12A</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>12B</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>12BA</p></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_and_delete_side_by_side_text_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "1234" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>1234</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 3, text { "a" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 5)
                },
            ) {
                assertEquals("<r><p>12a34</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>12</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>12a</p></r>", d1, d2)
        }
    }

    @Test
    fun test_delete_and_insert_side_by_side_text_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "1234" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>1234</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 3, text { "a" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 3)
                },
            ) {
                assertEquals("<r><p>12a34</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>34</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>a34</p></r>", d1, d2)
        }
    }

    @Test
    fun test_delete_side_by_side_text_blocks_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "1234" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>1234</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 5)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 3)
                },
            ) {
                assertEquals("<r><p>12</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>34</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_delete_text_content_at_the_same_left_position_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "123" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>123</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 2)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 2)
                },
            ) {
                assertEquals("<r><p>23</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>23</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>23</p></r>", d1, d2)
        }
    }

    @Test
    fun test_delete_text_content_at_the_same_middle_position_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "123" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>123</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 3)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 3)
                },
            ) {
                assertEquals("<r><p>13</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>13</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>13</p></r>", d1, d2)
        }
    }

    @Test
    fun test_delete_text_content_at_the_same_right_position_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "123" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>123</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 4)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 4)
                },
            ) {
                assertEquals("<r><p>12</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>12</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>12</p></r>", d1, d2)
        }
    }

    @Test
    fun test_delete_text_content_anchored_to_another_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "123" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>123</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 2)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 3)
                },
            ) {
                assertEquals("<r><p>23</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>13</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>3</p></r>", d1, d2)
        }
    }

    @Test
    fun test_producing_complete_deletion_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "123" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>123</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 2)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 4)
                },
            ) {
                assertEquals("<r><p>23</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>1</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_handling_block_delete_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "12345" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>12345</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 3)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(4, 6)
                },
            ) {
                assertEquals("<r><p>345</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>123</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>3</p></r>", d1, d2)
        }
    }

    @Test
    fun test_handling_insertion_within_block_delete_concurrently_case1() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "12345" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>12345</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 5)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, text { "B" })
                },
            ) {
                assertEquals("<r><p>15</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>12B345</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>1B5</p></r>", d1, d2)
        }
    }

    @Test
    fun test_handling_insertion_within_block_delete_concurrently_case2() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "12345" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>12345</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 6)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, text { "a" }, text { "bc" })
                },
            ) {
                assertEquals("<r><p>1</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>12abc345</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>1abc</p></r>", d1, d2)
        }
    }

    @Test
    fun test_handling_block_element_insertion_within_deletion() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "1234" }
                            }
                            element("p") {
                                text { "5678" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>1234</p><p>5678</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(0, 12)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(
                        6,
                        6,
                        element("p") { text { "cd" } },
                        element("i") { text { "fg" } },
                    )
                },
            ) {
                assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
                assertEquals(
                    "<r><p>1234</p><p>cd</p><i>fg</i><p>5678</p></r>",
                    d2.getRoot().rootTree().toXml(),
                )
            }
            assertTreesXmlEquals("<r><p>cd</p><i>fg</i></r>", d1, d2)
        }
    }

    @Test
    fun test_handling_concurrent_element_insertion_and_deletion_to_left() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "12345" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>12345</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(0, 7)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(
                        0,
                        0,
                        element("p") { text { "cd" } },
                        element("i") { text { "fg" } },
                    )
                },
            ) {
                assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
                assertEquals(
                    "<r><p>cd</p><i>fg</i><p>12345</p></r>",
                    d2.getRoot().rootTree().toXml(),
                )
            }
            assertTreesXmlEquals("<r><p>cd</p><i>fg</i></r>", d1, d2)
        }
    }

    @Test
    fun test_handling_concurrent_element_insertion_and_deletion_to_right() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "12345" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>12345</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(0, 7)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(
                        7,
                        7,
                        element("p") { text { "cd" } },
                        element("i") { text { "fg" } },
                    )
                },
            ) {
                assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
                assertEquals(
                    "<r><p>12345</p><p>cd</p><i>fg</i></r>",
                    d2.getRoot().rootTree().toXml(),
                )
            }
            assertTreesXmlEquals("<r><p>cd</p><i>fg</i></r>", d1, d2)
        }
    }

    @Test
    fun test_handling_deletion_of_insertion_anchor_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
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

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, text { "A" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 2)
                },
            ) {
                assertEquals("<r><p>1A2</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p>2</p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>A2</p></r>", d1, d2)
        }
    }

    @Test
    fun test_handling_deletion_after_insertion_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
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

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 1, text { "A" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 3)
                },
            ) {
                assertEquals("<r><p>A12</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>A</p></r>", d1, d2)
        }
    }

    @Test
    fun test_handling_deletion_before_insertion_concurrently() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
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

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 3, text { "A" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 3)
                },
            ) {
                assertEquals("<r><p>12A</p></r>", d1.getRoot().rootTree().toXml())
                assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())
            }
            assertTreesXmlEquals("<r><p>A</p></r>", d1, d2)
        }
    }

    @Test
    fun test_whether_split_link_can_be_transmitted_through_rpc() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("doc") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    ).edit(2, 2, text { "1" })
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<doc><p>a1b</p></doc>", d1, d2)

            d2.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, text { "1" })
            }.await()
            assertTreesXmlEquals("<doc><p>a11b</p></doc>", d2)

            d2.updateAsync { root, _ ->
                root.rootTree().apply {
                    edit(2, 3, text { "12" })
                    edit(4, 5, text { "21" })
                }
            }.await()
            assertTreesXmlEquals("<doc><p>a1221b</p></doc>", d2)

            // if split link is not transmitted, then left sibling in from index below, is "b" not "a"
            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 4, text { "123" })
            }.await()
            assertTreesXmlEquals("<doc><p>a12321b</p></doc>", d2)
        }
    }

    @Test
    fun test_calculating_size_of_index_tree() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("doc") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    ).apply {
                        edit(2, 2, text { "123" })
                        edit(2, 2, text { "456" })
                        edit(2, 2, text { "789" })
                        edit(2, 2, text { "0123" })
                    }
                },
                Updater(c2, d2),
            ) {
                assertTreesXmlEquals("<doc><p>a0123789456123b</p></doc>", d1)
            }

            val size = d1.getRoot().rootTree().indexTree.root.size
            assertEquals(size, d2.getRoot().rootTree().indexTree.root.size)
        }
    }

    @Test
    fun test_tree_change_concurrent_delete() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("doc") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            ) {
                assertTreesXmlEquals("<doc><p>ab</p></doc>", d1)
            }

            val ops1 = mutableListOf<SimpleTreeEditOpInfo>()
            val ops2 = mutableListOf<SimpleTreeEditOpInfo>()
            val jobs = listOf(collectTreeEditOpInfos(d1, ops1), collectTreeEditOpInfos(d2, ops2))

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(0, 4)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 2)
                },
            ) {
                assertTreesXmlEquals("<doc></doc>", d1)
                assertTreesXmlEquals("<doc><p>b</p></doc>", d2)
            }
            assertEquals(d1.getRoot().rootTree().toXml(), d2.getRoot().rootTree().toXml())

            assertTreeEditOpInfosEquals(listOf(SimpleTreeEditOpInfo(0, 4)), ops1)
            assertTreeEditOpInfosEquals(
                listOf(SimpleTreeEditOpInfo(1, 2), SimpleTreeEditOpInfo(0, 3)),
                ops2,
            )
            jobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_tree_change_concurrent_delete_and_insert() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("doc") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            ) {
                assertTreesXmlEquals("<doc><p>ab</p></doc>", d1)
            }

            val ops1 = mutableListOf<SimpleTreeEditOpInfo>()
            val ops2 = mutableListOf<SimpleTreeEditOpInfo>()
            val jobs = listOf(collectTreeEditOpInfos(d1, ops1), collectTreeEditOpInfos(d2, ops2))

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 3)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, text { "c" })
                },
            ) {
                assertTreesXmlEquals("<doc><p></p></doc>", d1)
                assertTreesXmlEquals("<doc><p>acb</p></doc>", d2)
            }
            assertEquals(d1.getRoot().rootTree().toXml(), d2.getRoot().rootTree().toXml())

            assertTreeEditOpInfosEquals(
                listOf(
                    SimpleTreeEditOpInfo(1, 3),
                    SimpleTreeEditOpInfo(1, 1, text { "c" }),
                ),
                ops1,
            )
            assertTreeEditOpInfosEquals(
                listOf(
                    SimpleTreeEditOpInfo(2, 2, text { "c" }),
                    SimpleTreeEditOpInfo(3, 4),
                    SimpleTreeEditOpInfo(1, 2),
                ),
                ops2,
            )
            jobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_tree_change_concurrent_delete_and_insert_when_parent_removed() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("doc") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            ) {
                assertTreesXmlEquals("<doc><p>ab</p></doc>", d1)
            }

            val ops1 = mutableListOf<SimpleTreeEditOpInfo>()
            val ops2 = mutableListOf<SimpleTreeEditOpInfo>()
            val jobs = listOf(collectTreeEditOpInfos(d1, ops1), collectTreeEditOpInfos(d2, ops2))

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(0, 4)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, text { "c" })
                },
            ) {
                assertTreesXmlEquals("<doc></doc>", d1)
                assertTreesXmlEquals("<doc><p>acb</p></doc>", d2)
            }
            assertEquals(d1.getRoot().rootTree().toXml(), d2.getRoot().rootTree().toXml())

            assertTreeEditOpInfosEquals(listOf(SimpleTreeEditOpInfo(0, 4)), ops1)
            assertTreeEditOpInfosEquals(
                listOf(SimpleTreeEditOpInfo(2, 2, text { "c" }), SimpleTreeEditOpInfo(0, 5)),
                ops2,
            )
            jobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_tree_change_concurrent_delete_with_contents_and_insert() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("doc") {
                            element("p") {
                                text { "a" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            ) {
                assertTreesXmlEquals("<doc><p>a</p></doc>", d1)
            }

            val ops1 = mutableListOf<SimpleTreeEditOpInfo>()
            val ops2 = mutableListOf<SimpleTreeEditOpInfo>()
            val jobs = listOf(collectTreeEditOpInfos(d1, ops1), collectTreeEditOpInfos(d2, ops2))

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 2, text { "b" })
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, text { "c" })
                },
            ) {
                assertTreesXmlEquals("<doc><p>b</p></doc>", d1)
                assertTreesXmlEquals("<doc><p>ac</p></doc>", d2)
            }
            assertEquals(d1.getRoot().rootTree().toXml(), d2.getRoot().rootTree().toXml())

            assertTreeEditOpInfosEquals(
                listOf(
                    SimpleTreeEditOpInfo(1, 2, text { "b" }),
                    SimpleTreeEditOpInfo(2, 2, text { "c" }),
                ),
                ops1,
            )
            assertTreeEditOpInfosEquals(
                listOf(
                    SimpleTreeEditOpInfo(2, 2, text { "c" }),
                    SimpleTreeEditOpInfo(1, 2, text { "b" }),
                ),
                ops2,
            )
            jobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_overlapping_merge_and_merge() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "a" }
                            }
                            element("p") {
                                text { "b" }
                            }
                            element("p") {
                                text { "c" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>a</p><p>b</p><p>c</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 4)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(5, 7)
                },
            ) {
                assertTreesXmlEquals("<r><p>ab</p><p>c</p></r>", d1)
                assertTreesXmlEquals("<r><p>a</p><p>bc</p></r>", d2)
            }
            assertTreesXmlEquals("<r><p>abc</p></r>", d1, d2)
        }
    }

    @Test
    fun test_concurrently_deleting_and_styling_on_same_path() {
        withTwoClientsAndDocuments(
            syncMode = Manual,
        ) { client1, client2, document1, document2, _ ->
            val document1Ops = mutableListOf<OperationInfo>()
            val document2Ops = mutableListOf<OperationInfo>()

            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events.filterIsInstance<RemoteChange>()
                        .collect {
                            document1Ops.addAll(it.changeInfo.operations)
                        }
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document2.events.filterIsInstance<RemoteChange>()
                        .collect {
                            document2Ops.addAll(it.changeInfo.operations)
                        }
                },
            )

            // client1 initializes tree
            updateAndSync(
                Updater(client1, document1) { root, _ ->
                    val tree = root.setNewTree("t")
                    tree.editByPath(
                        listOf(0),
                        listOf(0),
                        ElementNode("t", mapOf("id" to "1", "value" to "init")),
                        ElementNode("t", mapOf("id" to "2", "value" to "init")),
                    )
                },
                Updater(client2, document2),
            )

            /* assert both documents are synced right
             {
                "t": {
                    "type": "root",
                    "children": [
                        {
                            "type": "t",
                            "children": [],
                            "attributes": {
                                "id": "1",
                                "value": "init"
                            }
                        },
                        {
                            "type": "t",
                            "children": [],
                            "attributes": {
                                "id": "2",
                                "value": "init"
                            }
                        }
                    ]
                }
             }
             */
            var root1 = document1.getRoot().rootTree().rootTreeNode as ElementNode
            assertEquals(
                mapOf("id" to "1", "value" to "init"),
                (root1.children.first() as ElementNode).attributes,
            )
            assertEquals(
                mapOf("id" to "2", "value" to "init"),
                (root1.children[1] as ElementNode).attributes,
            )

            var root2 = document2.getRoot().rootTree().rootTreeNode as ElementNode
            assertEquals(
                mapOf("id" to "1", "value" to "init"),
                (root2.children.first() as ElementNode).attributes,
            )
            assertEquals(
                mapOf("id" to "2", "value" to "init"),
                (root2.children[1] as ElementNode).attributes,
            )

            updateAndSync(
                // client1 changes attributes on path [0]
                Updater(client1, document1) { root, _ ->
                    root.rootTree().styleByPath(listOf(0), mapOf("value" to "changed"))
                },
                // client2 deletes path[0]
                Updater(client2, document2) { root, _ ->
                    root.rootTree().editByPath(listOf(0), listOf(1))
                },
            )

            /* assert both documents are synced right
             {
                "t": {
                    "type": "root",
                    "children": [
                        {
                            "type": "t",
                            "children": [],
                            "attributes": {
                                "id": "2",
                                "value": "init"
                            }
                        }
                    ]
                }
             }
             */
            root1 = document1.getRoot().rootTree().rootTreeNode as ElementNode
            assertEquals(1, root1.children.size)
            assertEquals(
                mapOf("id" to "2", "value" to "init"),
                (root1.children.first() as ElementNode).attributes,
            )

            root2 = document2.getRoot().rootTree().rootTreeNode as ElementNode
            assertEquals(1, root2.children.size)
            assertEquals(
                mapOf("id" to "2", "value" to "init"),
                (root2.children.first() as ElementNode).attributes,
            )

            delay(500)
            collectJobs.forEach(Job::cancel)

            // assert list of OperationInfo were emitted right
            assertEquals(
                listOf<OperationInfo>(
                    // client2 deleted on path [0]
                    TreeEditOpInfo(
                        0,
                        2,
                        listOf(0),
                        listOf(1),
                        null,
                        0,
                        "$.t",
                    ),
                ),
                document1Ops,
            )

            assertEquals(
                listOf(
                    // client1 set new tree
                    SetOpInfo("t", "$"),
                    // client1 initialized tree
                    TreeEditOpInfo(
                        0,
                        0,
                        listOf(0),
                        listOf(0),
                        listOf(
                            ElementNode("t", mapOf("id" to "1", "value" to "init")),
                            ElementNode("t", mapOf("id" to "2", "value" to "init")),
                        ),
                        0,
                        "$.t",
                    ),
                    // client1 changed attributes on path [0]
                    /* assert style changes on already deleted path is not applied
                     {
                        "t": {
                            "type": "root",
                            "children": [
                                {
                                    "type": "t",
                                    "children": [],
                                    "attributes": {
                                        "id": "2",
                                        "value": "init"
                                    }
                                }
                            ]
                        }
                     }
                     */
                ),
                document2Ops,
            )
        }
    }

    @Test
    fun test_returning_range_from_index_correctly_within_document_events() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
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
                Updater(c1, d1) { root, presence ->
                    root.rootTree().edit(1, 1, text { "a" })
                    val posSelection = root.rootTree().indexRangeToPosRange(2 to 2)
                    presence.put(mapOf("selection" to gson.toJson(posSelection)))
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<doc><p>ahello</p></doc>", d1, d2)
            val selectionType = object : TypeToken<TreePosStructRange>() {}.type
            val selection = gson.fromJson<TreePosStructRange>(
                d1.allPresences.value[c1.requireClientId()]!!["selection"],
                selectionType,
            )
            assertEquals(2 to 2, d1.getRoot().rootTree().posRangeToIndexRange(selection))

            val d1Events = mutableListOf<Document.Event>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                d1.events.collect(d1Events::add)
            }
            updateAndSync(
                Updater(c1, d1),
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, text { "b" })
                },
            )
            assertTreesXmlEquals("<doc><p>abhello</p></doc>", d1, d2)

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.isEmpty()) {
                    delay(50)
                }
            }
            assertIs<RemoteChange>(d1Events.first())
            job.cancel()
        }
    }

    @Test
    fun test_returning_correct_range_path() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("c") {
                                element("u") {
                                    element("p") {
                                        element("n")
                                    }
                                }
                            }
                            element("c") {
                                element("p") {
                                    element("n")
                                }
                            }
                        },
                    )
                },
                Updater(c2, d1),
            )
            assertTreesXmlEquals("<r><c><u><p><n></n></p></u></c><c><p><n></n></p></c></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1),
                Updater(c2, d2) { root, _ ->
                    root.rootTree().editByPath(listOf(1, 0, 0, 0), listOf(1, 0, 0, 0), text { "1" })
                    root.rootTree().editByPath(listOf(1, 0, 0, 1), listOf(1, 0, 0, 1), text { "2" })
                    root.rootTree().editByPath(listOf(1, 0, 0, 2), listOf(1, 0, 0, 2), text { "3" })
                },
            )
            assertTreesXmlEquals(
                "<r><c><u><p><n></n></p></u></c><c><p><n>123</n></p></c></r>",
                d1,
                d2,
            )

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree()
                        .editByPath(listOf(1, 0, 0, 1), listOf(1, 0, 0, 1), text { "abcdefgh" })
                },
                Updater(c2, d1),
            )
            assertTreesXmlEquals(
                "<r><c><u><p><n></n></p></u></c><c><p><n>1abcdefgh23</n></p></c></r>",
                d1,
                d2,
            )

            updateAndSync(
                Updater(c1, d1),
                Updater(c2, d2) { root, _ ->
                    root.rootTree().editByPath(listOf(1, 0, 0, 5), listOf(1, 0, 0, 5), text { "4" })
                    root.rootTree().editByPath(listOf(1, 0, 0, 6), listOf(1, 0, 0, 7))
                    root.rootTree().editByPath(listOf(1, 0, 0, 6), listOf(1, 0, 0, 6), text { "5" })
                },
            )

            val d2Events = mutableListOf<Document.Event>()

            fun handleOpInfo(operation: TreeEditOpInfo) {
                val (_, _, fromPath, toPath) = operation
                assertEquals(listOf(1, 0, 0, 7), fromPath)
                assertEquals(listOf(1, 0, 0, 8), toPath)
            }

            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                d2.events
                    .filter { it is LocalChange || it is RemoteChange }
                    .onEach { event ->
                        when (event) {
                            is LocalChange -> {
                                (event.changeInfo.operations.firstOrNull() as? TreeEditOpInfo)
                                    ?.let(::handleOpInfo)
                            }

                            else -> {
                                val operations = (event as RemoteChange).changeInfo.operations
                                (operations.firstOrNull() as? TreeEditOpInfo)?.let(::handleOpInfo)
                            }
                        }
                    }.collect(d2Events::add)
            }

            updateAndSync(
                Updater(c1, d1),
                Updater(c2, d2) { root, _ ->
                    root.rootTree().editByPath(listOf(1, 0, 0, 7), listOf(1, 0, 0, 8))
                },
            )
            assertTreesXmlEquals(
                "<r><c><u><p><n></n></p></u></c><c><p><n>1abcd45gh23</n></p></c></r>",
                d1,
                d2,
            )

            assertIs<LocalChange>(d2Events.firstOrNull())

            collectJob.cancel()
        }
    }

    @Test
    fun test_client_reload_cases() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, key ->
            // Perform a dummy update to apply changes up to the snapshot threshold.
            repeat(500) {
                d1.updateAsync { root, _ ->
                    root["num"] = 0
                }.await()
            }

            // Start scenario.
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("c") {
                                element("u") {
                                    element("p") {
                                        element("n")
                                    }
                                }
                            }
                            element("c") {
                                element("p") {
                                    element("n")
                                }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><c><u><p><n></n></p></u></c><c><p><n></n></p></c></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().apply {
                        editByPath(listOf(1, 0, 0, 0), listOf(1, 0, 0, 0), text { "1" })
                        editByPath(listOf(1, 0, 0, 1), listOf(1, 0, 0, 1), text { "2" })
                        editByPath(listOf(1, 0, 0, 2), listOf(1, 0, 0, 2), text { "3" })
                        editByPath(listOf(1, 0, 0, 2), listOf(1, 0, 0, 2), text { " " })
                        editByPath(listOf(1, 0, 0, 3), listOf(1, 0, 0, 3), text { "네이버랑 " })
                    }
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals(
                "<r><c><u><p><n></n></p></u></c><c><p><n>12 네이버랑 3</n></p></c></r>",
                d1,
                d2,
            )

            updateAndSync(
                Updater(c1, d1),
                Updater(c2, d2) { root, _ ->
                    root.rootTree().apply {
                        editByPath(listOf(1, 0, 0, 1), listOf(1, 0, 0, 8), text { " 2 네이버랑 " })
                        editByPath(listOf(1, 0, 0, 2), listOf(1, 0, 0, 2), text { "ㅋ" })
                        editByPath(listOf(1, 0, 0, 2), listOf(1, 0, 0, 3), text { "카" })
                        editByPath(listOf(1, 0, 0, 2), listOf(1, 0, 0, 3), text { "캌" })
                        editByPath(listOf(1, 0, 0, 2), listOf(1, 0, 0, 3), text { "카카" })
                        editByPath(listOf(1, 0, 0, 3), listOf(1, 0, 0, 4), text { "캉" })
                        editByPath(listOf(1, 0, 0, 3), listOf(1, 0, 0, 4), text { "카오" })
                        editByPath(listOf(1, 0, 0, 4), listOf(1, 0, 0, 5), text { "올" })
                        editByPath(listOf(1, 0, 0, 4), listOf(1, 0, 0, 5), text { "오라" })
                        editByPath(listOf(1, 0, 0, 5), listOf(1, 0, 0, 6), text { "랑" })
                        editByPath(listOf(1, 0, 0, 6), listOf(1, 0, 0, 6), text { " " })
                    }
                },
            )
            assertTreesXmlEquals(
                "<r><c><u><p><n></n></p></u></c><c><p><n>1 카카오랑 2 네이버랑 3</n></p></c></r>",
                d1,
                d2,
            )

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().apply {
                        editByPath(listOf(1, 0, 0, 13), listOf(1, 0, 0, 14))
                        editByPath(listOf(1, 0, 0, 12), listOf(1, 0, 0, 13))
                    }
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals(
                "<r><c><u><p><n></n></p></u></c><c><p><n>1 카카오랑 2 네이버3</n></p></c></r>",
                d1,
                d2,
            )

            updateAndSync(
                Updater(c1, d1),
                Updater(c2, d2) { root, _ ->
                    root.rootTree().apply {
                        editByPath(listOf(1, 0, 0, 6), listOf(1, 0, 0, 7))
                        editByPath(listOf(1, 0, 0, 5), listOf(1, 0, 0, 6))
                    }
                },
            )
            assertTreesXmlEquals(
                "<r><c><u><p><n></n></p></u></c><c><p><n>1 카카오2 네이버3</n></p></c></r>",
                d1,
                d2,
            )

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().editByPath(listOf(1, 0, 0, 9), listOf(1, 0, 0, 10))
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals(
                "<r><c><u><p><n></n></p></u></c><c><p><n>1 카카오2 네이3</n></p></c></r>",
                d1,
                d2,
            )

            // A new client has been added.
            val d3 = Document(key)
            val c3 = createClient()
            c3.activateAsync().await()
            c3.attachAsync(d3, syncMode = Manual).await()
            assertTreesXmlEquals(d2.getRoot().rootTree().toXml(), d3)

            updateAndSync(
                Updater(c3, d3) { root, _ ->
                    root.rootTree().apply {
                        editByPath(listOf(1, 0, 0, 4), listOf(1, 0, 0, 5))
                        editByPath(listOf(1, 0, 0, 3), listOf(1, 0, 0, 4))
                    }
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals(
                "<r><c><u><p><n></n></p></u></c><c><p><n>1 카2 네이3</n></p></c></r>",
                d2,
                d3,
            )

            updateAndSync(
                Updater(c3, d3) { root, _ ->
                    root.rootTree().editByPath(listOf(1, 0, 0, 2), listOf(1, 0, 0, 3))
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals(
                "<r><c><u><p><n></n></p></u></c><c><p><n>1 2 네이3</n></p></c></r>",
                d2,
                d3,
            )

            c3.deactivateAsync().await()
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

        fun CoroutineScope.collectTreeEditOpInfos(
            document: Document,
            ops: MutableList<SimpleTreeEditOpInfo>,
        ) = launch(start = CoroutineStart.UNDISPATCHED) {
            document.events("$.t")
                .flatMapConcat { event ->
                    when (event) {
                        is LocalChange -> event.changeInfo.operations.asFlow()
                        is RemoteChange -> event.changeInfo.operations.asFlow()
                        else -> emptyFlow()
                    }
                }
                .filterIsInstance<TreeEditOpInfo>()
                .map { SimpleTreeEditOpInfo(it.from, it.to, it.nodes?.firstOrNull()) }
                .collect(ops::add)
        }

        suspend fun assertTreeEditOpInfosEquals(
            expected: List<SimpleTreeEditOpInfo>,
            actual: List<SimpleTreeEditOpInfo>,
        ) {
            withTimeout(GENERAL_TIMEOUT) {
                while (actual.size < expected.size) {
                    delay(50)
                }
            }
            assertEquals(expected, actual)
        }

        data class Updater(
            val client: Client,
            val document: Document,
            val updater: (suspend (JsonObject, Presence) -> Unit)? = null,
        )

        data class SimpleTreeEditOpInfo(
            val from: Int,
            val to: Int,
            val nodes: TreeNode? = null,
        )
    }
}
