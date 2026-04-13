package dev.yorkie.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.json.JsonCounter
import dev.yorkie.document.json.JsonPrimitive
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
    fun test_undo_empty_stack_throws() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            assertFalse(d1.history.canUndo())
            val result = d1.history.undoAsync().await()
            assertTrue(result.isFailure)
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
}
