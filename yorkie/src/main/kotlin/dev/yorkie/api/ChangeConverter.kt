package dev.yorkie.api

import com.google.protobuf.ByteString
import dev.yorkie.api.v1.change
import dev.yorkie.api.v1.changeID
import dev.yorkie.api.v1.changePack
import dev.yorkie.api.v1.checkpoint
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
    return change {
        id = this@toPBChange.id.toPBChangeID()
        message = this@toPBChange.message ?: ""
        operations.addAll(this@toPBChange.operations.toPBOperations())
    }
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
    return changeID {
        clientSeq = this@toPBChangeID.clientSeq
        lamport = this@toPBChangeID.lamport
        actorId = this@toPBChangeID.actor.id.toDecodedByteString()
    }
}

internal fun PBCheckPoint.toCheckPoint(): CheckPoint {
    return CheckPoint(serverSeq, clientSeq)
}

internal fun CheckPoint.toPBCheckPoint(): PBCheckPoint {
    return checkpoint {
        serverSeq = this@toPBCheckPoint.serverSeq
        clientSeq = this@toPBCheckPoint.clientSeq
    }
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
    val changePack = this
    return changePack {
        documentKey = changePack.documentKey
        checkpoint = changePack.checkPoint.toPBCheckPoint()
        snapshot = changePack.snapshot ?: ByteString.EMPTY
        changes.addAll(changePack.changes.toPBChanges())
        if (changePack.minSyncedTicket != null) {
            minSyncedTicket = changePack.minSyncedTicket.toPBTimeTicket()
        } else {
            clearMinSyncedTicket()
        }
    }
}
