package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtContainer
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
    override var parentCreatedAt: TimeTicket,
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
    override fun execute(
        root: CrdtRoot,
        source: OpSource,
        versionVector: VersionVector?,
    ): ExecutionResult {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtObject) {
            val previousValue = if (parentObject.has(key)) {
                parentObject[key].takeIf { !it.isRemoved }
            } else {
                null
            }
            val copiedValue = value.deepCopy()
            copiedValue.removedAt = null
            val removed = parentObject.set(key, copiedValue, executedAt)
            if (source == OpSource.UndoRedo) {
                // NOTE(yorkie-js-sdk#1349): kept UndoRedo-only for parity —
                // the same undo reaching a peer as Remote (or replayed as
                // Local from a snapshot) leaves the peer's ledger stale;
                // drop this gate when upstream does. Deregisters the
                // REGISTERED element under the incoming createdAt (the
                // tombstone being restored, or a member a peer grew on it),
                // never the incoming copy — copiedValue has not been
                // registered yet, so findByCreatedAt only ever returns the
                // previously-registered element here.
                root.findByCreatedAt(copiedValue.createdAt)?.let(root::deregisterElement)
            }
            root.registerElement(copiedValue, parentObject)
            if (source == OpSource.UndoRedo && copiedValue is CrdtContainer) {
                // Divergence 3 (yorkie-js-sdk#1349 item 1, iOS fd15fa3cf6): a
                // tombstone nested inside the restored container must stay
                // collectable. Android's object-remove reverse deep-copies
                // the whole tombstoned subtree (ElementRht.deepCopy keeps
                // tombstones), so a nested member can still carry removedAt
                // here even though copiedValue.removedAt was just cleared
                // above. Adopt every such descendant into gc directly — the
                // restored container itself is untouched.
                copiedValue.getDescendants { elem, _ ->
                    if (elem.removedAt != null) root.adoptRemovedElement(elem)
                    false
                }
            }
            removed?.let(root::registerRemovedElement)
            // When the new value already has a removedAt (i.e. it was the LWW-losing side
            // of a concurrent set), register it as removed so GC can collect it once all
            // peers have seen the winning value.
            if (copiedValue.isRemoved) {
                root.registerRemovedElement(copiedValue)
            }

            val reverseOps = if (source.producesReverseOps) {
                val reverseOp = if (previousValue != null) {
                    SetOperation(key, previousValue.deepCopy(), parentCreatedAt, executedAt)
                } else {
                    RemoveOperation(copiedValue.createdAt, parentCreatedAt, executedAt)
                }
                listOf(reverseOp)
            } else {
                emptyList()
            }

            ExecutionResult(
                opInfos = listOf(
                    OperationInfo.SetOpInfo(key, root.createPath(parentCreatedAt)),
                ),
                reverseOps = reverseOps,
            )
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only object can execute set")
            ExecutionResult(opInfos = emptyList())
        }
    }

    companion object {
        private const val TAG = "SetOperation"
    }
}
