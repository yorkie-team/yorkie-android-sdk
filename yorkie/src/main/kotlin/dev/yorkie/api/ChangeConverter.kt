package dev.yorkie.api

import com.google.protobuf.ByteString
import dev.yorkie.api.v1.change
import dev.yorkie.api.v1.changeID
import dev.yorkie.api.v1.changePack
import dev.yorkie.api.v1.checkpoint
import dev.yorkie.api.v1.minSyncedTicketOrNull
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint

internal typealias PBChange = dev.yorkie.api.v1.Change
internal typealias PBChangeID = dev.yorkie.api.v1.ChangeID
internal typealias PBCheckPoint = dev.yorkie.api.v1.Checkpoint
internal typealias PBChangePack = dev.yorkie.api.v1.ChangePack

internal fun List<PBChange>.toChanges(): List<Change> {
    return map {
        Change(
            it.id.toChangeID(),
            it.operationsList.toOperations(),
            it.takeIf { it.hasPresenceChange() }?.presenceChange?.toPresenceChange(),
            it.message.ifEmpty { null },
        )
    }
}

internal fun Change.toPBChange(): PBChange {
    val change = this
    return change {
        id = change.id.toPBChangeID()
        message = change.message ?: ""
        operations.addAll(change.operations.toPBOperations())
        change.presenceChange?.let {
            presenceChange = it.toPBPresenceChange()
        }
    }
}

internal fun List<Change>.toPBChanges(): List<PBChange> {
    return map { it.toPBChange() }
}

internal fun PBChangeID.toChangeID(): ChangeID {
    return ChangeID(
        clientSeq.toUInt(),
        lamport,
        actorId.toActorID(),
        versionVector.toVersionVector(),
        serverSeq,
    )
}

internal fun ChangeID.toPBChangeID(): PBChangeID {
    val changeID = this
    return changeID {
        clientSeq = changeID.clientSeq.toInt()
        lamport = changeID.lamport
        actorId = changeID.actor.toByteString()
        serverSeq = changeID.serverSeq
        versionVector = changeID.versionVector.toPBVersionVector()
    }
}

internal fun PBCheckPoint.toCheckPoint(): CheckPoint {
    return CheckPoint(serverSeq, clientSeq.toUInt())
}

internal fun CheckPoint.toPBCheckPoint(): PBCheckPoint {
    val checkPoint = this
    return checkpoint {
        serverSeq = checkPoint.serverSeq
        clientSeq = checkPoint.clientSeq.toInt()
    }
}

internal fun PBChangePack.toChangePack(): ChangePack {
    return ChangePack(
        documentKey = documentKey,
        checkPoint = checkpoint.toCheckPoint(),
        changes = changesList.toChanges(),
        snapshot = snapshot.takeUnless { it.isEmpty },
        minSyncedTicket = minSyncedTicketOrNull?.toTimeTicket(),
        isRemoved = isRemoved,
        versionVector = versionVector.toVersionVector(),
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
        isRemoved = changePack.isRemoved
        versionVector = changePack.versionVector.toPBVersionVector()
    }
}
