package dev.yorkie.document.crdt

import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * Ports `tree_split_link_guard_test.ts` (JS SDK c8928853, #1375) as JVM unit
 * tests (spec 033 AC1).
 *
 * `insNextID` is a structural pointer only [CrdtTreeNode.split] is supposed
 * to write, but it also arrives verbatim from client-supplied bytes, so a
 * document rebuilt from stored client changes can hold a chain that loops
 * back on itself. An unbounded walk of that chain spins the applying task
 * forever -- and [CrdtTree.edit]'s delete cascade also appends to its
 * removal list on every turn, so it burns memory while it spins.
 *
 * The converter now strips the field from client-supplied content
 * (`toCrdtTreeNodesWhenEdit` for operation content, `dropSplitLinksInElement`
 * for the element bytes a Set/Add/ArraySet carries -- unit 1B), so these
 * chains should no longer be constructible going forward. The walks stay
 * bounded anyway: a document stored before that change already carries
 * whatever a client sent.
 */
class CrdtTreeSplitLinkGuardTest {

    // `knownActor` creates the nodes the operation under test knows about.
    private val knownActor = "000000000000000000000001"

    // `cycleActor` creates the two nodes that point at each other. They have
    // to be unknown to the operation's version vector: that is the only
    // case these walks follow the chain at all.
    private val cycleActor = "000000000000000000000002"

    // `remoteActor` runs the operation.
    private val remoteActor = "000000000000000000000003"

    /** Hands out tickets with an increasing lamport for a given actor. */
    private fun ticketer(): (String) -> TimeTicket {
        var lamport = 0L
        return { actor ->
            lamport += 1
            TimeTicket(lamport, 0u, actor)
        }
    }

    /**
     * Builds
     * ```
     * <r><p>ab</p><p></p><p></p><p>cd</p></r>
     * ```
     * where the two middle paragraphs were created by [cycleActor] and point
     * at each other through `insNextID`, and both the first paragraph and
     * its text node link into that cycle. Every `insNextID` walk reachable
     * from the first paragraph runs into it.
     */
    private fun poisonedTree(): CrdtTree {
        val issue = ticketer()
        val issueKnown: () -> TimeTicket = { issue(knownActor) }
        fun element(actor: String, type: String) =
            CrdtTreeElement(CrdtTreeNodeID(issue(actor), 0), type)
        fun text(actor: String, value: String) =
            CrdtTreeText(CrdtTreeNodeID(issue(actor), 0), value)

        val tree = CrdtTree(element(knownActor, "r"), issue(knownActor))

        val first = element(knownActor, "p")
        tree.edit(tree.findPos(0) to tree.findPos(0), listOf(first), 0, issueKnown(), issueKnown)

        val textNode = text(knownActor, "ab")
        tree.edit(tree.findPos(1) to tree.findPos(1), listOf(textNode), 0, issueKnown(), issueKnown)

        val left = element(cycleActor, "p")
        tree.edit(tree.findPos(4) to tree.findPos(4), listOf(left), 0, issueKnown(), issueKnown)

        val right = element(cycleActor, "p")
        tree.edit(tree.findPos(6) to tree.findPos(6), listOf(right), 0, issueKnown(), issueKnown)

        val last = element(knownActor, "p")
        tree.edit(tree.findPos(8) to tree.findPos(8), listOf(last), 0, issueKnown(), issueKnown)

        tree.edit(
            tree.findPos(9) to tree.findPos(9),
            listOf(text(knownActor, "cd")),
            0,
            issueKnown(),
            issueKnown,
        )

        assertEquals("<r><p>ab</p><p></p><p></p><p>cd</p></r>", tree.toXml())

        textNode.insNextID = left.id
        first.insNextID = left.id
        left.insNextID = right.id
        right.insNextID = left.id

        return tree
    }

    // knownActor's nodes are known, cycleActor's are not.
    private val editedAt = TimeTicket(TimeTicket.MAX_LAMPORT, 0u, remoteActor)
    private val vector = maxVectorOf(listOf(knownActor, remoteActor))

    // `edit` walks the chain twice: Phase 3 range narrowing follows
    // fromLeft's chain looking for a sibling under toParent, and the delete
    // cascade cascades the delete to unknown split siblings of every element
    // it removes.
    @Test(timeout = 5_000)
    fun `edit over the cycle terminates`() {
        val tree = poisonedTree()
        val range = tree.findPos(3) to tree.findPos(11)

        tree.edit(range, null, 0, editedAt, { editedAt }, vector)

        assertEquals(true, tree.toXml().isNotEmpty())
    }

    // `style` and `removeStyle` propagate to unknown split siblings along
    // the same chain.
    @Test(timeout = 5_000)
    fun `style over the cycle terminates`() {
        val tree = poisonedTree()

        tree.style(tree.findPos(0) to tree.findPos(12), mapOf("b" to "t"), editedAt, vector)

        assertEquals(true, tree.toXml().isNotEmpty())
    }

    @Test(timeout = 5_000)
    fun `remove style over the cycle terminates`() {
        val tree = poisonedTree()

        tree.removeStyle(tree.findPos(0) to tree.findPos(12), listOf("b"), editedAt, vector)

        assertEquals(true, tree.toXml().isNotEmpty())
    }

    @Test
    fun `dropSplitLinks clears the links on the node and every descendant`() {
        val issue = ticketer()

        val root = CrdtTreeElement(CrdtTreeNodeID(issue(knownActor), 0), "r")
        val para = CrdtTreeElement(CrdtTreeNodeID(issue(knownActor), 0), "p")
        val text = CrdtTreeText(CrdtTreeNodeID(issue(knownActor), 0), "ab")
        root.append(para)
        para.append(text)

        val other = CrdtTreeNodeID(issue(cycleActor), 0)
        root.insNextID = other
        para.insPrevID = other
        para.insNextID = other
        text.insNextID = other

        root.dropSplitLinks()

        for (node in listOf(root, para, text)) {
            assertNull(node.insPrevID)
            assertNull(node.insNextID)
        }
    }
}
