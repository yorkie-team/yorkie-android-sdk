package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.gson
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented pin (test-only): the earlier instrumented test
 * ([TreeSplitRemoveStyleServerRootTest]) covered split-vs-removeStyle only,
 * leaving the #1375 headline itself -- two replicas splitting ONE element at
 * the SAME boundary concurrently -- without a committed pin over a real
 * server. JS's own commit for `c8928853`/#1375 says the fix "ships with"
 * yorkie#2030 and that a client-only patch regresses on snapshot/history
 * arrival order, so a JVM-only in-process test
 * ([dev.yorkie.document.TreeSplitOrderTest]) is not
 * sufficient: this adds a THIRD, late-attaching client that never saw either
 * split live and only pulls the already-settled history from the server.
 *
 * Scenario: `<doc><p><span>abcde</span></p></doc>`; c1 and c2 (Manual sync,
 * actor order fixed the same way [withTwoClientsAndDocuments] fixes it)
 * BOTH split the `<p>` at the same boundary (after its only child,
 * `splitByPath(listOf(0, 1))`) concurrently, before either has seen the
 * other's change. A disposable JVM-level probe of this exact scenario (same
 * fixture, same path, reusing [dev.yorkie.helper.exchange]; not committed)
 * confirmed the settled shape is `<doc><p><span>abcde</span></p><p></p>
 * <p></p></doc>` -- two empty `<p>` split products -- and that the two
 * replicas' node-id sequences (newest lamport first, per
 * `orderSameBoundarySplit`) already agree. c3 then attaches AFTERWARDS
 * ([createThreeClientsAndDocuments], not [withThreeClientsAndDocuments],
 * which would attach all three before any split happens) and pulls that
 * settled history cold.
 *
 * Oracles: all three documents equal in XML AND node-id sequence (not just
 * XML -- two empty `<p>`s render identically but are different nodes); the
 * server's OWN replayed root (admin `GetDocuments`, pattern:
 * [TreeSplitRemoveStyleServerRootTest.serverRoot]) equals all three;
 * `docSize` equals a from-scratch [CrdtRoot] rebuild on each client.
 *
 * A red run is not constructible here: the production fix
 * (`orderSameBoundarySplit`) landed earlier (`c8928853`, JS #1375); this
 * commit only adds the missing over-the-server pin, so there is no local
 * revert-and-rerun step here -- only the executed run below.
 */
@RunWith(AndroidJUnit4::class)
class TreeSplitSameBoundaryServerRootTest {

    private val http = OkHttpClient()

    /**
     * The server's own replayed root for [documentKey], via admin
     * `GetDocuments` -- pattern: [TreeSplitRemoveStyleServerRootTest.serverRoot].
     */
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

    private fun JsonTree.asXml() = toXml()

    /**
     * The live node-identity sequence of the document's `t` tree, in
     * traverse (postorder) order -- pattern:
     * [dev.yorkie.document.json.JsonTreeRestoreTest.identitySequence].
     * Two empty `<p>` split products render identical XML but are different
     * nodes; this is what actually pins the same-boundary ordering.
     */
    private fun identitySequence(document: Document): List<CrdtTreeNodeID> = buildList {
        (document.getRootObject()["t"] as CrdtTree).indexTree.traverse { node, _ -> add(node.id) }
    }

    @Test
    fun test_two_concurrent_same_boundary_splits_converge_with_a_late_attaching_third_client() {
        createThreeClientsAndDocuments {
                rawClient1,
                rawClient2,
                rawClient3,
                rawDocument1,
                rawDocument2,
                rawDocument3,
                documentKey,
            ->
            rawClient1.activateAsync().await()
            rawClient2.activateAsync().await()
            rawClient3.activateAsync().await()

            // Fix the actor order the same way withTwoClientsAndDocuments /
            // withThreeClientsAndDocuments do, so which client is "c1" vs
            // "c2" vs the late-attaching "c3" is deterministic across runs.
            val pairs = listOf(
                rawClient1 to rawDocument1,
                rawClient2 to rawDocument2,
                rawClient3 to rawDocument3,
            ).sortedBy { it.first.requireActorId() }
            val (c1, d1) = pairs[0]
            val (c2, d2) = pairs[1]
            val (c3, d3) = pairs[2]

            try {
                attachAndAwaitWatch(c1, d1, Manual)
                attachAndAwaitWatch(c2, d2, Manual)

                suspend fun syncBothWays() {
                    c1.syncAsync(d1).await().getOrThrow()
                    c2.syncAsync(d2).await().getOrThrow()
                    c1.syncAsync(d1).await().getOrThrow()
                }

                d1.updateAsync { root, _ ->
                    root.setNewTree(
                        key = "t",
                        initialRoot = element("doc") {
                            element("p") {
                                element("span") {
                                    text { "abcde" }
                                }
                            }
                        },
                    )
                }.await().getOrThrow()
                syncBothWays()

                // Concurrently, before either side has seen the other's
                // change: both split the SAME <p> at the same boundary
                // (after its only child).
                d1.updateAsync { root, _ ->
                    root.getAs<JsonTree>("t").splitByPath(listOf(0, 1))
                }.await().getOrThrow()
                d2.updateAsync { root, _ ->
                    root.getAs<JsonTree>("t").splitByPath(listOf(0, 1))
                }.await().getOrThrow()
                syncBothWays()

                val expectedXml = "<doc><p><span>abcde</span></p><p></p><p></p></doc>"
                val d1Xml = d1.getRoot().getAs<JsonTree>("t").asXml()
                val d2Xml = d2.getRoot().getAs<JsonTree>("t").asXml()
                assertEquals(expectedXml, d1Xml)
                assertEquals(expectedXml, d2Xml)
                assertEquals(identitySequence(d1), identitySequence(d2))

                val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
                val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
                assertEquals(d1Rebuild.docSize, d1.getDocSize())
                assertEquals(d2Rebuild.docSize, d2.getDocSize())
                assertEquals(d1.getDocSize(), d2.getDocSize())

                // A THIRD client attaches only now -- after c1/c2 already
                // settled -- and pulls the already-converged history cold,
                // rather than ever seeing the two splits as concurrent
                // operations itself.
                attachAndAwaitWatch(c3, d3, Manual)

                val d3Xml = d3.getRoot().getAs<JsonTree>("t").asXml()
                assertEquals(expectedXml, d3Xml)
                assertEquals(identitySequence(d1), identitySequence(d3))

                val d3Rebuild = CrdtRoot(d3.getRootObject().deepCopy())
                assertEquals(d3Rebuild.docSize, d3.getDocSize())
                assertEquals(d1.getDocSize(), d3.getDocSize())

                // The server's OWN replayed root, not just what the clients
                // show each other, must equal all three.
                val serverJson = serverRoot(documentKey)
                assertJsonContentEquals(serverJson, d1.toJson())
                assertJsonContentEquals(serverJson, d2.toJson())
                assertJsonContentEquals(serverJson, d3.toJson())
            } finally {
                c1.detachDocument(d1).await()
                c2.detachDocument(d2).await()
                c3.detachDocument(d3).await()
                c1.deactivateAsync().await()
                c2.deactivateAsync().await()
                c3.deactivateAsync().await()
                d1.close()
                d2.close()
                d3.close()
                c1.close()
                c2.close()
                c3.close()
            }
        }
    }
}
