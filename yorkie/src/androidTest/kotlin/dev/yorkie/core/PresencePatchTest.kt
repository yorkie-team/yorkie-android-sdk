package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented, multi-client happy path for spec 040 (presence as a patch, `b50f8900`
 * #1477) against a real Yorkie server (compose pin `yorkieteam/yorkie:0.7.25`, which
 * advertises `presence-patch`): once a client has a base (a full Put) and the server's
 * advertised the capability, a one-key presence update rides the wire as a PATCH -- the
 * server folds it into a stored full Put, so it never reaches a peer as a bare one-key
 * map.
 *
 * C12's admin `GetDocuments` server-root oracle ([PreAttachTest]'s own pattern) was
 * attempted here first (both its default shape and an `includePresences: true` request)
 * but this server build's `GetDocuments` response never carries a `presences` field
 * either way -- disclosed, not guessed further; see the round build report. Instead,
 * [clientC] -- attached AFTER the patch has already landed, with NO prior relationship to
 * [clientA]'s presence -- is the oracle: a fresh attach pulls whatever the server is
 * CURRENTLY storing cold, so if the server had folded the patch wrong (e.g. dropped
 * "name", kept only the patch's own "cursor" key), [clientC]'s first pull would show
 * exactly that, where [clientB]'s own incremental client-side merge could mask it
 * (parity-port-review-blind-spot: a client-side merge can paper over a server-side
 * storage bug that a cold, independent read exposes).
 */
@RunWith(AndroidJUnit4::class)
class PresencePatchTest {

    private fun newDocKey(name: String) = "$name-${UUID.randomUUID()}".toDocKey()

    @Test
    fun test_sends_a_presence_patch_once_the_base_and_capability_are_both_there() = runBlocking {
        val documentKey = newDocKey("presence-patch")
        val clientA = createClient()
        val clientB = createClient()
        clientA.activateAsync().await().getOrThrow()
        clientB.activateAsync().await().getOrThrow()

        val docA = Document(documentKey)
        val docB = Document(documentKey)

        // (1) A attaches with an initial presence -- the base, a full Put.
        clientA.attachDocument(
            docA,
            initialPresence = mapOf("name" to "a"),
            syncMode = Client.SyncMode.Manual,
        ).await().getOrThrow()
        // A sync round trip reads the server's advertised capability off a fresh pack
        // (attach's own response may not carry a fresh checkpoint for the ack).
        clientA.syncAsync(docA).await().getOrThrow()
        assertTrue(docA.isPresencePatchableForTest())

        // (2) B attaches and observes A's base.
        clientB.attachDocument(docB, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        assertEquals(mapOf("name" to "a"), docB.allPresences.value[clientA.requireActorId()])

        // (3) A updates one key -- the commit-time decision now has both capability and
        // base, so this rides the wire as a PATCH, not a full Put.
        docA.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await().getOrThrow()
        clientA.syncAsync(docA).await().getOrThrow()

        // (4) B pulls -- its presence event folds the PATCH onto what it already held.
        clientB.syncAsync(docB).await().getOrThrow()
        val expected = mapOf("name" to "a", "cursor" to "1,1")
        assertEquals(expected, docB.allPresences.value[clientA.requireActorId()])

        // (5) C attaches cold, AFTER the patch landed, with no prior presence for A of
        // its own to merge onto -- whatever it sees on its very first pull is exactly
        // what the server folded and stored, proving the server holds the full map, not
        // just the patch's one key.
        val clientC = createClient()
        clientC.activateAsync().await().getOrThrow()
        val docC = Document(documentKey)
        clientC.attachDocument(docC, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        assertEquals(expected, docC.allPresences.value[clientA.requireActorId()])

        clientA.detachDocument(docA).await().getOrThrow()
        clientB.detachDocument(docB).await().getOrThrow()
        clientC.detachDocument(docC).await().getOrThrow()
        clientA.deactivateAsync().await().getOrThrow()
        clientB.deactivateAsync().await().getOrThrow()
        clientC.deactivateAsync().await().getOrThrow()
        docA.close()
        docB.close()
        docC.close()
        clientA.close()
        clientB.close()
        clientC.close()
    }
}
