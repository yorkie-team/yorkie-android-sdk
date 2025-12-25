package dev.yorkie.document.crdt

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrdtRootTest {

    // TODO(7hong13): maybe need to separate it into multiple unit test functions.
    @Suppress("ktlint:standard:property-naming")
    @Test
    fun `basic test`() {
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val cc = ChangeContext(ChangeID.InitialChangeID, root)
        assertNull(root.findByCreatedAt(TimeTicket.MaxTimeTicket))
        assertEquals("", root.createPath(TimeTicket.MaxTimeTicket))

        // set '$.k1'
        val k1 = CrdtPrimitive("k1", cc.issueTimeTicket())
        root.rootObject.set(
            key = "k1",
            value = k1,
            executedAt = cc.issueTimeTicket(),
        )
        root.registerElement(k1, root.rootObject)
        assertEquals(2, root.elementMapSize)
        assertEquals(k1, root.findByCreatedAt(k1.createdAt))
        assertEquals("$.k1", root.createPath(k1.createdAt))

        // delete '$.k1'
        assertNull(root.findByCreatedAt(TimeTicket.MaxTimeTicket))
        root.rootObject.removeByKey("k1", cc.issueTimeTicket())
        root.deregisterElement(k1)
        assertEquals(1, root.elementMapSize)
        assertNull(root.findByCreatedAt(k1.createdAt))

        // set '$.k2'
        val k2 = CrdtObject(cc.issueTimeTicket(), memberNodes = ElementRht())
        root.rootObject.set(
            key = "k2",
            value = k2,
            executedAt = cc.issueTimeTicket(),
        )
        root.registerElement(k2, root.rootObject)
        assertEquals(2, root.elementMapSize)
        assertEquals(k2, root.findByCreatedAt(k2.createdAt))
        assertEquals("$.k2", root.createPath(k2.createdAt))
        assertEquals("{}", k2.toJson())

        // set '$.k2.1'
        val k2_1 = CrdtArray(cc.issueTimeTicket())
        k2.set(
            key = "1",
            value = k2_1,
            executedAt = cc.issueTimeTicket(),
        )
        root.registerElement(k2_1, k2)
        assertEquals(3, root.elementMapSize)
        assertEquals(k2_1, root.findByCreatedAt(k2_1.createdAt))
        assertEquals("$.k2.1", root.createPath(k2_1.createdAt))

        // set '$.k2.1.0'
        val k2_1_0 = CrdtPrimitive("0", cc.issueTimeTicket())
        k2_1.insertAfter(k2_1.lastCreatedAt, k2_1_0)
        root.registerElement(k2_1_0, k2_1)
        assertEquals(4, root.elementMapSize)
        assertEquals(k2_1_0, root.findByCreatedAt(k2_1_0.createdAt))
        assertEquals("$.k2.1.0", root.createPath(k2_1_0.createdAt))

        // set '$.k2.1.1'
        val k2_1_1 = CrdtPrimitive("1", cc.issueTimeTicket())
        k2_1.insertAfter(k2_1_0.createdAt, k2_1_1)
        root.registerElement(k2_1_1, k2_1)
        assertEquals(5, root.elementMapSize)
        assertEquals(k2_1_1, root.findByCreatedAt(k2_1_1.createdAt))
        assertEquals("$.k2.1.1", root.createPath(k2_1_1.createdAt))
    }

    @Test
    fun `test gc`() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        obj.set(
            key = "k1",
            value = CrdtPrimitive("v1", TimeTicket.InitialTimeTicket.copy(lamport = 1)),
            executedAt = TimeTicket.InitialTimeTicket.copy(lamport = 1),
        )
        obj.set(
            key = "k2",
            value = CrdtPrimitive("v2", TimeTicket.InitialTimeTicket.copy(lamport = 2)),
            executedAt = TimeTicket.InitialTimeTicket.copy(lamport = 2),
        )
        val root = CrdtRoot(obj)
        obj["k1"].remove(TimeTicket.InitialTimeTicket.copy(lamport = 3))
        obj["k2"].remove(TimeTicket.InitialTimeTicket.copy(lamport = 4))
        root.registerRemovedElement(obj["k1"])
        root.registerRemovedElement(obj["k2"])
        root.garbageCollect(VersionVector.INITIAL_VERSION_VECTOR)
    }
}
