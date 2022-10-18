package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [SetOperation] represents an operation that stores the value corresponding to the
 * given key in [CrdtObject].
 */
internal class SetOperation(
    val key: String,
    val value: CrdtElement,
    parentCreatedAt: TimeTicket,
    executedAt: TimeTicket,
) : Operation(parentCreatedAt, executedAt) {

    // Note(7hong13): original comment from JS-SDK:
    // `getEffectedCreatedAt` returns the time of the effected element.
    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = value.createdAt

    /**
     * Executes this [SetOperation] on the given [Document.root].
     */
    override fun execute(root: CrdtRoot) {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        if (parentObject is CrdtObject) {
            val copiedValue = value.deepCopy()
            parentObject[key] = copiedValue
            root.registerElement(copiedValue, parentObject)
        } else {
            if (parentObject == null) {
                YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            }
            YorkieLogger.e(TAG, "fail to execute, only object can execute set")
        }
    }

    /**
     * Returns a string containing the meta data.
     */
    override fun toString(): String {
        return "$parentCreatedAt.SET"
    }

    companion object {
        private const val TAG = "SetOperation"
    }
}
