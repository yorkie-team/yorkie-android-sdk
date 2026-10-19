package dev.yorkie.document

import dev.yorkie.api.toOperations
import dev.yorkie.api.toPBOperation
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.VersionVector
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `tree_split_cascade_regression_test.ts` (JS SDK `6f690a14`, #1456)
 * as JVM convergence tests. Each scenario loses text that nobody deleted if
 * the split-sibling cascade also runs when the delete lost the LWW of the
 * element -- a tempting extra step for #1408 that the fix leaves out on
 * purpose.
 *
 * Mirrors the JS test's one-directional `Replica.flush()`/`receive()` (NOT
 * a bidirectional sync) so the exact delivery order the JS cases probe --
 * which concurrent change a replica applies first -- is preserved.
 */
class TreeSplitCascadeRegressionTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"
    private val actor3 = "000000000000000000000003"

    private suspend fun Document.tree(key: String = "t") = getRoot().getAs<JsonTree>(key)
    private fun JsonObject.tree(key: String = "t") = getAs<JsonTree>(key)
    private suspend fun Document.xml(key: String = "t") = tree(key).toXml()
    private fun visibleText(xml: String) = xml.replace(Regex("<[^>]*>"), "")

    /** Captures this document's pending changes (over the wire) and self-acks them. */
    private suspend fun Document.flush(): Pair<String, List<Change>> {
        val pack = createChangePack()
        applyChangePack(
            ChangePack(
                getKey(),
                CheckPoint(0, pack.checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
        val wireChanges = pack.changes.map { change ->
            change.copy(operations = change.operations.map { it.toPBOperation() }.toOperations())
        }
        return pack.documentKey to wireChanges
    }

    /** Applies another replica's flushed changes. */
    private suspend fun Document.receive(flushed: Pair<String, List<Change>>) {
        val (key, changes) = flushed
        applyChangePack(
            ChangePack(key, CheckPoint.InitialCheckPoint, changes, null, false, VersionVector()),
        )
    }

    /**
     * [n] replicas holding
     * `<p><span>abc</span><span>de</span></p><p><span>fg</span></p>`.
     */
    private suspend fun start(n: Int): List<Document> {
        val actors = listOf(actor1, actor2, actor3)
        val docs = (0 until n).map { i -> Document("doc").apply { setActor(actors[i]) } }
        docs[0].updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("span") { text { "abc" } }
                        element("span") { text { "de" } }
                    }
                    element("p") {
                        element("span") { text { "fg" } }
                    }
                },
            )
        }.await()
        val init = docs[0].flush()
        for (i in 1 until n) {
            docs[i].receive(init)
        }
        return docs
    }

    // The editor's Enter right before a styled run.
    private val enterAtSpanStart: (JsonTree) -> Unit = { t ->
        t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), splitLevel = 1)
        t.splitByPath(listOf(0, 2))
        t.editByPath(listOf(0, 1), listOf(0, 2))
    }
    private val splitAt0DropLeft: (JsonTree) -> Unit = { t ->
        t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), splitLevel = 1)
        t.editByPath(listOf(0, 1), listOf(0, 2))
    }

    // r1 deletes the styled span and undoes it; r2 (newer ticket) presses
    // Enter right before it. Both replicas keep "de" (they differ by an
    // empty <span>). Cascading on a lost LWW would let r1's delete
    // tombstone r2's split product holding "de" with r1's ticket; r1's undo
    // restores only what r1 knew, so "de" would stay under a tombstoned
    // <span> on r2.
    @Test
    fun `delete span + undo vs Enter at span start - de stays visible on both`() = runTest {
        assertDeSurvives(enterAtSpanStart)
    }

    @Test
    fun `delete span + undo vs split at 0 + drop left - de stays visible on both`() = runTest {
        assertDeSurvives(splitAt0DropLeft)
    }

    private suspend fun assertDeSurvives(op: (JsonTree) -> Unit) {
        val (r1, r2) = start(2)
        r1.updateAsync { root, _ -> root.tree().editByPath(listOf(0, 1), listOf(0, 2)) }.await()
        r1.history.undoAsync().await()
        val p1 = r1.flush()
        r2.updateAsync { root, _ -> op(root.tree()) }.await()
        val p2 = r2.flush()
        r2.receive(p1)
        r1.receive(p2)
        assertTrue(visibleText(r1.xml()).contains("de"), "r1 lost \"de\": ${r1.xml()}")
        assertTrue(visibleText(r2.xml()).contains("de"), "r2 lost \"de\": ${r2.xml()}")
    }

    // r1 does the #1408 shape; r2 (newer ticket) does the same and types "y"
    // at the start of its new span. Nobody deletes "de" or "y". Cascading on
    // a lost LWW would tombstone r2's product with "y" in it on r2 as well.
    // r1 does not show "y": on r1, r2's product is split off a span r1 has
    // already deleted, so it is born tombstoned. That is not the cascade.
    @Test
    fun `Enter plus type vs Enter - the typed y survives on the typist replica`() = runTest {
        val (r1, r2) = start(2)
        r1.updateAsync { root, _ -> splitAt0DropLeft(root.tree()) }.await()
        val p1 = r1.flush()
        r2.updateAsync { root, _ ->
            val t = root.tree()
            splitAt0DropLeft(t)
            t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), text { "y" })
        }.await()
        val p2 = r2.flush()
        r2.receive(p1)
        r1.receive(p2)
        assertTrue(visibleText(r1.xml()).contains("de"), "r1: ${r1.xml()}")
        assertTrue(visibleText(r2.xml()).contains("yde"), "r2: ${r2.xml()}")
    }

    // No undo. r1 splits the second span in the middle with level 2 (Enter
    // in the middle of a styled run): <p>abc,d</p><p>e</p>. r2, concurrently,
    // deletes "c" and "d" across the span boundary, merging the second span
    // into the first. r3 has seen r1 and does the #1408 shape on the "d"
    // span. Nobody deleted "e" and every replica keeps it. Cascading on a
    // lost LWW would let the merge-turned-delete on r3 run through the
    // whole chain, including r1's level-2 product in the next paragraph
    // that holds "e".
    @Test
    fun `Enter level 2 vs cross-span merge vs split-at-0+drop - e survives`() = runTest {
        for (rev in listOf(false, true)) {
            val (r1, r2, r3) = start(3)
            r1.updateAsync { root, _ ->
                root.tree().editByPath(listOf(0, 1, 1), listOf(0, 1, 1), splitLevel = 2)
            }.await()
            val p1 = r1.flush()
            r2.updateAsync { root, _ ->
                root.tree().editByPath(listOf(0, 0, 2), listOf(0, 1, 1))
            }.await()
            val p2 = r2.flush()
            r3.receive(p1)
            r3.updateAsync { root, _ -> splitAt0DropLeft(root.tree()) }.await()
            val p3 = r3.flush()

            if (rev) {
                r1.receive(p3)
                r1.receive(p2)
            } else {
                r1.receive(p2)
                r1.receive(p3)
            }
            r2.receive(p1)
            r2.receive(p3)
            r3.receive(p2)

            for ((i, r) in listOf(r1, r2, r3).withIndex()) {
                assertTrue(
                    visibleText(r.xml()).contains("e"),
                    "r${i + 1} (rev=$rev) lost \"e\": ${r.xml()}",
                )
            }
        }
    }

    // Same family, convergence: the replicas agree; cascading on a lost LWW
    // would leave r2 without "e".
    @Test
    fun `delete span + undo vs split-at-0+drop vs split-in-middle+drop - converge`() = runTest {
        val (r1, r2, r3) = start(3)
        r1.updateAsync { root, _ -> root.tree().editByPath(listOf(0, 1), listOf(0, 2)) }.await()
        r1.history.undoAsync().await()
        val p1 = r1.flush()
        r2.updateAsync { root, _ -> splitAt0DropLeft(root.tree()) }.await()
        val p2 = r2.flush()
        r3.receive(p1)
        r3.updateAsync { root, _ ->
            val t = root.tree()
            t.editByPath(listOf(0, 1, 1), listOf(0, 1, 1), splitLevel = 1)
            t.editByPath(listOf(0, 1), listOf(0, 2))
        }.await()
        val p3 = r3.flush()
        r1.receive(p2)
        r1.receive(p3)
        r2.receive(p1)
        r2.receive(p3)
        r3.receive(p2)

        assertEquals(r1.xml(), r2.xml(), "r2 diverged from r1")
        assertEquals(r1.xml(), r3.xml(), "r3 diverged from r1")
    }
}
