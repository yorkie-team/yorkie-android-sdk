package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieException
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class JsonArrayTest {

    private lateinit var target: JsonArray

    @Before
    fun setUp() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, rht = ElementRht())
        val array = CrdtArray(TimeTicket.InitialTimeTicket)
        target = JsonArray(
            ChangeContext(ChangeID.InitialChangeID, CrdtRoot(obj)),
            array,
        )
    }

    @Test
    fun `should put values and elements`() {
        val jsonPrimitive = JsonPrimitive(CrdtPrimitive("json", TimeTicket.InitialTimeTicket))
        target.apply {
            put(false)
            put(0)
            put(1L)
            put(2.0)
            put("string")
            put("byte array".toByteArray())
            put(Date(1_000))
            put(jsonPrimitive)
            putNewArray().put(1)
            putNewObject().setNewArray("array")
        }
        assertTrue(jsonPrimitive in target)
        assertEquals(
            """[false,0,1,2.0,"string","byte array",1000,"json",[1],{"array":[]}]""",
            target.toJson(),
        )
    }

    @Test
    fun `should put values and elements with prevCreatedAt`() {
        val inserted = listOf(
            target.putNewArray(),
            target.putNewObject().apply {
                set("k1", "v1")
            },
        )
        assertEquals("""[[],{"k1":"v1"}]""", target.toJson())
        assertTrue(target.containsAll(inserted))

        val array = assertIs<JsonArray>(inserted.first())
        target.put(1, array.id)
        assertEquals("""[[],1,{"k1":"v1"}]""", target.toJson())

        val obj = assertIs<JsonObject>(inserted.last())
        target.put(false, obj.id)
        assertEquals("""[[],1,{"k1":"v1"},false]""", target.toJson())
    }

    @Test
    fun `should remove elements using index`() {
        target.apply {
            put(0)
            putNewArray().put("value")
        }
        assertEquals("""[0,["value"]]""", target.toJson())

        assertIs<JsonPrimitive>(target.removeAt(0))
        assertEquals("""[["value"]]""", target.toJson())

        assertNull(target.removeAt(3))
        assertEquals("""[["value"]]""", target.toJson())

        assertIs<JsonArray>(target.removeAt(0))
        assertTrue(target.isEmpty())
    }

    @Test
    fun `should remove elements using TimeTicket`() {
        val obj = target.putNewObject()
        assertIs<JsonObject>(target.last())
        assertTrue(target.isNotEmpty())

        target.remove(obj.target.createdAt)
        assertTrue(target.isEmpty())

        assertThrows(YorkieException::class.java) {
            target.remove(TimeTicket.MaxTimeTicket)
        }
    }

    @Test
    fun `should handle moveAfter`() {
        target.apply {
            put(1)
            put(2)
            put(3)
        }
        assertEquals("[1,2,3]", target.toJson())

        val firstElement = requireNotNull(target.getAs<JsonPrimitive>(0))
        val lastElement = requireNotNull(target.getAs<JsonPrimitive>(2))
        target.moveAfter(lastElement.id, firstElement.id)
        assertEquals("[2,3,1]", target.toJson())
    }

    @Test
    fun `should handle moveBefore`() {
        target.apply {
            put(1)
            put(2)
            put(3)
        }
        assertEquals("[1,2,3]", target.toJson())

        val firstElement = requireNotNull(target.getAs<JsonPrimitive>(0))
        val lastElement = requireNotNull(target.getAs<JsonPrimitive>(2))
        target.moveBefore(lastElement.id, firstElement.id)
        assertEquals("[2,1,3]", target.toJson())
    }

    @Test
    fun `should handle moveFront`() {
        target.apply {
            put(1)
            put(2)
            put(3)
        }
        assertEquals("[1,2,3]", target.toJson())

        val lastElement = requireNotNull(target.getAs<JsonPrimitive>(2))
        target.moveFront(lastElement.id)
        assertEquals("[3,1,2]", target.toJson())
    }

    @Test
    fun `should handle moveLast`() {
        target.apply {
            put(1)
            put(2)
            put(3)
        }
        assertEquals("[1,2,3]", target.toJson())

        val firstElement = requireNotNull(target.getAs<JsonPrimitive>(0))
        target.moveLast(firstElement.id)
        assertEquals("[2,3,1]", target.toJson())
    }

    @Test
    fun `should return null when index for get function does not exist`() {
        assertNull(target.getOrNull(0))
    }

    @Test
    fun `should return null when index for getAs function does not exist`() {
        assertNull(target.getAs(0))
    }

    @Test
    fun `should return null when TimeTicket for get function does not exist`() {
        assertNull(target.getOrNull(TimeTicket.MaxTimeTicket))
    }

    @Test
    fun `should return null when TimeTicket for getAs function does not exist`() {
        assertNull(target.getAs(TimeTicket.MaxTimeTicket))
    }

    @Test
    fun `should return requested type with getAs`() {
        target.put(0)
        val get = assertIs<JsonPrimitive>(target[0])
        assertEquals(0, get.value)

        val getAs = assertIs<JsonPrimitive>(target.getAs<JsonPrimitive>(0))
        assertEquals(0, getAs.value)

        assertThrows(TypeCastException::class.java) {
            target.getAs<JsonArray>(0)
        }
    }
}
