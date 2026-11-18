package dev.yorkie.core

import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import dev.yorkie.api.v1.attachDocumentResponse
import dev.yorkie.api.v1.changePack
import dev.yorkie.api.v1.detachDocumentResponse
import dev.yorkie.api.v1.pushPullChangesResponse
import dev.yorkie.api.v1.refreshChannelResponse
import dev.yorkie.api.v1.removeDocumentResponse
import dev.yorkie.core.MockYorkieService.Companion.MOCK_SESSION_ID
import dev.yorkie.core.MockYorkieService.Companion.NORMAL_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_ACTOR_ID
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.SyncStatusChanged
import dev.yorkie.presence.Channel
import dev.yorkie.util.YorkieException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Ports the session and attachment ownership guards of JS SDK `87a3802f9` (#1522), as iOS did.
 * The keepalive deactivate itself is NOT changed here: it still awaits the server response
 * (see the PR body), so the JS cases that assert "settles without waiting" are not ported.
 *
 * Work suspended at an RPC can resume after a deactivate, or after a re-activation under a new
 * session. A late `ErrClientNotFound`/`ErrClientNotActivated` must deactivate only the session
 * the failed request belonged to, and a late response must be applied only while the
 * attachment it started from is still the one its key holds.
 */
class SessionOwnershipTest {
    private lateinit var service: MockYorkieService
    private lateinit var client: Client

    @Before
    fun setUp() {
        service = MockYorkieService()
        client = Client(
            host = "0.0.0.0",
            options = Client.Options(
                key = MockYorkieService.TEST_KEY,
                apiKey = MockYorkieService.TEST_KEY,
            ),
        )
        client.service = service
    }

    @After
    fun tearDown() {
        client.close()
    }

    private fun <T> failure(cause: ConnectException): ResponseMessage<T> =
        ResponseMessage.Failure(cause, emptyMap(), emptyMap())

    // -- captureSession -------------------------------------------------------------------

    @Test
    fun `captureSession is true while the client stays in the session`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val ownsSession = client.captureSession()
            assertTrue(ownsSession())
        }
    }

    @Test
    fun `captureSession is false after a deactivate`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val ownsSession = client.captureSession()
            client.deactivateAsync().await()
            assertFalse(ownsSession())
        }
    }

    @Test
    fun `captureSession stays false after re-activation under a new session`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val ownsSession = client.captureSession()
            client.deactivateAsync().await()
            client.activateAsync().await()
            assertTrue(client.isActive)
            assertFalse(ownsSession())
            assertTrue(client.captureSession()())
        }
    }

    @Test
    fun `captureSession before a channel-only activation owns the session once it activates`() =
        runTest {
            runBlocking {
                // Not activated: the capture holds no client id.
                val ownsSession = client.captureSession()
                assertTrue(ownsSession())

                client.activateAsync().await()
                assertTrue(ownsSession(), "activation is not a teardown")

                client.deactivateAsync().await()
                assertFalse(ownsSession())
            }
        }

    // -- a late error must not deactivate a new session -----------------------------------

    @Test
    fun `late task error must not deactivate a new session`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val document = Document(NORMAL_DOCUMENT_KEY)
            client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            service.pushPullHook = {
                entered.complete(Unit)
                release.await()
                failure(clientNotFoundConnectException())
            }
            val sync = client.syncAsync(document)
            entered.await() // the push-pull is now in flight under the first session

            client.deactivateAsync().await()
            service.pushPullHook = null
            client.activateAsync().await()
            assertTrue(client.isActive)

            release.complete(Unit)
            assertTrue(sync.await().isFailure)

            assertTrue(client.isActive, "the late ErrClientNotFound belongs to the old session")
        }
    }

    @Test
    fun `late sync response must not touch a document detached by teardown`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val document = Document(NORMAL_DOCUMENT_KEY)
            client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

            val synced = mutableListOf<SyncStatusChanged>()
            val collect = launch(start = CoroutineStart.UNDISPATCHED) {
                document.events.collect { if (it is SyncStatusChanged) synced.add(it) }
            }

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            service.pushPullHook = {
                entered.complete(Unit)
                release.await()
                ResponseMessage.Success(
                    pushPullChangesResponse {
                        changePack = changePack { documentKey = NORMAL_DOCUMENT_KEY }
                    },
                    emptyMap(),
                    emptyMap(),
                )
            }
            val sync = client.syncAsync(document)
            entered.await()

            client.deactivateAsync().await()
            service.pushPullHook = null
            release.complete(Unit)
            sync.await()
            delay(100) // settle window for a NEGATIVE assertion (no Synced may arrive)

            assertTrue(
                synced.none { it is SyncStatusChanged.Synced },
                "a response of a torn-down attachment is dropped",
            )
            collect.cancel()
        }
    }

    // -- attach loses its session ---------------------------------------------------------

    @Test
    fun `no attach registration under a new session`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val document = Document(NORMAL_DOCUMENT_KEY)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            service.attachDocumentHook = {
                entered.complete(Unit)
                release.await()
                null // fall through to the mock's own attach response
            }

            val attach = client.attachDocument(document)
            entered.await() // the attach RPC is in flight under the first session

            client.deactivateAsync().await()
            service.attachDocumentHook = null
            client.activateAsync().await()
            assertTrue(client.isActive)

            release.complete(Unit)
            val result = attach.await()
            val error = assertIs<YorkieException>(result.exceptionOrNull())
            assertEquals(YorkieException.Code.ErrClientNotActivated, error.code)

            assertFalse(client.has(NORMAL_DOCUMENT_KEY), "nothing is registered")
            assertEquals(ResourceStatus.Detached, document.getStatus())
            assertTrue(client.isActive, "an abandoned attach does not trigger recovery")
        }
    }

    @Test
    fun `a stale attach response never touches the document`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val document = Document(NORMAL_DOCUMENT_KEY)
            val docIdBefore = document.docId
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            service.attachDocumentHook = {
                entered.complete(Unit)
                release.await()
                ResponseMessage.Success(
                    attachDocumentResponse {
                        documentId = "stale-document-id"
                        maxSizePerDocument = 1234
                        changePack = changePack { documentKey = NORMAL_DOCUMENT_KEY }
                    },
                    emptyMap(),
                    emptyMap(),
                )
            }

            val attach = client.attachDocument(document)
            entered.await()
            client.deactivateAsync().await()
            service.attachDocumentHook = null
            client.activateAsync().await()

            release.complete(Unit)
            val error = assertIs<YorkieException>(attach.await().exceptionOrNull())
            assertEquals(YorkieException.Code.ErrClientNotActivated, error.code)

            assertEquals(docIdBefore, document.docId, "the stale response was not applied")
            assertFalse(client.has(NORMAL_DOCUMENT_KEY))
        }
    }

    @Test
    fun `an attach that loses its session before the RPC sends nothing`() = runTest {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = GatedLoadStore(entered, release)
            val storeClient = Client(
                host = "0.0.0.0",
                options = Client.Options(
                    key = MockYorkieService.TEST_KEY,
                    apiKey = MockYorkieService.TEST_KEY,
                    docStore = store,
                ),
            )
            storeClient.service = service
            var attachRpcs = 0
            service.attachDocumentHook = {
                attachRpcs++
                null
            }
            try {
                storeClient.activateAsync().await()
                val document = Document(NORMAL_DOCUMENT_KEY)

                // The attach is parked in its pre-RPC restore (the store load).
                val attach = storeClient.attachDocument(document)
                entered.await()
                storeClient.deactivateAsync().await()
                storeClient.activateAsync().await()

                release.complete(Unit)
                val error = assertIs<YorkieException>(attach.await().exceptionOrNull())
                assertEquals(YorkieException.Code.ErrClientNotActivated, error.code)

                assertEquals(0, attachRpcs, "no attach RPC under the old client id")
                assertFalse(storeClient.has(NORMAL_DOCUMENT_KEY))
            } finally {
                storeClient.close()
            }
        }
    }

    /** A [DocStore] whose `load` parks until released, to hold an attach before its RPC. */
    private class GatedLoadStore(
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
        private val inner: DocStore = MemoryDocStore(),
    ) : DocStore {
        override suspend fun load(docKey: String): StoredDoc? {
            entered.complete(Unit)
            release.await()
            return inner.load(docKey)
        }

        override suspend fun saveSnapshot(docKey: String, bytes: ByteArray) =
            inner.saveSnapshot(docKey, bytes)

        override suspend fun appendChange(docKey: String, change: StoredChange) =
            inner.appendChange(docKey, change)

        override suspend fun saveMeta(docKey: String, bytes: ByteArray) =
            inner.saveMeta(docKey, bytes)

        override suspend fun remove(docKey: String) = inner.remove(docKey)
    }

    @Test
    fun `an attachChannel that loses its session registers nothing`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // A same-key document attach holds the per-key mutex the channel attach waits on.
            service.attachDocumentHook = {
                entered.complete(Unit)
                release.await()
                null
            }
            val documentAttach = client.attachDocument(Document(NORMAL_DOCUMENT_KEY))
            entered.await()
            val channel = Channel(NORMAL_DOCUMENT_KEY)
            val channelAttach = client.attachChannel(channel, isRealtime = false)

            client.deactivateAsync().await()
            service.attachDocumentHook = null
            client.activateAsync().await()

            release.complete(Unit)
            documentAttach.await()
            val error = assertFailsWith<YorkieException> { channelAttach.await() }
            assertEquals(YorkieException.Code.ErrClientNotActivated, error.code)

            assertFalse(client.has(NORMAL_DOCUMENT_KEY), "stale channel attach registered nothing")
            assertEquals(ResourceStatus.Detached, channel.getStatus())
        }
    }

    // -- detach lifecycle -----------------------------------------------------------------

    @Test
    fun `detach lifecycle ownership - a late detach response leaves the teardown result alone`() =
        runTest {
            runBlocking {
                client.activateAsync().await()
                val document = Document(NORMAL_DOCUMENT_KEY)
                client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                service.detachDocumentHook = {
                    entered.complete(Unit)
                    release.await()
                    ResponseMessage.Success(detachDocumentResponse { }, emptyMap(), emptyMap())
                }
                val detach = client.detachDocument(document)
                entered.await()

                client.deactivateAsync().await()
                // Teardown already detached it.
                assertEquals(ResourceStatus.Detached, document.getStatus())
                assertFalse(client.has(NORMAL_DOCUMENT_KEY))

                release.complete(Unit)
                assertTrue(detach.await().isSuccess)

                assertEquals(ResourceStatus.Detached, document.getStatus())
                assertFalse(client.has(NORMAL_DOCUMENT_KEY))
            }
        }

    @Test
    fun `late detach error must not deactivate a new session`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val document = Document(NORMAL_DOCUMENT_KEY)
            client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            service.detachDocumentHook = {
                entered.complete(Unit)
                release.await()
                failure(clientNotFoundConnectException())
            }
            val detach = client.detachDocument(document)
            entered.await()

            client.deactivateAsync().await()
            service.detachDocumentHook = null
            client.activateAsync().await()

            release.complete(Unit)
            assertTrue(detach.await().isFailure)

            assertTrue(client.isActive)
        }
    }

    // -- remove lifecycle -----------------------------------------------------------------

    @Test
    fun `remove lifecycle ownership - a late remove response leaves the teardown result alone`() =
        runTest {
            runBlocking {
                client.activateAsync().await()
                val document = Document(NORMAL_DOCUMENT_KEY)
                client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                service.removeDocumentHook = {
                    entered.complete(Unit)
                    release.await()
                    ResponseMessage.Success(
                        removeDocumentResponse {
                            changePack = changePack { isRemoved = true }
                        },
                        emptyMap(),
                        emptyMap(),
                    )
                }
                val remove = client.removeDocument(document)
                entered.await()

                client.deactivateAsync().await()
                assertEquals(ResourceStatus.Detached, document.getStatus())

                release.complete(Unit)
                remove.await()

                assertEquals(
                    ResourceStatus.Detached,
                    document.getStatus(),
                    "a late remove must not mark a torn-down document removed",
                )
                assertFalse(client.has(NORMAL_DOCUMENT_KEY))
            }
        }

    // -- channel refresh ownership --------------------------------------------------------

    @Test
    fun `channel refresh ownership - a late refresh from a torn-down session is dropped`() =
        runTest {
            runBlocking {
                client.activateAsync().await()
                val channel = Channel("session-ownership-channel")
                client.attachChannel(channel, isRealtime = false).await()

                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                service.refreshChannelHook = {
                    entered.complete(Unit)
                    release.await()
                    ResponseMessage.Success(
                        refreshChannelResponse {
                            sessionCount = 7
                            clientId = TEST_ACTOR_ID
                            sessionId = MOCK_SESSION_ID
                        },
                        emptyMap(),
                        emptyMap(),
                    )
                }
                val refresh = client.syncAsync(channel)
                entered.await()

                client.deactivateAsync().await()
                service.refreshChannelHook = null
                client.activateAsync().await()

                release.complete(Unit)
                refresh.await()

                assertNull(channel.getSessionId(), "the old session's id is not applied")
                assertEquals(ResourceStatus.Detached, channel.getStatus())
                assertEquals(0L, channel.getSessionCount())
                assertFalse(client.has("session-ownership-channel"))
            }
        }

    @Test
    fun `detachChannel on an unattached key still throws after a teardown`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val channel = Channel("session-ownership-detached")
            client.attachChannel(channel, isRealtime = false).await()
            client.deactivateAsync().await()
            client.activateAsync().await()

            assertFailsWith<YorkieException> { client.detachChannel(channel).await() }
        }
    }

    // Ports the JS `87a3802f9` (#1522) integration case `restarts channel-only activation after
    // a completed deactivate`: a completed deactivate leaves `deactivating` set, which used to
    // make the sync loop of the NEXT channel attach exit at its first tick, so the channel
    // never refreshed. A channel attach after a completed teardown starts a fresh lazy lifecycle.
    @Test
    fun `restarts channel-only activation after a completed deactivate`() = runTest {
        runBlocking {
            val firstRefresh = CompletableDeferred<Unit>()
            service.refreshChannelHook = {
                firstRefresh.complete(Unit)
                null
            }
            val first = Channel("restart-first")
            // Never activated: the first refresh activates the client lazily.
            // Realtime (the default): the sync loop performs the first refresh.
            client.attachChannel(first).await()
            withTimeout(5_000) { firstRefresh.await() }
            withTimeout(5_000) {
                while (!client.isActive || first.getStatus() != ResourceStatus.Attached) {
                    delay(10)
                }
            }

            client.deactivateAsync().await()
            assertFalse(client.isActive)
            assertEquals(ResourceStatus.Detached, first.getStatus())

            val secondRefresh = CompletableDeferred<Unit>()
            service.refreshChannelHook = {
                secondRefresh.complete(Unit)
                null
            }
            val second = Channel("restart-second")
            client.attachChannel(second).await()
            withTimeout(3_000) { secondRefresh.await() }
            withTimeout(5_000) {
                while (!client.isActive || second.getStatus() != ResourceStatus.Attached) {
                    delay(10)
                }
            }

            assertFalse(client.deactivating, "the fresh lifecycle is not marked as deactivating")
            assertEquals(MOCK_SESSION_ID, second.getSessionId())
            assertTrue(client.has("restart-second"))
            assertTrue(client.syncAsync(second).await().isSuccess)
        }
    }
}
