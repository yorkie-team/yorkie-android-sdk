package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import kotlin.test.assertIs

class JsonObjectTest {
    private lateinit var target: JsonObject

    @Before
    fun setUp() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, rht = ElementRht())
        target = JsonObject(
            ChangeContext(ChangeID.InitialChangeID, CrdtRoot(obj)),
            obj,
        )
    }

    @Test
    fun `should throw when accessing a key not added with get function`() {
        assertThrows(NoSuchElementException::class.java) {
            target["k1"]
        }
    }

    @Test
    fun `should return null when accessing a key not added with getOrNull function`() {
        assertNull(target.getOrNull("k1"))
    }

    @Test
    fun `should throw when accessing a key not added with getAs function`() {
        assertThrows(NoSuchElementException::class.java) {
            target.getAs<JsonPrimitive>("k1")
        }
    }

    @Test
    fun `should throw when setting a key containing delimiter`() {
        assertThrows(IllegalArgumentException::class.java) {
            target["."] = "dot"
        }

        assertThrows(IllegalArgumentException::class.java) {
            target["$..hello"] = "world"
        }

        assertThrows(IllegalArgumentException::class.java) {
            target.setNewObject("")["."] = "dot"
        }
    }

    @Test
    fun `should return null when accessing a key not added with getAsOrNull function`() {
        assertNull(target.getAsOrNull<JsonPrimitive>("k1"))
    }

    @Test
    fun `should return requested type with getAs`() {
        target["k1"] = 1
        val get = assertIs<JsonPrimitive>(target["k1"])
        assertEquals(1, get.value)

        val getAs = assertIs<JsonPrimitive>(target.getAs<JsonPrimitive>("k1"))
        assertEquals(1, getAs.value)

        assertThrows(TypeCastException::class.java) {
            target.getAs<JsonArray>("k1")
        }

        assertNull(target.getAsOrNull<JsonArray>("k1"))
    }

    @Test
    fun `should return all keys added to object`() {
        target["k1"] = "v1"
        target["k2"] = true
        target["k3"] = false
        target.remove("k3")
        assertEquals(listOf("k1", "k2"), target.keys)
    }
}
