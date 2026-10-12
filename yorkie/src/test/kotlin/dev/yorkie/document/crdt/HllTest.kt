package dev.yorkie.document.crdt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HllTest {

    @Test
    fun `cardinality of empty sketch is zero`() {
        // given
        val hll = Hll()

        // when - then
        assertEquals(0L, hll.count())
    }

    @Test
    fun `cardinality of single item is one`() {
        // given
        val hll = Hll()

        // when
        hll.add("user-1")

        // then - small-range correction returns exact value for tiny cardinalities.
        assertEquals(1L, hll.count())
    }

    @Test
    fun `cardinality of N distinct items is approximately N`() {
        // given
        val hll = Hll()
        val n = 10_000

        // when
        for (i in 0 until n) {
            hll.add("user-$i")
        }

        // then - precision 14 yields ~2% expected error.
        val estimate = hll.count()
        val errorRatio = Math.abs(estimate - n).toDouble() / n
        assertTrue(
            "expected ~$n within 5%, got $estimate (error=$errorRatio)",
            errorRatio < 0.05,
        )
    }

    @Test
    fun `adding the same item twice leaves cardinality unchanged`() {
        // given
        val hll = Hll()
        hll.add("user-1")
        val before = hll.count()

        // when
        val updated = hll.add("user-1")

        // then
        assertFalse("duplicate add should not update register", updated)
        assertEquals(before, hll.count())
    }

    @Test
    fun `merging two sketches equals cardinality of the union`() {
        // given
        val a = Hll()
        val b = Hll()
        for (i in 0 until 5_000) {
            a.add("a-$i")
        }
        for (i in 0 until 5_000) {
            b.add("b-$i")
        }
        val union = Hll()
        for (i in 0 until 5_000) {
            union.add("a-$i")
            union.add("b-$i")
        }

        // when
        a.merge(b)

        // then - merged sketch should match independently-built union sketch.
        assertEquals(union.count(), a.count())
    }

    @Test
    fun `merge is commutative`() {
        // given
        val a = Hll()
        val b = Hll()
        for (i in 0 until 1_000) {
            a.add("a-$i")
            b.add("b-$i")
        }
        val ab = Hll().also {
            it.merge(a)
            it.merge(b)
        }
        val ba = Hll().also {
            it.merge(b)
            it.merge(a)
        }

        // when - then
        assertArrayEquals(ab.toBytes(), ba.toBytes())
    }

    @Test
    fun `merge is idempotent`() {
        // given
        val a = Hll()
        for (i in 0 until 1_000) {
            a.add("a-$i")
        }
        val before = a.toBytes()

        // when
        a.merge(a)

        // then
        assertArrayEquals(before, a.toBytes())
    }

    @Test
    fun `toBytes and restore round-trips`() {
        // given
        val original = Hll()
        for (i in 0 until 100) {
            original.add("token-$i")
        }

        // when
        val bytes = original.toBytes()
        val restored = Hll().apply { restore(bytes) }

        // then
        assertArrayEquals(bytes, restored.toBytes())
        assertEquals(original.count(), restored.count())
    }

    @Test
    fun `restore rejects mismatched payload length instead of throwing`() {
        // given - `restore` used to throw IllegalArgumentException on a wrong
        // length; ac60f3d9 (#1422; D8) widened the fix so a malformed payload
        // from a peer is refused rather than torn out of snapshot decode,
        // which has no handler.
        val hll = Hll()

        // when
        val accepted = hll.restore(ByteArray(10))

        // then
        assertFalse(accepted)
        assertArrayEquals(ByteArray(Hll.HllRegisterCount), hll.toBytes())
    }

    @Test
    fun `restore accepts a register at the maximum value 51 but rejects 52`() {
        // given - probe (ix): maxRegisterValue = 64 - HllPrecision + 1 = 51 at
        // precision 14; a register value no `add` could have written (52)
        // does not describe a cardinality (ac60f3d9, #1422; D8).
        val accepted = ByteArray(Hll.HllRegisterCount).also { it[0] = 51 }
        val rejected = ByteArray(Hll.HllRegisterCount).also { it[0] = 52 }
        val acceptedHll = Hll()
        val rejectedHll = Hll()

        // when
        val acceptedResult = acceptedHll.restore(accepted)
        val rejectedResult = rejectedHll.restore(rejected)

        // then
        assertTrue(acceptedResult)
        assertArrayEquals(accepted, acceptedHll.toBytes())
        assertFalse(rejectedResult)
        assertArrayEquals(ByteArray(Hll.HllRegisterCount), rejectedHll.toBytes())
    }

    @Test
    fun `restore rejects an all-0xFF payload and leaves registers untouched`() {
        // given - the probe's reproduction of the unfixed bug: a correct-length
        // payload of out-of-range registers (0xFF = 255, unsigned) used to be
        // accepted and `count()` computed the nonsensical estimate -1.
        val hll = Hll()
        val malformed = ByteArray(Hll.HllRegisterCount) { 0xFF.toByte() }

        // when
        val accepted = hll.restore(malformed)

        // then
        assertFalse(accepted)
        assertArrayEquals(ByteArray(Hll.HllRegisterCount), hll.toBytes())
        assertEquals(0L, hll.count())
    }

    @Test
    fun `restore rejections leave prior state untouched, a well-formed payload still applies`() {
        // given - a sketch already holding real state
        val hll = Hll()
        hll.add("user-1")
        hll.add("user-2")
        val before = hll.toBytes()

        // when - a malformed payload is rejected
        val rejectedWrongLength = hll.restore(ByteArray(10))
        val rejectedOutOfRange = hll.restore(ByteArray(Hll.HllRegisterCount) { 0xFF.toByte() })

        // then - state survives both rejections unchanged
        assertFalse(rejectedWrongLength)
        assertFalse(rejectedOutOfRange)
        assertArrayEquals(before, hll.toBytes())

        // when - a well-formed payload still applies afterward
        val wellFormed = Hll().apply { add("user-3") }.toBytes()
        val accepted = hll.restore(wellFormed)

        // then
        assertTrue(accepted)
        assertArrayEquals(wellFormed, hll.toBytes())
    }

    @Test
    fun `toBytes has fixed size matching HllRegisterCount`() {
        // given
        val hll = Hll()

        // when - then
        assertEquals(Hll.HllRegisterCount, hll.toBytes().size)
    }

    @Test
    fun `xxhash64 matches reference vectors`() {
        // given - test vectors verified against Go's cespare/xxhash/v2 with seed 0.
        // empty -> 0xef46db3751d8e999
        // "a"   -> 0xd24ec4f1a98c6e5b
        // "abc" -> 0x44bc2cf5ad770999
        // "Nobody inspects the spammish repetition" -> 0xfbcea83c8a378bf1

        // when - then
        assertEquals(
            0xef46db3751d8e999uL.toLong(),
            Hll.xxhash64(ByteArray(0)),
        )
        assertEquals(
            0xd24ec4f1a98c6e5buL.toLong(),
            Hll.xxhash64("a".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            0x44bc2cf5ad770999uL.toLong(),
            Hll.xxhash64("abc".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            0xfbcea83c8a378bf1uL.toLong(),
            Hll.xxhash64(
                "Nobody inspects the spammish repetition".toByteArray(Charsets.UTF_8),
            ),
        )
    }

    @Test
    fun `different inputs produce different bytes`() {
        // given
        val a = Hll().apply { add("alice") }
        val b = Hll().apply { add("bob") }

        // when - then
        assertNotEquals(a.toBytes().toList(), b.toBytes().toList())
    }
}
