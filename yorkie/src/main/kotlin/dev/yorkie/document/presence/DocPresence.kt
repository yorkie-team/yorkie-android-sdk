package dev.yorkie.document.presence

import dev.yorkie.document.change.ChangeContext

/**
 * [DocPresence] represents a proxy for the Presence to be manipulated from the outside.
 */
public class DocPresence internal constructor(
    private val changeContext: ChangeContext,
    initialPresence: P,
) {
    private val presence = initialPresence.toMutableMap()

    public fun put(data: Map<String, String>) {
        presence.putAll(data)
        changeContext.presenceChange = PresenceChange.Put(presence.toMap())
    }

    public fun clear() {
        presence.clear()
        changeContext.presenceChange = PresenceChange.Clear
    }
}

internal sealed interface PresenceChange {

    data class Put(val presence: Map<String, String>) : PresenceChange

    data object Clear : PresenceChange
}
