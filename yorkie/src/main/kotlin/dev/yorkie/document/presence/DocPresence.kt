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
        // Always records a provisional full Put -- NEVER decides Put vs Patch here.
        // `Document`'s commit-time decision (D2, spec 040) does that, immediately
        // before this context's change is appended to `localChanges`: the updater
        // is a suspend lambda, so the server's advertised capability can flip while
        // it is suspended, and reading it here would race that window (ported
        // determination vs JS `b50f8900` #1477's `Channel.set`, which decides
        // eagerly since JS has no such suspension). `presencePatchKeys` accumulates
        // so a Patch rewrite -- if the commit-time decision allows one -- carries
        // every key touched across however many `put()` calls this update made.
        changeContext.presencePatchKeys += data.keys
        changeContext.presenceChange = PresenceChange.Put(presence.toMap())
    }

    public fun clear() {
        presence.clear()
        changeContext.presencePatchKeys.clear()
        // CJ-MED-1 (round 4 fix): raises the context's baseless flag so a put() later in
        // this SAME update cannot be rewritten into a Patch at commit time -- see
        // ChangeContext.presenceBaseless's doc.
        changeContext.presenceBaseless = true
        changeContext.presenceChange = PresenceChange.Clear
    }
}

internal sealed interface PresenceChange {

    data class Put(val presence: Map<String, String>) : PresenceChange

    data object Clear : PresenceChange

    /**
     * [Patch] carries only the keys that changed since the sender's previous
     * presence. [presence] holds just those keys; [full] is the whole merged
     * presence known only to the sender that produced this patch locally --
     * `null` when decoded off the wire (a patch received from a pull never
     * carries `full`; see [dev.yorkie.api.toPresenceChange]). Sent only to a
     * server that advertises the "presence-patch" capability on `ChangePack`
     * (ported from JS `b50f8900` #1477).
     */
    data class Patch(val presence: Map<String, String>, val full: Map<String, String>?) :
        PresenceChange
}
