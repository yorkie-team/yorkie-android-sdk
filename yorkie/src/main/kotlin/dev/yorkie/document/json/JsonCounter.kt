package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CounterValue
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.operation.IncreaseOperation

/**
 * [JsonCounter] is a numeric counter that supports [increase].
 *
 * For counting unique actors via HyperLogLog, use [JsonDedupCounter] instead.
 * Splitting the two roles into separate classes makes invalid method calls
 * (such as [add] on a numeric counter) compile-time errors.
 */
public class JsonCounter internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtCounter,
) : JsonElement() {

    public val value: CounterValue
        get() = target.value

    /**
     * Increases this counter by [value] as an [Int] delta.
     */
    public fun increase(value: Int): JsonCounter {
        return increaseInternal(value)
    }

    /**
     * Increases this counter by [value] as a [Long] delta.
     */
    public fun increase(value: Long): JsonCounter {
        return increaseInternal(value)
    }

    private fun increaseInternal(value: CounterValue): JsonCounter {
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
}
