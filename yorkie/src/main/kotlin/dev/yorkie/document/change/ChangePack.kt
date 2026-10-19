package dev.yorkie.document.change

import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.isNotEmpty
import dev.yorkie.document.time.VersionVector

/**
 * [ChangePack] is a unit for delivering changes in a document to the remote.
 *
 * @property documentKey the key of the document.
 * @property checkPoint It is used to determine the client received changes.
 * @property snapshot a byte array that encodes the document.
 * It is used to collect garbage on the replica on the client.
 */
internal data class ChangePack(
    val documentKey: String,
    val checkPoint: CheckPoint,
    val changes: List<Change>,
    val snapshot: ByteString?,
    val isRemoved: Boolean,
    val versionVector: VersionVector,
    // epoch is the document's compaction epoch. A bidirectional carrier:
    // server responses set it to the document's current epoch, and an
    // attach/sync request presents the client's last-known epoch so the
    // server can detect a stale-epoch mismatch after a force compaction.
    val epoch: Long = 0,
    // capabilities is the wire-feature negotiation channel (`ChangePack.capabilities`,
    // field 9). The server sets it on every pack it returns; this SDK never writes
    // it back (D5, ported from JS `b50f8900` #1477) -- a client only reads what the
    // server advertises.
    val capabilities: List<String> = emptyList(),
) {

    /**
     * Returns the whether this [ChangePack] has changes or not.
     */
    val hasChanges
        get() = changes.isNotEmpty()

    /**
     * Returns the whether this [ChangePack] has a snapshot or not.
     */
    val hasSnapshot
        get() = snapshot?.isNotEmpty() == true

    /**
     * Returns whether the server advertised the wire feature named [name] on
     * this pack.
     */
    fun hasCapability(name: String): Boolean = name in capabilities

    companion object {
        /**
         * The capability name the server advertises on a [ChangePack] to mean
         * it folds a `CHANGE_TYPE_PATCH` presence change into a stored PUT
         * (JS `presence/change.ts` `PresencePatchCapability`).
         */
        const val PRESENCE_PATCH_CAPABILITY = "presence-patch"
    }
}
