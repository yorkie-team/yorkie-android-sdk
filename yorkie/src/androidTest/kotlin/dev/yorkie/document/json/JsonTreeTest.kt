package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import dev.yorkie.core.Client
import dev.yorkie.core.Presence
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.Document
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitAll
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(0, 4)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 6)
            }.await()
            assertEquals("<r><b></b></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 4)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 5)
            }.await()
            assertEquals("<r><p>d</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>a</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(6, 6, element("p"))
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(0, 12)
            }.await()
            assertEquals("<r><p>1234</p><p></p><p>abcd</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(6, 6, element("p"))
                root.rootTree().edit(8, 8, element("p"))
                root.rootTree().edit(10, 10, element("p"))
                root.rootTree().edit(12, 12, element("p"))
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(0, 12)
            }.await()
            assertEquals(
                "<r><p>1234</p><p></p><p></p><p></p><p></p><p>abcd</p></r>",
                d1.getRoot().rootTree().toXml(),
            )
            assertEquals("<r></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, element("i"))
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 3)
            }.await()
            assertEquals("<r><p><i><i></i></i></p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(0, 8)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 7)
            }.await()
            assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 5)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, text { "a" })
            }.await()
            assertEquals("<r><p></p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>12a34</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 5)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 4)
            }.await()
            assertEquals("<r><p></p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>14</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(0, 6)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, text { "a" })
            }.await()
            assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>12a34</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(0, 6)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 5)
            }.await()
            assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertTreesXmlEquals("<r></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_side_by_side_elements_into_left_concurrently() {
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(0, 0, element("b"))
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(0, 0, element("i"))
            }.await()
            assertEquals("<r><b></b><p></p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><i></i><p></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertTreesXmlEquals("<r><i></i><b></b><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_side_by_side_elements_into_middle_concurrently() {
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 1, element("b"))
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 1, element("i"))
            }.await()
            assertEquals("<r><p><b></b></p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p><i></i></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertTreesXmlEquals("<r><p><i></i><b></b></p></r>", d1, d2)
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, element("b"))
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, element("i"))
            }.await()
            assertEquals("<r><p></p><b></b></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p></p><i></i></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertTreesXmlEquals("<r><p></p><i></i><b></b></r>", d1, d2)
        }
    }

    @Test
    fun test_insert_and_delete_side_by_side_elements_concurrently() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                element("b")
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p><b></b></p></r>", d1, d2)

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 3)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 1, element("i"))
            }.await()
            assertEquals("<r><p></p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p><i></i><b></b></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertTreesXmlEquals("<r><p><i></i></p></r>", d1, d2)
        }
    }

    @Test
    fun test_delete_and_insert_side_by_side_elements_concurrently() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") {
                                element("b")
                            }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p><b></b></p></r>", d1, d2)

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 3)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, element("i"))
            }.await()
            assertEquals("<r><p></p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p><b></b><i></i></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertTreesXmlEquals("<r><p><i></i></p></r>", d1, d2)
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 3)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(3, 5)
            }.await()
            assertEquals("<r><p><i></i></p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p><b></b></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 1, text { "A" })
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 1, text { "B" })
            }.await()
            assertEquals("<r><p>A12</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>B12</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, text { "A" })
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, text { "B" })
            }.await()
            assertEquals("<r><p>1A2</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>1B2</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, text { "A" })
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, text { "B" })
            }.await()
            assertEquals("<r><p>12A</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>12B</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, text { "a" })
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(3, 5)
            }.await()
            assertEquals("<r><p>12a34</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>12</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, text { "a" })
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 3)
            }.await()
            assertEquals("<r><p>12a34</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>34</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(3, 5)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 3)
            }.await()
            assertEquals("<r><p>12</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>34</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 2)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 2)
            }.await()
            assertEquals("<r><p>23</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>23</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 3)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 3)
            }.await()
            assertEquals("<r><p>13</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>13</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(3, 4)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(3, 4)
            }.await()
            assertEquals("<r><p>12</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>12</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 2)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 3)
            }.await()
            assertEquals("<r><p>23</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>13</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 2)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 4)
            }.await()
            assertEquals("<r><p>23</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>1</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 3)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(4, 6)
            }.await()
            assertEquals("<r><p>345</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>123</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 5)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, text { "B" })
            }.await()
            assertEquals("<r><p>15</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>12B345</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 6)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, text { "a" }, text { "bc" })
            }.await()
            assertEquals("<r><p>1</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>12abc345</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(0, 12)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(
                    6,
                    6,
                    element("p") { text { "cd" } },
                    element("i") { text { "fg" } },
                )
            }.await()
            assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
            assertEquals(
                "<r><p>1234</p><p>cd</p><i>fg</i><p>5678</p></r>",
                d2.getRoot().rootTree().toXml(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(0, 7)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(
                    0,
                    0,
                    element("p") { text { "cd" } },
                    element("i") { text { "fg" } },
                )
            }.await()
            assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
            assertEquals(
                "<r><p>cd</p><i>fg</i><p>12345</p></r>",
                d2.getRoot().rootTree().toXml(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(0, 7)
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(
                    7,
                    7,
                    element("p") { text { "cd" } },
                    element("i") { text { "fg" } },
                )
            }.await()
            assertEquals("<r></r>", d1.getRoot().rootTree().toXml())
            assertEquals(
                "<r><p>12345</p><p>cd</p><i>fg</i></r>",
                d2.getRoot().rootTree().toXml(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, text { "A" })
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 2)
            }.await()
            assertEquals("<r><p>1A2</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p>2</p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 1, text { "A" })
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 3)
            }.await()
            assertEquals("<r><p>A12</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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

            d1.updateAsync { root, _ ->
                root.rootTree().edit(3, 3, text { "A" })
            }.await()
            d2.updateAsync { root, _ ->
                root.rootTree().edit(1, 3)
            }.await()
            assertEquals("<r><p>12A</p></r>", d1.getRoot().rootTree().toXml())
            assertEquals("<r><p></p></r>", d2.getRoot().rootTree().toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
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
            assertEquals("<doc><p>a11b</p></doc>", d2.getRoot().rootTree().toXml())

            d2.updateAsync { root, _ ->
                root.rootTree().apply {
                    edit(2, 3, text { "12" })
                    edit(4, 5, text { "21" })
                }
            }.await()
            assertEquals("<doc><p>a1221b</p></doc>", d2.getRoot().rootTree().toXml())

            // if split link is not transmitted, then left sibling in from index below, is "b" not "a"
            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 4, text { "123" })
            }.await()
            assertEquals("<doc><p>a12321b</p></doc>", d2.getRoot().rootTree().toXml())
        }
    }

    @Test
    fun test_calculating_size_of_index_tree() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
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
            }.await()

            assertEquals("<doc><p>a0123789456123b</p></doc>", d1.getRoot().rootTree().toXml())
            c1.syncAsync().await()
            c2.syncAsync().await()

            val size = d1.getRoot().rootTree().indexTree.root.size
            assertEquals(size, d2.getRoot().rootTree().indexTree.root.size)
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
            val updater: (suspend (JsonObject, Presence) -> Unit)? = null,
        )
    }
}
