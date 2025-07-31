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
}
