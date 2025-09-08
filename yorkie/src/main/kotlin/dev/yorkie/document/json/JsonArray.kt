package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.MoveOperation
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieException
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

    public operator fun get(index: Int): JsonElement {
        return getCrdtElement(index).toJsonElement(context)
    }

    public operator fun get(createdAt: TimeTicket): JsonElement {
        val element = target[createdAt] ?: throw YorkieException(
            code = YorkieException.Code.ErrInvalidArgument,
            errorMessage = "element not found: $createdAt",
        )
        return element.toJsonElement(context)
    }

    public fun getOrNull(index: Int): JsonElement? {
        return target[index]?.toJsonElement(context)
    }

    public fun getOrNull(createdAt: TimeTicket): JsonElement? {
        return target[createdAt]?.toJsonElement(context)
    }

    private fun getCrdtElement(index: Int): CrdtElement {
        return target[index] ?: throw YorkieException(
            code = YorkieException.Code.ErrInvalidArgument,
            errorMessage = "index out of bounds: $index",
        )
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

    public operator fun set(index: Int, value: Int): JsonElement = setPrimitive(index, value)

    public operator fun set(index: Int, value: Boolean): JsonElement = setPrimitive(index, value)

    public operator fun set(index: Int, value: Long): JsonElement = setPrimitive(index, value)

    public operator fun set(index: Int, value: Double): JsonElement = setPrimitive(index, value)

    public operator fun set(index: Int, value: String): JsonElement = setPrimitive(index, value)

    public operator fun set(index: Int, value: ByteArray): JsonElement = setPrimitive(index, value)

    public operator fun set(index: Int, value: Date): JsonElement = setPrimitive(index, value)

    public fun setNewArray(index: Int): JsonArray {
        val createdAt = context.issueTimeTicket()
        val prevElement = getCrdtElement(index)
        val array = CrdtArray(createdAt)
        setValueInternal(prevElement, array, createdAt)
        return array.toJsonElement(context)
    }

    public fun setNewObject(index: Int): JsonObject {
        val createdAt = context.issueTimeTicket()
        val prevElement = getCrdtElement(index)
        val obj = CrdtObject(context.issueTimeTicket(), rht = ElementRht())
        setValueInternal(prevElement, obj, createdAt)
        return obj.toJsonElement(context)
    }

    private fun setPrimitive(index: Int, value: Any): JsonElement {
        val createdAt = context.issueTimeTicket()
        val prevElement = getCrdtElement(index)
        val element = CrdtPrimitive(value, createdAt)
        return setValueInternal(prevElement, element, createdAt)
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
        val deleted = target.delete(createdAt, executedAt)
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
        context.push(
            MoveOperation(
                parentCreatedAt = target.createdAt,
                prevCreatedAt = prevCreatedAt,
                createdAt = createdAt,
                executedAt = executedAt,
            ),
        )
        target.moveAfter(prevCreatedAt, createdAt, executedAt)
    }

    /**
     * `setValue` sets the element of the given index.
     */
    private fun setValueInternal(
        prevElement: CrdtElement,
        element: CrdtElement,
        createdAt: TimeTicket,
    ): JsonElement {
        val copiedValue = element.deepCopy()
        context.push(
            ArraySetOperation(
                createdAt = prevElement.createdAt,
                value = copiedValue,
                parentCreatedAt = target.createdAt,
                executedAt = createdAt,
            ),
        )

        target.set(prevElement.createdAt, element, createdAt)
        context.registerElement(element, target)

        return target.toJsonElement(context)
    }

    /**
     * `insertIntegerAfter` inserts a value after the given index.
     */
    public fun insertIntegerAfter(index: Int, value: Int): JsonElement {
        val prev = target[index]
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "index out of bounds: $index",
            )

        val ticket = context.issueTimeTicket()
        val element = CrdtPrimitive(
            value = value,
            createdAt = ticket,
        )
        target.insertAfter(prev.createdAt, element)
        context.registerElement(element, target)
        context.push(
            AddOperation(
                prevCreatedAt = prev.createdAt,
                value = element.deepCopy(),
                parentCreatedAt = target.createdAt,
                executedAt = ticket,
            ),
        )

        return target.toJsonElement(context)
    }

    /**
     * `insertBefore` inserts a value before the given next element.
     */
    public fun insertBefore(nextCreatedAt: TimeTicket, value: Any) {
        putPrimitive(value, target.getPrevCreatedAt(nextCreatedAt))
    }

    /**
     * `moveAfterByIndex` moves the element after the given index.
     */
    public fun moveAfterByIndex(prevIndex: Int, targetIndex: Int) {
        val prevElem = target[prevIndex]
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "index out of bounds: $prevIndex",
            )

        val targetElem = target[targetIndex]
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "index out of bounds: $targetIndex",
            )

        val ticket = context.issueTimeTicket()
        context.push(
            MoveOperation(
                prevCreatedAt = prevElem.createdAt,
                createdAt = targetElem.createdAt,
                parentCreatedAt = target.createdAt,
                executedAt = ticket,
            ),
        )
        target.moveAfter(prevElem.createdAt, targetElem.createdAt, ticket)
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
