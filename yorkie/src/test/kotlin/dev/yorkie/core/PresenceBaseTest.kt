package dev.yorkie.core

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import dev.yorkie.api.PBPresenceChangeType
import dev.yorkie.api.v1.AttachDocumentRequest
import dev.yorkie.api.v1.DetachDocumentRequest
import dev.yorkie.api.v1.PushPullChangesRequest
import dev.yorkie.api.v1.RemoveDocumentRequest
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_USER_ID
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.VersionVector
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ports yorkie-js-sdk `presence_base_test.ts` (`b50f8900`, #1477)'s three client-level cases
 * as JVM unit tests of `Client`'s `ErrPresenceBaseUnavailable` retry wrapper (D4, spec 040):
 * on that ONE code, the wrapper rewrites the earliest pending presence patch into a full put,
 * rebuilds the pack, and resends exactly once; any other error propagates with exactly one RPC
 * call. The Document-level building blocks the wrapper calls
 * ([dev.yorkie.document.Document.restorePresencePatchesAndRebuildPack], via its private
 * `restorePresencePatches`/`resetPresenceBase`) are covered as JS cases 7, 8, 16 in
 * [dev.yorkie.document.PresencePatchTest] instead -- this file is Client-only.
 *
 * Every test seeds the document's base-tracking state directly (an initial `put` + an `ack`
 * advertising the capability, then a second `put`), exactly like [dev.yorkie.document.PresencePatchTest],
 * so the document already holds a pending [dev.yorkie.document.presence.PresenceChange.Patch]
 * BEFORE the real (mocked) `Client` call that exercises the retry wrapper runs -- this sidesteps
 * JS's own test setup (restoring a document from persisted bytes), which cannot reproduce a queued
 * Patch on Android: D1 (spec 040) already rewrites every persisted Patch to a Put before either
 * persist encoder ever serializes it.
 */
class PresenceBaseTest {

    private lateinit var service: MockYorkieService
    private lateinit var client: Client

    @Before
    fun setUp() {
        service = spyk(MockYorkieService())
        client = Client(
            options = Client.Options(
                key = TEST_KEY,
                apiKey = TEST_KEY,
                metadata = mapOf("user-id" to TEST_USER_ID),
            ),
            host = "0.0.0.0",
        )
        client.service = service
    }

    @After
    fun tearDown() {
        client.close()
    }

