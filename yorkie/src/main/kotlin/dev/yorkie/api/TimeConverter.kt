package dev.yorkie.api

import com.google.common.io.BaseEncoding
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket

typealias PBTimeTicket = dev.yorkie.api.v1.TimeTicket

internal fun PBTimeTicket.toTimeTicket(): TimeTicket {
    return TimeTicket(
        lamport = lamport,
        delimiter = delimiter,
        actorID = ActorID(BaseEncoding.base16().lowerCase().encode(actorId.toByteArray())),
    )
}
