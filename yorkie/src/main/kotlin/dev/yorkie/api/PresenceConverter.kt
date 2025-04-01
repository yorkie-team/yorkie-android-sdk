package dev.yorkie.api

import dev.yorkie.api.v1.presence
import dev.yorkie.api.v1.presenceChange
import dev.yorkie.document.presence.P
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.ActorID
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
        else -> throw YorkieException(ErrUnimplemented, "Unimplemented type: $type")
    }
}

internal fun Map<String, PBPresence>.toPresences(): Map<ActorID, P> {
    return entries.associate { (actorID, pbPresence) ->
        ActorID(actorID) to pbPresence.toPresence()
    }
}
