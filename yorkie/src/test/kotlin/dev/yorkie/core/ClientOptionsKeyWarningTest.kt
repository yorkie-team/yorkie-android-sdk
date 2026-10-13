package dev.yorkie.core

import dev.yorkie.helper.RecordingLogger
import dev.yorkie.util.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Ports JS SDK `client_options_test.ts`'s `describe('Client key with offline persistence')`
 * (`3b9c668c`, #1402) as JVM unit tests. JS captures
 * `console.warn`; Android has no `console` — [Logger.init] with a [RecordingLogger] captures the
 * [dev.yorkie.util.Logger.Companion.logError] call `Client`'s `init {}` block makes instead
 * (`Logger` has only DEBUG/ERROR, so the warning is emitted via `logError`, not a dedicated `warn`).
 *
 * JS's 4th case reads `client.getKey()`; `Client` has no public key accessor on Android (`options`
 * is private), so `keeps the generated key when only a store is configured` pins
 * [Client.Options.key]'s own default-value expression directly instead — the exact code path
 * `Client`'s constructor reads to decide whether to warn.
 *
 * Two Android-only cases pin Android-specific decisions: a re-passed key that
 * happens to equal a previously generated one is indistinguishable from a defaulted key and warns
 * falsely (accepted — the cost is one extra warning, never a correctness break), and two `Client`
 * instances warn independently of each other (the generated-key weak set is membership-only, never
 * consumed/cleared by a warning).
 */
class ClientOptionsKeyWarningTest {

    private lateinit var recordingLogger: RecordingLogger

    @Before
    fun setUp() {
        recordingLogger = RecordingLogger()
        Logger.init(recordingLogger)
    }

    @After
    fun tearDown() {
        // Reinstall a fresh, non-capturing instance so this test's captured state cannot leak
        // into another test class relying on Logger's process-wide singleton (see RecordingLogger's
        // own KDoc, Helper.kt).
        Logger.init(RecordingLogger())
    }

    @Test
    fun `warns when a store is configured without a client key`() {
        Client(host = "0.0.0.0", options = Client.Options(docStore = MemoryDocStore()))

        assertEquals(1, recordingLogger.errorMessages.size)
        assertTrue(recordingLogger.errorMessages.single().contains("stable clientKey"))
    }

    @Test
    fun `stays silent when a store is configured with an explicit client key`() {
        Client(
            host = "0.0.0.0",
            options = Client.Options(key = "stable-key", docStore = MemoryDocStore()),
        )

        assertTrue(recordingLogger.errorMessages.isEmpty())
    }

    @Test
    fun `stays silent when no store is configured`() {
        Client(host = "0.0.0.0", options = Client.Options())

        assertTrue(recordingLogger.errorMessages.isEmpty())
    }

    @Test
    fun `keeps the generated key when only a store is configured`() {
        val options = Client.Options(docStore = MemoryDocStore())

        assertTrue(options.key.isNotEmpty())
        Client(host = "0.0.0.0", options = options)
    }

    @Test
    fun `a re-passed key equal to a previously generated one still warns falsely`() {
        val firstOptions = Client.Options(docStore = MemoryDocStore())
        val recycledKey = firstOptions.key
        Client(host = "0.0.0.0", options = firstOptions)
        assertEquals(1, recordingLogger.errorMessages.size)

        // The accepted false positive: the explicit key equals a string that was previously
        // generated, so it is still a member of the generated-key set; detection is
        // set-membership, not "was `key` passed as a named argument" — the Options data class has
        // no way to tell those apart. Still just a warning, never a thrown error or a dropped key.
        Client(
            host = "0.0.0.0",
            options = Client.Options(key = recycledKey, docStore = MemoryDocStore()),
        )
        assertEquals(2, recordingLogger.errorMessages.size)
        recordingLogger.errorMessages.forEach { assertTrue(it.contains("stable clientKey")) }
    }

    @Test
    fun `two Clients with generated keys warn independently`() {
        Client(host = "0.0.0.0", options = Client.Options(docStore = MemoryDocStore()))
        Client(host = "0.0.0.0", options = Client.Options(docStore = MemoryDocStore()))

        assertEquals(2, recordingLogger.errorMessages.size)
        recordingLogger.errorMessages.forEach { assertTrue(it.contains("stable clientKey")) }
    }
}
