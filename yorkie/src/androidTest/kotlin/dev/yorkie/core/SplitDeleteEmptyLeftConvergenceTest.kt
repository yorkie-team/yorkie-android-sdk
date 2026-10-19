package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.gson
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for yorkie-js-sdk v0.7.25 (`6f690a14` / #1456)
 * against a real Yorkie server (compose pin `yorkieteam/yorkie:0.7.24` --
 * the pin moves in spec 040; this case must be re-run once, by hand,
 * against `0.7.25` before that part closes -- #1456 is "the JS half of
 * yorkie#2143" and the server rebuilds snapshots with the same rule).
 *
 * Both clients independently split `<span>de</span>` at offset 0 and
 * delete the empty left piece (the #1408 shape): without the fix, the
 * delete walk's split-sibling cascade tombstones the OTHER replica's
 * concurrent split product down the `insNextID` chain even though it
 * holds live text ("de") the editor never deleted, and whichever replica
 * applies the remote delete second loses "de". With the fix the cascade
 * stops at a known sibling unless the editor saw it removed or this
 * delete encloses it whole, so "de" survives on both; one replica may
 * keep the concurrent split product as an empty `<span></span>` residue
 * (#1408, not fixed here) -- visible text must still agree regardless.
 *
 * Oracles beyond the two clients agreeing with each other: the server's
 * OWN replayed root (pattern: [TreeSplitRemoveStyleServerRootTest.serverRoot]),
 * a from-scratch [CrdtRoot] rebuild of each client's own root, and GC
 * leaving no garbage on either client.
 */
@RunWith(AndroidJUnit4::class)
class SplitDeleteEmptyLeftConvergenceTest {

    private val http = OkHttpClient()

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

    private fun JsonTree.visibleText() = toXml().replace(Regex("<[^>]*>"), "")

    @Test
    fun test_split_and_delete_of_an_empty_left_piece_converges_with_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            d1.updateAsync { root, _ ->
                root.setNewTree(
                    key = "t",
                    initialRoot = element("doc") {
                        element("p") {
                            element("span") { text { "abc" } }
                            element("span") { text { "de" } }
                        }
                    },
                )
            }.await().getOrThrow()
            sync()

            // Concurrently, before either side has seen the other's
            // change: both split "de" at offset 0 and drop the empty left
            // piece.
            fun splitAt0DropLeft(root: JsonObject) {
                val t = root.getAs<JsonTree>("t")
                t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), splitLevel = 1)
                t.editByPath(listOf(0, 1), listOf(0, 2))
            }
            d1.updateAsync { root, _ -> splitAt0DropLeft(root) }.await().getOrThrow()
            d2.updateAsync { root, _ -> splitAt0DropLeft(root) }.await().getOrThrow()
            sync()

            val d1Tree = d1.getRoot().getAs<JsonTree>("t")
            val d2Tree = d2.getRoot().getAs<JsonTree>("t")
            // One replica may keep the concurrent split product as an
            // empty <span></span> residue the other tombstoned (#1408, not
            // fixed here) -- visible text must still agree regardless.
            assertEquals(
                d1Tree.toXml().replace("<span></span>", ""),
                d2Tree.toXml().replace("<span></span>", ""),
                "replicas diverged beyond an empty-span residue",
            )
            assertEquals("abcde", d1Tree.visibleText(), "d1 lost \"de\": ${d1Tree.toXml()}")
            assertEquals("abcde", d2Tree.visibleText(), "d2 lost \"de\": ${d2Tree.toXml()}")

            // docSize equals a from-scratch CrdtRoot(deepCopy) rebuild on
            // both clients -- the #1392 docSize-rebuild invariant. Not
            // compared cross-client: the #1408 empty-span residue means
            // one client's tree genuinely carries an extra element the
            // other doesn't, so their docSizes legitimately differ.
            val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
            val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
            assertEquals(d1Rebuild.docSize, d1.getDocSize())
            assertEquals(d2Rebuild.docSize, d2.getDocSize())

            // GC leaves no garbage on either client once both know
            // everything.
            val actors = listOf(c1.requireActorId(), c2.requireActorId())
            d1.garbageCollect(maxVectorOf(actors))
            d2.garbageCollect(maxVectorOf(actors))
            assertEquals(0, d1.garbageLength, "garbage left on d1")
            assertEquals(0, d2.garbageLength, "garbage left on d2")

            // The server's OWN replayed root is fetched as a further
            // oracle, but NOT asserted equal to the clients here: at the
            // 0.7.24 server this case was first pinned against, the
            // server applied each client's pushed ChangePack through its
            // own (Go) CRDTTree, which did not yet carry the yorkie#2143
            // cascade-delete fix #1456 is the JS half of -- observed
            // losing "de" entirely server-side even though both Android
            // clients correctly keep it after this spec's fix.
            // M10 (team-review, RTCOLLABPLATFORM-789): the compose pin has
            // since moved to 0.7.25 (`caaab8b2`), but this case has not
            // actually been re-run by hand against it since the bump --
            // whether the Go server now also keeps "de" is still
            // unverified, so the assertion below stays conservative
            // (paragraph/"abc" shape only) rather than asserting "de"
            // without having confirmed it survives there. Re-run this
            // case by hand against the current pin and tighten the
            // assertion to require "de" once confirmed.
            val serverJson = serverRoot(documentKey)
            assertTrue(serverJson.contains("\"abc\""), "server lost \"abc\": $serverJson")
            assertTrue(serverJson.contains("\"p\""), "server lost the paragraph: $serverJson")
        }
    }
}
