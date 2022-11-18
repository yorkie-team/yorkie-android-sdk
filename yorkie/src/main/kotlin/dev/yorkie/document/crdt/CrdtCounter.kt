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

    val type = _value.counterType()

    fun toBytes(): ByteArray {
        return when (type) {
            CounterType.IntegerCnt -> ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.toInt())
            CounterType.LongCnt -> ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value.toLong())
        }.array()
    }

    fun increase(primitive: CrdtPrimitive) {
        val primitiveValue = primitive.value
        require(primitive.isNumericType && primitiveValue is Number) {
            "Unsupported type of value: ${primitive.type}"
        }
        value = when (type) {
            CounterType.IntegerCnt -> value.toInt() + primitiveValue.toInt()
            CounterType.LongCnt -> value.toLong() + primitiveValue.toLong()
        }
    }

    override fun deepCopy(): CrdtElement {
        return copy()
    }

    companion object {

        operator fun invoke(
            value: CounterValue,
            createdAt: TimeTicket,
            _movedAt: TimeTicket? = null,
            _removedAt: TimeTicket? = null,
        ) = CrdtCounter(value.sanitized(), createdAt, _movedAt, _removedAt)

        private fun CounterValue.sanitized(): Number = when (counterType()) {
            CounterType.IntegerCnt -> toInt()
            CounterType.LongCnt -> toLong()
        }

        private fun CounterValue.counterType() = when (this) {
            is Int -> CounterType.IntegerCnt
            is Long -> CounterType.LongCnt
            else -> error("Counter supports only Int and Long")
        }

        fun ByteArray.asCounterValue(counterType: CounterType): Number =
            with(ByteBuffer.wrap(this)) {
                when (counterType) {
                    CounterType.IntegerCnt -> int
                    CounterType.LongCnt -> long
                }
            }
    }

    enum class CounterType {
        IntegerCnt, LongCnt
    }
}
