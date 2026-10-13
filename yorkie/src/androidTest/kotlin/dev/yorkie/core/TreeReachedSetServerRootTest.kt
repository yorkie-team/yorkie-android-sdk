package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.crdt.CrdtRoot
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
 * Instrumented headline for #1404(c) (`b69169c0`):
 * a style's/removeStyle's reached set must resolve from the CHANGE's own
 * positions, not the receiving replica's visible-index space, over a real
 * Yorkie server (compose pin `yorkieteam/yorkie:0.7.24`) -- not just the JVM
 * in-process shapes [dev.yorkie.document.TreeStyleReachedSetTest] and
 * [dev.yorkie.document.TreeUnwrapMergeDeleteTest] already pin (both executed
 * and green; the expected XML below is cited from those runs, not
 * reguessed).
 *
 * This class asserts only the CLOSED shapes -- the three cases
 * below converge identically whichever client the server happens to apply
 * second -- and separately RUNS one pair from the still-open §9.5 nested
 * family (yorkie#2070, not confirmed present on every 0.7.24 server build)
 * WITHOUT asserting its two replicas agree; that pair's actual outcome is
 * recorded in the commit body instead.
 */
@RunWith(AndroidJUnit4::class)
class TreeReachedSetServerRootTest {

    private val http = OkHttpClient()
    private val bold = mapOf("bold" to "x")

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
     * `<r><p>ab</p><p>cd</p><p>ef</p></r>`, 12 wide inside the root -- the
     * flat base [dev.yorkie.document.TreeStyleReachedSetTest.styleScanBase]
     * also uses for the named split/merge cases.
     */
    private fun threeParagraphTree() = element("r") {
        listOf("ab", "cd", "ef").forEach { value -> element("p") { text { value } } }
    }

    @Test
    fun test_split_and_concurrent_style_converge_with_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            d1.updateAsync { root, _ -> root.setNewTree("t", threeParagraphTree()) }
                .await().getOrThrow()
            sync()

            // Concurrently, before either side has seen the other's change:
            // d1 splits the middle paragraph at its midpoint (level 1); d2
            // styles [5, 8) -- a range that ran past the paragraph's end
            // before the split existed, so both resulting halves must carry
            // the style on either delivery order.
            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(6, 6, 1) }
                .await().getOrThrow()
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").style(5, 8, bold) }
                .await().getOrThrow()
            sync()

            val expectedXml = """<r><p>ab</p><p bold="x">c</p><p bold="x">d</p><p>ef</p></r>"""
            assertEquals(expectedXml, d1.getRoot().getAs<JsonTree>("t").asXml())
            assertEquals(expectedXml, d2.getRoot().getAs<JsonTree>("t").asXml())

            val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
            val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
            assertEquals(d1Rebuild.docSize, d1.getDocSize())
            assertEquals(d2Rebuild.docSize, d2.getDocSize())
            assertEquals(d1.getDocSize(), d2.getDocSize())

            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(serverJson, d1.toJson())
            assertJsonContentEquals(serverJson, d2.toJson())
        }
    }

    @Test
    fun test_merge_and_concurrent_style_converge_with_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            d1.updateAsync { root, _ -> root.setNewTree("t", threeParagraphTree()) }
                .await().getOrThrow()
            sync()

            // Concurrently: d1 merges the first two paragraphs; d2 styles
            // [1, 6) -- a range that covered BOTH paragraphs' boundary
            // tokens, so the merge must carry the style onto the surviving
            // merged paragraph on either delivery order.
            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 5) }
                .await().getOrThrow()
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").style(1, 6, bold) }
                .await().getOrThrow()
            sync()

            val expectedXml = """<r><p bold="x">cd</p><p>ef</p></r>"""
            assertEquals(expectedXml, d1.getRoot().getAs<JsonTree>("t").asXml())
            assertEquals(expectedXml, d2.getRoot().getAs<JsonTree>("t").asXml())

            val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
            val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
            assertEquals(d1Rebuild.docSize, d1.getDocSize())
            assertEquals(d2Rebuild.docSize, d2.getDocSize())
            assertEquals(d1.getDocSize(), d2.getDocSize())

            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(serverJson, d1.toJson())
            assertJsonContentEquals(serverJson, d2.toJson())
        }
    }

    @Test
    fun test_unwrap_and_concurrent_delete_converge_with_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("r") {
                        element("p") { text { "ab" } }
                        element("p") { text { "cd" } }
                    },
                )
            }.await().getOrThrow()
            sync()

            // Concurrently: d1 unwraps the first paragraph (removes only its
            // opening token, hoisting "ab" into the root); d2 deletes the
            // first paragraph WHOLE, including the second paragraph's
            // opening token (#1331, yorkie#2042). d2's delete covers "ab",
            // so it is gone on both replicas regardless of delivery order --
            // the live (closed) shape this test asserts; the tombstoned
            // "ab"'s relative ORDER among the two replicas' tombstones is a
            // separate, still-open merge-moved-child-order task shared by
            // both SDKs (pinned by the JVM
            // dev.yorkie.document.TreeUnwrapMergeDeleteTest, not this test).
            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 1) }
                .await().getOrThrow()
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }
                .await().getOrThrow()
            sync()

            val expectedXml = "<r>cd</r>"
            assertEquals(expectedXml, d1.getRoot().getAs<JsonTree>("t").asXml())
            assertEquals(expectedXml, d2.getRoot().getAs<JsonTree>("t").asXml())

            val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
            val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
            assertEquals(d1Rebuild.docSize, d1.getDocSize())
            assertEquals(d2Rebuild.docSize, d2.getDocSize())
            assertEquals(d1.getDocSize(), d2.getDocSize())

            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(serverJson, d1.toJson())
            assertJsonContentEquals(serverJson, d2.toJson())
        }
    }

    /**
     * ONE pair from the still-open §9.5 level-2 nested split x style
     * family (the 241-of-11592 divergent pairs
     * [dev.yorkie.document.TreeStyleReachedSetScanTest] exhaustively pins
     * in-process), run here over a real server and RECORDED, not asserted to
     * converge -- whether yorkie#2070 is deployed on the compose server this
     * run happened to hit is out of this SDK's control. The pair itself
     * (level-2 split at index 1, style range [2, 15), over
     * `<r><p><p><p>abcd</p><p>efgh</p></p><p>ijkl</p></p></r>`) was
     * confirmed divergent by a disposable JVM probe (deleted, not committed)
     * before being hard-coded here -- its in-process outcome, for reference,
     * was `ab (structural-then-style)` =
     * `<r><p bold="x"></p><p><p bold="x"><p bold="x">abcd</p>
     * <p bold="x">efgh</p></p><p>ijkl</p></p></r>` and
     * `ba (style-then-structural)` =
     * `<r><p></p><p><p bold="x"><p bold="x">abcd</p><p bold="x">efgh</p></p>
     * <p>ijkl</p></p></r>` -- the two renders disagree on whether the
     * now-empty FIRST `<p>` (the level-2 split's left half) carries `bold`.
     * The only invariant this test enforces is the one that must ALWAYS
     * hold regardless of the open divergence: each client's own `docSize`
     * still equals a from-scratch [CrdtRoot] rebuild of its own root.
     */
    @Test
    fun test_level_2_split_and_concurrent_style_records_the_open_nested_outcome() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("r") {
                        element("p") {
                            element("p") {
                                element("p") { text { "abcd" } }
                                element("p") { text { "efgh" } }
                            }
                            element("p") { text { "ijkl" } }
                        }
                    },
                )
            }.await().getOrThrow()
            sync()

            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 1, 2) }
                .await().getOrThrow()
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").style(2, 15, bold) }
                .await().getOrThrow()
            sync()

            val d1Xml = d1.getRoot().getAs<JsonTree>("t").asXml()
            val d2Xml = d2.getRoot().getAs<JsonTree>("t").asXml()
            // RECORDED, not asserted equal -- println surfaces the
            // actual outcome in the instrumented run's logcat output, which
            // the commit body quotes verbatim.
            println(
                "open §9.5 pair -- d1=$d1Xml d2=$d2Xml " +
                    "converged=${d1Xml == d2Xml}",
            )

            val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
            val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
            assertEquals(d1Rebuild.docSize, d1.getDocSize())
            assertEquals(d2Rebuild.docSize, d2.getDocSize())
        }
    }
}
