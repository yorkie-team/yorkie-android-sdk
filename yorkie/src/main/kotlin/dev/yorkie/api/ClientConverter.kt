package dev.yorkie.api

import dev.yorkie.api.v1.client
import dev.yorkie.api.v1.presence
import dev.yorkie.core.Client
import dev.yorkie.core.PresenceInfo

internal typealias PBPresence = dev.yorkie.api.v1.Presence
internal typealias PBClient = dev.yorkie.api.v1.Client

internal fun Client.toPBClient(): PBClient {
    return client {
        id = requireClientId().toByteString()
        presence = presenceInfo.toPBPresence()
    }
}

internal fun PBPresence.toPresence(): PresenceInfo {
    return PresenceInfo(clock, dataMap.toMutableMap())
}

internal fun PresenceInfo.toPBPresence(): PBPresence {
    val presence = this
    return presence {
        clock = presence.clock
        data.putAll(presence.data)
    }
}
