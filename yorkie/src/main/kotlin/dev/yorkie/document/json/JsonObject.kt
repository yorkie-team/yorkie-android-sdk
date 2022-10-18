package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.Primitive
import dev.yorkie.document.json.JsonElement.Companion.toJsonElement
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

    private fun setPrimitive(key: String, value: Any) {
        val ticket = context.issueTimeTicket()
        val primitive = Primitive.of(value, ticket)
        setAndRegister(key, primitive)
    }

    /**
     * TODO(skhugh): we need to find a better way to handle this
     */
    public fun setNewObject(key: String): JsonObject {
        val ticket = context.issueTimeTicket()
        val obj = JsonObject(context, CrdtObject.create(ticket))
        setAndRegister(key, obj.target)
        return obj
    }

    public fun setNewArray(key: String): JsonArray {
        TODO("set and register array")
    }

    private fun setAndRegister(key: String, element: CrdtElement) {
        val removed = target.set(key, element)
        context.registerElement(element, target)
        removed?.let(context::registerRemovedElement)
        TODO("push SetOperation")
    }

    public operator fun get(key: String) = target[key].toJsonElement(context)

    public fun remove(key: String) {
        val ticket = context.issueTimeTicket()
        val removed = try {
            target.removeByKey(key, ticket)
        } catch (e: NoSuchElementException) {
            return
        }
        TODO("push RemoveOperation")
        context.registerRemovedElement(removed)
    }

    public fun toJson() = target.toJson()

    override fun toString() = toJson()
}
