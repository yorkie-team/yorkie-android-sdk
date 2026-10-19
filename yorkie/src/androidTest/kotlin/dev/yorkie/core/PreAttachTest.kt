package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.Document
import dev.yorkie.document.time.ActorID
import dev.yorkie.gson
import dev.yorkie.util.postApi
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented port of yorkie-js-sdk's `integration/pre_attach_test.ts`
 * (`ed25355e`, #1449, yorkie#2111), the 4 cases, against a real Yorkie
 * server (compose pin `yorkieteam/yorkie:0.7.24`). `Client.SyncMode.Manual`
 * everywhere, matching the JS fixture's own `{ syncMode: SyncMode.Manual }`.
 *
 * Oracle: [creatorOf] reads a root element's OWN `createdAt` actor (not
 * `changeID.actor`, which the PLAIN rename also rewrites) -- the same
 * distinction [dev.yorkie.core.ReissueClaimTest]'s `reissued` helper relies
 * on -- plus [serverRoot] (admin `GetDocuments`), the same oracle pattern as
 * [SurrogateIndexServerRootTest].
 */
@RunWith(AndroidJUnit4::class)
class PreAttachTest {

    private val http = OkHttpClient()

    private fun newDocKey(name: String) = "$name-${UUID.randomUUID()}".toDocKey()

    private fun creatorOf(document: Document, key: String): String {
        return document.getRootObject()[key].createdAt.actorID
    }

    /** The server's own replayed root for [documentKey], via admin `GetDocuments`. */
    private fun serverRoot(documentKey: String): String {
        val yorkieServerUrl = getYorkieServerUrl()
        val adminToken = http.postApi<Map<String, Any>>(
            url = "$yorkieServerUrl/yorkie.v1.AdminService/LogIn",
            requestMap = mapOf("username" to TEST_API_ID, "password" to TEST_API_PW),
            gson = gson,
        )["token"] as String
        val listProjectsResponse = http.postApi<Map<String, Any>>(
            url = "$yorkieServerUrl/yorkie.v1.AdminService/ListProjects",
            headers = mapOf("Authorization" to "Bearer $adminToken"),
            requestMap = emptyMap(),
            gson = gson,
        )

        @Suppress("UNCHECKED_CAST")
        val secretKey = (listProjectsResponse["projects"] as List<Map<String, Any>>)
            .first { it["name"] == "default" }["secretKey"] as String

        val documentsResponse = http.postApi<Map<String, Any>>(
            url = "$yorkieServerUrl/yorkie.v1.AdminService/GetDocuments",
            headers = mapOf("Authorization" to "API-Key $secretKey"),
            requestMap = mapOf("documentKeys" to listOf(documentKey), "includeRoot" to true),
            gson = gson,
        )

        @Suppress("UNCHECKED_CAST")
        val documents = documentsResponse["documents"] as List<Map<String, Any>>
        return documents.first()["root"] as String
    }

