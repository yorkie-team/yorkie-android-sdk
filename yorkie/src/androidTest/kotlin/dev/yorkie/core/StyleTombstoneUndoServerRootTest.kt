package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.json.JsonText
import dev.yorkie.gson
import dev.yorkie.util.postApi
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for yorkie-js-sdk v0.7.23 `e0609c7a` (#1368, server
 * twin yorkie#2012, `6731bb6c`) against a real Yorkie server (compose pin
 * `yorkieteam/yorkie:0.7.23`), with the server's OWN replayed root as a
 * third oracle beyond the two clients agreeing with each other (pattern:
 * [TreeSplitAttributeAndRestoreServerRootTest.serverRoot]).
 *
 * d1 styles a text range while d2 concurrently deletes the SAME range; once
 * synced, the style has landed on the tombstone on both replicas (`canStyle`
 * no longer refuses a removed node -- the behaviour change #1368 discloses).
 * d1 then undoes its own style: a reverse that lands only on the tombstone,
 * produces no `OpInfo`, and -- via the `executedOperations`-gated undo
 * propagation -- still reaches the wire.
 * d2 then undoes its own deletion, reviving the text. Both replicas, and
 * the server's own replayed root, must converge on the SAME text WITHOUT
 * the attribute the style installed: the style covered text the other
 * replica had already deleted, so the attribute never survives past that
 * deletion's own undo.
 *
 * Purge/GC schedule (see `TreeSplitAttributeAndRestoreServerRootTest`'s
 * KDoc): the server only purges a tombstone while building a snapshot with
 * the MINIMUM of the attached clients' own request version vectors
 * (`pushpull.go`'s `UpdateMinVersionVector`, backed by `mongo/client.go`).
 * This test never calls `garbageCollect` on either client and the
 * style/removeStyle operations it exercises are not themselves
 * purge-dependent -- only the text's own content removal is -- so the
 * server-root oracle below does not need to race any purge schedule the
 * way a tree-restore-under-a-removed-parent test must.
 */
@RunWith(AndroidJUnit4::class)
class StyleTombstoneUndoServerRootTest {

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
     * True when a REMOVED node of text key [key] carries a LIVE attribute
     * [attr] = [value]. The final visible JSON converges to the same "no
     * attribute survives" result whether `canStyle` correctly admits the
     * style onto the tombstone (then the undo's executed-operations gate
     * propagates the strip) or incorrectly refuses the remote style
     * outright (then there was
     * never anything to strip) -- so this direct tombstone introspection,
     * not the JSON equality checks alone, is what actually discriminates
     * the `canStyle` fix. Mirrors the JVM `StyleTombstoneConvergenceTest`'s
     * `nodeAttrs` helper.
     */
    private fun tombstoneHasAttr(
        d: Document,
        key: String,
        attr: String,
        value: String,
    ): Boolean {
        val text = d.getRootObject()[key] as CrdtText
        return text.rgaTreeSplit.any { node ->
            node.isRemoved && node.value.attributesWithTimeTicket.any {
                it.key == attr && it.value == value && !it.isRemoved
            }
        }
    }

    @Test
    fun test_concurrent_style_and_removal_converge_after_undo_with_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync().await()
                c2.syncAsync().await()
                c1.syncAsync().await()
            }

            d1.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "abcdefghij") }.await()
            sync()

            // Concurrent: d1 styles the exact range d2 deletes, neither
            // having seen the other's change yet.
            d1.updateAsync { root, _ -> root.getAs<JsonText>("t").style(4, 6, mapOf("b" to "1")) }
                .await()
            d2.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(4, 6, "") }.await()
            sync()

            assertJsonContentEquals(d1.toJson(), d2.toJson())
            // The actual canStyle discriminator: the style must have landed
            // on BOTH replicas' tombstones, not just d1's own (where it was
            // applied while the range was still live, before d2's deletion
            // arrived) -- a canStyle that refuses a removed node would drop
            // this silently on d2 and still converge on the same (wrong)
            // final JSON once d2 later revives the range.
            assertTrue(
                tombstoneHasAttr(d1, "t", "b", "1"),
                "d1's own tombstone must carry the style it applied",
            )
            assertTrue(
                tombstoneHasAttr(d2, "t", "b", "1"),
                "d2 must have admitted d1's remote style onto its own tombstone",
            )

            // d1's reverse style lands only on the tombstone -- no OpInfo,
            // but the executed-operations gate (ran, not OpInfo-produced)
            // means it still has to reach d2.
            d1.history.undoAsync().await()
            sync()

            // d2 revives the deleted range. It must come back WITHOUT the
            // attribute: d1's undo above already stripped it from the
            // tombstone before this revival.
            d2.history.undoAsync().await()
            sync()

            val expected = """{"t":[{"val":"abcd"},{"val":"ef"},{"val":"ghij"}]}"""
            assertJsonContentEquals(expected, d1.toJson())
            assertJsonContentEquals(expected, d2.toJson())
            assertJsonContentEquals(expected, serverRoot(documentKey))
        }
    }
}
