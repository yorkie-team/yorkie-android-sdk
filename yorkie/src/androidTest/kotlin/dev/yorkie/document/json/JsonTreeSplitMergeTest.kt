package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.TreeTest
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.json.JsonTreeTest.Companion.rootTree
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@TreeTest
@RunWith(AndroidJUnit4::class)
class JsonTreeSplitMergeTest {

    @Test
    fun test_contained_split_and_split_at_the_same_position() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, 1)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, 1)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1, d2)
            }
            JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p></p><p>b</p></r>", d1, d2)
        }
    }

    @Test
    fun test_contained_split_and_split_at_different_positions_on_the_same_node() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") {
                                text { "abc" }
                            }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>abc</p></r>", d1, d2)

            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, 1)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, 1)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p>bc</p></r>", d1)
                JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p><p>c</p></r>", d2)
            }
            JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p>b</p><p>c</p></r>", d1, d2)
        }
    }

    @Test
    fun test_contained_split_and_insert_into_the_split_position() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, 1)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 2, TreeBuilder.text { "c" })
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1)
                JsonTreeTest.assertTreesXmlEquals("<r><p>acb</p></r>", d2)
            }
            JsonTreeTest.assertTreesXmlEquals("<r><p>ac</p><p>b</p></r>", d1, d2)
        }
    }

    @Test
    fun test_contained_split_and_insert_into_original_node() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, 1)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 1, TreeBuilder.text { "c" })
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1)
                JsonTreeTest.assertTreesXmlEquals("<r><p>cab</p></r>", d2)
            }
            JsonTreeTest.assertTreesXmlEquals("<r><p>ca</p><p>b</p></r>", d1, d2)
        }
    }

    @Test
    fun test_contained_split_and_insert_into_split_node() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, 1)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, TreeBuilder.text { "c" })
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1)
                JsonTreeTest.assertTreesXmlEquals("<r><p>abc</p></r>", d2)
            }
            JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p>bc</p></r>", d2)
        }
    }

    @Test
    fun test_contained_split_and_delete_contents_in_split_node() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") {
                                text { "ab" }
                            }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, 1)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(2, 3)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1)
                JsonTreeTest.assertTreesXmlEquals("<r><p>a</p></r>", d2)
            }

            JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_split_and_merge_with_empty_paragraph_left() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    TreeBuilder.element("doc") {
                        element("p") {
                            text { "a" }
                            text { "b" }
                        }
                    },
                )
            }.await()
            JsonTreeTest.assertTreesXmlEquals("<doc><p>ab</p></doc>", d1)

            d1.updateAsync { root, _ ->
                root.rootTree().edit(1, 1, 1)
            }.await()
            JsonTreeTest.assertTreesXmlEquals("<doc><p></p><p>ab</p></doc>", d1)

            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(1, 3)
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<doc><p>ab</p></doc>", d1, d2)
        }
    }

    @Test
    fun test_split_and_merge_with_empty_paragraph_and_multiple_split_level_left() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    TreeBuilder.element("doc") {
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
                root.rootTree().edit(2, 2, 2)
            }.await()
            assertEquals(
                "<doc><p><p></p></p><p><p>ab</p></p></doc>",
                d1.getRoot().rootTree().toXml(),
            )

            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 6)
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<doc><p><p>ab</p></p></doc>", d1, d2)
        }
    }

    @Test
    fun test_split_same_offset_multiple_times() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    TreeBuilder.element("doc") {
                        element("p") {
                            text { "a" }
                            text { "b" }
                        }
                    },
                )
            }.await()
            assertEquals("<doc><p>ab</p></doc>", d1.getRoot().rootTree().toXml())

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, 1)
            }.await()
            assertEquals("<doc><p>a</p><p>b</p></doc>", d1.getRoot().rootTree().toXml())

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, TreeBuilder.text { "c" })
            }.await()
            assertEquals("<doc><p>ac</p><p>b</p></doc>", d1.getRoot().rootTree().toXml())

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, 1)
            }.await()
            assertEquals("<doc><p>a</p><p>c</p><p>b</p></doc>", d1.getRoot().rootTree().toXml())

            JsonTreeTest.updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 7)
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<doc><p>ab</p></doc>", d1, d2)
        }
    }
}