    @Test
    fun test_converges_two_clients_that_filled_the_same_key_before_attach() = runBlocking {
        val documentKey = newDocKey("reissue-converge")
        val client1 = createClient()
        val client2 = createClient()
        client1.activateAsync().await().getOrThrow()
        client2.activateAsync().await().getOrThrow()

        val doc1 = Document(documentKey)
        val doc2 = Document(documentKey)
        doc1.updateAsync { root, _ ->
            root.setNewText(
                "k1",
            ).edit(0, 0, "client1")
        }.await().getOrThrow()
        doc2.updateAsync { root, _ ->
            root.setNewText(
                "k1",
            ).edit(0, 0, "client2")
        }.await().getOrThrow()

        client1.attachDocument(doc1, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        client2.attachDocument(doc2, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        client1.syncAsync(doc1).await().getOrThrow()
        client2.syncAsync(doc2).await().getOrThrow()
        client1.syncAsync(doc1).await().getOrThrow()

        assertJsonContentEquals(doc1.toJson(), doc2.toJson())
        // Neither client's root keeps the initial actor -- both pushed
        // pre-attach tickets re-issued to their own real actor, so the
        // server never saw two elements sharing one createdAt.
        assertNotEquals(ActorID.INITIAL_ACTOR_ID, creatorOf(doc1, "k1"))
        val serverJson = serverRoot(documentKey)
        assertJsonContentEquals(serverJson, doc1.toJson())
        assertJsonContentEquals(serverJson, doc2.toJson())

        client1.detachDocument(doc1).await().getOrThrow()
        client2.detachDocument(doc2).await().getOrThrow()
        client1.deactivateAsync().await().getOrThrow()
        client2.deactivateAsync().await().getOrThrow()
        doc1.close()
        doc2.close()
        client1.close()
        client2.close()
    }

    @Test
    fun test_re_issues_pre_attach_tickets_to_the_client_actor() = runBlocking {
        val documentKey = newDocKey("reissue-basic")
        val c1 = createClient()
        val c2 = createClient()
        c1.activateAsync().await().getOrThrow()
        c2.activateAsync().await().getOrThrow()

        val doc = Document(documentKey)
        doc.updateAsync { root, _ -> root.setNewText("k1").edit(0, 0, "abc") }.await().getOrThrow()
        assertEquals(ActorID.INITIAL_ACTOR_ID, creatorOf(doc, "k1"))

        c1.attachDocument(doc, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        assertEquals(c1.requireActorId(), creatorOf(doc, "k1"))
        assertEquals("""{"k1":[{"val":"abc"}]}""", doc.toJson())

        val observed = Document(documentKey)
        c2.attachDocument(observed, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        assertJsonContentEquals(doc.toJson(), observed.toJson())
        assertEquals(c1.requireActorId(), creatorOf(observed, "k1"))

        c1.deactivateAsync().await().getOrThrow()
        c2.deactivateAsync().await().getOrThrow()
        doc.close()
        observed.close()
        c1.close()
        c2.close()
    }

    @Test
    fun test_does_not_re_issue_a_second_pre_attach_document_of_a_key() = runBlocking {
        // A re-issue keeps the lamports, and a fresh document starts them at
        // 1, so re-issuing this one to the same actor would mint the
        // createdAt the first attach already pushed -- the tickets keep the
        // initial actor instead.
        val documentKey = newDocKey("reissue-decline")
        val client = createClient()
        val observer = createClient()
        client.activateAsync().await().getOrThrow()
        observer.activateAsync().await().getOrThrow()

        val doc1 = Document(documentKey)
        doc1.updateAsync { root, _ ->
            root.setNewText(
                "first",
            ).edit(0, 0, "a")
        }.await().getOrThrow()
        client.attachDocument(doc1, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        assertEquals(client.requireActorId(), creatorOf(doc1, "first"))
        client.detachDocument(doc1).await().getOrThrow()

        val doc2 = Document(documentKey)
        doc2.updateAsync { root, _ ->
            root.setNewText(
                "second",
            ).edit(0, 0, "b")
        }.await().getOrThrow()
        client.attachDocument(doc2, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        assertEquals(ActorID.INITIAL_ACTOR_ID, creatorOf(doc2, "second"))

        val observed = Document(documentKey)
        observer.attachDocument(observed, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        assertEquals("""{"first":[{"val":"a"}],"second":[{"val":"b"}]}""", observed.toJson())

        client.deactivateAsync().await().getOrThrow()
        observer.deactivateAsync().await().getOrThrow()
        doc1.close()
        doc2.close()
        observed.close()
        client.close()
        observer.close()
    }

    @Test
    fun test_keeps_an_earlier_session_of_an_explicit_client_key() = runBlocking {
        // Every session of an explicit key shares its stable actor. A
        // re-issue in the second session would mint the tickets the first
        // session's edits already carry, so pre-attach tickets under an
        // explicit key keep the initial actor.
        val documentKey = newDocKey("reissue-explicit")
        val clientKey = "$documentKey-client"

        val first = createClient(Client.Options(key = clientKey))
        first.activateAsync().await().getOrThrow()
        val doc1 = Document(documentKey)
        first.attachDocument(doc1, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        doc1.updateAsync { root, _ ->
            root.setNewText(
                "first",
            ).edit(0, 0, "a")
        }.await().getOrThrow()
        first.syncAsync(doc1).await().getOrThrow()
        val firstActorId = first.requireActorId()
        first.deactivateAsync().await().getOrThrow()
        doc1.close()
        first.close()

        val second = createClient(Client.Options(key = clientKey))
        second.activateAsync().await().getOrThrow()
        assertEquals(firstActorId, second.requireActorId())
        val doc2 = Document(documentKey)
        doc2.updateAsync { root, _ ->
            root.setNewText(
                "second",
            ).edit(0, 0, "b")
        }.await().getOrThrow()
        second.attachDocument(doc2, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        assertEquals(ActorID.INITIAL_ACTOR_ID, creatorOf(doc2, "second"))

        val observer = createClient()
        observer.activateAsync().await().getOrThrow()
        val observed = Document(documentKey)
        observer.attachDocument(observed, syncMode = Client.SyncMode.Manual).await().getOrThrow()
        assertEquals("""{"first":[{"val":"a"}],"second":[{"val":"b"}]}""", observed.toJson())

        second.deactivateAsync().await().getOrThrow()
        observer.deactivateAsync().await().getOrThrow()
        doc2.close()
        observed.close()
        second.close()
        observer.close()
    }
}
