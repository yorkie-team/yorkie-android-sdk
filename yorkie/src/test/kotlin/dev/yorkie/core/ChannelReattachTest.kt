package dev.yorkie.core

import dev.yorkie.document.Document
import dev.yorkie.presence.Channel
import dev.yorkie.util.YorkieException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Ports the JS SDK channel cases of `ba56ec053` (#1527): `describe 're-attaching
 * the same key while the previous detach is pending'` (`channel_test.ts`) and
 * `deactivate_orphan_attachment_test.ts`.
 *
 * Android determinations (see the PR body): `attachChannel` refuses a key that
 * is still in `attachments`, and a pending detach keeps its entry until
 * `detachInternal` runs, so JS cases 1-3 cannot arise as written. They are
 * pinned here in their Android form (the new attach is refused while the old
 * detach is pending, accepted once it ends). JS case 4 (detach through a stale
 * Channel) is reachable on Android and is the behavioural change of this fix.
 */
class ChannelReattachTest {
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

    @Test
    fun `keeps the new channel when the old detach runs before its first refresh`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val first = Channel(KEY)
            val second = Channel(KEY)

            client.attachChannel(first, isRealtime = false).await()
            client.detachChannel(first).await()
            client.attachChannel(second, isRealtime = false).await()

            assertTrue(client.has(KEY))
            assertTrue(client.syncAsync(second).await().isSuccess)
            assertEquals(ResourceStatus.Attached, second.getStatus())
            assertEquals(ResourceStatus.Detached, first.getStatus())
        }
    }

    @Test
    fun `keeps the new channel when it attaches before the old detach ends`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            service.refreshChannelHook = {
                entered.complete(Unit)
                release.await()
                null // fall through to the mock's own refresh response
            }
            val first = Channel(KEY)
            val second = Channel(KEY)
            client.attachChannel(first, isRealtime = false).await()

            // The first refresh holds the channel mutex, so the detach below stays pending.
            val refresh = client.syncAsync(first)
            entered.await()
            val detach = client.detachChannel(first)

            // The key is still held by the detaching attachment: the attach is refused.
            val refused = assertFailsWith<YorkieException> {
                client.attachChannel(second, isRealtime = false).await()
            }
            assertEquals(YorkieException.Code.ErrDocumentNotDetached, refused.code)

            release.complete(Unit)
            detach.await()
            refresh.await()
            assertFalse(client.has(KEY))
            assertNull(first.getSessionId(), "the late refresh must not resurrect the session")
            assertEquals(ResourceStatus.Detached, first.getStatus())

            service.refreshChannelHook = null
            client.attachChannel(second, isRealtime = false).await()
            assertTrue(client.has(KEY))
            assertTrue(client.syncAsync(second).await().isSuccess)
            assertEquals(ResourceStatus.Attached, second.getStatus())
        }
    }

    @Test
    fun `detaches both when the new channel goes while the old detach is held`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            service.refreshChannelHook = {
                entered.complete(Unit)
                release.await()
                null
            }
            val first = Channel(KEY)
            val second = Channel(KEY)
            client.attachChannel(first, isRealtime = false).await()
            val refresh = client.syncAsync(first)
            entered.await()
            val detach = client.detachChannel(first)

            release.complete(Unit)
            detach.await()
            refresh.await()
            service.refreshChannelHook = null
            client.attachChannel(second, isRealtime = false).await()
            client.detachChannel(second).await()

            assertFalse(client.has(KEY))
            assertEquals(ResourceStatus.Detached, first.getStatus())
            assertEquals(ResourceStatus.Detached, second.getStatus())
        }
    }

    @Test
    fun `refuses to detach through a stale Channel of the key`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val first = Channel(KEY)
            val second = Channel(KEY)
            client.attachChannel(first, isRealtime = false).await()
            client.detachChannel(first).await()
            client.attachChannel(second, isRealtime = false).await()
            assertTrue(client.syncAsync(second).await().isSuccess)

            val exception = assertFailsWith<YorkieException> {
                client.detachChannel(first).await()
            }
            assertEquals(YorkieException.Code.ErrDocumentNotAttached, exception.code)

            assertTrue(client.has(KEY), "the new channel stays attached")
            assertEquals(ResourceStatus.Attached, second.getStatus())
            assertTrue(client.syncAsync(second).await().isSuccess)

            client.detachChannel(second).await()
            assertFalse(client.has(KEY))
        }
    }

    @Test
    fun `refuses to detach a channel through a key held by a document`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val document = Document(MockYorkieService.NORMAL_DOCUMENT_KEY)
            client.attachDocument(document).await()

            val exception = assertFailsWith<YorkieException> {
                client.detachChannel(Channel(MockYorkieService.NORMAL_DOCUMENT_KEY)).await()
            }
            assertEquals(YorkieException.Code.ErrDocumentNotAttached, exception.code)

            assertTrue(client.has(MockYorkieService.NORMAL_DOCUMENT_KEY))
            assertEquals(ResourceStatus.Attached, document.getStatus())
        }
    }

    // Android reaches the eviction in the opposite direction from JS: `attachChannel` checks the
    // key BEFORE it takes the per-key mutex, and an in-flight `attachDocument` of the same key
    // holds that mutex (and has not registered yet). The channel attach passes the check, waits
    // for the document to register, and then writes over it.
    @Test
    fun `tears down an attachment a same-key attach evicted from the map`() = runTest {
        runBlocking {
            client.activateAsync().await()
            val key = MockYorkieService.NORMAL_DOCUMENT_KEY
            val document = Document(key)
            val channel = Channel(key)

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            service.attachDocumentHook = {
                entered.complete(Unit)
                release.await()
                null
            }
            val documentAttach = client.attachDocument(document)
            entered.await() // the document attach is now parked on its RPC
            val channelAttach = client.attachChannel(channel, isRealtime = false)
            release.complete(Unit)
            assertTrue(documentAttach.await().isSuccess)
            channelAttach.await()
            assertEquals(ResourceStatus.Attached, document.getStatus(), "the document attached")

            client.deactivateAsync().await()

            assertEquals(
                ResourceStatus.Detached,
                document.getStatus(),
                "deactivate must reach the attachment the channel attach evicted",
            )
            assertEquals(ResourceStatus.Detached, channel.getStatus())
            assertFalse(client.has(key))
        }
    }

    private companion object {
        const val KEY = "reattach-channel"
    }
}
