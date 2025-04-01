package dev.yorkie.document

import androidx.annotation.VisibleForTesting
import com.google.protobuf.ByteString
import dev.yorkie.api.toSnapshot
import dev.yorkie.document.Document.Event.DocumentStatusChanged
import dev.yorkie.document.Document.Event.PresenceChanged
import dev.yorkie.document.Document.Event.PresenceChanged.MyPresence
import dev.yorkie.document.Document.Event.PresenceChanged.Others
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
import dev.yorkie.document.presence.P
import dev.yorkie.document.presence.Presence
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.presence.PresenceInfo
import dev.yorkie.document.presence.Presences
import dev.yorkie.document.presence.Presences.Companion.UninitializedPresences
import dev.yorkie.document.presence.Presences.Companion.asPresences
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logDebug
import dev.yorkie.util.OperationResult
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrDocumentRemoved
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import dev.yorkie.util.checkYorkieError
import dev.yorkie.util.createSingleThreadDispatcher
import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A CRDT-based data type.
 * We can represent the model of the application and edit it even while offline.
 *
 * A single-threaded, [Closeable] [dispatcher] is used as default.
 * Therefore you need to [close] the document, when the document is no longer needed.
 * If you provide your own [dispatcher], it is up to you to decide [close] is needed or not.
 * [snapshotDispatcher] can be set differently from [dispatcher],
 * as snapshot operation can be much heavier than other operations.
 */
