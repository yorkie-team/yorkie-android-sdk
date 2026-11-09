package dev.yorkie.helper

import android.util.Base64
import android.util.Log
import dev.yorkie.api.toChangePack
import dev.yorkie.api.toOperations
import dev.yorkie.api.toPBChangePack
import dev.yorkie.api.toPBOperation
import dev.yorkie.document.Document
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `maxVectorOf` creates a VersionVector with the maximum lamport value for the given actors.
 */
fun maxVectorOf(actors: List<String>): VersionVector {
    val vectorMap = if (actors.isEmpty()) {
        mapOf(
            ActorID.INITIAL_ACTOR_ID to TimeTicket.MAX_LAMPORT,
        )
    } else {
        actors.associateWith { TimeTicket.MAX_LAMPORT }
    }

    return VersionVector(
        vectorMap = vectorMap,
    )
}

/**
 * Exchanges each of [d1]/[d2]'s pending local changes with the other,
 * in-process, mirroring the JS SDK unit test `crossSync` helper. Delivers
 * via [Document.applyChangePack] with a neutral [CheckPoint] (`clientSeq =
 * 0u`) so the receiver's own pending local changes are not dropped, and an
 * empty [VersionVector] so the trailing garbage collection inside
 * `applyChangePack` is a no-op. Then self-acks each sender so its pushed
 * local changes are cleared and are not resent by a later call. Operations
 * are passed in memory by default (no protobuf round-trip), so
 * identity-preserving restore payloads (`restoreSpans`) survive intact. Pass
 * [overWire] to route every relayed operation through the protobuf converters
 * first, as a real server does — the only way a unit test exercises the
 * `restore_spans` encode/decode path under convergence.
 */
suspend fun crossSync(
    d1: Document,
    d2: Document,
    overWire: Boolean = false,
) {
    // Only the operations are round-tripped: encoding a whole Change would pull
    // in the version-vector converter, which needs android.util.Base64 (not
    // available in a JVM unit test).
    fun List<Change>.relay() = if (!overWire) {
        this
    } else {
        map { change ->
            change.copy(
                operations = change.operations.map { it.toPBOperation() }.toOperations(),
            )
        }
    }

    val pack1 = d1.createChangePack()
    val pack2 = d2.createChangePack()

    d2.applyChangePack(
        ChangePack(
            d1.getKey(),
            CheckPoint.InitialCheckPoint,
            pack1.changes.relay(),
            null,
            false,
            VersionVector(),
        ),
    )
    d1.applyChangePack(
        ChangePack(
            d2.getKey(),
            CheckPoint.InitialCheckPoint,
            pack2.changes.relay(),
            null,
            false,
            VersionVector(),
        ),
    )

    d1.applyChangePack(
        ChangePack(
            d1.getKey(),
            CheckPoint(0, pack1.checkPoint.clientSeq),
            emptyList(),
            null,
            false,
            VersionVector(),
        ),
    )
    d2.applyChangePack(
        ChangePack(
            d2.getKey(),
            CheckPoint(0, pack2.checkPoint.clientSeq),
            emptyList(),
            null,
            false,
            VersionVector(),
        ),
    )
}

/**
 * Exchanges pending local changes among [docs], in-process, mirroring the JS
 * SDK unit test `exchange` helper: every document's pending changes are
 * captured ONCE up front (`createChangePack` on each, before any delivery),
 * so a three-way concurrent edit is captured the same way regardless of
 * delivery order, then [orders] controls, per document index, which OTHER
 * documents' captured changes are delivered to it and in what sequence —
 * letting a three-replica convergence case apply one concurrent change set
 * in three different arrival orders. Each document then self-acks its own
 * captured pack (by the clientSeq [Document.createChangePack] already
 * bumped its checkpoint to) so its pushed local changes are cleared and are
 * not resent by a later call. See [crossSync] for what [overWire] does and
 * why this follows the same neutral-checkpoint/empty-version-vector
 * delivery shape for N documents instead of two.
 */
