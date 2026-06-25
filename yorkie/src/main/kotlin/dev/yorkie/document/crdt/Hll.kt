package dev.yorkie.document.crdt

/**
 * [Hll] is a HyperLogLog implementation used for approximate cardinality
 * estimation in [CrdtCounter] dedup mode. It uses xxhash64 hashing (matching
 * the Go server and JS SDK) and precision 14 (16384 registers, ~16KB, ~2% error).
 *
 * The structure is a CRDT: [merge] takes the bytewise maximum of two register
 * arrays, which is commutative, associative, and idempotent. This is why
 * concurrent dedup increments converge across replicas.
 *
 * Wire format: a fixed-size [HllRegisterCount]-byte array. Each byte stores
 * the run-length value `rho` for one register.
 */
internal class Hll {

    private val registers: ByteArray = ByteArray(HllRegisterCount)

    /**
     * Adds a value to the HLL. Returns true when the register was updated and
     * the cardinality estimate may have changed.
     */
    fun add(value: String): Boolean {
        val hash = xxhash64(value.toByteArray(Charsets.UTF_8))
        // Top [HllPrecision] bits select the register index.
        val idx = (hash ushr (64 - HllPrecision)).toInt()
        // Remaining bits shifted up, with sentinel bit set so leading-zero
        // counting always terminates within the bottom (64 - HllPrecision) bits.
        val remaining = (hash shl HllPrecision) or (1L shl (HllPrecision - 1))
        val rho = (countLeadingZeros64(remaining) + 1).toByte()
        return if (rho > registers[idx]) {
            registers[idx] = rho
            true
        } else {
            false
        }
    }

    /**
     * Returns the approximate cardinality estimate using the standard
     * HyperLogLog formula with small-range linear-counting correction.
     */
    fun count(): Long {
        val m = HllRegisterCount
        val alpha = 0.7213 / (1.0 + 1.079 / m)
        var sum = 0.0
        var zeros = 0
        for (i in 0 until m) {
            val r = registers[i].toInt() and 0xff
            sum += Math.pow(2.0, -r.toDouble())
            if (r == 0) {
                zeros++
            }
        }
        var estimate = (alpha * m.toDouble() * m.toDouble()) / sum
        if (estimate <= 2.5 * m && zeros > 0) {
            estimate = m.toDouble() * Math.log(m.toDouble() / zeros.toDouble())
        }
        return Math.round(estimate)
    }

    /**
     * Merges another [Hll] into this one by taking the bytewise max of each
     * register. Commutative, associative, and idempotent.
     */
    fun merge(other: Hll) {
        for (i in 0 until HllRegisterCount) {
            if (other.registers[i].toInt() and 0xff >
                registers[i].toInt() and 0xff
            ) {
                registers[i] = other.registers[i]
            }
        }
    }

    /**
     * Serializes the HLL registers to a new byte array.
     */
    fun toBytes(): ByteArray = registers.copyOf()

    /**
     * Restores the HLL registers from [data].
     *
     * @throws IllegalArgumentException when [data] length differs from [HllRegisterCount].
     */
    fun restore(data: ByteArray) {
        require(data.size == HllRegisterCount) {
            "invalid HLL register payload: got ${data.size} bytes, want $HllRegisterCount"
        }
        data.copyInto(registers)
    }

    /**
     * Returns a deep copy of this [Hll].
     */
    fun copy(): Hll {
        val clone = Hll()
        registers.copyInto(clone.registers)
        return clone
    }

