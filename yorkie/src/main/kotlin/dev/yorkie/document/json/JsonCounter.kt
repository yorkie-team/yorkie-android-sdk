package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CounterValue
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.operation.IncreaseOperation

/**
 * [JsonCounter] is a custom data type that is used to counter.
 *
 * When [isDedup] is true the counter is backed by a HyperLogLog sketch and only
 * supports [add], which records a unique actor. Otherwise it supports [increase]
 * with arbitrary integer or long deltas.
 */
public class JsonCounter internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtCounter,
) : JsonElement() {

    public val value: CounterValue
        get() = target.value

    /**
     * Returns true when this counter is in dedup mode.
     */
    public val isDedup: Boolean
        get() = target.isDedup()

    public fun increase(value: Int): JsonCounter {
        return increaseInternal(value)
    }

    public fun increase(value: Long): JsonCounter {
        return increaseInternal(value)
    }

    private fun increaseInternal(value: CounterValue): JsonCounter {
        check(!target.isDedup()) {
            "dedup counter does not support increase(), use add(actor)"
        }
        val ticket = context.issueTimeTicket()
        val counterValue = CrdtPrimitive(value, ticket)
        target.increase(counterValue)
        context.push(
            IncreaseOperation(
                value = counterValue,
                parentCreatedAt = target.createdAt,
                executedAt = ticket,
            ),
        )
        return this
    }

    /**
     * Records a unique [actor] token in the dedup counter. Duplicate actors are
     * ignored. Returns this [JsonCounter] for chaining.
     *
     * @throws IllegalStateException when the counter is not in dedup mode.
     * @throws IllegalArgumentException when [actor] is empty.
     */
    public fun add(actor: String): JsonCounter {
        check(target.isDedup()) {
            "add() is only supported on dedup counters"
        }
        require(actor.isNotEmpty()) {
            "actor is required"
        }
        val ticket = context.issueTimeTicket()
        val counterValue = CrdtPrimitive(1, ticket)
        target.increaseDedup(counterValue, actor)
        context.push(
            IncreaseOperation(
                value = counterValue,
                parentCreatedAt = target.createdAt,
                executedAt = ticket,
                actor = actor,
            ),
        )
        return this
    }
}
