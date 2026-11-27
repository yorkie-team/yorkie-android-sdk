package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtPrimitive.Type
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument

/**
 * [IncreaseOperation] represents an operation that increments a numeric value to [CrdtCounter].
 * Among [CrdtPrimitive] elements, numeric types Integer and Long are used as values.
 *
 * When [actor] is non-empty and the target counter is in dedup mode, this operation routes
 * through [CrdtCounter.increaseDedup] so the HLL sketch is updated for unique-visitor counting.
 */
internal data class IncreaseOperation(
    val value: CrdtElement,
    override var parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
    val actor: String = "",
) : Operation() {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    /**
     * Executes this [IncreaseOperation] on the given [root].
     */
    override fun execute(
        root: CrdtRoot,
        source: OpSource,
        versionVector: VersionVector?,
    ): ExecutionResult {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
            ?: throw YorkieException(
                ErrInvalidArgument,
                "fail to find ${parentCreatedAt.toTestString()}",
            )
        // Go (`ErrNotApplicableDataType`) and JS throw for EVERY source here, so does this.
        // Not routed through skipUnlessLocal on purpose: Go does not skip it either.
        if (parentObject !is CrdtCounter) {
            throw YorkieException(
                ErrInvalidArgument,
                "fail to execute, only Counter can execute increase",
            )
        }
        // The value arrives straight off the wire and may not be a primitive at all.
        val primitive = value as? CrdtPrimitive ?: return skipUnlessLocal(
            source,
            YorkieException(ErrInvalidArgument, "fail to execute, only Primitive can be increased"),
        )
        val copiedValue = primitive.deepCopy() as CrdtPrimitive
        return if (parentObject.isDedup()) {
            // A stored increase this SDK cannot apply -- no actor, or a delta the counter
            // refuses (not a unit increment) -- wedges the pack if it throws, since the
            // checkpoint never advances past a change that throws. Skip it on the pull
            // path, as Go's skipUnlessLocal does: local/undo/redo still report with the
            // counter's own error so the caller learns its edit was rejected. Ported
            // from JS `dcb74d27` #1502 and `d6704d015` #1525 (increase_operation.ts).
            if (actor.isEmpty()) {
                return skipUnlessLocal(
                    source,
                    YorkieException(ErrInvalidArgument, "dedup counter requires actor"),
                )
            }
            try {
                parentObject.increaseDedup(copiedValue, actor)
            } catch (cause: Exception) {
                return skipUnlessLocal(source, cause)
            }
            val newValue = parentObject.value
            ExecutionResult(
                opInfos = listOf(
                    OperationInfo.IncreaseOpInfo(
                        newValue,
                        root.createPath(parentCreatedAt),
                    ),
                ),
                // Dedup increments are not reversible since HLL cannot un-add an actor.
                reverseOps = emptyList(),
            )
        } else {
            try {
                parentObject.increase(copiedValue)
            } catch (cause: Exception) {
                return skipUnlessLocal(source, cause)
            }
            val delta = copiedValue.value as Number
            if (delta is Double && !delta.isFinite()) {
                // A NaN or an infinity is a no-op in the counter (as in Go): nothing
                // changed, so report nothing and keep no reverse.
                return ExecutionResult(opInfos = emptyList(), executed = false)
            }

            val reverseOps = if (source.producesReverseOps) {
                // A Double is negated as a Double, like JS `toReverseOperation`. Go
                // derives the reverse from the actual change for a Double on an Int
                // counter (`reverseFromChange`); they differ only for huge deltas.
                val negatedValue = when (copiedValue.type) {
                    Type.Integer -> CrdtPrimitive(
                        -(copiedValue.value as Int),
                        copiedValue.createdAt,
                    )
                    Type.Double -> CrdtPrimitive(
                        -(copiedValue.value as Double),
                        copiedValue.createdAt,
                    )
                    else -> CrdtPrimitive(-(copiedValue.value as Long), copiedValue.createdAt)
                }
                listOf(
                    IncreaseOperation(
                        value = negatedValue,
                        parentCreatedAt = parentCreatedAt,
                        executedAt = executedAt,
                    ),
                )
            } else {
                emptyList()
            }

            ExecutionResult(
                // JS emits the raw delta; a Double stays a Double.
                opInfos = listOf(
                    OperationInfo.IncreaseOpInfo(delta, root.createPath(parentCreatedAt)),
                ),
                reverseOps = reverseOps,
            )
        }
    }

    /**
     * Drops an increase that arrived from another replica and cannot be applied, and
     * rethrows [cause] for one this replica is producing, like Go's `skipUnlessLocal`.
     * The log names the failure only, never the delta or the value.
     */
    private fun skipUnlessLocal(source: OpSource, cause: Exception): ExecutionResult {
        if (source != OpSource.Remote) {
            throw cause
        }
        logError(
            TAG,
            "[Increase] skipped a remote increase that cannot apply: ${cause::class.simpleName}",
        )
        return ExecutionResult(opInfos = emptyList(), executed = false)
    }

    companion object {
        private const val TAG = "IncreaseOperation"
    }
}
