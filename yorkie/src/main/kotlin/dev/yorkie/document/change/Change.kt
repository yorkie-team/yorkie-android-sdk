package dev.yorkie.document.change

import dev.yorkie.core.Peers
import dev.yorkie.core.PresenceChange
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID

/**
 * Represents a unit of modification in the document.
 */
public data class Change internal constructor(
    internal var id: ChangeID,
    internal val operations: List<Operation>,
    internal val presenceChange: PresenceChange?,
    internal val message: String?,
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
        presences: Peers,
    ): List<OperationInfo> {
        presenceChange?.let {
            when (presenceChange) {
                is PresenceChange.PresencePut -> presences + presenceChange.presence
                is PresenceChange.PresenceClear -> presences - id.actor
            }
        }
        return operations.flatMap {
            it.execute(root)
        }
    }
}
