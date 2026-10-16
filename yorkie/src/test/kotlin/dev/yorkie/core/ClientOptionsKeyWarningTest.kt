package dev.yorkie.core

import dev.yorkie.helper.RecordingLogger
import dev.yorkie.util.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
 * Two Android-only cases pin Android-specific decisions: two `Client` instances built
 * from their OWN freshly-generated keys warn independently of each other (the
 * generated-key weak set is membership-only per distinct string), and (D3,
 * `ed25355e`/#1449/yorkie#2111, spec 038_sync-js-sdk-v0725-pre-attach-reissue) a
 * client built with a key string equal to a PREVIOUSLY generated one now stays
 * SILENT the second time: [Client]'s `init {}` consumes a generated key from
 * [generatedClientKeys] the first time it is used (so a `Client.Options.keyGenerated`-
 * equivalent read is correct for the one instance that actually got the default), which
 * also means a re-passed key is no longer mistaken for still-generated on its second use.
 * Before D3, this same scenario warned a SECOND time too (the accepted false positive the
 * generator-recorded weak set alone could not resolve) — the pre-attach re-issue feature
 * needed a real distinction here (a shared key is exactly where a re-issue would be
 * unsound), so the warning's false positive is fixed as a side effect of fixing that.
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

        // Freshly minted per instance (Options.key's own default-value expression,
        // generateClientKey) -- not a shared constant -- so a second Options() must
        // never collide with the first.
        assertNotEquals(options.key, Client.Options(docStore = MemoryDocStore()).key)

        Client(host = "0.0.0.0", options = options)

        // This IS the one-explicit-key-missing shape the constructor's warning
        // fires for (docStore set, key auto-generated) -- the warning message
        // itself names the key Client actually read, so it must be this SAME
        // generated key, unchanged, not regenerated or dropped by construction.
        assertEquals(1, recordingLogger.errorMessages.size)
        assertTrue(recordingLogger.errorMessages.single().contains(options.key))
    }

    // D3 (`ed25355e`/#1449/yorkie#2111, spec 038_sync-js-sdk-v0725-pre-attach-reissue):
    // BEFORE this change, a second Client built from a previously-generated key string
    // warned a SECOND time too (the generated-key weak set has no "consumed" state,
    // only membership) -- renamed from `a re-passed key equal to a previously generated
    // one still warns falsely` to reflect the fixed behavior: the first Client's `init{}`
    // now consumes the key from the set, so a second Client reusing that exact string
    // reads as an explicit key and stays silent. A real app re-passing a generated key
    // across two Client instances is exactly the shared-key case a pre-attach re-issue
    // must not fire for (another session of the key may already hold the same lamports).
    @Test
    fun `a re-passed key equal to a previously generated one is treated as explicit`() {
        val firstOptions = Client.Options(docStore = MemoryDocStore())
        val recycledKey = firstOptions.key
        Client(host = "0.0.0.0", options = firstOptions)
        assertEquals(1, recordingLogger.errorMessages.size)

        // D3 consume-once: the key is no longer a member of generatedClientKeys after
        // the first Client's constructor read it, so this second Client -- despite
        // reusing the exact same string -- is treated as an explicit key and stays
        // silent, never a correctness break (it just no longer warns, or re-issues).
        Client(
            host = "0.0.0.0",
            options = Client.Options(key = recycledKey, docStore = MemoryDocStore()),
        )
        assertEquals(1, recordingLogger.errorMessages.size, "the second Client must stay silent")
    }

    @Test
    fun `two Clients with generated keys warn independently`() {
        Client(host = "0.0.0.0", options = Client.Options(docStore = MemoryDocStore()))
        Client(host = "0.0.0.0", options = Client.Options(docStore = MemoryDocStore()))

        assertEquals(2, recordingLogger.errorMessages.size)
        recordingLogger.errorMessages.forEach { assertTrue(it.contains("stable clientKey")) }
    }
}
