package dev.yorkie.helper

import android.util.Base64
import android.util.Log
import dev.yorkie.api.toChangePack
import dev.yorkie.api.toPBChangePack
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

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
 * are passed in memory (no protobuf round-trip), so identity-preserving
 * restore payloads (`restoreSpans`) survive intact.
 */
suspend fun crossSync(d1: Document, d2: Document) {
    val pack1 = d1.createChangePack()
    val pack2 = d2.createChangePack()

    d2.applyChangePack(
        ChangePack(
            d1.getKey(),
            CheckPoint.InitialCheckPoint,
            pack1.changes,
            null,
            false,
            VersionVector(),
        ),
    )
    d1.applyChangePack(
        ChangePack(
            d2.getKey(),
            CheckPoint.InitialCheckPoint,
            pack2.changes,
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
 * Records every debug message passed to [Logger], for asserting that a
 * specific `logDebug` call site fired (AC13). Admits DEBUG unconditionally.
 * [Logger]'s backing instance is a process-wide singleton — install via
 * [Logger.init], and reinstall a fresh plain instance afterward (e.g. in
 * `@After`) so captured state does not leak across test classes.
 */
class RecordingLogger : Logger {
    override val minimumPriority: Int = Log.DEBUG

    val debugMessages = mutableListOf<String>()

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
        // Not needed by current tests; error-level capture can be added when used.
    }
}
