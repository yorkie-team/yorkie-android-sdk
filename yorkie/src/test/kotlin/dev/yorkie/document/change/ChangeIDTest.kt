package dev.yorkie.document.change

import dev.yorkie.document.time.VersionVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChangeIDTest {
    private val actorA = "000000000000000000000001"
    private val actorB = "000000000000000000000002"
    private val actorC = "000000000000000000000003"

    private fun changeID(
        actor: String,
        lamport: Long,
        vector: Map<String, Long> = mapOf(actor to lamport),
    ) = ChangeID(
        clientSeq = 1u,
        lamport = lamport,
        actor = actor,
        versionVector = VersionVector(vector),
    )

    @Test
    fun `syncLamport should advance lamport past remote without merging version vector`() {
        val local = changeID(actorA, 3)
        val remote = changeID(actorB, 10, mapOf(actorB to 10L, actorC to 7L))

        val synced = local.syncLamport(remote)

        assertEquals(11, synced.lamport)
        assertEquals(1, synced.versionVector.size())
        assertEquals(11L, synced.versionVector.get(actorA))
    }

    @Test
    fun `syncLamport should advance own lamport when remote is behind`() {
        val local = changeID(actorA, 10)
        val remote = changeID(actorB, 3)

        val synced = local.syncLamport(remote)

        assertEquals(11, synced.lamport)
        assertEquals(1, synced.versionVector.size())
        assertEquals(11L, synced.versionVector.get(actorA))
    }

    @Test
    fun `syncLamport should return this when remote has no clocks`() {
        val local = changeID(actorA, 3)
        val remote = ChangeID(1u, 0, actorB, VersionVector())

        assertSame(local, local.syncLamport(remote))
    }

    @Test
    fun `syncClocks should merge remote version vector`() {
        val local = changeID(actorA, 3)
        val remote = changeID(actorB, 10, mapOf(actorB to 10L, actorC to 7L))

        val synced = local.syncClocks(remote)

        assertEquals(11, synced.lamport)
        assertEquals(3, synced.versionVector.size())
        assertEquals(11L, synced.versionVector.get(actorA))
        assertEquals(10L, synced.versionVector.get(actorB))
        assertEquals(7L, synced.versionVector.get(actorC))
    }

    @Test
    fun `next should increase clientSeq and lamport and update version vector`() {
        val id = changeID(actorA, 3)

        val next = id.next()

        assertEquals(2u, next.clientSeq)
        assertEquals(4, next.lamport)
        assertEquals(4L, next.versionVector.get(actorA))
    }

    @Test
    fun `setClocks should catch up to remote lamport and merge vector`() {
        val id = changeID(actorA, 3)

        val updated = id.setClocks(10, VersionVector(mapOf(actorB to 10L)))

        assertEquals(11, updated.lamport)
        assertEquals(11L, updated.versionVector.get(actorA))
        assertEquals(10L, updated.versionVector.get(actorB))
    }

    @Test
    fun `setLamport should replace lamport only`() {
        val id = changeID(actorA, 3)

        val updated = id.setLamport(42)

        assertEquals(42, updated.lamport)
        assertEquals(3L, updated.versionVector.get(actorA))
    }
}
