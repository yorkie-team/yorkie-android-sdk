package dev.yorkie.core

import android.util.Base64
import dev.yorkie.core.MockYorkieService.Companion.TEST_ACTOR_ID
import dev.yorkie.document.Document
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins [Client]'s identity-namespace routing — `storeKey`'s `namespaceOf`/`escapeNamespacePart`
 * (JS `client.ts` `3b9c668c`/#1402) — which backs both the `DocStore` key and the session-lock
 * name (the lock name is `"yorkie-session:${storeKey(docKey)}"`, so one routing change covers
 * both; see [Client]'s `storeKey` KDoc). Two identity triples whose BARE `apiKey/clientKey/docKey`
 * join would collide must land on distinct store entries and distinct lock names; a literal `%`
 * in a part must not forge the `/` escape; a triple with no `/`/`%` anywhere keeps the exact
 * pre-#1402 bare-joined key, so an entry persisted before this change is still found (see the
 * migration note in the commit that introduced it).
 *
 * JS's `3b9c668c` adds no dedicated unit test for `namespaceOf`/`escapeNamespacePart` themselves
 * (only `client_options_test.ts`'s warning cases, ported separately in
 * `ClientOptionsKeyWarningTest`) — confirmed by `git -C yorkie-js-sdk show 3b9c668c --
 * packages/sdk/test` and a repo-wide grep for `namespaceOf`/`escapeNamespacePart` under
 * `packages/sdk/test` at HEAD (no matches). Every case below is therefore an Android-only pin
 * derived from the commit's own stated injectivity goal, not a 1:1 JS port; each expected store
 * key is a literal string this test hand-derives from the documented
 * `escapeNamespacePart`/`namespaceOf` formula, then confirmed via a
 * real [Client.attachDocument] restore — never by calling the private production function
 * directly, since [Client.storeKey]/`namespaceOf`/`escapeNamespacePart` are private (routing
 * only, no public surface).
 */
class NamespaceEscapingTest {

    // Same reason as ClientPersistenceTest: a restored ChangeID/VersionVector routes through
    // android.util.Base64, an unmocked stub under plain JVM unit tests.
    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    /**
     * Records every name passed to [acquire] and always grants it. A bare recording double —
     * contention itself is [SessionLockTest]'s concern, not this file's.
     */
    private class RecordingSessionLock : SessionLock {
        val acquired = CopyOnWriteArrayList<String>()

        override suspend fun acquire(name: String): SessionLockHandle {
            acquired += name
            return object : SessionLockHandle {
                override fun release() {}
            }
        }
    }

    private fun newClient(
        apiKey: String,
        key: String,
        store: DocStore,
        lock: SessionLock,
    ) = Client(
        options = Client.Options(
            key = key,
            apiKey = apiKey,
            docStore = store,
            sessionLock = lock,
        ),
        host = "0.0.0.0",
    ).apply { service = MockYorkieService() }

    /** A [Document.toBytes] envelope holding one local, un-acked `root["k"] = [value]` edit. */
    private suspend fun envelope(docKey: String, value: Int): ByteArray {
        val document = Document(docKey)
        document.setActor(TEST_ACTOR_ID)
        document.updateAsync { root, _ -> root["k"] = value }.await()
        return document.toBytes()
    }

    private suspend fun attachAndGetRoot(
        apiKey: String,
        key: String,
        store: DocStore,
        lock: SessionLock,
        docKey: String,
    ): String {
        val client = newClient(apiKey, key, store, lock)
        client.activateAsync().await()
        val document = Document(docKey)
        val result = client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()
        assertTrue(result.isSuccess, "attach($apiKey, $key, $docKey) failed: $result")
        val json = document.toJson()
        client.detachDocument(document).await()
        client.deactivateAsync().await()
        client.close()
        return json
    }

    @Test
    fun `two triples bare-joining to the same string get distinct keys and lock names`() = runTest {
        val store = MemoryDocStore()
        val docKey = "doc"
        // Bare-joined, BOTH triples below would read/write "a/b/c/doc" pre-#1402 — a
        // collision. Escaped, triple 1 ("a/b", "c") -> "a%2Fb/c/doc" and triple 2
        // ("a", "b/c") -> "a/b%2Fc/doc": distinct literal strings, hand-derived here from
        // escapeNamespacePart (`%` first, then `/`) on each part in turn.
        store.saveSnapshot("a%2Fb/c/doc", envelope(docKey, 1))
        store.saveSnapshot("a/b%2Fc/doc", envelope(docKey, 2))
        // A pre-#1402 entry at the literal bare-joined string must NOT be the one either
        // triple reads — seeded with a THIRD value so reading it would be caught below.
        store.saveSnapshot("a/b/c/doc", envelope(docKey, 3))

        val lock1 = RecordingSessionLock()
        val lock2 = RecordingSessionLock()
        val json1 = attachAndGetRoot("a/b", "c", store, lock1, docKey)
        val json2 = attachAndGetRoot("a", "b/c", store, lock2, docKey)

        assertTrue(json1.contains("\"k\":1"), "triple 1 did not restore its own entry: $json1")
        assertTrue(json2.contains("\"k\":2"), "triple 2 did not restore its own entry: $json2")
        assertTrue(!json1.contains("\"k\":3"), "triple 1 read the stale bare-joined entry")
        assertTrue(!json2.contains("\"k\":3"), "triple 2 read the stale bare-joined entry")

        assertTrue(lock1.acquired.isNotEmpty())
        assertTrue(lock2.acquired.isNotEmpty())
        assertNotEquals(lock1.acquired.single(), lock2.acquired.single())
        assertTrue(lock1.acquired.single() == "yorkie-session:a%2Fb/c/doc")
        assertTrue(lock2.acquired.single() == "yorkie-session:a/b%2Fc/doc")
    }

    @Test
    fun `a literal percent in a part does not forge the slash escape`() = runTest {
        val store = MemoryDocStore()
        val docKey = "doc"
        // apiKey "a/b" (a REAL slash) escapes to "a%2Fb". apiKey "a%2Fb" (a LITERAL percent
        // sign followed by the characters 2, F — no real slash) escapes `%` first to
        // "a%252Fb", which must stay distinct from the first triple's "a%2Fb" — otherwise
        // escaping `/` before `%` (or not escaping `%` at all) would let a crafted apiKey
        // forge another triple's namespace.
        store.saveSnapshot("a%2Fb/c/doc", envelope(docKey, 10))
        store.saveSnapshot("a%252Fb/c/doc", envelope(docKey, 20))

        val lockA = RecordingSessionLock()
        val lockB = RecordingSessionLock()
        val jsonRealSlash = attachAndGetRoot("a/b", "c", store, lockA, docKey)
        val jsonLiteralPercent = attachAndGetRoot("a%2Fb", "c", store, lockB, docKey)

        assertTrue(
            jsonRealSlash.contains("\"k\":10"),
            "real-slash apiKey did not restore its own entry: $jsonRealSlash",
        )
        assertTrue(
            jsonLiteralPercent.contains("\"k\":20"),
            "literal-percent apiKey did not restore its own entry: $jsonLiteralPercent",
        )
        assertNotEquals(lockA.acquired.single(), lockB.acquired.single())
    }

    @Test
    fun `a separator-free triple keeps the exact pre-#1402 bare-joined key`() = runTest {
        val store = MemoryDocStore()
        val docKey = "doc"
        // No part contains "/" or "%": escapeNamespacePart is a no-op on every part, so the
        // escaped key is byte-identical to the old bare join — an entry persisted before
        // #1402 is still found, with no migration needed for it.
        store.saveSnapshot("a/b/doc", envelope(docKey, 9))

        val lock = RecordingSessionLock()
        val json = attachAndGetRoot("a", "b", store, lock, docKey)

        assertTrue(json.contains("\"k\":9"), "separator-free triple lost its pre-existing entry")
        assertTrue(lock.acquired.single() == "yorkie-session:a/b/doc")
    }
}
