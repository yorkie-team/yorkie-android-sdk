package dev.yorkie.document

import android.util.Base64
import com.google.protobuf.InvalidProtocolBufferException
import dev.yorkie.api.PBChange
import dev.yorkie.api.toPBChange
import dev.yorkie.api.toStoredChange
import dev.yorkie.api.toStoredChangeBytes
import dev.yorkie.api.v1.JSONElement
import dev.yorkie.api.v1.Snapshot
import dev.yorkie.api.v1.ValueType
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.json.JsonCounter
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.time.VersionVector.Companion.INITIAL_VERSION_VECTOR
import dev.yorkie.helper.crossSync
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import dev.yorkie.util.YorkieException.Code.ErrUnimplemented
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `document_bytes_test.ts` (yorkie-js-sdk#1338 /
 * `2291bf67`, RTCOLLABPLATFORM-771), plus the restore-guard/reset/projection
 * cases the JS side only covers through its client suites. Pins the
 * [Document.toBytes] / [Document.Companion.fromBytes] / [Document
 * .restoreFromBytes] / [Document.resetForReanchor] / [Document
 * .pendingChanges] contract.
 */
class DocumentBytesTest {

    private val key = "document-bytes-test-key"
    private val actorA = "000000000000000000000001"
    private val actorB = "000000000000000000000002"

    // Encoding a Change/ChangeID with a non-empty VersionVector routes
    // through VersionVectorConverter.kt -> android.util.Base64, which is an
    // unmocked stub under plain JVM unit tests. Bridge to java.util.Base64
    // for every case; harmless for cases whose version vector stays empty.
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

