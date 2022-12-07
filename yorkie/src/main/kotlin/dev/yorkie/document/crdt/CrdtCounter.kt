package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import java.nio.ByteBuffer

internal typealias CounterValue = Number

/**
 * [CrdtCounter] is a CRDT implementation of a counter. It is used to represent a number
 * that can be incremented or decremented.
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
            _value = value
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
        require(primitive.isNumericType && primitiveValue is Number && primitiveValue !is Double) {
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
            value: Int,
            createdAt: TimeTicket,
            _movedAt: TimeTicket? = null,
            _removedAt: TimeTicket? = null,
        ) = CrdtCounter(value, createdAt, _movedAt, _removedAt)

        operator fun invoke(
            value: Long,
            createdAt: TimeTicket,
            _movedAt: TimeTicket? = null,
            _removedAt: TimeTicket? = null,
        ) = CrdtCounter(value, createdAt, _movedAt, _removedAt)

        private fun CounterValue.counterType() = when (this) {
            is Int -> CounterType.IntegerCnt
            else -> CounterType.LongCnt
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
