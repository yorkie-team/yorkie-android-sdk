package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

/**
 * [AddOperation] is an operation representing adding an element to an [CrdtArray].
 */
internal data class AddOperation(
    val prevCreatedAt: TimeTicket,
    val value: CrdtElement,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = value.createdAt

    /**
     * Executes this [AddOperation] on the given [root].
     */
    override fun execute(root: CrdtRoot, versionVector: VersionVector?): List<OperationInfo> {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtArray) {
            val copiedValue = value.deepCopy()
            parentObject.insertAfter(prevCreatedAt, copiedValue)
            root.registerElement(copiedValue, parentObject)
            listOf(
                OperationInfo.AddOpInfo(
                    parentObject.subPathOf(effectedCreatedAt).toInt(),
                    root.createPath(parentCreatedAt),
                ),
            )
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only array can execute add")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "AddOperation"
    }
}
