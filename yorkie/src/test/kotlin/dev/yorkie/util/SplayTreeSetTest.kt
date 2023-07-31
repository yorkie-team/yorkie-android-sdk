package dev.yorkie.util

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SplayTreeSetTest {
    private lateinit var target: SplayTreeSet<Node>

    @Before
    fun setUp() {
        target = SplayTreeSet {
            if (it.isRemoved) 0 else it.value.length
        }
    }

    @Test
    fun `should conform to specs when insertions and splaying occur`() {
        assertEquals(0, target.length)

        target.insert(Node("A2"))
        assertEquals("[2,2]A2", target.toTestString())
        target.insert(Node("B23"))
        assertEquals("[2,2]A2[5,3]B23", target.toTestString())
        target.insert(Node("C234"))
        assertEquals("[2,2]A2[5,3]B23[9,4]C234", target.toTestString())
        target.insert(Node("D2345"))
        assertEquals("[2,2]A2[5,3]B23[9,4]C234[14,5]D2345", target.toTestString())

        target.splay(Node("B23"))
        assertEquals("[2,2]A2[14,3]B23[9,4]C234[5,5]D2345", target.toTestString())

        assertEquals(0, target.indexOf(Node("A2")))
        assertEquals(2, target.indexOf(Node("B23")))
        assertEquals(5, target.indexOf(Node("C234")))
        assertEquals(9, target.indexOf(Node("D2345")))

        assertEquals(SplayTreeSet.ValueToOffset.Empty, target.find(-1))
        assertEquals(14, target.length)
    }

    @Test
    fun `should conform to specs when deletions occur`() {
        target.insert(Node("H"))
        assertEquals("[1,1]H", target.toTestString())
        target.insert(Node("E"))
        assertEquals("[1,1]H[2,1]E", target.toTestString())
        target.insert(Node("LL"))
        assertEquals("[1,1]H[2,1]E[4,2]LL", target.toTestString())
        target.insert(Node("O"))
        assertEquals("[1,1]H[2,1]E[4,2]LL[5,1]O", target.toTestString())

        target.valueToNodes[Node("E")]?.value?.isRemoved = true
        target.delete(Node("E"))
        assertEquals("[4,1]H[3,2]LL[1,1]O", target.toTestString())

        assertEquals(target.indexOf(Node("H")), 0)
        assertEquals(target.indexOf(Node("EE")), -1)
        assertEquals(target.indexOf(Node("LL")), 1)
        assertEquals(target.indexOf(Node("O")), 3)
    }

    @Test
    fun `should delete nodes in range when deleteRange is invoked with no right boundary`() {
        val nodes = buildSampleTree()
        nodes.removeRange(7..nodes.lastIndex)
        target.cutOffRange(nodes[6])
        assertEquals(nodes[6], target.root?.value)
        assertEquals(22, target.root?.weight)
        assertIfRangeCutOff(nodes, 7..8)
    }

    // FIXME(skhugh): need clarification for test name.
    @Test
    fun `should delete nodes with case 1`() {
        val nodes = buildSampleTree()
        nodes.removeRange(3..6)
        target.cutOffRange(nodes[2], nodes[7])
        assertEquals(nodes[7], target.root?.value)
        assertEquals(nodes[2], target.root?.left?.value)
        assertEquals(9, target.root?.weight)
        assertEquals(6, target.root?.left?.weight)
        assertIfRangeCutOff(nodes, 3..6)
    }

    // FIXME(skhugh): need clarification for test name.
    @Test
    fun `should delete nodes with case 2`() {
        val nodes = buildSampleTree()
        target.splay(nodes[6])
        target.splay(nodes[2])
        // check the case 2 of rangeDelete
        nodes.removeRange(3..7)
        target.cutOffRange(nodes[2], nodes[8])
        assertEquals(nodes[8], target.root?.value)
        assertEquals(nodes[2], target.root?.left?.value)
        assertEquals(7, target.root?.weight)
        assertEquals(6, target.root?.left?.weight)
        assertIfRangeCutOff(nodes, 3..7)
    }

    @Test
    fun `should handle indexOf correctly with single node`() {
        val node = Node("A")
        target.insert(node)
        assertEquals(0, target.indexOf(node))
        target.delete(node)
        assertEquals(-1, target.indexOf(node))
    }

    private fun assertIfRangeCutOff(nodes: List<Node>, targetRange: IntRange) {
        assertEquals(
            0,
            targetRange.sumOf {
                target.valueToNodes[nodes[it]]?.weight ?: 0
            },
        )
    }

    private fun <V> SplayTreeSet<V>.toTestString(): String {
        val nodes = traverseInorder(root)
        return nodes.joinToString("") {
            "[${it.weight},${it.length}]${it.value}"
        }
    }

    private fun buildSampleTree(): List<Node> {
        val nodes = listOf("A", "BB", "CCC", "DDDD", "EEEEE", "FFFF", "GGG", "HH", "I").map(::Node)
        nodes.forEach(target::insert)
        return nodes
    }

    private fun List<Node>.removeRange(range: IntRange) {
        subList(range.first, range.last + 1).forEach {
            target.valueToNodes[it]?.value?.isRemoved = true
        }
    }

    data class Node(val value: String) {
        var isRemoved = false

        override fun toString(): String = value
    }
}