    /** Hands [document] a server pack that acknowledges everything pushed so far. */
    private suspend fun ack(document: Document, capabilities: List<String>) {
        val pending = document.createChangePack().changes
        val lastSeq = pending.lastOrNull()?.id?.clientSeq ?: 0u
        document.applyChangePack(
            ChangePack(
                document.getKey(),
                CheckPoint(0, lastSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
                epoch = 0,
                capabilities = capabilities,
            ),
        )
    }

    /**
     * Seeds [document] with exactly one pending [dev.yorkie.document.presence.PresenceChange.Patch]
     * -- a Put (the base), acked with the capability advertised, then a second Put that commits as
     * a Patch -- entirely independent of any `Client`.
     */
    private suspend fun seedPendingPatch(document: Document) {
        document.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(document, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        document.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
    }

    private fun presenceTypeOf(pack: dev.yorkie.api.v1.ChangePack, index: Int = 0) =
        pack.changesList[index].presenceChange.type

    // --- presence_base 1 ---------------------------------------------------------

    @Test
    fun `resends an attach pack with a full put when the base is gone`() = runTest {
        val document = Document("presence-base-attach")
        seedPendingPatch(document)

        service.presenceBaseUnavailableOnceKeys += document.getKey()
        service.presencePatchCapableKeys += document.getKey()

        client.activateAsync().await()
        val result = client.attachDocument(
            document,
            syncMode = Manual,
            disablePresence = true,
        ).await()
        assertTrue(result.isSuccess)

        val sentPacks = mutableListOf<AttachDocumentRequest>()
        coVerify(exactly = 2) { service.attachDocument(capture(sentPacks), any()) }

        assertEquals(
            PBPresenceChangeType.CHANGE_TYPE_PATCH,
            presenceTypeOf(sentPacks[0].changePack),
        )
        assertEquals(
            PBPresenceChangeType.CHANGE_TYPE_PUT,
            presenceTypeOf(sentPacks[1].changePack),
        )
        assertEquals(
            setOf("name", "cursor"),
            sentPacks[1].changePack.changesList.single().presenceChange.presence.dataMap.keys,
        )

        client.deactivateAsync().await()
    }

    // --- presence_base 2 ---------------------------------------------------------

    @Test
    fun `resends a push-pull pack with a full put when the base is gone`() = runTest {
        val document = Document("presence-base-sync")
        seedPendingPatch(document)

        service.presencePatchCapableKeys += document.getKey()
        client.activateAsync().await()
        client.attachDocument(
            document,
            syncMode = Manual,
            disablePresence = true,
        ).await()
        // Nothing of the attach's own flow touches the pending patch: disablePresence=true
        // skips its internal presence seed, and the default attach response's unset
        // checkpoint acks nothing, so the single queued Patch rides out on the sync below.
        assertTrue(document.isPresencePatchableForTest())

        service.presenceBaseUnavailableOnceKeys += document.getKey()
        val syncResult = client.syncAsync(document).await()
        assertTrue(syncResult.isSuccess)

        val sentPacks = mutableListOf<PushPullChangesRequest>()
        coVerify(exactly = 2) { service.pushPullChanges(capture(sentPacks), any()) }

        assertEquals(
            PBPresenceChangeType.CHANGE_TYPE_PATCH,
            presenceTypeOf(sentPacks[0].changePack),
        )
        assertEquals(
            PBPresenceChangeType.CHANGE_TYPE_PUT,
            presenceTypeOf(sentPacks[1].changePack),
        )
        assertEquals(
            setOf("name", "cursor"),
            sentPacks[1].changePack.changesList.single().presenceChange.presence.dataMap.keys,
        )

        client.deactivateAsync().await()
    }

    // --- presence_base 3 ---------------------------------------------------------

    @Test
    fun `propagates an error that is not a lost presence base with exactly one RPC`() = runTest {
        val document = Document("presence-base-other-error")

        coEvery { service.attachDocument(any(), any()) } returns ResponseMessage.Failure(
            ConnectException(Code.UNKNOWN),
            emptyMap(),
            emptyMap(),
        )

        client.activateAsync().await()
        val result = client.attachDocument(document, syncMode = Manual).await()
        assertFalse(result.isSuccess)

        coVerify(exactly = 1) { service.attachDocument(any(), any()) }
    }

    // --- Android-only (generator/027): the detach site, queue ending in Clear ------

    @Test
    fun `resends a detach pack ending in Clear with a full put, without looping`() = runTest {
        val document = Document("presence-base-detach")
        seedPendingPatch(document)

        service.presencePatchCapableKeys += document.getKey()
        client.activateAsync().await()
        client.attachDocument(
            document,
            syncMode = Manual,
            disablePresence = true,
        ).await()
        // The attach call above only used disablePresence=true to skip attach's OWN
        // internal presence seed (which would otherwise queue an extra Put ahead of the
        // patch seedPendingPatch already minted); undo it now so detach's own presence
        // clear below is not ALSO silently dropped (Document.kt:824 -- disablePresence
        // drops every presence-only change from updateAsync, not just attach's own).
        document.setDisablePresence(false)

        // A detach commits a presence Clear ahead of its own pack build (unless minting is
        // suspended) -- the queue the detach RPC actually sends is [Patch, Clear], not the
        // bare Patch seedPendingPatch minted (P3-iv, spec 040 probe ii: the retry must not
        // loop when the rewritten pack still ends in a change the server could still refuse
        // for an unrelated reason).
        service.presenceBaseUnavailableOnceKeys += document.getKey()
        val result = client.detachDocument(document).await()
        assertTrue(result.isSuccess)

        val sentPacks = mutableListOf<DetachDocumentRequest>()
        coVerify(exactly = 2) { service.detachDocument(capture(sentPacks), any()) }

        assertEquals(2, sentPacks[0].changePack.changesCount)
        assertEquals(
            PBPresenceChangeType.CHANGE_TYPE_PATCH,
            presenceTypeOf(sentPacks[0].changePack, index = 0),
        )
        assertEquals(
            PBPresenceChangeType.CHANGE_TYPE_CLEAR,
            presenceTypeOf(sentPacks[0].changePack, index = 1),
        )
        assertEquals(
            PBPresenceChangeType.CHANGE_TYPE_PUT,
            presenceTypeOf(sentPacks[1].changePack, index = 0),
        )
        assertEquals(
            PBPresenceChangeType.CHANGE_TYPE_CLEAR,
            presenceTypeOf(sentPacks[1].changePack, index = 1),
        )

        client.deactivateAsync().await()
    }

    // --- Android-only: the remove site ---------------------------------------------

    @Test
    fun `resends a remove pack with a full put when the base is gone`() = runTest {
        val document = Document("presence-base-remove")
        seedPendingPatch(document)

        service.presencePatchCapableKeys += document.getKey()
        client.activateAsync().await()
        client.attachDocument(
            document,
            syncMode = Manual,
            disablePresence = true,
        ).await()

        service.presenceBaseUnavailableOnceKeys += document.getKey()
        val result = client.removeDocument(document).await()
        assertTrue(result.isSuccess)

        val sentPacks = mutableListOf<RemoveDocumentRequest>()
        coVerify(exactly = 2) { service.removeDocument(capture(sentPacks), any()) }

        assertEquals(
            PBPresenceChangeType.CHANGE_TYPE_PATCH,
            presenceTypeOf(sentPacks[0].changePack),
        )
        assertEquals(
            PBPresenceChangeType.CHANGE_TYPE_PUT,
            presenceTypeOf(sentPacks[1].changePack),
        )
    }
}
