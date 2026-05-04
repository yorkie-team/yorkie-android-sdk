package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.TreeTest
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.json.JsonTreeTest.Companion.rootTree
import dev.yorkie.document.json.JsonTreeTest.Companion.updateAndSync
import kotlin.test.assertEquals
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@TreeTest
@RunWith(AndroidJUnit4::class)
class JsonTreeSplitMergeTest {

    @Test
    fun test_contained_split_and_split_at_the_same_position() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
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

            updateAndSync(
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
            updateAndSync(
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

            updateAndSync(
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
            updateAndSync(
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

            updateAndSync(
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
            updateAndSync(
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

            updateAndSync(
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
            updateAndSync(
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

            updateAndSync(
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
            updateAndSync(
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

            updateAndSync(
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

            updateAndSync(
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

            updateAndSync(
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

            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 7)
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<doc><p>ab</p></doc>", d1, d2)
        }
    }

    @Test
    fun test_side_by_side_concurrent_merge_and_merge() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") { text { "a" } }
                            element("p") { text { "b" } }
                            element("p") { text { "c" } }
                            element("p") { text { "d" } }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals(
                "<r><p>a</p><p>b</p><p>c</p><p>d</p></r>",
                d1,
                d2,
            )

            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 4)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(8, 10)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals(
                    "<r><p>ab</p><p>c</p><p>d</p></r>",
                    d1,
                )
                JsonTreeTest.assertTreesXmlEquals(
                    "<r><p>a</p><p>b</p><p>cd</p></r>",
                    d2,
                )
            }
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p><p>cd</p></r>", d1, d2)
        }
    }

    @Test
    fun test_concurrent_delete_after_merge_with_nested_content() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") {
                                element("b") { text { "a" } }
                            }
                            element("p") {
                                element("b") { text { "b" } }
                            }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals(
                "<r><p><b>a</b></p><p><b>b</b></p></r>",
                d1,
                d2,
            )

            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(4, 6)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(0, 10)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals(
                    "<r><p><b>a</b><b>b</b></p></r>",
                    d1,
                )
                JsonTreeTest.assertTreesXmlEquals("<r></r>", d2)
            }
            JsonTreeTest.assertTreesXmlEquals("<r></r>", d1, d2)
        }
    }

    @Test
    fun test_delete_starting_inside_merge_target() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") { text { "ab" } }
                            element("p") { text { "c" } }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p><p>c</p></r>", d1, d2)

            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 5)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 7)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals("<r><p>abc</p></r>", d1)
                JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p></r>", d2)
            }
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)
        }
    }

    @Test
    fun test_contained_split_and_split_at_different_levels() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") {
                                element("p") { text { "ab" } }
                                element("p") { text { "c" } }
                            }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals(
                "<r><p><p>ab</p><p>c</p></p></r>",
                d1,
                d2,
            )

            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 3, 1)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(5, 5, 1)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals(
                    "<r><p><p>a</p><p>b</p><p>c</p></p></r>",
                    d1,
                )
                JsonTreeTest.assertTreesXmlEquals(
                    "<r><p><p>ab</p></p><p><p>c</p></p></r>",
                    d2,
                )
            }
            JsonTreeTest.assertTreesXmlEquals(
                "<r><p><p>a</p><p>b</p></p><p><p>c</p></p></r>",
                d1,
                d2,
            )
        }
    }

    @Test
    fun test_side_by_side_split_and_insert() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") { text { "ab" } }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)

            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, 1)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(
                        4,
                        4,
                        TreeBuilder.element("p") { text { "c" } },
                    )
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1)
                JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p><p>c</p></r>", d2)
            }
            JsonTreeTest.assertTreesXmlEquals(
                "<r><p>a</p><p>b</p><p>c</p></r>",
                d1,
                d2,
            )
        }
    }

    @Test
    fun test_side_by_side_split_and_delete() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") { text { "ab" } }
                            element("p") { text { "c" } }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p><p>c</p></r>", d1, d2)

            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 2, 1)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(4, 7)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals(
                    "<r><p>a</p><p>b</p><p>c</p></r>",
                    d1,
                )
                JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p></r>", d2)
            }
            JsonTreeTest.assertTreesXmlEquals("<r><p>a</p><p>b</p></r>", d1, d2)
        }
    }

    @Test
    fun test_merge_with_concurrent_content_delete() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") { text { "ab" } }
                            element("p") { text { "cd" } }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals(
                "<r><p>ab</p><p>cd</p></r>",
                d1,
                d2,
            )

            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 3)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 5)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals(
                    "<r><p>a</p><p>cd</p></r>",
                    d1,
                )
                JsonTreeTest.assertTreesXmlEquals("<r><p>abcd</p></r>", d2)
            }
            JsonTreeTest.assertTreesXmlEquals("<r><p>acd</p></r>", d1, d2)
        }
    }

    @Test
    fun test_merge_with_concurrent_full_content_delete_in_source() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") { text { "ab" } }
                            element("p") { text { "cd" } }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals(
                "<r><p>ab</p><p>cd</p></r>",
                d1,
                d2,
            )

            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(5, 7)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 5)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals(
                    "<r><p>ab</p><p></p></r>",
                    d1,
                )
                JsonTreeTest.assertTreesXmlEquals("<r><p>abcd</p></r>", d2)
            }
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p></r>", d1, d2)
        }
    }

    @Test
    fun test_cascade_delete_across_parent_after_multi_level_split() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") {
                                element("p") { text { "ab" } }
                                element("p") { text { "cd" } }
                            }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals(
                "<r><p><p>ab</p><p>cd</p></p></r>",
                d1,
                d2,
            )

            // d1: multi-level split at position 3 (splitLevel=2)
            // d2: merge-delete from 1 to 6 (removes inner p1 and its content)
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 3, 2)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 6)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals(
                    "<r><p><p>a</p></p><p><p>b</p><p>cd</p></p></r>",
                    d1,
                )
                JsonTreeTest.assertTreesXmlEquals("<r><p>cd</p></r>", d2)
            }

            JsonTreeTest.assertTreesXmlEquals("<r><p>cd</p><p></p></r>", d1, d2)
        }
    }

    @Test
    fun test_sequential_merge_then_split() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") { text { "ab" } }
                            element("p") { text { "cd" } }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p><p>cd</p></r>", d1, d2)

            // d1: merge two paragraphs (sequential, c2 will learn about it)
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 5)
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>abcd</p></r>", d1, d2)

            // d2: split the merged paragraph at ab|cd (sequential, knows about merge)
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1),
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, 1)
                },
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p><p>cd</p></r>", d1, d2)
        }
    }

    @Test
    fun test_multi_level_split_with_concurrent_merge_and_text_split() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") {
                                element("p") { text { "ab" } }
                                element("p") { text { "cd" } }
                            }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals(
                "<r><p><p>ab</p><p>cd</p></p></r>",
                d1,
                d2,
            )

            // d1: multi-level split at position 3 (splitLevel=2)
            // d2: merge the two inner paragraphs (edit 1 to 6)
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(3, 3, 2)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(1, 6)
                },
            )

            JsonTreeTest.assertTreesXmlEquals(d1.getRoot().rootTree().toXml(), d1, d2)
        }
    }

    @Ignore("TODO: fix concurrent delete + split convergence on overlapping content")
    @Test
    fun test_split_with_concurrent_delete_overlapping_content() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        TreeBuilder.element("r") {
                            element("p") { text { "abcd" } }
                        },
                    )
                },
                JsonTreeTest.Companion.Updater(c2, d2),
            )
            JsonTreeTest.assertTreesXmlEquals("<r><p>abcd</p></r>", d1, d2)

            // d1: delete "bc" (positions 2-4)
            // d2: split <p> at position 3 (between b and c) with splitLevel=1
            updateAndSync(
                JsonTreeTest.Companion.Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(2, 4)
                },
                JsonTreeTest.Companion.Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(3, 3, 1)
                },
            ) {
                JsonTreeTest.assertTreesXmlEquals("<r><p>ad</p></r>", d1)
                JsonTreeTest.assertTreesXmlEquals("<r><p>ab</p><p>cd</p></r>", d2)
            }

            JsonTreeTest.assertTreesXmlEquals(d1.getRoot().rootTree().toXml(), d1, d2)
        }
    }
}
