package dev.yorkie.document

import androidx.annotation.VisibleForTesting
import com.google.protobuf.ByteString
import dev.yorkie.api.PBChangePack
import dev.yorkie.api.snapshotToBytes
import dev.yorkie.api.toByteString
import dev.yorkie.api.toChangeID
import dev.yorkie.api.toChanges
import dev.yorkie.api.toPBChanges
import dev.yorkie.api.toSnapshot
import dev.yorkie.api.v1.changePack
import dev.yorkie.core.Attachable
import dev.yorkie.core.ResourceEvent
import dev.yorkie.core.ResourceStatus
import dev.yorkie.document.Document.Event.DocumentStatusChanged
import dev.yorkie.document.Document.Event.PresenceChanged
import dev.yorkie.document.Document.Event.PresenceChanged.MyPresence
import dev.yorkie.document.Document.Event.PresenceChanged.Others
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeExecutionResult
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.countNodes
import dev.yorkie.document.history.History
import dev.yorkie.document.history.HistoryOperation
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonElement
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.presence.DocPresence
import dev.yorkie.document.presence.P
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.presence.PresenceInfo
import dev.yorkie.document.presence.Presences
import dev.yorkie.document.presence.Presences.Companion.UninitializedPresences
import dev.yorkie.document.presence.Presences.Companion.asPresences
import dev.yorkie.document.schema.Rule
import dev.yorkie.document.schema.validateYorkieRuleset
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DocSize
import dev.yorkie.util.Logger.Companion.logDebug
import dev.yorkie.util.OperationResult
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrDocumentRemoved
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import dev.yorkie.util.checkYorkieError
import dev.yorkie.util.createSingleThreadDispatcher
import dev.yorkie.util.totalDocSize
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
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
 */
