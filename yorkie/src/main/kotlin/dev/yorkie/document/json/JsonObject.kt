package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.RhtPQMap
import dev.yorkie.document.json.JsonElement.Companion.toJsonElement
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SetOperation
import java.util.Date

public class JsonObject internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtObject,
) : JsonElement(), Collection<JsonElement> {
    public val id
        get() = target.createdAt

    public val keys: List<String>
        get() = target.keys

    override val size: Int
        get() = target.keys.size

    public operator fun set(key: String, value: Boolean) {
        setPrimitive(key, value)
    }

    public operator fun set(key: String, value: Int) {
        setPrimitive(key, value)
    }

    public operator fun set(key: String, value: Long) {
        setPrimitive(key, value)
    }

    public operator fun set(key: String, value: Double) {
        setPrimitive(key, value)
    }

    public operator fun set(key: String, value: String) {
        setPrimitive(key, value)
    }

    public operator fun set(key: String, value: ByteArray) {
        setPrimitive(key, value)
    }

    public operator fun set(key: String, value: Date) {
        setPrimitive(key, value)
    }

    public operator fun set(key: String, value: JsonPrimitive?) {
        setPrimitive(key, value)
    }

    private fun setPrimitive(key: String, value: Any?) {
        val executedAt = context.issueTimeTicket()
        val primitive = if (value is JsonPrimitive) {
            value.target
        } else {
            CrdtPrimitive(value, executedAt)
        }
        setAndRegister(key, primitive)
    }

    /**
     * TODO(skhugh): we need to find a better way to handle this
     */
    public fun setNewObject(key: String): JsonObject {
        val crdtObject = CrdtObject(context.issueTimeTicket(), rht = RhtPQMap())
        setAndRegister(key, crdtObject)
        return crdtObject.toJsonElement(context)
    }

    public fun setNewArray(key: String): JsonArray {
        val crdtArray = CrdtArray(context.issueTimeTicket())
        setAndRegister(key, crdtArray)
        return crdtArray.toJsonElement(context)
    }

    private fun setAndRegister(key: String, element: CrdtElement) {
        val removed = target.set(key, element)
        context.registerElement(element, target)
        removed?.let(context::registerRemovedElement)
        context.push(
            SetOperation(
                key = key,
                value = element.deepCopy(),
                parentCreatedAt = target.createdAt,
                executedAt = element.createdAt,
            ),
        )
    }

    public operator fun <T : JsonElement> get(key: String) = target[key].toJsonElement<T>(context)

    public fun remove(key: String) {
        val executedAt = context.issueTimeTicket()
        val removed = try {
            target.removeByKey(key, executedAt)
        } catch (e: NoSuchElementException) {
            return
        }
        context.push(
            RemoveOperation(
                createdAt = removed.createdAt,
                parentCreatedAt = target.createdAt,
                executedAt = executedAt,
            ),
        )
        context.registerRemovedElement(removed)
    }

    override fun contains(element: JsonElement): Boolean {
        return target.asSequence().map { it.second.toJsonElement<JsonElement>(context) }.contains(element)
    }

    override fun containsAll(elements: Collection<JsonElement>): Boolean {
        return target.map { it.second.toJsonElement<JsonElement>(context) }.containsAll(elements)
    }

    override fun isEmpty(): Boolean {
        return target.keys.isEmpty()
    }

    override fun iterator(): Iterator<JsonElement> {
        return JsonObjectIterator(context, target)
    }

    private class JsonObjectIterator(
        private val context: ChangeContext,
        target: CrdtObject,
    ) : Iterator<JsonElement> {
        private val targetIterator = target.iterator()

        override fun hasNext(): Boolean {
            return targetIterator.hasNext()
        }

        override fun next(): JsonElement {
            return targetIterator.next().second.toJsonElement(context)
        }
    }
}
