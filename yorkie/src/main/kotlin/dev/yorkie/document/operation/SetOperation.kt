package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

/**
 * [SetOperation] represents an operation that stores the value corresponding to the
 * given key in [CrdtObject].
 */
internal data class SetOperation(
    val key: String,
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
     * Executes this [SetOperation] on the given [root].
     */
    override fun execute(root: CrdtRoot, versionVector: VersionVector?): List<OperationInfo> {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtObject) {
            val copiedValue = value.deepCopy()
            val removed = parentObject.set(key, copiedValue, executedAt)
            root.registerElement(copiedValue, parentObject)
            removed?.let(root::registerRemovedElement)
            listOf(OperationInfo.SetOpInfo(key, root.createPath(parentCreatedAt)))
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only object can execute set")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "SetOperation"
    }
}
