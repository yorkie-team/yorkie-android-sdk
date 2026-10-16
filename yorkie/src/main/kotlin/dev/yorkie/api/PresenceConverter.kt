package dev.yorkie.api

import dev.yorkie.api.v1.presence
import dev.yorkie.api.v1.presenceChange
import dev.yorkie.document.presence.P
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrUnimplemented

internal typealias PBPresence = dev.yorkie.api.v1.Presence
internal typealias PBPresenceChange = dev.yorkie.api.v1.PresenceChange
internal typealias PBPresenceChangeType = dev.yorkie.api.v1.PresenceChange.ChangeType

internal fun PresenceChange.toPBPresenceChange(): PBPresenceChange {
    val change = this
    return presenceChange {
        when (change) {
            is PresenceChange.Put -> {
                type = PBPresenceChangeType.CHANGE_TYPE_PUT
                presence = change.presence.toPBPresence()
            }

            is PresenceChange.Clear -> {
                type = PBPresenceChangeType.CHANGE_TYPE_CLEAR
            }

            is PresenceChange.Patch -> {
                // `full` and `removed_keys` are never sent: the receiver either
                // merges the changed keys onto its held presence (a peer) or
                // never reads this change at all (the server folds it into a
                // stored PUT). Ported from JS `b50f8900` #1477.
                type = PBPresenceChangeType.CHANGE_TYPE_PATCH
                presence = change.presence.toPBPresence()
            }
        }
    }
}

internal fun P.toPBPresence(): PBPresence {
    return presence {
        data.putAll(this@toPBPresence)
    }
}

internal fun PBPresence.toPresence(): P = dataMap

internal fun PBPresenceChange.toPresenceChange(): PresenceChange {
    return when (type) {
        PBPresenceChangeType.CHANGE_TYPE_PUT -> PresenceChange.Put(presence.toPresence())
        PBPresenceChangeType.CHANGE_TYPE_CLEAR -> PresenceChange.Clear
        // `removed_keys` is ignored on decode (Android never sends it; a PATCH
        // always carries only set keys from this SDK). `full` is null here --
        // it is populated only when the change is produced locally, never when
        // decoded off the wire.
        PBPresenceChangeType.CHANGE_TYPE_PATCH ->
            PresenceChange.Patch(presence.toPresence(), full = null)
        else -> throw YorkieException(ErrUnimplemented, "Unimplemented type: $type")
    }
}

internal fun Map<String, PBPresence>.toPresences(): Map<String, P> {
    return entries.associate { (actorID, pbPresence) ->
        actorID to pbPresence.toPresence()
    }
}
