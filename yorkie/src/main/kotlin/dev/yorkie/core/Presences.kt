package dev.yorkie.core

import dev.yorkie.document.time.ActorID

public typealias P = Map<String, String>

public class Presences private constructor(
    private val map: MutableMap<ActorID, MutableMap<String, String>>,
) : Map<ActorID, P> by map {

    internal constructor() : this(mutableMapOf())

    public operator fun plus(presenceInfo: Pair<ActorID, P>): Presences {
        val (actorID, presence) = presenceInfo
        val newPresence = map[actorID].orEmpty() + presence
        return (map + (actorID to newPresence)).asPresences()
    }

    public operator fun minus(actorID: ActorID): Presences = (map - actorID).asPresences()

    override fun hashCode(): Int = map.hashCode()

    override fun equals(other: Any?): Boolean = map == (other as? Presences)?.map

    companion object {
        public fun Map<ActorID, P>.asPresences(): Presences {
            return Presences(mapValues { it.value.toMutableMap() }.toMutableMap())
        }

        public fun Pair<ActorID, P>.asPresences(): Presences {
            return Presences(mutableMapOf(first to second.toMutableMap()))
        }

        internal val UninitializedPresences = Presences(
            object : HashMap<ActorID, MutableMap<String, String>>() {
                override fun equals(other: Any?): Boolean = this === other
            },
        )
    }
}

public data class PresenceInfo(val actorID: ActorID, val presence: P)
