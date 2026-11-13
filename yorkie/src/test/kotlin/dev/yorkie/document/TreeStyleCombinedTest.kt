package dev.yorkie.document

import dev.yorkie.api.toOperations
import dev.yorkie.api.toPBOperation
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.JsonTree.ElementNode
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.crossSyncOverWire
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins JS `tree_style_combined_test.ts` (`feb00cff`, #1503, 2 cases): a `tree.style()`
 * that overwrites some attributes and adds others reverses into a single
 * [TreeStyleOperation] carrying BOTH halves -- the overwritten keys' previous values in
 * [TreeStyleOperation.attributes] and the added keys in [TreeStyleOperation.attributesToRemove].
 * The wire already carries both fields on Android (`OperationConverter.kt` encode `:352-355`-ish,
 * decode `:181-188`-ish) and execution already runs the Go if/else (`TreeStyleOperation.execute`:
 * `attributes` non-empty -> style, else `attributesToRemove` non-empty -> removeStyle) -- pin-only,
 * no production change. The required RED was confirmed by hand against a scratch
 * one-field encoder/decoder (the pre-#1503 JS shape), not against HEAD, which already passes.
 */
class TreeStyleCombinedTest {

    private fun newDoc(actor: String): Document {
        val doc = Document("tree-style-combined")
        doc.setActor(actor)
        return doc
    }

    private suspend fun Document.xml(): String = getRoot().getAs<JsonTree>("t").toXml()

    @Test
    fun `converges after undoing a style that overwrote and added attributes`() = runTest {
        val a = newDoc("000000000000000000000001")
        val b = newDoc("000000000000000000000002")

        a.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                ElementNode("doc", listOf(ElementNode("p", attributes = mapOf("b" to "x")))),
            )
        }.await()
        crossSyncOverWire(a, b)

        // `b` is overwritten and `c` is added, so the reverse operation has to restore
        // the one and remove the other.
        val overwriteAndAdd = mapOf("b" to "y", "c" to "z")
        a.updateAsync { root, _ -> root.getAs<JsonTree>("t").style(0, 1, overwriteAndAdd) }.await()
        crossSyncOverWire(a, b)
        assertEquals("""<doc><p b="y" c="z"></p></doc>""", a.xml())
        assertEquals(a.xml(), b.xml())

        a.history.undoAsync().await()
        crossSyncOverWire(a, b)

        // Go yields the same tree on both replicas: the reverse restores `b` and leaves
        // `c`, because execute applies the set half only. Before the #1503 wire fix the
        // peer decoded the remove half only and ended at `<p b="y"></p>`, diverging for
        // good.
        assertEquals("""<doc><p b="x" c="z"></p></doc>""", a.xml())
        assertEquals(a.xml(), b.xml())
    }

    @Test
    fun `keeps both halves across the protobuf round trip`() = runTest {
        val nodeID = CrdtTreeNodeID(TimeTicket(1, 0u, "000000000000000000000001"), 0)
        val pos = CrdtTreePos(nodeID, nodeID)
        val op = TreeStyleOperation(
            parentCreatedAt = TimeTicket.InitialTimeTicket,
            fromPos = pos,
            toPos = pos,
            executedAt = TimeTicket(2, 0u, "000000000000000000000001"),
            attributes = mapOf("b" to "x"),
            attributesToRemove = listOf("c"),
        )

        val decoded = listOf(op.toPBOperation()).toOperations().single()

        assertIs<TreeStyleOperation>(decoded)
        assertEquals(mapOf("b" to "x"), decoded.attributes)
        assertEquals(listOf("c"), decoded.attributesToRemove)
    }
}