suspend fun exchange(
    docs: List<Document>,
    orders: List<List<Int>>,
    overWire: Boolean = false,
) {
    fun List<Change>.relay() = if (!overWire) {
        this
    } else {
        map { change ->
            change.copy(
                operations = change.operations.map { it.toPBOperation() }.toOperations(),
            )
        }
    }

    val packs = docs.map { it.createChangePack() }

    docs.forEachIndexed { i, doc ->
        orders[i].forEach { j ->
            if (j == i) return@forEach
            doc.applyChangePack(
                ChangePack(
                    docs[j].getKey(),
                    CheckPoint.InitialCheckPoint,
                    packs[j].changes.relay(),
                    null,
                    false,
                    VersionVector(),
                ),
            )
        }
    }

    docs.forEachIndexed { i, doc ->
        doc.applyChangePack(
            ChangePack(
                doc.getKey(),
                CheckPoint(0, packs[i].checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
    }
}

/**
 * Hands [d]'s pending local changes to the server and acks them at [d] (the
 * sender's half of a one-way delivery — see [pull]/[oneWayDeliver]). Unlike
 * [crossSync]/[exchange], which self-ack BOTH sides in the same call, a push
 * with no corresponding [pull] on the other side keeps a change genuinely in
 * flight: that is what a [GcSuccessorBarrierTest]-style case needs to put a
 * replica's move/insert behind an un-pulled remove. Mirrors the JS unit test
 * helper `push` (`gc_rga_barrier_test.ts`, yorkie-js-sdk `dbff8892`, #1405).
 */
suspend fun push(d: Document): List<Change> {
    val pack = d.createChangePack()
    d.applyChangePack(
        ChangePack(
            d.getKey(),
            CheckPoint(0, pack.checkPoint.clientSeq),
            emptyList(),
            null,
            false,
            VersionVector(),
        ),
    )
    return pack.changes
}

/**
 * Applies [changes] to [d] as a pull from the server would — the receiver's
 * half of a one-way delivery. See [push]/[oneWayDeliver].
 */
suspend fun pull(d: Document, changes: List<Change>) {
    d.applyChangePack(
        ChangePack(
            d.getKey(),
            CheckPoint.InitialCheckPoint,
            changes,
            null,
            false,
            VersionVector(),
        ),
    )
}

/**
 * Hands [from]'s pending local changes to [to] and acks them at [from]: a
 * [push] followed by [to]'s [pull]. [to] is left with a pending pull of its
 * own in flight toward [from] — unlike [crossSync], this is NOT a round
 * trip. Mirrors the JS unit test helper `oneWayDeliver`
 * (`gc_rga_barrier_test.ts`, yorkie-js-sdk `dbff8892`, #1405).
 */
suspend fun oneWayDeliver(from: Document, to: Document) {
    pull(to, push(from))
}

/**
 * S5: same delivery shape as [crossSync], but routes each pack's `changes`
 * through a protobuf round-trip (`toPBChangePack()` -> `toChangePack()`)
 * before delivery, so identity-preserving restore payloads (`restoreSpans`)
 * must survive real wire encode/decode inside a convergence flow, not just
 * an in-memory hand-off.
 *
 * Each [dev.yorkie.document.change.ChangeID]'s [VersionVector] encodes its
 * actor keys via `android.util.Base64`, which is unmocked (a stub throwing
 * `not mocked`) under plain JVM unit tests. Bridges both directions to the
 * real `java.util.Base64` codec for the duration of this call so the round
 * trip is byte-faithful, not a constant stand-in.
 */
suspend fun crossSyncOverWire(d1: Document, d2: Document) {
    val pack1 = d1.createChangePack()
    val pack2 = d2.createChangePack()

    mockkStatic(Base64::class)
    every { Base64.encodeToString(any(), any()) } answers {
        java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
    }
    every { Base64.decode(any<String>(), any()) } answers {
        java.util.Base64.getDecoder().decode(firstArg<String>())
    }
    val (wireChanges1, wireChanges2) = try {
        pack1.toPBChangePack().toChangePack().changes to
            pack2.toPBChangePack().toChangePack().changes
    } finally {
        unmockkStatic(Base64::class)
    }

    d2.applyChangePack(
        ChangePack(
            d1.getKey(),
            CheckPoint.InitialCheckPoint,
            wireChanges1,
            null,
            false,
            VersionVector(),
        ),
    )
    d1.applyChangePack(
        ChangePack(
            d2.getKey(),
            CheckPoint.InitialCheckPoint,
            wireChanges2,
            null,
            false,
            VersionVector(),
        ),
    )

    d1.applyChangePack(
        ChangePack(
            d1.getKey(),
            CheckPoint(0, pack1.checkPoint.clientSeq),
            emptyList(),
            null,
            false,
            VersionVector(),
        ),
    )
    d2.applyChangePack(
        ChangePack(
            d2.getKey(),
            CheckPoint(0, pack2.checkPoint.clientSeq),
            emptyList(),
            null,
            false,
            VersionVector(),
        ),
    )
}

/**
 * A small seeded PRNG (mulberry32) so a seed replays the same history.
 * Ported from `test/helper/adv_sync.ts` (yorkie-js-sdk #1514, yorkie#2310).
 * Kotlin `Int` arithmetic wraps mod 2^32 on overflow the same way JS's
 * `Math.imul`/`>>> 0` do, so the bit-level steps translate directly.
 */
fun mulberry32(seed: Int): () -> Double {
    var state = seed
    return {
        state += 0x6d2b79f5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + ((t xor (t ushr 7)) * (t or 61)))
        ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

/**
 * Go's `time.MinVersionVector`: element-wise min across [vectors], 0 for an
 * actor any vector does not carry.
 */
fun minVersionVector(vectors: List<VersionVector>): VersionVector {
    val actors = vectors.flatMap { it.vectorMap.keys }.toSet()
    val out = actors.associateWith { actor -> vectors.minOf { it.get(actor) ?: 0L } }
    return VersionVector(out)
}

/** A replica attached to an [AdvServer]. */
class AdvClient(val document: Document, val id: String) {
    internal var cursor = 0
}

/** Returns [n] replicas of one document under the actors `1..n`. */
fun newAdvClients(n: Int): List<AdvClient> {
    return (1..n).map { i ->
        val id = i.toString().padStart(24, '0')
        AdvClient(Document("adv-doc"), id).also { it.document.setActor(id) }
    }
}

/**
 * Models the server's push-pull: one ordered change log, one stored version
 * vector row per client (the vector its push carried), and minVV =
 * element-wise min over the stored rows. A client only ever sees minVV as the
 * version vector of a pull response, and [Document.applyChangePack] is what
 * runs GC with it, so GC can never run at a moment the protocol would not
 * produce. Ported from `test/helper/adv_sync.ts` (yorkie-js-sdk #1514,
 * yorkie#2310).
 */
class AdvServer {
    val log = mutableListOf<Change>()
    private val rows = mutableMapOf<String, VersionVector>()

    /**
     * One PushPull round trip: push local changes, store the request's
     * vector, pull everything the server holds that this client has not
     * seen (by actor, not by log position -- a concurrent push from another
     * client between this client's own push and pull must still be
     * excluded only by identity), and hand the response (changes + minVV
     * when [gc]) to [Document.applyChangePack], which applies then collects.
     */
    suspend fun sync(client: AdvClient, gc: Boolean) {
        val pack = client.document.createChangePack()
        log.addAll(pack.changes)
        rows[client.id] = pack.versionVector.deepCopy()

        val pulled = log.drop(client.cursor).filter { it.id.actor != client.id }
        client.cursor = log.size

        val vv = if (gc) minVersionVector(rows.values.toList()) else VersionVector()
        client.document.applyChangePack(
            ChangePack(
                client.document.getKey(),
                CheckPoint(0, pack.checkPoint.clientSeq),
                pulled,
                null,
                false,
                vv,
            ),
        )
    }

    /**
     * Passes every change in [log] through the protobuf operation codec in
     * place -- the only way a unit test exercises the
     * `removal_events`/restore-span encode/decode path for a change already
     * sitting in an [AdvServer] log. Only the operations round-trip (same as
     * [crossSync]'s `overWire`): a [Change]'s own [dev.yorkie.document.change.ChangeID]
     * is never re-encoded here, so no `android.util.Base64` bridge is needed.
     */
    fun wire() {
        for (i in log.indices) {
            log[i] = log[i].copy(
                operations = log[i].operations.map { it.toPBOperation() }.toOperations(),
            )
        }
    }
}

/**
 * Records every debug message passed to [Logger], for asserting that a
 * specific `logDebug` call site fired (AC13). Admits DEBUG unconditionally.
 * [Logger]'s backing instance is a process-wide singleton — install via
 * [Logger.init], and reinstall a fresh plain instance afterward (e.g. in
 * `@After`) so captured state does not leak across test classes.
 */
class RecordingLogger : Logger {
    override val minimumPriority: Int = Log.DEBUG

    // Copy-on-write: Client's persist writes log from Dispatchers.IO while a test polls these
    // lists from its own thread.
    val debugMessages = CopyOnWriteArrayList<String>()
    val errorMessages = CopyOnWriteArrayList<String>()

    override fun d(
        tag: String,
        message: String?,
        throwable: Throwable?,
    ) {
        message?.let(debugMessages::add)
    }

    override fun e(
        tag: String,
        message: String?,
        throwable: Throwable?,
    ) {
        message?.let(errorMessages::add)
    }
}
