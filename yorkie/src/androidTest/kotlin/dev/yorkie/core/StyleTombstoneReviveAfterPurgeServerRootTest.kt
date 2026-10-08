package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.json.JsonText
import dev.yorkie.gson
import dev.yorkie.util.postApi
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented pin: when a client that purged the tombstone revives it
 * AFTER the order
 * `[d1 styles [4,6), d2 deletes [4,6), sync, d2 undoes the deletion first,
 * sync]`, d1 ends up WITHOUT the style's attribute while d2 and the server
 * BOTH keep it (`b=1`) -- the two clients, and the server, do NOT converge.
 *
 * The trace: after the first sync both replicas hold the `"ef"` tombstone
 * with `b=1` (`canStyle` admitting the remote style onto d2's removed node,
 * #1368, works as intended). d1's NEXT pull runs
 * `Document.applyChangePack -> garbageCollect(pack.versionVector)`
 * (`Document.kt:686`) and purges that tombstone once every attached client's
 * synced version vector covers its removal. d2's subsequent undo-of-the-
 * deletion reaches d1 as a restore operation; since d1 no longer HAS the
 * purged node by identity, it takes the gap path and rebuilds `"ef"` from
 * the `RestoreSpan` d2 captured at DELETION time -- before the style ever
 * arrived -- so the rebuilt node comes back without `b`. d2 and the server
 * revive the SAME node by identity (never purged it) and keep `b=1`.
 *
 * This is the known, deterministic outcome of the restore-span design (a
 * span's value is captured once, at deletion time, and a gap-path rebuild
 * replays exactly that captured value) combined with per-client GC purge
 * timing. JS carries the same design from its own source (span capture at
 * `rga_tree_split.ts:749-754`, gap-path rebuild from `span.value` at
 * `:851-857`, and the client's own pull-time collection at
 * `document.ts:1365`) -- the same outcome was executed and observed against
 * yorkie-js-sdk 0.7.23 directly (a vitest probe against its own `Document`/
 * `CRDTText`/`CRDTRoot` sources reproduced d1 ending up without `b=1` while
 * d2 keeps it, gcFirst=true), not just read from source. This test pins
 * the Android behaviour that design produces; it does not allege a defect
 * unique to Android, and the two clients plus the server are NOT expected to
 * agree once one of them has purged the tombstone before the other's
 * concurrent deletion is undone.
 */
@RunWith(AndroidJUnit4::class)
class StyleTombstoneReviveAfterPurgeServerRootTest {

    private val http = OkHttpClient()

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

    /**
     * True when a LIVE node of text key [key] carries a LIVE attribute
     * [attr] = [value]. Mirrors `StyleTombstoneUndoServerRootTest`'s
     * tombstone-side helper, but checks the revived (live) node here: the
     * divergence this test pins is visible in the rendered JSON itself once
     * the run is revived, unlike the tombstone-only case that helper covers.
     */
    private fun liveNodeHasAttr(
        d: Document,
        key: String,
        attr: String,
        value: String,
    ): Boolean {
        val text = d.getRootObject()[key] as CrdtText
        return text.rgaTreeSplit.any { node ->
            !node.isRemoved && node.value.attributesWithTimeTicket.any {
                it.key == attr && it.value == value && !it.isRemoved
            }
        }
    }

    @Test
    fun test_style_tombstone_revive_after_purge_diverges_from_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync().await()
                c2.syncAsync().await()
                c1.syncAsync().await()
            }

            d1.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "abcdefghij") }.await()
            sync()

            // Concurrent: d1 styles the exact range d2 deletes.
            d1.updateAsync { root, _ -> root.getAs<JsonText>("t").style(4, 6, mapOf("b" to "1")) }
                .await()
            d2.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(4, 6, "") }.await()
            sync()
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            // d2 undoes its OWN deletion first, reviving the run.
            d2.history.undoAsync().await()
            // d1's own pull purges the tombstone before applying d2's
            // restore (Document.kt:686): the sequence of syncs below is the
            // same c1/c2/c1 order `sync()` uses, spelled out so the purge
            // timing this KDoc describes stays visible at the call site.
            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            val revived = """{"t":[{"val":"abcd"},{"attrs":{"b":"1"},"val":"ef"},{"val":"ghij"}]}"""
            val plain = """{"t":[{"val":"abcd"},{"val":"ef"},{"val":"ghij"}]}"""

            // d2 and the server keep the style; d1 does not.
            assertJsonContentEquals(revived, d2.toJson())
            assertJsonContentEquals(revived, serverRoot(documentKey))
            assertJsonContentEquals(plain, d1.toJson())

            assertTrue(
                liveNodeHasAttr(d2, "t", "b", "1"),
                "d2 (never purged the tombstone) must keep the style on revive",
            )
            assertFalse(
                liveNodeHasAttr(d1, "t", "b", "1"),
                "d1 (purged the tombstone before the revive) must rebuild from d2's " +
                    "deletion-time snapshot, without the style",
            )
        }
    }
}
