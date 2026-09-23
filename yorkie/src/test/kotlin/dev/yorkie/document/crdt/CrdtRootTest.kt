package dev.yorkie.document.crdt

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [CrdtRoot]'s size-accounting internals directly (AC2, AC4, AC10):
 * [CrdtRoot.registerRemovedElement]'s idempotent top-up, [CrdtRoot.deregisterElement]'s
 * charged/uncharged/stale-twin accounting, and [CrdtRoot.adoptRemovedElement].
 */
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
        val k1 = obj["k1"]
        val k2 = obj["k2"]
        k1.remove(TimeTicket.InitialTimeTicket.copy(lamport = 3))
        k2.remove(TimeTicket.InitialTimeTicket.copy(lamport = 4))
        root.registerRemovedElement(k1)
        root.registerRemovedElement(k2)
        root.garbageCollect(VersionVector.INITIAL_VERSION_VECTOR)
    }

    // #1227: dead position nodes inside an array (e.g. restored from a snapshot) are
    // registered for GC when the root is constructed.
    @Test
    fun `should register dead position nodes of an array for gc on construction`() {
        val actor = "A"
        fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

        val array = CrdtArray(tick(1))
        val a = CrdtPrimitive("a", tick(2))
        val b = CrdtPrimitive("b", tick(3))
        listOf(a, b).forEach { array.insertAfter(array.lastCreatedAt, it) }
        // move a after b — a's old position becomes a dead node
        array.moveAfter(b.createdAt, a.createdAt, tick(4))

        val rootObject = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        rootObject.set("arr", array, tick(5))
        val root = CrdtRoot(rootObject)

        assertEquals(1, root.garbageLength)
        assertEquals(1, root.garbageCollect(maxVectorOf(listOf(actor))))
        assertEquals(0, root.garbageLength)
    }

    @Test
    fun `should register LWW-losing element in GC set on Set conflict`() {
        // given
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val actorA = "000000000000000000000001"
        val actorB = "000000000000000000000002"
        // actorB > actorA, so actorB wins LWW when lamport is equal.
        val ticketA = TimeTicket(lamport = 1, delimiter = 0u, actorID = actorA)
        val ticketB = TimeTicket(lamport = 1, delimiter = 0u, actorID = actorB)

        // when actorA sets "key" = 1
        SetOperation("key", CrdtPrimitive(1, ticketA), TimeTicket.InitialTimeTicket, ticketA)
            .execute(root, OpSource.Remote, null)

        // then no garbage yet
        assertEquals(0, root.garbageLength)

        // when actorB sets "key" = 2 (actorB wins, actorA is displaced)
        SetOperation("key", CrdtPrimitive(2, ticketB), TimeTicket.InitialTimeTicket, ticketB)
            .execute(root, OpSource.Remote, null)

        // then the displaced actorA element is registered as garbage
        assertEquals(1, root.garbageLength)
        assertEquals("""{"key":2}""", root.rootObject.toJson())

        // when actorA sets "key" = 3 (loses LWW to actorB's value)
        val ticketA2 = TimeTicket(lamport = 1, delimiter = 1u, actorID = actorA)
        SetOperation("key", CrdtPrimitive(3, ticketA2), TimeTicket.InitialTimeTicket, ticketA2)
            .execute(root, OpSource.Remote, null)

        // then the LWW-losing new value is also registered as garbage
        assertEquals(2, root.garbageLength)
        assertEquals("""{"key":2}""", root.rootObject.toJson())
    }

    // F2/AC8: a skipHistory (LocalNoHistory) change generates zero reverse ops at the
    // source, while Local and Remote keep their existing behavior.
    @Test
    fun `should generate reverse ops only for sources that push undo history`() {
        // given
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport = lamport, delimiter = 0u, actorID = actor)

        // when: Local produces a reverse op (existing behavior, unchanged)
        val localResult = SetOperation(
            "key",
            CrdtPrimitive(1, tick(1)),
            TimeTicket.InitialTimeTicket,
            tick(1),
        ).execute(root, OpSource.Local, null)

        // then
        assertEquals(1, localResult.reverseOps.size)

        // when: LocalNoHistory (skipHistory) produces zero reverse ops (F2)
        val skipHistoryResult = SetOperation(
            "key",
            CrdtPrimitive(2, tick(2)),
            TimeTicket.InitialTimeTicket,
            tick(2),
        ).execute(root, OpSource.LocalNoHistory, null)

        // then
        assertTrue(skipHistoryResult.reverseOps.isEmpty())
    }

    // T10 (AC2): registerRemovedElement is idempotent.
    @Test
    fun `registerRemovedElement tops up an already-charged element instead of moving it twice`() {
        // given: a container removal sweeps `member` into gc without setting
        // member's own removedAt (a descendant of a removed container).
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

        val container = CrdtObject(tick(1), memberNodes = ElementRht())
        root.rootObject.set(key = "k", value = container, executedAt = tick(1))
        root.registerElement(container, root.rootObject)

        val member = CrdtPrimitive("v", tick(2))
        container.set(key = "m", value = member, executedAt = tick(2))
        root.registerElement(member, container)

        container.remove(tick(3))
        root.registerRemovedElement(container)
        val gcAfterContainerRemoval = root.docSize.gc

        // when: member is removed directly afterwards and swept in again —
        // its getDataSize() grows by exactly one TIME_TICKET_SIZE now that
        // removedAt is set.
        member.remove(tick(4))
        val liveBeforeSecondCall = root.docSize.live
        root.registerRemovedElement(member)

        // then: gc grows by exactly the one-ticket top-up, live is untouched
        // by this second call (the size was already moved out on the first).
        assertEquals(liveBeforeSecondCall, root.docSize.live)
        assertEquals(
            DataSize(
                data = gcAfterContainerRemoval.data,
                meta = gcAfterContainerRemoval.meta + TimeTicket.TIME_TICKET_SIZE,
            ),
            root.docSize.gc,
        )
    }

    // T11a (AC4): a charged (removed) container releases exactly its charge from gc.
    @Test
    fun `deregisterElement releases exactly the charged amount from gc`() {
        // given: two independently-removed elements charged to gc.
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

        val k1 = CrdtPrimitive("v1", tick(1))
        root.rootObject.set(key = "k1", value = k1, executedAt = tick(1))
        root.registerElement(k1, root.rootObject)
        k1.remove(tick(2))
        root.registerRemovedElement(k1)

        val k2 = CrdtPrimitive("v2", tick(3))
        root.rootObject.set(key = "k2", value = k2, executedAt = tick(3))
        root.registerElement(k2, root.rootObject)
        k2.remove(tick(4))
        root.registerRemovedElement(k2)

        val chargedForK1 = k1.getDataSize()
        val gcBefore = root.docSize.gc

        // when
        val released = root.deregisterElement(k1)

        // then: only k1's charge leaves gc; k2 stays registered untouched.
        assertEquals(1, released)
        assertEquals(
            DataSize(
                data = gcBefore.data - chargedForK1.data,
                meta = gcBefore.meta - chargedForK1.meta,
            ),
            root.docSize.gc,
        )
        assertNull(root.findByCreatedAt(k1.createdAt))
        assertNotNull(root.findByCreatedAt(k2.createdAt))
    }

    // T11b (AC4): an element created inside an already-removed container —
    // never removed itself, never charged to gc — releases from live.
    @Test
    fun `deregisterElement releases a never-removed descendant from live, not gc`() {
        // given: `member` is registered into a container that is ALREADY
        // removed (e.g. a remote Set landing inside a container this replica
        // already tombstoned) — it is booked straight into live and never
        // swept by moveSizeToGC.
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

        val container = CrdtObject(tick(1), memberNodes = ElementRht())
        root.rootObject.set(key = "k", value = container, executedAt = tick(1))
        root.registerElement(container, root.rootObject)
        container.remove(tick(2))
        root.registerRemovedElement(container)

        val member = CrdtPrimitive("v", tick(3))
        container.set(key = "m", value = member, executedAt = tick(3))
        root.registerElement(member, container)

        val gcBefore = root.docSize.gc
        val liveBefore = root.docSize.live

        // when
        val released = root.deregisterElement(member)

        // then
        assertEquals(1, released)
        assertEquals(gcBefore, root.docSize.gc)
        assertEquals(
            DataSize(
                data = liveBefore.data - member.getDataSize().data,
                meta = liveBefore.meta - member.getDataSize().meta,
            ),
            root.docSize.live,
        )
        assertNull(root.findByCreatedAt(member.createdAt))
    }

    // T11c (AC4, Divergence 2): deregisterElement no-ops on a stale twin.
    @Test
    fun `deregisterElement no-ops when the createdAt is registered to a different instance`() {
        // given: `replacement` re-registers under the SAME createdAt as
        // `stale` (mirrors Android's array-remove undo reverse shape
        // re-registering a child under its original createdAt inside a new
        // container while the old tombstoned container still owns it).
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

        val stale = CrdtPrimitive("v1", tick(1))
        root.rootObject.set(key = "k", value = stale, executedAt = tick(1))
        root.registerElement(stale, root.rootObject)

        val replacement = CrdtPrimitive("v2", tick(1))
        root.registerElement(replacement, root.rootObject)

        val docSizeBefore = root.docSize
        val elementMapSizeBefore = root.elementMapSize
        val garbageLengthBefore = root.garbageLength

        // when
        val released = root.deregisterElement(stale)

        // then: nothing changes — the live twin's registration survives.
        assertEquals(0, released)
        assertEquals(docSizeBefore, root.docSize)
        assertEquals(elementMapSizeBefore, root.elementMapSize)
        assertEquals(garbageLengthBefore, root.garbageLength)
        assertEquals(replacement, root.findByCreatedAt(replacement.createdAt))
    }

    // T12 (AC10, Divergence 3): adoptRemovedElement charges gc without a
    // live refund.
    @Test
    fun `adoptRemovedElement charges gc without refunding a live ticket`() {
        // given: `member` is a live descendant of `container` but already
        // carries its own removedAt (e.g. a restored container's copy of a
        // nested tombstone).
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

        val container = CrdtObject(tick(1), memberNodes = ElementRht())
        root.rootObject.set(key = "k", value = container, executedAt = tick(1))
        root.registerElement(container, root.rootObject)

        val member = CrdtPrimitive("v", tick(2))
        member.remove(tick(3))
        container.set(key = "m", value = member, executedAt = tick(2))
        root.registerElement(member, container)

        val gcBefore = root.docSize.gc
        val liveBefore = root.docSize.live
        val memberSize = member.getDataSize()

        // when
        root.adoptRemovedElement(member)

        // then: gc grows and live shrinks by the member's post-removal size
        // — no extra TIME_TICKET_SIZE refund on top, unlike registerRemovedElement.
        assertEquals(
            DataSize(
                data = gcBefore.data + memberSize.data,
                meta = gcBefore.meta + memberSize.meta,
            ),
            root.docSize.gc,
        )
        assertEquals(
            DataSize(
                data = liveBefore.data - memberSize.data,
                meta = liveBefore.meta - memberSize.meta,
            ),
            root.docSize.live,
        )
        assertEquals(1, root.garbageLength)
    }
}
