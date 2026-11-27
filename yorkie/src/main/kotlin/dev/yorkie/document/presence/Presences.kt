package dev.yorkie.document.presence

/**
 * A presence: text values by key. On the wire a value is its JSON encoding, so the SDK encodes
 * the text you set and decodes what it reads: a value that is a JSON string reads as its
 * content (`"a"` on the wire reads `a`), any other value (an object, a number, ...) reads as
 * the JSON text. What you set reads back exactly as set. See [dev.yorkie.api.toPBPresence].
 *
 * Text that is itself valid JSON and not a string literal (`123`, `true`, `null`, `[1]`,
 * `{"x":0}`) goes on the wire as that JSON type, so JS and iOS peers read a number, boolean,
 * null, array or object rather than a string (unchanged from before). To send such text as a
 * string, pass a JSON string literal, for example `"\"123\""`.
 */
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
