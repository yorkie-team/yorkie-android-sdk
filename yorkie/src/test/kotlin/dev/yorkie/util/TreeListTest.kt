package dev.yorkie.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeListTest {

    private class TestValue(val content: String, override var isRemoved: Boolean = false) :
        TreeListValue {
        override fun toString() = content
    }

    private fun newNode(content: String) = TreeListNode(TestValue(content))

    private fun newRemovedNode(content: String) = TreeListNode(TestValue(content, true))

    private fun rebuildLiveList(tree: TreeList<TestValue>): List<TreeListNode<TestValue>> {
        return (0 until tree.length).map(tree::find)
    }

    /**
     * Simple seeded LCG so the stress test is deterministic across runs/platforms.
     */
    private class Rng(seed: Int) {
        private var state = seed.toLong() and 0xFFFFFFFFL

        fun next(): Double {
            state = (state * 1664525 + 1013904223) and 0xFFFFFFFFL
            return state.toDouble() / 0x100000000L
        }
    }

    @Test
    fun `should insert and find`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        assertEquals(0, tree.length)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        assertEquals(1, tree.length)

        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)
        assertEquals(2, tree.length)

        val nodeC = newNode("C")
        tree.insertAfter(nodeB, nodeC)
        assertEquals(3, tree.length)

        assertSame(nodeA, tree.find(0))
        assertSame(nodeB, tree.find(1))
        assertSame(nodeC, tree.find(2))
    }

    @Test
    fun `should insert in the middle`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        val nodeC = newNode("C")
        tree.insertAfter(nodeA, nodeC)

        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)
        assertEquals(3, tree.length)

        assertSame(nodeA, tree.find(0))
        assertSame(nodeB, tree.find(1))
        assertSame(nodeC, tree.find(2))
    }

    @Test
    fun `should insert after tombstone`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)

        nodeA.value.isRemoved = true
        tree.updateWeight(nodeA)
        assertEquals(1, tree.length)

        val nodeC = newNode("C")
        tree.insertAfter(nodeA, nodeC)
        assertEquals(2, tree.length)

        assertSame(nodeC, tree.find(0))
        assertSame(nodeB, tree.find(1))
    }

    @Test
    fun `should delete node`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)
        val nodeC = newNode("C")
        tree.insertAfter(nodeB, nodeC)
        assertEquals(3, tree.length)

        tree.delete(nodeB)
        assertEquals(2, tree.length)

        assertSame(nodeA, tree.find(0))
        assertSame(nodeC, tree.find(1))
    }

    @Test
    fun `should preserve node identity on delete`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodes = mutableListOf<TreeListNode<TestValue>>()
        var prev = dummyHead
        repeat(5) { index ->
            val node = newNode(('A' + index).toString())
            tree.insertAfter(prev, node)
            nodes.add(node)
            prev = node
        }
        assertEquals(5, tree.length)

        tree.delete(nodes[2])
        assertEquals(4, tree.length)

        assertSame(nodes[0], tree.find(0))
        assertSame(nodes[1], tree.find(1))
        assertSame(nodes[3], tree.find(2))
        assertSame(nodes[4], tree.find(3))
    }

    @Test
    fun `should delete first and last nodes`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)
        val nodeC = newNode("C")
        tree.insertAfter(nodeB, nodeC)

        tree.delete(nodeA)
        assertEquals(2, tree.length)
        assertSame(nodeB, tree.find(0))

        tree.delete(nodeC)
        assertEquals(1, tree.length)
        assertSame(nodeB, tree.find(0))
    }

    @Test
    fun `should delete tombstoned node`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)
        val nodeC = newNode("C")
        tree.insertAfter(nodeB, nodeC)

        nodeB.value.isRemoved = true
        tree.updateWeight(nodeB)
        assertEquals(2, tree.length)

        tree.delete(nodeB)
        assertEquals(2, tree.length)

        assertSame(nodeA, tree.find(0))
        assertSame(nodeC, tree.find(1))
    }

    @Test
    fun `should delete all nodes`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)

        tree.delete(nodeA)
        tree.delete(nodeB)
        tree.delete(dummyHead)

        assertEquals(0, tree.length)
    }

    @Test
    fun `should update weight on tombstone`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)
        val nodeC = newNode("C")
        tree.insertAfter(nodeB, nodeC)
        assertEquals(3, tree.length)

        nodeB.value.isRemoved = true
        tree.updateWeight(nodeB)
        assertEquals(2, tree.length)

        assertSame(nodeA, tree.find(0))
        assertSame(nodeC, tree.find(1))
        assertThrows(YorkieException::class.java) { tree.find(2) }
    }

    @Test
    fun `should handle multiple tombstones`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodes = mutableListOf<TreeListNode<TestValue>>()
        var prev = dummyHead
        repeat(6) { index ->
            val node = newNode(('A' + index).toString())
            tree.insertAfter(prev, node)
            nodes.add(node)
            prev = node
        }
        assertEquals(6, tree.length)

        listOf(1, 3, 5).forEach { index ->
            nodes[index].value.isRemoved = true
            tree.updateWeight(nodes[index])
        }
        assertEquals(3, tree.length)

        assertSame(nodes[0], tree.find(0))
        assertSame(nodes[2], tree.find(1))
        assertSame(nodes[4], tree.find(2))
    }

    @Test
    fun `should throw when find is out of bounds`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        assertThrows(YorkieException::class.java) { tree.find(0) }

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)

        assertThrows(YorkieException::class.java) { tree.find(-1) }
        assertThrows(YorkieException::class.java) { tree.find(1) }
    }

    @Test
    fun `should handle single live node tree`() {
        val node = newNode("A")
        val tree = TreeList(node)
        assertEquals(1, tree.length)

        assertSame(node, tree.find(0))

        tree.delete(node)
        assertEquals(0, tree.length)
    }

    @Test
    fun `should find after large sequential insert`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val n = 100
        val nodes = mutableListOf<TreeListNode<TestValue>>()
        var prev = dummyHead
        repeat(n) { index ->
            val node = newNode("$index")
            tree.insertAfter(prev, node)
            nodes.add(node)
            prev = node
        }
        assertEquals(n, tree.length)

        repeat(n) { index ->
            assertSame(nodes[index], tree.find(index))
        }
    }

    @Test
    fun `should find after large sequential insert and tombstone`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val n = 100
        val nodes = mutableListOf<TreeListNode<TestValue>>()
        var prev = dummyHead
        repeat(n) { index ->
            val node = newNode("$index")
            tree.insertAfter(prev, node)
            nodes.add(node)
            prev = node
        }

        for (index in 0 until n step 2) {
            nodes[index].value.isRemoved = true
            tree.updateWeight(nodes[index])
        }
        assertEquals(n / 2, tree.length)

        var liveIndex = 0
        for (index in 1 until n step 2) {
            assertSame(nodes[index], tree.find(liveIndex))
            liveIndex++
        }
    }

    @Test
    fun `should find after large sequential insert and delete`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val n = 100
        val nodes = mutableListOf<TreeListNode<TestValue>>()
        var prev = dummyHead
        repeat(n) { index ->
            val node = newNode("$index")
            tree.insertAfter(prev, node)
            nodes.add(node)
            prev = node
        }

        for (index in 0 until n step 2) {
            tree.delete(nodes[index])
        }
        assertEquals(n / 2, tree.length)

        var liveIndex = 0
        for (index in 1 until n step 2) {
            assertSame(nodes[index], tree.find(liveIndex))
            liveIndex++
        }
    }

    @Test
    fun `should handle interleaved insert and delete`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)
        val nodeC = newNode("C")
        tree.insertAfter(nodeB, nodeC)

        tree.delete(nodeB)
        val nodeD = newNode("D")
        tree.insertAfter(nodeA, nodeD)
        assertEquals(3, tree.length)

        assertSame(nodeA, tree.find(0))
        assertSame(nodeD, tree.find(1))
        assertSame(nodeC, tree.find(2))
    }

    @Test
    fun `should insert after dummy head with existing nodes`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeB = newNode("B")
        tree.insertAfter(dummyHead, nodeB)
        val nodeC = newNode("C")
        tree.insertAfter(nodeB, nodeC)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        assertEquals(3, tree.length)

        assertSame(nodeA, tree.find(0))
        assertSame(nodeB, tree.find(1))
        assertSame(nodeC, tree.find(2))
    }

    @Test
    fun `should render metadata in toTestString`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)

        val str = tree.toTestString()
        assertTrue(str.contains("dummy"))
        assertTrue(str.contains("A"))
        assertTrue(str.contains("B"))
    }

    @Test
    fun `should return logical index from indexOf and -1 for tombstone`() {
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        val nodeA = newNode("A")
        tree.insertAfter(dummyHead, nodeA)
        val nodeB = newNode("B")
        tree.insertAfter(nodeA, nodeB)
        val nodeC = newNode("C")
        tree.insertAfter(nodeB, nodeC)

        assertEquals(0, tree.indexOf(nodeA))
        assertEquals(1, tree.indexOf(nodeB))
        assertEquals(2, tree.indexOf(nodeC))
        assertEquals(-1, tree.indexOf(dummyHead))

        nodeB.value.isRemoved = true
        tree.updateWeight(nodeB)

        assertEquals(-1, tree.indexOf(nodeB))
        assertEquals(1, tree.indexOf(nodeC))
    }

    @Test
    fun `should stay consistent under random operations`() {
        val rng = Rng(42)
        val dummyHead = newRemovedNode("dummy")
        val tree = TreeList(dummyHead)

        var liveNodes = listOf<TreeListNode<TestValue>>()
        val allNodes = mutableListOf(dummyHead)

        val ops = 500
        repeat(ops) { iteration ->
            val op = (rng.next() * 3).toInt()

            if (op == 0 || allNodes.size < 3) {
                val prev = allNodes[(rng.next() * allNodes.size).toInt()]
                val node = newNode("n$iteration")
                tree.insertAfter(prev, node)
                allNodes.add(node)
                liveNodes = rebuildLiveList(tree)
            } else if (op == 1 && liveNodes.isNotEmpty()) {
                val node = liveNodes[(rng.next() * liveNodes.size).toInt()]
                node.value.isRemoved = true
                tree.updateWeight(node)
                liveNodes = rebuildLiveList(tree)
            } else if (op == 2 && allNodes.size > 1) {
                val deleteIndex = 1 + (rng.next() * (allNodes.size - 1)).toInt()
                tree.delete(allNodes[deleteIndex])
                allNodes.removeAt(deleteIndex)
                liveNodes = rebuildLiveList(tree)
            }

            assertEquals("iteration $iteration", liveNodes.size, tree.length)
            liveNodes.forEachIndexed { index, node ->
                assertSame("iteration $iteration, find index $index", node, tree.find(index))
            }
        }
    }
}
