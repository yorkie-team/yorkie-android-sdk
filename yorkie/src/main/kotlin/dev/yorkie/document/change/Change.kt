package dev.yorkie.document.change

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.presence.Presences

/**
 * Result of executing a [Change] against a CRDT root.
 */
internal data class ChangeExecutionResult(
    val opInfos: List<OperationInfo>,
    val newPresences: Presences?,
    val reverseOps: List<Operation>,
    val executedOperations: List<Operation>,
    val opInfoCounts: List<Int>,
)

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

    internal fun setActor(actorID: String) {
        operations.forEach {
            it.setActor(actorID)
        }
        id = id.setActor(actorID)
    }

    internal fun execute(
        root: CrdtRoot,
        presences: Presences,
        source: OpSource = OpSource.Local,
    ): ChangeExecutionResult {
        val newPresences = presenceChange?.let {
            when (presenceChange) {
                is PresenceChange.Put -> presences + (id.actor to presenceChange.presence)
                is PresenceChange.Clear -> presences - id.actor
            }
        }
        val allOpInfos = mutableListOf<OperationInfo>()
        val opInfoCounts = mutableListOf<Int>()
        val reverseOps = mutableListOf<Operation>()
        val executedOperations = mutableListOf<Operation>()

        for (op in operations) {
            val result = op.execute(root, source, id.versionVector)
            allOpInfos.addAll(result.opInfos)
            // addAll(0, ...) preserves internal order of multi-op reverses
            // while reversing the outer operation order (first op's reverse runs last)
            reverseOps.addAll(0, result.reverseOps)
            // Only an operation that actually ran (found its target) counts as
            // executed -- mirrors JS change.ts, which omits an operation from
            // its own `operations` result when `execute` returns undefined. A
            // style/tree-style that ran and admitted a tombstone (canStyle)
            // still belongs here even though its own opInfos can be empty.
            // opInfoCounts is filtered in lockstep so reconcileHistoryEdits's
            // index pairing with executedOperations stays aligned; an
            // unexecuted op always contributes an empty opInfos list (count
            // 0), so dropping it here changes nothing it would have sliced.
            if (result.executed) {
                executedOperations.add(op)
                opInfoCounts.add(result.opInfos.size)
            }
        }

        return ChangeExecutionResult(
            allOpInfos,
            newPresences,
            reverseOps,
            executedOperations,
            opInfoCounts,
        )
    }
}
