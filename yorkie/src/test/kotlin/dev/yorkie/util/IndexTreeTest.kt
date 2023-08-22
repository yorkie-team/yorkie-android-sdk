package dev.yorkie.util

import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID.Companion.InitialCrdtTreeNodeID
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
        assertEquals("text.hello" to 0, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(6)
        assertEquals("text.hello" to 5, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(6, false)
        assertEquals("p" to 1, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(7)
        assertEquals("r" to 1, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(8)
        assertEquals("text.world" to 0, pos.node.toDiagnostic() to pos.offset)
        pos = tree.findTreePos(13)
        assertEquals("text.world" to 5, pos.node.toDiagnostic() to pos.offset)
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

        assertEquals("text.ab", nodeAB.toDiagnostic())
        assertEquals("text.cd", nodeCD.toDiagnostic())
        assertEquals("p", findCommonAncestor(nodeAB, nodeCD)?.type)
    }

    @Test
    fun `should traverse nodes between two given positions`() {
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
            listOf("text.b", "p", "text.cde", "p", "text.fg", "p"),
            tree.nodesBetween(2, 11),
        )
        assertEquals(listOf("p"), tree.nodesBetween(0, 1))
        assertEquals(listOf("p"), tree.nodesBetween(3, 4))
        assertEquals(listOf("p", "p"), tree.nodesBetween(3, 5))
    }

    @Test
    fun `should throw IllegalArgumentException when traversing nodes within invalid ranges`() {
        val tree = createIndexTree(DefaultRootNode)

        assertThrows(IllegalArgumentException::class.java) {
            tree.nodesBetween(tree.size, 0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            tree.nodesBetween(tree.size + 1, tree.size + 2)
        }

        assertThrows(IllegalArgumentException::class.java) {
            tree.nodesBetween(tree.size, tree.size + 1)
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
        for (i in 0 until tree.root.size) {
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
        assertEquals("text.a" to 0, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(0, 1))
        assertEquals("text.a" to 1, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(0, 2))
        assertEquals("text.b" to 1, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(1))
        assertEquals("root" to 1, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(1, 0))
        assertEquals("text.cde" to 0, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(1, 1))
        assertEquals("text.cde" to 1, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(1, 2))
        assertEquals("text.cde" to 2, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(1, 3))
        assertEquals("text.cde" to 3, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(2))
        assertEquals("root" to 2, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(2, 0))
        assertEquals("text.fg" to 0, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(2, 1))
        assertEquals("text.fg" to 1, pos.node.toDiagnostic() to pos.offset)

        pos = tree.pathToTreePos(listOf(2, 2))
        assertEquals("text.fg" to 2, pos.node.toDiagnostic() to pos.offset)
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

    private fun CrdtTreeNode.toDiagnostic() = if (isText) "$type.$value" else type

    private fun createIndexTree(root: CrdtTreeNode): IndexTree<CrdtTreeNode> {
        root.children.forEach { child ->
            buildDescendants(child, root)
        }
        return IndexTree(root)
    }

    private fun buildDescendants(node: CrdtTreeNode, parent: CrdtTreeNode) {
        if (node in parent.children) {
            parent.removeChild(node)
        }
        parent.append(node)
        node.children.forEach { child ->
            buildDescendants(child, node)
        }
    }

    private fun IndexTree<CrdtTreeNode>.nodesBetween(from: Int, to: Int) = buildList {
        nodesBetween(from, to) { node ->
            add(node.toDiagnostic())
        }
    }

    companion object {
        private fun createElementNode(type: String, vararg childNode: CrdtTreeNode): CrdtTreeNode {
            return CrdtTreeElement(InitialCrdtTreeNodeID, type, childNode.toList())
        }

        private fun createTextNode(value: String) =
            CrdtTreeText(InitialCrdtTreeNodeID, value)

        private val DefaultRootNode = createElementNode(
            "root",
            createElementNode("p", createTextNode("ab")),
            createElementNode("p", createTextNode("cd")),
        )
    }
}
