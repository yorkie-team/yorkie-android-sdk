package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive

public abstract class JsonElement {
    internal abstract val target: CrdtElement

    public fun toJson() = target.toJson()

    override fun toString() = toJson()

    companion object {
        private val TypeMapper = mapOf(
            CrdtObject::class.java to JsonObject::class.java,
            CrdtArray::class.java to JsonArray::class.java,
            CrdtPrimitive::class.java to JsonPrimitive::class.java,
            CrdtCounter::class.java to JsonCounter::class.java,
        )

        @Suppress("UNCHECKED_CAST")
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
}
