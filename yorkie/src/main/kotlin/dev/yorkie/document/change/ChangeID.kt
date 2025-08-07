package dev.yorkie.document.change

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.ActorID.Companion.INITIAL_ACTOR_ID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.document.time.VersionVector.Companion.INITIAL_VERSION_VECTOR

/**
 * Identifies the Change.
 */
public data class ChangeID(
    val clientSeq: UInt,
    val lamport: Long,
    val actor: ActorID,
    val versionVector: VersionVector,
    val serverSeq: Long = 0,
) {

    /**
     * Creates a next ID of this ID.
     */
    fun next(): ChangeID {
        val vector = versionVector.deepCopy()
        vector.set(actor.value, lamport + 1)
        return copy(clientSeq = clientSeq + 1u, lamport = lamport + 1, versionVector = vector)
    }

    /**
     * Syncs lamport timestamp with the given ID.
     *
     * {@link https://en.wikipedia.org/wiki/Lamport_timestamps#Algorithm}
     */
    fun syncLamport(otherLamport: Long): ChangeID {
        if (otherLamport > lamport) {
            return copy(lamport = otherLamport)
        }
        return copy(lamport = lamport + 1)
    }

    /**
     * `syncClocks` syncs logical clocks with the given ID.
     */
    fun syncClocks(other: ChangeID): ChangeID {
        val lamport = if (other.lamport > lamport) other.lamport + 1 else lamport + 1
        val maxVersionVector = versionVector.max(other.versionVector)
        val newID = copy(
            lamport = lamport,
            versionVector = maxVersionVector,
        )
        newID.versionVector.set(actor.value, lamport)
        return newID
    }

    /**
     * `setClocks` sets the given clocks to this ID. This is used when the snapshot
     * is given from the server.
     */
    fun setClocks(otherLamport: Long, vector: VersionVector): ChangeID {
        val lamport = if (otherLamport > lamport) otherLamport + 1 else lamport + 1
        vector.unset(INITIAL_ACTOR_ID.value)

        val maxVersionVector = this.versionVector.max(vector)
        maxVersionVector.set(actor.value, lamport)
        return copy(
            lamport = lamport,
            versionVector = maxVersionVector,
        )
    }

    /**
     * Creates a ticket of the given delimiter.
     */
    fun createTimeTicket(delimiter: UInt): TimeTicket {
        return TimeTicket(lamport, delimiter, actor)
    }

    /**
     * Sets the given [actorID].
     */
    fun setActor(actorID: ActorID) = copy(actor = actorID)

    /**
     * Sets the vector [vector].
     */
    fun setVersionVector(vector: VersionVector) = copy(versionVector = vector)

    companion object {
        /**
         * Represents the initial state ID.
         * Usually this is used to represent a state where nothing has been edited.
         */
        val InitialChangeID = ChangeID(0u, 0, INITIAL_ACTOR_ID, INITIAL_VERSION_VECTOR)
    }
}