    private suspend fun Document.buildSample() {
        setActor(actorA)
        updateAsync { root, presence ->
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
    }

    @Test
    fun `T1 should round-trip a document with root, presence, and pending changes`() = runTest {
        val document = Document(key)
        document.buildSample()
        assertTrue(document.hasLocalChanges())

        val bytes = document.toBytes()
        val restored = Document.fromBytes(key, bytes)

        assertEquals(document.toJson(), restored.toJson())
        assertEquals(document.allPresences.value[actorA], restored.allPresences.value[actorA])
        assertEquals(document.checkPoint, restored.checkPoint)
        assertEquals(document.changeID.lamport, restored.changeID.lamport)
        assertEquals(document.changeID.actor, restored.changeID.actor)
        assertEquals(document.changeID.versionVector, restored.changeID.versionVector)
        assertEquals(
            document.createChangePack().changes.map { it.toPBChange() },
            restored.createChangePack().changes.map { it.toPBChange() },
        )
    }

    @Test
    fun `T2 should round-trip an empty document`() = runTest {
        val document = Document(key)
        assertFalse(document.hasLocalChanges())

        val bytes = document.toBytes()
        val restored = Document.fromBytes(key, bytes)

        assertEquals(document.toJson(), restored.toJson())
        assertEquals(document.checkPoint, restored.checkPoint)
        assertEquals(document.changeID, restored.changeID)
        assertFalse(restored.hasLocalChanges())
    }

    @Test
    fun `T3 should preserve pending changes across restore`() = runTest {
        val document = Document(key)
        document.setActor(actorB)
        document.updateAsync { root, _ ->
            root.setNewCounter("counter", 0)
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonCounter>("counter").increase(3)
        }.await()

        val bytes = document.toBytes()
        val restored = Document.fromBytes(key, bytes)

        assertEquals(
            document.createChangePack().changes.map { it.toPBChange() },
            restored.createChangePack().changes.map { it.toPBChange() },
        )
        assertEquals(
            document.createChangePack().changes.size,
            restored.createChangePack().changes.size,
        )
    }

    @Test
    fun `T4 should round-trip a version vector with multiple actor entries`() = runTest {
        val d1 = Document(key)
        d1.setActor(actorA)
        d1.updateAsync { root, _ -> root["k1"] = 1 }.await()

        val d2 = Document(key)
        d2.setActor(actorB)
        d2.updateAsync { root, _ -> root["k2"] = 2 }.await()

        crossSync(d1, d2)

        assertTrue(d1.changeID.versionVector.vectorMap.size >= 2)

        val bytes = d1.toBytes()
        val restored = Document.fromBytes(key, bytes)

        assertEquals(d1.toJson(), restored.toJson())
        assertEquals(d1.changeID, restored.changeID)
    }

    @Test
    fun `T5 should round-trip the compaction epoch`() = runTest {
        val document = Document(key)
        document.setActor(actorA)
        document.updateAsync { root, _ -> root["k1"] = 1 }.await()

        document.applyChangePack(
            ChangePack(
                key,
                CheckPoint(0, 1u),
                emptyList(),
                null,
                false,
                INITIAL_VERSION_VECTOR,
                epoch = 7,
            ),
        )
        assertEquals(7L, document.epoch)

        val bytes = document.toBytes()
        val restored = Document.fromBytes(key, bytes)

        assertEquals(7L, restored.epoch)
    }

    @Test
    fun `T6 should present the document epoch on createChangePack`() = runTest {
        val document = Document(key)
        document.applyChangePack(
            ChangePack(
                key,
                CheckPoint.InitialCheckPoint,
                emptyList(),
                null,
                false,
                INITIAL_VERSION_VECTOR,
                epoch = 11,
            ),
        )

        assertEquals(11L, document.createChangePack().epoch)
    }

    @Test
    fun `T7 should default the epoch to 0 for a legacy four-blob envelope`() = runTest {
        val document = Document(key)
        document.buildSample()

        val full = document.toBytes()
        val legacy = full.truncateToBlobCount(4)

        val restored = Document.fromBytes(key, legacy)

        assertEquals(0L, restored.epoch)
        assertEquals(document.toJson(), restored.toJson())
    }

    @Test
    fun `T8 should reject a corrupt envelope`() {
        val corrupted = runBlocking {
            val document = Document(key)
            document.buildSample()
            val bytes = document.toBytes()
            bytes.copyOfRange(0, bytes.size - 3)
        }

        val exception = assertThrows(YorkieException::class.java) {
            runBlocking { Document.fromBytes(key, corrupted) }
        }
        assertEquals(ErrInvalidArgument, exception.code)
    }

    @Test
    fun `T8b should reject an envelope whose length prefix has the high bit set`() {
        // A hand-built raw envelope needs no ChangeID/Base64 encoding: only
        // the first 4-byte length prefix is read before the bounds check
        // fires, so the bytes after it are never inspected.
        listOf(0xFFFFFFFFL, 0x80000000L).forEach { prefix ->
            val corrupted = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(prefix.toInt()).array()

            val exception = assertThrows(YorkieException::class.java) {
                runBlocking { Document.fromBytes(key, corrupted) }
            }
            assertEquals(ErrInvalidArgument, exception.code)
            assertTrue(exception.errorMessage.contains("blob length exceeds remaining bytes"))
        }
    }

    @Test
    fun `T9 should decode a five-blob envelope with an empty docId`() = runTest {
        val document = Document(key)
        document.buildSample()

        val full = document.toBytes()
        val fiveBlobs = full.truncateToBlobCount(5)

        val restored = Document.fromBytes(key, fiveBlobs)

        assertEquals("", restored.docId)
        assertEquals(document.toJson(), restored.toJson())
    }

    @Test
    fun `T10 should ignore an unknown seventh trailing blob`() = runTest {
        val document = Document(key)
        document.buildSample()

        val withExtra = document.toBytes().appendBlob("extra".toByteArray(Charsets.UTF_8))

        val restored = Document.fromBytes(key, withExtra)

        assertEquals(document.toJson(), restored.toJson())
    }

    @Test
    fun `T11 should reject an envelope with fewer than four blobs`() {
        val threeBlobs = runBlocking {
            val document = Document(key)
            document.buildSample()
            document.toBytes().truncateToBlobCount(3)
        }

        val exception = assertThrows(YorkieException::class.java) {
            runBlocking { Document.fromBytes(key, threeBlobs) }
        }
        assertEquals(ErrInvalidArgument, exception.code)
        assertTrue(exception.errorMessage.contains("expected at least 4 blobs"))
    }

    @Test
    fun `T12 restoreFromBytes rejects a mismatched actor and returns envelope pending changes`() {
        val document = Document(key)
        lateinit var jsonBefore: String
        lateinit var checkPointBefore: CheckPoint
        lateinit var pendingBefore: List<PBChange>
        lateinit var envelopePending: List<PBChange>
        lateinit var bytes: ByteArray

        runBlocking {
            val persisted = Document(key)
            persisted.setActor(actorB)
            persisted.updateAsync { root, _ -> root["k1"] = 1 }.await()
            bytes = persisted.toBytes()
            envelopePending = persisted.pendingChanges().map { it.toPBChange() }

            document.setActor(actorA)
            document.updateAsync { root, _ -> root["k2"] = 2 }.await()

            jsonBefore = document.toJson()
            checkPointBefore = document.checkPoint
            pendingBefore = document.pendingChanges().map { it.toPBChange() }
        }

        // restoreFromBytes classifies a mismatched (non-initial, distinct) actor via
        // RestoreResult instead of throwing (spec 029 I5/M1) — both actors here are real,
        // non-initial actors, so this holds before and after D1's parity change too.
        val result = runBlocking { document.restoreFromBytes(bytes) }

        assertTrue(result is RestoreResult.ActorMismatch)
        assertEquals(envelopePending, result.pending.map { it.toPBChange() })

        assertEquals(jsonBefore, document.toJson())
        assertEquals(checkPointBefore, document.checkPoint)
        assertEquals(
            pendingBefore,
            runBlocking { document.pendingChanges() }.map { it.toPBChange() },
        )
    }

    @Test
    fun `T13 restoreFromBytes restores a matching actor and clears history`() = runTest {
        val persisted = Document(key)
        persisted.setActor(actorA)
        persisted.updateAsync { root, _ -> root["k1"] = 1 }.await()
        val bytes = persisted.toBytes()

        val document = Document(key)
        document.setActor(actorA)
        document.updateAsync { root, _ -> root["k2"] = 2 }.await()

        document.restoreFromBytes(bytes)

        assertEquals(persisted.toJson(), document.toJson())
        assertEquals(persisted.checkPoint, document.checkPoint)
        assertFalse(document.history.canUndo())
    }

    @Test
    fun `T14 resetForReanchor returns the document to its initial state and clears history`() =
        runTest {
            val document = Document(key)
            document.buildSample()
            document.applyChangePack(
                ChangePack(
                    key,
                    CheckPoint.InitialCheckPoint,
                    emptyList(),
                    null,
                    false,
                    INITIAL_VERSION_VECTOR,
                    epoch = 5,
                ),
            )
            document.setDocId("some-doc-id")

            document.resetForReanchor()

            val fresh = Document(key)
            assertEquals(ChangeID.InitialChangeID, document.changeID)
            assertEquals(CheckPoint.InitialCheckPoint, document.checkPoint)
            assertFalse(document.hasLocalChanges())
            assertEquals(0L, document.epoch)
            assertEquals("", document.docId)
            assertEquals(fresh.toJson(), document.toJson())
            assertTrue(document.allPresences.value.isEmpty())
            assertFalse(document.history.canUndo())
        }

    @Test
    fun `T15 toDroppedChange projects id, message, operation count, and presence flag`() = runTest {
        val document = Document(key)
        document.setActor(actorA)
        document.updateAsync(message = "add k1") { root, presence ->
            root["k1"] = 1
            presence.put(mapOf("cursor" to "1"))
        }.await()

        val change = document.pendingChanges().single()
        val dropped = change.toDroppedChange()

        assertEquals(change.id, dropped.id)
        assertEquals(change.message, dropped.message)
        assertEquals(change.operations.size, dropped.operationCount)
        assertEquals(change.hasPresenceChange, dropped.hasPresenceChange)
    }

    @Test
    fun `T16 fromBytes rejects a checkpoint blob whose numeric fields overflow`() {
        // given: a checkpoint blob whose serverSeq is a syntactically valid
        // 26-digit number that overflows Long.
        val overflowServerSeq = runBlocking {
            val document = Document(key)
            document.buildSample()
            document.toBytes().replaceBlob(
                1,
                """{"serverSeq":"99999999999999999999999999","clientSeq":1}"""
                    .toByteArray(Charsets.UTF_8),
            )
        }

        // when / then
        val serverSeqException = assertThrows(YorkieException::class.java) {
            runBlocking { Document.fromBytes(key, overflowServerSeq) }
        }
        assertEquals(ErrInvalidArgument, serverSeqException.code)
        assertTrue(
            serverSeqException.errorMessage.contains("corrupt envelope: invalid checkpoint blob"),
        )

        // given: a checkpoint blob whose clientSeq overflows UInt.MAX_VALUE.
        val overflowClientSeq = runBlocking {
            val document = Document(key)
            document.buildSample()
            document.toBytes().replaceBlob(
                1,
                """{"serverSeq":"5","clientSeq":4294967296}""".toByteArray(Charsets.UTF_8),
            )
        }

        // when / then
        val clientSeqException = assertThrows(YorkieException::class.java) {
            runBlocking { Document.fromBytes(key, overflowClientSeq) }
        }
        assertEquals(ErrInvalidArgument, clientSeqException.code)
        assertTrue(
            clientSeqException.errorMessage.contains("corrupt envelope: invalid checkpoint blob"),
        )
    }

    @Test
    fun `T29 fromBytes rejects an epoch blob that is not numeric`() {
        // given: a well-formed envelope whose fifth (epoch) blob is text.
        val corrupted = runBlocking {
            val document = Document(key)
            document.buildSample()
            document.toBytes().replaceBlob(4, "not-a-number".toByteArray(Charsets.UTF_8))
        }

        // when / then
        val exception = assertThrows(YorkieException::class.java) {
            runBlocking { Document.fromBytes(key, corrupted) }
        }
        assertEquals(ErrInvalidArgument, exception.code)
        assertTrue(exception.errorMessage.contains("corrupt envelope: invalid epoch blob"))
    }

    @Test
    fun `T-E1 fromBytes rejects an epoch blob that is not plain ASCII digits`() {
        // Kotlin's String.toLong() accepts non-ASCII decimal digits and a leading '+';
        // JS `BigInt(text)` rejects both, and the writer only ever emits `epoch.toString()`.
        listOf("٣", "+7", "7 ", " 7").forEach { malformed ->
            val corrupted = runBlocking {
                val document = Document(key)
                document.buildSample()
                document.toBytes().replaceBlob(4, malformed.toByteArray(Charsets.UTF_8))
            }

            val exception = assertThrows(YorkieException::class.java) {
                runBlocking { Document.fromBytes(key, corrupted) }
            }
            assertEquals(ErrInvalidArgument, exception.code)
            assertTrue(
                exception.errorMessage.contains("corrupt envelope: invalid epoch blob"),
                "expected an epoch rejection for \"$malformed\", got: ${exception.errorMessage}",
            )
        }
    }

    // --- envelope error contract (AC3, I5/M1, spec 029) -----------------------
    // RED at a7579fe6: revert fromBytes's try/catch wrap around the withContext(doc.dispatcher)
    // decode (the production hunk this group pins) — each case below then escapes as its own raw
    // exception (ErrUnimplemented, ClassCastException, InvalidProtocolBufferException) instead of
    // the uniform YorkieException(ErrInvalidArgument) with the original attached as the cause.

    @Test
    fun `T-B1 fromBytes wraps an empty root snapshot as ErrInvalidArgument with cause attached`() {
        // given: a snapshot blob that decodes to a valid but root-less Snapshot proto — the
        // root JSONElement has none of its oneof fields set, so toCrdtElement() throws
        // ErrUnimplemented rather than a parse error.
        val corrupted = runBlocking {
            val document = Document(key)
            document.buildSample()
            document.toBytes().replaceBlob(0, ByteArray(0))
        }

        val exception = assertThrows(YorkieException::class.java) {
            runBlocking { Document.fromBytes(key, corrupted) }
        }
        assertEquals(ErrInvalidArgument, exception.code)
        assertTrue(exception.errorMessage.contains("corrupt envelope"))
        val cause = exception.cause
        assertNotNull(cause)
        assertTrue(cause is YorkieException)
        assertEquals(ErrUnimplemented, cause.code)
    }

    @Test
    fun `T-B2 fromBytes wraps a non-object root as ErrInvalidArgument with cause attached`() {
        // given: a root JSONElement whose oneof is a Primitive(null), not a JSONObject — decodes
        // fine via toCrdtElement(), but ElementConverter's `as CrdtObject` cast then fails.
        val nonObjectRoot = JSONElement.newBuilder()
            .setPrimitive(
                JSONElement.Primitive.newBuilder()
                    .setType(ValueType.VALUE_TYPE_NULL)
                    .build(),
            )
            .build()
        val snapshotBlob = Snapshot.newBuilder().setRoot(nonObjectRoot).build().toByteArray()
        val corrupted = runBlocking {
            val document = Document(key)
            document.buildSample()
            document.toBytes().replaceBlob(0, snapshotBlob)
        }

        val exception = assertThrows(YorkieException::class.java) {
            runBlocking { Document.fromBytes(key, corrupted) }
        }
        assertEquals(ErrInvalidArgument, exception.code)
        assertTrue(exception.errorMessage.contains("corrupt envelope"))
        assertNotNull(exception.cause)
        assertTrue(exception.cause is ClassCastException)
    }

    @Test
    fun `T-B3 fromBytes wraps garbage protobuf bytes as ErrInvalidArgument with cause attached`() {
        // given: 11 continuation-bit-set bytes — guaranteed malformed varint (protobuf varints
        // cap at 10 bytes for a 64-bit value), so the parser itself throws.
        val garbage = ByteArray(11) { 0xFF.toByte() }
        val corrupted = runBlocking {
            val document = Document(key)
            document.buildSample()
            document.toBytes().replaceBlob(0, garbage)
        }

        val exception = assertThrows(YorkieException::class.java) {
            runBlocking { Document.fromBytes(key, corrupted) }
        }
        assertEquals(ErrInvalidArgument, exception.code)
        assertTrue(exception.errorMessage.contains("corrupt envelope"))
        assertNotNull(exception.cause)
        assertTrue(exception.cause is InvalidProtocolBufferException)
    }

    // --- strict checkpoint parser (AC4, M6, spec 029) -------------------------
    // RED at a7579fe6: revert CheckpointRegex to the two old unanchored regexes — both
    // malformed blobs below would then be silently accepted (find() matches anywhere).

    @Test
    fun `T-C1 fromBytes rejects a checkpoint blob with unanchored garbage`() {
        listOf(
            """{"serverSeq":"1","clientSeq":12abc}""",
            """xx{"serverSeq":"1","clientSeq":2}yy""",
            // A trailing Unicode line terminator: Java's `$` also matches before one final
            // U+0085, U+2028 or U+2029, none of which `\s` covers, so only matchEntire
            // rejects these (JSON.parse rejects them too; a plain trailing "\n" is `\s`
            // and stays accepted, as JSON.parse accepts it).
            """{"serverSeq":"1","clientSeq":2}""" + "\u0085",
            """{"serverSeq":"1","clientSeq":2}""" + " ",
            """{"serverSeq":"1","clientSeq":2}""" + " ",
        ).forEach { malformed ->
            val corrupted = runBlocking {
                val document = Document(key)
                document.buildSample()
                document.toBytes().replaceBlob(1, malformed.toByteArray(Charsets.UTF_8))
            }

            val exception = assertThrows(YorkieException::class.java) {
                runBlocking { Document.fromBytes(key, corrupted) }
            }
            assertEquals(ErrInvalidArgument, exception.code)
            assertTrue(
                exception.errorMessage.contains("corrupt envelope: invalid checkpoint blob"),
                "expected a checkpoint rejection for \"$malformed\", " +
                    "got: ${exception.errorMessage}",
            )
        }

        // the canonical (anchored, no surrounding noise) blob a real toBytes() writes is
        // still accepted and round-trips the same (un-synced, still initial) checkpoint.
        val restoredCheckPoint = runBlocking {
            val document = Document(key)
            document.setActor(actorA)
            document.updateAsync { root, _ -> root["k1"] = 1 }.await()
            Document.fromBytes(key, document.toBytes()).checkPoint
        }
        assertEquals(CheckPoint.InitialCheckPoint, restoredCheckPoint)
    }

    // --- incremental restore: the offline-persistence log (aaa5cb15/#1354, RTCOLLABPLATFORM-779) --

    @Test
    fun `T17 restoreAppendedChanges replays a log appended after the snapshot`() = runTest {
        val document = Document(key)
        document.setActor(actorA)
        document.updateAsync { root, _ -> root.setNewText("text").edit(0, 0, "hello") }.await()
        document.updateAsync { root, _ -> root.setNewCounter("counter", 5) }.await()
        val snapshot = document.toBytes()
        val snapshotClientSeq = document.changeID.clientSeq

        document.updateAsync { root, _ -> root.getAs<JsonCounter>("counter").increase(1) }.await()
        document.updateAsync { root, _ -> root.getAs<JsonCounter>("counter").increase(2) }.await()
        document.updateAsync { root, _ -> root["flag"] = true }.await()
        val appended = document.pendingChangesAfter(snapshotClientSeq)
        assertEquals(3, appended.size)

        val restored = Document.fromBytes(key, snapshot)
        restored.setActor(actorA)
        val localChangeEvents = async(start = CoroutineStart.UNDISPATCHED) {
            restored.events.filterIsInstance<Document.Event.LocalChange>().take(3).toList()
        }

        restored.restoreAppendedChanges(appended)

        assertEquals(document.toJson(), restored.toJson())
        assertEquals(
            document.pendingChanges().map { it.id.clientSeq },
            restored.pendingChanges().map { it.id.clientSeq },
        )
        assertEquals(document.changeID, restored.changeID)
        assertEquals(3, localChangeEvents.await().size)
    }

    @Test
    fun `T18 restoreAppendedChanges with an acked prefix queues only the unacked tail`() = runTest {
        val document = Document(key)
        document.setActor(actorA)
        document.updateAsync { root, _ -> root["k0"] = 0 }.await()
        val snapshot = document.toBytes()
        val snapshotClientSeq = document.changeID.clientSeq

        document.updateAsync { root, _ -> root["k1"] = 1 }.await()
        document.updateAsync { root, _ -> root["k2"] = 2 }.await()
        document.updateAsync { root, _ -> root["k3"] = 3 }.await()
        val appended = document.pendingChangesAfter(snapshotClientSeq)

        val restored = Document.fromBytes(key, snapshot)
        restored.setActor(actorA)
        // The envelope already carries k0 as an unsynced pending change (toBytes bundles it).
        val envelopeCarriedClientSeq = restored.pendingChanges().single().id.clientSeq

        restored.restoreAppendedChanges(appended, ackedClientSeq = appended[0].id.clientSeq)

        assertEquals(document.toJson(), restored.toJson())
        val queuedSeqs = restored.pendingChanges().map { it.id.clientSeq }
        assertFalse(queuedSeqs.contains(appended[0].id.clientSeq))
        assertTrue(queuedSeqs.containsAll(appended.drop(1).map { it.id.clientSeq }))
        assertTrue(queuedSeqs.contains(envelopeCarriedClientSeq))
    }

    @Test
    fun `T19 the next local edit stays pushable after a replay`() = runTest {
        val document = Document(key)
        document.setActor(actorA)
        val snapshot = document.toBytes()

        document.updateAsync { root, _ -> root["k1"] = 1 }.await()
        document.updateAsync { root, _ -> root["k2"] = 2 }.await()
        val appended = document.pendingChangesAfter(0u)

        val restored = Document.fromBytes(key, snapshot)
        restored.setActor(actorA)
        restored.restoreAppendedChanges(appended)
        restored.updateAsync { root, _ -> root["k3"] = 3 }.await()

        val seqs = restored.createChangePack().changes.map { it.id.clientSeq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.distinct(), seqs)
        assertEquals(listOf(1u, 2u, 3u), seqs)
    }

    @Test
    fun `T20 fromBytes does not re-apply the pending change the envelope already carries`() =
        runTest {
            val document = Document(key)
            document.setActor(actorA)
            document.updateAsync { root, _ -> root.setNewCounter("counter", 0) }.await()
            document.updateAsync { root, _ -> root.getAs<JsonCounter>("counter").increase(7) }
                .await()

            val bytes = document.toBytes()
            val restored = Document.fromBytes(key, bytes)

            assertEquals(document.toJson(), restored.toJson())
            val restoredCounterValue =
                restored.getRoot().getAs<JsonCounter>("counter").value.toInt()
            assertEquals(7, restoredCounterValue)
            assertEquals(
                document.pendingChanges().map { it.id.clientSeq },
                restored.pendingChanges().map { it.id.clientSeq },
            )
        }

    @Test
    fun `T21 restoreAppendedChanges rejects an out-of-order log leaving the document untouched`() {
        val document = Document(key)
        lateinit var restored: Document
        lateinit var jsonBefore: String
        lateinit var checkPointBefore: CheckPoint
        lateinit var changeIDBefore: ChangeID
        lateinit var pendingBefore: List<UInt>
        lateinit var outOfOrder: List<Change>

        runBlocking {
            document.setActor(actorA)
            val snapshot = document.toBytes()
            document.updateAsync { root, _ -> root["a"] = 1 }.await()
            document.updateAsync { root, _ -> root["b"] = 2 }.await()
            val appended = document.pendingChangesAfter(0u)
            outOfOrder = listOf(appended[1], appended[0])

            restored = Document.fromBytes(key, snapshot)
            restored.setActor(actorA)
            jsonBefore = restored.toJson()
            checkPointBefore = restored.checkPoint
            changeIDBefore = restored.changeID
            pendingBefore = restored.pendingChanges().map { it.id.clientSeq }
        }

        val exception = assertThrows(YorkieException::class.java) {
            runBlocking { restored.restoreAppendedChanges(outOfOrder) }
        }
        assertEquals(ErrInvalidArgument, exception.code)
        assertTrue(exception.errorMessage.contains("ascending"))

        assertEquals(jsonBefore, restored.toJson())
        assertEquals(checkPointBefore, restored.checkPoint)
        assertEquals(changeIDBefore, restored.changeID)
        assertEquals(
            pendingBefore,
            runBlocking { restored.pendingChanges() }.map { it.id.clientSeq },
        )
    }

    @Test
    fun `T22 restoreMetaFromBytes round-trips the header without touching the root`() = runTest {
        val live = Document(key)
        live.setActor(actorA)
        live.updateAsync { root, _ -> root["k"] = 1 }.await()
        live.applyChangePack(
            ChangePack(
                key,
                CheckPoint(9, 1u),
                emptyList(),
                null,
                false,
                INITIAL_VERSION_VECTOR,
                epoch = 3,
            ),
        )
        live.setDocId("live-doc-id")

        val restored = Document(key)
        restored.setActor(actorA)
        restored.updateAsync { root, _ -> root["untouched"] = true }.await()
        restored.updateAsync { root, _ -> root["unacked"] = true }.await()
        val jsonBefore = restored.toJson()
        val pendingBefore = restored.pendingChanges().map { it.id.clientSeq }

        restored.restoreMetaFromBytes(live.metaToBytes())

        assertEquals(live.checkPoint, restored.checkPoint)
        assertEquals(live.changeID, restored.changeID)
        assertEquals(live.epoch, restored.epoch)
        assertEquals(live.docId, restored.docId)
        assertEquals(jsonBefore, restored.toJson())
        // The queue keeps only what the restored checkpoint has not acked (T26 pins the
        // trimming itself).
        assertEquals(
            pendingBefore.filter { it > live.checkPoint.clientSeq },
            restored.pendingChanges().map { it.id.clientSeq },
        )
    }

    @Test
    fun `T22b a two-blob meta restores the checkpoint and changeID and leaves epoch and docId`() =
        runTest {
            val live = Document(key)
            live.setActor(actorA)
            live.updateAsync { root, _ -> root["k"] = 1 }.await()
            live.applyChangePack(
                ChangePack(
                    key,
                    CheckPoint(9, 1u),
                    emptyList(),
                    null,
                    false,
                    INITIAL_VERSION_VECTOR,
                    epoch = 3,
                ),
            )
            live.setDocId("live-doc-id")
            val twoBlobMeta = live.metaToBytes().truncateToBlobCount(2)

            val restored = Document(key)
            restored.setActor(actorA)
            restored.applyChangePack(
                ChangePack(
                    key,
                    CheckPoint.InitialCheckPoint,
                    emptyList(),
                    null,
                    false,
                    INITIAL_VERSION_VECTOR,
                    epoch = 42,
                ),
            )
            restored.setDocId("pre-existing-doc-id")

            restored.restoreMetaFromBytes(twoBlobMeta)

            assertEquals(live.checkPoint, restored.checkPoint)
            assertEquals(live.changeID, restored.changeID)
            assertEquals(42L, restored.epoch)
            assertEquals("pre-existing-doc-id", restored.docId)
        }

    @Test
    fun `T22c restoreMetaFromBytes rejects empty, malformed, and non-numeric-epoch meta`() =
        runTest {
            val restored = Document(key)
            restored.setActor(actorA)

            val zeroException = assertThrows(YorkieException::class.java) {
                runBlocking { restored.restoreMetaFromBytes(ByteArray(0)) }
            }
            assertEquals(ErrInvalidArgument, zeroException.code)

            val malformedCheckpoint = ByteArray(0)
                .appendBlob("not-the-checkpoint-json-shape".toByteArray(Charsets.UTF_8))
            val malformedException = assertThrows(YorkieException::class.java) {
                runBlocking { restored.restoreMetaFromBytes(malformedCheckpoint) }
            }
            assertEquals(ErrInvalidArgument, malformedException.code)

            val live = Document(key)
            live.setActor(actorA)
            live.updateAsync { root, _ -> root["k"] = 1 }.await()
            val nonNumericEpoch =
                live.metaToBytes().replaceBlob(2, "not-a-number".toByteArray(Charsets.UTF_8))
            val epochException = assertThrows(YorkieException::class.java) {
                runBlocking { restored.restoreMetaFromBytes(nonNumericEpoch) }
            }
            assertEquals(ErrInvalidArgument, epochException.code)
            assertTrue(epochException.errorMessage.contains("epoch"))

            // Same ASCII-digits-only rule as the envelope's epoch blob (T-E1).
            val signedEpoch = live.metaToBytes().replaceBlob(2, "+7".toByteArray(Charsets.UTF_8))
            val signedException = assertThrows(YorkieException::class.java) {
                runBlocking { restored.restoreMetaFromBytes(signedEpoch) }
            }
            assertEquals(ErrInvalidArgument, signedException.code)
        }

    @Test
    fun `T23 restoreAppendedChanges does not pull changeID back below an all-acked meta counter`() =
        runTest {
            val document = Document(key)
            document.setActor(actorA)
            document.updateAsync { root, _ -> root["a"] = 1 }.await()

            val restored = Document.fromBytes(key, document.toBytes())
            restored.setActor(actorA)

            val highCounter = Document(key)
            highCounter.setActor(actorA)
            repeat(10) { i -> highCounter.updateAsync { root, _ -> root["x$i"] = i }.await() }
            restored.restoreMetaFromBytes(highCounter.metaToBytes())
            val changeIDAfterMeta = restored.changeID

            document.updateAsync { root, _ -> root["b"] = 2 }.await()
            val appended = document.pendingChangesAfter(1u)

            restored.restoreAppendedChanges(appended)

            // The guard is specifically about clientSeq: a lower-seq replay must not pull the
            // document's counter back below what the meta header already established, even
            // though applying the change still advances lamport/versionVector via syncClocks
            // (the same as it would for any applied change).
            assertEquals(changeIDAfterMeta.clientSeq, restored.changeID.clientSeq)
            assertTrue(restored.changeID.lamport >= changeIDAfterMeta.lamport)
            val restoredB = restored.getRoot().getAs<JsonPrimitive>("b")
            assertEquals(2, restoredB.value)
        }

    @Test
    fun `T23b restoreAppendedChanges on an empty list is a no-op`() = runTest {
        val document = Document(key)
        document.setActor(actorA)
        document.updateAsync { root, _ -> root["k"] = 1 }.await()
        val jsonBefore = document.toJson()
        val changeIDBefore = document.changeID
        val pendingBefore = document.pendingChanges().map { it.id.clientSeq }
        val canUndoBefore = document.history.canUndo()

        document.restoreAppendedChanges(emptyList())

        assertEquals(jsonBefore, document.toJson())
        assertEquals(changeIDBefore, document.changeID)
        assertEquals(pendingBefore, document.pendingChanges().map { it.id.clientSeq })
        assertEquals(canUndoBefore, document.history.canUndo())
    }

    @Test
    fun `T24 persistBase returns a toBytes-equal snapshot and the highest carried clientSeq`() =
        runTest {
            val document = Document(key)
            document.setActor(actorA)
            document.updateAsync { root, _ -> root["k1"] = 1 }.await()
            document.updateAsync { root, _ -> root["k2"] = 2 }.await()

            val base = document.persistBase()
            val expectedSnapshot = document.toBytes()

            assertTrue(base.snapshot.contentEquals(expectedSnapshot))
            assertEquals(
                document.pendingChanges().last().id.clientSeq,
                base.lastCarriedClientSeq,
            )

            val emptyDoc = Document(key)
            emptyDoc.setActor(actorA)
            emptyDoc.applyChangePack(
                ChangePack(
                    key,
                    CheckPoint(0, 3u),
                    emptyList(),
                    null,
                    false,
                    INITIAL_VERSION_VECTOR,
                ),
            )

            val emptyBase = emptyDoc.persistBase()
            assertTrue(emptyDoc.pendingChanges().isEmpty())
            assertEquals(3u, emptyBase.lastCarriedClientSeq)
        }

    @Test
    fun `T25 restoreMetaFromBytes is all-or-nothing on a corrupt changeID blob`() = runTest {
        val live = Document(key)
        live.setActor(actorA)
        live.updateAsync { root, _ -> root["k"] = 1 }.await()
        live.applyChangePack(
            ChangePack(
                key,
                CheckPoint(9, 1u),
                emptyList(),
                null,
                false,
                INITIAL_VERSION_VECTOR,
                epoch = 3,
            ),
        )
        val corruptChangeID = live.metaToBytes()
            .replaceBlob(1, "not-a-valid-changeid-protobuf-blob".toByteArray(Charsets.UTF_8))

        val restored = Document(key)
        restored.setActor(actorA)
        val checkPointBefore = restored.checkPoint
        val changeIDBefore = restored.changeID

        val exception = assertThrows(YorkieException::class.java) {
            runBlocking { restored.restoreMetaFromBytes(corruptChangeID) }
        }

        assertEquals(ErrInvalidArgument, exception.code)
        // All-or-nothing (LOW-1): the checkpoint blob decoded FINE, but the failure must not
        // leave it half-written while changeID/epoch/docId stay stale.
        assertEquals(checkPointBefore, restored.checkPoint)
        assertEquals(changeIDBefore, restored.changeID)

        // Regression: a well-formed meta still round-trips (T22 shape).
        restored.restoreMetaFromBytes(live.metaToBytes())
        assertEquals(live.checkPoint, restored.checkPoint)
        assertEquals(live.changeID, restored.changeID)
    }

    @Test
    fun `T25b restoreMetaFromBytes rejects a zero-length changeID blob`() = runTest {
        val live = Document(key)
        live.setActor(actorA)
        live.updateAsync { root, _ -> root["k"] = 1 }.await()

        val zeroLengthChangeID = live.metaToBytes().replaceBlob(1, ByteArray(0))

        val restored = Document(key)
        restored.setActor(actorA)
        val checkPointBefore = restored.checkPoint

        val exception = assertThrows(YorkieException::class.java) {
            runBlocking { restored.restoreMetaFromBytes(zeroLengthChangeID) }
        }

        assertEquals(ErrInvalidArgument, exception.code)
        assertEquals(checkPointBefore, restored.checkPoint)
    }

    @Test
    fun `T26 restoreMetaFromBytes drops envelope pending changes the header says were acked`() =
        runTest {
            // given: an envelope carrying pending [1, 2, 3], and a meta written after the server
            // acked 2 of them (team review, critic M2 -- a Kotlin hardening; JS leaves all three
            // queued and relies on the server skipping the two it already applied).
            val live = Document(key)
            live.setActor(actorA)
            repeat(3) { i -> live.updateAsync { root, _ -> root["k$i"] = i }.await() }
            val envelope = live.toBytes()
            live.applyChangePack(
                ChangePack(
                    key,
                    CheckPoint(1, 2u),
                    emptyList(),
                    null,
                    false,
                    INITIAL_VERSION_VECTOR,
                ),
            )
            val meta = live.metaToBytes()
            val restored = Document.fromBytes(key, envelope)
            assertEquals(listOf(1u, 2u, 3u), restored.pendingChanges().map { it.id.clientSeq })
            val jsonBefore = restored.toJson()

            // when
            restored.restoreMetaFromBytes(meta)

            // then: only the unacked tail stays queued; the root is untouched.
            assertEquals(2u, restored.checkPoint.clientSeq)
            assertEquals(listOf(3u), restored.pendingChanges().map { it.id.clientSeq })
            assertEquals(jsonBefore, restored.toJson())
        }

    @Test
    fun `T24b a Change round-trips through the stored-change codec`() = runTest {
        val document = Document(key)
        document.setActor(actorA)
        document.updateAsync(message = "add k1 with presence") { root, presence ->
            root["k1"] = 1
            presence.put(mapOf("cursor" to "1"))
        }.await()
        val change = document.pendingChanges().single()

        val bytes = change.toStoredChangeBytes()
        val decoded = bytes.toStoredChange()

        assertEquals(change.id, decoded.id)
        assertEquals(change.operations.size, decoded.operations.size)
        assertEquals(change.message, decoded.message)
        assertEquals(change.hasPresenceChange, decoded.hasPresenceChange)
    }

    @Test
    fun `T24c ByteArray(0) toStoredChange is rejected, not decoded as a zero-ID Change`() {
        val exception = assertThrows(YorkieException::class.java) {
            ByteArray(0).toStoredChange()
        }

        assertEquals(ErrInvalidArgument, exception.code)
    }
}

/**
 * Test-only re-implementation of the envelope's little-endian
 * length-prefixed blob framing, used to build malformed/legacy envelopes
 * without reaching into Document.kt's file-private packBlobs/unpackBlobs.
 */
private fun ByteArray.truncateToBlobCount(count: Int): ByteArray {
    var offset = 0
    repeat(count) {
        val length = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        offset += 4 + length
    }
    return copyOfRange(0, offset)
}

private fun ByteArray.appendBlob(payload: ByteArray): ByteArray {
    val header = ByteBuffer.allocate(
        4,
    ).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()
    return this + header + payload
}

/** Swaps the blob at [index] for [payload], re-framing the envelope. */
private fun ByteArray.replaceBlob(index: Int, payload: ByteArray): ByteArray {
    var offset = 0
    var result = ByteArray(0)
    var i = 0
    while (offset < size) {
        val length = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val blob = if (i == index) payload else copyOfRange(offset + 4, offset + 4 + length)
        result = result.appendBlob(blob)
        offset += 4 + length
        i++
    }
    return result
}
