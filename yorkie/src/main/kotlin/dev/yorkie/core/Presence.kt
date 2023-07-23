package dev.yorkie.core

import dev.yorkie.document.change.ChangeContext

/**
 * [Presence] represents a proxy for the Presence to be manipulated from the outside.
 */
public data class Presence internal constructor(
    private val changeContext: ChangeContext,
    private val presence: MutableMap<String, String>,
) {

    public fun set(data: Map<String, String>) {
        presence.putAll(data)
        changeContext.presenceChange = PresenceChange.PresencePut(presence)
    }

    public fun clear() {
        presence.clear()
        changeContext.presenceChange = PresenceChange.PresenceClear
    }
}

internal sealed class PresenceChange {

    data class PresencePut(val presence: Map<String, String>) : PresenceChange()

    object PresenceClear : PresenceChange()
}
