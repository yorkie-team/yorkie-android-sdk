package dev.yorkie.document.json

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtCounter.CounterType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Test

class JsonCounterTest {
    private val document = Document("")

    @Test
    fun `verify increase with Int type`() = runTest {
        document.updateAsync { root, _ ->
            val obj = root.setNewObject("k1")
            val age = obj.setNewCounter("age", 1)
            age.increase(1000)
            age.increase(100L)
        }.await()

        assertJsonContentEquals(
            """{ "k1": { "age": 1101 } }""",
            document.toJson(),
        )
    }

    @Test
    fun `verify increase with Long type`() = runTest {
        document.updateAsync { root, _ ->
            val obj = root.setNewObject("k1")
            val length = obj.setNewCounter("length", 1L)
            length.increase(1000L)
            length.increase(100)
        }.await()

        assertJsonContentEquals(
            """{ "k1": { "length": 1101 } }""",
            document.toJson(),
        )
    }

    @Test
    fun `verify whether increase input type is casted to counter type`() = runTest {
        document.updateAsync { root, _ ->
            val obj = root.setNewObject("k1")
            val lengthLong = obj.setNewCounter("lengthLong", 1L)
            lengthLong.increase(1000)
            assertEquals(CounterType.Int, lengthLong.target.type)

            val lengthInt = obj.setNewCounter("lengthInt", 1)
            lengthInt.increase(1000L)
            assertEquals(CounterType.Long, lengthInt.target.type)
        }.await()
    }

    @Test
    fun `dedup counter counts unique actors and ignores duplicates`() = runTest {
        // given - when
        document.updateAsync { root, _ ->
            val uv = root.setNewDedupCounter("uv")
            uv.add("alice")
            uv.add("bob")
            uv.add("alice") // duplicate
            uv.add("carol")
        }.await()

        // then
        val uv = document.getRoot().getAs<JsonDedupCounter>("uv")
        assertEquals(CounterType.IntDedup, uv.target.type)
        assertEquals(3, uv.value)
    }

    @Test
    fun `dedup counter rejects empty actor`() = runTest {
        document.updateAsync { root, _ ->
            val uv = root.setNewDedupCounter("uv")
            assertFailsWith<IllegalArgumentException> {
                uv.add("")
            }
        }.await()
    }

    @Test
    fun `getAs JsonCounter on a dedup counter is rejected at runtime`() = runTest {
        // given - a dedup counter is stored at the key
        document.updateAsync { root, _ ->
            root.setNewDedupCounter("uv")
        }.await()

        // when - then - accessing it as a numeric JsonCounter must fail
        assertFailsWith<TypeCastException> {
            document.getRoot().getAs<JsonCounter>("uv")
        }
    }

    @Test
    fun `getAs JsonDedupCounter on a numeric counter is rejected at runtime`() = runTest {
        // given - a numeric counter is stored at the key
        document.updateAsync { root, _ ->
            root.setNewCounter("pv", 0)
        }.await()

        // when - then - accessing it as a dedup counter must fail
        assertFailsWith<TypeCastException> {
            document.getRoot().getAs<JsonDedupCounter>("pv")
        }
    }
}
