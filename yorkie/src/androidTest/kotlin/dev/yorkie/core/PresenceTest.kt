package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.Client.SyncMode.Realtime
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.PresenceChanged
import dev.yorkie.document.Document.Event.PresenceChanged.MyPresence
import dev.yorkie.document.Document.Event.PresenceChanged.Others
import dev.yorkie.document.Document.Event.StreamConnectionChanged
import dev.yorkie.gson
import java.util.UUID
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresenceTest {

    @Test
    fun test_presence_from_snapshot() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            val snapshotThreshold = 500
            repeat(snapshotThreshold) {
                d1.updateAsync { _, presence ->
                    presence.put(mapOf("key" to "$it"))
                }.await()
            }

            assertEquals(
                mapOf("key" to "${snapshotThreshold - 1}"),
                d1.allPresences.value[c1.requireClientId()],
            )

            c1.syncAsync().await()
            c2.syncAsync().await()

            assertEquals(
                mapOf("key" to "${snapshotThreshold - 1}"),
                d2.allPresences.value[c1.requireClientId()],
            )
        }
    }

    @Test
    fun test_presence_with_attach_and_detach() {
        withTwoClientsAndDocuments(
            detachDocuments = false,
            syncMode = Manual,
            presences = mapOf("key" to "key1") to mapOf("key" to "key2"),
        ) { c1, c2, d1, d2, _ ->
            assertEquals(mapOf("key" to "key1"), d1.allPresences.value[c1.requireClientId()])
            assertNull(d1.allPresences.value[c2.requireClientId()])
            assertEquals(mapOf("key" to "key2"), d2.allPresences.value[c2.requireClientId()])
            assertEquals(mapOf("key" to "key1"), d2.allPresences.value[c1.requireClientId()])

            c1.syncAsync().await()
            assertEquals(mapOf("key" to "key2"), d1.allPresences.value[c2.requireClientId()])

            c2.detachAsync(d2).await()
            c1.syncAsync().await()
            assertNull(d1.allPresences.value[c2.requireClientId()])

            c1.detachAsync(d1).await()
        }
    }

    @Test
    fun test_initial_presence_value_without_manual_initialization() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            val emptyMap = emptyMap<String, String>()
            assertEquals(emptyMap, d1.allPresences.value[c1.requireClientId()])
            assertNull(d1.allPresences.value[c2.requireClientId()])
            assertEquals(emptyMap, d2.allPresences.value[c1.requireClientId()])
            assertEquals(emptyMap, d2.allPresences.value[c2.requireClientId()])
        }
    }

    @Test
    fun test_presence_sync() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(documentKey)
            val d2 = Document(documentKey)
            val d1Events = mutableListOf<Document.Event>()
            val d2Events = mutableListOf<Document.Event>()

            c1.activateAsync().await()
            c2.activateAsync().await()

            val d1Job = launch(start = CoroutineStart.UNDISPATCHED) {
                d1.events.filterIsInstance<PresenceChanged>()
                    .collect(d1Events::add)
            }
            c1.attachAsync(d1, initialPresence = mapOf("name" to "a")).await()

            withTimeout(GENERAL_TIMEOUT) {
                // initialized, my-presence changed
                while (d1Events.size < 2) {
                    delay(50)
                }
            }
            d1Events.clear()

            val d2Job = launch(start = CoroutineStart.UNDISPATCHED) {
                d2.events.filterIsInstance<PresenceChanged>()
                    .collect(d2Events::add)
            }
            c2.attachAsync(d2, initialPresence = mapOf("name" to "b")).await()

            withTimeout(GENERAL_TIMEOUT) {
                // initialized, my-presence changed
                while (d2Events.size < 2) {
                    delay(50)
                }
            }
            d2Events.clear()

            withTimeout(GENERAL_TIMEOUT) {
                // watched from c2
                while (d1Events.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(
                Others.Watched(PresenceInfo(c2.requireClientId(), mapOf("name" to "b"))),
                d1Events.last(),
            )

            d1.updateAsync { _, presence ->
                presence.put(mapOf("name" to "A"))
            }.await()
            d2.updateAsync { _, presence ->
                presence.put(mapOf("name" to "B"))
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 3) {
                    delay(50)
                }
            }

            assertEquals(
                listOf(
                    MyPresence.PresenceChanged(
                        PresenceInfo(c1.requireClientId(), mapOf("name" to "A")),
                    ),
                    Others.PresenceChanged(
                        PresenceInfo(c2.requireClientId(), mapOf("name" to "B")),
                    ),
                ),
                d1Events.takeLast(2),
            )

            withTimeout(GENERAL_TIMEOUT) {
                while (d2Events.size < 2) {
                    delay(50)
                }
            }

            assertEquals(
                listOf(
                    MyPresence.PresenceChanged(
                        PresenceInfo(
                            c2.requireClientId(),
                            mapOf("name" to "B"),
                        ),
                    ),
                    Others.PresenceChanged(
                        PresenceInfo(c1.requireClientId(), mapOf("name" to "A")),
                    ),
                ),
                d2Events,
            )
            assertEquals(d1.presences.value.toMap(), d2.presences.value.toMap())

            d1Job.cancel()
            d2Job.cancel()
            c1.detachAsync(d1).await()
            c2.detachAsync(d2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            d1.close()
            d2.close()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_partial_presence_update() {
        val previousCursor = gson.toJson(Cursor(0, 0))
        val updatedCursor = gson.toJson(Cursor(1, 1))

        withTwoClientsAndDocuments(
            syncMode = Manual,
            presences = mapOf("key" to "key1", "cursor" to previousCursor) to mapOf(
                "key" to "key2",
                "cursor" to previousCursor,
            ),
        ) { c1, c2, d1, d2, _ ->
            d1.updateAsync { _, presence ->
                presence.put(mapOf("cursor" to gson.toJson(Cursor(1, 1))))
            }.await()

            assertEquals(
                mapOf("key" to "key1", "cursor" to updatedCursor),
                d1.allPresences.value[c1.requireClientId()],
            )

            c1.syncAsync().await()
            c2.syncAsync().await()

            assertEquals(
                mapOf("key" to "key1", "cursor" to updatedCursor),
                d2.allPresences.value[c1.requireClientId()],
            )
        }
    }

    @Test
    fun test_presence_changed_event_with_multiple_changes() {
        val previousCursor = gson.toJson(Cursor(0, 0))
        val updatedCursor = gson.toJson(Cursor(1, 1))

        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(documentKey)
            val d2 = Document(documentKey)
            val d1Events = mutableListOf<Document.Event>()
            val d2Events = mutableListOf<Document.Event>()

            c1.activateAsync().await()
            c2.activateAsync().await()

            val c1ID = c1.requireClientId()
            val c2ID = c2.requireClientId()

            val d1Job = launch(start = CoroutineStart.UNDISPATCHED) {
                d1.events.filterIsInstance<PresenceChanged>().collect(d1Events::add)
            }
            c1.attachAsync(d1, mapOf("name" to "a", "cursor" to previousCursor)).await()

            withTimeout(GENERAL_TIMEOUT) {
                // initialized, my-presence changed
                while (d1Events.size < 2) {
                    delay(50)
                }
            }
            d1Events.clear()

            val d2Job = launch(start = CoroutineStart.UNDISPATCHED) {
                d2.events.filterIsInstance<Others>().collect(d2Events::add)
            }
            c2.attachAsync(d2, mapOf("name" to "b", "cursor" to previousCursor)).await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(
                Others.Watched(PresenceInfo(c2ID, d2.presences.value[c2ID]!!)),
                d1Events.last(),
            )

            d1.updateAsync { _, presence ->
                presence.put(mapOf("name" to "A"))
                presence.put(mapOf("cursor" to updatedCursor))
                presence.put(mapOf("name" to "X"))
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 2 || d2Events.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(
                MyPresence.PresenceChanged(
                    PresenceInfo(c1ID, mapOf("name" to "X", "cursor" to updatedCursor)),
                ),
                d1Events.last(),
            )
            assertEquals(
                Others.PresenceChanged(
                    PresenceInfo(c1ID, mapOf("name" to "X", "cursor" to updatedCursor)),
                ),
                d2Events.last(),
            )

            d1Job.cancel()
            d2Job.cancel()
            c1.detachAsync(d1).await()
            c2.detachAsync(d2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            d1.close()
            d2.close()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_unwatched_event_on_detachment() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(documentKey)
            val d2 = Document(documentKey)
            val d1Events = mutableListOf<Document.Event>()

            c1.activateAsync().await()
            c2.activateAsync().await()

            val c1ID = c1.requireClientId()
            val c2ID = c2.requireClientId()

            c1.attachAsync(d1, initialPresence = mapOf("name" to "a")).await()

            val d1CollectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                d1.events.filterIsInstance<Others>().collect(d1Events::add)
            }
            c2.attachAsync(d2, initialPresence = mapOf("name" to "b")).await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.isEmpty()) {
                    delay(50)
                }
            }
            assertEquals(
                Others.Watched(PresenceInfo(c2ID, mapOf("name" to "b"))),
                d1Events.first(),
            )

            c2.detachAsync(d2).await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 2) {
                    delay(50)
                }
            }

            assertEquals(
                Others.Unwatched(PresenceInfo(c2.requireClientId(), mapOf("name" to "b"))),
                d1Events.last(),
            )

            assertEquals(mapOf(c1ID to mapOf("name" to "a")), d1.presences.value.toMap())
            assertEquals(d1.presences.value.entries, d2.presences.value.entries)

            d1CollectJob.cancel()
            c1.detachAsync(d1).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            d1.close()
            d2.close()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_returning_online_clients_only() {
        val cursor = gson.toJson(Cursor(0, 0))

        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val c3 = createClient()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(documentKey)
            val d2 = Document(documentKey)
            val d3 = Document(documentKey)
            val d1Events = mutableListOf<Document.Event>()

            c1.activateAsync().await()
            c2.activateAsync().await()
            c3.activateAsync().await()

            val c1ID = c1.requireClientId()
            val c2ID = c2.requireClientId()
            val c3ID = c3.requireClientId()

            c1.attachAsync(d1, initialPresence = mapOf("name" to "a1", "cursor" to cursor)).await()

            val d1CollectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                d1.events.filterIsInstance<Others>().collect(d1Events::add)
            }

            // 01. c2 attaches doc in realtime sync, and c3 attached doc in manual sync.
            c2.attachAsync(d2, initialPresence = mapOf("name" to "b1", "cursor" to cursor)).await()
            c3.attachAsync(d3, mapOf("name" to "c1", "cursor" to cursor), syncMode = Manual).await()

            withTimeout(GENERAL_TIMEOUT) {
                // c2 watched
                while (d1Events.isEmpty()) {
                    delay(50)
                }
            }

            assertIs<Others.Watched>(d1Events.last())
            assertEquals(
                mapOf(c1ID to d1.presences.value[c1ID], c2ID to d2.presences.value[c2ID]),
                d1.presences.value.toMap(),
            )
            assertNull(d1.presences.value[c3ID])

            // 02. c2 pauses the document (in manual sync), c3 resumes the document (in realtime sync).
            c2.changeSyncMode(d2, Manual)

            withTimeout(GENERAL_TIMEOUT) {
                // c2 unwatched
                while (d1Events.size < 2) {
                    delay(50)
                }
            }

            assertIs<Others.Unwatched>(d1Events.last())
            c3.changeSyncMode(d3, Realtime)

            withTimeout(GENERAL_TIMEOUT) {
                // c3 watched
                while (d1Events.size < 3) {
                    delay(50)
                }
            }

            assertIs<Others.Watched>(d1Events.last())
            withTimeout(GENERAL_TIMEOUT) {
                d3.presences.first { c3ID in d3.presences.value }
            }
            assertEquals(
                mapOf(c1ID to d1.presences.value[c1ID], c3ID to d3.presences.value[c3ID]),
                d1.presences.value.toMap(),
            )
            assertNull(d1.presences.value[c2ID])

            d1CollectJob.cancel()
            c1.detachAsync(d1).await()
            c3.detachAsync(d3).await()
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

    @Test
    fun test_receiving_presence_events_only_for_realtime_sync() {
        val cursor = gson.toJson(Cursor(0, 0))
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val c3 = createClient()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(documentKey)
            val d2 = Document(documentKey)
            val d3 = Document(documentKey)
            val d1Events = mutableListOf<Document.Event>()

            c1.activateAsync().await()
            c2.activateAsync().await()
            c3.activateAsync().await()

            val c2ID = c2.requireClientId()
            val c3ID = c3.requireClientId()

            c1.attachAsync(d1, initialPresence = mapOf("name" to "a1", "cursor" to cursor)).await()

            val d1CollectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                d1.events.filterIsInstance<Others>().collect(d1Events::add)
            }

            // 01. c2 attaches doc in realtime sync, and c3 attached doc in manual sync.
            //     c1 receives the watched event from c2.
            c2.attachAsync(d2, initialPresence = mapOf("name" to "b1", "cursor" to cursor)).await()
            c3.attachAsync(d3, mapOf("name" to "c1", "cursor" to cursor), syncMode = Manual).await()

            withTimeout(GENERAL_TIMEOUT) {
                // c2 watched
                while (d1Events.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(1, d1Events.size)
            assertEquals(
                Others.Watched(PresenceInfo(c2ID, mapOf("name" to "b1", "cursor" to cursor))),
                d1Events.last(),
            )

            // 02. c2 and c3 update the presence.
            //     c1 receives the presence-changed event from c2.
            d2.updateAsync { _, presence ->
                presence.put(mapOf("name" to "b2"))
            }.await()

            d3.updateAsync { _, presence ->
                presence.put(mapOf("name" to "c2"))
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                // c2 presence changed
                while (d1Events.size < 2) {
                    delay(50)
                }
            }

            assertEquals(2, d1Events.size)
            assertEquals(
                Others.PresenceChanged(
                    PresenceInfo(
                        c2ID,
                        mapOf("name" to "b2", "cursor" to cursor),
                    ),
                ),
                d1Events.last(),
            )

            // 03-1. c2 pauses the document, c1 receives an unwatched event from c2.
            c2.changeSyncMode(d2, Manual)

            withTimeout(GENERAL_TIMEOUT) {
                // c2 unwatched
                while (d1Events.size < 3) {
                    delay(50)
                }
            }

            assertEquals(3, d1Events.size)
            assertEquals(
                Others.Unwatched(PresenceInfo(c2ID, mapOf("name" to "b2", "cursor" to cursor))),
                d1Events.last(),
            )

            // 03-2. c3 resumes the document, c1 receives a watched event from c3.
            // NOTE(chacha912): The events are influenced by the timing of realtime sync
            // and watch stream resolution. For deterministic testing, the resume is performed
            // after the sync. Since the sync updates c1 with all previous presence changes
            // from c3, only the watched event is triggered.
            c3.syncAsync().await()
            c1.syncAsync().await()
            delay(50)
            c3.changeSyncMode(d3, Realtime)

            withTimeout(GENERAL_TIMEOUT) {
                // c3 watched
                while (d1Events.size < 4) {
                    delay(50)
                }
            }

            assertEquals(4, d1Events.size)
            assertEquals(
                Others.Watched(PresenceInfo(c3ID, mapOf("name" to "c2", "cursor" to cursor))),
                d1Events.last(),
            )

            // 04. c2 and c3 update the presence.
            //     c1 receives the presence-changed event from c3.
            d2.updateAsync { _, presence ->
                presence.put(mapOf("name" to "b3"))
            }.await()

            d3.updateAsync { _, presence ->
                presence.put(mapOf("name" to "c3"))
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                // c3 presence changed
                while (d1Events.size < 5) {
                    delay(50)
                }
            }

            assertEquals(5, d1Events.size)
            assertEquals(
                Others.PresenceChanged(
                    PresenceInfo(
                        c3ID,
                        mapOf("name" to "c3", "cursor" to cursor),
                    ),
                ),
                d1Events.last(),
            )

            // 05-1. c3 pauses the document, c1 receives an unwatched event from c3.
            c3.changeSyncMode(d3, Manual)

            withTimeout(GENERAL_TIMEOUT) {
                // c3 unwatched
                while (d1Events.size < 6) {
                    delay(50)
                }
            }

            assertEquals(6, d1Events.size)
            assertEquals(
                Others.Unwatched(PresenceInfo(c3ID, mapOf("name" to "c3", "cursor" to cursor))),
                d1Events.last(),
            )

            // 05-2. c2 resumes the document, c1 receives a watched event from c2.
            c2.syncAsync().await()
            c1.syncAsync().await()
            delay(50)
            c2.changeSyncMode(d2, Realtime)

            withTimeout(GENERAL_TIMEOUT) {
                // c2 watched
                while (d1Events.size < 7) {
                    delay(50)
                }
            }

            assertEquals(7, d1Events.size)
            assertEquals(
                Others.Watched(PresenceInfo(c2ID, mapOf("name" to "b3", "cursor" to cursor))),
                d1Events.last(),
            )

            d1CollectJob.cancel()
            c1.detachAsync(d1).await()
            c2.detachAsync(d2).await()
            c3.detachAsync(d3).await()
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

    @Test
    fun test_receive_document_events_according_to_presence_changes() {
        withTwoClientsAndDocuments(
            syncMode = Manual,
            detachDocuments = false,
        ) { c1, c2, d1, d2, _ ->
            val d1Events = mutableListOf<PresenceChanged>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                d1.events.filterIsInstance<Others>()
                    .collect { event ->
                        when (event) {
                            is Others.Watched -> {
                                assertTrue(event.changed.actorID in d1.presences.value)
                            }

                            is Others.Unwatched -> {
                                assertTrue(event.changed.actorID !in d1.presences.value)
                            }

                            is Others.PresenceChanged -> {
                                val (actorID, presence) = event.changed
                                if (actorID !in d1.presences.value) {
                                    return@collect
                                }
                                assertEquals(presence, d1.presences.value[actorID])
                            }
                        }
                        d1Events.add(event)
                    }
            }

            // c1 in realtime sync
            c1.changeSyncMode(d1, Realtime)
            d2.updateAsync { _, presence ->
                presence.put(mapOf("k1" to "v1"))
            }.await()

            // c2 in realtime sync
            c2.changeSyncMode(d2, Realtime)

            withTimeout(GENERAL_TIMEOUT) {
                // watched, presence changed
                while (d1Events.size < 2) {
                    delay(50)
                }
            }

            c2.detachAsync(d2).await()

            withTimeout(GENERAL_TIMEOUT) {
                // unwatched
                while (d1Events.size < 3) {
                    delay(50)
                }
            }

            assertIs<Others.Watched>(d1Events.first())
            assertIs<Others.PresenceChanged>(d1Events[1])
            assertIs<Others.Unwatched>(d1Events.last())

            c1.detachAsync(d1).await()
            collectJob.cancel()
        }
    }

    @Test
    fun test_emit_the_same_presence_multiple_times() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            val d1Events = mutableListOf<PresenceChanged>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                d1.events.filterIsInstance<Others>().collect(d1Events::add)
            }

            c1.changeSyncMode(d1, Realtime)
            c2.changeSyncMode(d2, Realtime)

            d2.updateAsync { _, presence ->
                presence.put(mapOf("a" to "b"))
            }.await()

            delay(100)

            d2.updateAsync { _, presence ->
                presence.put(mapOf("a" to "b"))
            }.await()

            d2.updateAsync { _, presence ->
                presence.put(mapOf("a" to "b"))
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 4) {
                    delay(50)
                }
            }
            assertEquals(4, d1Events.size)
            assertIs<Others.Watched>(d1Events.first())
            d1Events.drop(1).forEach { event ->
                assertEquals(
                    Others.PresenceChanged(PresenceInfo(c2.requireClientId(), mapOf("a" to "b"))),
                    event,
                )
            }
            collectJob.cancel()
        }
    }

    @Test
    fun test_presence_access_after_stream_disconnection() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val key = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(key)
            val d2 = Document(key)

            c1.activateAsync().await()
            c2.activateAsync().await()

            c1.attachAsync(d1, initialPresence = mapOf("name" to "a")).await()
            val d1PresenceEvents = mutableListOf<Others>()
            val d1ConnectionEvents = mutableListOf<StreamConnectionChanged>()
            val jobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filterIsInstance<Others>().collect(d1PresenceEvents::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filterIsInstance<StreamConnectionChanged>()
                        .collect(d1ConnectionEvents::add)
                },
            )

            c2.attachAsync(d2, initialPresence = mapOf("name" to "b")).await()
            withTimeout(GENERAL_TIMEOUT) {
                while (d1PresenceEvents.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(
                Others.Watched(PresenceInfo(c2.requireClientId(), mapOf("name" to "b"))),
                d1PresenceEvents.last(),
            )

            c1.changeSyncMode(d1, Manual)
            withTimeout(GENERAL_TIMEOUT) {
                while (d1ConnectionEvents.none { it is StreamConnectionChanged.Disconnected }) {
                    delay(50)
                }
            }
            assertNull(d1.presences.value[c2.requireClientId()])

            jobs.forEach(Job::cancel)
            c1.detachAsync(d1).await()
            c2.detachAsync(d2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
            d1.close()
            d2.close()
        }
    }

    @Test
    fun test_whether_presence_event_queue_is_empty_after_consecutive_presence_changes() {
        withTwoClientsAndDocuments { c1, _, d1, d2, _ ->
            val d1PresenceEvents = mutableListOf<MyPresence.PresenceChanged>()
            val d2PresenceEvents = mutableListOf<Others.PresenceChanged>()
            val jobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filterIsInstance<MyPresence.PresenceChanged>()
                        .collect(d1PresenceEvents::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filterIsInstance<Others.PresenceChanged>()
                        .collect(d2PresenceEvents::add)
                },
            )

            d1.updateAsync { _, presence ->
                repeat(10) {
                    presence.put(mapOf("a" to "${it + 1}"))
                }
            }.await()

            val lastD1PresenceEvent = MyPresence.PresenceChanged(
                PresenceInfo(c1.requireClientId(), mapOf("a" to "10")),
            )
            val lastD2PresenceEvent = Others.PresenceChanged(
                PresenceInfo(c1.requireClientId(), mapOf("a" to "10")),
            )

            withTimeout(GENERAL_TIMEOUT) {
                while (lastD1PresenceEvent !in d1PresenceEvents ||
                    lastD2PresenceEvent !in d2PresenceEvents
                ) {
                    delay(50)
                }
            }

            assertEquals(lastD1PresenceEvent, d1PresenceEvents.last())
            assertTrue(d1.presenceEventQueue.isEmpty())
            assertEquals(lastD2PresenceEvent, d2PresenceEvents.last())
            assertTrue(d2.presenceEventQueue.isEmpty())

            jobs.forEach(Job::cancel)
        }
    }

    private data class Cursor(val x: Int, val y: Int)
}
