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
    var value = value
        private set(value) {
            field = value
            type = getCounterType(value)
        }

    var type = getCounterType(value)

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

    companion object {
        fun of(value: CounterValue, createdAt: TimeTicket) = CrdtCounter(value, createdAt)

        fun getCounterType(value: CounterValue) = when (value) {
            is Byte, is Short, is Int -> CounterType.IntegerCnt
            is Long -> CounterType.LongCnt
            else -> CounterType.DoubleCnt
        }

        fun valueFromBytes(counterType: CounterType, bytes: ByteArray): Number {
            return with(ByteBuffer.wrap(bytes)) {
                when (counterType) {
                    CounterType.IntegerCnt -> int
                    CounterType.LongCnt -> long
                    CounterType.DoubleCnt -> double
                }
            }
        }
    }

    enum class CounterType {
        IntegerCnt, LongCnt, DoubleCnt
    }
}
