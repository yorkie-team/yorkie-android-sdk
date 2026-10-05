package dev.yorkie.core

import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `client_options_test.ts` (`2291bf67`/#1338, RTCOLLABPLATFORM-771).
 * `docStore`/`sessionLock` defaults and an injected override. The two JS `deactivateOnUnload`
 * cases are N/A on Android (no page-unload event to auto-default against — recorded, spec 025 §3).
 */
class ClientOptionsTest {

    @Test
    fun `T1 docStore defaults to null`() {
        assertNull(Client.Options().docStore)
    }

    @Test
    fun `T2 sessionLock defaults to NoopSessionLock`() {
        assertSame(NoopSessionLock, Client.Options().sessionLock)
    }

    @Test
    fun `T3 an injected docStore and sessionLock are retained on Options`() {
        val store = MemoryDocStore()
        val lock = NoopSessionLock

        val options = Client.Options(docStore = store, sessionLock = lock)

        assertSame(store, options.docStore)
        assertSame(lock, options.sessionLock)
    }
}
