package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CounterValue
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieException
import dev.yorkie.util.checkYorkieError
import java.util.Date

/**
 * [JsonObject] represents a JSON object, but unlike regular JSON, it has
 * [TimeTicket]s created by logical clock to resolve conflicts.
 */
public class JsonObject internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtObject,
) : JsonElement() {

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
        val crdtObject = CrdtObject(context.issueTimeTicket(), rht = ElementRht())
        setAndRegister(key, crdtObject)
        return crdtObject.toJsonElement(context)
    }

    public fun setNewArray(key: String): JsonArray {
        val crdtArray = CrdtArray(context.issueTimeTicket())
        setAndRegister(key, crdtArray)
        return crdtArray.toJsonElement(context)
    }

    public fun setNewText(key: String): JsonText {
        val crdtText = CrdtText(RgaTreeSplit(), context.issueTimeTicket())
        setAndRegister(key, crdtText)
        return crdtText.toJsonElement(context)
    }

    public fun setNewCounter(key: String, value: Int): JsonCounter {
        return setNewCounterInternal(key, value)
    }

    public fun setNewCounter(key: String, value: Long): JsonCounter {
        return setNewCounterInternal(key, value)
    }

    private fun setNewCounterInternal(key: String, value: CounterValue): JsonCounter {
        val counter = if (value is Int) {
            CrdtCounter(value, context.issueTimeTicket())
        } else {
            CrdtCounter(value.toLong(), context.issueTimeTicket())
        }
        setAndRegister(key, counter)
        return counter.toJsonElement(context)
    }

    public fun setNewTree(key: String, initialRoot: JsonTree.ElementNode? = null): JsonTree {
        val ticket = context.issueTimeTicket()
        val tree = CrdtTree(JsonTree.buildRoot(initialRoot, context), ticket)
        setAndRegister(key, tree)
        return tree.toJsonElement(context)
    }

    private fun setAndRegister(key: String, element: CrdtElement) {
        checkYorkieError(
            !key.contains("."),
            YorkieException(
                YorkieException.Code.ErrInvalidObjectKey,
                """key must not contain the "."."""
            ),
        )
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

    public operator fun get(key: String): JsonElement {
        return try {
            target[key].toJsonElement(context)
        } catch (e: NoSuchElementException) {
            throw NoSuchElementException("element with key: $key does not exist")
        }
    }

    public fun getOrNull(key: String): JsonElement? {
        return try {
            get(key)
        } catch (e: NoSuchElementException) {
            null
        }
    }

    @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
    public inline fun <reified T : JsonElement> getAs(key: String): T {
        return try {
            target[key].toJsonElement(context)
        } catch (e: NoSuchElementException) {
            throw NoSuchElementException("element with key: $key does not exist")
        }
    }

    @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
    public inline fun <reified T : JsonElement> getAsOrNull(key: String): T? {
        return try {
            getAs(key)
        } catch (e: TypeCastException) {
            null
        } catch (e: NoSuchElementException) {
            null
        }
    }

    public fun remove(key: String) {
        val executedAt = context.issueTimeTicket()
        val removed = try {
            target.removeByKey(key, executedAt)
        } catch (e: NoSuchElementException) {
            return
        } ?: return
        context.push(
            RemoveOperation(
                createdAt = removed.createdAt,
                parentCreatedAt = target.createdAt,
                executedAt = executedAt,
            ),
        )
        context.registerRemovedElement(removed)
    }
}
