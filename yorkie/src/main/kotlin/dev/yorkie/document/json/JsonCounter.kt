package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CounterValue
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.operation.IncreaseOperation

/**
 * [JsonCounter] is
 */
public class JsonCounter internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtCounter,
) : JsonElement() {
    public val id
        get() = target.id

    public fun increase(value: Int): JsonCounter {
        return increaseInternal(value)
    }

    public fun increase(value: Long): JsonCounter {
        return increaseInternal(value)
    }

    private fun increaseInternal(value: CounterValue): JsonCounter {
        val ticket = context.issueTimeTicket()
        val counterValue = CrdtPrimitive(value, ticket)
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
