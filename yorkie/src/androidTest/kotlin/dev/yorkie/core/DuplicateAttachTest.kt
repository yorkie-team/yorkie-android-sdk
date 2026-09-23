package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrAlreadyAttached
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Port of yorkie-js-sdk's `duplicate_attach_test.ts` (yorkie-js-sdk#1337 /
 * `dd28a47c`, RTCOLLABPLATFORM-771). Pins that attaching a second [Document]
 * instance under an already-attached (or in-flight) key fails with
 * [ErrAlreadyAttached] instead of deactivating the whole client.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateAttachTest {

    private var client: Client? = null

    @After
    fun tearDown() {
        runBlocking {
            client?.let { c ->
                if (c.isActive) {
                    c.deactivateAsync().await()
                }
                c.close()
            }
        }
    }

    @Test
    fun test_rejects_re_attaching_an_already_attached_key_without_killing_the_client() {
        runBlocking {
            val c1 = createClient()
            client = c1
            val key = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(key)
            c1.activateAsync().await()

            c1.attachDocument(d1).await()

            val d2 = Document(key)
            val exception = assertFailsWith(YorkieException::class) {
                c1.attachDocument(d2).await()
            }
            assertEquals(ErrAlreadyAttached, exception.code)
            assertTrue(c1.isActive)
            assertTrue(c1.has(key))

            c1.detachDocument(d1).await()
            c1.deactivateAsync().await()
        }
    }

    @Test
    fun test_rejects_a_concurrent_attach_of_the_same_key_while_the_first_is_in_flight() {
        runBlocking {
            val c1 = createClient()
            client = c1
            val key = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(key)
            c1.activateAsync().await()

            val first = c1.attachDocument(d1)
            val d2 = Document(key)
            val exception = assertFailsWith(YorkieException::class) {
                c1.attachDocument(d2).await()
            }
            assertEquals(ErrAlreadyAttached, exception.code)

            assertTrue(first.await().isSuccess)
            assertTrue(c1.isActive)
            assertTrue(c1.has(key))

            c1.detachDocument(d1).await()
            c1.deactivateAsync().await()
        }
    }
}
