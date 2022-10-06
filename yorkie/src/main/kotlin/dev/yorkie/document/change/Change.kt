package dev.yorkie.document.change

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.time.ActorID

/**
 * Represents a unit of modification in the document.
 */
internal data class Change(
    var id: ChangeID,
    val operations: List<Operation>,
    val message: String?,
) {

    fun setActor(actorID: ActorID) {
        operations.forEach {
            it.setActor(actorID)
        }
        id = id.setActor(actorID)
    }

    fun execute(root: CrdtRoot) {
        operations.forEach {
            it.execute(root)
        }
    }
}
