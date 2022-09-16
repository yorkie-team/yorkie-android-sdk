package dev.yorkie.util

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SplayTreeSetTest {
    private lateinit var target: SplayTreeSet<String>

    @Before
    fun setUp() {
        target = SplayTreeSet(String::length)
    }

    @Test
    fun `should conform to specs when insertions and splaying occur`() {
        assertEquals(0, target.length)

        target.insert("A2")
        assertEquals("[2,2]A2", target.getStructureAsString())
        target.insert("B23")
        assertEquals("[2,2]A2[5,3]B23", target.getStructureAsString())
        target.insert("C234")
        assertEquals("[2,2]A2[5,3]B23[9,4]C234", target.getStructureAsString())
        target.insert("D2345")
        assertEquals("[2,2]A2[5,3]B23[9,4]C234[14,5]D2345", target.getStructureAsString())

        target.splay("B23")
        assertEquals("[2,2]A2[14,3]B23[9,4]C234[5,5]D2345", target.getStructureAsString())

        assertEquals(0, target.indexOf("A2"))
        assertEquals(2, target.indexOf("B23"))
        assertEquals(5, target.indexOf("C234"))
        assertEquals(9, target.indexOf("D2345"))

        assertEquals(SplayTreeSet.ValueToOffset.Empty, target.find(-1))
        assertEquals(14, target.length)
    }

    @Test
    fun `should conform to specs when deletions occur`() {
        target.insert("H")
        assertEquals("[1,1]H", target.getStructureAsString())
        target.insert("E")
        assertEquals("[1,1]H[2,1]E", target.getStructureAsString())
        target.insert("LL")
        assertEquals("[1,1]H[2,1]E[4,2]LL", target.getStructureAsString())
        target.insert("O")
        assertEquals("[1,1]H[2,1]E[4,2]LL[5,1]O", target.getStructureAsString())

        target.delete("E")
        assertEquals("[4,1]H[3,2]LL[1,1]O", target.getStructureAsString())

        assertEquals(target.indexOf("H"), 0)
        assertEquals(target.indexOf("EE"), -1)
        assertEquals(target.indexOf("LL"), 1)
        assertEquals(target.indexOf("O"), 3)
    }

    @Test
    fun `should delete nodes in range when deleteRange is invoked with no right boundary`() {
        val nodes = buildSampleTree()
        target.deleteRange(nodes[6])
        assertEquals(nodes[6], target.root?.value)
        assertEquals(22, target.root?.weight)
        assertIfRangeRemoved(nodes, 7..8)
    }

    // FIXME: need clarification for test name
    @Test
    fun `should delete nodes with case 1`() {
        val nodes = buildSampleTree()
        target.deleteRange(nodes[2], nodes[7])
        assertEquals(nodes[7], target.root?.value)
        assertEquals(nodes[2], target.root?.left?.value)
        assertEquals(9, target.root?.weight)
        assertEquals(6, target.root?.left?.weight)
        assertIfRangeRemoved(nodes, 3..6)
    }

    // FIXME: need clarification for test name
    @Test
    fun `should delete nodes with case 2`() {
        val nodes = buildSampleTree()
        target.splay(nodes[6])
        target.splay(nodes[2])
        // check the case 2 of rangeDelete
        target.deleteRange(nodes[2], nodes[8])
        assertEquals(nodes[8], target.root?.value)
        assertEquals(nodes[2], target.root?.left?.value)
        assertEquals(7, target.root?.weight)
        assertEquals(6, target.root?.left?.weight)
        assertIfRangeRemoved(nodes, 3..7)
    }

    private fun assertIfRangeRemoved(nodes: List<String>, targetRange: IntRange) {
        targetRange.forEach {
            assertEquals(-1, target.indexOf(nodes[it]))
        }
    }

    private fun <V> SplayTreeSet<V>.getStructureAsString(): String {
        val nodes = traverseInorder(root)
        return nodes.joinToString("") {
            "[${it.weight},${it.length}]${it.value}"
        }
    }

    private fun buildSampleTree(): List<String> {
        val nodes = listOf("A", "BB", "CCC", "DDDD", "EEEEE", "FFFF", "GGG", "HH", "I")
        nodes.forEach(target::insert)
        return nodes
    }
}
