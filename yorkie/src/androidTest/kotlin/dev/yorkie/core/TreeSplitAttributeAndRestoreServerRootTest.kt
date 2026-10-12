package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
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
 * Instrumented headline for yorkie-js-sdk v0.7.23 (`99dbec9d` / #1363 and
 * `248551a1` / #1364) against a real Yorkie server (compose pin
 * `yorkieteam/yorkie:0.7.23`), with the server's OWN replayed root as a
 * third oracle beyond the two clients agreeing with each other
 * ([serverRoot], pattern: [GcContainmentTest.serverRoot]).
 *
 * Part A: d1 styles then un-styles a span (one tombstoned `color`
 * attribute), then splits it. `d1.garbageLength` must already count the
 * split's copy of that tombstone immediately, before anything syncs (port
 * `99dbec9d`, #1363) -- before the fix the copy rode along unreachable and
 * never showed up as garbage at all. Both replicas, and the server's own
 * replayed root, converge once everything syncs and each side runs its own
 * GC.
 *
 * Part B: under a SEPARATE tree key, d1 removes a text node and both
 * replicas purge it; d2 then tombstones (not purges) the enclosing `<p>`;
 * d1 undoes its own earlier removal, recreating the node under the
 * now-tombstoned `<p>` (port `248551a1`, #1364) -- born tombstoned, so
 * `d1.getDocSize().live` does not move across the undo. d2 then revives
 * `<p>`, and since the recreated node stayed dead, both replicas converge
 * on an EMPTY `<p>`.
 *
 * The server only purges a tombstone inside its own cached-doc rebuild
 * (`server/packs/snapshot.go`'s `BuildInternalDocForServerSeq`), and only
 * once every attached client's own request has reported having seen that
 * removal (the server's min version vector is the minimum over each
 * client's request vector: `pushpull.go`'s `UpdateMinVersionVector`, backed
 * by `mongo/client.go`). Removing the enclosing `<p>` re-stamps "hello"'s
 * tombstone with a later ticket, so once that happens "hello" can only be
 * purged in the same pass as `<p>`.
 * [test_split_copied_tombstones_and_a_tombstoned_restore_converge_with_the_server_root]
 * forces both replicas' purges with an explicit `garbageCollect` call
 * ahead of any server rebuild, so its Part B server root replays the
 * restore against an unpurged "hello" and takes the still-open F2 UNREMOVE
 * route (see `TreeUpstreamDefectPinTest`'s `restore under a tombstoned
 * parent diverges`): `<p>hello</p>`, even though both clients correctly
 * converge on an empty `<p>`. That is a shared upstream gap (yorkie-js-sdk
 * #1364 / Go `3891d70d` close only the RECREATE route, not UNREMOVE), not a
 * client defect.
 *
 * [test_restore_under_a_removed_parent_converges_with_the_server_root_under_natural_gc]
 * gives Part B a convergent server-root oracle instead, by placing the
 * [serverRoot] prime (an admin `GetDocuments` call) after BOTH replicas
 * have reported seeing "hello"'s removal (one settling `sync()` round
 * beyond the removal itself) and before `<p>`'s removal is applied. That
 * ordering lets the server's own rebuild purge "hello" ahead of the `<p>`
 * removal, so the server takes the same RECREATE route the clients do, and
 * the restore converges all the way to the server:
 * `server == d1 == d2 == <p></p>`.
 */
@RunWith(AndroidJUnit4::class)
class TreeSplitAttributeAndRestoreServerRootTest {

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

    @Test
    fun test_split_copied_tombstones_and_a_tombstoned_restore_converge_with_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            // Three round trips, matching GCTest.test_getGarbageLength: a
            // client's own `applyChangePack` auto-collects against the
            // SERVER's version vector on every incoming pack (Document.kt's
            // `garbageCollect(pack.versionVector)`), so whichever replica
            // syncs SECOND in a round can auto-purge a tombstone the FIRST
            // replica only just registered -- an extra round lets both
            // settle on the SAME version vector before anything here reads
            // garbageLength.
            suspend fun sync() {
                c1.syncAsync().await()
                c2.syncAsync().await()
                c1.syncAsync().await()
            }
            val actors = listOf(c1.requireActorId(), c2.requireActorId())

            // --- Part A: split copies a tombstoned attribute ---
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    key = "attrs",
                    initialRoot = element("doc") {
                        element("p") {
                            element("span") { text { "abcdefghij" } }
                        }
                    },
                )
            }.await()
            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("attrs")
                    .styleByPath(listOf(0, 0), listOf(0, 1), mapOf("color" to "red"))
            }.await()
            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("attrs")
                    .removeStyleByPath(listOf(0, 0), listOf(0, 1), listOf("color"))
            }.await()
            sync()
            assertEquals(
                d1.getRoot().getAs<JsonTree>("attrs").toXml(),
                d2.getRoot().getAs<JsonTree>("attrs").toXml(),
            )
            assertEquals(
                d1.garbageLength,
                d2.garbageLength,
                "the removed attribute's tombstone must count the same on both replicas",
            )

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("attrs")
                    .editByPath(listOf(0, 0, 1), listOf(0, 0, 1), splitLevel = 1)
            }.await()
            // The split's copy of the removed attribute must already count
            // as garbage locally, before anything syncs. A revert of
            // #1363's split-time registration turns this 1 instead of 2.
            assertEquals(
                2,
                d1.garbageLength,
                "the split's copied tombstone must be registered as garbage immediately",
            )
            sync()

            val attrsXmlD1 = d1.getRoot().getAs<JsonTree>("attrs").toXml()
            assertEquals("<doc><p><span>a</span><span>bcdefghij</span></p></doc>", attrsXmlD1)
            assertEquals(attrsXmlD1, d2.getRoot().getAs<JsonTree>("attrs").toXml())
            // Both replicas must agree on docSize once each settles (a
            // client's OWN sync may have already auto-collected what the
            // other hasn't yet, so garbageLength alone can transiently
            // differ here -- docSize.live + docSize.gc is the invariant
            // that does not depend on WHEN a purge physically ran). This
            // equality holds whether or not the split's copy was ever
            // registered: a client's own auto-collect on sync reconciles
            // garbageLength to 0 either way. The assertion above, taken
            // immediately after the local split and before any sync, is
            // what actually discriminates a revert of #1363.
            assertEquals(d1.getDocSize(), d2.getDocSize())

            // Drive both replicas' own garbageCollect explicitly so neither
            // can hide behind the other's auto-collect timing, then assert
            // the steady state: nothing left to collect, on either side.
            d1.garbageCollect(maxVectorOf(actors))
            d2.garbageCollect(maxVectorOf(actors))
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)
            assertEquals(d1.getDocSize(), d2.getDocSize())

            // Server-root oracle for Part A: the split-copied-tombstone fix
            // (#1363) converges all the way to the server's own replayed
            // root, not just between the two clients.
            assertJsonContentEquals(serverRoot(documentKey), d1.toJson())
            assertJsonContentEquals(serverRoot(documentKey), d2.toJson())

            // --- Part B: recreate under a removed parent ---
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    key = "restore",
                    initialRoot = element("doc2") { element("p") { text { "hello" } } },
                )
            }.await()
            sync()

            d1.updateAsync { root, _ -> root.getAs<JsonTree>("restore").edit(1, 6) }.await()
            sync()
            d1.garbageCollect(maxVectorOf(actors))
            d2.garbageCollect(maxVectorOf(actors))
            assertEquals(
                "<doc2><p></p></doc2>",
                d1.getRoot().getAs<JsonTree>("restore").toXml(),
                "\"hello\" must be purged on d1",
            )
            assertEquals(
                "<doc2><p></p></doc2>",
                d2.getRoot().getAs<JsonTree>("restore").toXml(),
                "\"hello\" must be purged on d2",
            )

            d2.updateAsync { root, _ -> root.getAs<JsonTree>("restore").edit(0, 2) }.await()
            sync()
            assertEquals("<doc2></doc2>", d1.getRoot().getAs<JsonTree>("restore").toXml())
            assertEquals(
                d1.getRoot().getAs<JsonTree>("restore").toXml(),
                d2.getRoot().getAs<JsonTree>("restore").toXml(),
            )

            val liveBeforeUndo = d1.getDocSize().live
            d1.history.undoAsync().await()
            sync()
            // The recreated node is born tombstoned under the already
            // -tombstoned <p> (port #1364), so it does not inflate live
            // size. A revert of that fix would count the recreated node as
            // visible data, moving `live` -- the assertion below catches
            // that; the unasserted XML checks alone do not, since nothing
            // renders either way.
            assertEquals("<doc2></doc2>", d1.getRoot().getAs<JsonTree>("restore").toXml())
            assertEquals(
                d1.getRoot().getAs<JsonTree>("restore").toXml(),
                d2.getRoot().getAs<JsonTree>("restore").toXml(),
            )
            assertEquals(d1.getDocSize(), d2.getDocSize())
            assertEquals(
                liveBeforeUndo,
                d1.getDocSize().live,
                "a node recreated under an already-removed parent must not inflate live size",
            )

            // The recreated node's gcOnlySize pair is reachable -- a purge
            // sweeps it (plus <p>'s own tombstone) instead of orphaning it.
            d1.garbageCollect(maxVectorOf(actors))
            d2.garbageCollect(maxVectorOf(actors))
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)

            d2.history.undoAsync().await()
            sync()
            // "hello" was purged, not revived, so reviving <p> leaves it
            // empty -- both replicas converge on the correct outcome.
            assertEquals("<doc2><p></p></doc2>", d1.getRoot().getAs<JsonTree>("restore").toXml())
            assertEquals(
                d1.getRoot().getAs<JsonTree>("restore").toXml(),
                d2.getRoot().getAs<JsonTree>("restore").toXml(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            // Pins the still-open F2 UNREMOVE route on the server (shared by
            // Go, JS and Android): the explicit garbageCollect calls above
            // purge "hello" on both clients ahead of anything the server's
            // own cached replay has reached, so the server replays the
            // restore on an unpurged "hello" and d2's undo revives it under
            // <p>, diverging from both clients' empty <p>. Flips when
            // upstream fixes it (see the class KDoc and
            // [test_restore_under_a_removed_parent_converges_with_the_server_root_under_natural_gc]
            // below, which gives Part B a convergent server-root oracle
            // under a schedule that does not race ahead of the server).
            val partBServerRoot = serverRoot(documentKey)
            assertTrue(
                partBServerRoot.contains(
                    """{"type":"p","children":[{"type":"text","value":"hello"}]}""",
                ),
                partBServerRoot,
            )
        }
    }

    /**
     * Gives Part B a convergent server-root oracle under a NATURAL-GC
     * schedule (no explicit client-forced `garbageCollect`): the
     * [serverRoot] prime is placed only after BOTH replicas have reported
     * seeing "hello"'s removal (one settling `sync()` round beyond the
     * removal itself), and before `<p>`'s removal is applied. The server
     * only purges a tombstone once every attached client's own request has
     * reported having seen it (its min version vector is the minimum over
     * each client's request vector); placing the prime there lets the
     * server's own rebuild purge "hello" ahead of the `<p>` removal, so it
     * takes the same RECREATE route the clients do and the restore
     * converges all the way to the server's own replayed root:
     * `server == d1 == d2 == <p></p>`. This is deterministic, not a race --
     * see the class KDoc for the mechanism and for the still-open F2
     * UNREMOVE route the sibling test above pins when the schedule races
     * ahead of the server instead.
     */
    @Test
    fun test_restore_under_a_removed_parent_converges_with_the_server_root_under_natural_gc() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync().await()
                c2.syncAsync().await()
                c1.syncAsync().await()
            }

            d1.updateAsync { root, _ ->
                root.setNewTree(
                    key = "restore",
                    initialRoot = element("doc2") { element("p") { text { "hello" } } },
                )
            }.await()
            sync()

            d1.updateAsync { root, _ -> root.getAs<JsonTree>("restore").edit(1, 6) }.await()
            sync()
            // One more round so c2's own request reports having seen the
            // removal too -- only then can the server's min version vector
            // cover it, and only then can the prime below purge "hello"
            // ahead of the <p> removal.
            sync()

            // Prime: an admin GetDocuments call forces a server-side
            // rebuild (and GC) now, while "hello" is the only thing removed
            // so far and both replicas have reported it.
            serverRoot(documentKey)

            d2.updateAsync { root, _ -> root.getAs<JsonTree>("restore").edit(0, 2) }.await()
            sync()
            assertEquals("<doc2></doc2>", d1.getRoot().getAs<JsonTree>("restore").toXml())
            assertEquals(
                d1.getRoot().getAs<JsonTree>("restore").toXml(),
                d2.getRoot().getAs<JsonTree>("restore").toXml(),
            )

            val liveBeforeUndo = d1.getDocSize().live
            d1.history.undoAsync().await()
            sync()
            assertEquals("<doc2></doc2>", d1.getRoot().getAs<JsonTree>("restore").toXml())
            assertEquals(
                d1.getRoot().getAs<JsonTree>("restore").toXml(),
                d2.getRoot().getAs<JsonTree>("restore").toXml(),
            )
            // The recreated node is born tombstoned under the already
            // -removed <p>, so it does not inflate live size. A revert of
            // #1364's recreate fix would count it as visible data instead.
            assertEquals(
                liveBeforeUndo,
                d1.getDocSize().live,
                "a node recreated under an already-removed parent must not inflate live size",
            )

            d2.history.undoAsync().await()
            sync()
            assertEquals("<doc2><p></p></doc2>", d1.getRoot().getAs<JsonTree>("restore").toXml())
            assertEquals(
                d1.getRoot().getAs<JsonTree>("restore").toXml(),
                d2.getRoot().getAs<JsonTree>("restore").toXml(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            // The server took the same RECREATE route: it purged "hello"
            // ahead of the <p> removal, so its own replayed root converges
            // with both clients instead of reviving "hello" under <p>.
            assertJsonContentEquals(serverRoot(documentKey), d1.toJson())
            assertJsonContentEquals(serverRoot(documentKey), d2.toJson())
        }
    }
}
