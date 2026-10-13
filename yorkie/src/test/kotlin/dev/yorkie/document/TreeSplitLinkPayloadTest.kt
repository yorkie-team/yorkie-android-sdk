package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `tree_split_link_payload_test.ts` (JS SDK c8928853, #1375) as JVM
 * unit tests.
 *
 * A Set/Add/ArraySet payload carries a whole element, and the wire format
 * carries `insPrevID`/`insNextID` on every tree node it holds. The decode
 * path (`ElementConverter.kt`'s `PBJsonElementSimple.toCrdtElement`) strips
 * them on the way in, because such a payload is client-supplied and its
 * nodes can never be split products.
 *
 * A reverse operation reaches the document without passing the converter:
 * undo executes the deep copy it captured directly. Unless that copy is
 * stripped too (`Document.executeUndoRedo`), the replica that ran the undo
 * keeps links every other replica -- and the server -- decoded away, and the
 * two disagree from the next same-boundary split on.
 */
class TreeSplitLinkPayloadTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    /**
     * Lists every split-sibling link the tree under [key] carries, one
     * `(nodeID, prevID, nextID)` triple per node that has one. Empty when
     * the tree carries none.
     */
    private fun splitLinks(
        document: Document,
        key: String = "t",
    ): List<Triple<CrdtTreeNodeID, CrdtTreeNodeID?, CrdtTreeNodeID?>> {
        val lines = mutableListOf<Triple<CrdtTreeNodeID, CrdtTreeNodeID?, CrdtTreeNodeID?>>()
        fun walk(node: CrdtTreeNode) {
            if (node.insPrevID != null || node.insNextID != null) {
                lines += Triple(node.id, node.insPrevID, node.insNextID)
            }
            node.allChildren.forEach(::walk)
        }
        walk((document.getRootObject()[key] as CrdtTree).root)
        return lines
    }

    /** A document holding a tree whose span has been split, so its nodes carry split-sibling links. */
    private suspend fun withSplitTree(actorID: String): Document {
        val document = Document("test-doc")
        document.setActor(actorID)
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("span") {
                            text { "abcde" }
                        }
                    }
                },
            )
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1)
        }.await()
        return document
    }

    // Sanity precondition for the two cases below: this depends on
    // `CrdtTreeNode.split` setting insPrevID/insNextID, unrelated to the
    // ElementConverter.kt/Document.kt hunks. A red run is not constructible
    // against this production code — nothing in the strip paths runs in
    // this test (no decode, no undo).
    @Test
    fun `a split tree carries links`() = runTest {
        assertTrue(splitLinks(withSplitTree(actor1)).isNotEmpty())
    }

    // Red-then-green target: Document.kt's executeUndoRedo strip. Reverting
    // ONLY that hunk leaves d1's restored value holding the links its
    // deepcopy captured (the undo executes that copy directly, never
    // through the converter), while d2 -- decoding the same reverse
    // SetOperation over the wire -- still strips them via the (unreverted)
    // ElementConverter.kt change. d1 and d2 then disagree, and
    // this fails.
    @Test
    fun `an undone Set restores no split links, identically on a replica`() = runTest {
        val d1 = withSplitTree(actor1)
        val restoredXml = d1.getRoot().getAs<JsonTree>("t").toXml()

        d1.updateAsync { root, _ ->
            root.setNewTree("t", element("doc") { element("p") {} })
        }.await()
        d1.history.undoAsync().await()
        assertEquals(restoredXml, d1.getRoot().getAs<JsonTree>("t").toXml())
        assertTrue(
            splitLinks(d1).isEmpty(),
            "the undo-reinserted value must carry no peer split links",
        )

        val d2 = Document("test-doc")
        d2.setActor(actor2)
        crossSync(d1, d2, overWire = true)

        assertEquals(restoredXml, d2.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(splitLinks(d1), splitLinks(d2))
    }

    // Same as above, for the AddOperation reverse of a Remove (JS
    // `delete root.t`; Android's equivalent is JsonObject.remove).
    @Test
    fun `an undone Remove restores no split links, identically on a replica`() = runTest {
        val d1 = withSplitTree(actor1)
        val restoredXml = d1.getRoot().getAs<JsonTree>("t").toXml()

        d1.updateAsync { root, _ -> root.remove("t") }.await()
        d1.history.undoAsync().await()
        assertEquals(restoredXml, d1.getRoot().getAs<JsonTree>("t").toXml())
        assertTrue(
            splitLinks(d1).isEmpty(),
            "the undo-reinserted value must carry no peer split links",
        )

        val d2 = Document("test-doc")
        d2.setActor(actor2)
        crossSync(d1, d2, overWire = true)

        assertEquals(restoredXml, d2.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(splitLinks(d1), splitLinks(d2))
    }
}
