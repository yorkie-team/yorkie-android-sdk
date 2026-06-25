/*
 * Copyright 2025 The Yorkie Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.Document
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RevisionTest {

    @Test
    fun can_create_a_revision_and_list_revisions() {
        runBlocking {
            val client = createClient()
            val key = UUID.randomUUID().toString().toDocKey()
            val doc = Document(key)

            client.activateAsync().await()
            client.attachDocument(doc, syncMode = Manual).await()

            // given — initial content synced to server
            doc.updateAsync { root, _ ->
                root["k1"] = "v1"
            }.await()
            client.syncAsync().await()

            // when — create first revision
            val rev1 = client.createRevision(doc, "v1.0", "First revision").await()

            // then
            assertNotNull(rev1)
            assertEquals("v1.0", rev1.label)
            assertEquals("First revision", rev1.description)
            assertTrue(rev1.id.isNotEmpty())

            // given — more changes
            doc.updateAsync { root, _ ->
                root["k2"] = "v2"
            }.await()
            client.syncAsync().await()

            // when — create second revision
            val rev2 = client.createRevision(doc, "v2.0", "Second revision").await()

            // then
            assertEquals("v2.0", rev2.label)

            // when — list all revisions
            val revisions = client.listRevisions(doc).await()

            // then — both revisions are returned (order is server-defined)
            assertTrue(revisions.size >= 2)
            val labels = revisions.map { it.label }.toSet()
            assertTrue("v1.0" in labels)
            assertTrue("v2.0" in labels)

            client.detachDocument(doc).await()
            client.deactivateAsync().await()
            doc.close()
            client.close()
        }
    }

    @Test
    fun can_paginate_revisions() {
        runBlocking {
            val client = createClient()
            val key = UUID.randomUUID().toString().toDocKey()
            val doc = Document(key)

            client.activateAsync().await()
            client.attachDocument(doc, syncMode = Manual).await()

            // given — create 5 revisions
            for (i in 1..5) {
                doc.updateAsync { root, _ -> root["count"] = i }.await()
                client.syncAsync().await()
                client.createRevision(doc, "v$i.0", "Revision $i").await()
            }

            // when — page 1
            val firstPage = client.listRevisions(doc, pageSize = 3).await()
            // then
            assertEquals(3, firstPage.size)

            // when — page 2
            val secondPage = client.listRevisions(doc, pageSize = 3, offset = 3).await()
            // then
            assertEquals(2, secondPage.size)

            client.detachDocument(doc).await()
            client.deactivateAsync().await()
            doc.close()
            client.close()
        }
    }

    @Test
    fun can_retrieve_a_specific_revision_by_id() {
        runBlocking {
            val client = createClient()
            val key = UUID.randomUUID().toString().toDocKey()
            val doc = Document(key)

            client.activateAsync().await()
            client.attachDocument(doc, syncMode = Manual).await()

            // given — initial state synced and snapshotted
            doc.updateAsync { root, _ ->
                root["k1"] = "v1"
                root["k2"] = "v2"
            }.await()
            client.syncAsync().await()
            val rev1 = client.createRevision(doc, "v1.0", "First revision").await()
            assertNotNull(rev1)

            // given — further changes and a second revision
            doc.updateAsync { root, _ ->
                root["k2"] = "modified"
                root["k3"] = "v3"
            }.await()
            client.syncAsync().await()
            val rev2 = client.createRevision(doc, "v2.0", "Second revision").await()
            assertNotNull(rev2)

            // when — retrieve the first revision by id
            val retRev1 = client.getRevision(doc, rev1.id).await()

            // then — first revision matches the snapshot at that point
            assertNotNull(retRev1)
            assertEquals(rev1.id, retRev1.id)
            assertEquals("v1.0", retRev1.label)
            assertEquals("First revision", retRev1.description)
            assertEquals("""{"k1":"v1","k2":"v2"}""", retRev1.snapshot)

            // when — retrieve the second revision by id
            val retRev2 = client.getRevision(doc, rev2.id).await()

            // then — second revision matches the later snapshot
            assertNotNull(retRev2)
            assertEquals(rev2.id, retRev2.id)
            assertEquals("v2.0", retRev2.label)
            assertEquals("Second revision", retRev2.description)
            assertEquals("""{"k1":"v1","k2":"modified","k3":"v3"}""", retRev2.snapshot)

            // then — the two snapshots differ
            assertTrue(retRev1.snapshot != retRev2.snapshot)

            client.detachDocument(doc).await()
            client.deactivateAsync().await()
            doc.close()
            client.close()
        }
    }

    @Test
    fun can_restore_document_to_a_revision() {
        runBlocking {
            val client = createClient()
            val key = UUID.randomUUID().toString().toDocKey()
            val doc = Document(key)

            client.activateAsync().await()
            client.attachDocument(doc, syncMode = Manual).await()

            // given — create initial state and snapshot it
            doc.updateAsync { root, _ ->
                root["k1"] = "v1"
                root["k2"] = "v2"
            }.await()
            client.syncAsync().await()
            val revision = client.createRevision(doc, "v1.0", "Initial state").await()

            // given — modify document after snapshot
            doc.updateAsync { root, _ ->
                root["k1"] = "modified"
                root["k2"] = "v3"
            }.await()
            client.syncAsync().await()
            assertJsonContentEquals("""{"k1":"modified","k2":"v3"}""", doc.toJson())

            // when — restore to snapshot
            client.restoreRevision(doc, revision.id).await()
            client.syncAsync().await()

            // then — document content matches the snapshot
            assertJsonContentEquals("""{"k1":"v1","k2":"v2"}""", doc.toJson())

            client.detachDocument(doc).await()
            client.deactivateAsync().await()
            doc.close()
            client.close()
        }
    }

    @Test
    fun restore_propagates_to_other_clients() {
        withTwoClientsAndDocuments(
            syncMode = Manual,
        ) { c1, c2, d1, d2, _ ->
            // given — both clients have initial state
            d1.updateAsync { root, _ ->
                root["k1"] = "v1"
                root["k2"] = "v2"
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":"v1","k2":"v2"}""", d1.toJson())
            assertJsonContentEquals("""{"k1":"v1","k2":"v2"}""", d2.toJson())

            // given — c1 creates a revision of the current state
            val revision = c1.createRevision(d1, "v1.0", "Initial state").await()

            // given — c1 makes changes
            d1.updateAsync { root, _ ->
                root["k1"] = "modified"
                root["k2"] = "v3"
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":"modified","k2":"v3"}""", d1.toJson())
            assertJsonContentEquals("""{"k1":"modified","k2":"v3"}""", d2.toJson())

            // when — c1 restores and syncs
            c1.restoreRevision(d1, revision.id).await()
            c1.syncAsync().await()

            // when — c2 syncs to receive restore
            c2.syncAsync().await()

            // then — both clients converge to the restored state
            assertJsonContentEquals("""{"k1":"v1","k2":"v2"}""", d1.toJson())
            assertJsonContentEquals("""{"k1":"v1","k2":"v2"}""", d2.toJson())
        }
    }
}
