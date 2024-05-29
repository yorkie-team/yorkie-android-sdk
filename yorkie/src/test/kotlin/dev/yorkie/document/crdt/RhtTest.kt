package dev.yorkie.document.crdt

import dev.yorkie.OpCode.RhtCode
import dev.yorkie.Step
import dev.yorkie.TestCase
import dev.yorkie.TestOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.issueTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

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

    @Test
    fun `should handle remove`() {
        target.set(TEST_KEY, TEST_VALUE, TimeTicket.InitialTimeTicket)
        assertEquals(TEST_VALUE, target[TEST_KEY])
        assertEquals(1, target.size)

        target.remove(TEST_KEY, TimeTicket.MaxTimeTicket)
        assertFalse(target.has(TEST_KEY))
        assertTrue(target.isEmpty())
    }

    @Test
    fun `should handle marshal`() {
        val tests = listOf(
            TestCase(
                "1. empty hash table",
                Step(TestOperation(RhtCode.NoOp, "", ""), "{}").toList(),
            ),
            TestCase(
                "2. only one element",
                Step(
                    TestOperation(RhtCode.Set, "hello\\\\\\\\\\\\t", "world\"\\f\\b"),
                    """{"hello\\\\\\\\\\\\t":"world\"\\f\\b"}""",
                ).toList(),
            ),
            TestCase(
                "3. non-empty hash table",
                Step(
                    TestOperation(RhtCode.Set, "hi", "test\\r"),
                    """{"hello\\\\\\\\\\\\t":"world\"\\f\\b","hi":"test\\r"}""",
                ).toList(),
            ),
        )
        tests.forEach { test ->
            test.steps.forEach { (op, expectedJson, _) ->
                if (op.code == RhtCode.Set) {
                    target.set(op.key, op.value, issueTime())
                }
                assertEquals(expectedJson, target.toJson())
            }
        }
    }

    @Test
    fun `should handle set multiple times`() {
        val tests = listOf(
            TestCase(
                "1. set elements",
                listOf(
                    Step(
                        TestOperation(RhtCode.Set, "key1", "value1"),
                        """{"key1":"value1"}""",
                        1,
                    ),
                    Step(
                        TestOperation(RhtCode.Set, "key2", "value2"),
                        """{"key1":"value1","key2":"value2"}""",
                        2,
                    ),
                ),
            ),
            TestCase(
                "2. change elements",
                listOf(
                    Step(
                        TestOperation(RhtCode.Set, "key1", "value2"),
                        """{"key1":"value2","key2":"value2"}""",
                        2,
                    ),
                    Step(
                        TestOperation(RhtCode.Set, "key2", "value1"),
                        """{"key1":"value2","key2":"value1"}""",
                        2,
                    ),
                ),
            ),
        )
        tests.forEach { test ->
            test.steps.forEach { (op, expectedJson, expectedSize) ->
                if (op.code == RhtCode.Set) {
                    target.set(op.key, op.value, issueTime())
                }
                assertEquals(expectedJson, target.toJson())
                assertEquals(expectedSize, target.size)
            }
        }
    }

    @Test
    fun `should handle remove multiple times`() {
        val tests = listOf(
            TestCase(
                "1. set elements",
                listOf(
                    Step(
                        TestOperation(RhtCode.Set, "key1", "value1"),
                        """{"key1":"value1"}""",
                        1,
                    ),
                    Step(
                        TestOperation(RhtCode.Set, "key2", "value2"),
                        """{"key1":"value1","key2":"value2"}""",
                        2,
                    ),
                ),
            ),
            TestCase(
                "2. remove element",
                Step(
                    TestOperation(RhtCode.Remove, "key1", "value1"),
                    """{"key2":"value2"}""",
                    1,
                ).toList(),
            ),
            TestCase(
                "3. set after remove",
                Step(
                    TestOperation(RhtCode.Set, "key1", "value11"),
                    """{"key1":"value11","key2":"value2"}""",
                    2,
                ).toList(),
            ),
            TestCase(
                "4. remove element",
                listOf(
                    Step(
                        TestOperation(RhtCode.Set, "key2", "value22"),
                        """{"key1":"value11","key2":"value22"}""",
                        2,
                    ),
                    Step(
                        TestOperation(RhtCode.Remove, "key1", "value11"),
                        """{"key2":"value22"}""",
                        1,
                    ),
                ),
            ),
            TestCase(
                "5. remove element again",
                Step(
                    TestOperation(RhtCode.Remove, "key1", "value11"),
                    """{"key2":"value22"}""",
                    1,
                ).toList(),
            ),
            TestCase(
                "6. remove element(cleared)",
                Step(
                    TestOperation(RhtCode.Remove, "key2", "value22"),
                    """{}""",
                    0,
                ).toList(),
            ),
            TestCase(
                "7. remove not exist key",
                Step(
                    TestOperation(RhtCode.Remove, "not-exist-key", ""),
                    """{}""",
                    0,
                ).toList(),
            ),
        )

        tests.forEach { test ->
            test.steps.forEach { (op, expectedJson, expectedSize) ->
                when (op.code) {
                    RhtCode.Set -> target.set(op.key, op.value, issueTime())
                    RhtCode.Remove -> target.remove(op.key, issueTime())
                }
                assertEquals(expectedJson, target.toJson())
                assertEquals(expectedSize, target.size)
            }
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
