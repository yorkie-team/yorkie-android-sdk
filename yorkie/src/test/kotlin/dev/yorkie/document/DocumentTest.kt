package dev.yorkie.document

import com.google.gson.JsonParser
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.OperationInfo.RemoveOpInfo
import dev.yorkie.document.operation.OperationInfo.SetOpInfo
import dev.yorkie.document.time.ActorID.Companion.INITIAL_ACTOR_ID
import dev.yorkie.document.time.TimeTicket.Companion.MAX_LAMPORT
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.YorkieException
import java.util.Date
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentTest {
    private lateinit var target: Document

    @Before
    fun setUp() {
        target = Document("")
    }

    @After
    fun tearDown() {
        target.close()
    }

    @Test
    fun `should not throw error when trying to delete missing key`() = runTest {
        var result = target.updateAsync { root, _ ->
            root["k1"] = "1"
            root["k2"] = "2"
            root["k2"] = "3"
            with(root.setNewArray("k3")) {
                put(1)
                put(2)
            }
        }.await()
        assertTrue(result.isSuccess)

        result = target.updateAsync { root, _ ->
            root.remove("k1")
            val array = root.getAs<JsonArray>("k3")
            array.removeAt(0)
            root.remove("k2")
            array.removeAt(2)
        }.await()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `should handle null values`() = runTest {
        target.updateAsync { root, _ ->
            val data = root.setNewObject("data")
            data["null"] = null
            data[""] = null
        }.await()

        assertJsonContentEquals("""{"data":{"":null,"null":null}}""", target.toJson())
    }

    @Test
    fun `should handle all types`() = runTest {
        target.updateAsync { root, _ ->
            val obj = root.setNewObject("data")
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

    @Test
    fun `should emit local change events when document properties are changed`() = runTest {
        val events = mutableListOf<Document.Event>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            target.events.collect(events::add)
        }

        assertFalse(target.hasLocalChanges())

        target.updateAsync { root, _ ->
            root["k1"] = 1
            root["k2"] = true
        }.await()

        assertEquals(1, events.size)
        var event = events.first()
        assertIs<Document.Event.LocalChange>(event)
        var operations = event.changeInfo.operations
        assertEquals(2, operations.size)
        assertTrue(operations.all { it is SetOpInfo })

        val firstSet = operations.first() as SetOpInfo
        assertEquals("k1", firstSet.key)

        val secondSet = operations.last() as SetOpInfo
        assertEquals("k2", secondSet.key)

        target.updateAsync { root, _ ->
            root.remove("k2")
            root.remove("k1")
        }.await()

        assertEquals(2, events.size)
        event = events.last()
        assertIs<Document.Event.LocalChange>(event)
        operations = event.changeInfo.operations
        assertEquals(2, operations.size)
        assertTrue(operations.all { it is RemoveOpInfo })

        val firstRemove = operations.first() as RemoveOpInfo
        assertEquals(secondSet.executedAt, firstRemove.executedAt)

        val secondRemove = operations.last() as RemoveOpInfo
        assertEquals(firstSet.executedAt, secondRemove.executedAt)

        assertTrue(target.hasLocalChanges())

        collectJob.cancel()
    }

    @Test
    fun `should clear clone when error occurs on update`() = runTest {
        target.updateAsync { root, _ ->
            root["k1"] = 1
        }.await()
        assertNotNull(target.clone)

        target.updateAsync { root, _ ->
            root["k2"] = 2
            error("error test")
        }.await()
        assertNull(target.clone)
    }

    @Test
    fun `should throw YorkieException when trying to find value from invalid paths`() {
        val exception = assertThrows(YorkieException::class.java) {
            runTest {
                target.getValueByPath("..$")
            }
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
    }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun `should get value from paths`() = runTest {
        target.updateAsync { root, _ ->
            root.setNewArray("todos").putNewObject().apply {
                set("text", "todo1")
                set("completed", false)
            }
            root.setNewObject("obj").setNewObject("c1").apply {
                set("name", "josh")
                set("age", 14)
            }
            root["str"] = "string"
        }.await()

        assertEquals(
            """{"todos":[{"text":"todo1","completed":false}],"obj":{"c1":{"name":"josh","age":14}},"str":"string"}""",
            target.getValueByPath("$")?.toJson(),
        )
        assertEquals(
            """[{"text":"todo1","completed":false}]""",
            target.getValueByPath("$.todos")?.toJson(),
        )
        assertEquals(
            """{"text":"todo1","completed":false}""",
            target.getValueByPath("$.todos.0")?.toJson(),
        )
        assertEquals(
            """{"c1":{"name":"josh","age":14}}""",
            target.getValueByPath("$.obj")?.toJson(),
        )
        assertEquals(
            """{"name":"josh","age":14}""",
            target.getValueByPath("$.obj.c1")?.toJson(),
        )
        assertEquals(
            """"josh"""",
            target.getValueByPath("$.obj.c1.name")?.toJson(),
        )
        assertEquals(
            """"string"""",
            target.getValueByPath("$.str")?.toJson(),
        )
        assertNull(target.getValueByPath("$..."))
    }

    @Test
    fun `should remove previously inserted elements in heap when running GC`() = runTest {
        target.updateAsync { root, _ ->
            root["a"] = 1
            root["a"] = 2
            root.remove("a")
        }.await()
        assertEquals("{}", target.toJson())
        assertEquals(2, target.garbageLength)

        target.garbageCollect(maxVectorOf(listOf()))
        assertEquals(0, target.garbageLength)
    }

    @Test
    fun `should handle escape string for strings containing single quotes`() = runTest {
        target.updateAsync { root, _ ->
            root["str"] = "I'm Yorkie"
        }.await()
        assertEquals("""{"str":"I'm Yorkie"}""", target.toJson())
        assertEquals(
            "I'm Yorkie",
            JsonParser.parseString(target.toJson()).asJsonObject.get("str").asString,
        )

        target.updateAsync { root, _ ->
            root["str"] = """I\\'m Yorkie"""
        }.await()
        assertEquals("""{"str":"I\\\\'m Yorkie"}""", target.toJson())
        assertEquals(
            """I\\'m Yorkie""",
            JsonParser.parseString(target.toJson()).asJsonObject.get("str").asString,
        )
    }

    @Test
    fun `should handle escape string for object keys`() = runTest {
        target.updateAsync { root, _ ->
            root["""it"s"""] = "Yorkie"
        }.await()
        assertEquals("""{"it\"s":"Yorkie"}""", target.toJson())
        assertEquals(
            "Yorkie",
            JsonParser.parseString(target.toJson()).asJsonObject.get("""it"s""").asString,
        )
    }

    @Test
    fun `should purge node from indexes during GC`() = runTest {
        target.updateAsync { root, _ ->
            root.setNewText("text")
        }.await()
        assertEquals(1, target.getRoot().getAs<JsonText>("text").treeByID.size)

        target.updateAsync { root, _ ->
            root.getAs<JsonText>("text").apply {
                edit(0, 0, "ABC")
            }
        }.await()
        assertEquals(2, target.getRoot().getAs<JsonText>("text").treeByID.size)

        target.updateAsync { root, _ ->
            root.getAs<JsonText>("text").apply {
                edit(1, 3, "")
            }
        }.await()
        assertEquals(3, target.getRoot().getAs<JsonText>("text").treeByID.size)

        target.garbageCollect(
            VersionVector().apply {
                set(INITIAL_ACTOR_ID.value, MAX_LAMPORT)
            },
        )
        assertEquals(2, target.getRoot().getAs<JsonText>("text").treeByID.size)
    }
}
