package dev.yorkie.api

import dev.yorkie.api.v1.timeTicket
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket

typealias PBTimeTicket = dev.yorkie.api.v1.TimeTicket

internal fun PBTimeTicket.toTimeTicket(): TimeTicket {
    return TimeTicket(
        lamport = lamport,
        delimiter = delimiter,
        actorID = ActorID(actorId.toHexString()),
    )
}

internal fun TimeTicket.toPBTimeTicket(): PBTimeTicket {
    return timeTicket {
        lamport = this@toPBTimeTicket.lamport
        delimiter = this@toPBTimeTicket.delimiter
        actorId = this@toPBTimeTicket.actorID.id.toDecodedByteString()
    }
}
