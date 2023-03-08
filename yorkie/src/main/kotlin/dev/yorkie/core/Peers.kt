package dev.yorkie.core

import dev.yorkie.document.time.ActorID

public class Peers private constructor(
    private val map: Map<ActorID, PresenceInfo>,
) : Map<ActorID, PresenceInfo> by map {

    internal constructor() : this(emptyMap())

    operator fun plus(peer: Pair<ActorID, PresenceInfo>): Peers {
        return (map + peer).asPeers()
    }

    operator fun minus(actorID: ActorID): Peers {
        return (map - actorID).asPeers()
    }

    companion object {
        public fun Map<ActorID, PresenceInfo>.asPeers() = Peers(toMap())

        public fun Pair<ActorID, PresenceInfo>.asPeers() = Peers(mapOf(this))
    }
}

public data class PresenceInfo(
    public val clock: Int,
    public val data: Map<String, String>,
)
