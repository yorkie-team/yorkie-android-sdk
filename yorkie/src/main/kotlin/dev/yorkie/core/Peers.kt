package dev.yorkie.core

import dev.yorkie.document.time.ActorID

public class Peers private constructor(
    map: Map<ActorID, PresenceInfo>,
) : Map<ActorID, PresenceInfo> by map {

    companion object {
        public fun Map<ActorID, PresenceInfo>.asPeers() = Peers(toMap())

        public fun Pair<ActorID, PresenceInfo>.asPeers() = Peers(mapOf(this))
    }
}

public data class PresenceInfo(
    public val clock: Int,
    public val data: Map<String, String>,
)
