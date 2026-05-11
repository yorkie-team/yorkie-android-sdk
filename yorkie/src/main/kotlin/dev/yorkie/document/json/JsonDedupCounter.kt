package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.operation.IncreaseOperation

/**
 * [JsonDedupCounter] counts unique actors via an internal HyperLogLog sketch
 * and exposes [add] to register one actor. Duplicate actors are ignored.
 *
 * For numeric increment counters, use [JsonCounter] instead. Keeping the two
 * roles in separate classes makes invalid method calls (such as `increase`
 * on a dedup counter) compile-time errors.
 */
public class JsonDedupCounter internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtCounter,
) : JsonElement() {

    public val value: Int
        get() = target.value.toInt()

    /**
     * Records a unique [actor] token in this dedup counter. Duplicate actors
     * are ignored. Returns this [JsonDedupCounter] for chaining.
     *
     * @throws IllegalArgumentException when [actor] is empty.
     */
    public fun add(actor: String): JsonDedupCounter {
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
