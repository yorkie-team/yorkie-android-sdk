package dev.yorkie.document

import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.ByteString
import dev.yorkie.api.toSnapshot
import dev.yorkie.core.P
import dev.yorkie.core.Presence
import dev.yorkie.core.PresenceChange
import dev.yorkie.core.PresenceInfo
import dev.yorkie.core.Presences
import dev.yorkie.core.Presences.Companion.asPresences
import dev.yorkie.document.Document.Event.PresenceChange.MyPresence
import dev.yorkie.document.Document.Event.PresenceChange.Others
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
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

    private val _presences = MutableStateFlow(Presences())
    public val presences: StateFlow<Presences> = _presences.map { presences ->
        presences.filterKeys { it in onlineClients }.asPresences()
    }.stateIn(scope, SharingStarted.Eagerly, _presences.value.asPresences())

    @VisibleForTesting
    internal val allPresences: StateFlow<Presences> = _presences.asStateFlow()

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
            val actorID = changeID.actor

            runCatching {
                val proxy = JsonObject(context, clone.root.rootObject)
                updater.invoke(
                    proxy,
                    Presence(context, clone.presences[changeID.actor] ?: emptyMap()),
                )
            }.onFailure {
                this@Document.clone = null
                YorkieLogger.e("Document.update", it.message.orEmpty())
                return@async false
            }

            if (!context.hasChange) {
                return@async true
            }
            val change = context.getChange()
            val (operationInfos, newPresences) = change.execute(root, _presences.value)

            newPresences?.let { _presences.emit(it) }
            localChanges += change
            changeID = change.id

            if (change.hasOperations) {
                eventStream.emit(Event.LocalChange(change.toChangeInfo(operationInfos)))
            }
            if (change.hasPresenceChange) {
                val presence = _presences.value[actorID] ?: return@async false
                eventStream.emit(createPresenceChangedEvent(actorID, presence))
            }
            true
        }
    }

    private fun createPresenceChangedEvent(actorID: ActorID, presence: P): Event.PresenceChange {
        return if (actorID == changeID.actor) {
            MyPresence.PresenceChanged(PresenceInfo(actorID, presence))
        } else {
            Others.PresenceChanged(PresenceInfo(actorID, presence))
        }
    }

    /**
     * Subscribes to events on the document with the specific [targetPath].
     */
    public fun events(targetPath: String): Flow<Event> {
        return events.filterNot { it is Event.Snapshot && targetPath != "&" }
            .mapNotNull { event ->
                when (event) {
                    is Event.RemoteChange -> {
                        event.changeInfo.operations.filterTargetOpInfos(targetPath)
                            .takeIf { it.isNotEmpty() }
                            ?.let { Event.RemoteChange(event.changeInfo.copy(operations = it)) }
                    }

                    is Event.LocalChange -> {
                        event.changeInfo.operations.filterTargetOpInfos(targetPath)
                            .takeIf { it.isNotEmpty() }
                            ?.let { Event.LocalChange(event.changeInfo.copy(operations = it)) }
                    }

                    else -> event
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
        _presences.value = presences.asPresences()
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
            change.execute(clone.root, clone.presences).also { (_, newPresences) ->
                this.clone = clone.copy(presences = newPresences ?: return@also)
            }
            val actorID = change.id.actor
            var event: Event? = null
            if (change.hasPresenceChange && actorID in onlineClients) {
                val presenceChange = change.presenceChange ?: return@forEach
                event = when (presenceChange) {
                    is PresenceChange.Put -> {
                        if (actorID in _presences.value) {
                            createPresenceChangedEvent(actorID, presenceChange.presence)
                        } else {
                            Others.Watched(PresenceInfo(actorID, presenceChange.presence))
                        }
                    }

                    is PresenceChange.Clear -> {
                        onlineClients.remove(actorID)
                        presences.value[actorID]?.let { presence ->
                            Others.Unwatched(PresenceInfo(actorID, presence))
                        }
                    }
                }
            }

            val (opInfos, newPresences) = change.execute(root, _presences.value)
            if (change.hasOperations) {
                eventStream.emit(Event.RemoteChange(change.toChangeInfo(opInfos)))
            }

            event?.let { eventStream.emit(it) }
            newPresences?.let { _presences.emit(it) }
            changeID = changeID.syncLamport(change.id.lamport)
        }
    }

    private suspend fun ensureClone(): RootClone = withContext(dispatcher) {
        clone ?: RootClone(root.deepCopy(), _presences.value.asPresences()).also { clone = it }
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
        when (event) {
            is Others.Watched -> {
                presences.first { event.watched.actorID in it.keys }
            }

            is Others.Unwatched -> {
                presences.first { event.unwatched.actorID !in it.keys }
            }

            is MyPresence.Initialized -> {
                presences.first { event.initialized == it }
            }

            else -> {}
        }
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

        public sealed interface PresenceChange : Event {

            public sealed interface MyPresence : PresenceChange {

                /**
                 * Means that online clients have been loaded from the server.
                 */
                public data class Initialized(public val initialized: Presences) : MyPresence

                /**
                 * Means that the presences of the client has been updated.
                 */
                public data class PresenceChanged(public val changed: PresenceInfo) : MyPresence
            }

            public sealed interface Others : PresenceChange {

                /**
                 * Means that the client has established a connection with the server,
                 * enabling real-time synchronization.
                 */
                public data class Watched(public val watched: PresenceInfo) : Others

                /**
                 * Means that the client has been disconnected.
                 */
                public data class Unwatched(public val unwatched: PresenceInfo) : Others

                /**
                 * Means that the presences of the client has been updated.
                 */
                public data class PresenceChanged(public val changed: PresenceInfo) : Others
            }
        }

        /**
         * Represents the modification made during a document update and the message passed.
         */
        public data class ChangeInfo(
            public val message: String,
            public val operations: List<OperationInfo>,
            public val actorID: ActorID,
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

    internal data class RootClone(
        val root: CrdtRoot,
        val presences: Presences,
    )
}
