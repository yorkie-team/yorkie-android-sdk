package dev.yorkie.document.crdt

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrdtRootTest {

    // TODO(7hong13): maybe need to separate it into multiple unit test functions.
    @Test
    fun `basic test`() {
        val root = CrdtRoot(CrdtObject.create(TimeTicket.InitialTimeTicket))
        val cc = ChangeContext(ChangeID.InitialChangeID, root, null)
        assertNull(root.findByCreatedAt(TimeTicket.MaxTimeTicket))
        assertEquals("", root.createPath(TimeTicket.MaxTimeTicket))

        // set '$.k1'
        val k1 = CrdtPrimitive.of("k1", cc.issueTimeTicket())
        root.rootObject["k1"] = k1
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
        val k2 = CrdtObject.create(cc.issueTimeTicket())
        root.rootObject["k2"] = k2
        root.registerElement(k2, root.rootObject)
        assertEquals(2, root.elementMapSize)
        assertEquals(k2, root.findByCreatedAt(k2.createdAt))
        assertEquals("$.k2", root.createPath(k2.createdAt))
        assertEquals("{}", k2.toJson())

        // set '$.k2.1'
        val k2_1 = CrdtArray.create(cc.issueTimeTicket())
        k2["1"] = k2_1
        root.registerElement(k2_1, k2)
        assertEquals(3, root.elementMapSize)
        assertEquals(k2_1, root.findByCreatedAt(k2_1.createdAt))
        assertEquals("$.k2.1", root.createPath(k2_1.createdAt))

        // set '$.k2.1.0'
        val k2_1_0 = CrdtPrimitive.of("0", cc.issueTimeTicket())
        k2_1.insertAfter(k2_1.lastCreatedAt, k2_1_0)
        root.registerElement(k2_1_0, k2_1)
        assertEquals(4, root.elementMapSize)
        assertEquals(k2_1_0, root.findByCreatedAt(k2_1_0.createdAt))
        assertEquals("$.k2.1.0", root.createPath(k2_1_0.createdAt))

        // set '$.k2.1.1'
        val k2_1_1 = CrdtPrimitive.of("1", cc.issueTimeTicket())
        k2_1.insertAfter(k2_1_0.createdAt, k2_1_1)
        root.registerElement(k2_1_1, k2_1)
        assertEquals(5, root.elementMapSize)
        assertEquals(k2_1_1, root.findByCreatedAt(k2_1_1.createdAt))
        assertEquals("$.k2.1.1", root.createPath(k2_1_1.createdAt))
    }
}
