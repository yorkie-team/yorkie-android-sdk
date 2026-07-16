package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonTreeUndoTest {

    /**
     * Verifies undo/redo for a pure text insertion into a tree.
     */
    @Test
    fun test_undo_and_redo_text_insert() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("root") {
                        element("p") { text { "hello" } }
                    },
                )
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(6, 6, text { " world" })
            }.await()
            c1.syncAsync().await()

            assertEquals(
                "<root><p>hello world</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            assertTrue(d1.history.canUndo())
            d1.history.undoAsync().await()
            assertEquals(
                "<root><p>hello</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            assertTrue(d1.history.canRedo())
            d1.history.redoAsync().await()
            assertEquals(
                "<root><p>hello world</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )
        }
    }

    /**
     * Verifies undo/redo for a pure text deletion from a tree.
     */
    @Test
    fun test_undo_and_redo_text_delete() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("root") {
                        element("p") { text { "hello" } }
                    },
                )
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(2, 4)
            }.await()
            c1.syncAsync().await()
            assertEquals(
                "<root><p>hlo</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.history.undoAsync().await()
            assertEquals(
                "<root><p>hello</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.history.redoAsync().await()
            assertEquals(
                "<root><p>hlo</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )
        }
    }

    /**
     * Verifies undo/redo for replacing text in a tree.
     */
    @Test
    fun test_undo_and_redo_text_replace() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("root") {
                        element("p") { text { "hello" } }
                    },
                )
            }.await()
            c1.syncAsync().await()

            // edit(2, 4, "ELL") deletes [2, 4) = "el" and inserts "ELL",
            // producing "hELLlo".
            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(2, 4, text { "ELL" })
            }.await()
            c1.syncAsync().await()
            assertEquals(
                "<root><p>hELLlo</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.history.undoAsync().await()
            assertEquals(
                "<root><p>hello</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.history.redoAsync().await()
            assertEquals(
                "<root><p>hELLlo</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )
        }
    }

    /**
     * Verifies undo/redo for inserting an element node.
     */
    @Test
    fun test_undo_and_redo_element_insert() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("root") {
                        element("p") { text { "ab" } }
                    },
                )
            }.await()
            c1.syncAsync().await()

            // Insert a new p element after the first one
            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(
                    4,
                    4,
                    element("p") { text { "cd" } },
                )
            }.await()
            c1.syncAsync().await()
            assertEquals(
                "<root><p>ab</p><p>cd</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.history.undoAsync().await()
            assertEquals(
                "<root><p>ab</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.history.redoAsync().await()
            assertEquals(
                "<root><p>ab</p><p>cd</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )
        }
    }

    /**
     * Verifies undo/redo for deleting an element node.
     */
    @Test
    fun test_undo_and_redo_element_delete() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("root") {
                        element("p") { text { "ab" } }
                        element("p") { text { "cd" } }
                    },
                )
            }.await()
            c1.syncAsync().await()

            // Delete the second p element (from index 4 to 8)
            d1.updateAsync { root, _ ->
                val tree = root.getAs<JsonTree>("tree")
                tree.edit(4, 8)
            }.await()
            c1.syncAsync().await()
            assertEquals(
                "<root><p>ab</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.history.undoAsync().await()
            assertEquals(
                "<root><p>ab</p><p>cd</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.history.redoAsync().await()
            assertEquals(
                "<root><p>ab</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )
        }
    }

    /**
     * Verifies that canUndo returns false when there are no operations to undo.
     */
    @Test
    fun test_canUndo_returns_false_when_empty() {
        withTwoClientsAndDocuments(syncMode = Manual) { _, _, d1, _, _ ->
            assertFalse(d1.history.canUndo())
            assertFalse(d1.history.canRedo())
        }
    }

    /**
     * Verifies multiple sequential undo operations.
     */
    @Test
    fun test_multiple_undo_operations() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("root") {
                        element("p") { text { "hello" } }
                    },
                )
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(6, 6, text { " world" })
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(12, 12, text { "!" })
            }.await()
            c1.syncAsync().await()
            assertEquals(
                "<root><p>hello world!</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.history.undoAsync().await()
            assertEquals(
                "<root><p>hello world</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.history.undoAsync().await()
            assertEquals(
                "<root><p>hello</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            // The initial setNewTree contributes its own reverse op, so
            // canUndo() remains true after both edit reverses are popped.
        }
    }

    /**
     * Verifies undo/redo for setting an attribute that did not previously exist.
     */
    @Test
    fun test_undo_and_redo_tree_style_set_new_attribute() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("p") { text { "AB" } }
                    },
                )
            }.await()
            c1.syncAsync().await()

            val before = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").style(0, 1, mapOf("bold" to "true"))
            }.await()
            c1.syncAsync().await()

            val after = d1.getRoot().getAs<JsonTree>("tree").toXml()
            assertTrue(after.contains("bold"))

            d1.history.undoAsync().await()
            assertEquals(before, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(after, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    /**
     * Verifies undo/redo for overwriting an existing attribute.
     */
    @Test
    fun test_undo_and_redo_tree_style_overwrite_attribute() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("p") {
                            attr { "color" to "blue" }
                            text { "AB" }
                        }
                    },
                )
            }.await()
            c1.syncAsync().await()

            val before = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").style(0, 1, mapOf("color" to "red"))
            }.await()
            c1.syncAsync().await()

            val after = d1.getRoot().getAs<JsonTree>("tree").toXml()
            assertTrue(after.contains("color=\"red\""))

            d1.history.undoAsync().await()
            assertEquals(before, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(after, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    /**
     * Verifies undo/redo for removeStyle.
     */
    @Test
    fun test_undo_and_redo_tree_remove_style() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("p") {
                            attr { "bold" to "true" }
                            text { "AB" }
                        }
                    },
                )
            }.await()
            c1.syncAsync().await()

            val before = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").removeStyle(0, 1, listOf("bold"))
            }.await()
            c1.syncAsync().await()

            val after = d1.getRoot().getAs<JsonTree>("tree").toXml()
            assertFalse(after.contains("bold"))

            d1.history.undoAsync().await()
            assertEquals(before, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(after, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    /**
     * Verifies undo/redo for a chain of style operations.
     */
    @Test
    fun test_undo_and_redo_tree_style_chain() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("p") {
                            attr { "bold" to "true" }
                            text { "AB" }
                        }
                        element("p") { text { "CD" } }
                    },
                )
            }.await()
            c1.syncAsync().await()

            val s0 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").style(0, 1, mapOf("italic" to "true"))
            }.await()
            c1.syncAsync().await()
            val s1 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").style(3, 4, mapOf("color" to "red"))
            }.await()
            c1.syncAsync().await()
            val s2 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.history.undoAsync().await()
            assertEquals(s1, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.undoAsync().await()
            assertEquals(s0, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(s1, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(s2, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    /**
     * Verifies undo/redo for a mixed edit + style chain.
     */
    @Test
    fun test_undo_and_redo_tree_style_after_edit() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("p") { text { "AB" } }
                    },
                )
            }.await()
            c1.syncAsync().await()

            val s0 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(1, 1, text { "X" })
            }.await()
            c1.syncAsync().await()
            val s1 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").style(0, 1, mapOf("italic" to "true"))
            }.await()
            c1.syncAsync().await()
            val s2 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.history.undoAsync().await()
            assertEquals(s1, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.undoAsync().await()
            assertEquals(s0, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(s1, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(s2, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    /**
     * Verifies undo/redo for splitLevel=1 split at front/middle/back positions.
     */
    @Test
    fun test_undo_and_redo_split_at_front() {
        runSplitUndoRedoCase(
            splitIdx = 1,
            afterXml = "<doc><p></p><p>ABCD</p></doc>",
        )
    }

    @Test
    fun test_undo_and_redo_split_at_middle() {
        runSplitUndoRedoCase(
            splitIdx = 3,
            afterXml = "<doc><p>AB</p><p>CD</p></doc>",
        )
    }

    @Test
    fun test_undo_and_redo_split_at_back() {
        runSplitUndoRedoCase(
            splitIdx = 5,
            afterXml = "<doc><p>ABCD</p><p></p></doc>",
        )
    }

    /**
     * Verifies undo/redo for splitLevel=2 (nested) split at front/middle/back (#1234).
     * Tree: <doc><div><p>ABCD</p></div></doc> (indices: 2=before A, 4=between B/C, 6=after D).
     */
    @Test
    fun test_undo_and_redo_split_l2_at_front() {
        runL2SplitUndoRedoCase(
            splitIdx = 2,
            afterXml = "<doc><div><p></p></div><div><p>ABCD</p></div></doc>",
        )
    }

    @Test
    fun test_undo_and_redo_split_l2_at_middle() {
        runL2SplitUndoRedoCase(
            splitIdx = 4,
            afterXml = "<doc><div><p>AB</p></div><div><p>CD</p></div></doc>",
        )
    }

    @Test
    fun test_undo_and_redo_split_l2_at_back() {
        runL2SplitUndoRedoCase(
            splitIdx = 6,
            afterXml = "<doc><div><p>ABCD</p></div><div><p></p></div></doc>",
        )
    }

    @Test
    fun test_undo_redo_undo_split_l2_at_middle() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("div") { element("p") { text { "ABCD" } } }
                    },
                )
            }.await()
            c1.syncAsync().await()
            val before = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(4, 4, 2)
            }.await()
            c1.syncAsync().await()

            d1.history.undoAsync().await()
            d1.history.redoAsync().await()
            d1.history.undoAsync().await()
            assertEquals(before, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    /**
     * Verifies undo-redo-undo cycle for splitLevel=1 split returns to the
     * pre-split state.
     */
    @Test
    fun test_undo_redo_undo_split_at_middle() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("p") { text { "ABCD" } }
                    },
                )
            }.await()
            c1.syncAsync().await()
            val before = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(3, 3, 1)
            }.await()
            c1.syncAsync().await()

            d1.history.undoAsync().await()
            d1.history.redoAsync().await()
            d1.history.undoAsync().await()
            assertEquals(before, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    /**
     * Verifies undo/redo for chained ops: split -> insert-text.
     */
    @Test
    fun test_undo_and_redo_split_then_insert_text() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("p") { text { "ABCD" } }
                    },
                )
            }.await()
            c1.syncAsync().await()
            val s0 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(3, 3, 1)
            }.await()
            c1.syncAsync().await()
            val s1 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(1, 1, text { "X" })
            }.await()
            c1.syncAsync().await()
            val s2 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.history.undoAsync().await()
            assertEquals(s1, d1.getRoot().getAs<JsonTree>("tree").toXml())
            d1.history.undoAsync().await()
            assertEquals(s0, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(s1, d1.getRoot().getAs<JsonTree>("tree").toXml())
            d1.history.redoAsync().await()
            assertEquals(s2, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    /**
     * Verifies undo/redo for chained ops: split -> split.
     */
    @Test
    fun test_undo_and_redo_split_then_split() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("p") { text { "ABCD" } }
                    },
                )
            }.await()
            c1.syncAsync().await()
            val s0 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(3, 3, 1)
            }.await()
            c1.syncAsync().await()
            val s1 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(2, 2, 1)
            }.await()
            c1.syncAsync().await()
            val s2 = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.history.undoAsync().await()
            assertEquals(s1, d1.getRoot().getAs<JsonTree>("tree").toXml())
            d1.history.undoAsync().await()
            assertEquals(s0, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(s1, d1.getRoot().getAs<JsonTree>("tree").toXml())
            d1.history.redoAsync().await()
            assertEquals(s2, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    /**
     * Verifies that the redo stack is cleared when a new edit is made after a
     * split undo.
     */
    @Test
    fun test_split_undo_clears_redo_stack_on_new_edit() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("p") { text { "ABCD" } }
                    },
                )
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(3, 3, 1)
            }.await()
            c1.syncAsync().await()

            d1.history.undoAsync().await()
            assertTrue(d1.history.canRedo())

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(1, 1, text { "Z" })
            }.await()
            c1.syncAsync().await()

            assertFalse(d1.history.canRedo())
        }
    }

    private fun runSplitUndoRedoCase(splitIdx: Int, afterXml: String) {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("p") { text { "ABCD" } }
                    },
                )
            }.await()
            c1.syncAsync().await()
            val before = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(splitIdx, splitIdx, 1)
            }.await()
            c1.syncAsync().await()
            assertEquals(afterXml, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.undoAsync().await()
            assertEquals(before, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(afterXml, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    private fun runL2SplitUndoRedoCase(splitIdx: Int, afterXml: String) {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("doc") {
                        element("div") { element("p") { text { "ABCD" } } }
                    },
                )
            }.await()
            c1.syncAsync().await()
            val before = d1.getRoot().getAs<JsonTree>("tree").toXml()

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(splitIdx, splitIdx, 2)
            }.await()
            c1.syncAsync().await()
            assertEquals(afterXml, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.undoAsync().await()
            assertEquals(before, d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            assertEquals(afterXml, d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }
}
