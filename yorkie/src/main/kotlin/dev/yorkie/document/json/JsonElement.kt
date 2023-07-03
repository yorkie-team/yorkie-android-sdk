package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.time.TimeTicket

/**
 * [JsonElement] is a wrapper for [CrdtElement] that provides users with an
 * easy-to-use interface for manipulating [Document]s.
 */
public abstract class JsonElement {
    internal abstract val target: CrdtElement

    public val id: TimeTicket
        get() = target.id

    public fun toJson(): String = target.toJson()

    override fun toString() = toJson()

    companion object {
        private val TypeMapper = mapOf(
            CrdtObject::class.java to JsonObject::class.java,
            CrdtArray::class.java to JsonArray::class.java,
            CrdtText::class.java to JsonText::class.java,
            CrdtPrimitive::class.java to JsonPrimitive::class.java,
            CrdtCounter::class.java to JsonCounter::class.java,
        )

        internal inline fun <reified T : JsonElement> CrdtElement.toJsonElement(
            context: ChangeContext,
        ): T {
            val clazz = if (T::class.java == JsonElement::class.java) {
                TypeMapper[this::class.java]
            } else {
                T::class.java
            }
            return try {
                when (clazz) {
                    JsonObject::class.java -> JsonObject(context, this as CrdtObject)
                    JsonArray::class.java -> JsonArray(context, this as CrdtArray)
                    JsonText::class.java -> JsonText(context, this as CrdtText)
                    JsonPrimitive::class.java -> JsonPrimitive(this as CrdtPrimitive)
                    JsonCounter::class.java -> JsonCounter(context, this as CrdtCounter)
                    else -> throw TypeCastException("unknown CrdtElement type: $this")
                } as T
            } catch (e: ClassCastException) {
                if (e is TypeCastException) {
                    throw e
                }
                throw TypeCastException(e.message)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        return target == (other as? JsonElement)?.target
    }

    override fun hashCode(): Int {
        return target.hashCode()
    }
}
