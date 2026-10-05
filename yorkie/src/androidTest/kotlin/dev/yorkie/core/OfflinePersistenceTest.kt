package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonText
import dev.yorkie.util.postApi
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Port of yorkie-js-sdk's `offline_persistence_test.ts` (`2291bf67`/#1338, RTCOLLABPLATFORM-771).
 * The release's headline two-client instrumented case: a [MemoryDocStore]-backed [Client] surviving
 * a simulated process death, and a store-backed resume re-anchoring after an offline force
 * compaction. Requires Yorkie server >= 0.7.20 (yorkie#1969, #1970).
 */
@RunWith(AndroidJUnit4::class)
class OfflinePersistenceTest {

    @get:Rule
    val retryRule = RetryRule(retryCount = 2)

    private val gson = Gson()
    private val http = OkHttpClient()

    private fun options(key: String, store: DocStore?) = Client.Options(key = key, docStore = store)

    @Test
    fun test_resumes_un_pushed_local_changes_after_a_simulated_reload() = runBlocking {
        val sharedStore = MemoryDocStore()
        val clientKey = "offline-resume-${UUID.randomUUID()}"
        val documentKey = UUID.randomUUID().toString().toDocKey()

        // Session 1: attach, sync "hello", edit " world" WITHOUT syncing, then abandon
        // (close without detach) — simulates a process death with un-pushed edits.
        val session1 = createClient(options(clientKey, sharedStore))
        session1.activateAsync().await()
        val doc1 = Document(documentKey)
        session1.attachDocument(doc1, syncMode = Client.SyncMode.Manual).await()
        doc1.updateAsync { root, _ -> root.setNewText("content").edit(0, 0, "hello") }.await()
        session1.syncAsync(doc1).await()
        doc1.updateAsync { root, _ ->
            root.getAs<JsonText>("content").edit(5, 5, " world")
        }.await()
        // close() itself drains the in-flight persist (bounded, spec 025 MEDIUM-1), so
        // this no longer needs a test-side poll of the store before proceeding.
        session1.close()

        // Session 2: same key + store restores "hello world" BEFORE any sync.
        val session2 = createClient(options(clientKey, sharedStore))
        session2.activateAsync().await()
        val doc2 = Document(documentKey)
        session2.attachDocument(doc2, syncMode = Client.SyncMode.Manual).await()
        assertEquals("hello world", doc2.getRoot().getAs<JsonText>("content").toString())

        session2.syncAsync(doc2).await()

        // Session 3: a fresh observer with NO store sees the re-pushed change (C9
        // convergence probe).
        val session3 = createClient()
        session3.activateAsync().await()
        val doc3 = Document(documentKey)
        session3.attachDocument(doc3, syncMode = Client.SyncMode.Manual).await()
        session3.syncAsync(doc3).await()
        assertEquals("hello world", doc3.getRoot().getAs<JsonText>("content").toString())

        session2.detachDocument(doc2).await()
        session2.deactivateAsync().await()
        session3.detachDocument(doc3).await()
        session3.deactivateAsync().await()
        doc1.close()
        doc2.close()
        doc3.close()
        session2.close()
        session3.close()
    }

    @Test
    fun test_re_anchors_a_store_backed_resume_after_an_offline_force_compaction() = runBlocking {
        val sharedStore = MemoryDocStore()
        val clientKey = "offline-reanchor-${UUID.randomUUID()}"
        val documentKey = UUID.randomUUID().toString().toDocKey()
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
        val defaultProject = (listProjectsResponse["projects"] as List<Map<String, Any>>)
            .first { it["name"] == "default" }
        val secretKey = defaultProject["secretKey"] as String

        fun compact() {
            http.postApi<Any>(
                url = "$yorkieServerUrl/yorkie.v1.AdminService/CompactDocumentByAdmin",
                headers = mapOf("Authorization" to "API-Key $secretKey"),
                requestMap = mapOf("documentKey" to documentKey, "force" to true),
                gson = gson,
            )
        }

        val session1 = createClient(options(clientKey, sharedStore))
        session1.activateAsync().await()
        val doc1 = Document(documentKey)
        session1.attachDocument(doc1, syncMode = Client.SyncMode.Manual).await()
        doc1.updateAsync { root, _ -> root.setNewText("content").edit(0, 0, "hello") }.await()
        session1.syncAsync(doc1).await()

        compact() // epoch 0 -> 1; session1 learns epoch 1 on its next sync below.
        session1.syncAsync(doc1).await()
        doc1.updateAsync { root, _ ->
            root.getAs<JsonText>("content").edit(5, 5, " world")
        }.await()
        session1.close() // abandoned with " world" unsynced, at epoch 1.

        compact() // epoch 1 -> 2, invalidating session1's persisted epoch.

        val session2 = createClient(options(clientKey, sharedStore))
        session2.activateAsync().await()
        val doc2 = Document(documentKey)
        val droppedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            doc2.events.filterIsInstance<Document.Event.LocalChangesDropped>().first()
        }
        // Must not throw: the stale-epoch/checkpoint attach re-anchors instead of
        // failing (round-2 QA HIGH-1: the real server returns ErrInvalidServerSeq
        // here, not ErrEpochMismatch — see Client.kt's attach recovery catch).
        session2.attachDocument(doc2, syncMode = Client.SyncMode.Manual).await()
        // Bounded so a regression (the re-anchor catch missing this error code again)
        // fails fast instead of hanging the instrumentation run (round-2 QA HIGH-1).
        val dropped = withTimeout(30_000) { droppedDeferred.await() }
        assertEquals(Document.Event.Reason.EpochReanchor, dropped.reason)
        assertTrue(dropped.changes.isNotEmpty())
        assertEquals("hello", doc2.getRoot().getAs<JsonText>("content").toString())

        doc2.updateAsync { root, _ ->
            root.getAs<JsonText>("content").edit(5, 5, " again")
        }.await()
        session2.syncAsync(doc2).await()
        assertEquals("hello again", doc2.getRoot().getAs<JsonText>("content").toString())

        session2.detachDocument(doc2).await()
        session2.deactivateAsync().await()
        doc1.close()
        doc2.close()
        session2.close()
    }

    /** Mirrors `Client.storeKey`: `"<apiKey>/<clientKey>/<docKey>"`, apiKey unset here (""). */
    private fun storeKeyFor(clientKey: String, docKey: String) = "/$clientKey/$docKey"

    @Test
    fun test_keeps_writing_appends_not_snapshots_while_editing() = runBlocking {
        val store = MemoryDocStore()
        val clientKey = "offline-append-${UUID.randomUUID()}"
        val documentKey = UUID.randomUUID().toString().toDocKey()
        val storeKey = storeKeyFor(clientKey, documentKey)

        val session = createClient(options(clientKey, store))
        session.activateAsync().await()
        val document = Document(documentKey)
        session.attachDocument(document, syncMode = Client.SyncMode.Manual).await()
        val base = awaitStored(store, storeKey)
        assertTrue(base.changes.isEmpty())

        document.updateAsync { root, _ -> root.setNewText("content").edit(0, 0, "a") }.await()
        document.updateAsync { root, _ -> root.getAs<JsonText>("content").edit(1, 1, "b") }.await()
        document.updateAsync { root, _ -> root.getAs<JsonText>("content").edit(2, 2, "c") }.await()

        val stored = awaitStoredWithChanges(store, storeKey, expectedCount = 3)
        assertEquals(3, stored.changes.size)
        assertTrue(stored.snapshot.contentEquals(base.snapshot))

        session.detachDocument(document).await()
        session.deactivateAsync().await()
        document.close()
        session.close()
    }

    @Test
    fun test_survives_a_reload_and_stays_pushable_afterwards() = runBlocking {
        val sharedStore = MemoryDocStore()
        val clientKey = "offline-reload-${UUID.randomUUID()}"
        val documentKey = UUID.randomUUID().toString().toDocKey()

        // A peer writes "from-peer".
        val peer = createClient()
        peer.activateAsync().await()
        val peerDoc = Document(documentKey)
        peer.attachDocument(peerDoc, syncMode = Client.SyncMode.Manual).await()
        peerDoc.updateAsync { root, _ -> root.setNewText("content").edit(0, 0, "from-peer") }
            .await()
        peer.syncAsync(peerDoc).await()

        // c1 syncs (pulls "from-peer"), then edits "+offline" WITHOUT syncing.
        val session1 = createClient(options(clientKey, sharedStore))
        session1.activateAsync().await()
        val doc1 = Document(documentKey)
        session1.attachDocument(doc1, syncMode = Client.SyncMode.Manual).await()
        session1.syncAsync(doc1).await()
        assertEquals("from-peer", doc1.getRoot().getAs<JsonText>("content").toString())
        doc1.updateAsync { root, _ ->
            root.getAs<JsonText>("content").edit(9, 9, "+offline")
        }.await()
        session1.close()

        // c2 (same key+store) restores "from-peer+offline", edits "+after", syncs.
        val session2 = createClient(options(clientKey, sharedStore))
        session2.activateAsync().await()
        val doc2 = Document(documentKey)
        session2.attachDocument(doc2, syncMode = Client.SyncMode.Manual).await()
        assertEquals(
            "from-peer+offline",
            doc2.getRoot().getAs<JsonText>("content").toString(),
        )
        doc2.updateAsync { root, _ ->
            root.getAs<JsonText>("content").edit(17, 17, "+after")
        }.await()
        session2.syncAsync(doc2).await()

        // The server-oracle: a fresh verifier client reads the server's own copy — proof
        // the restored-and-edited document is genuinely pushable, not merely locally
        // consistent (C10(b)).
        val verifier = createClient()
        verifier.activateAsync().await()
        val verifierDoc = Document(documentKey)
        verifier.attachDocument(verifierDoc, syncMode = Client.SyncMode.Manual).await()
        verifier.syncAsync(verifierDoc).await()
        assertEquals(
            "from-peer+offline+after",
            verifierDoc.getRoot().getAs<JsonText>("content").toString(),
        )

        peer.detachDocument(peerDoc).await()
        peer.deactivateAsync().await()
        session2.detachDocument(doc2).await()
        session2.deactivateAsync().await()
        verifier.detachDocument(verifierDoc).await()
        verifier.deactivateAsync().await()
        doc1.close()
        peerDoc.close()
        doc2.close()
        verifierDoc.close()
        peer.close()
        session2.close()
        verifier.close()
    }

    @Test
    fun test_allows_re_attaching_a_document_after_detaching_it() = runBlocking {
        val store = MemoryDocStore()
        val clientKey = "offline-reattach-${UUID.randomUUID()}"
        val documentKey = UUID.randomUUID().toString().toDocKey()
        val storeKey = storeKeyFor(clientKey, documentKey)

        val session = createClient(options(clientKey, store))
        session.activateAsync().await()
        val document = Document(documentKey)
        session.attachDocument(document, syncMode = Client.SyncMode.Manual).await()
        awaitStored(store, storeKey)

        session.detachDocument(document).await()
        assertEquals(null, store.load(storeKey))

        val document2 = Document(documentKey)
        val result = session.attachDocument(document2, syncMode = Client.SyncMode.Manual).await()
        assertTrue(result.isSuccess)

        session.detachDocument(document2).await()
        session.deactivateAsync().await()
        document.close()
        document2.close()
        session.close()
    }

    @Test
    fun test_allows_attaching_again_after_learning_the_document_was_removed() = runBlocking {
        val store = MemoryDocStore()
        val clientKey = "offline-removed-${UUID.randomUUID()}"
        val documentKey = UUID.randomUUID().toString().toDocKey()
        val storeKey = storeKeyFor(clientKey, documentKey)

        val session = createClient(options(clientKey, store))
        session.activateAsync().await()
        val document = Document(documentKey)
        session.attachDocument(document, syncMode = Client.SyncMode.Manual).await()
        awaitStored(store, storeKey)

        // A different client removes the document server-side.
        val remover = createClient()
        remover.activateAsync().await()
        val removerDoc = Document(documentKey)
        remover.attachDocument(removerDoc, syncMode = Client.SyncMode.Manual).await()
        remover.removeDocument(removerDoc).await()
        remover.deactivateAsync().await()
        removerDoc.close()
        remover.close()

        // session's sync learns Removed -> detachInternal + the store entry removed.
        session.syncAsync(document).await()
        assertEquals(ResourceStatus.Removed, document.getStatus())
        assertEquals(null, store.load(storeKey))

        // A fresh attach on the same key+store succeeds.
        val document2 = Document(documentKey)
        val result = session.attachDocument(document2, syncMode = Client.SyncMode.Manual).await()
        assertTrue(result.isSuccess)

        session.detachDocument(document2).await()
        session.deactivateAsync().await()
        document.close()
        document2.close()
        session.close()
    }

    private suspend fun awaitStored(store: DocStore, key: String): StoredDoc {
        var result: StoredDoc? = null
        withTimeout(GENERAL_TIMEOUT) {
            while (result == null) {
                result = store.load(key)
                if (result == null) kotlinx.coroutines.delay(50)
            }
        }
        return checkNotNull(result)
    }

    /**
     * Polls until the log holds AT LEAST [expectedCount] entries. `>=`, not `==`, so a log
     * that overshoots (or a mid-test compaction that lands extra writes) surfaces as a clear
     * assertion failure at the call site rather than a timeout here (team review, test-writer).
     */
    private suspend fun awaitStoredWithChanges(
        store: DocStore,
        key: String,
        expectedCount: Int,
    ): StoredDoc {
        var result: StoredDoc? = null
        withTimeout(GENERAL_TIMEOUT) {
            while ((result?.changes?.size ?: 0) < expectedCount) {
                result = store.load(key)
                if ((result?.changes?.size ?: 0) < expectedCount) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }
        return checkNotNull(result)
    }
}
