package dev.yorkie.core

import android.util.Base64
import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import dev.yorkie.core.MockYorkieService.Companion.TEST_ACTOR_ID
import dev.yorkie.document.Document
import dev.yorkie.document.time.ActorID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `reissue_claim_test.ts` (`ed25355e`, #1449,
 * yorkie#2111), minus CSPRNG refusal (N/A -- `UUID.randomUUID()` is
 * SecureRandom-backed, `Client.kt:191`) and the bad-attach-option case (N/A
 * -- kept instead as "a duplicate or non-detached attach leaves the doc
 * untouched", already covered by `ClientTest`'s own attach-guard tests).
 *
 * Every document under test carries a local "obj" key written BEFORE
 * attach, so [reissued] can read the re-issue's effect directly off the
 * content: a Set/AddOperation's `setActor` call (the plain, non-reissuing
 * path) rewrites only the operation's OWN top-level tickets, never the
 * nested VALUE's `createdAt` -- so an un-reissued "obj" keeps
 * [ActorID.INITIAL_ACTOR_ID] as its createdAt actor even after the
 * document's `changeID.actor` has moved to the client's resolved actor.
 */
class ReissueClaimTest {

    private val clients = mutableListOf<Client>()

    // toChangePack()/applyChangePack() encode a non-empty VersionVector via
    // android.util.Base64, an unmocked stub under plain JVM unit tests (same
    // bridge as SnapshotConverterTest/SetActorReissueTest).
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
        clients.forEach { it.close() }
        unmockkStatic(Base64::class)
    }

    private fun newClient(key: String? = null): Client {
        val options = if (key != null) Client.Options(key = key) else Client.Options()
        val client = Client(options = options, host = "0.0.0.0")
        client.service = spyk(MockYorkieService())
        clients += client
        return client
    }

    private suspend fun neverSyncedDocument(key: String): Document {
        val document = Document(key)
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        return document
    }

    private fun reissued(document: Document): Boolean {
        val obj = document.getRootObject()["obj"]
        return obj.createdAt.actorID != ActorID.INITIAL_ACTOR_ID
    }

    @Test
    fun `re-issues a never-synced document's tickets to the client actor`() = runTest {
        val client = newClient()
        client.activateAsync().await().getOrThrow()
        val document = neverSyncedDocument("reissue-basic")

        client.attachDocument(document).await().getOrThrow()

        assertEquals(TEST_ACTOR_ID, document.changeID.actor)
        assertEquals(true, reissued(document))
    }

    @Test
    fun `does not re-issue a document of an explicit client key`() = runTest {
        val client = newClient(key = "an-explicit-client-key")
        client.activateAsync().await().getOrThrow()
        val document = neverSyncedDocument("reissue-explicit-key")

        client.attachDocument(document).await().getOrThrow()

        assertEquals(TEST_ACTOR_ID, document.changeID.actor, "the plain rename still runs")
        assertEquals(false, reissued(document), "an explicit key never re-issues")
    }

    @Test
    fun `declines a second never-synced document of the same key`() = runTest {
        val client = newClient()
        client.activateAsync().await().getOrThrow()
        val documentKey = "reissue-same-key"

        val first = neverSyncedDocument(documentKey)
        client.attachDocument(first).await().getOrThrow()
        assertEquals(true, reissued(first), "the first document takes the claim")
        client.detachDocument(first).await().getOrThrow()

        val second = neverSyncedDocument(documentKey)
        client.attachDocument(second).await().getOrThrow()

        assertEquals(
            false,
            reissued(second),
            "a second never-synced document of the SAME key must be declined",
        )
    }

    @Test
    fun `still re-issues a document of another key`() = runTest {
        val client = newClient()
        client.activateAsync().await().getOrThrow()

        val first = neverSyncedDocument("reissue-key-a")
        client.attachDocument(first).await().getOrThrow()
        assertEquals(true, reissued(first))

        val second = neverSyncedDocument("reissue-key-b")
        client.attachDocument(second).await().getOrThrow()

        assertEquals(true, reissued(second), "a different document key is unaffected by the claim")
    }

    @Test
    fun `a document that minted while attached takes the claim on detach`() = runTest {
        val client = newClient()
        client.activateAsync().await().getOrThrow()
        val documentKey = "reissue-mint-on-attach"

        // Attached with NO local changes pre-attach (truly empty -- no "obj"
        // key at all): the plain path runs (nothing to re-issue), so
        // hasMintedOperations() is false at attach time and the claim is NOT
        // taken there.
        val first = Document(documentKey)
        client.attachDocument(first).await().getOrThrow()

        // Mint an operation AFTER attach -- hasMintedOperations() now holds,
        // so detach must take the claim even though attach itself did not.
        first.updateAsync { root, _ -> root.setNewObject("late") }.await()
        client.detachDocument(first).await().getOrThrow()

        val second = neverSyncedDocument(documentKey)
        client.attachDocument(second).await().getOrThrow()

        assertEquals(
            false,
            reissued(second),
            "the first document's post-attach mint must still take the claim on detach",
        )
    }

    @Test
    fun `a retried attach under a different actor re-issues again`() = runTest {
        val documentKey = "reissue-retry-different-actor"
        val firstClient = newClient()
        firstClient.activateAsync().await().getOrThrow()
        val first = neverSyncedDocument(documentKey)
        firstClient.attachDocument(first).await().getOrThrow()
        assertEquals(true, reissued(first))
        assertEquals(TEST_ACTOR_ID, first.changeID.actor)
        firstClient.detachDocument(first).await().getOrThrow()

        // A second Client activation resolving to a DIFFERENT actor,
        // attaching a fresh never-synced document of the SAME key: its own
        // claim set starts empty (a per-Client claim, not global), so it
        // re-issues too, under its own actor.
        val secondActor = "0000000000000000000000cc"
        val secondClient = newClient()
        (secondClient.service as MockYorkieService).activateResponseActorId = secondActor
        secondClient.activateAsync().await().getOrThrow()
        val second = neverSyncedDocument(documentKey)

        secondClient.attachDocument(second).await().getOrThrow()

        assertEquals(true, reissued(second))
        assertEquals(secondActor, second.changeID.actor)
        assertNotEquals(first.changeID.actor, second.changeID.actor)
    }

    // LOW-3 fold-in (cross-judge CJ-8): the claim is taken BEFORE the RPC
    // (Client.kt:1994-2000), so an attach cancelled/failed mid-RPC has
    // already re-issued; the retry must not re-issue a second time and must
    // leave the document in a consistent, already-reissued state.
    @Test
    fun `attach failed mid-RPC does not re-issue twice on retry`() = runTest {
        val client = newClient()
        client.activateAsync().await().getOrThrow()
        val document = neverSyncedDocument("reissue-cancel-retry")
        val mock = client.service as MockYorkieService
        var firstCall = true
        coEvery { mock.attachDocument(any(), any()) } coAnswers {
            if (firstCall) {
                firstCall = false
                ResponseMessage.Failure(ConnectException(Code.UNAVAILABLE), emptyMap(), emptyMap())
            } else {
                callOriginal()
            }
        }

        assertTrue(client.attachDocument(document).await().isFailure)
        assertEquals(TEST_ACTOR_ID, document.changeID.actor, "the pre-RPC reissue already ran")
        assertEquals(true, reissued(document))

        assertTrue(client.attachDocument(document).await().isSuccess)

        assertEquals(TEST_ACTOR_ID, document.changeID.actor, "no second reissue moved the actor")
        assertEquals(true, reissued(document))
    }

    /** Same reflection technique as `ClientTest.forceStatus`: not shared across files. */
    @Suppress("UNCHECKED_CAST")
    private fun Client.forceStatus(status: Client.Status) {
        val field = Client::class.java.getDeclaredField("_status")
        field.isAccessible = true
        (field.get(this) as MutableStateFlow<Client.Status>).value = status
    }

    // M4 (team-review, RTCOLLABPLATFORM-789): the keepalive detach path
    // (`Client.detachDocument(keepalive = true)`'s GlobalScope task) can resume its
    // `detachInternal` call AFTER a concurrent `deactivateAsync()` has already flipped
    // status away from Activated -- `markReissueClaim(actorId ?: requireClientId(), key)`
    // used to throw `ErrClientNotActivated` there, uncaught, on a cleanup path that must
    // not fail. `detachInternal` is private, so this reflects into it directly (same
    // technique as `ClientTest.forceStatus`) rather than racing the public API, whose own
    // EARLIER `requireClientId()` call (building the detach RPC request) would otherwise
    // fail first and mask whether this fix's line was ever reached.
    @Suppress("UNCHECKED_CAST")
    private suspend fun Client.callDetachInternal(key: String) {
        val method = Client::class.java.getDeclaredMethod(
            "detachInternal",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            kotlin.coroutines.Continuation::class.java,
        )
        method.isAccessible = true
        kotlin.coroutines.suspendCoroutine<Unit> { cont ->
            val continuation = object : kotlin.coroutines.Continuation<Any?> {
                override val context = cont.context
                override fun resumeWith(result: Result<Any?>) {
                    cont.resumeWith(result.map { })
                }
            }
            val invokeResult = method.invoke(this, key, true, continuation)
            // COROUTINE_SUSPENDED sentinel means the suspend fun itself will resume
            // `continuation` later; any other return means it completed synchronously.
            if (invokeResult !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                cont.resumeWith(Result.success(Unit))
            }
        }
    }

    @Test
    fun `keepalive detach does not throw when it resumes after a concurrent deactivate`() =
        runTest {
            val client = newClient()
            client.activateAsync().await().getOrThrow()
            val document = neverSyncedDocument("reissue-keepalive-detach-race")
            // Manual: no background watch job to race forceStatus below -- isolates
            // this test to exactly the markReissueClaim line under test.
            client.attachDocument(document, syncMode = Client.SyncMode.Manual)
                .await().getOrThrow()
            assertEquals(true, document.hasMintedOperations())

            // Simulates deactivateAsync() having already flipped status by the time this
            // keepalive detach's GlobalScope task resumes -- the exact race the fix
            // guards (status checked via the non-throwing actorId/clientId chain, not
            // requireClientId()).
            client.forceStatus(Client.Status.Deactivated)

            // Must not throw ErrClientNotActivated out of markReissueClaim.
            client.callDetachInternal(document.getKey())
        }
}
