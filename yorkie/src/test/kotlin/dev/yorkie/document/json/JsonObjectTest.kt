package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.RhtPQMap
import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class JsonObjectTest {
    private lateinit var target: JsonObject

    @Before
    fun setUp() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, rht = RhtPQMap())
        target = JsonObject(
            ChangeContext(
                ChangeID.InitialChangeID,
                CrdtRoot(obj),
                null,
            ),
            obj,
        )
    }

    @Test
    fun `should throw when accessing a key not added with get function`() {
        assertThrows(NoSuchElementException::class.java) {
            target.get<JsonElement>("k1")
        }
    }

    @Test
    fun `should return null when accessing a key not added with getOrNull function`() {
        assertNull(target.getOrNull("k1"))
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
