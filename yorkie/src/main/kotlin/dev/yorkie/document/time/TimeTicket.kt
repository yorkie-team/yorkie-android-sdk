package dev.yorkie.document.time

/**
 * [TimeTicket] is a timestamp of the logical clock. Ticket is immutable.
 */
internal data class TimeTicket(
    val lamport: Long,
    val delimiter: Int,
    val actorID: ActorID,
) : Comparable<TimeTicket> {

    fun toIDString(): String {
        return "$lamport:$actorID:$delimiter"
    }

    override fun toString(): String {
        return toIDString()
    }

    override fun compareTo(other: TimeTicket): Int {
        return lamport.compareTo(other.lamport).takeUnless { it == 0 }
            ?: actorID.compareTo(other.actorID).takeUnless { it == 0 }
            ?: delimiter.compareTo(delimiter)
    }

    companion object {
        const val INITIAL_DELIMITER = 0
        const val MAX_DELIMITER = Int.MAX_VALUE
        const val MAX_LAMPORT = Long.MAX_VALUE

        val InitialTimeTicket = TimeTicket(0, INITIAL_DELIMITER, ActorID.INITIAL_ACTOR_ID)
        val MaxTimeTicket = TimeTicket(MAX_LAMPORT, MAX_DELIMITER, ActorID.MAX_ACTOR_ID)
    }
}
