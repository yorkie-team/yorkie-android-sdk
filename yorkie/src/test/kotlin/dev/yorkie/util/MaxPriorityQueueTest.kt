package dev.yorkie.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MaxPriorityQueueTest {
    private lateinit var target: MaxPriorityQueue<Int>

    @Before
    fun setUp() {
        target = MaxPriorityQueue()
    }

    @Test
    fun `should conform to max heap specs when push and pop are operated`() {
        listOf(8, 7, 5, 6, 2, 1, 9, 4, 0, 3).forEach(target::add)
        for (idx in 9 downTo 0) {
            assertEquals(idx, target.remove())
        }
    }

    @Test
    fun `should keep max heap ordering when root node is removed`() {
        val rootNode = 9
        (0..rootNode).forEach(target::add)
        target.remove(rootNode)

        val expected = arrayOf(8, 7, 5, 6, 2, 1, 4, 0, 3)
        assertArrayEquals(expected, target.toArray())
    }

    @Test
    fun `should keep max heap ordering when parent node is removed`() {
        val parentNode = 5
        (0..9).forEach(target::add)
        target.remove(parentNode)

        val expected = arrayOf(9, 8, 4, 6, 7, 1, 2, 0, 3)
        assertArrayEquals(expected, target.toArray())
    }

    @Test
    fun `should keep max heap ordering when leaf node is removed`() {
        val leafNode = 3
        (0..9).forEach(target::add)
        target.remove(leafNode)

        val expected = arrayOf(9, 8, 5, 6, 7, 1, 4, 0, 2)
        assertArrayEquals(expected, target.toArray())
    }

    @Test
    fun `should be able to remove when only a single node exists`() {
        val node = 0
        target.add(node)
        target.remove(node)

        assertTrue(target.isEmpty())
    }
}
