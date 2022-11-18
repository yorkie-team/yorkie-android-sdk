package dev.yorkie.document.json

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.Document
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JsonCounterTest {
    private val document = Document(Document.Key(""))

    @Test
    fun `verify increase with Int type`() {
        runTest {
            document.updateAsync {
                val obj = it.setNewObject("k1")
                val age = obj.setNewCounter("age", 1)
                age.increase(1000)
                age.increase(100L)
            }.await()
        }

        assertJsonContentEquals(
            """{ "k1": { "age": 1101 } }""", document.toJson(),
        )
    }

    @Test
    fun `verify increase with Long type`() {
        runTest {
            document.updateAsync {
                val obj = it.setNewObject("k1")
                val length = obj.setNewCounter("length", 1L)
                length.increase(1000L)
                length.increase(100)
            }.await()
        }

        assertJsonContentEquals(
            """{ "k1": { "length": 1101 } }""", document.toJson(),
        )
    }
}
