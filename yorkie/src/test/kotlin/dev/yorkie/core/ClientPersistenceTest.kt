package dev.yorkie.core

import android.util.Base64
import dev.yorkie.core.MockYorkieService.Companion.ATTACH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_ACTOR_ID
import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_STABLE_ACTOR_ID
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.VersionVector.Companion.INITIAL_VERSION_VECTOR
import dev.yorkie.presence.Channel
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `offline_persist_sync_test.ts` (2) + `epoch_reanchor_test.ts` (4)
 * (`2291bf67`/#1338, RTCOLLABPLATFORM-771), plus the iOS-derived cases (unreadable store,
 * pre-registration lease release, persist ordering, teardown). RED for each group = revert the
 * named production hunk in `attachDocument`/`syncInternal`/`detachInternal` (see the exec plan §4).
 */
class ClientPersistenceTest {

    private val actorB = "000000000000000000000099"

    // A restored ChangeID/VersionVector routes through android.util.Base64, an unmocked stub
    // under plain JVM unit tests — every toBytes()/fromBytes() call in this file needs it.
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

    private fun newClient(
        service: MockYorkieService,
        docStore: DocStore? = null,
        sessionLock: SessionLock = NoopSessionLock,
        key: String = TEST_KEY,
    ): Client {
        val client = Client(
            options = Client.Options(
                key = key,
                apiKey = TEST_KEY,
                docStore = docStore,
                sessionLock = sessionLock,
            ),
            host = "0.0.0.0",
        )
        client.service = service
        return client
    }

    private fun storeKeyFor(docKey: String) = "$TEST_KEY/$TEST_KEY/$docKey"

    private suspend fun buildEnvelope(
        docKey: String,
        actor: String = TEST_ACTOR_ID,
        docId: String? = null,
        serverSeq: Long = 0,
        edit: suspend (
            Document,
        ) -> Unit = { d -> d.updateAsync { root, _ -> root["k"] = 1 }.await() },
    ): ByteArray {
        val document = Document(docKey)
        document.setActor(actor)
        edit(document)
        if (serverSeq > 0) {
            document.applyChangePack(
                ChangePack(
                    docKey,
                    CheckPoint(serverSeq, 0u),
                    emptyList(),
                    null,
                    false,
                    INITIAL_VERSION_VECTOR,
                ),
            )
        }
        docId?.let { document.setDocId(it) }
        return document.toBytes()
    }

