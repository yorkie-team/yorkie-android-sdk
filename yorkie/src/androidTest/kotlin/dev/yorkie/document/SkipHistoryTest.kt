package dev.yorkie.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers AC4: a `skipHistory = true` mutation syncs to a peer and both
 * replicas converge identically to an ordinary update. Also covers the
 * spec 009 C9 convergence probes for the spec 008 single-replica
 * skipHistory/undo expectations: a pending tree undo entry reconciled
 * across a skipHistory index shift (F2), and a skipHistory overwrite of
 * an ordinary key that undoes as a no-op and redoes by LWW (F11) — both
 * asserted equal on both replicas after undo, after redo, and after
 * bidirectional sync.
 */
@RunWith(AndroidJUnit4::class)
class SkipHistoryTest {

    @Test
    fun skip_history_update_converges_to_peer_like_an_ordinary_update() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync(skipHistory = true) { root, _ ->
                root["k1"] = "bookkeeping"
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()

            assertJsonContentEquals("""{"k1":"bookkeeping"}""", d1.toJson())
            assertJsonContentEquals("""{"k1":"bookkeeping"}""", d2.toJson())

            // The skipHistory write left no undo entry on the originating client.
            assertFalse(d1.history.canUndo())
        }
    }

    @Test
    fun skip_history_tree_index_shift_undo_redo_converges_on_both_replicas() {
        // F2 case (single-replica pin: DocumentSkipHistoryTest."skipHistory reconciles a
        // pending tree undo entry across index shift"): c1's ordinary insert leaves a
        // pending undo entry; c1's skipHistory insert shifts its index range; undo/redo
        // on c1 must reconcile identically once synced to c2.
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree("tree", element("root") { element("p") {} })
            }.await()
            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(1, 1, text { "A" })
            }.await()
            assertEquals("<root><p>A</p></root>", d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.updateAsync(skipHistory = true) { root, _ ->
                root.getAs<JsonTree>("tree").edit(1, 1, text { "X" })
            }.await()
            assertEquals("<root><p>XA</p></root>", d1.getRoot().getAs<JsonTree>("tree").toXml())

            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals(
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
                d2.getRoot().getAs<JsonTree>("tree").toXml(),
            )
            assertEquals("<root><p>XA</p></root>", d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.undoAsync().await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals(
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
                d2.getRoot().getAs<JsonTree>("tree").toXml(),
            )
            assertEquals("<root><p>X</p></root>", d1.getRoot().getAs<JsonTree>("tree").toXml())

            d1.history.redoAsync().await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals(
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
                d2.getRoot().getAs<JsonTree>("tree").toXml(),
            )
            assertEquals("<root><p>XA</p></root>", d1.getRoot().getAs<JsonTree>("tree").toXml())
        }
    }

    @Test
    fun skip_history_same_key_overwrite_undo_noop_redo_lww_converges() {
        // F11 case (single-replica pin: DocumentSkipHistoryTest."skipHistory overwrite of
        // an ordinary key undoes as a no-op and redoes by LWW"): c1's skipHistory write
        // overwrites an ordinary key; undo is a no-op (the reverse op targets an
        // already-superseded element); redo resurrects the original value by LWW with a
        // freshly issued ticket. Both must converge once synced to c2.
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root["k"] = "1"
            }.await()

            d1.updateAsync(skipHistory = true) { root, _ ->
                root["k"] = "bookkeeping"
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k":"bookkeeping"}""", d1.toJson())
            assertJsonContentEquals("""{"k":"bookkeeping"}""", d2.toJson())

            d1.history.undoAsync().await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k":"bookkeeping"}""", d1.toJson())
            assertJsonContentEquals("""{"k":"bookkeeping"}""", d2.toJson())

            d1.history.redoAsync().await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k":"1"}""", d1.toJson())
            assertJsonContentEquals("""{"k":"1"}""", d2.toJson())
        }
    }
}
