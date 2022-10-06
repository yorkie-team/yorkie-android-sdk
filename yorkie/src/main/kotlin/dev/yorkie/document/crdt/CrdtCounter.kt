package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import java.nio.ByteBuffer

typealias CounterValue = Number

/**
 * [CrdtCounter] represents changeable number data type.
 */
internal class CrdtCounter private constructor(
    value: CounterValue,
    createdAt: TimeTicket,
) : CrdtElement(createdAt) {
    var value = value.sanitized()
        private set(value) {
            field = value.sanitized()
        }

    val type
        get() = value.counterType()

    fun toBytes(): ByteArray {
        return when (type) {
            CounterType.IntegerCnt -> ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.toInt())
            CounterType.LongCnt -> ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value.toLong())
            CounterType.DoubleCnt -> {
                ByteBuffer.allocate(Double.SIZE_BYTES).putDouble(value.toDouble())
            }
        }.array()
    }

    fun increase(primitive: Primitive) {
        val primitiveValue = primitive.value
        require(primitive.isNumericType && primitiveValue is Number) {
            "Unsupported type of value: ${primitive.type}"
        }
        val increaseResult = value.toDouble() + primitiveValue.toDouble()
        value = when (type) {
            CounterType.IntegerCnt -> {
                if (increaseResult < Int.MIN_VALUE || increaseResult > Int.MAX_VALUE) {
                    increaseResult.toLong()
                } else {
                    increaseResult.toInt()
                }
            }
            CounterType.LongCnt -> increaseResult.toLong()
            CounterType.DoubleCnt -> increaseResult
        }
    }

    override fun toJson(): String {
        TODO("To be implemented when it's actually needed")
    }

    override fun toSortedJson(): String {
        TODO("To be implemented when it's actually needed")
    }

    override fun deepCopy(): CrdtElement {
        TODO("To be implemented when it's actually needed")
    }

    companion object {

        fun of(value: CounterValue, createdAt: TimeTicket) = CrdtCounter(value, createdAt)

        fun CounterValue.sanitized(): Number = when (counterType()) {
            CounterType.IntegerCnt -> toInt()
            CounterType.LongCnt -> toLong()
            CounterType.DoubleCnt -> toDouble()
        }

        fun CounterValue.counterType() = when (this) {
            is Byte, is Short, is Int -> CounterType.IntegerCnt
            is Long -> CounterType.LongCnt
            else -> CounterType.DoubleCnt
        }
        fun ByteArray.asCounterValue(counterType: CounterType): Number =
            with(ByteBuffer.wrap(this)) {
                when (counterType) {
                    CounterType.IntegerCnt -> int
                    CounterType.LongCnt -> long
                    CounterType.DoubleCnt -> double
                }
            }
    }

    enum class CounterType {
        IntegerCnt, LongCnt, DoubleCnt
    }
}