    /** Polls [block] on a real dispatcher until non-null, since Client/Document run on their own
     * real single-threaded dispatchers outside runTest's virtual clock. */
    private suspend fun <T> awaitCondition(timeoutMs: Long = 5_000, block: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            block()?.let { return it }
            check(
                System.currentTimeMillis() < deadline,
            ) { "condition not met within ${timeoutMs}ms" }
            withContext(Dispatchers.Default) { delay(20) }
        }
    }

    // --- actor (AC2) ---------------------------------------------------

    @Test
    fun `T1 activate with a distinct actor_id makes requireActorId differ from requireClientId`() =
        runTest {
            val service = MockYorkieService().apply {
                activateResponseActorId = TEST_STABLE_ACTOR_ID
            }
            val client = newClient(service)
            client.activateAsync().await()

            assertEquals(TEST_STABLE_ACTOR_ID, client.requireActorId())
            assertNotEquals(client.requireClientId(), client.requireActorId())

            client.close()
        }

    @Test
    fun `T2 activate against a server with no actor_id falls back to the client id`() = runTest {
        val client = newClient(MockYorkieService())
        client.activateAsync().await()

        assertEquals(client.requireClientId(), client.requireActorId())

        client.close()
    }

    @Test
    fun `T3 the document watchRequest carries the stable actor`() = runTest {
        val service = MockYorkieService().apply {
            activateResponseActorId = TEST_STABLE_ACTOR_ID
        }
        val client = newClient(service)
        client.activateAsync().await()
        val document = Document("watch-actor-doc")

        client.attachDocument(document).await()
        awaitCondition { service.lastDocumentWatchActorId }

        assertEquals(TEST_STABLE_ACTOR_ID, service.lastDocumentWatchActorId)

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    // --- persist after sync + triggers + ordering (AC5) -----------------

    @Test
    fun `T4 a sync overwrites the store with the post-applyChangePack state`() = runTest {
        // The mock's pushPullChanges response always carries a remote change (k2) but no
        // client-seq ack, so this proves the persist-after-sync site (not the event-driven
        // one, which never fires for a push with no LocalChange/PresenceChanged) snapshots
        // AFTER applyChangePack: the stored envelope must contain the remote k2 key.
        val store = MemoryDocStore()
        val client = newClient(MockYorkieService(), docStore = store)
        client.activateAsync().await()
        val document = Document("push-only-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        client.syncAsync(document).await()

        val stored = awaitCondition { store.load(storeKeyFor("push-only-doc")) }
        val restored = Document.fromBytes("push-only-doc", stored)
        assertTrue(restored.toJson().contains("\"k2\""))

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `T5 a presence-only local change persists`() = runTest {
        val store = MemoryDocStore()
        val client = newClient(MockYorkieService(), docStore = store)
        client.activateAsync().await()
        val document = Document("presence-only-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        document.updateAsync { _, presence -> presence.put(mapOf("cursor" to "1")) }.await()

        val stored = awaitCondition { store.load(storeKeyFor("presence-only-doc")) }
        assertNotNull(stored)

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `T6 two rapid edits leave the second envelope in the store`() = runTest {
        val store = MemoryDocStore()
        val client = newClient(MockYorkieService(), docStore = store)
        client.activateAsync().await()
        val document = Document("rapid-edit-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        document.updateAsync { root, _ -> root["k"] = 1 }.await()
        document.updateAsync { root, _ -> root["k"] = 2 }.await()

        val restored = awaitCondition {
            store.load(storeKeyFor("rapid-edit-doc"))?.let {
                Document.fromBytes(
                    "rapid-edit-doc",
                    it,
                ).takeIf { d -> d.toJson().contains("\"k\":2") }
            }
        }
        assertTrue(restored.toJson().contains("\"k\":2"))

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    // --- epoch re-anchor (AC4) ------------------------------------------

    @Test
    fun `T7 a stale epoch on a store-backed resume re-anchors without deactivating`() = runTest {
        val store = MemoryDocStore()
        val docKey = "epoch-reanchor-doc"
        store.save(storeKeyFor(docKey), buildEnvelope(docKey))
        val service = MockYorkieService().apply { epochMismatchOnAttachOnceKeys += docKey }
        val client = newClient(service, docStore = store)
        client.activateAsync().await()
        val document = Document(docKey)

        val droppedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            document.events.filterIsInstance<Document.Event.LocalChangesDropped>().first()
        }
        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        assertTrue(result.isSuccess)
        val dropped = droppedDeferred.await()
        assertEquals(Document.Event.Reason.EpochReanchor, dropped.reason)
        assertTrue(dropped.changes.isNotEmpty())
        assertTrue(client.isActive)
        assertEquals(null, store.load(storeKeyFor(docKey)))

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `T8 an epoch mismatch without a store propagates and does not deactivate`() = runTest {
        val docKey = "epoch-no-store-doc"
        val service = MockYorkieService().apply { epochMismatchOnAttachOnceKeys += docKey }
        val client = newClient(service)
        client.activateAsync().await()
        val document = Document(docKey)

        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        assertTrue(result.isFailure)
        assertTrue(client.isActive)

        client.deactivateAsync().await()
        client.close()
    }

    // --- tier-3 purge guard (AC4) ----------------------------------------

    @Test
    fun `T9 a docId change on a restored attach emits DocumentPurged and re-anchors`() = runTest {
        val store = MemoryDocStore()
        val docKey = "purge-docid-doc"
        store.save(storeKeyFor(docKey), buildEnvelope(docKey, docId = "persisted-doc-id"))
        val service = MockYorkieService().apply {
            attachDocumentIdOverride[docKey] = "server-assigned-different-id"
        }
        val client = newClient(service, docStore = store)
        client.activateAsync().await()
        val document = Document(docKey)

        val droppedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            document.events.filterIsInstance<Document.Event.LocalChangesDropped>().first()
        }
        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        assertTrue(result.isSuccess)
        val dropped = droppedDeferred.await()
        assertEquals(Document.Event.Reason.DocumentPurged, dropped.reason)
        assertEquals(null, store.load(storeKeyFor(docKey)))
        assertEquals("server-assigned-different-id", document.docId)

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `T10 a serverSeq reset with prior local state emits DocumentPurged`() = runTest {
        val store = MemoryDocStore()
        val docKey = "purge-serverseq-doc"
        store.save(storeKeyFor(docKey), buildEnvelope(docKey, serverSeq = 5))
        val service = MockYorkieService().apply { attachServerSeqResetKeys += docKey }
        val client = newClient(service, docStore = store)
        client.activateAsync().await()
        val document = Document(docKey)

        val droppedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            document.events.filterIsInstance<Document.Event.LocalChangesDropped>().first()
        }
        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        assertTrue(result.isSuccess)
        assertEquals(Document.Event.Reason.DocumentPurged, droppedDeferred.await().reason)

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    // --- actor mismatch / restore failed (AC3, C-3) -----------------------

    @Test
    fun `T11 a persisted actor mismatch emits ActorMismatch with the recovered pending changes`() =
        runTest {
            val store = MemoryDocStore()
            val docKey = "actor-mismatch-doc"
            store.save(storeKeyFor(docKey), buildEnvelope(docKey, actor = actorB))
            val client = newClient(MockYorkieService(), docStore = store)
            client.activateAsync().await()
            val document = Document(docKey)

            val droppedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                document.events.filterIsInstance<Document.Event.LocalChangesDropped>().first()
            }
            val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

            assertTrue(result.isSuccess)
            val dropped = droppedDeferred.await()
            assertEquals(Document.Event.Reason.ActorMismatch, dropped.reason)
            assertTrue(dropped.changes.isNotEmpty())
            assertEquals(null, store.load(storeKeyFor(docKey)))

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }

    @Test
    fun `T12 a corrupt envelope emits RestoreFailed with no recovered changes`() = runTest {
        val store = MemoryDocStore()
        val docKey = "corrupt-envelope-doc"
        val full = buildEnvelope(docKey)
        store.save(storeKeyFor(docKey), full.copyOfRange(0, full.size - 3))
        val client = newClient(MockYorkieService(), docStore = store)
        client.activateAsync().await()
        val document = Document(docKey)

        val droppedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            document.events.filterIsInstance<Document.Event.LocalChangesDropped>().first()
        }
        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        assertTrue(result.isSuccess)
        val dropped = droppedDeferred.await()
        assertEquals(Document.Event.Reason.RestoreFailed, dropped.reason)
        assertTrue(dropped.changes.isEmpty())

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    // --- unreadable store (AC5, scenario 8) --------------------------------

    private class UnreadableDocStore(private val inner: DocStore = MemoryDocStore()) : DocStore {
        var saveCount = 0
        var removeCount = 0
        override suspend fun load(docKey: String): ByteArray? =
            throw java.io.IOException("store backend unavailable")

        override suspend fun save(docKey: String, bytes: ByteArray) {
            saveCount++
            inner.save(docKey, bytes)
        }

        override suspend fun remove(docKey: String) {
            removeCount++
            inner.remove(docKey)
        }
    }

    @Test
    fun `T13 an unreadable store attaches fresh and never persists this session`() = runTest {
        val store = UnreadableDocStore()
        val client = newClient(MockYorkieService(), docStore = store)
        client.activateAsync().await()
        val document = Document("unreadable-doc")

        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()
        assertTrue(result.isSuccess)
        document.updateAsync { root, _ -> root["k"] = 1 }.await()
        withContext(Dispatchers.Default) { delay(200) }

        assertEquals(0, store.saveCount)

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    // --- lease teardown (AC6) ---------------------------------------------

    private class RecordingSessionLock : SessionLock {
        val held = mutableSetOf<String>()
        override suspend fun acquire(name: String): SessionLockHandle? {
            if (!held.add(name)) return null
            return object : SessionLockHandle {
                override fun release() {
                    held.remove(name)
                }
            }
        }
    }

    @Test
    fun `T14 detach releases the session lease`() = runTest {
        val lock = RecordingSessionLock()
        val client = newClient(MockYorkieService(), docStore = MemoryDocStore(), sessionLock = lock)
        client.activateAsync().await()
        val document = Document("lease-detach-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()
        assertTrue(lock.held.isNotEmpty())

        client.detachDocument(document).await()

        assertTrue(lock.held.isEmpty())
        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `T15 deactivate releases the session lease`() = runTest {
        val lock = RecordingSessionLock()
        val client = newClient(MockYorkieService(), docStore = MemoryDocStore(), sessionLock = lock)
        client.activateAsync().await()
        val document = Document("lease-deactivate-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()
        assertTrue(lock.held.isNotEmpty())

        client.deactivateAsync().await()

        assertTrue(lock.held.isEmpty())
        client.close()
    }

    @Test
    fun `T16 removeDocument releases the session lease`() = runTest {
        val lock = RecordingSessionLock()
        val client = newClient(MockYorkieService(), docStore = MemoryDocStore(), sessionLock = lock)
        client.activateAsync().await()
        val document = Document("lease-remove-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()
        assertTrue(lock.held.isNotEmpty())

        client.removeDocument(document).await()

        assertTrue(lock.held.isEmpty())
        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `T17 a pre-registration attach failure releases the lease and clears the in-flight mark`() =
        runTest {
            val lock = RecordingSessionLock()
            val client = newClient(
                MockYorkieService(),
                docStore = MemoryDocStore(),
                sessionLock = lock,
            )
            client.activateAsync().await()
            val document = Document(ATTACH_ERROR_DOCUMENT_KEY)

            val result = client.attachDocument(document).await()

            assertTrue(result.isFailure)
            assertTrue(lock.held.isEmpty())
            assertFalse(client.has(ATTACH_ERROR_DOCUMENT_KEY))

            client.close()
        }

    // --- adversarial probes -------------------------------------------------

    @Test
    fun `T18 a store envelope for a different document key is not restored`() = runTest {
        val store = MemoryDocStore()
        store.save(storeKeyFor("doc-a"), buildEnvelope("doc-a"))
        val client = newClient(MockYorkieService(), docStore = store)
        client.activateAsync().await()
        val document = Document("doc-b")

        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        assertTrue(result.isSuccess)
        assertFalse(document.toJson().contains("\"k\""))

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `T19 two clients with different client keys sharing one store do not cross-talk`() =
        runTest {
            val store = MemoryDocStore()
            val docKey = "shared-store-doc"
            val serviceA = MockYorkieService()
            val clientA = newClient(serviceA, docStore = store, key = "client-a")
            clientA.activateAsync().await()
            val documentA = Document(docKey)
            clientA.attachDocument(documentA, syncMode = Client.SyncMode.Manual).await()
            documentA.updateAsync { root, _ -> root["owner"] = "a" }.await()
            awaitCondition { store.load("$TEST_KEY/client-a/$docKey") }

            val clientB = newClient(MockYorkieService(), docStore = store, key = "client-b")
            clientB.activateAsync().await()
            val documentB = Document(docKey)
            val result = clientB.attachDocument(
                documentB,
                syncMode = Client.SyncMode.Manual,
            ).await()

            assertTrue(result.isSuccess)
            assertFalse(documentB.toJson().contains("\"owner\""))

            clientA.detachDocument(documentA).await()
            clientA.deactivateAsync().await()
            clientA.close()
            clientB.detachDocument(documentB).await()
            clientB.deactivateAsync().await()
            clientB.close()
        }

    @Test
    fun `T20 a custom session lock receives the exact apiKey-clientKey-docKey name`() = runTest {
        val lock = RecordingSessionLock()
        val client = newClient(MockYorkieService(), docStore = MemoryDocStore(), sessionLock = lock)
        client.activateAsync().await()
        val document = Document("lease-name-doc")

        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        assertTrue(lock.held.contains("yorkie-session:$TEST_KEY/$TEST_KEY/lease-name-doc"))

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `T21 a contended session lock fails the attach with ErrInvalidArgument`() = runTest {
        val lock = RecordingSessionLock()
        val client = newClient(MockYorkieService(), docStore = MemoryDocStore(), sessionLock = lock)
        client.activateAsync().await()
        val document = Document("lease-contended-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        // Same store key composition (same client/apiKey), forced contention by
        // pre-holding the exact lock name a second attach of the same key would need.
        lock.held += "yorkie-session:$TEST_KEY/$TEST_KEY/lease-contended-doc-manual"
        val client2 =
            newClient(MockYorkieService(), docStore = MemoryDocStore(), sessionLock = lock)
        client2.activateAsync().await()
        val contended = Document("lease-contended-doc-manual")

        val result = client2.attachDocument(contended).await()

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertEquals(ErrInvalidArgument, (exception as? YorkieException)?.code)
        assertFalse(client2.has("lease-contended-doc-manual"))

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
        client2.close()
    }

    // --- ErrInvalidServerSeq re-anchor (AC4, round-2 QA HIGH-1/MEDIUM-2) --------

    @Test
    fun `T22 a stale checkpoint on a store-backed resume re-anchors without deactivating`() =
        runTest {
            val store = MemoryDocStore()
            val docKey = "invalid-serverseq-reanchor-doc"
            store.save(storeKeyFor(docKey), buildEnvelope(docKey))
            val service = MockYorkieService().apply { invalidServerSeqOnAttachOnceKeys += docKey }
            val client = newClient(service, docStore = store)
            client.activateAsync().await()
            val document = Document(docKey)

            val droppedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                document.events.filterIsInstance<Document.Event.LocalChangesDropped>().first()
            }
            val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

            assertTrue(result.isSuccess)
            val dropped = droppedDeferred.await()
            assertEquals(Document.Event.Reason.EpochReanchor, dropped.reason)
            assertTrue(dropped.changes.isNotEmpty())
            assertTrue(client.isActive)
            assertEquals(null, store.load(storeKeyFor(docKey)))

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }

    @Test
    fun `T23 a stale checkpoint serverSeq without a store propagates and does not deactivate`() =
        runTest {
            val docKey = "invalid-serverseq-no-store-doc"
            val service = MockYorkieService().apply { invalidServerSeqOnAttachOnceKeys += docKey }
            val client = newClient(service)
            client.activateAsync().await()
            val document = Document(docKey)

            val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

            assertTrue(result.isFailure)
            assertTrue(client.isActive)

            client.deactivateAsync().await()
            client.close()
        }

    // --- persist drain on teardown (round-2 QA MEDIUM-1) ------------------------

    @Test
    fun `T24 an edit immediately followed by close lands in the store`() = runTest {
        val store = MemoryDocStore()
        val client = newClient(MockYorkieService(), docStore = store)
        client.activateAsync().await()
        val document = Document("close-drain-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        document.updateAsync { root, _ -> root["k"] = 1 }.await()
        client.close()

        val stored = store.load(storeKeyFor("close-drain-doc"))
        assertNotNull(stored)
        val restored = Document.fromBytes("close-drain-doc", stored)
        assertTrue(restored.toJson().contains("\"k\":1"))
    }

    @Test
    fun `T25 deactivateAsync flushes an in-flight persist before releasing the lease`() = runTest {
        val store = MemoryDocStore()
        val lock = RecordingSessionLock()
        val client = newClient(MockYorkieService(), docStore = store, sessionLock = lock)
        client.activateAsync().await()
        val document = Document("deactivate-drain-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        document.updateAsync { root, _ -> root["k"] = 1 }.await()
        client.deactivateAsync().await()

        assertTrue(lock.held.isEmpty())
        val stored = store.load(storeKeyFor("deactivate-drain-doc"))
        assertNotNull(stored)
        val restored = Document.fromBytes("deactivate-drain-doc", stored)
        assertTrue(restored.toJson().contains("\"k\":1"))

        client.close()
    }

    // --- close() drain bound vs. write durability (round-4 cross-judge HIGH-1-new) ------

    // --- team-review pins (2026-09-29) ------------------------------------

    @Test
    fun `T27 a bare INVALID_ARGUMENT with the server checkpoint message re-anchors`() = runTest {
        // given: the REAL 0.7.20 wire shape — no ErrorInfo, fixed message only.
        val store = MemoryDocStore()
        val docKey = "invalid-serverseq-bare-reanchor-doc"
        store.save(storeKeyFor(docKey), buildEnvelope(docKey))
        val service = MockYorkieService().apply {
            invalidServerSeqBareOnAttachOnceKeys += docKey
        }
        val client = newClient(service, docStore = store)
        client.activateAsync().await()
        val document = Document(docKey)
        val droppedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            document.events.filterIsInstance<Document.Event.LocalChangesDropped>().first()
        }

        // when
        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        // then
        assertTrue(result.isSuccess)
        assertEquals(Document.Event.Reason.EpochReanchor, droppedDeferred.await().reason)
        assertTrue(client.isActive)
        assertEquals(null, store.load(storeKeyFor(docKey)))

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `T28 the channel watchRequest never carries the stable actor`() = runTest {
        // given: a server that hands out a stable actor distinct from the session id.
        val service = MockYorkieService().apply {
            activateResponseActorId = TEST_STABLE_ACTOR_ID
        }
        val client = newClient(service)
        client.activateAsync().await()
        val channel = Channel("watch-actor-channel")

        // when
        client.attachChannel(channel, isRealtime = true).await()
        awaitCondition { service.lastChannelWatchActorId }

        // then: C-1 — a channel's actor IS the session id; only the document watch
        // stream declares the stable actor (JS refreshChannel/attachChannel stamp this.id).
        assertEquals("", service.lastChannelWatchActorId)

        client.detachChannel(channel).await()
        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `T30 own presence survives a watch init that omits the subscriber`() = runTest {
        // given: presences are keyed by the stable actor and the init list is empty.
        val service = MockYorkieService().apply {
            activateResponseActorId = TEST_STABLE_ACTOR_ID
            watchInitClientIdsOverride = emptyList()
        }
        val client = newClient(service)
        client.activateAsync().await()
        val document = Document("watch-init-self-doc")
        val initialized = async(start = CoroutineStart.UNDISPATCHED) {
            document.events
                .filterIsInstance<Document.Event.PresenceChanged.MyPresence.Initialized>()
                .first()
        }

        // when
        client.attachDocument(document, initialPresence = mapOf("k" to "v")).await()
        initialized.await()

        // then: the self-guard keys by the stable actor (JS applyWatchInit compares
        // against changeID.getActorID()), so own presence is not cleared.
        assertTrue(document.allPresences.value.containsKey(TEST_STABLE_ACTOR_ID))

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }

    private class SlowSaveStore(private val delayMs: Long) : DocStore {
        private val inner = MemoryDocStore()

        @Volatile
        var saveCompleted = false

        override suspend fun load(docKey: String): ByteArray? = inner.load(docKey)

        override suspend fun save(docKey: String, bytes: ByteArray) {
            delay(delayMs)
            inner.save(docKey, bytes)
            saveCompleted = true
        }

        override suspend fun remove(docKey: String) = inner.remove(docKey)
    }

    // Slow test (~6s wall time): a real DocStore.save() slower than close()'s 5s drain
    // bound must still land after the bound elapses, not be permanently rejected by the
    // client dispatcher's shutdown (round-4 cross-judge probe cj025-d-close-past-drain-bound).
    // Real dispatchers/real time throughout -- runTest's virtual clock would mask the
    // dispatcher-shutdown race this reproduces.
    @Test
    fun `T26 close honours its 5s drain bound and a slow save still lands afterward`() = runTest {
        val store = SlowSaveStore(delayMs = 7_000)
        val client = newClient(MockYorkieService(), docStore = store)
        client.activateAsync().await()
        val document = Document("close-slow-persist-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        document.updateAsync { root, _ -> root["k"] = 1 }.await()
        // Head start so the persist-subscription collector has definitely enqueued the
        // write before close()'s drain timer starts (rules out a same-tick race, not
        // the drain-bound-vs-dispatcher-shutdown behavior this test targets).
        withContext(Dispatchers.Default) { delay(200) }

        val closeStart = System.currentTimeMillis()
        withContext(Dispatchers.Default) { client.close() }
        val closeElapsedMs = System.currentTimeMillis() - closeStart
        assertTrue(
            closeElapsedMs in 4_500..6_500,
            "close() must return near its 5s bound, not block for the full 7s save: " +
                "${closeElapsedMs}ms",
        )

        val stored = awaitCondition(timeoutMs = 4_000) {
            store.load(storeKeyFor("close-slow-persist-doc"))
        }
        assertTrue(store.saveCompleted)
        val restored = Document.fromBytes("close-slow-persist-doc", stored)
        assertTrue(restored.toJson().contains("\"k\":1"))
    }

    // --- synchronous persist registration (AC1, B2, spec 029) ---------------
    // RED at a7579fe6: revert the Document.onLocalChange hook (steps 1-3) and the Client-side
    // wiring that subscribes it (step 7) back to the document.events collector — the scratch
    // probe this replaces lost the marker in 5/10 runs at 3,000 edits; this test's 20,000-edit
    // text makes document.toBytes() slow enough that every one of the 5 repeats below fails
    // the same way once the fix is reverted.

    @Test
    fun `T31 an edit immediately followed by close never loses the last edit`() = runBlocking {
        repeat(5) { i ->
            val store = MemoryDocStore()
            val client = newClient(MockYorkieService(), docStore = store)
            client.activateAsync().await()
            val docKey = "close-race-doc-$i"
            val document = Document(docKey)
            client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

            document.updateAsync { root, _ ->
                val text = root.setNewText("t")
                repeat(20_000) { n -> text.edit(n, n, "x") }
            }.await()
            document.updateAsync { root, _ -> root["marker"] = "last" }.await()
            client.close()

            val stored = store.load(storeKeyFor(docKey))
            assertNotNull(stored, "run $i: no envelope was persisted at all")
            val restored = Document.fromBytes(docKey, stored)
            assertTrue(
                restored.toJson().contains("marker"),
                "run $i: close() lost the last edit (marker missing)",
            )
        }
    }

    // --- store re-anchor gated on a restored envelope (AC2, I3, spec 029) ---
    // RED at a7579fe6: revert the gate back to `options.docStore != null` (step 12) — the bare
    // stale-checkpoint error below would then re-anchor despite nothing having been restored.

    @Test
    fun `T32 a store read failure does not re-anchor a stale checkpoint attach`() = runTest {
        val store = UnreadableDocStore()
        val docKey = "unreadable-store-reanchor-doc"
        val service = MockYorkieService().apply {
            invalidServerSeqBareOnAttachOnceKeys += docKey
        }
        val client = newClient(service, docStore = store)
        client.activateAsync().await()
        val document = Document(docKey)

        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        assertTrue(result.isFailure)
        assertTrue(client.isActive)
        assertEquals(0, store.saveCount)
        assertEquals(0, store.removeCount)

        client.deactivateAsync().await()
        client.close()
    }

    // --- single bounded deactivate drain, not N*5s (AC4, M3, spec 029) ------
    // RED at a7579fe6: revert deactivateInternal to call detachInternal with its per-document
    // drain (step 10) — three attachments at 7s each would then take ~21s, not ~5-6.5s.

    @Test
    fun `T33 deactivate drains three slow persists with one bound instead of stacking them`() =
        runBlocking {
            val store = SlowSaveStore(delayMs = 7_000)
            val client = newClient(MockYorkieService(), docStore = store)
            client.activateAsync().await()
            val documents = (1..3).map { i ->
                Document("deactivate-drain-doc-$i").also {
                    client.attachDocument(it, syncMode = Client.SyncMode.Manual).await()
                }
            }
            documents.forEach { it.updateAsync { root, _ -> root["k"] = 1 }.await() }
            delay(200)

            val deactivateStart = System.currentTimeMillis()
            client.deactivateAsync().await()
            val elapsedMs = System.currentTimeMillis() - deactivateStart

            assertTrue(
                elapsedMs in 4_500..6_500,
                "deactivateAsync() must return near the single 5s bound, not stack N*5s " +
                    "across the three attachments: ${elapsedMs}ms",
            )
            client.close()
        }

    // --- persist queue pruned on detach (AC4, M2, spec 029) ------------------
    // RED at a7579fe6: revert the detachInternal prune (step 9) — the entry for this key
    // would remain in persistQueues forever after detach.

    @Test
    fun `T34 detach prunes the completed persist entry from the queue`() = runTest {
        val store = MemoryDocStore()
        val client = newClient(MockYorkieService(), docStore = store)
        client.activateAsync().await()
        val document = Document("detach-prune-doc")
        client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        document.updateAsync { root, _ -> root["k"] = 1 }.await()
        client.detachDocument(document).await()

        assertFalse(client.persistQueues.containsKey(storeKeyFor("detach-prune-doc")))

        client.deactivateAsync().await()
        client.close()
    }

    // --- JS-parity actor guard rejects an initial-actor envelope (AC5, D1, spec 029) ----
    // RED at a7579fe6: restore either INITIAL_ACTOR_ID disjunct in Document.restoreFrom's
    // guard (step 20) — the envelope below would then be exempted and restored instead of
    // rejected.

    @Test
    fun `T-D1 an initial-actor envelope is rejected as an actor mismatch`() = runTest {
        val store = MemoryDocStore()
        val docKey = "initial-actor-envelope-doc"
        store.save(storeKeyFor(docKey), buildEnvelope(docKey, actor = ActorID.INITIAL_ACTOR_ID))
        val client = newClient(MockYorkieService(), docStore = store)
        client.activateAsync().await()
        val document = Document(docKey)

        val droppedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            document.events.filterIsInstance<Document.Event.LocalChangesDropped>().first()
        }
        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

        assertTrue(result.isSuccess)
        val dropped = droppedDeferred.await()
        assertEquals(Document.Event.Reason.ActorMismatch, dropped.reason)
        assertTrue(dropped.changes.isNotEmpty())
        assertEquals(null, store.load(storeKeyFor(docKey)))

        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
    }
}
