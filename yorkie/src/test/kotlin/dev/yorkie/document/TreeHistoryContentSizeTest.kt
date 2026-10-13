package dev.yorkie.document

import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports the `Document.reconcileHistoryEdits` half of JS `e41069df`
 * (yorkie-js-sdk#1360, backlog 009 second half): a remote Tree edit must
 * shift a pending LOCAL undo-stack entry by the edit's measured content
 * span (`TreeEditOperation.getContentSize()` ==
 * `insertedContentSize + splitSize`), not by the inserted-node COUNT
 * (`opInfo.nodes?.size`).
 *
 * A pure SPLIT's own reverse is offset-based (`toSplitReverseOperation`,
 * `undoFromOffset`/`undoToOffset`), so it is the case that actually exercises
 * `reconcileOperation`'s stored offsets (a plain insert/delete reverses by
 * ORIGINAL IDENTITY instead -- `RestoreMode.Restore` -- and is immune to this
 * bug by construction). Pins that a remote MULTI-CHARACTER text insert
 * shifts such a pending split-undo by its padded length (4), not by 1 (the
 * node count): RED at `5959dac0` (reverting this commit's `Document.kt` hunk
 * reproduces the wrong-boundary merge this test would otherwise catch, the
 * same failure shape as [TreeSplitUndoConcurrentTest]).
 */
class TreeHistoryContentSizeTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun JsonObject.tree() = getAs<JsonTree>("t")

    @Test
    fun `remote multi-character insert shifts a pending local split-undo by its padded size`() =
        runTest {
            val d1 = Document("test-doc")
            val d2 = Document("test-doc")
            d1.setActor(actor1)
            d2.setActor(actor2)

            // <doc><p>ab</p></doc>
            d1.updateAsync { root, _ ->
                root.setNewTree("t", element("doc") { element("p") { text { "ab" } } })
            }.await()
            crossSync(d1, d2)
            assertEquals("<doc><p>ab</p></doc>", d1.getRoot().tree().toXml())

            // d2 locally splits "ab" into "a"|"b" -- a PENDING, offset-based
            // undo entry (toSplitReverseOperation) that will merge the
            // boundary back together when undone.
            d2.updateAsync { root, _ ->
                root.tree().editByPath(listOf(0, 1), listOf(0, 1), splitLevel = 1)
            }.await()
            assertEquals("<doc><p>a</p><p>b</p></doc>", d2.getRoot().tree().toXml())

            // d1 (concurrently, before seeing d2's split) inserts a
            // 4-character text node at the very start of the first <p>, to
            // the LEFT of d2's pending split-undo boundary. The root's own
            // open/close tags are not index-counted, so index1 is the
            // position right before `a`.
            d1.updateAsync { root, _ -> root.tree().edit(1, 1, text { "WXYZ" }) }.await()
            assertEquals("<doc><p>WXYZab</p></doc>", d1.getRoot().tree().toXml())

            crossSync(d1, d2)
            crossSync(d1, d2)
            assertEquals("<doc><p>WXYZa</p><p>b</p></doc>", d2.getRoot().tree().toXml())

            // d2 undoes its OWN split. If the pending split-undo entry
            // shifted by the inserted-node COUNT (1) instead of the padded
            // size (4), the boundary-deletion would target 3 characters too
            // early and merge the wrong content (or throw an index error).
            d2.history.undoAsync().await()
            crossSync(d1, d2)
            crossSync(d1, d2)

            assertEquals("<doc><p>WXYZab</p></doc>", d1.getRoot().tree().toXml())
            assertEquals(d1.toJson(), d2.toJson())
        }
}
