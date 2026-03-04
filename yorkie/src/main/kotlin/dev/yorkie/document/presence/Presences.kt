package dev.yorkie.document.presence

public typealias P = Map<String, String>

public class Presences private constructor(
    private val map: Map<String, Map<String, String>>,
) : Map<String, P> by map {

    public operator fun plus(presenceInfo: Pair<String, P>): Presences {
        val (actorID, presence) = presenceInfo
        val newPresence = map[actorID].orEmpty() + presence
        return Presences(map + (actorID to newPresence))
    }

    public operator fun minus(actorID: String): Presences {
        return Presences(map - actorID)
    }

    override fun toString(): String {
        return map.entries.toString()
    }

    companion object {
        public fun Map<String, P>.asPresences(): Presences {
            return if (this is Presences) {
                Presences(map)
            } else {
                Presences(this)
            }
        }

        public fun Pair<String, P>.asPresences(): Presences {
            return Presences(mapOf(this))
        }

        internal val UninitializedPresences = Presences(
            object : HashMap<String, MutableMap<String, String>>() {
                override fun equals(other: Any?): Boolean = this === other
            },
        )
    }
}

public data class PresenceInfo(val actorID: String, val presence: P)
