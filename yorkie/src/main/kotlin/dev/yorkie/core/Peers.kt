package dev.yorkie.core

import dev.yorkie.document.time.ActorID

public typealias PresenceInfo = Map<String, String>

public class Peers private constructor(
    private val map: Map<ActorID, PresenceInfo>,
) : Map<ActorID, PresenceInfo> by map {

    internal constructor() : this(emptyMap())

    public operator fun plus(peer: Pair<ActorID, PresenceInfo>): Peers = (map + peer).asPeers()

    public operator fun minus(actorID: ActorID): Peers = (map - actorID).asPeers()

    override fun hashCode(): Int = map.hashCode()

    override fun equals(other: Any?): Boolean = map == (other as? Peers)?.map

    companion object {
        public fun Map<ActorID, PresenceInfo>.asPeers() = Peers(toMap())

        public fun Pair<ActorID, PresenceInfo>.asPeers() = Peers(mapOf(this))

        internal val UninitializedPresences = Peers()
    }
}
