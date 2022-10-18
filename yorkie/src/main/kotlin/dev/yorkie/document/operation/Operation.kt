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
     * Returns the created time of the effected element.
     */
    abstract val effectedCreatedAt: TimeTicket

    /**
     * Executes this [Operation] on the given [Document.root].
     */
    abstract fun execute(root: CrdtRoot)

    /**
     * Sets the given [ActorID] to this [Operation].
     */
    fun setActor(actorID: ActorID) {
        executedAt = executedAt.setActor(actorID)
    }
}