    @Suppress("ktlint:standard:property-naming")
    companion object {
        /** HLL precision. Matches yorkie-js-sdk and Go server. */
        const val HllPrecision: Int = 14

        /** Number of registers = 2^[HllPrecision] = 16384. */
        const val HllRegisterCount: Int = 1 shl HllPrecision

        // xxhash64 constants (cespare/xxhash/v2 compatible).
        // Use unsigned literals then cast so the bit patterns match Go/JS exactly.
        private val Prime64x1: Long = 0x9e3779b185ebca87uL.toLong()
        private val Prime64x2: Long = 0xc2b2ae3d27d4eb4fuL.toLong()
        private val Prime64x3: Long = 0x165667b19e3779f9uL.toLong()
        private val Prime64x4: Long = 0x85ebca77c2b2ae63uL.toLong()
        private val Prime64x5: Long = 0x27d4eb2f165667c5uL.toLong()

        /**
         * Computes the 64-bit xxHash of [buf] with seed 0, matching Go's
         * cespare/xxhash/v2 and the JS SDK implementation. Used so HLL register
         * updates converge bit-for-bit across replicas.
         */
        @Suppress("INTEGER_OVERFLOW")
        internal fun xxhash64(buf: ByteArray): Long {
            val len = buf.size
            var h64: Long
            var offset = 0
            val seed = 0L

            if (len >= 32) {
                var v1 = seed + Prime64x1 + Prime64x2
                var v2 = seed + Prime64x2
                var v3 = seed
                var v4 = seed - Prime64x1

                while (offset <= len - 32) {
                    v1 = xxRound(v1, readU64LE(buf, offset))
                    offset += 8
                    v2 = xxRound(v2, readU64LE(buf, offset))
                    offset += 8
                    v3 = xxRound(v3, readU64LE(buf, offset))
                    offset += 8
                    v4 = xxRound(v4, readU64LE(buf, offset))
                    offset += 8
                }

                h64 = java.lang.Long.rotateLeft(v1, 1) +
                    java.lang.Long.rotateLeft(v2, 7) +
                    java.lang.Long.rotateLeft(v3, 12) +
                    java.lang.Long.rotateLeft(v4, 18)
                h64 = xxMergeRound(h64, v1)
                h64 = xxMergeRound(h64, v2)
                h64 = xxMergeRound(h64, v3)
                h64 = xxMergeRound(h64, v4)
            } else {
                h64 = seed + Prime64x5
            }

            h64 += len.toLong()

            while (offset + 8 <= len) {
                val k1 = xxRound(0L, readU64LE(buf, offset))
                h64 = java.lang.Long.rotateLeft(h64 xor k1, 27) * Prime64x1 + Prime64x4
                offset += 8
            }

            if (offset + 4 <= len) {
                val k = readU32LE(buf, offset) * Prime64x1
                h64 = h64 xor k
                h64 = java.lang.Long.rotateLeft(h64, 23) * Prime64x2 + Prime64x3
                offset += 4
            }

            while (offset < len) {
                h64 = h64 xor ((buf[offset].toLong() and 0xffL) * Prime64x5)
                h64 = java.lang.Long.rotateLeft(h64, 11) * Prime64x1
                offset++
            }

            h64 = h64 xor (h64 ushr 33)
            h64 *= Prime64x2
            h64 = h64 xor (h64 ushr 29)
            h64 *= Prime64x3
            h64 = h64 xor (h64 ushr 32)
            return h64
        }

        private fun xxRound(acc: Long, input: Long): Long {
            var a = acc + input * Prime64x2
            a = java.lang.Long.rotateLeft(a, 31)
            return a * Prime64x1
        }

        private fun xxMergeRound(acc: Long, value: Long): Long {
            val v = xxRound(0L, value)
            val merged = acc xor v
            return merged * Prime64x1 + Prime64x4
        }

        private fun readU64LE(buf: ByteArray, offset: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) {
                v = (v shl 8) or (buf[offset + i].toLong() and 0xffL)
            }
            return v
        }

        private fun readU32LE(buf: ByteArray, offset: Int): Long {
            return (buf[offset].toLong() and 0xffL) or
                ((buf[offset + 1].toLong() and 0xffL) shl 8) or
                ((buf[offset + 2].toLong() and 0xffL) shl 16) or
                ((buf[offset + 3].toLong() and 0xffL) shl 24)
        }

        /**
         * Counts the leading zero bits of [x] treated as a 64-bit unsigned value.
         * Matches `Math.clz64`-style semantics used by the JS implementation.
         */
        private fun countLeadingZeros64(x: Long): Int {
            return if (x == 0L) 64 else java.lang.Long.numberOfLeadingZeros(x)
        }
    }
}
