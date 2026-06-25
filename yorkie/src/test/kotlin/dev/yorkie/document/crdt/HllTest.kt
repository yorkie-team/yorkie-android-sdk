package dev.yorkie.document.crdt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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
    fun `restore rejects mismatched payload length`() {
        // given
        val hll = Hll()

        // when - then
        assertThrows(IllegalArgumentException::class.java) {
            hll.restore(ByteArray(10))
        }
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
