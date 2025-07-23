package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
            CounterType.Int ->
                ByteBuffer
                    .allocate(Int.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(value.toInt())

            CounterType.Long ->
                ByteBuffer
                    .allocate(Long.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(value.toLong())
        }.array()
    }

    fun increase(primitive: CrdtPrimitive) {
        val primitiveValue = primitive.value
        require(primitive.isNumericType && primitiveValue is Number && primitiveValue !is Double) {
            "Unsupported type of value: ${primitive.type}"
        }
        value = when (type) {
            CounterType.Int -> value.toInt() + primitiveValue.toInt()
            CounterType.Long -> value.toLong() + primitiveValue.toLong()
        }
    }

    override fun deepCopy(): CrdtElement {
        return copy()
    }

    /**
     * `getDataSize` returns the data usage of this element.
     */
    override fun getDataSize(): DataSize = DataSize(
        data = if (type == CounterType.Int) {
            4
        } else {
            8
        },
        meta = getMetaUsage(),
    )

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
            is Int -> CounterType.Int
            else -> CounterType.Long
        }

        fun ByteArray.asCounterValue(counterType: CounterType): Number =
            with(ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)) {
                when (counterType) {
                    CounterType.Int -> int
                    CounterType.Long -> long
                }
            }
    }

    enum class CounterType {
        Int,
        Long,
    }
}
