package dev.yorkie.util

import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID.Companion.InitialCrdtTreeNodeID
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IndexTreeTest {

    @Test
    fun `should find treePos from the offsets`() {
        //    0   1 2 3 4 5 6    7   8 9  10 11 12 13    14
        // <r> <p> h e l l o </p> <p> w  o  r  l  d  </p>  </r>
        val tree = createIndexTree(
            createElementNode(
                "r",
                createElementNode("p", createTextNode("hello")),
                createElementNode("p", createTextNode("world")),
            ),
        )
        var pos = tree.findTreePos(0)
        assertEquals("r" to 0, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(1)
        assertEquals("hello" to 0, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(6)
        assertEquals("hello" to 5, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(6, false)
        assertEquals("p" to 1, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(7)
        assertEquals("r" to 1, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(8)
        assertEquals("world" to 0, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(13)
        assertEquals("world" to 5, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(14)
        assertEquals("r" to 2, pos.node.toDiagnostic() to pos.offset)
    }

    @Test
    fun `should throw IllegalArgumentException when trying to find treePos with invalid index`() {
        val tree = createIndexTree(DefaultRootNode)

        assertThrows(IllegalArgumentException::class.java) {
            tree.findTreePos(tree.size + 1)
        }
    }

    @Test
    fun `should find common ancestor of two given nodes`() {
        val tree = createIndexTree(
            createElementNode(
                "root",
                createElementNode(
                    "p",
                    createElementNode("b", createTextNode("ab")),
                    createElementNode("b", createTextNode("cd")),
                ),
            ),
        )

        val nodeAB = tree.findTreePos(3, true).node
        val nodeCD = tree.findTreePos(7, true).node

        assertEquals("ab", nodeAB.toDiagnostic())
        assertEquals("cd", nodeCD.toDiagnostic())
        assertEquals("p", findCommonAncestor(nodeAB, nodeCD)?.type)
    }

    @Test
    fun `should traverse tokens between two given positions`() {
        //       0   1 2 3    4   5 6 7 8    9   10 11 12   13
        // <root> <p> a b </p> <p> c d e </p> <p>  f  g  </p>  </root>
        val tree = createIndexTree(
            createElementNode(
                "root",
                createElementNode("p", createTextNode("a"), createTextNode("b")),
                createElementNode("p", createTextNode("cde")),
                createElementNode("p", createTextNode("fg")),
            ),
        )
        assertEquals(
            listOf("b:Text", "p:End", "p:Start", "cde:Text", "p:End", "p:Start", "fg:Text"),
            tree.tokensBetween(2, 11),
        )
        assertEquals(
            listOf("b:Text", "p:End", "p:Start", "cde:Text"),
            tree.tokensBetween(2, 6),
        )
        assertEquals(listOf("p:Start"), tree.tokensBetween(0, 1))
        assertEquals(listOf("p:End"), tree.tokensBetween(3, 4))
        assertEquals(listOf("p:End", "p:Start"), tree.tokensBetween(3, 5))
    }

    @Test
    fun `should throw IllegalArgumentException when traversing nodes within invalid ranges`() {
        val tree = createIndexTree(DefaultRootNode)

        assertThrows(IllegalArgumentException::class.java) {
            tree.tokensBetween(tree.size, 0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            tree.tokensBetween(tree.size + 1, tree.size + 2)
        }

        assertThrows(IllegalArgumentException::class.java) {
            tree.tokensBetween(tree.size, tree.size + 1)
        }
    }

    @Test
    fun `should convert index to treePos and vice versa`() {
        //       0   1 2 3 4    5   6 7 8 9 10 11 12  13  14 15 16  17 18 19 20   21
        // <root> <p> a b c </p> <p> c d e f  g  h </p> <p> i  j   k  l  m  n  </p>  </root>
        val tree = createIndexTree(
            createElementNode(
                "root",
                createElementNode("p", createTextNode("ab"), createTextNode("c")),
                createElementNode(
                    "p",
                    createTextNode("cde"),
                    createTextNode("fgh"),
                ),
                createElementNode(
                    "p",
                    createTextNode("ij"),
                    createTextNode("k"),
                    createTextNode("l"),
                    createTextNode("mn"),
                ),
            ),
        )
        for (i in 0 until tree.root.visibleSize) {
            val pos = tree.findTreePos(i, true)
            assertEquals(i, tree.indexOf(pos))
        }
    }

    @Test
    fun `should find treePos from given path`() {
        //       0   1 2 3    4   5 6 7 8    9   10 11 12   13
        // <root> <p> a b </p> <p> c d e </p> <p>  f  g  </p>  </root>
        val tree = createIndexTree(
            createElementNode(
                "root",
                createElementNode("p", createTextNode("a"), createTextNode("b")),
                createElementNode("p", createTextNode("cde")),
                createElementNode("p", createTextNode("fg")),
            ),
        )

        var pos = tree.pathToTreePos(listOf(0))
        assertEquals("root" to 0, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(0, 0))
        assertEquals("a" to 0, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(0, 1))
        assertEquals("a" to 1, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(0, 2))
        assertEquals("b" to 1, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(1))
        assertEquals("root" to 1, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(1, 0))
        assertEquals("cde" to 0, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(1, 1))
        assertEquals("cde" to 1, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(1, 2))
        assertEquals("cde" to 2, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(1, 3))
        assertEquals("cde" to 3, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(2))
        assertEquals("root" to 2, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(2, 0))
        assertEquals("fg" to 0, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(2, 1))
        assertEquals("fg" to 1, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(2, 2))
        assertEquals("fg" to 2, pos.node.toDiagnostic() to pos.offset)
    }

    @Test
    fun `should throw IllegalArgumentException for unacceptable paths`() {
        val tree = createIndexTree(DefaultRootNode)

        assertThrows(IllegalArgumentException::class.java) {
            tree.pathToTreePos(emptyList())
        }

        assertThrows(IllegalArgumentException::class.java) {
            tree.pathToTreePos(listOf(tree.size + 1))
        }
    }

    @Test
    fun `can find path from given treePos`() {
        //       0  1  2    3 4 5 6 7     8   9 10 11 12 13  14 15  16
        // <root><tc><p><tn> A B C D </tn><tn> E  F G  H </tn></p></tc></root>
        val tree = createIndexTree(
            createElementNode(
                "root",
                createElementNode(
                    "tc",
                    createElementNode(
                        "p",
                        createElementNode(
                            "tn",
                            createTextNode("AB"),
                            createTextNode("CD"),
                        ),
                        createElementNode(
                            "tn",
                            createTextNode("EF"),
                            createTextNode("GH"),
                        ),
                    ),
                ),
            ),
        )

        var pos = tree.findTreePos(0)
        assertEquals(listOf(0), tree.treePosToPath(pos))

        pos = tree.findTreePos(1)
        assertEquals(listOf(0, 0), tree.treePosToPath(pos))

        pos = tree.findTreePos(2)
        assertEquals(listOf(0, 0, 0), tree.treePosToPath(pos))

        pos = tree.findTreePos(3)
        assertEquals(listOf(0, 0, 0, 0), tree.treePosToPath(pos))

        pos = tree.findTreePos(4)
        assertEquals(listOf(0, 0, 0, 1), tree.treePosToPath(pos))

        pos = tree.findTreePos(5)
        assertEquals(listOf(0, 0, 0, 2), tree.treePosToPath(pos))

        pos = tree.findTreePos(6)
        assertEquals(listOf(0, 0, 0, 3), tree.treePosToPath(pos))

        pos = tree.findTreePos(7)
        assertEquals(listOf(0, 0, 0, 4), tree.treePosToPath(pos))

        pos = tree.findTreePos(8)
        assertEquals(listOf(0, 0, 1), tree.treePosToPath(pos))

        pos = tree.findTreePos(9)
        assertEquals(listOf(0, 0, 1, 0), tree.treePosToPath(pos))

        pos = tree.findTreePos(10)
        assertEquals(listOf(0, 0, 1, 1), tree.treePosToPath(pos))

        pos = tree.findTreePos(11)
        assertEquals(listOf(0, 0, 1, 2), tree.treePosToPath(pos))

        pos = tree.findTreePos(12)
        assertEquals(listOf(0, 0, 1, 3), tree.treePosToPath(pos))

        pos = tree.findTreePos(13)
        assertEquals(listOf(0, 0, 1, 4), tree.treePosToPath(pos))

        pos = tree.findTreePos(14)
        assertEquals(listOf(0, 0, 2), tree.treePosToPath(pos))

        pos = tree.findTreePos(15)
        assertEquals(listOf(0, 1), tree.treePosToPath(pos))

        pos = tree.findTreePos(16)
        assertEquals(listOf(1), tree.treePosToPath(pos))
    }

    @Test
    fun `should find index from given path and vice versa`() {
        val tree = createIndexTree(
            createElementNode(
                "root",
                createElementNode(
                    "tc",
                    createElementNode(
                        "p",
                        createElementNode("tn", createTextNode("AB")),
                        createElementNode("tn", createTextNode("CD")),
                    ),
                ),
            ),
        )

        //      <root>
        //        |
        //       <tc>
        //        |
        //       <p>
        //      /   \
        //   <tn>   <tn>
        //    |      |
        //    AB     CD
        //
        //       0    1   2    3 4 5     6    7 8 9     10   11     12
        // <root> <tc> <p> <tn> A B </tn> <tn> C D </tn>  </p>  </tc>  </root>
        var pos = tree.pathToIndex(listOf(0))
        assertEquals(0, pos)
        assertEquals(listOf(0, 0), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 0))
        assertEquals(1, pos)
        assertEquals(listOf(0, 0, 0), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 0, 0))
        assertEquals(2, pos)
        assertEquals(listOf(0, 0, 0, 0), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 0, 0, 0))
        assertEquals(3, pos)
        assertEquals(listOf(0, 0, 0, 1), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 0, 0, 1))
        assertEquals(4, pos)
        assertEquals(listOf(0, 0, 0, 2), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 0, 0, 2))
        assertEquals(5, pos)
        assertEquals(listOf(0, 0, 1), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 0, 1))
        assertEquals(6, pos)
        assertEquals(listOf(0, 0, 1, 0), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 0, 1, 0))
        assertEquals(7, pos)
        assertEquals(listOf(0, 0, 1, 1), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 0, 1, 1))
        assertEquals(8, pos)
        assertEquals(listOf(0, 0, 1, 2), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 0, 1, 2))
        assertEquals(9, pos)
        assertEquals(listOf(0, 0, 2), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 0, 2))
        assertEquals(10, pos)
        assertEquals(listOf(0, 1), tree.indexToPath(pos + 1))

        pos = tree.pathToIndex(listOf(0, 1))
        assertEquals(11, pos)
        assertEquals(listOf(1), tree.indexToPath(pos + 1))
    }

    /**
     * Ports the tombstone-aware size bookkeeping [moveChild] already has
     * onto [moveChildBefore] (port `e41069df`, yorkie-js-sdk#1360,
     * `tree.ts:730`): moving a LIVE child still charges both parents'
     * visible size; moving a REMOVED child only moves `totalSize` (its own
     * padding never counted toward either parent's visible size), and it
     * lands immediately before the named reference instead of at the end.
     */
    @Test
    fun `moveChildBefore inserts before the reference with tombstone-aware sizing`() {
        val source = createElementNode("src")
        val reference = createElementNode("ref")
        val target = createElementNode("target", reference)
        val liveChild = createElementNode("live")
        val removedChild = createElementNode("removed")
        source.append(liveChild)
        source.append(removedChild)
        removedChild.remove(InitialTimeTicket)

        val targetVisibleBefore = target.visibleSize
        val targetTotalBefore = target.totalSize
        val sourceVisibleBefore = source.visibleSize
        val sourceTotalBefore = source.totalSize

        target.moveChildBefore(liveChild, reference)
        assertEquals(listOf("live", "ref"), target.children.map { it.toDiagnostic() })
        assertEquals(targetVisibleBefore + liveChild.paddedSize(), target.visibleSize)
        assertEquals(targetTotalBefore + liveChild.paddedSize(true), target.totalSize)
        // The source parent must give back exactly what the target gained --
        // the live child's departure shrinks both of ITS size dimensions.
        assertEquals(sourceVisibleBefore - liveChild.paddedSize(), source.visibleSize)
        assertEquals(sourceTotalBefore - liveChild.paddedSize(true), source.totalSize)

        val targetVisibleAfterLive = target.visibleSize
        val targetTotalAfterLive = target.totalSize
        val sourceVisibleAfterLive = source.visibleSize
        val sourceTotalAfterLive = source.totalSize

        target.moveChildBefore(removedChild, reference)
        // Landed immediately before "ref" in full (tombstone-including) order,
        // but stays absent from the live-only children list.
        assertEquals(
            listOf("live", "removed", "ref"),
            target.allChildren.map { it.toDiagnostic() },
        )
        assertEquals(listOf("live", "ref"), target.children.map { it.toDiagnostic() })
        // No live-size change for the removed child's own move:
        // visibleSize is unaffected, only totalSize grows by its padding.
        assertEquals(targetVisibleAfterLive, target.visibleSize)
        assertEquals(targetTotalAfterLive + removedChild.paddedSize(true), target.totalSize)
        // Mirror on the source side: a removed child never counted toward
        // source's visibleSize, so only totalSize shrinks when it leaves.
        assertEquals(sourceVisibleAfterLive, source.visibleSize)
        assertEquals(sourceTotalAfterLive - removedChild.paddedSize(true), source.totalSize)
    }

    // The old buggy order detached `child` from its old parent BEFORE
    // validating that `reference` actually belongs to the target node --
    // a missing reference left `child` detached-but-unattached, corrupting
    // BOTH trees (lost from source, never inserted into target). Validate
    // that neither tree moves when the call throws.
    @Test
    fun `moveChildBefore throws and leaves both trees untouched when reference is not a child`() {
        val source = createElementNode("src")
        val child = createElementNode("live")
        source.append(child)

        val target = createElementNode("target")
        val strayReference = createElementNode("stray") // never added to target

        val sourceChildrenBefore = source.children.map { it.toDiagnostic() }
        val targetChildrenBefore = target.children.map { it.toDiagnostic() }
        val sourceVisibleBefore = source.visibleSize
        val sourceTotalBefore = source.totalSize
        val targetVisibleBefore = target.visibleSize
        val targetTotalBefore = target.totalSize

        assertThrows(NoSuchElementException::class.java) {
            target.moveChildBefore(child, strayReference)
        }

        assertEquals(sourceChildrenBefore, source.children.map { it.toDiagnostic() })
        assertEquals(targetChildrenBefore, target.children.map { it.toDiagnostic() })
        assertEquals(sourceVisibleBefore, source.visibleSize)
        assertEquals(sourceTotalBefore, source.totalSize)
        assertEquals(targetVisibleBefore, target.visibleSize)
        assertEquals(targetTotalBefore, target.totalSize)
    }

    // JS parity (index_tree.ts v0.7.23 moveChildBefore): moving a node
    // before itself is a no-op, not an error -- even though `reference`
    // trivially "is" one of `child`'s own siblings (itself).
    @Test
    fun `moveChildBefore is a no-op when child and reference are the same node`() {
        val target = createElementNode("target")
        val child = createElementNode("child")
        target.append(child)

        val childrenBefore = target.children.map { it.toDiagnostic() }
        val visibleBefore = target.visibleSize
        val totalBefore = target.totalSize

        target.moveChildBefore(child, child)

        assertEquals(childrenBefore, target.children.map { it.toDiagnostic() })
        assertEquals(visibleBefore, target.visibleSize)
        assertEquals(totalBefore, target.totalSize)
    }

    // `child.parent` is allowed to be null (e.g. a freshly constructed node
    // never yet attached anywhere) -- the detach branch must simply be
    // skipped, not throw.
    @Test
    fun `moveChildBefore works for a parentless child`() {
        val reference = createElementNode("ref")
        val target = createElementNode("target", reference)
        val orphan = createElementNode("orphan")

        val visibleBefore = target.visibleSize
        val totalBefore = target.totalSize

        target.moveChildBefore(orphan, reference)

        assertEquals(listOf("orphan", "ref"), target.children.map { it.toDiagnostic() })
        assertEquals(visibleBefore + orphan.paddedSize(), target.visibleSize)
        assertEquals(totalBefore + orphan.paddedSize(true), target.totalSize)
    }

    private fun CrdtTreeNode.toDiagnostic() = if (isText) value else type

    private fun createIndexTree(root: CrdtTreeNode): IndexTree<CrdtTreeNode> {
        root.children.toList().forEach { child ->
            buildDescendants(child, root)
        }
        return IndexTree(root)
    }

    private fun buildDescendants(node: CrdtTreeNode, parent: CrdtTreeNode) {
        if (node in parent.children) {
            parent.removeChild(node)
        }
        parent.append(node)
        node.children.toList().forEach { child ->
            buildDescendants(child, node)
        }
    }

    private fun IndexTree<CrdtTreeNode>.tokensBetween(from: Int, to: Int) = buildList {
        tokensBetween(
            from = from,
            to = to,
            action = { (node, tokenType), _ ->
                add("${node.toDiagnostic()}:$tokenType")
            },
        )
    }

    companion object {
        private fun createElementNode(type: String, vararg childNode: CrdtTreeNode): CrdtTreeNode {
            return CrdtTreeElement(InitialCrdtTreeNodeID, type, childNode.toList())
        }

        private fun createTextNode(value: String) = CrdtTreeText(InitialCrdtTreeNodeID, value)

        private val DefaultRootNode = createElementNode(
            "root",
            createElementNode("p", createTextNode("ab")),
            createElementNode("p", createTextNode("cd")),
        )
    }
}
