package dev.yorkie.helper

import android.util.Log
import dev.yorkie.api.toOperations
import dev.yorkie.api.toPBOperation
import dev.yorkie.document.Document
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger

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
