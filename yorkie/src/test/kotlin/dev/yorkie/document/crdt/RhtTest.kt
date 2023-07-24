package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RhtTest {
    private lateinit var target: Rht

    @Before
    fun setUp() {
        target = Rht()
    }

    @Test
    fun `should handle set operations`() {
        assertTrue(target.toTestString().isEmpty())

        target.set(TEST_KEY, TEST_VALUE, TimeTicket.InitialTimeTicket)
        assertEquals("$TEST_KEY:$TEST_VALUE", target.toTestString())
    }

    @Test
    fun `should handle get operations`() {
        target.set(TEST_KEY, TEST_VALUE, TimeTicket.InitialTimeTicket)
        assertEquals(TEST_VALUE, target[TEST_KEY])
        assertNull(target[NON_EXISTING_KEY])
    }

    @Test
    fun `should handle has operations`() {
        target.set(TEST_KEY, TEST_VALUE, TimeTicket.InitialTimeTicket)
        assertTrue(target.has(TEST_KEY))
        assertFalse(target.has(NON_EXISTING_KEY))
    }

    @Test
    fun `verify nodeKeyValueMap is returned correctly`() {
        val testData = mapOf("key0" to "value0", "key1" to "value1", "key2" to "value2")
        testData.entries.forEach {
            target.set(it.key, it.value, TimeTicket.InitialTimeTicket)
        }

        target.nodeKeyValueMap.entries.forEachIndexed { index, obj ->
            assertEquals("key$index", obj.key)
            assertEquals("value$index", obj.value)
        }
    }

    private fun Rht.toTestString(): String {
        return nodeKeyValueMap.entries.joinToString("") { "${it.key}:${it.value}" }
    }

    companion object {
        private const val TEST_KEY = "test key"
        private const val TEST_VALUE = "test value"
        private const val NON_EXISTING_KEY = "non-existing test key"
    }
}
