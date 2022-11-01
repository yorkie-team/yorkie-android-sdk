package dev.yorkie.api

import com.google.common.io.BaseEncoding
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.v1.timeTicket
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket

internal typealias PBTimeTicket = dev.yorkie.api.v1.TimeTicket

internal fun PBTimeTicket.toTimeTicket(): TimeTicket {
    return TimeTicket(
        lamport = lamport,
        delimiter = delimiter,
        actorID = actorId.toActorID(),
    )
}

internal fun TimeTicket.toPBTimeTicket(): PBTimeTicket {
    val timeTicket = this
    return timeTicket {
        lamport = timeTicket.lamport
        delimiter = timeTicket.delimiter
        actorId = timeTicket.actorID.toByteString()
    }
}

internal fun ByteString.toActorID(): ActorID {
    return ActorID(BaseEncoding.base16().encode(toByteArray()))
}

internal fun ActorID.toByteString(): ByteString {
    return BaseEncoding.base16().decode(value).toByteString()
}
