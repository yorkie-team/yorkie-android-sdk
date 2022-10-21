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
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * A CRDT-based data type.
 * We can represen the model of the application.
 * And we can edit it even while offline.
 *
 * TODO(skhugh): we need to check for thread-safety.
 */
public class Document private constructor(
    public val key: String,
    private val eventStream: MutableSharedFlow<DocEvent<*>>,
) : Flow<Document.DocEvent<*>> by eventStream {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localChanges = mutableListOf<Change>()

    private var root: CrdtRoot = CrdtRoot(CrdtObject.create(TimeTicket.InitialTimeTicket))
    private var clone: CrdtRoot? = null
    private var changeID = ChangeID.InitialChangeID
    private var checkPoint = CheckPoint.InitialCheckPoint

    public constructor(key: String) : this(key, MutableSharedFlow())

    /**
     * executes the given [updater] to update this document.
     */
    public fun update(
        message: String? = null,
        updater: (root: JsonObject) -> Unit,
    ) {
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
            this.clone = null
            YorkieLogger.e("Document.update", it.message.orEmpty())
        }

        if (!context.hasOperations) {
            return
        }
        val change = context.getChange()
        change.execute(root)
        localChanges += change
        changeID = change.id

        scope.launch {
            eventStream.emit(change.asLocal())
        }
    }

    /**
     * applies the given [pack] into this document.
     * 1. Remove local changes applied to server.
     * 2. Update the checkpoint.
     * 3. Do Garbage collection.
     */
    internal fun applyChangePack(pack: ChangePack) {
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
     * applies the given [snapshot] into this document.
     */
    private fun applySnapshot(serverSeq: Long, snapshot: ByteString) {
        root = CrdtRoot(snapshot.toCrdtObject())
        changeID = changeID.syncLamport(serverSeq)
        clone = null
        scope.launch {
            eventStream.emit(DocEvent.Snapshot(snapshot))
        }
    }

    /**
     * applies the given [changes] into this document.
     */
    private fun applyChanges(changes: List<Change>) {
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
        scope.launch {
            eventStream.emit(DocEvent.RemoteChange(changesInfo))
        }
    }

    private fun ensureClone(): CrdtRoot {
        return clone ?: root.deepCopy().also { clone = it }
    }

    /**
     * create [ChangePack] of [localChanges] to send to the remote server.
     */
    internal fun createChangePack(): ChangePack {
        val checkPoint = this.checkPoint.increaseClientSeq(localChanges.size)
        return ChangePack(key, checkPoint, localChanges, null, null)
    }

    /**
     * sets [actorID] into this document.
     * This is also applied in the [localChanges] the document has.
     */
    internal fun setActor(actorID: ActorID) {
        localChanges.forEach {
            it.setActor(actorID)
        }
        changeID = changeID.setActor(actorID)

        // TODO: also apply to root
    }

    /**
     * purges elements that were removed before the given time.
     */
    private fun garbageCollect(ticket: TimeTicket): Int {
        clone?.garbageCollect(ticket)
        return root.garbageCollect(ticket)
    }

    private fun Change.createPaths(): List<String> {
        TODO("need trie")
    }

    private fun Change.asLocal() = DocEvent.LocalChange(listOf(toChangeInfo()))

    private fun Change.toChangeInfo() = DocEvent.ChangeInfo(this, createPaths())

    public sealed class DocEvent<T>(public val value: T) {

        public class Snapshot internal constructor(value: ByteString) : DocEvent<ByteString>(value)

        public class LocalChange internal constructor(
            value: List<ChangeInfo>,
        ) : DocEvent<List<ChangeInfo>>(value)

        public class RemoteChange internal constructor(
            value: List<ChangeInfo>,
        ) : DocEvent<List<ChangeInfo>>(value)

        public class ChangeInfo(public val change: Change, public val paths: List<String>)
    }
}
