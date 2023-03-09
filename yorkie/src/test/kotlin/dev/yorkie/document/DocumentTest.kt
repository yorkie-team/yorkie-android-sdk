package dev.yorkie.document

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SetOperation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentTest {
    private lateinit var target: Document

    @Before
    fun setUp() {
        target = Document(Document.Key(""))
    }

    @Test
    fun `should not throw error when trying to delete missing key`() {
        runTest {
            var result = target.updateAsync {
                it["k1"] = "1"
                it["k2"] = "2"
                it["k2"] = "3"
                with(it.setNewArray("k3")) {
                    put(1)
                    put(2)
                }
            }.await()
            assertTrue(result)

            result = target.updateAsync {
                it.remove("k1")
                val array = it.getAs<JsonArray>("k3")
                array.removeAt(0)
                it.remove("k2")
                array.removeAt(2)
            }.await()
            assertTrue(result)
        }
    }

    @Test
    fun `should handle null values`() {
        runTest {
            target.updateAsync {
                val data = it.setNewObject("data")
                data["null"] = null
                data[""] = null
            }.await()

            assertJsonContentEquals("""{"data":{"":null,"null":null}}""", target.toJson())
        }
    }

    @Test
    fun `should handle all types`() {
        runTest {
            target.updateAsync {
                val obj = it.setNewObject("data")
                obj["k1"] = true
                obj["k2"] = false
                obj["k3"] = 11
                obj["k4"] = 100L
                obj["k5"] = 111.11
                obj["k6"] = "test string\n\n"
                obj["k7"] = "bytes".toByteArray()
                obj["k8"] = Date(1_000)

                val array = obj.setNewArray("k9")
                array.put(true)
                array.put(1)
                array.put(100L)
                array.put(111.111)
                array.put("test")
                array.put("bytes".toByteArray())
                array.put(Date(10_000))
                val arrayObj = array.putNewObject()
                arrayObj["k1"] = 1
                val arrayArray = array.putNewArray()
                arrayArray.put(1)
                arrayArray.put(2)

                obj.setNewCounter("int", 100)
                obj.setNewCounter("long", 100L)

                obj.setNewText("text").edit(0, 0, "Hello World")
                obj.getAs<JsonText>("text").style(0, 1, mapOf("b" to "1"))
            }.await()

            assertJsonContentEquals(
                """{
                        "data": {
                            "k1": true,
                            "k2": false,
                            "k3": 11,
                            "k4": 100,
                            "k5": 111.11,
                            "k6": "test string\n\n",
                            "k7": "bytes",
                            "k8": 1000,
                            "k9": [true, 1, 100, 111.111, "test", "bytes", 10000, {"k1": 1}, [1, 2]],
                            "int": 100,
                            "long": 100,
                            "text": [{"attrs":{"b":"1"},"val":"H"},{"val":"ello World"}]
                        }
                }""",
                target.toJson(),
            )
        }
    }

    @Test
    fun `should emit local change events when document properties are changed`() {
        runTest {
            val events = mutableListOf<Document.Event>()
            val collectJob = launch(UnconfinedTestDispatcher()) {
                target.events.collect(events::add)
            }

            assertFalse(target.hasLocalChanges)

            target.updateAsync {
                it["k1"] = 1
                it["k2"] = true
            }.await()

            assertEquals(1, events.size)
            var event = events.first()
            assertIs<Document.Event.LocalChange>(event)
            var change = event.changeInfos.first().change
            assertEquals(2, change.operations.size)
            assertTrue(change.operations.all { it is SetOperation })

            val firstSet = change.operations.first() as SetOperation
            assertEquals("k1", firstSet.key)
            assertEquals(1, (firstSet.value as CrdtPrimitive).value)

            val secondSet = change.operations.last() as SetOperation
            assertEquals("k2", secondSet.key)
            assertTrue((secondSet.value as CrdtPrimitive).value as Boolean)

            target.updateAsync {
                it.remove("k2")
                it.remove("k1")
            }.await()

            assertEquals(2, events.size)
            event = events.last()
            assertIs<Document.Event.LocalChange>(event)
            change = event.changeInfos.first().change
            assertEquals(2, change.operations.size)
            assertTrue(change.operations.all { it is RemoveOperation })

            val firstRemove = change.operations.first() as RemoveOperation
            assertEquals(secondSet.effectedCreatedAt, firstRemove.createdAt)

            val secondRemove = change.operations.last() as RemoveOperation
            assertEquals(firstSet.effectedCreatedAt, secondRemove.createdAt)

            assertTrue(target.hasLocalChanges)

            collectJob.cancel()
        }
    }

    @Test
    fun `should clear clone when error occurs on update`() {
        runTest {
            target.updateAsync {
                it["k1"] = 1
            }.await()
            assertNotNull(target.clone)

            target.updateAsync {
                it["k2"] = 2
                error("error test")
            }.await()
            assertNull(target.clone)
        }
    }
}
