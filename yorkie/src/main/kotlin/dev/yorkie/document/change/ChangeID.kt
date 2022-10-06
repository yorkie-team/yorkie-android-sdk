package dev.yorkie.document.change

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.ActorID.Companion.INITIAL_ACTOR_ID
import dev.yorkie.document.time.TimeTicket

/**
 * Identifies the Change.
 */
internal data class ChangeID(
    val clientSeq: Int,
    val lamport: Long,
    val actor: ActorID,
) {

    /**
     * Creates a next ID of this ID.
     */
    fun next() = copy(clientSeq = clientSeq + 1, lamport = lamport + 1)

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
     * Creates a ticket of the given delimiter.
     */
    fun createTimeTicket(delimiter: Int): TimeTicket {
        return TimeTicket(lamport, delimiter, actor)
    }

    /**
     * `setActor` sets the given actor.
     */
    fun setActor(actorID: ActorID) = copy(actor = actorID)

    companion object {
        /**
         * Represents the initial state ID.
         * Usually this is used to represent a state where nothing has been edited.
         */
        val InitialChangeID = ChangeID(0, 0, INITIAL_ACTOR_ID)
    }
}
