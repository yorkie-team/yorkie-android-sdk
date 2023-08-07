package dev.yorkie.core

import dev.yorkie.document.time.ActorID

public typealias Presence = Map<String, String>

public class Presences private constructor(
    private val map: MutableMap<ActorID, MutableMap<String, String>>,
) : Map<ActorID, Presence> by map {

    internal constructor() : this(mutableMapOf())

    internal operator fun set(actorID: ActorID, presence: Presence) {
        map.getOrPut(actorID) { mutableMapOf() }.putAll(presence)
    }

    internal fun remove(actorID: ActorID) {
        map -= actorID
    }

    override fun hashCode(): Int = map.hashCode()

    override fun equals(other: Any?): Boolean = map == (other as? Presences)?.map

    companion object {
        public fun Map<ActorID, Presence>.asPresences(): Presences {
            return Presences(mapValues { it.value.toMutableMap() }.toMutableMap())
        }

        public fun Pair<ActorID, Presence>.asPresences(): Presences {
            return Presences(mutableMapOf(first to second.toMutableMap()))
        }
    }
}

public data class PresenceInfo(val actorID: ActorID, val presence: Presence)
