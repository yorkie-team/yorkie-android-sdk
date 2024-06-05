package dev.yorkie.core

import dev.yorkie.document.time.ActorID

public typealias P = Map<String, String>

public class Presences private constructor(
    private val map: Map<ActorID, Map<String, String>>,
) : Map<ActorID, P> by map {

    public operator fun plus(presenceInfo: Pair<ActorID, P>): Presences {
        val (actorID, presence) = presenceInfo
        val newPresence = map[actorID].orEmpty() + presence
        return Presences(map + (actorID to newPresence))
    }

    public operator fun minus(actorID: ActorID): Presences {
        return Presences(map - actorID)
    }

    override fun toString(): String {
        return map.entries.toString()
    }

    companion object {
        public fun Map<ActorID, P>.asPresences(): Presences {
            return if (this is Presences) {
                Presences(map)
            } else {
                Presences(this)
            }
        }

        public fun Pair<ActorID, P>.asPresences(): Presences {
            return Presences(mapOf(this))
        }

        internal val UninitializedPresences = Presences(
            object : HashMap<ActorID, MutableMap<String, String>>() {
                override fun equals(other: Any?): Boolean = this === other
            },
        )
    }
}

public data class PresenceInfo(val actorID: ActorID, val presence: P)
