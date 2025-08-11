package dev.yorkie.document.change

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.ActorID.Companion.INITIAL_ACTOR_ID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.document.time.VersionVector.Companion.INITIAL_VERSION_VECTOR

/**
 * Identifies the Change.
 * @param clientSeq: is the sequence number of the client that created this change.
 * @param lamport: lamport` is the lamport clock of this change. This is used to determine
 * the order of changes in logical time. It is optional and only present
 * if the change has operations.
 * @param actor: is the creator of this change.
 * @param versionVector: is the vector clock of this change. This is used to
 * determine the relationship is causal or not between changes. It is optional
 * and only present if the change has operations.
 * @param serverSeq: is optional and only present for changes stored on the server.
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
    fun next(excludeClocks: Boolean = false): ChangeID {
        if (excludeClocks) {
            return copy(
                clientSeq = clientSeq + 1u,
                versionVector = INITIAL_VERSION_VECTOR,
                serverSeq = TimeTicket.INITIAL_LAMPORT,
            )
        }

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

    private fun hasClocks(): Boolean {
        return versionVector.size() > 0 && lamport != TimeTicket.INITIAL_LAMPORT
    }

    /**
     * `syncClocks` syncs logical clocks with the given ID.
     */
    fun syncClocks(other: ChangeID): ChangeID {
        if (!other.hasClocks()) {
            return this
        }

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
     * `setLamport` sets the given lamport clock.
     */
    fun setLamport(lamport: Long): ChangeID {
        return copy(lamport = lamport)
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
