package dev.yorkie.document.change

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.presence.Presences
import dev.yorkie.util.ChangeApplyError

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

    /**
     * Applies [operations] to [root] in order, one at a time (no rollback on a
     * throw partway through). A [source] of [OpSource.Remote] whose operation throws
     * is re-thrown as a [ChangeApplyError] naming this change ([id]) and the failing
     * operation's type + target ticket ONLY — never its payload, since the message is
     * surfaced to application code and logged on every redelivery. Every other
     * [source] (`Local`, `LocalNoHistory`, `UndoRedo`) rethrows the original error
     * unchanged: only a change already confirmed to be stuck in front of the whole
     * replica set needs naming. Ports JS SDK `change.ts` `execute` (`4251ab7e`, #1403).
     */
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

        for ((opIndex, op) in operations.withIndex()) {
            val result = try {
                op.execute(root, source, id.versionVector)
            } catch (cause: Throwable) {
                if (source != OpSource.Remote) {
                    throw cause
                }
                throw ChangeApplyError(
                    changeID = id.toTestString(),
                    opIndex = opIndex,
                    operation = "${op::class.simpleName} on ${op.parentCreatedAt.toTestString()}",
                    cause = cause,
                )
            }
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
