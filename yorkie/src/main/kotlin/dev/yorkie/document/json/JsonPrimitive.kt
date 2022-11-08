package dev.yorkie.document.json

import com.google.protobuf.ByteString
import dev.yorkie.document.crdt.CrdtPrimitive
import java.util.Date

public class JsonPrimitive internal constructor(
    override val target: CrdtPrimitive,
) : JsonElement() {
    public val value: Any?
        get() = target.value

    public val type: Type
        get() = when (target.type) {
            CrdtPrimitive.Type.Null -> Type.Null
            CrdtPrimitive.Type.Boolean -> Type.Boolean
            CrdtPrimitive.Type.Integer -> Type.Integer
            CrdtPrimitive.Type.Long -> Type.Long
            CrdtPrimitive.Type.Double -> Type.Double
            CrdtPrimitive.Type.String -> Type.String
            CrdtPrimitive.Type.Bytes -> Type.Bytes
            CrdtPrimitive.Type.Date -> Type.Date
        }

    public inline fun <reified T> getValueAs(): T {
        val value = checkNotNull(value)
        val isTypeValid = type == when (T::class) {
            Boolean::class -> Type.Boolean
            Int::class -> Type.Integer
            Long::class -> Type.Long
            Double::class -> Type.Double
            String::class -> Type.String
            ByteString::class -> Type.Bytes
            Date::class -> Type.Date
            else -> false
        }
        if (isTypeValid) {
            return value as T
        }
        throw TypeCastException(
            "cannot cast value with type ${value::class} to requested type ${T::class}",
        )
    }

    public inline fun <reified T> getValueAsOrNull(): T? {
        return runCatching {
            getValueAs<T>()
        }.recover {
            when (it) {
                is IllegalStateException, is TypeCastException -> null
                else -> throw it
            }
        }.getOrThrow()
    }

    public enum class Type {
        Null, Boolean, Integer, Long, Double, String, Bytes, Date
    }
}
