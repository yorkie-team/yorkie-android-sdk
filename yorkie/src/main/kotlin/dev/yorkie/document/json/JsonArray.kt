package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.MoveOperation
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.time.TimeTicket
import java.util.Date

/**
 * [JsonArray] represents a JSON array, but unlike regular JSON, it has
 * [TimeTicket]s created by logical clock to resolve conflicts.
 */
public class JsonArray internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtArray,
) : JsonElement(), Collection<JsonElement> {

    override val size: Int
        get() = target.length

    public operator fun get(index: Int): JsonElement? {
        return target[index]?.toJsonElement(context)
    }

    public operator fun get(createdAt: TimeTicket): JsonElement? {
        return target[createdAt]?.toJsonElement(context)
    }

    @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
    public inline fun <reified T : JsonElement> getAs(index: Int): T? {
        return target[index]?.toJsonElement(context)
    }

    @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
    public inline fun <reified T : JsonElement> getAs(createdAt: TimeTicket): T? {
        return target[createdAt]?.toJsonElement(context)
    }

    public fun put(value: Boolean, prevCreatedAt: TimeTicket? = null) =
        putPrimitive(value, prevCreatedAt)

    public fun put(value: Int, prevCreatedAt: TimeTicket? = null) =
        putPrimitive(value, prevCreatedAt)

    public fun put(value: Long, prevCreatedAt: TimeTicket? = null) =
        putPrimitive(value, prevCreatedAt)

    public fun put(value: Double, prevCreatedAt: TimeTicket? = null) =
        putPrimitive(value, prevCreatedAt)

    public fun put(value: String, prevCreatedAt: TimeTicket? = null) =
        putPrimitive(value, prevCreatedAt)

    public fun put(value: ByteArray, prevCreatedAt: TimeTicket? = null) =
        putPrimitive(value, prevCreatedAt)

    public fun put(value: Date, prevCreatedAt: TimeTicket? = null) =
        putPrimitive(value, prevCreatedAt)

    public fun put(value: JsonPrimitive, prevCreatedAt: TimeTicket? = null) =
        putPrimitive(value, prevCreatedAt)

    private fun putPrimitive(value: Any, prevCreatedAt: TimeTicket? = null) {
        val primitive = if (value is JsonPrimitive) {
            value.target
        } else {
            CrdtPrimitive(value, context.issueTimeTicket())
        }
        putCrdtElement(primitive, prevCreatedAt)
    }

    public fun putNewObject(prevCreatedAt: TimeTicket? = null): JsonObject {
        val obj = CrdtObject(context.issueTimeTicket(), rht = ElementRht())
        putCrdtElement(obj, prevCreatedAt)
        return obj.toJsonElement(context)
    }

    public fun putNewArray(prevCreatedAt: TimeTicket? = null): JsonArray {
        val array = CrdtArray(context.issueTimeTicket())
        putCrdtElement(array, prevCreatedAt)
        return array.toJsonElement(context)
    }

    private fun putCrdtElement(value: CrdtElement, prevCreatedAt: TimeTicket? = null) {
        val prevCreated = prevCreatedAt ?: target.lastCreatedAt
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

    public fun remove(createdAt: TimeTicket): JsonElement {
        val executedAt = context.issueTimeTicket()
        val deleted = target.remove(createdAt, executedAt)
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

    public fun moveAfter(prevCreatedAt: TimeTicket, createdAt: TimeTicket) {
        moveInternal(prevCreatedAt, createdAt)
    }

    public fun moveBefore(nextCreatedAt: TimeTicket, createdAt: TimeTicket) {
        moveInternal(target.getPrevCreatedAt(nextCreatedAt), createdAt)
    }

    public fun moveFront(createdAt: TimeTicket) {
        moveInternal(target.head.createdAt, createdAt)
    }

    public fun moveLast(createdAt: TimeTicket) {
        moveInternal(target.lastCreatedAt, createdAt)
    }

    private fun moveInternal(prevCreatedAt: TimeTicket, createdAt: TimeTicket) {
        val executedAt = context.issueTimeTicket()
        target.moveAfter(prevCreatedAt, createdAt, executedAt)
        context.push(
            MoveOperation(
                parentCreatedAt = target.createdAt,
                prevCreatedAt = prevCreatedAt,
                createdAt = createdAt,
                executedAt = executedAt,
            ),
        )
    }

    override fun contains(element: JsonElement): Boolean {
        return target.asSequence().map { it.toJsonElement<JsonElement>(context) }.contains(element)
    }

    override fun containsAll(elements: Collection<JsonElement>): Boolean {
        return target.map { it.toJsonElement<JsonElement>(context) }.containsAll(elements)
    }

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
