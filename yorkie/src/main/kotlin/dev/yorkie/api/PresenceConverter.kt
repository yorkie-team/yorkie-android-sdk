package dev.yorkie.api

import dev.yorkie.api.v1.presence
import dev.yorkie.api.v1.presenceChange
import dev.yorkie.core.PresenceChange
import dev.yorkie.core.PresenceInfo
import dev.yorkie.document.time.ActorID

internal typealias PBPresence = dev.yorkie.api.v1.Presence
internal typealias PBPresenceChange = dev.yorkie.api.v1.PresenceChange
internal typealias PBPresenceChangeType = dev.yorkie.api.v1.PresenceChange.ChangeType

internal fun PresenceChange.toPBPresenceChange(): PBPresenceChange {
    val change = this
    return presenceChange {
        when (change) {
            is PresenceChange.PresencePut -> {
                type = PBPresenceChangeType.CHANGE_TYPE_PUT
                presence = change.presence.toPBPresence()
            }

            is PresenceChange.PresenceClear -> {
                type = PBPresenceChangeType.CHANGE_TYPE_CLEAR
            }
        }
    }
}

internal fun PresenceInfo.toPBPresence(): PBPresence {
    return presence {
        data.putAll(this@toPBPresence)
    }
}

internal fun PBPresence.toPresence(): PresenceInfo = dataMap

internal fun PBPresenceChange.toPresenceChange(): PresenceChange {
    return when (type) {
        PBPresenceChangeType.CHANGE_TYPE_PUT -> PresenceChange.PresencePut(presence.toPresence())
        PBPresenceChangeType.CHANGE_TYPE_CLEAR -> PresenceChange.PresenceClear
        else -> throw IllegalArgumentException("unsupported type: $type")
    }
}

internal fun Map<String, PBPresence>.toPresences(): Map<ActorID, PresenceInfo> {
    return map { (actorID, pbPresence) ->
        ActorID(actorID) to pbPresence.toPresence()
    }.toMap()
}
