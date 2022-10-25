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
    internal val target: CrdtObject,
) : JsonElement {
    internal val id
        get() = target.createdAt

    public val keys: List<String>
        get() = target.keys

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

    public operator fun set(key: String, value: JsonPrimitive) {
        setPrimitive(key, value)
    }

    private fun setPrimitive(key: String, value: Any) {
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
        val crdtObject = CrdtObject(context.issueTimeTicket(), RhtPQMap())
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

    public fun toJson() = target.toJson()

    override fun toString() = toJson()
}