public class Document(
    private val key: String,
    private val options: Options = Options(),
) : Closeable, Attachable {
    private val dispatcher: CoroutineDispatcher =
        createSingleThreadDispatcher("Document($key)")

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val localChanges = mutableListOf<Change>()
    private val internalHistory = History()

    /**
     * Fired synchronously, right after a local [Change] is appended to [localChanges] (both
     * [updateAsync] and undo/redo), by [dev.yorkie.core.Client] to register a store persist for
     * this document with zero suspension in between (spec 029 B2): the old design subscribed to
     * [events] instead, which suspends in [toBytes] before the write is even registered, leaving a
     * window where [close] can cancel the collector mid-snapshot and silently drop the last edit.
     * Null when no [dev.yorkie.core.Client.Options.docStore] is configured.
     */
    internal var onLocalChange: (() -> Unit)? = null

    @Volatile
    private var isUpdating = false

    @Volatile
    private var maxSizeLimit = 0

    @Volatile
    private var schemaRules: List<Rule> = emptyList()

    /**
     * Declares that this document does not produce or consume tombstones.
     * Set by the client on attach and consumed by applyChanges to skip merging
     * remote actors' version vectors into [changeID], keeping each subsequent
     * local Change's version vector at O(1) for high-fan-out Counter workloads.
     * Distinct from [Options.disableGC], which gates the local GC pass.
     */
    @Volatile
    private var disableGC = false

    /**
     * Declares that this document does not produce, consume, or store presence.
     * Seeded from [Options.disablePresence] and overwritten with the
     * server-fixated value on attach. When true, presence changes from
     * [updateAsync] are silently dropped.
     */
    @Volatile
    private var disablePresence = options.disablePresence

    private var presenceDropWarned = false

    // Buffered so emitters (notably applyChangePack, which the Client sync loop calls
    // while holding the per-document mutex, and the watch-stream reader) are not
    // rendezvous-coupled to every collector: with zero capacity a single stalled
    // collector suspends emit() forever, freezing sync push+pull and watch reconnect
    // while the client still reports itself Connected (#351 follow-up). SUSPEND on
    // overflow is kept deliberately — events must not be silently dropped; the buffer
    // only decouples transient consumer latency from the document's critical sections.
    private val eventStream = MutableSharedFlow<Event>(extraBufferCapacity = 4096)
    public val events = eventStream.asSharedFlow()

    @Volatile
    private var root: CrdtRoot =
        CrdtRoot(CrdtObject(createdAt = InitialTimeTicket, memberNodes = ElementRht()))

    @get:VisibleForTesting
    @Volatile
    internal var clone: RootClone? = null
        private set

    @VisibleForTesting
    public var changeID = ChangeID.InitialChangeID

    @VisibleForTesting
    internal var checkPoint = CheckPoint.InitialCheckPoint
        private set

    // epoch is the document's compaction epoch, learned from the server on
    // applyChangePack and presented back on the next attach/sync so the
    // server can detect a stale-epoch mismatch after a force compaction.
    internal var epoch: Long = 0

    // docId is the server-assigned id recorded on attach. Empty before the
    // first attach, or when rehydrated from a legacy envelope that predates
    // docId support. The copy persisted in a byte envelope.
    internal var docId: String = ""
        private set

    /**
     * Records the server-assigned document id.
     */
    internal fun setDocId(id: String) {
        docId = id
    }

    @Volatile
    private var status = ResourceStatus.Detached

    @VisibleForTesting
    public val garbageLength: Int
        get() = root.garbageLength

    @VisibleForTesting
    internal val presenceEventQueue = mutableListOf<PresenceChanged>()
    private val pendingPresenceEvents = mutableListOf<PresenceChanged>()

    private val onlineClients = MutableStateFlow(setOf<String>())

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
            .takeIf { status == ResourceStatus.Attached }
            .orEmpty()

    /**
     * Provides undo/redo operations for local user changes in this document.
     * The resulting document changes are synchronized normally.
     */
    public val history: DocumentHistory = object : DocumentHistory {
        override fun canUndo(): Boolean = internalHistory.hasUndo() && !isUpdating

        override fun canRedo(): Boolean = internalHistory.hasRedo() && !isUpdating

        override fun undoAsync(): Deferred<OperationResult> = executeUndoRedo(isUndo = true)

        override fun redoAsync(): Deferred<OperationResult> = executeUndoRedo(isUndo = false)
    }

    /**
     * Interface for undo/redo operations on a document.
     */
    public interface DocumentHistory {
        /**
         * Returns true if there are operations that can be undone.
         */
        fun canUndo(): Boolean

        /**
         * Returns true if there are operations that can be redone.
         */
        fun canRedo(): Boolean

        /**
         * Undoes the last local operation.
         */
        fun undoAsync(): Deferred<OperationResult>

        /**
         * Redoes the last undone operation.
         */
        fun redoAsync(): Deferred<OperationResult>
    }

    /**
     * Executes the given [updater] to update this document.
     *
     * @param skipHistory Skips recording this change on the undo/redo history
     * stacks when true. The change still mutates the document, emits events,
     * and syncs normally; only the undo entry and redo-stack clearing are
     * skipped, mirroring how remote changes bypass local history. Defaults
     * to false, which preserves existing history behavior.
     */
    public fun updateAsync(
        message: String? = null,
        updater: suspend (root: JsonObject, presence: DocPresence) -> Unit,
    ): Deferred<OperationResult> = updateAsync(message, skipHistory = false, updater = updater)

    /**
     * Executes the given [updater] to update this document.
     *
     * A `skipHistory` write is treated exactly like a remote client's write for history
     * purposes: pending undo/redo entries are index-reconciled against it, but an entry
     * whose target element it overwrites or removes is not retargeted and undoes/redoes
     * as a no-op or replay, as with remote changes.
     *
     * @param skipHistory Skips recording this change on the undo/redo history
     * stacks when true. The change still mutates the document, emits events,
     * and syncs normally; only the undo entry and redo-stack clearing are
     * skipped, mirroring how remote changes bypass local history.
     */
    public fun updateAsync(
        message: String? = null,
        skipHistory: Boolean,
        updater: suspend (root: JsonObject, presence: DocPresence) -> Unit,
    ): Deferred<OperationResult> {
        return scope.async {
            checkYorkieError(
                status != ResourceStatus.Removed,
                YorkieException(ErrDocumentRemoved, "document($key) is removed"),
            )

            val clone = ensureClone()
            val context = ChangeContext(
                prevId = changeID,
                root = clone.root,
                message = message,
            )
            val actorID = changeID.actor
            val result = runCatching {
                val proxy = JsonObject(context, clone.root.rootObject)
                updater.invoke(
                    proxy,
                    DocPresence(context, clone.presences[changeID.actor].orEmpty()),
                )
            }.onFailure {
                this@Document.clone = null
                ensureActive()
            }
            if (result.isFailure) {
                return@async result
            }

            // Drop presence changes on presence-free documents. Document
            // operations in the same update still persist; a presence-only
            // change becomes a no-op and is never enqueued. Mirrors JS SDK
            // PR #1285.
            if (disablePresence && context.presenceChange != null) {
                context.presenceChange = null
                if (!presenceDropWarned) {
                    presenceDropWarned = true
                    logDebug("Document.updateAsync") {
                        "\"$key\" was attached with disablePresence=true; " +
                            "presence updates from updateAsync are silently dropped"
                    }
                }
            }

            val rules = schemaRules
            if (!context.isPresenceOnlyChange() && rules.isNotEmpty()) {
                val validateYorkieRulesetResult = validateYorkieRuleset(
                    data = this@Document.clone?.root?.rootObject,
                    ruleset = rules,
                )
                if (!validateYorkieRulesetResult.valid) {
                    this@Document.clone = null
                    throw YorkieException(
                        code = YorkieException.Code.ErrDocumentSchemaValidationFailed,
                        errorMessage = "schema validation failed: ${
                            validateYorkieRulesetResult
                                .errors
                                .joinToString {
                                    it.message
                                }
                        }",
                    )
                }
            }

            val size = totalDocSize(this@Document.clone?.root?.docSize)
            if (
                !context.isPresenceOnlyChange() &&
                maxSizeLimit > 0 &&
                maxSizeLimit < size
            ) {
                this@Document.clone = null
                throw YorkieException(
                    code = YorkieException.Code.ErrDocumentSizeExceedsLimit,
                    errorMessage = "document size exceeded: $size > $maxSizeLimit",
                )
            }

            if (!context.hasChange) {
                return@async result
            }
            val change = context.toChange()
            val localResult = change.execute(
                root,
                _presences.value,
                if (skipHistory) OpSource.LocalNoHistory else OpSource.Local,
            )
            val operationInfos = localResult.opInfos
            val newPresences = localResult.newPresences
            val reverseOps = localResult.reverseOps

            localChanges += change
            onLocalChange?.invoke()
            changeID = context.getNextId()

            if (skipHistory) {
                // A skipHistory write is treated exactly like a remote client's write for
                // history purposes: pending undo/redo entries are index-reconciled against
                // it, but it is never itself pushed onto the undo stack.
                reconcileHistoryEdits(localResult)
            } else {
                // NOTE(hackerwins, document.ts:855-864 @ v0.7.21): a local Set
                // replaces an array element with a new value. Pending undo/redo
                // entries may still reference the replaced element's old
                // createdAt, so they are reconciled to the newly installed
                // value's createdAt here, before this change's own reverse ops
                // are pushed. Not run for a skipHistory write: that write is
                // treated like a remote change and must not retarget pending
                // entries, per the skipHistory contract on this function.
                for (op in change.operations) {
                    if (op is ArraySetOperation) {
                        internalHistory.reconcileCreatedAt(op.createdAt, op.value.createdAt)
                    }
                }
                val reverseHistoryOps = reverseOps.map { HistoryOperation.Op(it) }
                if (reverseHistoryOps.isNotEmpty()) {
                    internalHistory.pushUndo(reverseHistoryOps)
                }
                if (operationInfos.isNotEmpty()) {
                    internalHistory.clearRedo()
                }
            }

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

    private fun executeUndoRedo(isUndo: Boolean): Deferred<OperationResult> {
        return scope.async {
            if (isUpdating) {
                return@async Result.failure(
                    IllegalStateException(
                        "${if (isUndo) "Undo" else "Redo"} is not allowed during an update",
                    ),
                )
            }

            val ops = if (isUndo) {
                internalHistory.popUndo()
            } else {
                internalHistory.popRedo()
            }
            if (ops == null) {
                // Empty stack is a no-op so callers need not always guard with
                // canUndo()/canRedo(). Mirrors JS SDK PR #1238.
                return@async Result.success(Unit)
            }

            val clone = ensureClone()
            val context = ChangeContext(
                prevId = changeID,
                root = clone.root,
            )

            for ((index, historyOp) in ops.withIndex()) {
                when (historyOp) {
                    is HistoryOperation.Op -> {
                        val op = historyOp.operation
                        val ticket = context.issueTimeTicket()
                        op.executedAt = ticket

                        // Reconcile createdAt for ArraySet and Add operations
                        if (op is ArraySetOperation) {
                            val prev = op.createdAt
                            val prevValueId = op.value.createdAt
                            op.value.createdAt = ticket
                            internalHistory.reconcileCreatedAt(prev, ticket)
                            reconcileOpsCreatedAt(ops, index + 1, prev, ticket)
                            // The value is re-issued under a new id here too: member ops
                            // built in the same update (`setNewObject(i)["k"] = v`) name
                            // the value's OLD id as their parent, not the target slot's id
                            // reconciled just above, so both must be retargeted.
                            internalHistory.reconcileCreatedAt(prevValueId, ticket)
                            reconcileOpsCreatedAt(ops, index + 1, prevValueId, ticket)
                        } else if (op is AddOperation) {
                            val prev = op.value.createdAt
                            op.value.createdAt = ticket
                            internalHistory.reconcileCreatedAt(prev, ticket)
                            reconcileOpsCreatedAt(ops, index + 1, prev, ticket)
                        } else if (op is TreeEditOperation && op.removedNodeSnapshots != null) {
                            // Copy-reinsert undo (port 4ec66cc0): buildFreshNodes
                            // mints one fresh ticket per node in every snapshot
                            // subtree, starting right after `ticket`'s delimiter,
                            // but those tickets were never reserved in this
                            // context — a later op in the same undo/redo batch
                            // would otherwise repeat one via its own
                            // issueTimeTicket() call. Reserve exactly that many
                            // so the next op resumes after the range
                            // buildFreshNodes will consume. Restore-mode
                            // reverses (removedNodeSnapshots == null) never call
                            // buildFreshNodes and need no reservation.
                            val ticketCount = op.removedNodeSnapshots.sumOf { it.countNodes() }
                            repeat(ticketCount) { context.issueTimeTicket() }
                        }

                        context.push(op)
                    }

                    is HistoryOperation.Presence -> {
                        // Presence undo — not implemented in v0.6.36 scope
                    }
                }
            }

            if (!context.hasChange) {
                return@async Result.success(Unit)
            }

            val change = context.toChange()
            // Execute on clone (validation)
            change.execute(clone.root, clone.presences, OpSource.UndoRedo)
            // Execute on root (real application)
            val undoRedoResult = change.execute(
                root,
                _presences.value,
                OpSource.UndoRedo,
            )
            val opInfos = undoRedoResult.opInfos
            val reverseOps = undoRedoResult.reverseOps

            val reverseHistoryOps = reverseOps.map { HistoryOperation.Op(it) }
            if (reverseHistoryOps.isNotEmpty()) {
                if (isUndo) {
                    internalHistory.pushRedo(reverseHistoryOps)
                } else {
                    internalHistory.pushUndo(reverseHistoryOps)
                }
            }

            if (opInfos.isEmpty()) {
                return@async Result.success(Unit)
            }

            localChanges += change
            onLocalChange?.invoke()
            changeID = context.getNextId()

            if (opInfos.isNotEmpty()) {
                eventStream.emit(Event.LocalChange(change.toChangeInfo(opInfos)))
            }

            Result.success(Unit)
        }
    }

    /**
     * Reconciles parentCreatedAt references in the remaining ops of the current batch.
     * When an AddOperation or ArraySetOperation assigns a new createdAt to its value,
     * subsequent ops in the same batch that reference the old createdAt as their
     * parentCreatedAt must be updated to point to the new one.
     */
    private fun reconcileOpsCreatedAt(
        ops: List<HistoryOperation>,
        fromIndex: Int,
        prevCreatedAt: TimeTicket,
        currCreatedAt: TimeTicket,
    ) {
        for (i in fromIndex until ops.size) {
            val historyOp = ops[i]
            if (historyOp !is HistoryOperation.Op) continue
            val op = historyOp.operation
            if (op.parentCreatedAt === prevCreatedAt) {
                op.parentCreatedAt = currCreatedAt
            }
        }
    }

    private fun createPresenceChangedEvent(actorID: String, presence: P): PresenceChanged {
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
     * Removes local changes if the client sequence number is less than or equal to [clientSeq].
     */
    private fun removePushedLocalChanges(clientSeq: UInt) {
        val iterator = localChanges.iterator()
        while (iterator.hasNext()) {
            val change = iterator.next()
            if (change.id.clientSeq > clientSeq) {
                break
            }
            iterator.remove()
        }
    }

    /**
     * Applies the given [pack] into this document.
     * 1. Update the checkpoint.
     * 2. Do Garbage collection.
     */
    internal suspend fun applyChangePack(pack: ChangePack): Unit = withContext(dispatcher) {
        if (pack.hasSnapshot) {
            applySnapshot(
                pack.versionVector,
                checkNotNull(pack.snapshot),
                pack.checkPoint.clientSeq,
            )
        } else {
            applyChanges(pack.changes)
            removePushedLocalChanges(pack.checkPoint.clientSeq)
        }

        checkPoint = checkPoint.forward(pack.checkPoint)
        // Learn the document's current compaction epoch from the server so a
        // subsequent attach/sync (and any persisted envelope) presents it back.
        epoch = pack.epoch

        if (!pack.hasSnapshot) {
            garbageCollect(pack.versionVector)
        }

        if (pack.isRemoved) {
            applyStatus(ResourceStatus.Removed)
        }
    }

    /**
     * Applies the given [snapshot] into this document.
     */
    private suspend fun applySnapshot(
        snapshotVector: VersionVector,
        snapshot: ByteString,
        clientSeq: UInt,
    ) {
        val (root, presences) = withContext(dispatcher) {
            val (root, p) = snapshot.toSnapshot()
            CrdtRoot(root) to p.asPresences()
        }
        this.root = root
        _presences.value = presences
        logDebug("Document.snapshot") {
            "Snapshot: ${snapshot.toSnapshot()}"
        }
        changeID = changeID.setClocks(snapshotVector.maxLamport(), snapshotVector)
        clone = null
        removePushedLocalChanges(clientSeq)
        clearHistory()
        eventStream.emit(Event.Snapshot(snapshot))
    }

    /**
     * Flushes both undo and redo stacks. Used after applying a snapshot or
     * after attach so that setup operations are not reachable via undo.
     * Mirrors JS SDK PR #1238.
     */
    internal fun clearHistory() {
        internalHistory.clearUndo()
        internalHistory.clearRedo()
    }

    /**
     * Applies the given [changes] into this document, sourced from [source]
     * (defaults to [OpSource.Remote], the pre-existing behaviour of every
     * caller other than [restoreAppendedChanges], which applies a replayed
     * log as [OpSource.Local]).
     */
    private suspend fun applyChanges(changes: List<Change>, source: OpSource = OpSource.Remote) {
        val clone = ensureClone()
        changes.forEach { change ->
            change.execute(clone.root, clone.presences, source).also { cloneResult ->
                this.clone = clone.copy(presences = cloneResult.newPresences ?: return@also)
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

            val remoteResult = change.execute(root, _presences.value, source)
            val opInfos = remoteResult.opInfos
            val newPresences = remoteResult.newPresences

            // Reconcile text and tree undo/redo stack entries against remote edits.
            // Only reconcile against changes from other clients.
            if (change.id.actor != changeID.actor) {
                reconcileHistoryEdits(remoteResult)
            }

            if (opInfos.isNotEmpty()) {
                val info = change.toChangeInfo(opInfos)
                eventStream.emit(
                    if (source == OpSource.Local) {
                        Event.LocalChange(
                            info,
                        )
                    } else {
                        Event.RemoteChange(info)
                    },
                )
            }
            newPresences?.let {
                emitPresences(it, presenceEvent)
            }
            changeID = if (disableGC) {
                changeID.syncLamport(change.id)
            } else {
                changeID.syncClocks(change.id)
            }
        }
    }

    /**
     * Reconciles pending undo/redo stack entries against the operations in [result].
     * Adjusts index-based undo ranges so that entries created before [result] was executed
     * still target the correct positions afterward. Used both for remote changes from other
     * clients ([applyChanges]) and for local `skipHistory` changes ([updateAsync]), which are
     * treated identically for history-reconciliation purposes.
     */
    private fun reconcileHistoryEdits(result: ChangeExecutionResult) {
        var opInfoIndex = 0
        result.executedOperations.forEachIndexed { index, executedOp ->
            // Slice by each operation's own recorded opInfo count instead of
            // peeking at opInfo types: a zero-effect edit emits none, an
            // ordinary edit emits one, and executeRestore's retombstone-then-
            // restore split can emit two — but two adjacent EditOperations can
            // each emit exactly one, which type-peeking cannot tell apart from
            // a single operation's two entries.
            val count = result.opInfoCounts[index]
            val opInfosForOp = result.opInfos.subList(opInfoIndex, opInfoIndex + count)
            opInfoIndex += count

            when (executedOp) {
                is EditOperation -> {
                    // Reconcile once per entry so pending undo/redo offsets
                    // shift correctly for every affected span.
                    opInfosForOp.filterIsInstance<OperationInfo.EditOpInfo>()
                        .forEach { opInfo ->
                            internalHistory.reconcileTextEdit(
                                executedOp.parentCreatedAt,
                                opInfo.from,
                                opInfo.to,
                                opInfo.value.text.length,
                            )
                        }
                }

                is TreeEditOperation -> {
                    // For tree edits there may be multiple TreeEditOpInfos per operation
                    // (split/merge decomposes into multiple ranges). Reconcile using the
                    // first deletion range's from/to and the inserted node count.
                    opInfosForOp.filterIsInstance<OperationInfo.TreeEditOpInfo>()
                        .firstOrNull()
                        ?.let { opInfo ->
                            val insertedSize = opInfo.nodes?.size ?: 0
                            internalHistory.reconcileTreeEdit(
                                executedOp.parentCreatedAt,
                                opInfo.from,
                                opInfo.to,
                                insertedSize,
                            )
                        }
                }

                else -> Unit
            }
        }
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
            is MyPresence.Initialized -> {
                presences.keys.containsAll(event.initialized.keys)
            }

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
            key,
            checkPoint,
            localChanges,
            null,
            forceRemove || status == ResourceStatus.Removed,
            changeID.versionVector,
            epoch,
        )
    }

    /**
     * `getKey` returns the key of this document.
     */
    override fun getKey(): String {
        return key
    }

    /**
     * `getStatus` returns the status of this document.
     */
    override fun getStatus(): ResourceStatus {
        return status
    }

    override fun applyStatus(status: ResourceStatus) {
        if (this.status == status) {
            return
        }

        this.status = status
        scope.launch {
            publishEvent(
                DocumentStatusChanged(
                    status,
                    changeID.actor.takeIf { status == ResourceStatus.Attached },
                ),
            )
        }
    }

    /**
     * Sets [actorID] into this document.
     * This is also applied in the [localChanges] the document has.
     */
    override fun setActor(actorID: String) {
        localChanges.forEach {
            it.setActor(actorID)
        }
        changeID = changeID.setActor(actorID)

        // TODO: also apply to root
    }

    /**
     * `hasLocalChanges` returns whether this document has local changes or not.
     */
    override fun hasLocalChanges(): Boolean {
        return localChanges.isNotEmpty()
    }

    /**
     * Returns the currently pending (un-pushed) local changes. iOS
     * `getPendingChangeStructs` returns [Change]; Android's struct layer
     * exists only for JS's JSON encoding, which this SDK does not need.
     */
    internal suspend fun pendingChanges(): List<Change> = withContext(dispatcher) {
        // Confined like [pendingChangesAfter]: `localChanges` is owned by this document's
        // dispatcher, and every caller used to be safe only by an attach-time invariant.
        localChanges.toList()
    }

    /**
     * Returns the un-pushed local changes whose [ChangeID.clientSeq] is
     * strictly greater than [clientSeq], in queue order. Mirrors JS
     * `getPendingChangesAfter`; used by the offline-persistence layer to
     * append only what is new to the change log. Suspend and confined to
     * [dispatcher]: [localChanges]
     * is a plain `mutableListOf` mutated on this document's own dispatcher
     * (`updateAsync`), while the persist collector (`Client.append`) used to
     * call this as a non-suspending field read from the CLIENT's dispatcher —
     * a concurrent-iteration race (`ConcurrentModificationException`) that
     * silently killed persistence under a burst of edits. The `filter` below
     * already returns a fresh list, so the [withContext] hop is the only
     * change needed; the caller gets a snapshot copy, same shape as
     * [persistBase].
     */
    internal suspend fun pendingChangesAfter(clientSeq: UInt): List<Change> =
        withContext(dispatcher) {
            localChanges.filter { it.id.clientSeq > clientSeq }
        }

    /**
     * Serializes this document's full restorable state — root, presences,
     * checkpoint, changeID, pending changes, compaction epoch, and docId —
     * into a self-contained byte envelope. Non-suspending: assumes it runs on
     * [dispatcher] already, so a caller composing it with another
     * non-suspending dispatcher-confined read (e.g. [persistBase]) gets one
     * atomic step rather than two dispatcher hops with a gap
     * between them. [toBytes] is this method under its own [withContext];
     * this single source of truth for the envelope prevents the
     * two-copies-drift class of bug the incremental store exists to prevent.
     */
    private fun buildEnvelopeBytes(): ByteArray {
        val snapshotBlob = snapshotToBytes(root.rootObject, _presences.value).toByteArray()
        val checkpointBlob = checkPoint.toCheckpointBytes()
        val changeIDBlob = changeID.toByteString().toByteArray()
        // Pending changes are carried as a serialized PBChangePack used purely
        // as a changes container (iOS a77ff589da precedent): Android has no
        // struct layer like JS's toStruct, but already round-trips Change
        // through protobuf. The envelope is local-only and never crosses SDKs.
        val pendingChangesBlob =
            changePack { changes.addAll(localChanges.toList().toPBChanges()) }.toByteArray()
        // Appended after pending changes so a four-blob legacy envelope still
        // decodes: fromBytes treats a missing epoch blob as 0.
        val epochBlob = epoch.toString().toByteArray(Charsets.UTF_8)
        // Appended last so an envelope written before docID support (five
        // blobs) still decodes: fromBytes treats a missing docID blob as "".
        val docIdBlob = docId.toByteArray(Charsets.UTF_8)
        return packBlobs(
            listOf(
                snapshotBlob,
                checkpointBlob,
                changeIDBlob,
                pendingChangesBlob,
                epochBlob,
                docIdBlob,
            ),
        )
    }

    /**
     * Serializes this document's full restorable state — root, presences,
     * checkpoint, changeID, pending changes, compaction epoch, and docId —
     * into a self-contained byte envelope. The reverse of [Companion.fromBytes].
     */
    public suspend fun toBytes(): ByteArray = withContext(dispatcher) {
        buildEnvelopeBytes()
    }

    /**
     * Rehydrates this document's full state from a previously [toBytes]
     * envelope, in place. All-or-nothing: the actor guard runs before any
     * field write, so a rejected restore leaves this document completely
     * untouched. The caller must [setActor] first — setActor does not
     * rewrite root element actors (a documented JS/iOS limitation), so
     * restoring under a stale actor risks diverging the CRDT.
     *
     * A successful (matching-actor) restore replaces any local edits already made on this
     * document before its first attach with the persisted envelope's own pending changes, with
     * no [Document.Event.LocalChangesDropped] event — that event only fires on the failure
     * paths (actor mismatch, corrupt envelope, tier-3 purge, epoch re-anchor). This is JS parity
     * (`document.ts`'s `restoreFromBytes`), not an Android-specific gap.
     *
     * Returns a [RestoreResult] instead of throwing on an actor mismatch (a corrupt envelope
     * still throws, from [fromBytes] below): the caller classifies by result type, so it decodes
     * [bytes] exactly once instead of re-decoding to tell the two failure modes apart (spec 029
     * I5/M1).
     */
    internal suspend fun restoreFromBytes(bytes: ByteArray): RestoreResult =
        withContext(dispatcher) {
            val currentActor = changeID.actor
            val restored = fromBytes(key, bytes, options)
            try {
                restoreFrom(restored, currentActor)
            } finally {
                // The decoded instance owns a dispatcher and scope of its own; only its
                // fields are kept.
                restored.close()
            }
        }

    private fun restoreFrom(restored: Document, currentActor: String): RestoreResult {
        val restoredActor = restored.changeID.actor
        // JS/iOS parity (spec 029 D1): an envelope written under the initial actor — only
        // reachable by an app calling the public toBytes() on a never-attached Document and
        // writing it under the client's own store key — is rejected the same as any other
        // mismatched actor. Previously exempted on either side, but the client already stamps
        // the stable actor before every restore attempt (requireActorId(), before this runs),
        // so a legitimately-restored envelope never carries the initial actor in the first place.
        if (currentActor != restoredActor) {
            // restored.localChanges is read (and defensively copied) before restored.close()
            // runs in restoreFromBytes's finally, so this is safe despite restored owning its
            // own now-about-to-be-cancelled scope/dispatcher.
            return RestoreResult.ActorMismatch(restored.localChanges.toList())
        }
        root = restored.root
        _presences.value = restored._presences.value
        checkPoint = restored.checkPoint
        changeID = restored.changeID
        localChanges.clear()
        localChanges.addAll(restored.localChanges)
        epoch = restored.epoch
        docId = restored.docId
        clone = null
        // Reverse-ops reference the pre-restore root/changeID.
        clearHistory()
        return RestoreResult.Restored
    }

    /**
     * Serializes just the checkpoint and changeID — plus the compaction
     * epoch and docId — the client's position against the server, without
     * touching the root. Mirrors JS `metaToBytes`.
     *
     * This is what the offline-persistence layer writes after a sync. A sync
     * advances the checkpoint while leaving the document unchanged, so
     * re-serializing the whole document to record it would cost time
     * proportional to the document for information that is a few dozen
     * bytes. The epoch and docId are learned from sync responses, so meta is
     * the only place they can be recorded between snapshots: omitting the
     * epoch would make a server-side force-compaction invisible until the
     * next attach presented a stale one, took `ErrEpochMismatch`, and
     * re-anchored — discarding every un-pushed edit for want of a field.
     *
     * Public — mirrors the public [toBytes] (a durable store may want to
     * inspect it directly).
     */
    public suspend fun metaToBytes(): ByteArray = withContext(dispatcher) {
        packBlobs(
            listOf(
                checkPoint.toCheckpointBytes(),
                changeID.toByteString().toByteArray(),
                epoch.toString().toByteArray(Charsets.UTF_8),
                docId.toByteArray(Charsets.UTF_8),
            ),
        )
    }

    /**
     * Applies the bytes produced by [metaToBytes], overwriting the
     * checkpoint and the trailing fields present in [bytes]. Trailing blobs
     * stay optional, the same extension rule the [toBytes] envelope follows,
     * so a meta written before a field existed still decodes. Never touches
     * [root]. Mirrors JS `restoreMetaFromBytes`.
     *
     * The pending queue is trimmed to the restored checkpoint
     * ([removePushedLocalChanges]) — a Kotlin hardening: an envelope written before a sync carries
     * changes the header now says were acked, and keeping them queued would
     * re-push them on the next sync. JS leaves them queued and relies on the
     * server skipping an already-applied `clientSeq`; the root is unchanged
     * either way, since the envelope's root already reflects them.
     *
     * @throws YorkieException with [ErrInvalidArgument] when [bytes] is
     * empty, or any blob it carries (checkpoint, changeID, epoch) cannot be
     * parsed. All-or-nothing: every blob is
     * decoded into a local before any field is written, so a failure
     * partway through — e.g. a corrupt changeID blob — cannot leave
     * [checkPoint] written while [changeID] stays stale. JS `bytesToChangeID`
     * is unwrapped and assigns the checkpoint first; this stricter,
     * all-or-nothing shape is a deliberate Kotlin hardening. A zero-length changeID blob
     * is rejected rather than silently decoding to a default-valued
     * [ChangeID] (JS is lenient here).
     */
    internal suspend fun restoreMetaFromBytes(bytes: ByteArray): Unit = withContext(dispatcher) {
        val blobs = unpackBlobs(bytes)
        checkYorkieError(
            blobs.isNotEmpty(),
            YorkieException(ErrInvalidArgument, "corrupt meta: expected at least 1 blob, got 0"),
        )
        val decodedCheckPoint = blobs[0].toCheckPoint()
        val decodedChangeID = if (blobs.size > 1) {
            checkYorkieError(
                blobs[1].isNotEmpty(),
                YorkieException(ErrInvalidArgument, "corrupt meta: empty changeID blob"),
            )
            try {
                ByteString.copyFrom(blobs[1]).toChangeID()
            } catch (e: Exception) {
                throw YorkieException(
                    ErrInvalidArgument,
                    "corrupt meta: invalid changeID blob: ${e.message}",
                )
            }
        } else {
            null
        }
        val decodedEpoch = if (blobs.size > 2) {
            // Same ASCII-digits-only rule as the envelope's epoch blob in [fromBytes].
            EpochRegex.matchEntire(String(blobs[2], Charsets.UTF_8))
                ?.value
                ?.toLongOrNull()
                ?: throw YorkieException(ErrInvalidArgument, "corrupt meta: invalid epoch blob")
        } else {
            null
        }
        val decodedDocId = if (blobs.size > 3) String(blobs[3], Charsets.UTF_8) else null

        checkPoint = decodedCheckPoint
        decodedChangeID?.let { changeID = it }
        decodedEpoch?.let { epoch = it }
        decodedDocId?.let { docId = it }
        removePushedLocalChanges(checkPoint.clientSeq)
    }

    /**
     * Replays changes that were recorded *after* the snapshot this document
     * was restored from, as the offline-persistence layer's change log holds
     * them. Mirrors JS `restoreAppendedChanges`.
     *
     * These are the opposite case to the pending changes carried inside a
     * [toBytes] envelope. Those are already reflected in the snapshot's root
     * — [toBytes] serializes the live root — so [Companion.fromBytes] queues
     * them without applying. A change from the log was written after that
     * root was captured, so it must be both **applied**, to bring the root
     * forward, and **queued**, so it is still pushed. Doing only the first
     * loses the edit on reconnect; doing only the second leaves the user
     * looking at stale content.
     *
     * The log must be contiguous and ascending by [ChangeID.clientSeq]. A
     * caller that cannot satisfy that should restore from the snapshot alone
     * and report the loss rather than replaying a broken run: this throws
     * BEFORE any mutation, so the document is left completely untouched.
     *
     * Preconditions the caller must enforce:
     * this document must be quiescent (no concurrent writer) and [changes]
     * must share this document's own actor. Neither precondition is checked
     * here — a replayed entry under a foreign actor, or a replay racing a
     * concurrent local edit, is caller misuse this method does not defend
     * against; the store-backed restore path (`Client.kt`) enforces both
     * before calling this.
     *
     * @throws YorkieException with [ErrInvalidArgument] when [changes] is
     * not strictly ascending by clientSeq.
     */
    internal suspend fun restoreAppendedChanges(
        changes: List<Change>,
        ackedClientSeq: UInt = 0u,
    ): Unit = withContext(dispatcher) {
        if (changes.isEmpty()) return@withContext

        var prev: UInt? = null
        for (change in changes) {
            val clientSeq = change.id.clientSeq
            if (prev != null && clientSeq <= prev) {
                throw YorkieException(
                    ErrInvalidArgument,
                    "appended changes must be ascending by clientSeq, got $clientSeq after $prev",
                )
            }
            prev = clientSeq
        }

        // Every entry is applied — the log is the delta between the
        // snapshot and current content, so skipping an acked one would
        // leave the root behind. Only the unacked ones are queued:
        // re-pushing what the server has already taken presents a
        // clientSeq it will skip.
        applyChanges(changes, OpSource.Local)
        localChanges.addAll(changes.filter { it.id.clientSeq > ackedClientSeq })

        // Adopt the last replayed change's ID as the document's own
        // counter. applyChanges only syncs clocks, which leaves clientSeq
        // behind and over-advances lamport. Guarded: an all-acked replay
        // must not pull the clock back below what the meta header already
        // established.
        val lastID = changes.last().id
        if (lastID.clientSeq >= changeID.clientSeq) {
            changeID = lastID
        }

        // The clone predates the replay, and the history's reverse-ops
        // reference the pre-replay state — the same reasoning
        // restoreFromBytes applies.
        clone = null
        clearHistory()
    }

    /**
     * The result of [persistBase]: a [toBytes]-equal snapshot paired with
     * the highest [ChangeID.clientSeq] it already carries.
     */
    internal data class PersistBase(val snapshot: ByteArray, val lastCarriedClientSeq: UInt)

    /**
     * The atomic base read a store write needs: a [toBytes]-equal snapshot
     * paired with the highest [ChangeID.clientSeq] it already carries.
     * Mirrors the iOS finding for `4213eecc67` (`aaa5cb15`/#1354): JS reads
     * `toBytes()` and `getPendingChangesAfter(0)` in one synchronous block;
     * on a coroutine dispatcher those would be two hops with a suspension
     * gap between them, and an edit landing in that gap would be in neither
     * the snapshot nor the log — durably lost. Because [buildEnvelopeBytes]
     * is non-suspending and the [localChanges]/[checkPoint] reads are plain
     * field reads, this whole block runs under [dispatcher] with no
     * suspension in between.
     */
    internal suspend fun persistBase(): PersistBase = withContext(dispatcher) {
        val snapshot = buildEnvelopeBytes()
        val lastCarried =
            maxOf(localChanges.lastOrNull()?.id?.clientSeq ?: 0u, checkPoint.clientSeq)
        PersistBase(snapshot, lastCarried)
    }

    /**
     * Mirrors constructing a brand-new Document instance without forcing the
     * caller to swap the object reference it already holds.
     */
    internal suspend fun resetForReanchor(): Unit = withContext(dispatcher) {
        changeID = ChangeID.InitialChangeID
        checkPoint = CheckPoint.InitialCheckPoint
        localChanges.clear()
        epoch = 0
        docId = ""
        root = CrdtRoot(CrdtObject(createdAt = InitialTimeTicket, memberNodes = ElementRht()))
        _presences.value = UninitializedPresences
        clone = null
        clearHistory()
    }

    override fun publish(event: ResourceEvent) {
        scope.launch {
            (event as? Event)?.let {
                eventStream.emit(it)
            }
        }
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
     * `getDocSize` returns the size of this document.
     */
    public fun getDocSize(): DocSize {
        return root.docSize
    }

    /**
     * `getRootObject` returns root object.
     */
    internal fun getRootObject(): CrdtObject {
        return root.rootObject
    }

    internal fun getOnlineClients() = onlineClients.value

    internal fun setOnlineClients(actorIDs: Set<String>) {
        onlineClients.value = actorIDs
    }

    internal fun addOnlineClient(actorID: String) {
        onlineClients.value += actorID
    }

    internal fun removeOnlineClient(actorID: String) {
        onlineClients.value -= actorID
    }

    internal fun clearPresence(actorID: String) {
        _presences.value = _presences.value - actorID
    }

    /**
     * `getVersionVector` returns the version vector of document
     */
    fun getVersionVector(): VersionVector {
        return changeID.versionVector
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

    /**
     * `setDisableGC` records whether this document participates in GC. The
     * client calls this on attach so subsequent applyChanges runs use the
     * lamport-only sync path.
     */
    internal fun setDisableGC(disableGC: Boolean) {
        this.disableGC = disableGC
    }

    /**
     * `setDisablePresence` records the server-fixated presence-free state. The
     * client calls this on attach so subsequent [updateAsync] calls drop
     * presence changes.
     */
    internal fun setDisablePresence(disablePresence: Boolean) {
        this.disablePresence = disablePresence
    }

    /**
     * `isPresenceDisabled` returns whether this document is presence-free.
     */
    internal fun isPresenceDisabled(): Boolean {
        return disablePresence
    }

    /**
     * `setMaxSizePerDocument` sets the maximum size of this document.
     */
    fun setMaxSizePerDocument(size: Int) {
        this.maxSizeLimit = size
    }

    /**
     * `getMaxSizePerDocument` gets the maximum size of this document.
     */
    fun getMaxSizePerDocument(): Int {
        return this.maxSizeLimit
    }

    /**
     * `getSchemaRules` gets the schema rules of this document.
     */
    fun getSchemaRules(): List<Rule> {
        return this.schemaRules
    }

    /**
     * `setSchemaRules` sets the schema rules of this document.
     */
    fun setSchemaRules(rules: List<Rule>) {
        this.schemaRules = rules
    }

    public suspend fun publishEvent(event: Event) {
        if (event is PresenceChanged) {
            pendingPresenceEvents.add(event)
        } else {
            eventStream.emit(event)
        }
    }

    /**
     * Releases this document's coroutine scope and dispatcher.
     *
     * For a document attached through a [dev.yorkie.core.Client.Options.docStore], close it
     * only after it has been detached (or after the client has been deactivated or closed):
     * a persist still queued for this document takes its snapshot on this dispatcher, so
     * closing the document first forfeits that snapshot (logged, never thrown).
     */
    override fun close() {
        scope.cancel()
        (dispatcher as? Closeable)?.close()
    }

    public sealed interface Event : ResourceEvent {

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

        /**
         * An event that represents whether the stream connection is connected or not.
         */
        public sealed interface StreamConnectionChanged : Event {

            public data object Connected : StreamConnectionChanged

            public data object Disconnected : StreamConnectionChanged
        }

        /**
         * An event that occurs when the document's status has been changed.
         * @see ResourceStatus
         */
        public data class DocumentStatusChanged(
            val docStatus: ResourceStatus,
            val actorID: String?,
        ) : Event

        /**
         * `Broadcast` means that the broadcast event is received from the remote client.
         */
        public data class Broadcast(
            val actorID: String?,
            val topic: String,
            val payload: String,
        ) : Event

        /**
         * `AuthError` means that an authentication error occurred.
         */
        public data class AuthError(
            val reason: String,
            val method: AuthErrorMethod,
        ) : Event {
            enum class AuthErrorMethod(val value: String) {
                PushPull("PushPull"),
                Watch("Watch"),
                Broadcast("Broadcast"),
            }
        }

        /**
         * `EpochMismatch` indicates the document was compacted on the server
         * and this client must detach and reattach to recover.
         */
        public data class EpochMismatch(
            val method: EpochMismatchMethod,
        ) : Event {
            enum class EpochMismatchMethod(val value: String) {
                PushPull("PushPull"),
            }
        }

        /**
         * Indicates that local changes were discarded without reaching the
         * server. Emitted only by the store-backed client (spec 025); this
         * type exists here so an app can react to the event regardless of
         * which layer raises it.
         */
        public data class LocalChangesDropped(
            val reason: Reason,
            val changes: List<DroppedChange>,
        ) : Event

        /**
         * The reason [LocalChangesDropped] was raised.
         */
        public enum class Reason(val value: String) {
            ActorMismatch("actor-mismatch"),
            RestoreFailed("restore-failed"),
            EpochReanchor("epoch-reanchor"),
            DocumentPurged("document-purged"),

            // LogDiscontinuity is raised when the persisted change log had a
            // clientSeq hole — an append that never landed — so it could not
            // be replayed: the server rejects a discontinuous run, and a
            // document restored from one would never sync again. Raised by
            // the store-backed attach path (`Client.kt`) when the log cannot
            // back the persisted header.
            LogDiscontinuity("log-discontinuity"),
        }

        /**
         * The app-readable projection of a dropped [Change]. [Change]'s own
         * fields are internal, so this projection is what an app handling
         * [LocalChangesDropped] can actually read.
         */
        public data class DroppedChange(
            val id: ChangeID,
            val message: String?,
            val operationCount: Int,
            val hasPresenceChange: Boolean,
        )

        /**
         * Represents the modification made during a document update and the message passed.
         */
        public data class ChangeInfo(
            public val message: String,
            public val operations: List<OperationInfo>,
            public val actorID: String,
            public val clientSeq: UInt,
            public val serverSeq: Long,
        )
    }

    public data class Options(
        /**
         * Disables garbage collection if true.
         */
        public val disableGC: Boolean = false,
        /**
         * Seeds the presence-free state before attach. The server-fixated
         * value from the attach response takes precedence once attached.
         */
        public val disablePresence: Boolean = false,
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

    public companion object {

        // Anchored start-to-end (spec 029 M6): the two formerly-separate, unanchored regexes
        // each matched anywhere in the blob, so a trailing/embedded garbage tail past a
        // syntactically valid prefix — e.g. "...,"clientSeq":12abc}" or leading/trailing noise
        // around an otherwise-canonical blob — was silently accepted (find() only needs ONE
        // match anywhere, never checked what surrounds it). Mirrors the writer's exact shape
        // (toCheckpointBytes below) and JS's JSON.stringify output byte-for-byte.
        private val CheckpointRegex = Regex(
            """^\s*\{\s*"serverSeq"\s*:\s*"(-?\d+)"\s*,\s*"clientSeq"\s*:\s*(\d+)\s*\}\s*$""",
        )

        // The epoch blob is `epoch.toString()`: an optional minus and ASCII digits, nothing else.
        private val EpochRegex = Regex("""-?\d+""")

        /**
         * Rebuilds a [Document] from a byte envelope produced by [toBytes].
         * The envelope carries at least four blobs (snapshot, checkpoint,
         * changeID, pending changes); a missing epoch blob decodes as `0`
         * and a missing docId blob decodes as `""`, so a legacy four- or
         * five-blob envelope still decodes. Blobs beyond the sixth are
         * ignored rather than rejected, so an app downgrade cannot discard
         * un-pushed edits carried in an envelope written by a newer SDK.
         *
         * Any decode failure — framing/checkpoint/epoch corruption, OR a protobuf-level failure
         * inside the snapshot, changeID, or pending-changes blobs (shared with the JS SDK's
         * `JSON.parse`/protobuf errors and iOS's decode errors) — surfaces as [YorkieException]
         * with [ErrInvalidArgument]; where a lower-level exception caused it, that exception is
         * attached as [Throwable.cause] (a blob that merely fails validation, such as a
         * non-numeric epoch, has no cause). The half-built [Document] is closed first so it
         * cannot leak its dispatcher (spec 029 I5/M1).
         */
        public suspend fun fromBytes(
            key: String,
            bytes: ByteArray,
            options: Options = Options(),
        ): Document {
            val blobs = unpackBlobs(bytes)
            checkYorkieError(
                blobs.size >= 4,
                YorkieException(
                    ErrInvalidArgument,
                    "corrupt envelope: expected at least 4 blobs, got ${blobs.size}",
                ),
            )

            val doc = Document(key, options)
            try {
                withContext(doc.dispatcher) {
                    // toSnapshot()/toChangeID() are internal ByteString-receiver
                    // converters; convert only at this boundary, per the envelope's
                    // public/internal ByteArray contract.
                    val (snapshotRoot, snapshotPresences) =
                        ByteString.copyFrom(blobs[0]).toSnapshot()
                    doc.root = CrdtRoot(snapshotRoot)
                    doc._presences.value = snapshotPresences.asPresences()

                    doc.checkPoint = blobs[1].toCheckPoint()

                    doc.changeID = ByteString.copyFrom(blobs[2]).toChangeID()

                    doc.localChanges.clear()
                    doc.localChanges.addAll(
                        PBChangePack.parseFrom(blobs[3]).changesList.toChanges(),
                    )

                    // A missing epoch blob (legacy four-blob envelope) decodes as 0.
                    doc.epoch = if (blobs.size > 4) {
                        // ASCII digits only, like JS `BigInt(text)`: Kotlin's toLong() would
                        // also accept non-ASCII decimal digits (Character.digit). Overflow
                        // (toLongOrNull == null) is corrupt as well.
                        EpochRegex.matchEntire(String(blobs[4], Charsets.UTF_8))
                            ?.value
                            ?.toLongOrNull()
                            ?: throw YorkieException(
                                ErrInvalidArgument,
                                "corrupt envelope: invalid epoch blob",
                            )
                    } else {
                        0
                    }
                    // A missing docId blob (legacy five-blob envelope) decodes as "".
                    doc.docId = if (blobs.size > 5) {
                        String(blobs[5], Charsets.UTF_8)
                    } else {
                        ""
                    }
                }
            } catch (e: CancellationException) {
                doc.close()
                throw e
            } catch (e: YorkieException) {
                doc.close()
                // Already the public contract for this function — pass it through unchanged
                // instead of re-wrapping (that would bury the real message behind a second
                // "corrupt envelope:" prefix and replace a meaningful cause with itself).
                if (e.code == ErrInvalidArgument) throw e
                throw YorkieException(
                    ErrInvalidArgument,
                    "corrupt envelope: ${e.message}",
                ).apply { initCause(e) }
            } catch (e: Throwable) {
                // Protobuf parser failures (InvalidProtocolBufferException) and anything else
                // land here; wrapped rather than left to propagate raw (spec 029 I5).
                doc.close()
                throw YorkieException(
                    ErrInvalidArgument,
                    "corrupt envelope: ${e.message}",
                ).apply { initCause(e) }
            }
            return doc
        }

        private fun ByteArray.toCheckPoint(): CheckPoint {
            val json = String(this, Charsets.UTF_8)
            // matchEntire, not find: Java's `$` also matches just before one final line
            // terminator (\n, \r, U+0085, U+2028, U+2029), so an anchored find() still
            // accepted a blob with a trailing terminator that JSON.parse rejects.
            val match = CheckpointRegex.matchEntire(json)
                ?: throw YorkieException(
                    ErrInvalidArgument,
                    "corrupt envelope: invalid checkpoint blob",
                )
            val serverSeqLong = match.groupValues[1].toLongOrNull()
                ?: throw YorkieException(
                    ErrInvalidArgument,
                    "corrupt envelope: invalid checkpoint blob",
                )
            val clientSeqUInt = match.groupValues[2].toUIntOrNull()
                ?: throw YorkieException(
                    ErrInvalidArgument,
                    "corrupt envelope: invalid checkpoint blob",
                )
            return CheckPoint(serverSeqLong, clientSeqUInt)
        }
    }
}

/**
 * Outcome of [Document.restoreFromBytes]. [ActorMismatch] carries the restored envelope's own
 * pending changes (not this document's) so the caller — [dev.yorkie.core.Client] today — can
 * surface them via a `LocalChangesDropped` event instead of losing them silently; a corrupt
 * envelope is not represented here, [Document.Companion.fromBytes] still throws for that case.
 */
internal sealed interface RestoreResult {
    object Restored : RestoreResult
    class ActorMismatch(val pending: List<Change>) : RestoreResult
}

/**
 * Projects this [Change] into a [Document.Event.DroppedChange] for an
 * app-visible [Document.Event.LocalChangesDropped] event.
 */
internal fun Change.toDroppedChange(): Document.Event.DroppedChange {
    return Document.Event.DroppedChange(
        id = id,
        message = message,
        operationCount = operations.size,
        hasPresenceChange = hasPresenceChange,
    )
}

/**
 * Encodes this [CheckPoint] into the byte-envelope checkpoint blob shape:
 * `{"serverSeq":"<serverSeq>","clientSeq":<clientSeq>}` — serverSeq quoted
 * as a decimal string, clientSeq an unquoted number (JS `getServerSeq()
 * .toString()` shape). The yorkie module has no JSON dependency, so this is
 * hand-written and parsed back with a small strict extractor (`Document
 * .Companion.toCheckPoint`) rather than pulling in a JSON library for one
 * literal.
 */
private fun CheckPoint.toCheckpointBytes(): ByteArray {
    return """{"serverSeq":"$serverSeq","clientSeq":$clientSeq}""".toByteArray(Charsets.UTF_8)
}

/**
 * Packs [blobs] into a single envelope: each blob is prefixed by its length
 * as a 4-byte little-endian uint32, concatenated with no header and no
 * count. JS `document.ts` `packBlobs` framing, byte-identical.
 */
private fun packBlobs(blobs: List<ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    blobs.forEach { blob ->
        val length = ByteBuffer.allocate(
            4,
        ).order(ByteOrder.LITTLE_ENDIAN).putInt(blob.size).array()
        output.write(length)
        output.write(blob)
    }
    return output.toByteArray()
}

/**
 * Unpacks an envelope produced by [packBlobs] back into its blobs. Walks the
 * length-prefixed framing and throws [YorkieException] with
 * [ErrInvalidArgument] on a truncated length prefix or a blob length that
 * exceeds the remaining bytes. JS `document.ts` `unpackBlobs`.
 */
private fun unpackBlobs(bytes: ByteArray): List<ByteArray> {
    val blobs = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < bytes.size) {
        checkYorkieError(
            offset + 4 <= bytes.size,
            YorkieException(ErrInvalidArgument, "corrupt envelope: truncated length prefix"),
        )
        // Read as unsigned uint32 (matches JS DataView#getUint32): a signed
        // Int read would let a high-bit-set prefix (e.g. 0xFFFFFFFF) pass the
        // bounds check via negative/overflowed arithmetic and blow up in
        // copyOfRange instead of surfacing the contracted YorkieException.
        val length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            .toLong() and 0xFFFF_FFFFL
        offset += 4
        checkYorkieError(
            offset.toLong() + length <= bytes.size,
            YorkieException(
                ErrInvalidArgument,
                "corrupt envelope: blob length exceeds remaining bytes",
            ),
        )
        blobs += bytes.copyOfRange(offset, offset + length.toInt())
        offset += length.toInt()
    }
    return blobs
}
