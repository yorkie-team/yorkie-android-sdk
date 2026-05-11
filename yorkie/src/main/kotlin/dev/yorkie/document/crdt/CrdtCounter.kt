package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal typealias CounterValue = Number

/**
 * [CrdtCounter] is a CRDT implementation of a counter. It is used to represent a number
 * that can be incremented or decremented.
 *
 * When created with [CounterType.IntDedup], the counter uses an internal [Hll] sketch
 * to dedup increments by actor for approximate unique-visitor counting. The reported
 * [value] always equals the HLL cardinality estimate. Dedup increments must be unit
 * increments (+1) and require a non-empty actor token.
 */
@Suppress("DataClassPrivateConstructor")
internal data class CrdtCounter(
    var value: CounterValue,
    override var createdAt: TimeTicket,
    override var movedAt: TimeTicket? = null,
    override var removedAt: TimeTicket? = null,
    val type: CounterType = value.counterType(),
    private var hll: Hll? = if (type == CounterType.IntDedup) Hll() else null,
) : CrdtElement() {

    init {
        if (type == CounterType.IntDedup) {
            // Dedup counters track cardinality via HLL; raw value seed is ignored.
            value = 0
        }
    }

    /**
     * Returns true when this counter is in dedup mode and backed by an [Hll].
     */
    fun isDedup(): Boolean = type == CounterType.IntDedup

    fun toBytes(): ByteArray {
        return when (type) {
            CounterType.Int, CounterType.IntDedup ->
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

    /**
     * Returns the HLL register bytes, or null when not in dedup mode.
     */
    fun hllBytes(): ByteArray? = hll?.toBytes()

    /**
     * Restores the HLL state from [data] and recomputes [value] from the
     * cardinality estimate.
     */
    fun restoreHll(data: ByteArray) {
        val sketch = hll ?: Hll().also { hll = it }
        sketch.restore(data)
        recomputeValue()
    }

    fun increase(primitive: CrdtPrimitive) {
        require(!isDedup()) {
            "dedup counter requires actor, use increaseDedup()"
        }
        val primitiveValue = primitive.value
        require(primitive.isNumericType && primitiveValue is Number && primitiveValue !is Double) {
            "Unsupported type of value: ${primitive.type}"
        }
        value = when (type) {
            CounterType.Int -> value.toInt() + primitiveValue.toInt()
            CounterType.Long -> value.toLong() + primitiveValue.toLong()
            CounterType.IntDedup -> error("unreachable")
        }
    }

    /**
     * Adds [actor] to the HLL sketch when in dedup mode. Updates [value] to the
     * new cardinality estimate if the register was changed. Ignored when the
     * sketch already saw [actor].
     */
    fun increaseDedup(primitive: CrdtPrimitive, actor: String) {
        require(isDedup()) {
            "increaseDedup is only valid on dedup counter"
        }
        require(actor.isNotEmpty()) {
            "dedup counter requires actor"
        }
        val primitiveValue = primitive.value
        require(primitive.isNumericType && primitiveValue is Number && primitiveValue !is Double) {
            "Unsupported type of value: ${primitive.type}"
        }
        // Dedup mode only supports unit increments. JS rejects anything else.
        val unit = when (primitiveValue) {
            is Int -> primitiveValue == 1
            is Long -> primitiveValue == 1L
            else -> false
        }
        require(unit) {
            "dedup counter only supports increment by 1"
        }
        val sketch = hll ?: Hll().also { hll = it }
        if (sketch.add(actor)) {
            recomputeValue()
        }
    }

    private fun recomputeValue() {
        val sketch = hll ?: return
        // Cardinality estimate fits in Int for any realistic count, and JS stores it as a number.
        value = sketch.count().toInt()
    }

    override fun deepCopy(): CrdtElement {
        val cloned = copy()
        hll?.let { cloned.hll = it.copy() }
        return cloned
    }

    /**
     * `getDataSize` returns the data usage of this element.
     */
    override fun getDataSize(): DataSize {
        val data = when (type) {
            CounterType.Int -> 4
            CounterType.IntDedup -> 4 + (hll?.toBytes()?.size ?: 0)
            CounterType.Long -> 8
        }
        return DataSize(data = data, meta = getMetaUsage())
    }

    companion object {
        private fun CounterValue.counterType() = when (this) {
            is Int -> CounterType.Int
            else -> CounterType.Long
        }

        /**
         * Creates a new dedup-mode [CrdtCounter] backed by an internal [Hll] sketch.
         */
        fun createDedup(createdAt: TimeTicket): CrdtCounter {
            return CrdtCounter(value = 0, createdAt = createdAt, type = CounterType.IntDedup)
        }

        fun ByteArray.asCounterValue(counterType: CounterType): Number =
            with(ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)) {
                when (counterType) {
                    CounterType.Int, CounterType.IntDedup -> int
                    CounterType.Long -> long
                }
            }
    }

    enum class CounterType {
        Int,
        Long,
        IntDedup,
    }
}
