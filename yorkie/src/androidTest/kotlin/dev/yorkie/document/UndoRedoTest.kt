package dev.yorkie.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.json.JsonCounter
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UndoRedoTest {

    @Test
    fun test_undo_reverts_object_set() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root["k1"] = "v1"
            }.await()
            assertEquals("v1", d1.getRoot().getAs<JsonPrimitive>("k1").value)

            d1.updateAsync { root, _ ->
                root["k1"] = "v2"
            }.await()
            assertEquals("v2", d1.getRoot().getAs<JsonPrimitive>("k1").value)

            assertTrue(d1.history.canUndo())
            d1.history.undoAsync().await()
            assertEquals("v1", d1.getRoot().getAs<JsonPrimitive>("k1").value)
        }
    }

    @Test
    fun test_undo_restores_removed_object_key() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root["k1"] = "v1"
            }.await()

            d1.updateAsync { root, _ ->
                root.remove("k1")
            }.await()

            d1.history.undoAsync().await()
            assertEquals("v1", d1.getRoot().getAs<JsonPrimitive>("k1").value)
        }
    }

    @Test
    fun test_redo_restores_undone_operation() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root["k1"] = "v1"
            }.await()

            d1.updateAsync { root, _ ->
                root["k1"] = "v2"
            }.await()

            d1.history.undoAsync().await()
            assertEquals("v1", d1.getRoot().getAs<JsonPrimitive>("k1").value)

            assertTrue(d1.history.canRedo())
            d1.history.redoAsync().await()
            assertEquals("v2", d1.getRoot().getAs<JsonPrimitive>("k1").value)
        }
    }

    @Test
    fun test_undo_reverts_counter_increase() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewCounter("counter", 10)
            }.await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(5)
            }.await()
            assertEquals(15, d1.getRoot().getAs<JsonCounter>("counter").value)

            d1.history.undoAsync().await()
            assertEquals(10, d1.getRoot().getAs<JsonCounter>("counter").value)
        }
    }

    @Test
    fun test_redo_cleared_on_new_local_edit() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root["k1"] = "v1"
            }.await()

            d1.updateAsync { root, _ ->
                root["k1"] = "v2"
            }.await()

            d1.history.undoAsync().await()
            assertTrue(d1.history.canRedo())

            // New edit should clear redo
            d1.updateAsync { root, _ ->
                root["k1"] = "v3"
            }.await()
            assertFalse(d1.history.canRedo())
        }
    }

    @Test
    fun test_attach_clears_undo_history() {
        // Pre-attach (offline) edits must not be reachable via undo after
        // attach, so setup/initialRoot ops cannot be undone. JS SDK PR #1238.
        withTwoClientsAndDocuments(
            attachDocuments = false,
            detachDocuments = false,
            syncMode = Manual,
        ) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root["k1"] = "v1"
            }.await()
            assertTrue(d1.history.canUndo())

            c1.attachDocument(d1, syncMode = Manual).await()
            assertFalse(d1.history.canUndo())
            assertFalse(d1.history.canRedo())

            c1.detachDocument(d1).await()
        }
    }

    @Test
    fun test_undo_empty_stack_is_noop() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            assertFalse(d1.history.canUndo())
            // Empty stack undo/redo is a silent no-op (JS SDK PR #1238).
            assertTrue(d1.history.undoAsync().await().isSuccess)
            assertTrue(d1.history.redoAsync().await().isSuccess)
            assertFalse(d1.history.canUndo())
            assertFalse(d1.history.canRedo())
        }
    }

    @Test
    fun test_multiple_sequential_undos_restore_initial_state() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root["k1"] = "A"
            }.await()

            d1.updateAsync { root, _ ->
                root["k1"] = "B"
            }.await()

            d1.updateAsync { root, _ ->
                root["k1"] = "C"
            }.await()
            assertEquals("C", d1.getRoot().getAs<JsonPrimitive>("k1").value)

            // Undo C → B
            d1.history.undoAsync().await()
            assertEquals("B", d1.getRoot().getAs<JsonPrimitive>("k1").value)

            // Undo B → A
            d1.history.undoAsync().await()
            assertEquals("A", d1.getRoot().getAs<JsonPrimitive>("k1").value)
        }
    }

    @Test
    fun test_multi_user_undo_reverts_only_own_changes() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            // c1 sets k1
            d1.updateAsync { root, _ ->
                root["k1"] = "c1-value"
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // c2 sets k2
            d2.updateAsync { root, _ ->
                root["k2"] = "c2-value"
            }.await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            // Both documents have both keys
            assertEquals("c1-value", d1.getRoot().getAs<JsonPrimitive>("k1").value)
            assertEquals("c2-value", d1.getRoot().getAs<JsonPrimitive>("k2").value)

            // c1 undoes — only c1's change should be reverted
            d1.history.undoAsync().await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // c2's value should still be there on both
            assertEquals("c2-value", d2.getRoot().getAs<JsonPrimitive>("k2").value)
        }
    }

    @Test
    fun test_undo_after_remote_sync() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            // c1 sets a key
            d1.updateAsync { root, _ ->
                root["k1"] = "original"
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // c1 updates the key
            d1.updateAsync { root, _ ->
                root["k1"] = "updated"
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            assertEquals("updated", d2.getRoot().getAs<JsonPrimitive>("k1").value)

            // c1 undoes
            d1.history.undoAsync().await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // Both should see the original value
            assertEquals("original", d1.getRoot().getAs<JsonPrimitive>("k1").value)
            assertEquals("original", d2.getRoot().getAs<JsonPrimitive>("k1").value)
        }
    }

    @Test
    fun test_tree_undo_and_redo_converge_after_a_concurrent_append() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree("tree", element("root") { element("p") {} })
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            listOf("1", "2", "3").forEachIndexed { index, value ->
                d1.updateAsync { root, _ ->
                    root.getAs<JsonTree>("tree").edit(index + 1, index + 1, text { value })
                }.await()
            }
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertFalse(d2.history.canUndo())

            d2.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(4, 4, text { " 456" })
            }.await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            d1.history.undoAsync().await()
            assertEquals("<root><p>12 456</p></root>", d1.getRoot().getAs<JsonTree>("tree").toXml())
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals("<root><p>12 456</p></root>", d2.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals(
                "<root><p>123 456</p></root>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )
            assertEquals(
                "<root><p>123 456</p></root>",
                d2.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            // Remote history changes stay outside B's history; B still undoes its own append.
            d2.history.undoAsync().await()
            assertEquals("<root><p>123</p></root>", d2.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    @Test
    fun test_text_undo_and_redo_converge_after_a_concurrent_overlapping_edit() {
        // F10 scenario 2: c1 edits and holds the undo; c2 commits a remote edit
        // overlapping the END of c1's pending undo range; c1 undoes — both replicas
        // must converge (the reconciled local range must equal the serialized wire
        // range, or the two replicas diverge).
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "abcde")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // c1 makes an edit to be undone later: delete [1,4) -> "ae".
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(1, 4, "")
            }.await()

            // c2 concurrently replaces [3,5) — overlaps the END of c1's pending undo range.
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(3, 5, "Z")
            }.await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            d1.history.undoAsync().await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            val text1 = d1.getRoot().getAs<JsonText>("text").toString()
            val text2 = d2.getRoot().getAs<JsonText>("text").toString()
            assertEquals(text1, text2)
        }
    }

    @Test
    fun test_tree_undo_consumes_a_change_whose_target_vanished_remotely() {
        withTwoClientsAndDocuments(
            attachDocuments = false,
            syncMode = Manual,
        ) { c1, c2, d1, d2, _ ->
            c1.attachDocument(
                document = d1,
                syncMode = Manual,
                initialRoot = mapOf(
                    "tree" to { key ->
                        setNewTree(
                            key,
                            element("root") { element("p") { text { "x" } } },
                        )
                    },
                ),
            ).await()
            c2.attachDocument(d2, syncMode = Manual).await()
            assertFalse(d1.history.canUndo())

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(2, 2, text { "y" })
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            d2.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(2, 3)
            }.await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertFalse(d1.hasLocalChanges())
            assertTrue(d1.history.canUndo())

            assertTrue(d1.history.undoAsync().await().isSuccess)

            assertEquals("<root><p>x</p></root>", d1.getRoot().getAs<JsonTree>("tree").toXml())
            assertFalse(d1.hasLocalChanges())
            assertFalse(d1.history.canUndo())
            assertFalse(d1.history.canRedo())
        }
    }
}
