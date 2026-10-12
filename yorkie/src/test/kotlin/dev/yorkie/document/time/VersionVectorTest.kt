package dev.yorkie.document.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ticketKnown] treats a non-null but EMPTY [VersionVector] (no actor has
 * ever been recorded in it — e.g. a freshly constructed change with no
 * causal history yet) exactly like a null one: the change could not
 * possibly know about ANY node yet, so it is admitted unconditionally
 * ("local", `true`) either way -- the early-return guard must check
 * `vv.size() == 0` and not just `vv == null`, or an empty (but non-null)
 * vector would fall through to `vv.get(...)`, which returns `null` for an
 * empty map, making the result `false`.
 */
class VersionVectorTest {
    private val actorA = "000000000000000000000001"
    private val actorB = "000000000000000000000002"

    private fun tick(lamport: Long, actor: String = actorA) = TimeTicket(lamport, 0u, actor)

    @Test
    fun `ticketKnown treats an empty but non-null version vector the same as a null one`() {
        val emptyButNonNull = VersionVector()
        assertEquals(0, emptyButNonNull.size())

        assertTrue(ticketKnown(emptyButNonNull, tick(5)))
        assertTrue(ticketKnown(null, tick(5)))
    }

    @Test
    fun `ticketKnown compares a ticket's lamport against its actor's recorded one`() {
        val vv = VersionVector().apply { set(actorA, 5) }

        assertTrue(ticketKnown(vv, tick(5, actorA)), "equal lamport is known (boundary >=)")
        assertFalse(ticketKnown(vv, tick(6, actorA)), "a higher lamport is not yet known")
        assertFalse(ticketKnown(vv, tick(1, actorB)), "an actor absent from the vector is unknown")
        assertTrue(ticketKnown(vv, tick(4, actorA)), "a lower lamport is known")
    }
}
