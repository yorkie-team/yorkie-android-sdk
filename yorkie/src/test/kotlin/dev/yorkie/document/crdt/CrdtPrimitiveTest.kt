package dev.yorkie.document.crdt

import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.toPBTimeTicket
import dev.yorkie.api.v1.JSONElementKt.jSONObject
import dev.yorkie.api.v1.JSONElementKt.primitive
import dev.yorkie.api.v1.ValueType
import dev.yorkie.api.v1.jSONElement
import dev.yorkie.api.v1.rHTNode
import dev.yorkie.api.v1.snapshot
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector.Companion.INITIAL_VERSION_VECTOR
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrdtPrimitiveTest {
    @Test
    fun `Verify the value`() {
        val primitive = CrdtPrimitive("hello", TimeTicket.InitialTimeTicket)
        assertEquals("hello", primitive.value)
    }

    @Test
    fun `Verify that the value of Primitive and PrimitiveType are mapped correctly`() {
        var primitive = CrdtPrimitive(true, TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.Boolean == primitive.type)

        primitive = CrdtPrimitive(1, TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.Integer == primitive.type)

        primitive = CrdtPrimitive(1L, TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.Long == primitive.type)

        primitive = CrdtPrimitive(1.toDouble(), TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.Double == primitive.type)

        primitive = CrdtPrimitive("hello", TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.String == primitive.type)

        primitive = CrdtPrimitive(
            byteArrayOf(-0x01, -0x01, 0x02, -0x02),
            TimeTicket.InitialTimeTicket,
        )
        assertTrue(CrdtPrimitive.Type.Bytes == primitive.type)

        primitive = CrdtPrimitive(null, TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.Null == primitive.type)
    }

    @Test
    fun `should create new instance when deep copying Date and ByteArray`() {
        val datePrimitive = CrdtPrimitive(Date(1_000L), TimeTicket.InitialTimeTicket)
        val dateClone = datePrimitive.deepCopy() as CrdtPrimitive
        assertEquals(datePrimitive.value, dateClone.value)
        assertNotSame(datePrimitive.value, dateClone.value)

        val byteArrayPrimitive = CrdtPrimitive(
            ByteArray(4) { 1 },
            TimeTicket.InitialTimeTicket,
        )
        val byteArrayClone = byteArrayPrimitive.deepCopy() as CrdtPrimitive
        assertEquals(byteArrayPrimitive.value, byteArrayClone.value)
        assertNotSame(byteArrayPrimitive.value, byteArrayClone.value)
    }

    @Test
    fun `should return same instance when deep copying immutable values`() {
        val intPrimitive = CrdtPrimitive(1, TimeTicket.InitialTimeTicket)
        val intClone = intPrimitive.deepCopy() as CrdtPrimitive
        assertNotSame(intPrimitive, intClone)
        assertSame(intPrimitive.value, intClone.value)

        val stringPrimitive = CrdtPrimitive("str", TimeTicket.InitialTimeTicket)
        val stringClone = stringPrimitive.deepCopy() as CrdtPrimitive
        assertNotSame(stringPrimitive, stringClone)
        assertSame(stringPrimitive.value, stringClone.value)

        val boolPrimitive = CrdtPrimitive(false, TimeTicket.InitialTimeTicket)
        val boolClone = boolPrimitive.deepCopy() as CrdtPrimitive
        assertNotSame(boolPrimitive, boolClone)
        assertSame(boolPrimitive.value, boolClone.value)
    }

    @Test
    fun `should sanitize value to supported types`() {
        val bytePrimitive = CrdtPrimitive(14.toByte(), TimeTicket.InitialTimeTicket)
        assertEquals(14, bytePrimitive.value)

        val shortPrimitive = CrdtPrimitive(1.toShort(), TimeTicket.InitialTimeTicket)
        assertEquals(1, shortPrimitive.value)

        val numberPrimitive = CrdtPrimitive(111.111.toBigDecimal(), TimeTicket.InitialTimeTicket)
        assertEquals(111.111, numberPrimitive.value)

        val unsupported = CrdtPrimitive(Unit, TimeTicket.InitialTimeTicket)
        assertNull(unsupported.value)
        assertEquals(CrdtPrimitive.Type.Null, unsupported.type)
    }

    // T-INT64 (commit 3, e1e13671 / yorkie-js-sdk#1326): JS added a runtime
    // reject for a `number` outside the int64 range (`BigInt.asUintN(64)`
    // silently wraps past int64, e.g. 1e19) — not reachable on Android by
    // typing. Every public entry point is a typed overload (`Int`/`Long`/
    // `Double`), `setPrimitive(Any?)` is private, `sanitized()` maps other
    // `Number`s to `Double`, and a Kotlin `Long` cannot hold a value outside
    // int64 in the first place — no `BigInteger` path exists to add
    // `util/number.ts`'s `MaxInt64`/`MinInt64` counterparts to. This pins
    // the parity contract iOS also ported (PrimitiveTests): the int64
    // boundary classifies as Type.Long and round-trips losslessly through
    // toBytes()/fromBytes(). JS's two `assert.throws` cases are
    // unrepresentable here and are not emulated.
    @Test
    fun `accepts the int64 boundary losslessly`() {
        for (bound in listOf(Long.MAX_VALUE, Long.MIN_VALUE)) {
            val primitive = CrdtPrimitive(bound, TimeTicket.InitialTimeTicket)
            assertEquals(CrdtPrimitive.Type.Long, primitive.type)
            assertEquals(
                bound,
                CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Long, primitive.toBytes()),
            )
        }
    }

    // Port of yorkie-js-sdk primitive_test.ts (ac60f3d9, #1422; D8): a
    // fixed-width reader trusts a peer's payload length — JS reads a payload
    // shorter than the type's width as its zero-padded value instead of
    // throwing. Probe at b1fa0d39 confirmed Android throws
    // BufferUnderflowException/NoSuchElementException for every case here.
    @Test
    fun `short Integer Long and Date payloads decode zero-padded`() {
        // byte0=LSB .. zero-padded upper bytes: 1 + 2*256 = 513
        val shortTwoBytes = byteArrayOf(1, 2).toByteString()
        assertEquals(513, CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Integer, shortTwoBytes))

        // 1 + 2*256 + 3*65536 = 197_121
        val shortThreeBytes = byteArrayOf(1, 2, 3).toByteString()
        assertEquals(197_121L, CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Long, shortThreeBytes))
        assertEquals(
            Date(197_121L),
            CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Date, shortThreeBytes),
        )

        // 0 bytes zero-pads to 0 for every 64-bit/32-bit reader.
        val empty = ByteString.EMPTY
        assertEquals(0, CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Integer, empty))
        assertEquals(0L, CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Long, empty))
        assertEquals(Date(0L), CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Date, empty))
    }

    @Test
    fun `empty Boolean payload decodes false instead of throwing`() {
        assertEquals(false, CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Boolean, ByteString.EMPTY))
        // Non-empty still round-trips as before (sanity, not a probe case).
        assertEquals(
            true,
            CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Boolean, byteArrayOf(1).toByteString()),
        )
        assertEquals(
            false,
            CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Boolean, byteArrayOf(0).toByteString()),
        )
    }

    @Test
    fun `short Double payload decodes as the same value as its zero-padded 8-byte form`() {
        val fullEightBytes = ByteBuffer.allocate(Double.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(1.5)
            .array()
        val shortFourBytes = fullEightBytes.copyOf(4)
        val expected = ByteBuffer.wrap(fullEightBytes.copyOf(4).copyOf(8))
            .order(ByteOrder.LITTLE_ENDIAN)
            .double

        assertEquals(
            expected,
            CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Double, shortFourBytes.toByteString()),
        )
        // A 0-byte Double (probe case) also zero-pads rather than throwing.
        assertEquals(0.0, CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Double, ByteString.EMPTY))
    }

    @Test
    fun `exact-width payloads decode unchanged`() {
        val boolPrimitive = CrdtPrimitive(true, TimeTicket.InitialTimeTicket)
        assertEquals(
            true,
            CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Boolean, boolPrimitive.toBytes()),
        )

        val intPrimitive = CrdtPrimitive(42, TimeTicket.InitialTimeTicket)
        assertEquals(
            42,
            CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Integer, intPrimitive.toBytes()),
        )

        val longPrimitive = CrdtPrimitive(42L, TimeTicket.InitialTimeTicket)
        assertEquals(42L, CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Long, longPrimitive.toBytes()))

        val doublePrimitive = CrdtPrimitive(1.5, TimeTicket.InitialTimeTicket)
        assertEquals(
            1.5,
            CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Double, doublePrimitive.toBytes()),
        )

        val datePrimitive = CrdtPrimitive(Date(1_000L), TimeTicket.InitialTimeTicket)
        assertEquals(
            Date(1_000L),
            CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Date, datePrimitive.toBytes()),
        )
    }

    // Probe already found the Double case "already at parity" (Android read
    // the leading 8 bytes of a longer payload before this commit too); pin it
    // for every other fixed-width reader as well, matching JS's `subarray(0,
    // width)`/zero-pad-to-width policy on both sides.
    @Test
    fun `over-length payloads decode from only the leading width bytes`() {
        val trailingGarbage = byteArrayOf(9, 9, 9, 9)

        val fullInt = ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(42)
            .array()
        assertEquals(
            42,
            CrdtPrimitive.fromBytes(
                CrdtPrimitive.Type.Integer,
                (fullInt + trailingGarbage).toByteString(),
            ),
        )

        val fullLong = ByteBuffer.allocate(Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(42L)
            .array()
        assertEquals(
            42L,
            CrdtPrimitive.fromBytes(
                CrdtPrimitive.Type.Long,
                (fullLong + trailingGarbage).toByteString(),
            ),
        )

        val fullDouble = ByteBuffer.allocate(Double.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(1.5)
            .array()
        assertEquals(
            1.5,
            CrdtPrimitive.fromBytes(
                CrdtPrimitive.Type.Double,
                (fullDouble + trailingGarbage).toByteString(),
            ),
        )
    }

    // The #1422 headline ("read a double without writing to the buffer it
    // shares") does not reproduce on Android: `fromBytes` reads off
    // `ByteString.toByteArray()`, which is already a defensive copy (an
    // immutable ByteString has no writable backing array to share in the
    // first place) — RED not constructible, see the spec's D8/probe table.
    // Pin the guarantee instead: decoding never mutates the source, and
    // decoding the same ByteString twice agrees.
    @Test
    fun `fromBytes never mutates its input and two reads of the same ByteString agree`() {
        val sourceArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val sourceArrayBeforeDecode = sourceArray.copyOf()
        val bytes = sourceArray.toByteString()

        val firstRead = CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Double, bytes)
        val secondRead = CrdtPrimitive.fromBytes(CrdtPrimitive.Type.Double, bytes)

        assertEquals(firstRead, secondRead)
        // The array handed to toByteString() is untouched by either read.
        assertArrayEquals(sourceArrayBeforeDecode, sourceArray)
        // And the ByteString's own exposed bytes are unchanged too.
        assertArrayEquals(sourceArrayBeforeDecode, bytes.toByteArray())
    }

    // Decode-level pin (D8 / probe row "Document.applyChangePack(snapshot)
    // whose root holds a Double 4B throws... out of applyChangePack"): a
    // snapshot from a peer whose Double payload is short must decode through
    // the full applyChangePack path rather than wedging the client that
    // pulls it.
    @Test
    fun `applyChangePack with a snapshot holding a short Double no longer throws`() = runTest {
        val docKey = "crdt-primitive-decode-level-test"
        val document = Document(docKey)
        val shortDoubleBytes = byteArrayOf(1, 2, 3, 4)
        val rootElement = jSONElement {
            jsonObject = jSONObject {
                createdAt = TimeTicket.InitialTimeTicket.toPBTimeTicket()
                nodes.add(
                    rHTNode {
                        key = "d"
                        element = jSONElement {
                            primitive = primitive {
                                type = ValueType.VALUE_TYPE_DOUBLE
                                value = shortDoubleBytes.toByteString()
                                createdAt = TimeTicket.InitialTimeTicket.toPBTimeTicket()
                            }
                        }
                    },
                )
            }
        }
        val snapshotBytes = snapshot { root = rootElement }.toByteString()

        document.applyChangePack(
            ChangePack(
                docKey,
                CheckPoint(0, 0u),
                emptyList(),
                snapshotBytes,
                false,
                INITIAL_VERSION_VECTOR,
            ),
        )

        val expected = ByteBuffer.wrap(
            shortDoubleBytes.copyOf(8),
        ).order(ByteOrder.LITTLE_ENDIAN).double
        val decoded = document.getRootObject()["d"] as CrdtPrimitive
        assertEquals(expected, decoded.value)
    }
}
