package dev.yorkie.document.json

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtCounter.CounterType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class JsonCounterTest {
    private val document = Document(Document.Key(""))

    @Test
    fun `verify increase with Int type`() {
        runTest {
            document.updateAsync { root, _ ->
                val obj = root.setNewObject("k1")
                val age = obj.setNewCounter("age", 1)
                age.increase(1000)
                age.increase(100L)
            }.await()
        }

        assertJsonContentEquals(
            """{ "k1": { "age": 1101 } }""",
            document.toJson(),
        )
    }

    @Test
    fun `verify increase with Long type`() {
        runTest {
            document.updateAsync { root, _ ->
                val obj = root.setNewObject("k1")
                val length = obj.setNewCounter("length", 1L)
                length.increase(1000L)
                length.increase(100)
            }.await()
        }

        assertJsonContentEquals(
            """{ "k1": { "length": 1101 } }""",
            document.toJson(),
        )
    }

    @Test
    fun `verify whether increase input type is casted to counter type`() {
        runTest {
            document.updateAsync { root, _ ->
                val obj = root.setNewObject("k1")
                val lengthLong = obj.setNewCounter("lengthLong", 1L)
                lengthLong.increase(1000)
                assertEquals(CounterType.IntegerCnt, lengthLong.target.type)

                val lengthInt = obj.setNewCounter("lengthInt", 1)
                lengthInt.increase(1000L)
                assertEquals(CounterType.LongCnt, lengthInt.target.type)
            }.await()
        }
    }
}
