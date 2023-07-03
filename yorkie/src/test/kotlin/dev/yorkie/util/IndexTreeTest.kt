package dev.yorkie.util

import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreePos.Companion.InitialCrdtTreePos
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexTreeTest {

    @Test
    fun `should find position from the offsets`() {
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
    fun `should find right node from the given offset in postorder traversal`() {
        //       0   1 2 3    4   5 6 7    8
        // <root> <p> a b </p> <p> c d </p> </root>
        val tree = createIndexTree(
            createElementNode(
                "root",
                createElementNode("p", createTextNode("ab")),
                createElementNode("p", createTextNode("cd")),
            ),
        )
        assertEquals("text", tree.findPostorderRight(tree.findTreePos(0))?.type)
        assertEquals("text", tree.findPostorderRight(tree.findTreePos(1))?.type)
        assertEquals("p", tree.findPostorderRight(tree.findTreePos(3))?.type)
        assertEquals("text", tree.findPostorderRight(tree.findTreePos(4))?.type)
        assertEquals("text", tree.findPostorderRight(tree.findTreePos(5))?.type)
        assertEquals("p", tree.findPostorderRight(tree.findTreePos(7))?.type)
        assertEquals("root", tree.findPostorderRight(tree.findTreePos(8))?.type)
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
        println(tree.root.size)
        assertEquals(
            listOf("text.b", "p", "text.cde", "p", "text.fg", "p"),
            tree.nodesBetween(2, 11),
        )
        assertEquals(listOf("p"), tree.nodesBetween(0, 1))
        assertEquals(listOf("p"), tree.nodesBetween(3, 4))
        assertEquals(listOf("p", "p"), tree.nodesBetween(3, 5))
    }

    @Test
    fun `should convert index to pos`() {
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
    fun `should find index from given path`() {
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

    private fun createElementNode(type: String, vararg childNode: CrdtTreeNode): CrdtTreeNode {
        return CrdtTreeNode(InitialCrdtTreePos, type, _children = childNode.toMutableList())
    }

    private fun createTextNode(value: String) =
        CrdtTreeNode(InitialCrdtTreePos, "text", _value = value)

    private fun IndexTree<CrdtTreeNode>.nodesBetween(from: Int, to: Int) = buildList {
        nodesBetween(from, to) { node ->
            add(node.toDiagnostic())
        }
    }
}
