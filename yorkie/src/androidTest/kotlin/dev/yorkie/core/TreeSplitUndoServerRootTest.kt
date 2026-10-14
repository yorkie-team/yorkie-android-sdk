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
import dev.yorkie.util.traverseAll
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for #1404(a) (`b69169c0`):
 * an undo of two splitLevel-2 merge reverses recorded in ONE undo entry must
 * mint each reverse its own split tickets, over a real Yorkie server
 * (compose pin `yorkieteam/yorkie:0.7.24`), not just the JVM in-process shape
 * [dev.yorkie.document.TreeSplitTicketTest] already pins.
 *
 * Scenario (JS `split_ticket_test.ts`'s `threeBlockDoc`, ported as a JVM
 * unit test in [dev.yorkie.document.TreeSplitTicketTest], executed and
 * green there -- the XML shapes below are cited from that run, not
 * reguessed): `<r><d><p>ab</p></d><d><p>cd</p></d><d><p>ef</p></d></r>`; c1
 * runs `edit(4,8)` then `edit(6,10)` in ONE `updateAsync`, merging all three
 * blocks into `<r><d><p>abcdef</p></d></r>`, then undoes it. Before the fix,
 * `executeUndoRedoInternal` never recorded split tickets for a splitLevel>0
 * history op, so `TreeEditOperation.issueTimeTicket`'s fallback reconstructed
 * them by counting delimiters up from `executedAt` -- landing the SECOND
 * split reverse on the ticket the FIRST reverse in this same undo entry
 * already consumed, duplicating a node id on every replica.
 *
 * A THIRD client, c3, attaches only AFTER c1/c2 have already settled the
 * undo and synced -- exercising the RECORDED split tickets' own encode path
 * (`OperationConverter`): c3 reconstructs the undo's split products
 * purely from the wire-carried tickets, never having seen either merge or
 * the undo live itself.
 *
 * Oracles: all three documents equal in XML AND live node-id sequence (two
 * duplicate ids can still render identical XML); no id occurs twice (live or
 * tombstoned) on any of the three; the server's OWN replayed root (admin
 * `GetDocuments`, pattern: [TreeSplitRemoveStyleServerRootTest.serverRoot])
 * equals all three; `docSize` equals a from-scratch [CrdtRoot] rebuild on
 * each client.
 */
@RunWith(AndroidJUnit4::class)
class TreeSplitUndoServerRootTest {

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
     * The live node-identity sequence of [document]'s `t` tree, in traverse
     * (postorder) order -- pattern:
     * [TreeSplitSameBoundaryServerRootTest.identitySequence].
     */
    private fun identitySequence(document: Document): List<CrdtTreeNodeID> = buildList {
        (document.getRootObject()["t"] as CrdtTree).indexTree.traverse { node, _ -> add(node.id) }
    }

    /** Ids naming more than one (live or tombstoned) node under [document]'s `t`. */
    private fun duplicatedIds(document: Document): List<CrdtTreeNodeID> {
        val tree = document.getRootObject()["t"] as CrdtTree
        val counts = mutableMapOf<CrdtTreeNodeID, Int>()
        traverseAll(tree.root) { node, _ -> counts[node.id] = (counts[node.id] ?: 0) + 1 }
        return counts.filterValues { it > 1 }.keys.toList()
    }

    // The method name is shortened ("...and_server_root" rather than
    // "...and_the_server_root") -- dropping "the_"
    // (4 chars) is the only way to keep this name on one line under the
    // project's 100-char ktlint cap (.editorconfig max_line_length = 100)
    // without breaking ktlint's indent rule, which rejects a brace moved to
    // its own continuation line for a zero-parameter function signature.
    @Test
    fun test_undo_of_two_merges_in_one_entry_keeps_unique_ids_on_every_replica_and_server_root() {
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
            // withThreeClientsAndDocuments / TreeSplitSameBoundaryServerRootTest
            // do, so which client is "c1" vs "c2" vs the late-attaching "c3"
            // is deterministic across runs.
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
                        initialRoot = element("r") {
                            listOf("ab", "cd", "ef").forEach { value ->
                                element("d") { element("p") { text { value } } }
                            }
                        },
                    )
                }.await().getOrThrow()
                syncBothWays()

                val beforeXml = "<r><d><p>ab</p></d><d><p>cd</p></d><d><p>ef</p></d></r>"
                assertEquals(beforeXml, d1.getRoot().getAs<JsonTree>("t").asXml())

                // Two splitLevel-2 merge reverses land in ONE undo entry: the
                // first edit merges d1/d2's <p>s, the second merges the
                // result with d3's <p> -- both in the SAME updateAsync.
                d1.updateAsync { root, _ ->
                    val tree = root.getAs<JsonTree>("t")
                    tree.edit(4, 8)
                    tree.edit(6, 10)
                }.await().getOrThrow()
                assertEquals(
                    "<r><d><p>abcdef</p></d></r>",
                    d1.getRoot().getAs<JsonTree>("t").asXml(),
                )

                d1.history.undoAsync().await().getOrThrow()
                syncBothWays()

                assertEquals(beforeXml, d1.getRoot().getAs<JsonTree>("t").asXml())
                assertEquals(beforeXml, d2.getRoot().getAs<JsonTree>("t").asXml())
                assertEquals(identitySequence(d1), identitySequence(d2))
                assertEquals(emptyList(), duplicatedIds(d1), "no duplicate id on c1 after undo")
                assertEquals(emptyList(), duplicatedIds(d2), "no duplicate id on c2 after undo")

                val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
                val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
                assertEquals(d1Rebuild.docSize, d1.getDocSize())
                assertEquals(d2Rebuild.docSize, d2.getDocSize())
                assertEquals(d1.getDocSize(), d2.getDocSize())

                // A THIRD client attaches only now -- after c1/c2 already
                // settled the undo -- and pulls the already-converged
                // history cold: it reconstructs the undo's split products
                // purely from the WIRE-encoded split tickets, never
                // having applied the merges or the undo itself.
                attachAndAwaitWatch(c3, d3, Manual)

                assertEquals(beforeXml, d3.getRoot().getAs<JsonTree>("t").asXml())
                assertEquals(identitySequence(d1), identitySequence(d3))
                assertEquals(emptyList(), duplicatedIds(d3), "no duplicate id on the late c3")

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
