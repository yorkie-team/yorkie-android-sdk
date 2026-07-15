package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonCounter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisableGCTest {

    @Test
    fun test_disable_gc_attachment_converges_on_counter_workload() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            c1.activateAsync().await()
            c2.activateAsync().await()

            val documentKey = "disable-gc-${UUID.randomUUID()}".toDocKey()
            val d1 = Document(documentKey)
            val d2 = Document(documentKey)

            // Both clients opt out of server-side GC tracking; the wire flag
            // rides on attach and every push-pull
            c1.attachDocument(d1, syncMode = Client.SyncMode.Manual, disableGC = true).await()
            c2.attachDocument(d2, syncMode = Client.SyncMode.Manual, disableGC = true).await()

            d1.updateAsync { root, _ ->
                root.setNewCounter("counter", 0)
            }.await()
            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(5)
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(3)
            }.await()

            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()
            c1.syncAsync(d1).await()

            assertEquals("""{"counter":8}""", d1.toJson())
            assertEquals("""{"counter":8}""", d2.toJson())

            c1.detachDocument(d1).await()
            c2.detachDocument(d2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_mixed_opt_in_and_opt_out_clients_converge_on_counter_value() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            c1.activateAsync().await()
            c2.activateAsync().await()

            val documentKey = "disable-gc-mixed-${UUID.randomUUID()}".toDocKey()
            val d1 = Document(documentKey)
            val d2 = Document(documentKey)

            c1.attachDocument(d1, syncMode = Client.SyncMode.Manual).await()
            d1.updateAsync { root, _ ->
                root.setNewCounter("counter", 0)
            }.await()
            c1.syncAsync(d1).await()

            c2.attachDocument(d2, syncMode = Client.SyncMode.Manual, disableGC = true).await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(1)
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(1)
            }.await()

            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()
            c1.syncAsync(d1).await()

            assertEquals("""{"counter":2}""", d1.toJson())
            assertEquals("""{"counter":2}""", d2.toJson())

            c1.detachDocument(d1).await()
            c2.detachDocument(d2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }

    // Regression for yorkie-js-sdk #1270: without the syncLamport path, every remote
    // actor was merged into the opt-out client's version vector via syncClocks, so the
    // per-Change VV grew O(num_actors) and the opt-out's wire savings never materialized.
    @Test
    fun test_opt_out_clients_keep_version_vector_at_size_1_under_multi_actor_fanout() {
        runBlocking {
            val clients = List(3) { createClient() }
            clients.forEach { it.activateAsync().await() }

            val documentKey = "disable-gc-fanout-${UUID.randomUUID()}".toDocKey()
            val documents = List(3) { Document(documentKey) }

            clients.zip(documents).forEach { (client, document) ->
                client.attachDocument(
                    document,
                    syncMode = Client.SyncMode.Manual,
                    disableGC = true,
                ).await()
            }

            documents[0].updateAsync { root, _ ->
                root.setNewCounter("counter", 0)
            }.await()
            clients.zip(documents).forEach { (client, document) ->
                client.syncAsync(document).await()
            }

            repeat(3) {
                documents.forEach { document ->
                    document.updateAsync { root, _ ->
                        root.getAs<JsonCounter>("counter").increase(1)
                    }.await()
                }
                clients.zip(documents).forEach { (client, document) ->
                    client.syncAsync(document).await()
                }
            }
            // Drain remaining pushed changes so every client sees them.
            clients.zip(documents).forEach { (client, document) ->
                client.syncAsync(document).await()
            }
            clients[0].syncAsync(documents[0]).await()

            documents.forEach { document ->
                assertEquals("""{"counter":9}""", document.toJson())
            }

            // The contract assertion: every opt-out doc's VV stays at size 1.
            documents.forEachIndexed { index, document ->
                assertEquals(
                    1,
                    document.getVersionVector().size(),
                    "opt-out doc[$index].versionVector must stay at size 1",
                )
            }

            clients.zip(documents).forEach { (client, document) ->
                client.detachDocument(document).await()
                client.deactivateAsync().await()
                client.close()
            }
        }
    }

    @Test
    fun test_reattach_without_disable_gc_restores_normal_sync_behavior() {
        runBlocking {
            val c = createClient()
            c.activateAsync().await()

            val documentKey = "disable-gc-reattach-${UUID.randomUUID()}".toDocKey()

            // First attach: opt-out.
            val d1 = Document(documentKey)
            c.attachDocument(d1, syncMode = Client.SyncMode.Manual, disableGC = true).await()
            d1.updateAsync { root, _ ->
                root.setNewCounter("counter", 0)
                root.getAs<JsonCounter>("counter").increase(1)
            }.await()
            c.syncAsync(d1).await()
            c.detachDocument(d1).await()

            // Re-attach without the option. The SDK reads the flag from the
            // attachment, so this PushPull omits disableGC.
            val d2 = Document(documentKey)
            c.attachDocument(d2, syncMode = Client.SyncMode.Manual).await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(1)
            }.await()
            c.syncAsync(d2).await()

            assertEquals("""{"counter":2}""", d2.toJson())

            c.detachDocument(d2).await()
            c.deactivateAsync().await()
            c.close()
        }
    }

    // Regression for the server-side issue where the response VV was nil'ed for opt-out
    // clients even on snapshot pulls, preventing the opt-out client's change clock from
    // catching up to the server's actual state.
    @Test
    fun test_opt_out_client_picks_up_server_lamport_when_attach_returns_snapshot() {
        runBlocking {
            val documentKey = "disable-gc-snapshot-${UUID.randomUUID()}".toDocKey()

            val cA = createClient()
            cA.activateAsync().await()
            val dA = Document(documentKey)
            cA.attachDocument(dA, syncMode = Client.SyncMode.Manual).await()
            repeat(DEFAULT_SNAPSHOT_THRESHOLD + 1) { index ->
                dA.updateAsync { root, _ ->
                    root["k$index"] = index
                }.await()
            }
            cA.syncAsync(dA).await()

            // The opt-out attach pull crosses the snapshot threshold, so the
            // response is a snapshot.
            val cB = createClient()
            cB.activateAsync().await()
            val dB = Document(documentKey)
            cB.attachDocument(dB, syncMode = Client.SyncMode.Manual, disableGC = true).await()

            assertTrue(
                dB.changeID.lamport >= DEFAULT_SNAPSHOT_THRESHOLD,
                "opt-out client must catch up to server lamport via snapshot, " +
                    "lamport: ${dB.changeID.lamport}",
            )
            assertEquals(
                1,
                dB.getVersionVector().size(),
                "opt-out doc.VV must stay size 1 after snapshot pull",
            )

            cB.detachDocument(dB).await()
            cB.deactivateAsync().await()
            cB.close()
            cA.detachDocument(dA).await()
            cA.deactivateAsync().await()
            cA.close()
        }
    }
}
