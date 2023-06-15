package dev.yorkie.document.change

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.time.ActorID

/**
 * Represents a unit of modification in the document.
 */
public data class Change internal constructor(
    internal var id: ChangeID,
    internal val operations: List<Operation>,
    internal val message: String?,
) {

    internal fun setActor(actorID: ActorID) {
        operations.forEach {
            it.setActor(actorID)
        }
        id = id.setActor(actorID)
    }

    internal fun execute(root: CrdtRoot): List<OperationInfo> {
        return operations.flatMap {
            it.execute(root)
        }
    }
}
