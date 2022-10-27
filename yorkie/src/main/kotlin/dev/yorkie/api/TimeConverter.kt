package dev.yorkie.api

import dev.yorkie.api.v1.timeTicket
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket

internal typealias PBTimeTicket = dev.yorkie.api.v1.TimeTicket

internal fun PBTimeTicket.toTimeTicket(): TimeTicket {
    return TimeTicket(
        lamport = lamport,
        delimiter = delimiter,
        actorID = ActorID(actorId.toHexString()),
    )
}

internal fun TimeTicket.toPBTimeTicket(): PBTimeTicket {
    val timeTicket = this
    return timeTicket {
        lamport = timeTicket.lamport
        delimiter = timeTicket.delimiter
        actorId = timeTicket.actorID.id.toDecodedByteString()
    }
}
