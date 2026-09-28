package dev.yorkie.core

import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.document.Document
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `session_lock_test.ts` (`2291bf67`/#1338, RTCOLLABPLATFORM-771). Pins
 * the [SessionLock] contract with a test-local in-memory lock, the attach-time guard decision
 * (a contended lock fails the attach fast), and the [NoopSessionLock] default (maps the JS
 * `WebLocksSessionLock` non-browser cases onto the no-op).
 */
class SessionLockTest {

    /** A minimal in-memory [SessionLock]: fails fast (returns null) on a held name. */
    private class TestSessionLock : SessionLock {
        private val held = ConcurrentHashMap<String, Boolean>()

        override suspend fun acquire(name: String): SessionLockHandle? {
            if (held.putIfAbsent(name, true) != null) return null
            return object : SessionLockHandle {
                override fun release() {
                    held.remove(name)
                }
            }
        }
    }

    @Test
    fun `T1 acquire holds the named lock`() = runTest {
        val lock = TestSessionLock()

        assertNotNull(lock.acquire("doc-a"))
    }

    @Test
    fun `T2 a second acquire of a held name fails fast`() = runTest {
        val lock = TestSessionLock()
        lock.acquire("doc-a")

        assertNull(lock.acquire("doc-a"))
    }

    @Test
    fun `T3 acquire succeeds again after release`() = runTest {
        val lock = TestSessionLock()
        val handle = checkNotNull(lock.acquire("doc-a"))

        handle.release()

        assertNotNull(lock.acquire("doc-a"))
    }

    @Test
    fun `T4 release is idempotent`() = runTest {
        val lock = TestSessionLock()
        val handle = checkNotNull(lock.acquire("doc-a"))

        handle.release()
        handle.release()

        assertNotNull(lock.acquire("doc-a"))
    }

    @Test
    fun `T5 distinct names are isolated`() = runTest {
        val lock = TestSessionLock()
        lock.acquire("doc-a")

        assertNotNull(lock.acquire("doc-b"))
    }

    @Test
    fun `T6 a released name can be re-acquired by a different caller`() = runTest {
        val lock = TestSessionLock()
        val first = checkNotNull(lock.acquire("doc-a"))
        first.release()

        val second = lock.acquire("doc-a")

        assertNotNull(second)
    }

    @Test
    fun `T7 acquiring an unrelated name while one is held does not contend`() = runTest {
        val lock = TestSessionLock()
        lock.acquire("doc-a")
        lock.acquire("doc-b")

        assertNull(lock.acquire("doc-a"))
        assertNull(lock.acquire("doc-b"))
    }

    @Test
    fun `T8 NoopSessionLock always grants`() = runTest {
        assertNotNull(NoopSessionLock.acquire("any-name"))
        assertNotNull(NoopSessionLock.acquire("any-name"))
    }

    @Test
    fun `T9 NoopSessionLock release is a no-op`() = runTest {
        val handle = NoopSessionLock.acquire("any-name")

        handle.release()
        handle.release()
    }

    @Test
    fun `T10 an attach fails fast with ErrInvalidArgument when the session lock is contended`() =
        runTest {
            val lock = TestSessionLock()
            lock.acquire("yorkie-session:$TEST_KEY/${TEST_KEY}2/doc-a")

            val client = Client(
                options = Client.Options(
                    key = "${TEST_KEY}2",
                    apiKey = TEST_KEY,
                    docStore = MemoryDocStore(),
                    sessionLock = lock,
                ),
                host = "0.0.0.0",
            )
            client.service = MockYorkieService()
            client.activateAsync().await()

            val document = Document("doc-a")
            val result = client.attachDocument(document).await()

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertEquals(ErrInvalidArgument, (exception as? YorkieException)?.code)
            assertFalse(client.has("doc-a"))

            client.close()
        }

    @Test
    fun `T11 a SessionLock that throws on acquire fails the attach without registering it`() =
        runTest {
            val throwingLock = object : SessionLock {
                override suspend fun acquire(name: String): SessionLockHandle? {
                    throw IllegalStateException("lock backend unavailable")
                }
            }
            val client = Client(
                options = Client.Options(
                    key = TEST_KEY,
                    apiKey = TEST_KEY,
                    docStore = MemoryDocStore(),
                    sessionLock = throwingLock,
                ),
                host = "0.0.0.0",
            )
            client.service = MockYorkieService()
            client.activateAsync().await()

            val document = Document("doc-b")
            val result = client.attachDocument(document).await()

            assertTrue(result.isFailure)
            assertFalse(client.has("doc-b"))

            client.close()
        }
}
