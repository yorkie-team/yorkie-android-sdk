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
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import dev.yorkie.util.checkYorkieError

internal typealias PBChange = dev.yorkie.api.v1.Change
internal typealias PBChangeID = dev.yorkie.api.v1.ChangeID
internal typealias PBCheckPoint = dev.yorkie.api.v1.Checkpoint
internal typealias PBChangePack = dev.yorkie.api.v1.ChangePack

internal fun PBChange.toChange(): Change {
    return Change(
        id.toChangeID(),
        operationsList.toOperations(),
        takeIf { it.hasPresenceChange() }?.presenceChange?.toPresenceChange(),
        message.ifEmpty { null },
    )
}

internal fun List<PBChange>.toChanges(): List<Change> {
    return map { it.toChange() }
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
        isRemoved = isRemoved,
        versionVector = versionVector.toVersionVector(),
        epoch = epoch,
    )
}

internal fun ChangePack.toPBChangePack(): PBChangePack {
    val changePack = this
    return changePack {
        documentKey = changePack.documentKey
        checkpoint = changePack.checkPoint.toPBCheckPoint()
        snapshot = changePack.snapshot ?: ByteString.EMPTY
        changes.addAll(changePack.changes.toPBChanges())
        isRemoved = changePack.isRemoved
        versionVector = changePack.versionVector.toPBVersionVector()
        epoch = changePack.epoch
    }
}

/**
 * Encodes this [ChangeID] into its protobuf binary form.
 */
internal fun ChangeID.toByteString(): ByteString = toPBChangeID().toByteString()

/**
 * Decodes a [ChangeID] from its protobuf binary form.
 */
internal fun ByteString.toChangeID(): ChangeID = PBChangeID.parseFrom(this).toChangeID()

/**
 * Encodes this [Change] into the bytes a [dev.yorkie.core.StoredChange] carries. Android has
 * no `ChangeStruct`/`toStruct` layer like JS, so a stored change is simply
 * `change.toPBChange().toByteArray()` — matching how [dev.yorkie.document.Document.toBytes]
 * already carries pending changes, and iOS's own serialized `PbChange`. This store is
 * local-only and never crosses SDKs, so the encoding choice is Android-internal.
 */
internal fun Change.toStoredChangeBytes(): ByteArray = toPBChange().toByteArray()

/**
 * Decodes a [Change] from the bytes produced by [toStoredChangeBytes]. A malformed payload
 * surfaces the protobuf parser's own exception to the caller.
 *
 * Zero-length [bytes][ByteArray] are rejected with [YorkieException]([ErrInvalidArgument])
 * rather than decoded: an empty array parses to a default-valued,
 * zero-ID [dev.yorkie.api.v1.Change] without throwing, which would otherwise be replayed as a
 * silently-wrong entry instead of surfacing as the log corruption it actually is. JS is lenient
 * here; this is a deliberate Kotlin hardening.
 */
internal fun ByteArray.toStoredChange(): Change {
    checkYorkieError(
        isNotEmpty(),
        YorkieException(ErrInvalidArgument, "corrupt stored change: zero-length bytes"),
    )
    return PBChange.parseFrom(this).toChange()
}
