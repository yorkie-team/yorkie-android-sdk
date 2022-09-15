package dev.yorkie.util

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SplayTreeTest {
    private lateinit var target: SplayTree<String>

    @Before
    fun setUp() {
        target = SplayTree { it.length }
    }

    @Test
    fun `should conform to specs when insertions and splaying occur`() {
        assertEquals(0, target.length)

        val nodeA = target.insert("A2")
        assertEquals("[2,2]A2", target.getStructureAsString())
        val nodeB = target.insert("B23")
        assertEquals("[2,2]A2[5,3]B23", target.getStructureAsString())
        val nodeC = target.insert("C234")
        assertEquals("[2,2]A2[5,3]B23[9,4]C234", target.getStructureAsString())
        val nodeD = target.insert("D2345")
        assertEquals("[2,2]A2[5,3]B23[9,4]C234[14,5]D2345", target.getStructureAsString())

        target.splayNode(nodeB)
        assertEquals("[2,2]A2[14,3]B23[9,4]C234[5,5]D2345", target.getStructureAsString())

        assertEquals(0, target.indexOf(nodeA))
        assertEquals(2, target.indexOf(nodeB))
        assertEquals(5, target.indexOf(nodeC))
        assertEquals(9, target.indexOf(nodeD))

        assertEquals(SplayTree.ValueToOffset.Empty, target.find(-1))
        assertEquals(14, target.length)
    }

    private fun SplayTree<*>.getStructureAsString(): String {
        val nodes = buildList { traverseInorder(root, this) }
        return nodes.joinToString("") {
            "[${it.weight},${it.length}]${it.value}"
        }
    }

    private fun traverseInorder(node: SplayTree.Node<*>?, nodes: MutableList<SplayTree.Node<*>>) {
        if (node == null) {
            return
        }
        traverseInorder(node.left, nodes)
        nodes.add(node)
        traverseInorder(node.right, nodes)
    }
}
