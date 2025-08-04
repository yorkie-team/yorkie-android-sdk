package dev.yorkie.helper

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector

/**
 * `maxVectorOf` creates a VersionVector with the maximum lamport value for the given actors.
 */
fun maxVectorOf(actors: List<String>): VersionVector {
    val vectorMap = if (actors.isEmpty()) {
        mapOf(
            ActorID.INITIAL_ACTOR_ID.value to TimeTicket.MAX_LAMPORT,
        )
    } else {
        actors.associateWith { TimeTicket.MAX_LAMPORT }
    }

    return VersionVector(
        vectorMap = vectorMap,
    )
}
