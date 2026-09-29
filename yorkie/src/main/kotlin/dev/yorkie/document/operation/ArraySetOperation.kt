package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.YorkieException

/**
 * `ArraySetOperation` is an operation representing setting an element in Array.
 */
internal data class ArraySetOperation(
    var createdAt: TimeTicket,
    val value: CrdtElement,
    override var parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {
    override val effectedCreatedAt: TimeTicket
        get() = createdAt

    override fun execute(
        root: CrdtRoot,
        source: OpSource,
        versionVector: VersionVector?,
    ): ExecutionResult {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "fail to find $parentCreatedAt",
            )

        if (parentObject !is CrdtArray) {
            throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "fail to execute, only array can execute set",
            )
        }

        val previousValue = parentObject[createdAt]
        val value = value.deepCopy()
        value.removedAt = null
        parentObject.insertAfter(createdAt, value, executedAt)
        val removed = parentObject.delete(createdAt, executedAt)

        // NOTE(yorkie-js-sdk#1341): the parent must be passed so garbageCollect can
        // reach `value` through its registration and call `purge` on the parent
        // once `value` is removed. A value registered with no parent (the old code
        // passed `null` here) can never be purged: garbageCollect's `parent =
        // pair.parent ?: return@forEach` guard skips it forever. No undo is
        // involved: setting an array element, then removing it, then collecting is
        // already enough to reach the leak.
        root.registerElement(value, parentObject)

        // NOTE(yorkie-js-sdk#1341): also register the element this op just
        // displaced. `removed` and `value` do NOT share a createdAt: this op's own
        // `createdAt` field IS the displaced element's identity (that is exactly
        // what addressing a set's target slot means), while `value` carries its
        // own, separate identity from ITS OWN createdAt (already used for the
        // registration above). Once `set` became this insert-then-remove shape,
        // `removed` needed its own registration; leaving it unregistered is what
        // let every element a set ever displaced stay uncounted, so a
        // set-in-a-loop grows docSize.live without bound.
        root.registerRemovedElement(removed)

        val reverseOp = if (source.producesReverseOps) {
            previousValue?.let {
                ArraySetOperation(
                    // Targets the element THIS op just installed (`value`'s own
                    // createdAt, preserved by deepCopy), not the element it
                    // displaced: undo must remove that installed element and
                    // restore the one it displaced. The reverse's own `value`
                    // below (`it.deepCopy()`, i.e. `previousValue`) is what gets
                    // reinstalled when the reverse executes (JS `28f4ad26`/#1059).
                    createdAt = value.createdAt,
                    value = it.deepCopy(),
                    parentCreatedAt = parentCreatedAt,
                    executedAt = executedAt,
                )
            }
        } else {
            null
        }

        return ExecutionResult(
            opInfos = listOf(
                OperationInfo.ArraySetOpInfo(
                    path = root.createPath(parentCreatedAt),
                ),
            ),
            reverseOps = listOfNotNull(reverseOp),
        )
    }
}
