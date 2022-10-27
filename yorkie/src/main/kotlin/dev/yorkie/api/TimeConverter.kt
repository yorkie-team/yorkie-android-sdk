package dev.yorkie.api

import com.google.protobuf.kotlin.toByteStringUtf8
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
    return PBTimeTicket.newBuilder().apply {
        lamport = this@toPBTimeTicket.lamport
        delimiter = this@toPBTimeTicket.delimiter
        actorId = this@toPBTimeTicket.actorID.id.toByteStringUtf8()
    }.build()
}
