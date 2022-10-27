package dev.yorkie.api

import com.google.protobuf.kotlin.toByteStringUtf8
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

internal fun Change.toPBChange(): PBChange {
    return PBChange.newBuilder().apply {
        id = this@toPBChange.id.toPBChangeID()
        message = this@toPBChange.message
        this@toPBChange.operations.toPBOperations().forEachIndexed { index, operation ->
            setOperations(index, operation)
        }
    }.build()
}


internal fun List<Change>.toPBChanges(): List<PBChange> {
    return map { it.toPBChange() }
}

internal fun PBChangeID.toChangeID(): ChangeID {
    return ChangeID(
        clientSeq,
        lamport,
        ActorID(actorId.toHexString()),
    )
}

internal fun ChangeID.toPBChangeID(): PBChangeID {
    return PBChangeID.newBuilder().apply {
        clientSeq = this@toPBChangeID.clientSeq
        lamport = this@toPBChangeID.lamport
        actorId = this@toPBChangeID.actor.id.toByteStringUtf8()
    }.build()
}

internal fun PBCheckPoint.toCheckPoint(): CheckPoint {
    return CheckPoint(serverSeq, clientSeq)
}

internal fun CheckPoint.toPBCheckPoint(): PBCheckPoint {
    return PBCheckPoint.newBuilder().apply {
        serverSeq = this@toPBCheckPoint.serverSeq
        clientSeq = this@toPBCheckPoint.clientSeq
    }.build()
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

internal fun ChangePack.toPBChangePack(): PBChangePack {
    return PBChangePack.newBuilder().apply {
        documentKey = this@toPBChangePack.documentKey
        checkpoint = this@toPBChangePack.checkPoint.toPBCheckPoint()
        snapshot = this@toPBChangePack.snapshot
        minSyncedTicket = this@toPBChangePack.minSyncedTicket?.toPBTimeTicket()
        this@toPBChangePack.changes.toPBChanges().forEachIndexed { index, change ->
            setChanges(index, change)
        }
    }.build()
}
