package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import dev.yorkie.core.Client
import dev.yorkie.core.GENERAL_TIMEOUT
import dev.yorkie.core.Presence
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.LocalChange
import dev.yorkie.document.Document.Event.RemoteChange
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OperationInfo.TreeEditOpInfo
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class JsonTreeTest {

    @Test
    fun test_tree_sync_between_replicas() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
    fun test_contained_split_and_split_at_the_same_position() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, splitLevel = 1)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, splitLevel = 1)
                },
            ) {
                assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1, d2)
            }
            assertTreesXmlEquals("<r><p>a</p><p></p><p>b</p></r>", d1, d2)
        }
    }

    @Test
    fun test_contained_split_and_split_at_different_positions_on_the_same_node() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "abc" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>abc</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, splitLevel = 1)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, splitLevel = 1)
                },
            ) {
                assertTreesXmlEquals("<r><p>a</p><p>bc</p></r>", d1)
                assertTreesXmlEquals("<r><p>ab</p><p>c</p></r>", d2)
            }
            assertTreesXmlEquals("<r><p>a</p><p>b</p><p>c</p></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_side_by_side_elements_into_right_concurrently() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
    fun test_contained_split_and_insert_into_the_split_position() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, splitLevel = 1)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, text { "c" })
                },
            ) {
                assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1)
                assertTreesXmlEquals("<r><p>acb</p></r>", d2)
            }
            assertTreesXmlEquals("<r><p>ac</p><p>b</p></r>", d1, d2)
        }
    }

    @Test
    fun test_contained_split_and_insert_into_original_node() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, splitLevel = 1)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 1, text { "c" })
                },
            ) {
                assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1)
                assertTreesXmlEquals("<r><p>cab</p></r>", d2)
            }
            assertTreesXmlEquals("<r><p>ca</p><p>b</p></r>", d1, d2)
        }
    }

    @Test
    fun test_contained_split_and_insert_into_split_node() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, splitLevel = 1)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, text { "c" })
                },
            ) {
                assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1)
                assertTreesXmlEquals("<r><p>abc</p></r>", d2)
            }
            assertTreesXmlEquals("<r><p>a</p><p>bc</p></r>", d2)
        }
    }

    @Test
    fun test_contained_split_and_delete_contents_in_split_node() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, splitLevel = 1)
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 3)
                },
            ) {
                assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1)
                assertTreesXmlEquals("<r><p>a</p></r>", d2)
            }

            assertTreesXmlEquals("<r><p>a</p><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_deleting_side_by_side_elements_concurrently() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
    fun test_split_and_merge_with_empty_paragraph_left() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") {
                            text { "a" }
                            text { "b" }
                        }
                    },
                )
            }.await()
            assertTreesXmlEquals("<doc><p>ab</p></doc>", d1)

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 1, splitLevel = 1)
            }.await()
            assertTreesXmlEquals("<doc><p></p><p>ab</p></doc>", d1)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 3)
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<doc><p>ab</p></doc>", d1, d2)
        }
    }

    @Test
    fun test_split_and_merge_with_empty_paragraph_left_and_multiple_split_level_left() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") {
                            element("p") {
                                text { "a" }
                                text { "b" }
                            }
                        }
                    },
                )
            }.await()
            assertEquals("<doc><p><p>ab</p></p></doc>", d1.getRoot().rootTree().toXml())

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, splitLevel = 2)
            }.await()
            assertEquals(
                "<doc><p><p></p></p><p><p>ab</p></p></doc>",
                d1.getRoot().rootTree().toXml(),
            )

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 6)
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<doc><p><p>ab</p></p></doc>", d1, d2)
        }
    }

    @Test
    fun test_split_same_offset_multiple_times() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") {
                            text { "a" }
                            text { "b" }
                        }
                    },
                )
            }.await()
            assertEquals("<doc><p>ab</p></doc>", d1.getRoot().rootTree().toXml())

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, splitLevel = 1)
            }.await()
            assertEquals("<doc><p>a</p><p>b</p></doc>", d1.getRoot().rootTree().toXml())

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, text { "c" })
            }.await()
            assertEquals("<doc><p>ac</p><p>b</p></doc>", d1.getRoot().rootTree().toXml())

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, splitLevel = 1)
            }.await()
            assertEquals("<doc><p>a</p><p>c</p><p>b</p></doc>", d1.getRoot().rootTree().toXml())

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 7)
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<doc><p>ab</p></doc>", d1, d2)
        }
    }

    @Test
    fun test_tree_change_concurrent_delete() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
                    SimpleTreeEditOpInfo(1, 2),
                    SimpleTreeEditOpInfo(3, 4),
                ),
                ops2,
            )
            jobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_tree_change_concurrent_delete_and_insert_when_parent_removed() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
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
            val nodes: JsonTree.TreeNode? = null,
        )
    }
}
