package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import java.nio.ByteBuffer

internal typealias CounterValue = Number

/**
 * [CrdtCounter] represents changeable number data type.
 */
@Suppress("DataClassPrivateConstructor")
internal data class CrdtCounter private constructor(
    private var _value: CounterValue,
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
) : CrdtElement() {
    var value: CounterValue
        get() = _value
        private set(value) {
            _value = value.sanitized()
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

    fun increase(primitive: CrdtPrimitive) {
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
        return "$value"
    }

    override fun deepCopy(): CrdtElement {
        return CrdtCounter(value, createdAt, _movedAt, _removedAt)
    }

    companion object {

        operator fun invoke(
            value: CounterValue,
            createdAt: TimeTicket,
            _movedAt: TimeTicket? = null,
            _removedAt: TimeTicket? = null,
        ) = CrdtCounter(value.sanitized(), createdAt, _movedAt, _removedAt)

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
