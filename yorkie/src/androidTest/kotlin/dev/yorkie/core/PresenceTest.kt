package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.PresenceChange.MyPresence
import dev.yorkie.document.Document.Event.PresenceChange.Others
import dev.yorkie.gson
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresenceTest {

    @Test
    fun test_presence_from_snapshot() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            val snapshotThreshold = 500
            repeat(snapshotThreshold) {
                d1.updateAsync { _, presence ->
                    presence.set(mapOf("key" to "$it"))
                }.await()
            }

            assertEquals(
                mapOf("key" to "${snapshotThreshold - 1}"),
                d1.presences.value[c1.requireClientId()],
            )

            c1.syncAsync().await()
            c2.syncAsync().await()

            assertEquals(
                mapOf("key" to "${snapshotThreshold - 1}"),
                d2.presences.value[c1.requireClientId()],
            )
        }
    }

    @Test
    fun test_presence_with_attach_and_detach() {
        withTwoClientsAndDocuments(
            detachDocuments = false,
            realTimeSync = false,
            presences = mapOf("key" to "key1") to mapOf("key" to "key2"),
        ) { c1, c2, d1, d2, _ ->
            assertEquals(mapOf("key" to "key1"), d1.presences.value[c1.requireClientId()])
            assertNull(d1.presences.value[c2.requireClientId()])
            assertEquals(mapOf("key" to "key2"), d2.presences.value[c2.requireClientId()])
            assertEquals(mapOf("key" to "key1"), d2.presences.value[c1.requireClientId()])

            c1.syncAsync().await()
            assertEquals(mapOf("key" to "key2"), d1.presences.value[c2.requireClientId()])

            c2.detachAsync(d2).await()
            c1.syncAsync().await()
            assertNull(d1.presences.value[c2.requireClientId()])

            c1.detachAsync(d1).await()
        }
    }

    @Test
    fun test_initial_presence_value_without_manual_initialization() {
        withTwoClientsAndDocuments(realTimeSync = false) { c1, c2, d1, d2, _ ->
            val emptyMap = emptyMap<String, String>()
            assertEquals(emptyMap, d1.presences.value[c1.requireClientId()])
            assertNull(d1.presences.value[c2.requireClientId()])
            assertEquals(emptyMap, d2.presences.value[c1.requireClientId()])
            assertEquals(emptyMap, d2.presences.value[c2.requireClientId()])
        }
    }

    @Test
    fun test_presence_sync() {
        withTwoClientsAndDocuments(
            presences = mapOf("name" to "a") to mapOf("name" to "b"),
        ) { c1, c2, d1, d2, _ ->
            val d1Events = mutableListOf<Document.Event>()
            val d2Events = mutableListOf<Document.Event>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filterIsInstance<Document.Event.PresenceChange>()
                        .filterNot { it is MyPresence.Initialized }
                        .collect(d1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filterIsInstance<Document.Event.PresenceChange>()
                        .filterNot { it is MyPresence.Initialized }
                        .collect(d2Events::add)
                },
            )

            d1.updateAsync { _, presence ->
                presence.set(mapOf("name" to "A"))
            }.await()
            d2.updateAsync { _, presence ->
                presence.set(mapOf("name" to "B"))
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 3 || d2Events.size < 2) {
                    delay(50)
                }
            }

            assertEquals(
                listOf(
                    MyPresence.PresenceChanged(
                        PresenceInfo(c1.requireClientId(), mapOf("name" to "A")),
                    ),
                    Others.Watched(PresenceInfo(c2.requireClientId(), mapOf("name" to "b"))),
                    Others.PresenceChanged(
                        PresenceInfo(c2.requireClientId(), mapOf("name" to "B")),
                    ),
                ),
                d1Events,
            )
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
            assertEquals(d1.presences.value.entries, d2.presences.value.entries)

            collectJobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_partial_presence_update() {
        val previousCursor = gson.toJson(Cursor(0, 0))
        val updatedCursor = gson.toJson(Cursor(1, 1))

        withTwoClientsAndDocuments(
            realTimeSync = false,
            presences = mapOf("key" to "key1", "cursor" to previousCursor) to mapOf(
                "key" to "key2",
                "cursor" to previousCursor,
            ),
        ) { c1, c2, d1, d2, _ ->
            d1.updateAsync { _, presence ->
                presence.set(mapOf("cursor" to gson.toJson(Cursor(1, 1))))
            }.await()

            assertEquals(
                mapOf("key" to "key1", "cursor" to updatedCursor),
                d1.presences.value[c1.requireClientId()],
            )

            c1.syncAsync().await()
            c2.syncAsync().await()

            assertEquals(
                mapOf("key" to "key1", "cursor" to updatedCursor),
                d2.presences.value[c1.requireClientId()],
            )
        }
    }

    @Test
    fun test_presence_changed_event_with_multiple_changes() {
        val previousCursor = gson.toJson(Cursor(0, 0))
        val updatedCursor = gson.toJson(Cursor(1, 1))

        withTwoClientsAndDocuments(
            presences = mapOf("name" to "a", "cursor" to previousCursor) to mapOf(
                "name" to "b",
                "cursor" to previousCursor,
            ),
        ) { c1, c2, d1, d2, _ ->
            val d1Events = mutableListOf<Document.Event>()
            val d2Events = mutableListOf<Document.Event>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filterIsInstance<Document.Event.PresenceChange>()
                        .filterNot { it is MyPresence.Initialized }
                        .collect(d1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filterIsInstance<Document.Event.PresenceChange>()
                        .filterNot { it is MyPresence.Initialized }
                        .collect(d2Events::add)
                },
            )

            d1.updateAsync { _, presence ->
                presence.set(mapOf("name" to "A"))
                presence.set(mapOf("cursor" to updatedCursor))
                presence.set(mapOf("name" to "X"))
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 2 || d2Events.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(
                listOf(
                    MyPresence.PresenceChanged(
                        PresenceInfo(
                            c1.requireClientId(),
                            mapOf("name" to "X", "cursor" to updatedCursor),
                        ),
                    ),
                    Others.Watched(
                        PresenceInfo(
                            c2.requireClientId(),
                            mapOf("name" to "b", "cursor" to previousCursor),
                        ),
                    ),
                ),
                d1Events,
            )
            assertEquals(
                listOf(
                    Others.PresenceChanged(
                        PresenceInfo(
                            c1.requireClientId(),
                            mapOf("name" to "X", "cursor" to updatedCursor),
                        ),
                    ),
                ),
                d2Events,
            )

            collectJobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_unwatched_event_on_detachment() {
        withTwoClientsAndDocuments(
            detachDocuments = false,
            presences = mapOf("name" to "a") to mapOf("name" to "b"),
        ) { c1, c2, d1, d2, _ ->
            val d1Events = mutableListOf<Document.Event>()
            val d2Events = mutableListOf<Document.Event>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filterIsInstance<Document.Event.PresenceChange>()
                        .filterNot { it is MyPresence.Initialized }
                        .collect(d1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filterIsInstance<Document.Event.PresenceChange>()
                        .filterNot { it is MyPresence.Initialized }
                        .collect(d2Events::add)
                },
            )

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.isEmpty()) {
                    delay(50)
                }
            }
            c2.detachAsync(d2).await()
            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 2) {
                    delay(50)
                }
            }

            assertEquals(
                listOf(
                    Others.Watched(PresenceInfo(c2.requireClientId(), mapOf("name" to "b"))),
                    Others.Unwatched(c2.requireClientId()),
                ),
                d1Events,
            )
            assertEquals(
                mapOf(c1.requireClientId() to mapOf("name" to "a")),
                d1.presences.value.toMap(),
            )
            assertEquals(d1.presences.value.entries, d2.presences.value.entries)

            c1.detachAsync(d1).await()
            collectJobs.forEach(Job::cancel)
        }
    }

    private data class Cursor(val x: Int, val y: Int)
}
