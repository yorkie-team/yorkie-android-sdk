package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket

/**
 * Represents an operation to be executed on a document.
 * [parentCreatedAt] is the creation time of the target element to execute the operation.
 * [executedAt] is the execution time of this operation
 */
internal abstract class Operation(val parentCreatedAt: TimeTicket, var executedAt: TimeTicket) {

    /**
     * Sets the given [ActorID] to this [Operation].
     */
    fun setActor(actorID: ActorID) {
        executedAt = executedAt.setActor(actorID)
    }

    /**
     * Returns the time of the effected element.
     */
    abstract fun getEffectedCreatedAt(): TimeTicket

    /**
     * Executes this operation on the given document(`root`)
     */
    abstract fun execute(root: CrdtRoot)
}
