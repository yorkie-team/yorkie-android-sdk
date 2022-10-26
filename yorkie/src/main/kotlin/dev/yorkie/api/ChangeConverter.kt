package dev.yorkie.api

import com.google.common.io.BaseEncoding
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.ActorID

typealias PBChange = dev.yorkie.api.v1.Change
typealias PBChangeID = dev.yorkie.api.v1.ChangeID
typealias PBCheckPoint = dev.yorkie.api.v1.Checkpoint
typealias PBChangePack = dev.yorkie.api.v1.ChangePack

internal fun List<PBChange>.toChanges(): List<Change> {
    return map {
        Change(it.id.toChangeID(), it.operationsList.toOperation(), it.message)
    }
}

internal fun PBChangeID.toChangeID(): ChangeID {
    return ChangeID(
        clientSeq,
        lamport,
        ActorID(BaseEncoding.base16().lowerCase().encode(actorId.toByteArray())),
    )
}

internal fun PBCheckPoint.toCheckPoint(): CheckPoint {
    return CheckPoint(serverSeq, clientSeq)
}

internal fun PBChangePack.toChangePack(): ChangePack {
    return ChangePack(
        documentKey = documentKey,
        checkPoint = checkpoint.toCheckPoint(),
        changes = changesList.toChanges(),
        snapshot = snapshot,
        minSyncedTicket = minSyncedTicket.toTimeTicket(),
    )
}
