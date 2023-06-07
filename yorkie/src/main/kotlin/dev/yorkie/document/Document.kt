package dev.yorkie.document

import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.ByteString
import dev.yorkie.api.toCrdtObject
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.util.YorkieLogger
import dev.yorkie.util.createSingleThreadDispatcher
import dev.yorkie.util.findPrefixes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.apache.commons.collections4.trie.PatriciaTrie

/**
 * A CRDT-based data type.
 * We can represent the model of the application and edit it even while offline.
 */
public class Document(public val key: Key) {
    private val dispatcher = createSingleThreadDispatcher("Document($key)")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val localChanges = mutableListOf<Change>()

    private val eventStream = MutableSharedFlow<Event>()
    public val events = eventStream.asSharedFlow()

    @Volatile
    private var root: CrdtRoot = CrdtRoot(CrdtObject(InitialTimeTicket, rht = ElementRht()))

    @get:VisibleForTesting
    @Volatile
    internal var clone: CrdtRoot? = null
        private set

    private var changeID = ChangeID.InitialChangeID

    @VisibleForTesting
    internal var checkPoint = CheckPoint.InitialCheckPoint

    internal val hasLocalChanges: Boolean
        get() = localChanges.isNotEmpty()

    @Volatile
    public var status = DocumentStatus.Detached
        internal set

    /**
     * Executes the given [updater] to update this document.
     */
    public fun updateAsync(
        message: String? = null,
        updater: (root: JsonObject) -> Unit,
    ): Deferred<Boolean> {
        return scope.async {
            require(status != DocumentStatus.Removed) {
                "document is removed"
            }

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
    internal suspend fun applyChangePack(pack: ChangePack): Unit = withContext(dispatcher) {
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

        pack.minSyncedTicket?.let(::garbageCollect)

        if (pack.isRemoved) {
            status = DocumentStatus.Removed
        }
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

    private suspend fun ensureClone(): CrdtRoot = withContext(dispatcher) {
        clone ?: root.deepCopy().also { clone = it }
    }

    /**
     * Create [ChangePack] of [localChanges] to send to the remote server.
     */
    internal suspend fun createChangePack(forceRemove: Boolean = false) = withContext(dispatcher) {
        val checkPoint = checkPoint.increaseClientSeq(localChanges.size)
        ChangePack(
            key.value,
            checkPoint,
            localChanges,
            null,
            null,
            forceRemove || status == DocumentStatus.Removed,
        )
    }

    /**
     * Sets [actorID] into this document.
     * This is also applied in the [localChanges] the document has.
     */
    internal suspend fun setActor(actorID: ActorID) = withContext(dispatcher) {
        localChanges.forEach {
            it.setActor(actorID)
        }
        changeID = changeID.setActor(actorID)

        // TODO: also apply to root
    }

    public suspend fun getRoot(): JsonObject = withContext(dispatcher) {
        val clone = ensureClone()
        val context = ChangeContext(changeID.next(), clone, null)
        JsonObject(context, clone.rootObject)
    }

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

    public interface Event {

        /**
         * An event that occurs when a snapshot is received from the server.
         */
        public class Snapshot internal constructor(public val data: ByteString) : Event

        /**
         * An event that occurs when the document is changed by local changes.
         */
        public class LocalChange internal constructor(
            public val changeInfos: List<ChangeInfo>,
        ) : Event

        /**
         * An event that occurs when the document is changed by remote changes.
         */
        public class RemoteChange internal constructor(
            public val changeInfos: List<ChangeInfo>,
        ) : Event

        /**
         * Represents a pair of [Change] and the JsonPath of the changed element.
         */
        public class ChangeInfo(
            public val change: Change,
            @Suppress("unused") public val paths: List<String>,
        )
    }

    /**
     * Represents a unique key to identify [Document].
     */
    @JvmInline
    public value class Key(public val value: String)

    /**
     * Represents the status of the [Document].
     */
    public enum class DocumentStatus {
        /**
         * Means that this [Document] is attached to the client.
         * The actor of the ticket is created with being assigned by the client.
         */
        Attached,

        /**
         * Means that this [Document] is not attached to the client.
         * The actor of the ticket is created without being assigned.
         */
        Detached,

        /**
         * Means that this [Document] is removed.
         * If the [Document] is removed, it cannot be edited.
         */
        Removed,
    }
}
