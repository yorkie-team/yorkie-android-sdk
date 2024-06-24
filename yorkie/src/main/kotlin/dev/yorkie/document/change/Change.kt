package dev.yorkie.document.change

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.presence.Presences
import dev.yorkie.document.time.ActorID

/**
 * Represents a unit of modification in the document.
 */
public data class Change internal constructor(
    internal var id: ChangeID,
    internal val operations: List<Operation>,
    internal val presenceChange: PresenceChange? = null,
    internal val message: String? = null,
) {

    internal val hasPresenceChange: Boolean
        get() = presenceChange != null

    internal val hasOperations: Boolean
        get() = operations.isNotEmpty()

    internal fun setActor(actorID: ActorID) {
        operations.forEach {
            it.setActor(actorID)
        }
        id = id.setActor(actorID)
    }

    internal fun execute(
        root: CrdtRoot,
        presences: Presences,
    ): Pair<List<OperationInfo>, Presences?> {
        val newPresences = presenceChange?.let {
            when (presenceChange) {
                is PresenceChange.Put -> presences + (id.actor to presenceChange.presence)
                is PresenceChange.Clear -> presences - id.actor
            }
        }
        return operations.flatMap { it.execute(root) } to newPresences
    }
}
