package dev.yorkie.document

import dev.yorkie.api.toPBChange
import dev.yorkie.api.toStoredChange
import dev.yorkie.api.toStoredChangeBytes
import dev.yorkie.core.ResourceStatus
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.VersionVector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Ports yorkie-js-sdk `presence_patch_test.ts` (`b50f8900`, #1477) cases 1-8 and 11-14, 16 as
 * JVM unit tests, plus the Android-only D2 suspend-window case and heal-guard probes the JS
 * suite has no equivalent of (Android defers the Put-vs-Patch decision to commit time; JS
 * decides eagerly in `Channel.set`, which has no such suspension -- see [Document]'s
 * `resolvePresenceChangeForCommit` KDoc). Cases 7, 8 and 16 exercise
 * [Document.restorePresencePatchesAndRebuildPack] (D4, spec 040) directly -- the `Client`-level
 * retry wrapper that calls it from the four push sites is [dev.yorkie.core.PresenceBaseTest].
 * Cases 9, 10 are N/A (no key-removal/presence-undo API); case 15 is
 * [DocumentAcknowledgePushedChangesTest]; case 17 is [DocumentTest].
 *
 * All updates here are presence-only, so `ChangeID.versionVector` never leaves
 * `INITIAL_VERSION_VECTOR` (`ChangeContext.toChange`'s `excludeClocks` branch) -- no
 * `android.util.Base64` mock is needed for `toBytes`/`fromBytes` the way operation-bearing
 * tests elsewhere in this module need one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresencePatchTest {

    private lateinit var target: Document

    @Before
    fun setUp() {
        target = Document("presence-patch-test")
    }

    @After
    fun tearDown() {
        target.close()
    }

    /** Hands [doc] a server pack that acknowledges everything pushed so far. */
    private suspend fun ack(
        doc: Document,
        capabilities: List<String>,
        isRemoved: Boolean = false,
    ) {
        val pending = doc.createChangePack().changes
        val lastSeq = pending.lastOrNull()?.id?.clientSeq ?: 0u
        doc.applyChangePack(
            ChangePack(
                doc.getKey(),
                CheckPoint(0, lastSeq),
                emptyList(),
                null,
                isRemoved,
                VersionVector(),
                epoch = 0,
                capabilities = capabilities,
            ),
        )
    }

    private suspend fun pendingPresenceTypes(doc: Document): List<PresenceChange?> {
        return doc.createChangePack().changes.map { it.presenceChange }
    }

    // --- JS 1 ------------------------------------------------------------

    @Test
    fun `JS1 sends a full Put until the server advertises the capability`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("cursor" to "0,0")) }.await()
        assertIs<PresenceChange.Put>(pendingPresenceTypes(target).single())

        ack(target, emptyList())
        target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
        assertIs<PresenceChange.Put>(pendingPresenceTypes(target).single())
    }

    // --- JS 2 ------------------------------------------------------------

    @Test
    fun `JS2 patches once the capability and a base are both there`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a", "cursor" to "0,0")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))

        target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
        val change = target.createChangePack().changes.single()
        val presenceChange = change.presenceChange
        assertIs<PresenceChange.Patch>(presenceChange)
        assertEquals(mapOf("cursor" to "1,1"), presenceChange.presence)
        // The patch still carries the whole presence locally, so the document this
        // change applies to is not left holding the changed key alone.
        assertEquals(mapOf("name" to "a", "cursor" to "1,1"), presenceChange.full)
        assertEquals(
            mapOf("name" to "a", "cursor" to "1,1"),
            target.allPresences.value[target.changeID.actor],
        )

        // Only the changed key goes on the wire.
        val pbChange = change.toPBChange()
        assertEquals(setOf("cursor"), pbChange.presenceChange.presence.dataMap.keys)
    }

    // --- JS 3 ------------------------------------------------------------

    @Test
    fun `JS3 accumulates the keys of several puts in one update`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))

        target.updateAsync { _, p ->
            p.put(mapOf("cursor" to "1,1"))
            p.put(mapOf("selection" to "id-1"))
        }.await()

        val presenceChange = target.createChangePack().changes.single().presenceChange
        assertIs<PresenceChange.Patch>(presenceChange)
        assertEquals(setOf("cursor", "selection"), presenceChange.presence.keys)
    }

    // --- JS 4 ------------------------------------------------------------

    @Test
    fun `JS4 puts again after a clear, which leaves the server no base`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        assertTrue(target.isPresencePatchableForTest())

        target.updateAsync { _, p -> p.clear() }.await()
        assertFalse(target.isPresencePatchableForTest())

        target.updateAsync { _, p -> p.put(mapOf("name" to "b")) }.await()
        val types = pendingPresenceTypes(target)
        assertEquals(2, types.size)
        assertIs<PresenceChange.Clear>(types[0])
        assertIs<PresenceChange.Put>(types[1])
    }

    // --- JS 5 ------------------------------------------------------------

    @Test
    fun `JS5 puts a presence too large for the server to cache`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))

        // 4 KiB is the server's base limit, counting 64 B per key on top of the key and
        // value bytes.
        target.updateAsync { _, p -> p.put(mapOf("selection" to "id-" + "x".repeat(5000))) }
            .await()
        assertIs<PresenceChange.Put>(pendingPresenceTypes(target).single())
    }

    // --- CJ-MED-2 (round 4, cross-judge round 3): raw-byte 4 KiB rule ------

    @Test
    fun `4 KiB rule counts RAW value bytes, not the quoted-escaped form, at the exact boundary`() =
        runTest {
            // "seed" (4) + "s" (1) + 64 overhead = 69 fixed bytes ahead of the key under test.
            val seedBytes = 69

            // At K=3959 quote characters, the RAW total is exactly 4096 ("data"=4 bytes +
            // K + 64): still cacheable, so this commits as a Patch. The quoted/escaped
            // formula the pre-fix code used (each `"` doubled to `\"`, plus 2 bytes for the
            // wrapping quotes) would put this value at 139 + 2*3959 = 8057 bytes -- Put,
            // under the wrong premise that the wire value is JSON-quoted (it is the raw
            // string, PresenceConverter's `toPBPresence` is a bare `data.putAll`).
            val atLimit = Document("cj-med-2-at-limit")
            atLimit.updateAsync { _, p -> p.put(mapOf("seed" to "s")) }.await()
            ack(atLimit, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
            val k = 4096 - seedBytes - "data".toByteArray(Charsets.UTF_8).size - 64
            atLimit.updateAsync { _, p -> p.put(mapOf("data" to "\"".repeat(k))) }.await()
            assertIs<PresenceChange.Patch>(
                atLimit.createChangePack().changes.single().presenceChange,
            )
            atLimit.close()

            // One byte over the same RAW boundary flips to a Put.
            val overLimit = Document("cj-med-2-over-limit")
            overLimit.updateAsync { _, p -> p.put(mapOf("seed" to "s")) }.await()
            ack(overLimit, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
            overLimit.updateAsync { _, p -> p.put(mapOf("data" to "\"".repeat(k + 1))) }.await()
            assertIs<PresenceChange.Put>(
                overLimit.createChangePack().changes.single().presenceChange,
            )
            overLimit.close()
        }

    @Test
    fun `4 KiB rule flips at the key-count boundary the server's own formula implies`() = runTest {
        // "base" (4) + "x" (1) + 64 overhead = 69 fixed bytes; each additional key is
        // 4 ASCII bytes + an empty value + 64 overhead = 68 bytes. 69 + 68*59 = 4081
        // (<=4096, Patch); 69 + 68*60 = 4149 (>4096, Put).
        val under = Document("cj-med-2-keycount-under")
        under.updateAsync { _, p -> p.put(mapOf("base" to "x")) }.await()
        ack(under, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        under.updateAsync { _, p ->
            repeat(59) { i -> p.put(mapOf("k%03d".format(i) to "")) }
        }.await()
        assertIs<PresenceChange.Patch>(under.createChangePack().changes.single().presenceChange)
        under.close()

        val over = Document("cj-med-2-keycount-over")
        over.updateAsync { _, p -> p.put(mapOf("base" to "x")) }.await()
        ack(over, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        over.updateAsync { _, p ->
            repeat(60) { i -> p.put(mapOf("k%03d".format(i) to "")) }
        }.await()
        assertIs<PresenceChange.Put>(over.createChangePack().changes.single().presenceChange)
        over.close()
    }

    // --- D2 (Android-only, generator/024) ---------------------------------

    @Test
    fun `D2 a capability drop while the updater is suspended is decided at commit time`() =
        runTest {
            target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
            ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
            assertTrue(target.isPresencePatchableForTest())

            val updaterEntered = CompletableDeferred<Unit>()
            val proceedUpdater = CompletableDeferred<Unit>()
            val updateJob = async {
                target.updateAsync { _, p ->
                    updaterEntered.complete(Unit)
                    proceedUpdater.await()
                    p.put(mapOf("cursor" to "1,1"))
                }.await()
            }
            updaterEntered.await()
            // The capability drops while the updater above is suspended mid-update --
            // straddling the whole window the D2 decision + trackPresenceBase guard
            // against (memory js-sync-block-becomes-suspend-window).
            ack(target, emptyList())
            assertFalse(target.isPresencePatchableForTest())
            proceedUpdater.complete(Unit)
            updateJob.await()

            // The commit-time decision wins: capability was gone by the time this change
            // actually committed, so it is a full Put, never a Patch -- reading
            // canPatchPresence() at DocPresence construction (this update's entry) would
            // have wrongly produced a Patch.
            assertIs<PresenceChange.Put>(target.createChangePack().changes.single().presenceChange)
        }

    // --- CJ-MED-1 (round 4, cross-judge round 3): baseless -----------------

    @Test
    fun `clear then put in ONE update stays a Put, not a Patch against the old base`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a", "cursor" to "0,0")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        assertTrue(target.isPresencePatchableForTest())

        // clear() raises `baseless` on this context (JS `Channel.clear()`, b50f8900
        // presence.ts); the put() that follows it in the SAME update must not commit as
        // a Patch -- the server has already lost whatever base it held, so a Patch here
        // would fold {only} onto the STALE {name, cursor} base and leave every peer
        // holding keys this update meant to drop (CJ-MED-1).
        target.updateAsync { _, p ->
            p.clear()
            p.put(mapOf("only" to "1"))
        }.await()

        val change = target.createChangePack().changes.single()
        val presenceChange = change.presenceChange
        assertIs<PresenceChange.Put>(presenceChange)
        assertEquals(mapOf("only" to "1"), presenceChange.presence)

        // The wire carries exactly {only}, so a folding server replaces its base wholesale
        // (PUT semantics) rather than merging it onto {name, cursor, only}.
        val pbChange = change.toPBChange()
        assertEquals(setOf("only"), pbChange.presenceChange.presence.dataMap.keys)
    }

    @Test
    fun `put then clear in ONE update stays a Clear`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))

        target.updateAsync { _, p ->
            p.put(mapOf("cursor" to "1,1"))
            p.clear()
        }.await()

        assertIs<PresenceChange.Clear>(target.createChangePack().changes.single().presenceChange)
    }

    @Test
    fun `a plain put in the NEXT update after a baseless commit patches again`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a", "cursor" to "0,0")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))

        target.updateAsync { _, p ->
            p.clear()
            p.put(mapOf("only" to "1"))
        }.await()
        assertIs<PresenceChange.Put>(target.createChangePack().changes.single().presenceChange)

        // This commit established a fresh base (it was a Put, trackPresenceBase sets
        // presenceBaseSent=true/presencePatchSent=false same as any other Put), so a NEW,
        // separate update -- a fresh ChangeContext, baseless defaults back to false -- may
        // patch again once the server acks it.
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        target.updateAsync { _, p -> p.put(mapOf("only" to "2")) }.await()
        val presenceChange = target.createChangePack().changes.single().presenceChange
        assertIs<PresenceChange.Patch>(presenceChange)
        assertEquals(mapOf("only" to "2"), presenceChange.presence)
    }

    // --- JS 6 --------------------------------------------------------------

    @Test
    fun `JS6 heals with a put when a reply drops the capability`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
        assertIs<PresenceChange.Patch>(pendingPresenceTypes(target).single())

        // The push landed on an old server, which stored the patch as an empty
        // presence. Its reply advertises nothing, so a full put has to follow.
        ack(target, emptyList())
        val healed = target.createChangePack().changes.single().presenceChange
        assertIs<PresenceChange.Put>(healed)
        assertEquals(mapOf("name" to "a", "cursor" to "1,1"), healed.presence)
    }

    // --- Android-only heal guards --------------------------------------------

    @Test
    fun `heal is skipped when the ack also removes the document`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()

        // Drops the capability (needs a heal) AND marks the document removed in the
        // SAME reply -- healPresenceBase must skip rather than mint against a
        // torn-down document.
        ack(target, emptyList(), isRemoved = true)
        assertEquals(ResourceStatus.Removed, target.getStatus())
        assertTrue(target.createChangePack().changes.isEmpty())
    }

    @Test
    fun `heal is skipped while minting is suspended`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()

        target.discardLocalChanges()
        assertFalse(target.isMintable())

        // Would otherwise owe a heal (the reply drops the capability); healPresenceBase
        // must skip rather than throw ErrRefused out of an unrelated pack apply.
        ack(target, emptyList())
        assertTrue(target.createChangePack().changes.isEmpty())
    }

    // --- LOW-6 (round 4, cross-judge round 3): heal swallow, fault-injected --------

    /** Reflects out [Document]'s own single-thread dispatcher (generator/028, MED-1). */
    private fun docDispatcher(doc: Document): CoroutineDispatcher {
        val field = Document::class.java.getDeclaredField("dispatcher")
        field.isAccessible = true
        return field.get(doc) as CoroutineDispatcher
    }

    /** Seeds [doc] with exactly one pending [PresenceChange.Patch], independent of `ack`'s own
     * checkpoint accounting -- used so the fault-injection pack below (built with its own
     * explicit [CheckPoint]) acks exactly the one pending change these tests mint. */
    private suspend fun seedPendingPatchFor(doc: Document) {
        doc.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(doc, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        doc.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
    }

    // These two fault-injection tests use `runBlocking`, not `runTest`: the fault is
    // injected by blocking a REAL thread (the document's own single-thread dispatcher)
    // behind a `CountDownLatch`, and `runTest`'s virtual-time auto-advance races ahead of
    // that real wait, firing `withTimeout` spuriously (observed directly: the
    // cancellation case timed out under virtual time on the first run of this fix).

    @Test
    fun `a swallowed heal failure lets the apply succeed and re-fires once on the next pack`() =
        runBlocking {
            val doc = Document("heal-fault-p1a")
            seedPendingPatchFor(doc)
            assertIs<PresenceChange.Patch>(pendingPresenceTypes(doc).single())

            // A reply dropping the capability, so needsPresenceHeal() fires healPresenceBase()
            // at the tail of applyChangePack -- but a discardLocalChanges() FIFO-queued
            // immediately behind it on the document's own single-thread dispatcher lands
            // first, suspending minting before the heal's own updateAsync body gets to run:
            // the heal's mint is therefore refused with ErrRefused INSIDE healPresenceBase's
            // try block.
            val pack = ChangePack(
                doc.getKey(),
                CheckPoint(0, 2u),
                emptyList(),
                null,
                false,
                VersionVector(),
                epoch = 0,
                capabilities = emptyList(),
            )
            val latch = CountDownLatch(1)
            val dispatcher = docDispatcher(doc)
            dispatcher.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    // M9 (team-review, RTCOLLABPLATFORM-789): bounded -- an unbounded
                    // await() on a missed countDown() would hang this real dispatcher
                    // thread forever, leaking across tests.
                    check(latch.await(5, TimeUnit.SECONDS)) { "latch never counted down" }
                },
            )
            val applyJob = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { doc.applyChangePack(pack) }
            }
            val discardJob = async(start = CoroutineStart.UNDISPATCHED) {
                doc.discardLocalChanges()
            }
            latch.countDown()
            val applyResult = withTimeout(5_000) { applyJob.await() }
            withTimeout(5_000) { discardJob.await() }

            // The swallow branch does exactly its job: a heal failure never fails the pack
            // apply that triggered it.
            assertTrue(applyResult.isSuccess)
            assertFalse(doc.isMintable())
            assertTrue(pendingPresenceTypes(doc).isEmpty())

            // No stuck `needsHeal`: once the resync that suspended minting is rolled back,
            // the very NEXT pack re-fires the heal...
            doc.restoreLocalChanges(emptyList())
            ack(doc, emptyList())
            assertIs<PresenceChange.Put>(pendingPresenceTypes(doc).single())

            // ...and exactly once -- a THIRD pack owes nothing further.
            ack(doc, emptyList())
            assertTrue(pendingPresenceTypes(doc).isEmpty())
            doc.close()
        }

    @Test
    fun `a CancellationException inside the heal is NOT swallowed and mints no duplicate Put`() =
        runBlocking {
            val doc = Document("heal-fault-p1b")
            seedPendingPatchFor(doc)

            val pack = ChangePack(
                doc.getKey(),
                CheckPoint(0, 2u),
                emptyList(),
                null,
                false,
                VersionVector(),
                epoch = 0,
                capabilities = emptyList(),
            )
            val latch = CountDownLatch(1)
            val dispatcher = docDispatcher(doc)
            dispatcher.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    // M9 (team-review, RTCOLLABPLATFORM-789): bounded -- an unbounded
                    // await() on a missed countDown() would hang this real dispatcher
                    // thread forever, leaking across tests.
                    check(latch.await(5, TimeUnit.SECONDS)) { "latch never counted down" }
                },
            )
            val applyJob = async(start = CoroutineStart.UNDISPATCHED) { doc.applyChangePack(pack) }
            // Queued behind applyJob's own continuation, ahead of the heal's updateAsync
            // (still to be enqueued once applyJob reaches healPresenceBase's .await()).
            dispatcher.dispatch(EmptyCoroutineContext, Runnable { applyJob.cancel() })
            latch.countDown()
            val outcome = withTimeout(5_000) { runCatching { applyJob.await() } }

            assertTrue(applyJob.isCancelled, "CancellationException must propagate out of the heal")
            assertTrue(outcome.isFailure)

            // M9 (team-review, RTCOLLABPLATFORM-789): the heal's own updateAsync is
            // independent of applyJob's cancelled scope, but this dispatch ordering
            // (applyJob.cancel() is queued ahead of the heal's own updateAsync, both on
            // the document's single-thread dispatcher) cancels it before it ever mints --
            // deterministic across repeated runs, not merely "at most one". A regression
            // that let it mint anyway, or mint TWICE on a later pack, is what this pins.
            ack(doc, emptyList())
            val putCount = doc.createChangePack()
                .changes
                .count { it.presenceChange is PresenceChange.Put }
            assertEquals(0, putCount, "the heal must have been cancelled before minting")
            doc.close()
        }

    // --- JS 7 (D4) ---------------------------------------------------------------

    @Test
    fun `JS7 restorePresencePatchesAndRebuildPack rewrites only the earliest pending patch`() =
        runTest {
            target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
            ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
            target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
            target.updateAsync { _, p -> p.put(mapOf("selection" to "id-1")) }.await()
            val before = pendingPresenceTypes(target)
            assertEquals(2, before.size)
            assertIs<PresenceChange.Patch>(before[0])
            assertIs<PresenceChange.Patch>(before[1])

            target.restorePresencePatchesAndRebuildPack()

            // Only the first one is rewritten: the server folds the rest in order, so the
            // put in front of them is the base they need.
            val after = pendingPresenceTypes(target)
            val rewritten = after[0]
            assertIs<PresenceChange.Put>(rewritten)
            assertEquals(mapOf("name" to "a", "cursor" to "1,1"), rewritten.presence)
            assertIs<PresenceChange.Patch>(after[1])
        }

    // --- JS 8 (D4) ---------------------------------------------------------------

    @Test
    fun `JS8 still owes a heal while later patches stay queued`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
        target.updateAsync { _, p -> p.put(mapOf("selection" to "id-1")) }.await()

        target.restorePresencePatchesAndRebuildPack()
        val afterRewrite = pendingPresenceTypes(target)
        assertIs<PresenceChange.Put>(afterRewrite[0])
        assertIs<PresenceChange.Patch>(afterRewrite[1])

        // The second patch is still on its way to a server that may not take one, so a
        // reply without the capability has to heal rather than conclude the rewrite
        // already left a put behind.
        ack(target, emptyList())
        val healed = target.createChangePack().changes.single().presenceChange
        assertIs<PresenceChange.Put>(healed)
        assertEquals(
            mapOf("name" to "a", "cursor" to "1,1", "selection" to "id-1"),
            healed.presence,
        )
    }

    // --- JS 16 (D4) ---------------------------------------------------------------

    @Test
    fun `JS16 restorePresencePatchesAndRebuildPack drops the base even with nothing queued`() =
        runTest {
            target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
            ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
            assertTrue(target.isPresencePatchableForTest())

            target.restorePresencePatchesAndRebuildPack()
            assertFalse(target.isPresencePatchableForTest())

            target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
            assertIs<PresenceChange.Put>(pendingPresenceTypes(target).single())
        }

    // --- M9 (team-review, RTCOLLABPLATFORM-789) ------------------------------------

    @Test
    fun `restorePresencePatchesAndRebuildPack after a discard yields an empty pack`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
        val mintGenerationAtEntry = target.currentMintGeneration()

        // Simulates a concurrent Client.resyncAsync discarding this document's queue
        // (and later re-anchoring it) BETWEEN this retry's original failed push and
        // its presence-base rebuild -- the entry generation captured above is now
        // stale.
        target.discardLocalChanges()

        val rebuilt =
            target.restorePresencePatchesAndRebuildPack(
                mintGenerationAtEntry = mintGenerationAtEntry,
            )

        assertTrue(rebuilt.changes.isEmpty(), "a stale retry must resend nothing")
    }

    // --- JS 11 ---------------------------------------------------------------

    @Test
    fun `JS11 owes a put again after restoring from offline persistence`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        assertTrue(target.isPresencePatchableForTest())

        val restored = Document.fromBytes(target.getKey(), target.toBytes())
        assertFalse(restored.isPresencePatchableForTest())

        restored.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
        assertIs<PresenceChange.Put>(restored.createChangePack().changes.single().presenceChange)
        restored.close()
    }

    // --- JS 12 ---------------------------------------------------------------

    @Test
    fun `JS12 owes a put again after an in-place restore`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        val bytes = target.toBytes()

        val restoreTarget = Document(target.getKey())
        // Pre-establish capability+base on the RESTORE TARGET itself (a brand-new
        // Document already defaults to false/false, which would make the assertion
        // below pass even if restoreFromBytes's own reset call were missing).
        restoreTarget.updateAsync { _, p -> p.put(mapOf("x" to "y")) }.await()
        ack(restoreTarget, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        assertTrue(restoreTarget.isPresencePatchableForTest())

        restoreTarget.restoreFromBytes(bytes)
        assertFalse(restoreTarget.isPresencePatchableForTest())

        restoreTarget.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
        assertIs<PresenceChange.Put>(
            restoreTarget.createChangePack().changes.single().presenceChange,
        )
        restoreTarget.close()
    }

    // --- Android-only: the 4th restore rebuild site -----------------------------

    @Test
    fun `a reanchor resets the presence-patch base state`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        assertTrue(target.isPresencePatchableForTest())

        target.resetForReanchor()
        assertFalse(target.isPresencePatchableForTest())

        target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
        assertIs<PresenceChange.Put>(target.createChangePack().changes.single().presenceChange)
    }

    // --- JS 13 (D1) ------------------------------------------------------------

    @Test
    fun `JS13 carries a pending patch through persistence and replays it as Put of full`() =
        runTest {
            target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
            ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
            target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
            assertIs<PresenceChange.Patch>(pendingPresenceTypes(target).single())

            // D1: the append-log encoder maps the pending Patch to Put(full), never Patch.
            val pendingChange = target.pendingChanges().single()
            val storedBytes = pendingChange.toStoredChangeBytes()
            val decodedFromLog = storedBytes.toStoredChange().presenceChange
            assertIs<PresenceChange.Put>(decodedFromLog)
            assertEquals(mapOf("name" to "a", "cursor" to "1,1"), decodedFromLog.presence)

            // D1: the envelope encoder applies the SAME mapping.
            val restored = Document.fromBytes(target.getKey(), target.toBytes())
            val presenceChange = restored.createChangePack().changes.single().presenceChange
            assertIs<PresenceChange.Put>(presenceChange)
            assertEquals(mapOf("name" to "a", "cursor" to "1,1"), presenceChange.presence)
            restored.close()
        }

    // --- JS 14 -----------------------------------------------------------------

    @Test
    fun `JS14 heals a restored patch the server stored as empty`() = runTest {
        target.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        ack(target, listOf(ChangePack.PRESENCE_PATCH_CAPABILITY))
        target.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()

        // A restore knows nothing of the capability, but the queue it brings back still
        // holds a presence change (D1: persisted as Put(full), the Android
        // over-approximation of JS's "holds a pending Patch" test) that is about to be
        // pushed. An old server stores that as an empty presence, so its reply has to
        // heal.
        val restored = Document.fromBytes(target.getKey(), target.toBytes())
        assertIs<PresenceChange.Put>(restored.createChangePack().changes.single().presenceChange)

        ack(restored, emptyList())
        val healed = restored.createChangePack().changes.single().presenceChange
        assertIs<PresenceChange.Put>(healed)
        assertEquals(mapOf("name" to "a", "cursor" to "1,1"), healed.presence)
        restored.close()
    }
}
