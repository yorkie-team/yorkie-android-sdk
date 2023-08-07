package dev.yorkie.core

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.time.ActorID

/**
 * [PresenceProxy] represents a proxy for the Presence to be manipulated from the outside.
 */
public data class PresenceProxy internal constructor(
    private val changeContext: ChangeContext,
    private val presences: Presences,
    private val actorID: ActorID,
) {

    public fun set(data: Map<String, String>) {
        presences[actorID] = data
        val presence = presences[actorID] ?: emptyMap()
        changeContext.presenceChange = PresenceChange.Put(presence.toMap())
    }

    public fun clear() {
        presences.remove(actorID)
        changeContext.presenceChange = PresenceChange.Clear
    }
}

internal sealed class PresenceChange {

    data class Put(val presence: Map<String, String>) : PresenceChange()

    data object Clear : PresenceChange()
}
