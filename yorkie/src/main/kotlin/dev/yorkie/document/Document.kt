package dev.yorkie.document

import com.google.protobuf.ByteString
import dev.yorkie.api.toCrdtObject
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.RhtPQMap
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger
import dev.yorkie.util.YorkieScope
import dev.yorkie.util.findPrefixes
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.apache.commons.collections4.trie.PatriciaTrie

/**
 * A CRDT-based data type.
 * We can represent the model of the application.
 * And we can edit it even while offline.
 */
public class Document private constructor(
    public val key: String,
    private val eventStream: MutableSharedFlow<Event<*>>,
) : Flow<Document.Event<*>> by eventStream {
    private val localChanges = mutableListOf<Change>()

    private var root: CrdtRoot = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, RhtPQMap()))
    private var clone: CrdtRoot? = null
    private var changeID = ChangeID.InitialChangeID
    private var checkPoint = CheckPoint.InitialCheckPoint

    internal val hasLocalChanges: Boolean
        get() = localChanges.isNotEmpty()

    public constructor(key: String) : this(key, MutableSharedFlow())

    /**
     * Executes the given [updater] to update this document.
     */
    public fun updateAsync(
        message: String? = null,
        updater: (root: JsonObject) -> Unit,
    ): Deferred<Boolean> {
        return YorkieScope.async {
            val clone = ensureClone()
            val context = ChangeContext(
                id = changeID.next(),
                root = clone,
                message = message,
            )

            runCatching {
                val proxy = JsonObject(context, clone.rootObject)
                updater.invoke(proxy)
            }.onFailure {
                this@Document.clone = null
                YorkieLogger.e("Document.update", it.message.orEmpty())
                return@async false
            }

            if (!context.hasOperations) {
                return@async true
            }
            val change = context.getChange()
            change.execute(root)
            localChanges += change
            changeID = change.id
            eventStream.emit(change.asLocal())
            true
        }
    }

    /**
     * Applies the given [pack] into this document.
     * 1. Remove local changes applied to server.
     * 2. Update the checkpoint.
     * 3. Do Garbage collection.
     */
    internal suspend fun applyChangePack(pack: ChangePack) {
        if (pack.hasSnapshot) {
            applySnapshot(pack.checkPoint.serverSeq, checkNotNull(pack.snapshot))
        } else if (pack.hasChanges) {
            applyChanges(pack.changes)
        }

        val iterator = localChanges.iterator()
        while (iterator.hasNext()) {
            val change = iterator.next()
            if (change.id.clientSeq > pack.checkPoint.clientSeq) {
                break
            }
            iterator.remove()
        }

        checkPoint = checkPoint.forward(pack.checkPoint)

        garbageCollect(checkNotNull(pack.minSyncedTicket))
    }

    /**
     * Applies the given [snapshot] into this document.
     */
    private suspend fun applySnapshot(serverSeq: Long, snapshot: ByteString) {
        root = CrdtRoot(snapshot.toCrdtObject())
        changeID = changeID.syncLamport(serverSeq)
        clone = null
        eventStream.emit(Event.Snapshot(snapshot))
    }

    /**
     * Applies the given [changes] into this document.
     */
    private suspend fun applyChanges(changes: List<Change>) {
        val clone = ensureClone()
        val changesInfo = changes.map {
            it.execute(clone)
            it.execute(root)
            changeID = changeID.syncLamport(it.id.lamport)
            it.toChangeInfo()
        }
        if (changesInfo.isEmpty()) {
            return
        }
        eventStream.emit(Event.RemoteChange(changesInfo))
    }

    private fun ensureClone(): CrdtRoot {
        return clone ?: root.deepCopy().also { clone = it }
    }

    /**
     * Create [ChangePack] of [localChanges] to send to the remote server.
     */
    internal fun createChangePack(): ChangePack {
        val checkPoint = this.checkPoint.increaseClientSeq(localChanges.size)
        return ChangePack(key, checkPoint, localChanges, null, null)
    }

    /**
     * Sets [actorID] into this document.
     * This is also applied in the [localChanges] the document has.
     */
    internal fun setActor(actorID: ActorID) {
        localChanges.forEach {
            it.setActor(actorID)
        }
        changeID = changeID.setActor(actorID)

        // TODO: also apply to root
    }

    // NOTE(7hong13): original comment from JS-SDK:
    // `garbageCollect` purges elements that were removed before the given time.
    /**
     * Deletes elements that were removed before the given time.
     */
    private fun garbageCollect(ticket: TimeTicket): Int {
        clone?.garbageCollect(ticket)
        return root.garbageCollect(ticket)
    }

    private fun Change.createPaths(): List<String> {
        val pathTrie = PatriciaTrie<String>()
        operations.forEach { operation ->
            val createdAt = operation.effectedCreatedAt
            val subPaths = root.createSubPaths(createdAt).drop(1)
            subPaths.forEach { subPath -> pathTrie[subPath] = subPath }
        }
        return pathTrie.findPrefixes().map { "." + it.joinToString(".") }
    }

    private fun Change.asLocal() = Event.LocalChange(listOf(toChangeInfo()))

    private fun Change.toChangeInfo() = Event.ChangeInfo(this, createPaths())

    public fun toJson(): String {
        return root.toJson()
    }

    public sealed class Event<T>(public val value: T) {

        public class Snapshot internal constructor(value: ByteString) : Event<ByteString>(value)

        public class LocalChange internal constructor(
            value: List<ChangeInfo>,
        ) : Event<List<ChangeInfo>>(value)

        public class RemoteChange internal constructor(
            value: List<ChangeInfo>,
        ) : Event<List<ChangeInfo>>(value)

        public class ChangeInfo(public val change: Change, public val paths: List<String>)
    }
}
