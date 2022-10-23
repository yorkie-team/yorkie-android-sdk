package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.json.JsonElement.Companion.toJsonElement
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.time.TimeTicket
import java.util.Date

public class JsonArray internal constructor(
    internal val context: ChangeContext,
    internal val target: CrdtArray,
) : JsonElement, Collection<JsonElement> {
    internal val id
        get() = target.createdAt

    override val size: Int
        get() = target.length

    public operator fun get(index: Int): JsonElement? {
        return target[index]?.toJsonElement(context)
    }

    internal operator fun get(createdAt: TimeTicket): JsonElement? {
        return target[createdAt]?.toJsonElement(context)
    }

    public fun put(value: Boolean) = putPrimitive(value)

    public fun put(value: Int) = putPrimitive(value)

    public fun put(value: Long) = putPrimitive(value)

    public fun put(value: Double) = putPrimitive(value)

    public fun put(value: String) = putPrimitive(value)

    public fun put(value: ByteArray) = putPrimitive(value)

    public fun put(value: Date) = putPrimitive(value)

    public fun put(value: JsonPrimitive) = putPrimitive(value)

    private fun putPrimitive(value: Any) {
        val primitive = if (value is JsonPrimitive) {
            value.target
        } else {
            CrdtPrimitive.of(value, context.issueTimeTicket())
        }
        putCrdtElement(primitive)
    }

    public fun putNewObject(): JsonObject {
        val obj = CrdtObject.create(context.issueTimeTicket())
        putCrdtElement(obj)
        return obj.toJsonElement(context)
    }

    public fun putNewArray(): JsonArray {
        val array = CrdtArray.create(context.issueTimeTicket())
        putCrdtElement(array)
        return array.toJsonElement(context)
    }

    private fun putCrdtElement(value: CrdtElement) {
        val prevCreated = target.lastCreatedAt
        target.insertAfter(prevCreated, value)
        context.registerElement(value, target)
        context.push(
            AddOperation(
                parentCreatedAt = target.createdAt,
                prevCreatedAt = prevCreated,
                value = value.deepCopy(),
                executedAt = value.createdAt,
            ),
        )
    }

    public fun removeAt(index: Int): JsonElement? {
        val executedAt = context.issueTimeTicket()
        val deleted = target.removeByIndex(index, executedAt) ?: return null
        context.push(
            RemoveOperation(
                createdAt = deleted.createdAt,
                parentCreatedAt = target.createdAt,
                executedAt = executedAt,
            ),
        )
        context.registerRemovedElement(deleted)
        return deleted.toJsonElement(context)
    }

    override fun contains(element: JsonElement): Boolean {
        return target.asSequence().map { it.toJsonElement<JsonElement>(context) }.contains(element)
    }

    override fun containsAll(elements: Collection<JsonElement>): Boolean {
        return target.map { it.toJsonElement<JsonElement>(context) }.containsAll(elements)
    }

    /**
     * TODO(skhugh): check if checking length is enough. maybe tombstone should be considered?
     */
    override fun isEmpty(): Boolean {
        return target.length == 0
    }

    override fun iterator(): Iterator<JsonElement> {
        return JsonArrayIterator(context, target)
    }

    private class JsonArrayIterator(
        private val context: ChangeContext,
        target: CrdtArray,
    ) : Iterator<JsonElement> {
        private val targetIterator = target.iterator()

        override fun hasNext(): Boolean {
            return targetIterator.hasNext()
        }

        override fun next(): JsonElement {
            return targetIterator.next().toJsonElement(context)
        }
    }
}
