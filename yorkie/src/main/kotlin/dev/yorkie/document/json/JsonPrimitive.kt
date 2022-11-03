package dev.yorkie.document.json

import com.google.protobuf.ByteString
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.PrimitiveType
import java.util.Date
import kotlin.reflect.KProperty

public class JsonPrimitive internal constructor(
    override val target: CrdtPrimitive,
) : JsonElement() {
    public val value: Any?
        get() = target.value

    public val type: Type
        get() = when (target.type) {
            PrimitiveType.Null -> Type.Null
            PrimitiveType.Boolean -> Type.Boolean
            PrimitiveType.Integer -> Type.Integer
            PrimitiveType.Long -> Type.Long
            PrimitiveType.Double -> Type.Double
            PrimitiveType.String -> Type.String
            PrimitiveType.Bytes -> Type.Bytes
            PrimitiveType.Date -> Type.Date
        }

    public inline operator fun <reified T> getValue(thisObj: Any?, property: KProperty<*>): T? {
        val value = value ?: return null
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

    public enum class Type {
        Null, Boolean, Integer, Long, Double, String, Bytes, Date
    }
}
