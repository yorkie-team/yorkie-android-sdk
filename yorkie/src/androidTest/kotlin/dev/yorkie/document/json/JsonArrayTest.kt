package dev.yorkie.document.json

import dev.yorkie.core.Client
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.Document
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class JsonArrayTest {
    @Test
    fun can_handle_concurrent_insertAfter_operations() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            var prev: JsonElement? = null
            document1.updateAsync { root, _ ->
                val k1 = root.setNewArray("k1").apply {
                    put(1)
                    put(2)
                    put(3)
                    put(4)
                }
                prev = k1[1]
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.remove(prev!!.id)
                assertEquals(
                    expected = "{\"k1\":[1,3,4]}",
                    actual = root.toJson(),
                )
            }.await()
            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.put(2, prev!!.id)
                assertEquals(
                    expected = "{\"k1\":[1,2,2,3,4]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
            assertEquals(
                expected = "{\"k1\":[1,2,3,4]}",
                actual = document1.toJson(),
            )

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.put("2.1", k1[1]!!.id)
                assertEquals(
                    expected = "{\"k1\":[1,2,\"2.1\",3,4]}",
                    actual = root.toJson(),
                )
            }.await()
            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.put("2.2", k1[1]!!.id)
                assertEquals(
                    expected = "{\"k1\":[1,2,\"2.2\",3,4]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
        }
    }

    @Test
    fun can_handle_concurrent_moveBefore_operations_with_same_position() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            document1.updateAsync { root, _ ->
                root.setNewArray("k1").apply {
                    put(0)
                    put(1)
                    put(2)
                }
                assertEquals(
                    expected = "{\"k1\":[0,1,2]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val next = k1[0]!!
                val item = k1[2]!!
                k1.moveBefore(next.id, item.id)
                assertEquals(
                    expected = "{\"k1\":[2,0,1]}",
                    actual = root.toJson(),
                )
            }.await()

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val next = k1[0]!!
                val item = k1[2]!!
                k1.moveBefore(next.id, item.id)
                assertEquals(
                    expected = "{\"k1\":[1,2,0]}",
                    actual = root.toJson(),
                )
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val next = k1[0]!!
                val item = k1[1]!!
                k1.moveBefore(next.id, item.id)
                assertEquals(
                    expected = "{\"k1\":[1,0,2]}",
                    actual = root.toJson(),
                )
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val next = k1[0]!!
                val item = k1[1]!!
                k1.moveBefore(next.id, item.id)
                assertEquals(
                    expected = "{\"k1\":[0,1,2]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
        }
    }

    @Test
    fun can_handle_concurrent_moveBefore_operations_from_different_position() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            document1.updateAsync { root, _ ->
                root.setNewArray("k1").apply {
                    put(0)
                    put(1)
                    put(2)
                }
                assertEquals(
                    expected = "{\"k1\":[0,1,2]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val next = k1[0]!!
                val item = k1[1]!!
                k1.moveBefore(next.id, item.id)
                assertEquals(
                    expected = "{\"k1\":[1,0,2]}",
                    actual = root.toJson(),
                )
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val next = k1[1]!!
                val item = k1[2]!!
                k1.moveBefore(next.id, item.id)
                assertEquals(
                    expected = "{\"k1\":[0,2,1]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
        }
    }

    @Test
    fun can_handle_concurrent_moveFront_operations_with_different_index() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            document1.updateAsync { root, _ ->
                root.setNewArray("k1").apply {
                    put(0)
                    put(1)
                    put(2)
                }
                assertEquals(
                    expected = "{\"k1\":[0,1,2]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val item = k1[2]!!
                k1.moveFront(item.id)
                assertEquals(
                    expected = "{\"k1\":[2,0,1]}",
                    actual = root.toJson(),
                )
            }.await()

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val item = k1[2]!!
                k1.moveFront(item.id)
                assertEquals(
                    expected = "{\"k1\":[1,2,0]}",
                    actual = root.toJson(),
                )
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val item = k1[1]!!
                k1.moveFront(item.id)
                assertEquals(
                    expected = "{\"k1\":[1,0,2]}",
                    actual = root.toJson(),
                )
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val item = k1[1]!!
                k1.moveFront(item.id)
                assertEquals(
                    expected = "{\"k1\":[0,1,2]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
        }
    }

    @Test
    fun can_handle_concurrent_moveFront_operations_with_same_index() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            document1.updateAsync { root, _ ->
                root.setNewArray("k1").apply {
                    put(0)
                    put(1)
                    put(2)
                }
                assertEquals(
                    expected = "{\"k1\":[0,1,2]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val item = k1[2]!!
                k1.moveFront(item.id)
                assertEquals(
                    expected = "{\"k1\":[2,0,1]}",
                    actual = root.toJson(),
                )
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val item = k1[2]!!
                k1.moveFront(item.id)
                assertEquals(
                    expected = "{\"k1\":[2,0,1]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
        }
    }

    @Test
    fun can_handle_concurrent_moveAfter_operations() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            document1.updateAsync { root, _ ->
                root.setNewArray("k1").apply {
                    put(0)
                    put(1)
                    put(2)
                }
                assertEquals(
                    expected = "{\"k1\":[0,1,2]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val item = k1[1]!!
                k1.moveLast(item.id)
                assertEquals(
                    expected = "{\"k1\":[0,2,1]}",
                    actual = root.toJson(),
                )
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val item = k1[0]!!
                k1.moveLast(item.id)
                assertEquals(
                    expected = "{\"k1\":[1,2,0]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
        }
    }

    @Test
    fun can_handle_concurrent_insertAfter_and_moveBefore_operations() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            var prev: JsonElement? = null
            document1.updateAsync { root, _ ->
                val k1 = root.setNewArray("k1").apply {
                    put(0)
                }
                prev = k1[0]
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.put(1, prev!!.id)
                assertEquals(
                    expected = "{\"k1\":[0,1]}",
                    actual = root.toJson(),
                )
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.put(2, prev!!.id)
                assertEquals(
                    expected = "{\"k1\":[0,2]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val next = k1[0]!!
                val item = k1[1]!!
                k1.moveBefore(next.id, item.id)
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val next = k1[0]!!
                val item = k1[2]!!
                k1.moveBefore(next.id, item.id)
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
        }
    }

    @Test
    fun can_handle_concurrent_moveAfter() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            document1.updateAsync { root, _ ->
                root.setNewArray("k1").apply {
                    put(0)
                    put(1)
                    put(2)
                }
                assertEquals(
                    expected = "{\"k1\":[0,1,2]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val prev = k1[0]!!
                val item = k1[1]!!
                k1.moveAfter(prev.id, item.id)
                assertEquals(
                    expected = "{\"k1\":[0,1,2]}",
                    actual = root.toJson(),
                )
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val prev = k1[0]!!
                val item = k1[2]!!
                k1.moveAfter(prev.id, item.id)
                assertEquals(
                    expected = "{\"k1\":[0,2,1]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
        }
    }

    @Test
    fun can_handle_concurrent_add_operations() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            document1.updateAsync { root, _ ->
                root.setNewArray("k1").apply {
                    put("1")
                }
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.put("2")
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.put("3")
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
        }
    }

    @Test
    fun can_handle_concurrent_delete_operations() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            var prev: JsonElement? = null
            document1.updateAsync { root, _ ->
                root.setNewArray("k1").apply {
                    put(1)
                    put(2)
                    put(3)
                    put(4)
                }
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                prev = k1[2]
                k1.remove(prev!!.id)
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                prev = k1[2]
                k1.remove(prev!!.id)
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                assertEquals(3, k1.size)
            }.await()
        }
    }

    @Test
    fun can_handle_concurrent_insertBefore_and_delete_operations() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            var prev: JsonElement? = null

            document1.updateAsync { root, _ ->
                val k1 = root.setNewArray("k1").apply {
                    put(1)
                }
                prev = k1[0]
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.remove(prev!!.id)
                assertEquals(
                    expected = "{\"k1\":[]}",
                    actual = root.toJson(),
                )
                assertEquals(0, k1.size)
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                // Insert before by adding element and moving it before the target
                k1.put(2)
                val element2 = k1[k1.size - 1]!!
                k1.moveBefore(prev!!.id, element2.id)
                assertEquals(
                    expected = "{\"k1\":[2,1]}",
                    actual = root.toJson(),
                )
                assertEquals(2, k1.size)
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                assertEquals(
                    expected = "{\"k1\":[2]}",
                    actual = root.toJson(),
                )
                assertEquals(1, k1.size)
            }.await()
        }
    }

    @Test
    fun can_handle_complex_concurrent_insertBefore_and_delete_operations() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            var prev: JsonElement? = null

            document1.updateAsync { root, _ ->
                val k1 = root.setNewArray("k1").apply {
                    put(1)
                    put(2)
                    put(3)
                    put(4)
                }
                prev = k1[1]
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.remove(prev!!.id)
                assertEquals(
                    expected = "{\"k1\":[1,3,4]}",
                    actual = root.toJson(),
                )
                assertEquals(3, k1.size)
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.insertBefore(prev!!.id, 5)
                assertEquals(
                    expected = "{\"k1\":[1,5,2,3,4]}",
                    actual = root.toJson(),
                )
                assertEquals(5, k1.size)
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
            assertEquals(
                expected = "{\"k1\":[1,5,3,4]}",
                actual = document1.toJson(),
            )

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val targetElement = k1[3]!!
                assertEquals(4, k1.size)
                k1.insertBefore(targetElement.id, 6)
                assertEquals(
                    expected = "{\"k1\":[1,5,3,6,4]}",
                    actual = root.toJson(),
                )
                assertEquals(5, k1.size)
            }.await()

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                val targetElement = k1[0]!!
                assertEquals(4, k1.size)
                k1.insertBefore(targetElement.id, 7)
                assertEquals(
                    expected = "{\"k1\":[7,1,5,3,4]}",
                    actual = root.toJson(),
                )
                assertEquals(5, k1.size)
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                assertEquals(
                    expected = "{\"k1\":[7,1,5,3,6,4]}",
                    actual = root.toJson(),
                )
                assertEquals(6, k1.size)
            }.await()
        }
    }

    @Test
    fun can_handle_simple_array_set_operations() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            document1.updateAsync { root, _ ->
                root.setNewArray("k1").apply {
                    put(-1)
                    put(-2)
                    put(-3)
                }
                assertEquals(
                    expected = "{\"k1\":[-1,-2,-3]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.setInteger(1, -4)
                assertEquals(
                    expected = "{\"k1\":[-1,-4,-3]}",
                    actual = root.toJson(),
                )
            }.await()

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.setInteger(0, -5)
                assertEquals(
                    expected = "{\"k1\":[-5,-2,-3]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())
        }
    }

    // Generate all (op1, op2) combination tests
    @Test
    fun array_concurrency_table_test_insert_prev_vs_insert_prev() {
        testConcurrentOperations(concurrencyOperations[0], concurrencyOperations[0])
    }

    @Test
    fun array_concurrency_table_test_insert_prev_vs_insert_prev_next() {
        testConcurrentOperations(concurrencyOperations[0], concurrencyOperations[1])
    }

    @Test
    fun array_concurrency_table_test_insert_prev_vs_move_prev() {
        testConcurrentOperations(concurrencyOperations[0], concurrencyOperations[2])
    }

    @Test
    fun array_concurrency_table_test_insert_prev_vs_move_prev_next() {
        testConcurrentOperations(concurrencyOperations[0], concurrencyOperations[3])
    }

    @Test
    fun array_concurrency_table_test_insert_prev_vs_move_target() {
        testConcurrentOperations(concurrencyOperations[0], concurrencyOperations[4])
    }

    @Test
    fun array_concurrency_table_test_insert_prev_vs_set_target() {
        testConcurrentOperations(concurrencyOperations[0], concurrencyOperations[5])
    }

    @Test
    fun array_concurrency_table_test_insert_prev_vs_remove_target() {
        testConcurrentOperations(concurrencyOperations[0], concurrencyOperations[6])
    }

    @Test
    fun array_concurrency_table_test_set_target_vs_move_target() {
        testConcurrentOperations(concurrencyOperations[5], concurrencyOperations[4])
    }

    // Can handle complicated concurrent array operations
    data class ComplicatedArrayOp(
        val opName: String,
        val executor: (arr: JsonArray) -> Unit,
    )

    @Test
    fun complicated_concurrent_array_operations_insert() {
        testComplicatedConcurrentOperation(complicatedOperations[0])
    }

    @Test
    fun complicated_concurrent_array_operations_set() {
        testComplicatedConcurrentOperation(complicatedOperations[2])
    }

    @Test
    fun complicated_concurrent_array_operations_remove() {
        testComplicatedConcurrentOperation(complicatedOperations[3])
    }

    @Test
    fun array_set_by_index_test() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            document1.updateAsync { root, _ ->
                root.setNewArray("k1").apply {
                    put(-1)
                    put(-2)
                    put(-3)
                }
                assertEquals(
                    expected = "{\"k1\":[-1,-2,-3]}",
                    actual = root.toJson(),
                )
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document2.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.setInteger(1, -4)
                assertEquals(
                    expected = "{\"k1\":[-1,-4,-3]}",
                    actual = root.toJson(),
                )
            }.await()

            document1.updateAsync { root, _ ->
                val k1 = root.getAs<JsonArray>("k1")
                k1.setInteger(0, -5)
                assertEquals(
                    expected = "{\"k1\":[-5,-2,-3]}",
                    actual = root.toJson(),
                )
            }.await()

            val result = syncClientsThenCheckEqual(
                listOf(
                    ClientAndDocPair(client1, document1),
                    ClientAndDocPair(client2, document2),
                ),
            )
            assertTrue(result)
        }
    }

    // Helper functions
    private fun testConcurrentOperations(op1: ArrayOp, op2: ArrayOp) {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            document1.updateAsync { root, _ ->
                val a = root.setNewArray("a")
                initArr.forEach { a.put(it) }
                assertEquals(INIT_MARSHALL, root.toJson())
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()

            // Verify initial state
            assertEquals(document1.toJson(), document2.toJson())

            // Apply operations concurrently
            document1.updateAsync { root, _ ->
                val a = root.getAs<JsonArray>("a")
                op1.executor(a, 0)
            }.await()

            document2.updateAsync { root, _ ->
                val a = root.getAs<JsonArray>("a")
                op2.executor(a, 1)
            }.await()

            // Sync and verify convergence
            val result = syncClientsThenCheckEqual(
                listOf(
                    ClientAndDocPair(client1, document1),
                    ClientAndDocPair(client2, document2),
                ),
            )
            assertTrue(result)
        }
    }

    private fun testComplicatedConcurrentOperation(op: ComplicatedArrayOp) {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            // Reset documents for each test case
            document1.updateAsync { root, _ ->
                val a = root.setNewArray("a")
                initArr.forEach { a.put(it) }
                assertEquals(INIT_MARSHALL, root.toJson())
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()

            // Verify initial state
            assertEquals(document1.toJson(), document2.toJson())

            // Client 1 performs the test operation
            document1.updateAsync { root, _ ->
                val a = root.getAs<JsonArray>("a")
                op.executor(a)
            }.await()

            // Client 2 performs two move operations
            document2.updateAsync { root, _ ->
                val a = root.getAs<JsonArray>("a")
                // Move element at index 2 after element at oneIdx
                a.moveAfter(
                    a[COMPLICATED_ONE_IDX]!!.id,
                    a[2]!!.id,
                )

                // Move element at index 3 after element at index 2
                a.moveAfter(
                    a[2]!!.id,
                    a[3]!!.id,
                )
            }.await()

            // Sync and verify convergence
            val result = syncClientsThenCheckEqual(
                listOf(
                    ClientAndDocPair(client1, document1),
                    ClientAndDocPair(client2, document2),
                ),
            )
            assertTrue(result)
        }
    }

    private suspend fun syncClientsThenCheckEqual(pairs: List<ClientAndDocPair>): Boolean {
        assertTrue(pairs.size > 1)

        // Save own changes and get previous changes
        for (i in pairs.indices) {
            val pair = pairs[i]
            pair.client.syncAsync().await()
        }

        // Get last client changes
        // Last client get all precede changes in above loop
        for (pair in pairs.dropLast(1)) {
            pair.client.syncAsync().await()
        }

        // Assert start
        val expected = pairs[0].doc.toJson()
        for (i in 1 until pairs.size) {
            val v = pairs[i].doc.toJson()
            if (expected != v) {
                return false
            }
        }

        return true
    }

    // Array Concurrency Table Tests
    data class ArrayOp(
        val opName: String,
        val executor: (arr: JsonArray, cid: Int) -> Unit,
    )

    // Helper class for syncing clients and checking equality
    data class ClientAndDocPair(
        val client: Client,
        val doc: Document,
    )

    companion object {
        // Array Concurrency Table Tests constants
        private val initArr = listOf(1, 2, 3, 4)
        private const val INIT_MARSHALL = "{\"a\":[1,2,3,4]}"
        private const val ONE_IDX = 1
        private val otherIdxs = listOf(2, 3)
        private val newValues = listOf(5, 6)

        private val concurrencyOperations = listOf(
            // insert
            ArrayOp("insert.prev") { arr, cid ->
                arr.insertIntegerAfter(ONE_IDX, newValues[cid])
            },
            ArrayOp("insert.prev.next") { arr, cid ->
                arr.insertIntegerAfter(ONE_IDX - 1, newValues[cid])
            },
            // move
            ArrayOp("move.prev") { arr, cid ->
                arr.moveAfterByIndex(ONE_IDX, otherIdxs[cid])
            },
            ArrayOp("move.prev.next") { arr, cid ->
                arr.moveAfterByIndex(ONE_IDX - 1, otherIdxs[cid])
            },
            ArrayOp("move.target") { arr, cid ->
                arr.moveAfterByIndex(otherIdxs[cid], ONE_IDX)
            },
            // set by index
            ArrayOp("set.target") { arr, cid ->
                arr.setInteger(ONE_IDX, newValues[cid])
            },
            // remove
            ArrayOp("remove.target") { arr, _ ->
                arr.removeAt(ONE_IDX)
            },
        )

        // Complicated concurrent array operations constants
        private const val COMPLICATED_ONE_IDX = 1
        private const val COMPLICATED_OTHER_IDX = 0
        private const val COMPLICATED_NEW_VALUE = 5

        private val complicatedOperations = listOf(
            // insert
            ComplicatedArrayOp("insert") { arr ->
                arr.put(COMPLICATED_NEW_VALUE, arr[COMPLICATED_ONE_IDX]!!.id)
            },
            // move
            ComplicatedArrayOp("move") { arr ->
                arr.moveAfter(
                    arr[COMPLICATED_OTHER_IDX]!!.id,
                    arr[COMPLICATED_ONE_IDX]!!.id,
                )
            },
            // set (implemented as delete + insert)
            ComplicatedArrayOp("set") { arr ->
                val targetElement = arr[COMPLICATED_ONE_IDX]!!
                arr.remove(targetElement.id)
                if (COMPLICATED_ONE_IDX > 0) {
                    arr.put(COMPLICATED_NEW_VALUE, arr[COMPLICATED_ONE_IDX - 1]!!.id)
                } else {
                    val firstElement = arr[0]!!
                    arr.insertBefore(firstElement.id, COMPLICATED_NEW_VALUE)
                }
            },
            // remove
            ComplicatedArrayOp("remove") { arr ->
                arr.remove(arr[COMPLICATED_ONE_IDX]!!.id)
            },
        )
    }
}
