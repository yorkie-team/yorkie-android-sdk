package dev.yorkie.document.time

import dev.yorkie.document.time.ActorID.Companion.INITIAL_ACTOR_ID
import dev.yorkie.document.time.ActorID.Companion.MAX_ACTOR_ID

/**
 * [TimeTicket] is a timestamp of the logical clock. Ticket is immutable.
 */
public data class TimeTicket(
    public val lamport: Long,
    public val delimiter: Int,
    public val actorID: ActorID,
) : Comparable<TimeTicket> {

    /**
     * Creates a new instance of [TimeTicket] with the given [ActorID].
     */
    internal fun setActor(actorID: ActorID) = copy(actorID = actorID)

    override fun compareTo(other: TimeTicket): Int {
        return lamport.compareTo(other.lamport).takeUnless { it == 0 }
            ?: actorID.compareTo(other.actorID).takeUnless { it == 0 }
            ?: delimiter.compareTo(other.delimiter)
    }

    companion object {
        internal const val INITIAL_DELIMITER = 0
        internal const val MAX_DELIMITER = Int.MAX_VALUE
        internal const val MAX_LAMPORT = Long.MAX_VALUE

        internal val NullTimeTicket = TimeTicket(
            Long.MIN_VALUE,
            INITIAL_DELIMITER,
            INITIAL_ACTOR_ID,
        )

        public val InitialTimeTicket = TimeTicket(0, INITIAL_DELIMITER, INITIAL_ACTOR_ID)
        internal val MaxTimeTicket = TimeTicket(MAX_LAMPORT, MAX_DELIMITER, MAX_ACTOR_ID)

        public operator fun TimeTicket?.compareTo(other: TimeTicket?): Int {
            return orNull().compareTo(other.orNull())
        }

        private fun TimeTicket?.orNull() = this ?: NullTimeTicket
    }
}
