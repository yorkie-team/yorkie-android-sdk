package dev.yorkie.document

import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.ByteString
import dev.yorkie.api.toSnapshot
import dev.yorkie.core.Peers
import dev.yorkie.core.Peers.Companion.asPeers
import dev.yorkie.core.Presence
import dev.yorkie.core.PresenceChange
import dev.yorkie.core.PresenceInfo
import dev.yorkie.document.Document.Event.PeersChanged
import dev.yorkie.document.Document.PeersChangedResult.PresenceChanged
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonElement
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.util.YorkieLogger
import dev.yorkie.util.createSingleThreadDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

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
    internal var clone: RootClone? = null
        private set

    private var changeID = ChangeID.InitialChangeID

    @VisibleForTesting
    internal var checkPoint = CheckPoint.InitialCheckPoint
        private set

    internal val hasLocalChanges: Boolean
        get() = localChanges.isNotEmpty()

    @Volatile
    public var status = DocumentStatus.Detached
        internal set

    internal val garbageLength: Int
        get() = root.getGarbageLength()

    internal val onlineClients = mutableSetOf<ActorID>()

    private val _presences = MutableStateFlow(Peers.UninitializedPresences)
    public val presences: StateFlow<Peers> = _presences.asStateFlow()

    /**
     * Executes the given [updater] to update this document.
     */
    public fun updateAsync(
        message: String? = null,
        updater: suspend (root: JsonObject, presence: Presence) -> Unit,
    ): Deferred<Boolean> {
        return scope.async {
            check(status != DocumentStatus.Removed) {
                "document is removed"
            }

            val clone = ensureClone()
            val context = ChangeContext(
                id = changeID.next(),
                root = clone.root,
                message = message,
            )

            runCatching {
                val proxy = JsonObject(context, clone.root.rootObject)
                val presence = clone.presences.getOrPut(changeID.actor) { emptyMap() }
                updater.invoke(proxy, Presence(context, presence.toMutableMap()))
            }.onFailure {
                this@Document.clone = null
                YorkieLogger.e("Document.update", it.message.orEmpty())
                return@async false
            }

            if (!context.hasChange) {
                return@async true
            }
            val change = context.getChange()
            val operationInfos = change.execute(root, _presences.value)
            localChanges += change
            changeID = change.id
            val changeInfo = change.toChangeInfo(operationInfos)
            eventStream.emit(Event.LocalChange(changeInfo))
            if (change.hasPresenceChange) {
                val presence = _presences.value[change.id.actor] ?: return@async false
                eventStream.emit(
                    PeersChanged(
                        PresenceChanged(change.id.actor to presence),
                    ),
                )
            }
            true
        }
    }

    /**
     * Subscribes to events on the document with the specific [targetPath].
     */
    public fun events(targetPath: String): Flow<Event> {
        return events.filterNot { it is Event.Snapshot && targetPath != "&" }
            .mapNotNull { event ->
                when (event) {
                    is Event.Snapshot, is PeersChanged -> event
                    is Event.RemoteChange -> {
                        event.changeInfo.operations.filterTargetOpInfos(targetPath)
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                Event.RemoteChange(event.changeInfo.copy(operations = it))
                            }
                    }

                    is Event.LocalChange -> {
                        event.changeInfo.operations.filterTargetOpInfos(targetPath)
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                Event.LocalChange(event.changeInfo.copy(operations = it))
                            }
                    }
                }
            }
    }

    private fun List<OperationInfo>.filterTargetOpInfos(targetPath: String): List<OperationInfo> {
        return filter { isSameElementOrChildOf(it.path, targetPath) }
    }

    private fun isSameElementOrChildOf(element: String, parent: String): Boolean {
        return if (parent == element) {
            true
        } else {
            val nodePath = element.split(".")
            val targetPath = parent.split(".")
            targetPath.withIndex().all { (index, path) -> path == nodePath.getOrNull(index) }
        }
    }

    /**
     * Returns the [JsonElement] corresponding to the [path].
     */
    public suspend fun getValueByPath(path: String): JsonElement? {
        require(path.startsWith("$")) {
            "the path must start with \"$\""
        }
        val paths = path.split(".").drop(1)
        var value: JsonElement? = getRoot()
        paths.forEach { key ->
            value = when (value) {
                is JsonObject -> (value as JsonObject).getOrNull(key)
                is JsonArray -> (value as JsonArray)[key.toInt()]
                else -> return null
            }
        }
        return value
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
        val (root, presences) = snapshot.toSnapshot()
        this.root = CrdtRoot(root)
        _presences.value = presences.asPeers()
        changeID = changeID.syncLamport(serverSeq)
        clone = null
        eventStream.emit(Event.Snapshot(snapshot))
    }

    /**
     * Applies the given [changes] into this document.
     */
    private suspend fun applyChanges(changes: List<Change>) {
        val clone = ensureClone()
        changes.forEach { change ->
            change.execute(clone.root, _presences.value)
            val actorID = change.id.actor
            if (change.hasPresenceChange) {
                val presenceChange = change.presenceChange ?: return@forEach
                if (presenceChange is PresenceChange.PresencePut && actorID in onlineClients) {
                    val result = if (actorID in _presences.value) {
                        PresenceChanged(actorID to presenceChange.presence)
                    } else {
                        PeersChangedResult.Watched(actorID to presenceChange.presence)
                    }
                    eventStream.emit(PeersChanged(result))
                }
            }

            if (change.hasOperations) {
                val opInfos = change.execute(root, _presences.value)
                eventStream.emit(Event.RemoteChange(change.toChangeInfo(opInfos)))
            }

            changeID = changeID.syncLamport(change.id.lamport)
        }
    }

    private suspend fun ensureClone(): RootClone = withContext(dispatcher) {
        clone ?: RootClone(root.deepCopy(), _presences.value.toMutableMap()).also { clone = it }
    }

    /**
     * Create [ChangePack] of [localChanges] to send to the remote server.
     */
    internal suspend fun createChangePack(forceRemove: Boolean = false) = withContext(dispatcher) {
        val localChanges = localChanges.toList()
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
        val context = ChangeContext(changeID.next(), clone.root)
        JsonObject(context, clone.root.rootObject)
    }

    /**
     * Deletes elements that were removed before the given time.
     */
    internal fun garbageCollect(ticket: TimeTicket): Int {
        clone?.root?.garbageCollect(ticket)
        return root.garbageCollect(ticket)
    }

    /**
     * Triggers an event in this [Document].
     */
    internal suspend fun publish(event: Event) {
        eventStream.emit(event)
    }

    private fun Change.toChangeInfo(operationInfos: List<OperationInfo>) =
        Event.ChangeInfo(message.orEmpty(), operationInfos.map { it.updatePath() }, id.actor)

    private fun OperationInfo.updatePath(): OperationInfo {
        val path = root.createSubPaths(executedAt).joinToString(".")
        return apply { this.path = path }
    }

    public fun toJson(): String {
        return root.toJson()
    }

    public sealed interface Event {

        /**
         * An event that occurs when a snapshot is received from the server.
         */
        public class Snapshot internal constructor(public val data: ByteString) : Event

        /**
         * An event that occurs when the document is changed by local changes.
         */
        public class LocalChange internal constructor(
            public val changeInfo: ChangeInfo,
        ) : Event

        /**
         * An event that occurs when the document is changed by remote changes.
         */
        public class RemoteChange internal constructor(
            public val changeInfo: ChangeInfo,
        ) : Event

        public class PeersChanged(public val result: PeersChangedResult) : Event

        /**
         * Represents the modification made during a document update and the message passed.
         */
        public data class ChangeInfo(
            public val message: String,
            public val operations: List<OperationInfo>,
            public val actorID: ActorID,
        )
    }

    public sealed class PeersChangedResult {

        public class Initialized(public val changedPeers: Peers) : PeersChangedResult()

        public class PresenceChanged(public val changedPeer: Pair<ActorID, PresenceInfo>) :
            PeersChangedResult()

        public class Watched(public val changedPeer: Pair<ActorID, PresenceInfo>) :
            PeersChangedResult()

        public class Unwatched(public val changedPeer: ActorID) : PeersChangedResult()
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

    internal data class RootClone(
        val root: CrdtRoot,
        val presences: MutableMap<ActorID, PresenceInfo>,
    )
}
