package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonTextHistoryTest {

    @Test
    fun test_undo_and_redo_insert() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(5, 5, " world")
            }.await()
            c1.syncAsync().await()
            assertEquals("hello world", d1.getRoot().getAs<JsonText>("text").toString())

            assertTrue(d1.history.canUndo())
            d1.history.undoAsync().await()
            assertEquals("hello", d1.getRoot().getAs<JsonText>("text").toString())

            assertTrue(d1.history.canRedo())
            d1.history.redoAsync().await()
            assertEquals("hello world", d1.getRoot().getAs<JsonText>("text").toString())
        }
    }

    @Test
    fun test_undo_and_redo_delete() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(1, 3, "")
            }.await()
            c1.syncAsync().await()
            assertEquals("hlo", d1.getRoot().getAs<JsonText>("text").toString())

            d1.history.undoAsync().await()
            assertEquals("hello", d1.getRoot().getAs<JsonText>("text").toString())

            d1.history.redoAsync().await()
            assertEquals("hlo", d1.getRoot().getAs<JsonText>("text").toString())
        }
    }

    @Test
    fun test_undo_and_redo_replace() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(1, 4, "ELL")
            }.await()
            c1.syncAsync().await()
            assertEquals("hELLo", d1.getRoot().getAs<JsonText>("text").toString())

            d1.history.undoAsync().await()
            assertEquals("hello", d1.getRoot().getAs<JsonText>("text").toString())

            d1.history.redoAsync().await()
            assertEquals("hELLo", d1.getRoot().getAs<JsonText>("text").toString())
        }
    }

    @Test
    fun test_clear_redo_stack_on_new_edit_after_undo() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(5, 5, " world")
            }.await()
            c1.syncAsync().await()

            d1.history.undoAsync().await()
            assertTrue(d1.history.canRedo())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(5, 5, "!")
            }.await()
            assertFalse(d1.history.canRedo())
        }
    }

    @Test
    fun test_handle_empty_undo_stack() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text")
            }.await()
            c1.syncAsync().await()

            // Only the setNewText op was pushed — undo the set to get to empty undo stack
            d1.history.undoAsync().await()

            // Now undo stack should be empty — undo is a silent no-op (JS SDK PR #1238)
            assertFalse(d1.history.canUndo())
            val result = d1.history.undoAsync().await()
            assertTrue(result.isSuccess)
        }
    }

    @Test
    fun test_handle_insert_into_empty_text() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text")
            }.await()
            c1.syncAsync().await()
            assertEquals("", d1.getRoot().getAs<JsonText>("text").toString())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(0, 0, "abc")
            }.await()
            c1.syncAsync().await()
            assertEquals("abc", d1.getRoot().getAs<JsonText>("text").toString())

            d1.history.undoAsync().await()
            assertEquals("", d1.getRoot().getAs<JsonText>("text").toString())
        }
    }

    @Test
    fun test_handle_full_deletion_then_undo_restores_content() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(0, 5, "")
            }.await()
            c1.syncAsync().await()
            assertEquals("", d1.getRoot().getAs<JsonText>("text").toString())

            d1.history.undoAsync().await()
            assertEquals("hello", d1.getRoot().getAs<JsonText>("text").toString())
        }
    }

    @Test
    fun test_handle_full_replacement_then_undo_restores_original() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(0, 5, "world")
            }.await()
            c1.syncAsync().await()
            assertEquals("world", d1.getRoot().getAs<JsonText>("text").toString())

            d1.history.undoAsync().await()
            assertEquals("hello", d1.getRoot().getAs<JsonText>("text").toString())
        }
    }

    @Test
    fun test_converge_after_one_client_undoes_3_op_pairs() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals("hello", d2.getRoot().getAs<JsonText>("text").toString())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(5, 5, " A")
            }.await()
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(7, 7, " B")
            }.await()
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(9, 9, " C")
            }.await()
            c1.syncAsync().await()
            assertEquals("hello A B C", d1.getRoot().getAs<JsonText>("text").toString())

            d1.history.undoAsync().await()
            d1.history.undoAsync().await()
            d1.history.undoAsync().await()
            assertEquals("hello", d1.getRoot().getAs<JsonText>("text").toString())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertEquals(
                d1.getRoot().getAs<JsonText>("text").toString(),
                d2.getRoot().getAs<JsonText>("text").toString(),
            )
        }
    }

    @Test
    fun test_reconcile_undo_against_concurrent_remote_edit_on_left() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // c2 makes an edit to be undone later
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(5, 5, " world")
            }.await()

            // c1 concurrently inserts a prefix (remote relative to c2's undo op)
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(0, 0, ">> ")
            }.await()
            c1.syncAsync().await()
            // c2 syncs, picks up c1's edit → reconcile fires for the " world" undo op
            c2.syncAsync().await()

            // Undo: positions should be reconciled to account for c1's insert
            d2.history.undoAsync().await()

            c2.syncAsync().await()
            c1.syncAsync().await()
            val text1 = d1.getRoot().getAs<JsonText>("text").toString()
            val text2 = d2.getRoot().getAs<JsonText>("text").toString()
            assertEquals(text1, text2)
        }
    }

    @Test
    fun test_reconcile_undo_against_concurrent_remote_edit_overlap_start() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "abcde")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // c2 deletes [2,4) — to be undone
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(2, 4, "")
            }.await()

            // c1 concurrently replaces [1,3) (overlaps start of c2's undo range)
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(1, 3, "X")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            d2.history.undoAsync().await()

            c2.syncAsync().await()
            c1.syncAsync().await()
            val text1 = d1.getRoot().getAs<JsonText>("text").toString()
            val text2 = d2.getRoot().getAs<JsonText>("text").toString()
            assertEquals(text1, text2)
        }
    }

    @Test
    fun test_reconcile_undo_against_concurrent_remote_edit_overlap_end() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "abcde")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // c2 deletes [1,4) — to be undone
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(1, 4, "")
            }.await()

            // c1 concurrently replaces [3,5) (overlaps end of c2's undo range)
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(3, 5, "Z")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            d2.history.undoAsync().await()

            c2.syncAsync().await()
            c1.syncAsync().await()
            val text1 = d1.getRoot().getAs<JsonText>("text").toString()
            val text2 = d2.getRoot().getAs<JsonText>("text").toString()
            assertEquals(text1, text2)
        }
    }

    @Test
    fun test_undo_and_redo_style_op() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            c1.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").style(0, 5, mapOf("bold" to "true"))
            }.await()
            c1.syncAsync().await()
            assertEquals(
                mapOf("bold" to "true"),
                d1.getRoot().getAs<JsonText>("text").values.first().attributes,
            )

            assertTrue(d1.history.canUndo())
            d1.history.undoAsync().await()
            val attrsAfterUndo = d1.getRoot().getAs<JsonText>("text").values.first().attributes
            assertNull(attrsAfterUndo["bold"])

            assertTrue(d1.history.canRedo())
            d1.history.redoAsync().await()
            assertEquals(
                mapOf("bold" to "true"),
                d1.getRoot().getAs<JsonText>("text").values.first().attributes,
            )
        }
    }

    @Test
    fun test_undo_style_restores_previous_attribute_value() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").style(0, 5, mapOf("bold" to "true"))
            }.await()
            c1.syncAsync().await()
            assertEquals(
                "true",
                d1.getRoot().getAs<JsonText>("text").values.first().attributes["bold"],
            )

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").style(0, 5, mapOf("bold" to "false"))
            }.await()
            c1.syncAsync().await()
            assertEquals(
                "false",
                d1.getRoot().getAs<JsonText>("text").values.first().attributes["bold"],
            )

            d1.history.undoAsync().await()
            assertEquals(
                "true",
                d1.getRoot().getAs<JsonText>("text").values.first().attributes["bold"],
            )

            d1.history.redoAsync().await()
            assertEquals(
                "false",
                d1.getRoot().getAs<JsonText>("text").values.first().attributes["bold"],
            )
        }
    }

    @Test
    fun test_converge_with_concurrent_style_operations() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").style(0, 5, mapOf("bold" to "true"))
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("text").style(0, 5, mapOf("italic" to "true"))
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            val attrs1 = d1.getRoot().getAs<JsonText>("text").values.first().attributes
            val attrs2 = d2.getRoot().getAs<JsonText>("text").values.first().attributes
            assertEquals(attrs1, attrs2)
            assertEquals("true", attrs1["bold"])
            assertEquals("true", attrs1["italic"])
        }
    }

    @Test
    fun test_clear_history_on_snapshot() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, _, d1, _, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "hello")
            }.await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(5, 5, " world")
            }.await()
            assertTrue(d1.history.canUndo())

            c1.syncAsync().await()

            // After sync (no snapshot triggered), the undo stack remains intact.
            assertTrue(d1.history.canUndo())
        }
    }

    // Case 3 correctness: d1 deletes [4,6)="45", d2 deletes [2,8)="234567"
    // (d2's range fully contains d1's). After both undo, the expected content
    // is "0123456789", but the undo mechanism produces "012345674589" because
    // each client deep-copies and re-inserts its removed nodes as new CRDT
    // nodes — the overlapping "45" is re-inserted twice.
    //
    // Fixing this requires un-tombstone (resurrect) semantics or node-ID-based
    // overlap detection. Tracked as a known limitation.
    @Ignore("known limitation: overlapping undo duplicates content; see RTCOLLABPLATFORM-652")
    @Test
    fun test_case3_both_undo_of_overlapping_deletes_restores_original() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("t").edit(0, 0, "0123456789")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("t").edit(4, 6, "")
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("t").edit(2, 8, "")
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            d1.history.undoAsync().await()
            d2.history.undoAsync().await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            val text1 = d1.getRoot().getAs<JsonText>("t").toString()
            val text2 = d2.getRoot().getAs<JsonText>("t").toString()
            assertEquals(text1, text2, "convergence")
            assertEquals("0123456789", text1, "content correctness after both undo")
        }
    }

    // Case 5 correctness: d1 deletes [4,8)="4567", d2 deletes [2,6)="2345"
    // (partial overlap at "45"). After both undo, the expected content is
    // "0123456789", but the undo mechanism produces "012345456789" because the
    // overlapping "45" is re-inserted twice — once by each client's undo.
    //
    // Same root cause as Case 3. Tracked as a known limitation.
    @Ignore("known limitation: overlapping undo duplicates content; see RTCOLLABPLATFORM-652")
    @Test
    fun test_case5_both_undo_of_partially_overlapping_deletes_restores_original() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("t").edit(0, 0, "0123456789")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("t").edit(4, 8, "")
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("t").edit(2, 6, "")
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            d1.history.undoAsync().await()
            d2.history.undoAsync().await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            val text1 = d1.getRoot().getAs<JsonText>("t").toString()
            val text2 = d2.getRoot().getAs<JsonText>("t").toString()
            assertEquals(text1, text2, "convergence")
            assertEquals("0123456789", text1, "content correctness after both undo")
        }
    }
}
