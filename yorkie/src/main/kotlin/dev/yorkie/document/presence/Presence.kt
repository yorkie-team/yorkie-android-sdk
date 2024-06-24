package dev.yorkie.document.presence

import dev.yorkie.document.change.ChangeContext

/**
 * [Presence] represents a proxy for the Presence to be manipulated from the outside.
 */
public data class Presence internal constructor(
    private val changeContext: ChangeContext,
    private val initialPresence: P,
) {
    private val presence = initialPresence.toMutableMap()

    public fun put(data: Map<String, String>) {
        presence.putAll(data)
        changeContext.presenceChange = PresenceChange.Put(presence)
    }

    public fun clear() {
        changeContext.presenceChange = PresenceChange.Clear
    }
}

internal sealed class PresenceChange {

    data class Put(val presence: Map<String, String>) : PresenceChange()

    data object Clear : PresenceChange()
}