public class Document(
    public val key: Key,
    private val options: Options = Options(),
    private val dispatcher: CoroutineDispatcher = createSingleThreadDispatcher("Document($key)"),
    private val snapshotDispatcher: CoroutineDispatcher = dispatcher,
) : Closeable {
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

    @VisibleForTesting
    public val garbageLength: Int
        get() = root.garbageLength

    @VisibleForTesting
    internal val presenceEventQueue = mutableListOf<PresenceChanged>()
    private val pendingPresenceEvents = mutableListOf<PresenceChanged>()

    private val onlineClients = MutableStateFlow(setOf<ActorID>())

    private val _presences = MutableStateFlow(UninitializedPresences)
    public val presences: StateFlow<Presences> =
        combine(_presences, onlineClients) { presences, onlineClients ->
            presences.filterKeys { it in onlineClients + changeID.actor }.asPresences()
        }.stateIn(scope, SharingStarted.Eagerly, _presences.value).also {
            scope.launch {
                it.collect { presences ->
                    presenceEventQueue.addAll(pendingPresenceEvents)
                    pendingPresenceEvents.clear()
                    publishPresenceEvent(presences)
                }
            }
        }

    internal val allPresences: StateFlow<Presences> = _presences.asStateFlow()

    public val myPresence: P
        get() = allPresences.value[changeID.actor]
            .takeIf { status == DocumentStatus.Attached }
            .orEmpty()

    /**
     * Executes the given [updater] to update this document.
     */
    public fun updateAsync(
        message: String? = null,
        updater: suspend (root: JsonObject, presence: Presence) -> Unit,
    ): Deferred<OperationResult> {
        return scope.async {
            checkYorkieError(
                status != DocumentStatus.Removed,
                YorkieException(ErrDocumentRemoved, "document(${key}) is removed"),
            )

            val clone = ensureClone()
            val context = ChangeContext(
                id = changeID.next(),
                root = clone.root,
                message = message,
            )
            val actorID = changeID.actor

            val result = runCatching {
                val proxy = JsonObject(context, clone.root.rootObject)
                updater.invoke(
                    proxy,
                    Presence(context, clone.presences[changeID.actor].orEmpty()),
                )
            }.onFailure {
                this@Document.clone = null
                ensureActive()
            }
            if (result.isFailure) {
                return@async result
            }

            if (!context.hasChange) {
                return@async result
            }
            val change = context.getChange()
            val (operationInfos, newPresences) = change.execute(root, _presences.value)

            localChanges += change
            changeID = change.id

            if (change.hasOperations) {
                eventStream.emit(Event.LocalChange(change.toChangeInfo(operationInfos)))
            }
            if (change.hasPresenceChange) {
                val presence =
                    newPresences?.get(actorID) ?: _presences.value[actorID] ?: return@async result
                newPresences?.let {
                    emitPresences(it, createPresenceChangedEvent(actorID, presence))
                }
            }
            result
        }
    }

    private fun createPresenceChangedEvent(actorID: ActorID, presence: P): PresenceChanged {
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
    public suspend fun getValueByPath(path: String): JsonElement? = withContext(dispatcher) {
        checkYorkieError(
            path.startsWith("$"),
            YorkieException(ErrInvalidArgument, "the path must start with \"$\""),
        )

        val paths = path.split(".").drop(1)
        var value: JsonElement? = getRoot()
        paths.forEach { key ->
            value = when (value) {
                is JsonObject -> (value as JsonObject).getOrNull(key)
                is JsonArray -> (value as JsonArray)[key.toInt()]
                else -> return@withContext null
            }
        }
        value
    }

    /**
     * Applies the given [pack] into this document.
     * 1. Remove local changes applied to server.
     * 2. Update the checkpoint.
     * 3. Do Garbage collection.
     */
    internal suspend fun applyChangePack(pack: ChangePack): Unit = withContext(dispatcher) {
        if (pack.hasSnapshot) {
            applySnapshot(
                pack.checkPoint.serverSeq,
                pack.versionVector,
                checkNotNull(pack.snapshot),
            )
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

        if (pack.hasSnapshot) {
            applyChanges(localChanges)
        }

        checkPoint = checkPoint.forward(pack.checkPoint)

        if (!pack.hasSnapshot) {
            garbageCollect(pack.versionVector)
        }

        if (!pack.hasSnapshot) {
            filterVersionVector(pack.versionVector)
        }

        if (pack.isRemoved) {
            applyDocumentStatus(DocumentStatus.Removed)
        }
    }

    /**
     * Applies the given [snapshot] into this document.
     */
    private suspend fun applySnapshot(
        serverSeq: Long,
        snapshotVector: VersionVector,
        snapshot: ByteString,
    ) {
        val (root, presences) = withContext(snapshotDispatcher) {
            val (root, p) = snapshot.toSnapshot()
            CrdtRoot(root) to p.asPresences()
        }
        this.root = root
        _presences.value = presences
        logDebug("Document.snapshot") {
            "Snapshot: ${snapshot.toSnapshot()}"
        }
        changeID = changeID.setClocks(serverSeq, snapshotVector)
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
            var presenceEvent: PresenceChanged? = null
            if (change.hasPresenceChange && actorID in onlineClients.value) {
                val presenceChange = change.presenceChange ?: return@forEach
                presenceEvent = when (presenceChange) {
                    is PresenceChange.Put -> {
                        if (actorID in _presences.value) {
                            createPresenceChangedEvent(actorID, presenceChange.presence)
                        } else {
                            // NOTE(chacha912): When the user exists in onlineClients, but
                            // their presence was initially absent, we can consider that we have
                            // received their initial presence, so trigger the 'watched' event.
                            Others.Watched(PresenceInfo(actorID, presenceChange.presence))
                        }
                    }

                    is PresenceChange.Clear -> {
                        // NOTE(chacha912): When the user exists in onlineClients, but
                        // PresenceChange(clear) is received, we can consider it as detachment
                        // occurring before unwatching.
                        // Detached user is no longer participating in the document, we remove
                        // them from the online clients and trigger the 'unwatched' event.
                        presences.value[actorID]?.let { presence ->
                            Others.Unwatched(PresenceInfo(actorID, presence))
                        }.takeIf { actorID in onlineClients.value }
                            ?.also { removeOnlineClient(actorID) }
                    }
                }
            }

            val (opInfos, newPresences) = change.execute(root, _presences.value)
            if (opInfos.isNotEmpty()) {
                eventStream.emit(Event.RemoteChange(change.toChangeInfo(opInfos)))
            }
            newPresences?.let { emitPresences(it, presenceEvent) }
            changeID = changeID.syncClocks(change.id)
        }
    }

    internal suspend fun applyDocumentStatus(status: DocumentStatus) {
        if (this.status == status) {
            return
        }

        this.status = status
        publishEvent(
            DocumentStatusChanged(
                status,
                changeID.actor.takeIf { status == DocumentStatus.Attached },
            ),
        )
    }

    private suspend fun ensureClone(): RootClone = withContext(dispatcher) {
        clone ?: RootClone(root.deepCopy(), _presences.value.asPresences()).also { clone = it }
    }

    private suspend fun emitPresences(newPresences: Presences, event: PresenceChanged?) {
        event?.let(pendingPresenceEvents::add)
        _presences.emit(newPresences)
        clone = ensureClone().copy(presences = newPresences)
    }

    /**
     * Triggers an event in this [Document].
     */
    private suspend fun publishPresenceEvent(presences: Presences) {
        val iterator = presenceEventQueue.listIterator()
        var clearPresenceEventQueue = false
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (event is Others && event.changed.actorID == changeID.actor) {
                iterator.remove()
                continue
            }

            if (presenceEventReadyToBePublished(event, presences)) {
                if (presenceEventQueue.first() != event) {
                    clearPresenceEventQueue = true
                }
                eventStream.emit(event)
                iterator.remove()
            }
        }
        if (clearPresenceEventQueue) {
            presenceEventQueue.clear()
        }
    }

    private fun presenceEventReadyToBePublished(
        event: PresenceChanged,
        presences: Presences,
    ): Boolean {
        return when (event) {
            is MyPresence.Initialized -> presences.keys.containsAll(event.initialized.keys)
            is MyPresence.PresenceChanged -> {
                val actorID = event.changed.actorID
                event.changed.presence == presences[actorID]
            }

            is Others.Watched -> event.changed.actorID in presences
            is Others.Unwatched -> event.changed.actorID !in presences
            is Others.PresenceChanged -> {
                val actorID = event.changed.actorID
                event.changed.presence == presences[actorID]
            }
        }
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
            changeID.versionVector,
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

    /**
     * Returns a new proxy of cloned root.
     */
    public suspend fun getRoot(): JsonObject = withContext(dispatcher) {
        val clone = ensureClone()
        val context = ChangeContext(changeID.next(), clone.root)
        JsonObject(context, clone.root.rootObject)
    }

    /**
     * Returns a new proxy of deep-copied root.
     * It ensures thread-safety by avoiding reuse of [clone].
     */
    public suspend fun getRootThreadSafe(): JsonObject = withContext(dispatcher) {
        val clone = ensureClone().deepCopy()
        val context = ChangeContext(changeID.next(), clone.root)
        JsonObject(context, clone.root.rootObject)
    }

    internal fun getOnlineClients() = onlineClients.value

    internal fun setOnlineClients(actorIDs: Set<ActorID>) {
        onlineClients.value = actorIDs
    }

    internal fun addOnlineClient(actorID: ActorID) {
        onlineClients.value += actorID
    }

    internal fun removeOnlineClient(actorID: ActorID) {
        onlineClients.value -= actorID
    }

    /**
     * Filters detached client's Lamport timestamps from the version vector.
     */
    private fun filterVersionVector(minSyncedVersionVector: VersionVector) {
        val versionVector = changeID.versionVector
        val filteredVersionVector = versionVector.filter(minSyncedVersionVector)

        changeID = changeID.setVersionVector(filteredVersionVector)
    }


    /**
     * Deletes elements that were removed before the given time.
     */
    @VisibleForTesting
    public fun garbageCollect(minSyncedVersionVector: VersionVector): Int {
        if (options.disableGC) {
            return 0
        }

        clone?.root?.garbageCollect(minSyncedVersionVector)
        return root.garbageCollect(minSyncedVersionVector)
    }

    private fun Change.toChangeInfo(operationInfos: List<OperationInfo>) =
        Event.ChangeInfo(message.orEmpty(), operationInfos, id.actor, id.clientSeq, id.serverSeq)

    public fun toJson(): String {
        return root.toJson()
    }

    public suspend fun publishEvent(event: Event) {
        if (event is PresenceChanged) {
            pendingPresenceEvents.add(event)
        } else {
            eventStream.emit(event)
        }
    }

    override fun close() {
        scope.cancel()
        (dispatcher as? Closeable)?.close()
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

        public sealed interface PresenceChanged : Event {

            public sealed interface MyPresence : PresenceChanged {

                /**
                 * Means that online clients have been loaded from the server.
                 */
                public data class Initialized(public val initialized: Presences) : MyPresence

                /**
                 * Means that the presences of the client has been updated.
                 */
                public data class PresenceChanged(public val changed: PresenceInfo) : MyPresence
            }

            public sealed interface Others : PresenceChanged {
                public val changed: PresenceInfo

                /**
                 * Means that the client has established a connection with the server,
                 * enabling real-time synchronization.
                 */
                public data class Watched(override val changed: PresenceInfo) : Others

                /**
                 * Means that the client has been disconnected.
                 */
                public data class Unwatched(override val changed: PresenceInfo) : Others

                /**
                 * Means that the presences of the client has been updated.
                 */
                public data class PresenceChanged(override val changed: PresenceInfo) : Others
            }
        }

        /**
         * Means that the document sync status has changed.
         */
        public sealed interface SyncStatusChanged : Event {

            public data object Synced : SyncStatusChanged

            public data class SyncFailed(public val cause: Throwable?) : SyncStatusChanged
        }

        public sealed interface StreamConnectionChanged : Event {

            public data object Connected : StreamConnectionChanged

            public data object Disconnected : StreamConnectionChanged
        }

        /**
         * An event that occurs when the document's status has been changed.
         * @see DocumentStatus
         */
        public data class DocumentStatusChanged(
            val documentStatus: DocumentStatus,
            val actorID: ActorID?,
        ) : Event

        /**
         * `Broadcast` means that the broadcast event is received from the remote client.
         */
        public data class Broadcast(
            val actorID: ActorID?,
            val topic: String,
            val payload: String,
        ) : Event

        /**
         * Represents the modification made during a document update and the message passed.
         */
        public data class ChangeInfo(
            public val message: String,
            public val operations: List<OperationInfo>,
            public val actorID: ActorID,
            public val clientSeq: UInt,
            public val serverSeq: Long,
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

    public data class Options(
        /**
         * Disables garbage collection if true.
         */
        public val disableGC: Boolean = false,
    )

    /**
     * `BroadcastOptions` are the options to create a new broadcast.
     */
    public data class BroadcastOptions(
        /**
         * `maxRetries` is the maximum number of retries.
         */
        public val maxRetries: Int = Int.MAX_VALUE,
    )

    internal data class RootClone(val root: CrdtRoot, val presences: Presences) {

        fun deepCopy() = copy(root = root.deepCopy(), presences = presences.asPresences())
    }
}
