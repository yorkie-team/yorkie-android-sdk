package dev.yorkie.api

import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.crossSync
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `split_ticket_test.ts` (JS SDK 4ec66cc0) as JVM tests (AC8-AC9):
 * an element split's issued tickets round-trip on the wire and the
 * applying replica consumes them instead of reconstructing them from
 * `executedAt` + content count, which under-counts once content has
 * descendants; an empty list (pre-field wire data) still replays via that
 * reconstruction.
 */
class OperationConverterSplitTicketTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private val treeTicket = TimeTicket(2L, 0u, "000000000000000000000009")

    private fun buildTreeRoot(): Pair<CrdtTree, CrdtRoot> {
        val rootNode = CrdtTreeElement(CrdtTreeNodeID(treeTicket, 0), DEFAULT_ROOT_TYPE)
        val tree = CrdtTree(rootNode, treeTicket)
        val obj =
            CrdtObject(
                createdAt = TimeTicket(1L, 0u, "000000000000000000000009"),
                memberNodes = ElementRht(),
            )
        val root = CrdtRoot(obj)
        root.rootObject.set("tree", tree, treeTicket)
        root.registerElement(tree, root.rootObject)
        return tree to root
    }

    @Test
    fun `round-trips the split tickets list`() {
        val (tree, _) = buildTreeRoot()
        val executedAt = TimeTicket(3L, 0u, "000000000000000000000009")
        val (fromPos, toPos) = tree.indexRangeToPosRange(0 to 0)
        val tickets = listOf(
            TimeTicket(3L, 1u, "000000000000000000000009"),
            TimeTicket(3L, 2u, "000000000000000000000009"),
        )
        val op = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = fromPos,
            toPos = toPos,
            contents = null,
            splitLevel = 1,
            executedAt = executedAt,
            splitTickets = tickets,
        )

        val decoded = listOf(op.toPBOperation()).toOperations().single() as TreeEditOperation

        assertEquals(tickets, decoded.splitTickets)
    }

    @Test
    fun `an ordinary edit carries no split ticket wire payload`() {
        val (tree, _) = buildTreeRoot()
        val executedAt = TimeTicket(3L, 0u, "000000000000000000000009")
        val (fromPos, toPos) = tree.indexRangeToPosRange(0 to 0)
        val op = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = fromPos,
            toPos = toPos,
            contents = null,
            splitLevel = 0,
            executedAt = executedAt,
        )

        val decoded = listOf(op.toPBOperation()).toOperations().single() as TreeEditOperation

        assertTrue(decoded.splitTickets.isEmpty())
    }

    // AC9: a TreeEdit payload built without field 11 (a change written
    // before the field existed) replays via the executedAt+content-count
    // reconstruction — byte-identical to pre-port behavior.
    @Test
    fun `empty split tickets on a splitting edit replays via reconstruction`() {
        val (tree, root) = buildTreeRoot()
        val insertedAt = TimeTicket(3L, 0u, "000000000000000000000009")
        val paragraph = CrdtTreeElement(CrdtTreeNodeID(insertedAt, 0), "p")
        val insertOp = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = tree.indexRangeToPosRange(0 to 0).first,
            toPos = tree.indexRangeToPosRange(0 to 0).second,
            contents = listOf(paragraph),
            splitLevel = 0,
            executedAt = insertedAt,
        )
        insertOp.execute(root, OpSource.Local, null)
        assertEquals("<root><p></p></root>", tree.toXml())

        val splitAt = TimeTicket(4L, 0u, "000000000000000000000009")
        val splitOp = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = tree.indexRangeToPosRange(1 to 1).first,
            toPos = tree.indexRangeToPosRange(1 to 1).second,
            contents = null,
            splitLevel = 1,
            executedAt = splitAt,
        )
        // splitTickets left at its default (empty) — simulates a payload
        // decoded without field 11.
        assertTrue(splitOp.splitTickets.isEmpty())
        splitOp.execute(root, OpSource.Local, null)

        assertEquals("<root><p></p><p></p></root>", tree.toXml())
    }

    // AC8 (headline): a splitting edit whose content has descendants
    // relays with split_tickets; the applying replica consumes them and
    // converges — no split ticket collides with the content's own id.
    @Test
    fun `splitting edit with descendant-bearing content converges without an id collision`() =
        runTest {
            val d1 = Document("test-doc")
            val d2 = Document("test-doc")
            d1.setActor(actor1)
            d2.setActor(actor2)

            d1.updateAsync { docRoot, _ ->
                docRoot.setNewTree(
                    "t",
                    element("r") {
                        element("p") { text { "ab" } }
                    },
                )
            }.await()
            crossSync(d1, d2)

            // Insert content with a descendant (an element wrapping an
            // element) AND split the ancestor in the same edit.
            d1.updateAsync { docRoot, _ ->
                docRoot.getAs<JsonTree>("t").edit(2, 2, 1, element("x") { element("y") { } })
            }.await()

            crossSync(d1, d2)

            assertEquals(d1.toJson(), d2.toJson())
        }
}
