package dev.yorkie.api

import android.util.Base64
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.time.VersionVector
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `snapshot_converter_test.ts` (yorkie-js-sdk#1338 /
 * `2291bf67`, RTCOLLABPLATFORM-771). Pins [snapshotToBytes] as the exact
 * inverse of [ByteString?.toSnapshot] and the [ChangeID] binary helpers.
 */
class SnapshotConverterTest {

    // Encoding a ChangeID with a non-empty VersionVector routes through
    // VersionVectorConverter.kt -> android.util.Base64, which is an unmocked
    // stub under plain JVM unit tests. Bridge to java.util.Base64 for every
    // case; harmless for the snapshot-only cases which never touch it.
    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    @Test
    fun `should round-trip root and presences`() = runTest {
        val document = Document("")
        document.updateAsync { root, presence ->
            root.setNewObject("obj").apply {
                this["nested"] = true
                this["count"] = 42
            }
            root.setNewArray("arr").apply {
                put(1)
                put(2)
                put("three")
            }
            root.setNewText("text").edit(0, 0, "hello")
            root.setNewCounter("counter", 5)
            presence.put(mapOf("cursor" to "7", "name" to "alice"))
        }.await()

        val bytes = snapshotToBytes(document.getRootObject(), document.allPresences.value)
        val (root, presences) = bytes.toSnapshot()

        assertEquals(document.toJson(), root.toJson())
        assertEquals(document.allPresences.value.toMap(), presences)
    }

    @Test
    fun `should round-trip an empty document`() = runTest {
        val document = Document("")

        val bytes = snapshotToBytes(document.getRootObject(), document.allPresences.value)
        val (root, presences) = bytes.toSnapshot()

        assertEquals(document.toJson(), root.toJson())
        assertEquals(emptyMap(), presences)
    }

    @Test
    fun `should round-trip a ChangeID through binary`() {
        val changeID = ChangeID(
            clientSeq = 3u,
            lamport = 5,
            actor = "000000000000000000000001",
            versionVector = VersionVector(mapOf("000000000000000000000001" to 5L)),
        )

        val restored = changeID.toByteString().toChangeID()

        assertEquals(changeID.lamport, restored.lamport)
        assertEquals(changeID.actor, restored.actor)
        assertEquals(changeID.versionVector, restored.versionVector)
    }
}
