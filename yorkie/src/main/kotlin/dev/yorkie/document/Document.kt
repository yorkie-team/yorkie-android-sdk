package dev.yorkie.document

import androidx.annotation.VisibleForTesting
import com.google.protobuf.ByteString
import dev.yorkie.api.PBChangePack
import dev.yorkie.api.reissueOperations
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
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.countNodes
import dev.yorkie.document.crdt.dropSplitLinksInElement
import dev.yorkie.document.history.History
import dev.yorkie.document.history.HistoryOperation
import dev.yorkie.document.history.MaxUndoRedoStackDepth
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonElement
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.operation.TreeStyleOperation
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
import dev.yorkie.util.ChangeApplyError
import dev.yorkie.util.DocSize
import dev.yorkie.util.Logger.Companion.logDebug
import dev.yorkie.util.Logger.Companion.logError
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
import java.util.concurrent.atomic.AtomicInteger
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
    private val internalHistory = History(options.maxUndoDepth)

    /**
     * Fired synchronously, right after a local [Change] is appended to [localChanges] (both
     * [updateAsync] and undo/redo), by [dev.yorkie.core.Client] to register a store persist for
     * this document with zero suspension in between (spec 029 B2): the old design subscribed to
     * [events] instead, which suspends in [toBytes] before the write is even registered, leaving a
     * window where [close] can cancel the collector mid-snapshot and silently drop the last edit.
     * Null when no [dev.yorkie.core.Client.Options.docStore] is configured.
     */
    internal var onLocalChange: (() -> Unit)? = null

    /**
     * Counts in-flight [updateAsync]/undo-redo passes (D2, backlog 019,
     * spec 038). Must be a COUNT, not a boolean: two overlapping passes each
     * increment on entry and decrement in their own `finally`, so the flag
     * [isUpdating] derives from it only goes false once every in-flight pass
     * has finished, even when they finish out of order. A boolean flag that
     * the FIRST-to-finish pass cleared unconditionally let a re-issue run
     * while a second, still-suspended pass was in flight, reverting the
     * actor underneath it (cross-judge MEDIUM-1, round 3).
     */
    private val inFlight = AtomicInteger(0)
    private val isUpdating: Boolean
        get() = inFlight.get() > 0

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

    // Set sites: context §3.2.
    // - absorbedRemote: applySnapshot, applyChangesInternal (source ==
    //   Remote), fromBytes, restoreFrom, restoreAppendedChanges -- cleared
    //   only by resetForReanchor.
    // - mintedOperations: updateAsync/executeUndoRedoInternal when the
    //   queued change has operations, fromBytes, restoreFrom,
    //   restoreAppendedChanges (if any replayed change has operations) --
    //   NEVER cleared.
    // Both back [neverSynced] and [hasMintedOperations], which
    // `Client.attach`'s pre-attach ticket re-issue reads (spec
    // 038_sync-js-sdk-v0725-pre-attach-reissue).
    @Volatile
    private var absorbedRemote = false

    @Volatile
    private var mintedOperations = false

    /**
     * True once this document has queued a change carrying at least one
     * operation -- local, undo/redo, or replayed from a store. Read by
     * [dev.yorkie.core.Client] to decide whether this document's key takes
     * the per-client pre-attach re-issue claim on attach or on detach. Ports
     * yorkie-js-sdk `document.ts`'s `hasMintedOperations()` (`ed25355e`,
     * #1449, yorkie#2111).
     */
    public fun hasMintedOperations(): Boolean = mintedOperations

    /**
     * True when this document has never absorbed any remote/restored state
     * (snapshot, remote change, restore, or append-log replay), is
     * [ResourceStatus.Detached], sits at [CheckPoint.InitialCheckPoint], and
     * its version vector names no actor other than its own current one --
     * the exact four JS conditions a pre-attach ticket re-issue requires
     * (context §3.2). `internal` (not `public`): only [setActor]'s own
     * `reissue` overload and its tests need it.
     */
    internal val neverSynced: Boolean
        get() {
            if (absorbedRemote || status != ResourceStatus.Detached) return false
            if (checkPoint != CheckPoint.InitialCheckPoint) return false
            val actor = changeID.actor
            return changeID.versionVector.vectorMap.keys.none { it != actor }
        }

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
     * Gates new local writes while `Client.resyncAsync` discards this document's
     * un-pushed queue and re-anchors it under a fresh attach (JS SDK `document.ts`,
     * `61ca06c9`/#1463, D3). Set by [discardLocalChanges] (unconditionally, even for an
     * empty queue -- round-4 fix for CJ-MED-1: an empty queue still has an updater that
     * may be suspended PAST [ensureMintableAtEntry] and resume mid-resync), cleared by
     * [restoreLocalChanges] (a refused detach rolls the resync back) and
     * [resetForReanchor] (a successful resync's reanchor). `@Volatile`: written from this
     * document's own [dispatcher] by those three functions, read by [isMintable] /
     * [ensureMintableAtEntry] from whichever dispatcher an updater or undo/redo call
     * happens to run on.
     */
    @Volatile
    private var mintingSuspended = false

    /**
     * Bumped by every [discardLocalChanges], [restoreLocalChanges] and
     * [resetForReanchor] call. [mintingSuspended] alone only blocks an updater/undo/redo
     * pass that enters AFTER a discard already set it; it does not catch a pass already
     * past its entry check when a resync starts (it straddles the WHOLE discard ->
     * resetForReanchor window) and resumes once [resetForReanchor] has already cleared
     * the flag -- that pass would otherwise mint a change carrying the OLD attachment's
     * clientSeq into the freshly re-anchored one (CJ-MED-1, round 4 fix).
     * [ensureMintableAtEntry] captures this at entry; [ensureMintableBeforeAppend]
     * compares it again immediately before the append and refuses on any mismatch, not
     * just on [mintingSuspended]. `@Volatile` for the same reason as [mintingSuspended]
     * -- written only from [dispatcher], read from whichever dispatcher a suspended
     * pass resumes on.
     */
    @Volatile
    private var mintGeneration = 0

    /**
     * True unless a resync has suspended minting on this document (see
     * [mintingSuspended]) -- i.e. no resync is currently between its discard and its
     * re-anchor. [DocumentHistory.canUndo]/[DocumentHistory.canRedo] gate on this in
     * addition to their own stack checks, and [updateAsync]/undo-redo refuse with
     * [YorkieException.Code.ErrRefused] via [ensureMintableAtEntry] while it is false.
     */
    public fun isMintable(): Boolean = !mintingSuspended

    /**
     * Throws [YorkieException.Code.ErrRefused] while [isMintable] is false -- called at
     * [updateAsync]'s and undo/redo's ENTRY, so a pass suspended behind a resync never
     * starts running against a document about to be torn down -- and returns the
     * [mintGeneration] snapshot at that moment, to be re-checked by
     * [ensureMintableBeforeAppend] right before the pass appends its change. A new
     * `ErrRefused` window opens on EVERY [updateAsync]/`undoAsync`/`redoAsync` call once
     * any caller in the process has opted into `Client.resyncAsync` -- see that
     * function's own KDoc for exactly when the window opens and closes.
     */
    private fun ensureMintableAtEntry(): Int {
        if (mintingSuspended) {
            throw YorkieException(
                YorkieException.Code.ErrRefused,
                "document($key) refused to mint: a resync is discarding its local changes",
            )
        }
        return mintGeneration
    }

    /**
     * Re-checks immediately before appending to [localChanges], because the
     * updater/undo-redo pass is itself a suspend lambda: a pass already past
     * [ensureMintableAtEntry] can still resume AFTER [discardLocalChanges] or even
     * [resetForReanchor] has run while it was suspended. Refuses on [mintingSuspended]
     * OR on [mintGeneration] having moved since [generationAtEntry] was captured --
     * the generation check is what catches a pass that straddled a WHOLE resync
     * (CJ-MED-1), since [mintingSuspended] alone is already false again by then.
     */
    private fun ensureMintableBeforeAppend(generationAtEntry: Int) {
        if (mintingSuspended || mintGeneration != generationAtEntry) {
            throw YorkieException(
                YorkieException.Code.ErrRefused,
                "document($key) refused to mint: a resync is discarding its local changes",
            )
        }
    }

    /**
     * Suspends minting (see [isMintable]) and drains [localChanges] without touching
     * [checkPoint]'s clientSeq -- the discarded changes' clientSeq slots stay claimed, so
     * a later [resetForReanchor] + re-attach starts the server relationship over at
     * [CheckPoint.InitialCheckPoint] rather than resuming a sequence with a hole in it
     * (JS SDK `document.ts` `discardLocalChanges`, `61ca06c9`/#1463). Always suspends
     * minting and bumps [mintGeneration], even for an empty queue (round-4 fix for
     * CJ-MED-1 -- an empty queue can still have an updater suspended past its entry
     * check). `internal`: only `Client.resyncAsync` calls this.
     */
    internal suspend fun discardLocalChanges(): List<Change> = withContext(dispatcher) {
        val discarded = localChanges.toList()
        mintingSuspended = true
        mintGeneration++
        localChanges.clear()
        discarded
    }

    /**
     * Reverses [discardLocalChanges]: restores [changes] to the front of [localChanges],
     * bumps [mintGeneration] again and clears [mintingSuspended]. Called when a resync's
     * detach is itself refused (JS SDK `document.ts` `restoreLocalChanges`), so the
     * caller can keep using the document as though the resync never started -- any
     * updater/undo-redo pass still suspended from BEFORE the discard is refused rather
     * than minting against a base the restore may have already changed under it.
     */
    internal suspend fun restoreLocalChanges(changes: List<Change>): Unit =
        withContext(dispatcher) {
            localChanges.addAll(0, changes)
            mintingSuspended = false
            mintGeneration++
        }

    /**
     * Provides undo/redo operations for local user changes in this document.
     * The resulting document changes are synchronized normally.
     */
    public val history: DocumentHistory = object : DocumentHistory {
        override fun canUndo(): Boolean = internalHistory.hasUndo() && !isUpdating && isMintable()

        override fun canRedo(): Boolean = internalHistory.hasRedo() && !isUpdating && isMintable()

        override fun undoAsync(): Deferred<OperationResult> = executeUndoRedo(isUndo = true)

        override fun redoAsync(): Deferred<OperationResult> = executeUndoRedo(isUndo = false)
    }

    /**
     * Interface for undo/redo operations on a document.
     */
    public interface DocumentHistory {
        /**
         * Returns true if there are operations that can be undone. Also false while a
         * `Client.resyncAsync` call on this document is between its discard and its
         * re-anchor, even if the undo stack itself is non-empty.
         */
        fun canUndo(): Boolean

        /**
         * Returns true if there are operations that can be redone. Also false while a
         * `Client.resyncAsync` call on this document is between its discard and its
         * re-anchor, even if the redo stack itself is non-empty.
         */
        fun canRedo(): Boolean

        /**
         * Undoes the last local operation. Throws [YorkieException.Code.ErrRefused] while
         * a `Client.resyncAsync` call on this document is between its discard and its
         * re-anchor -- a new failure mode reachable once any caller opts into resync.
         */
        fun undoAsync(): Deferred<OperationResult>

        /**
         * Redoes the last undone operation. Throws [YorkieException.Code.ErrRefused] while
         * a `Client.resyncAsync` call on this document is between its discard and its
         * re-anchor -- a new failure mode reachable once any caller opts into resync.
         */
        fun redoAsync(): Deferred<OperationResult>
    }

    /**
     * Executes the given [updater] to update this document.
     *
     * Throws [YorkieException.Code.ErrRefused] while a `Client.resyncAsync` call on this
     * document is between its discard and its re-anchor -- a new failure mode reachable
     * once any caller opts into resync, including when [updater] itself was already
     * running before the resync started and only resumes afterward.
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
            // Refuses before the updater runs at all if a resync has suspended minting
            // (D3) -- see ensureMintableAtEntry's doc for why the ENTRY check alone is
            // not enough; the captured generation is re-checked at the append below.
            val mintGenerationAtEntry = ensureMintableAtEntry()

            val clone = ensureClone()
            val context = ChangeContext(
                prevId = changeID,
                root = clone.root,
                message = message,
            )
            val actorID = changeID.actor
            // D2 (backlog 019, spec 038_sync-js-sdk-v0725-pre-attach-reissue):
            // the updater is a suspend lambda running against `clone.root` --
            // any suspension inside it lets another coroutine on this
            // document's own dispatcher run, including a pre-attach ticket
            // re-issue's atomic swap. isUpdating gates that: `setActor`'s
            // reissue overload refuses while this is true, so a resumed
            // updater never finishes executing ops minted under the OLD
            // actor against a root already swapped to the NEW one.
            // inFlight is a COUNT, not a boolean: two overlapping updateAsync
            // calls each increment/decrement their own pass, so isUpdating
            // only goes false once EVERY in-flight pass has finished, even if
            // they finish out of order (cross-judge MEDIUM-1, round 3).
            inFlight.incrementAndGet()
            val result = try {
                runCatching {
                    val proxy = JsonObject(context, clone.root.rootObject)
                    updater.invoke(
                        proxy,
                        DocPresence(context, clone.presences[changeID.actor].orEmpty()),
                    )
                }.onFailure {
                    this@Document.clone = null
                    ensureActive()
                }
            } finally {
                inFlight.decrementAndGet()
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
            // The updater is a suspend lambda (above): a pass already past the entry
            // check can still resume here AFTER discardLocalChanges() ran while it was
            // suspended -- or even after a WHOLE resync finished (resetForReanchor
            // already cleared mintingSuspended by then, CJ-MED-1), which is what the
            // generation half of this check catches. Checked here, BEFORE the root
            // pass, so a late refusal never mutates the root at all: previously this
            // ran only after change.execute() below, leaving a refused change's
            // operations already applied to the root with no way to undo them, and the
            // lamport unburned. Burn the lamport and this actor's version-vector entry
            // the same way the root-pass catch below does, clientSeq unchanged -- this
            // change is never appended to localChanges on this path.
            try {
                ensureMintableBeforeAppend(mintGenerationAtEntry)
            } catch (e: YorkieException) {
                this@Document.clone = null
                if (!context.isPresenceOnlyChange()) {
                    changeID = context.getNextId().copy(clientSeq = changeID.clientSeq)
                }
                throw e
            }
            val change = context.toChange()
            val localResult = try {
                change.execute(
                    root,
                    _presences.value,
                    if (skipHistory) OpSource.LocalNoHistory else OpSource.Local,
                )
            } catch (err: Throwable) {
                // Change.execute applies operations one at a time and does not roll
                // back, so a root-pass throw here leaves the clone ahead of the root
                // (the clone's own copy of a prefix of this change already ran via
                // the updater proxy above). Drop it so the next access rebuilds it
                // from the root -- the local source passes err through unchanged
                // (Change.execute never wraps a non-Remote source). Port JS SDK
                // `document.ts` `update()` (`4251ab7e`, #1403).
                this@Document.clone = null
                // The root pass above still consumed this context's lamport-advanced
                // TimeTickets for whatever prefix of operations landed on the root
                // before it threw (Change.execute does not roll back). Leaving
                // changeID at its pre-change value would let the NEXT change's
                // context re-mint the exact same tickets (same lamport, same
                // per-context delimiter sequence) -- reproduced: a failed 2-op
                // change's landed first element and the next change's element end
                // up sharing one createdAt, and the first becomes unreachable.
                // Burn the lamport and this actor's version-vector entry via
                // context.getNextId() (prevId.next() for an operation change,
                // which also sets this actor's entry in a copy of the version
                // vector), but pin clientSeq back to its pre-change value: this
                // change was never appended to localChanges (only the success path
                // below does that) and never claims a clientSeq slot, so advancing
                // it here would open a gap the next real change's clientSeq must
                // not have to skip over. Skipped for a presence-only context (no
                // operations landed on the root, nothing to burn tickets against).
                // Never reached for a Remote source (applyChangesInternal has its
                // own, unrelated catch) -- a replica never burns another actor's
                // tickets. Port JS SDK `document.ts` `update()`'s catch
                // (`e29d17e0`, #1397; iOS `915dd77fe6`): JS's `this.changeID =
                // ctx.getNextID().setClientSeq(this.changeID.getClientSeq())`.
                if (!context.isPresenceOnlyChange()) {
                    changeID = context.getNextId().copy(clientSeq = changeID.clientSeq)
                }
                throw err
            }
            val operationInfos = localResult.opInfos
            val newPresences = localResult.newPresences
            val reverseOps = localResult.reverseOps

            localChanges += change
            if (change.hasOperations) {
                mintedOperations = true
            }
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
                // A plain local split can re-create an element a merge took
                // away too (a peer merged two blocks and this user splits
                // them apart again), and the stacks may still name the
                // merged-away element. Re-point them the same way
                // reconcileHistoryEdits does for a peer's split and
                // executeUndoRedoInternal does for an undo/redo one, before
                // this change's own reverse ops are pushed below. Port
                // yorkie-js-sdk `b173c093` (#1426).
                for (op in change.operations) {
                    if (op is TreeEditOperation) {
                        op.getSplitRecreatedIDs().forEach { (prevID, currID) ->
                            internalHistory.reconcileTreeNodeID(op.parentCreatedAt, prevID, currID)
                        }
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

            // NOTE: The refusal above must not drop the clone: an updater is
            // holding a proxy over it, and `updateAsync` reads it again
            // after the updater returns.
            // D2: the same isUpdating gate updateAsync sets around its
            // updater now also covers undo/redo's own execute pass, so a
            // pre-attach re-issue's atomic swap refuses to interleave here
            // too, and the existing refusal guard above (line 552) becomes
            // live against a CONCURRENT updateAsync (this flag was never
            // set before, so that guard never actually fired -- backlog
            // 019). Shares the same inFlight COUNT as updateAsync.
            // Refuses before executeUndoRedoInternal runs at all if a resync has
            // suspended minting (D3); the outer catch below clears the clone on ANY
            // throw from executeUndoRedoInternal, including ensureMintableBeforeAppend's
            // second check right before that function's own append (no clone was
            // created at THIS entry check yet, so nothing to drop here). The captured
            // generation is threaded through so that second check also catches a pass
            // that straddled a WHOLE resync (CJ-MED-1), not just an in-progress discard.
            val mintGenerationAtEntry = ensureMintableAtEntry()
            inFlight.incrementAndGet()
            try {
                executeUndoRedoInternal(isUndo, mintGenerationAtEntry)
            } catch (err: Throwable) {
                // Change.execute applies operations one at a time and does
                // not roll back, so an undo/redo that throws partway leaves
                // the clone ahead of the root. Drop it so the next access
                // rebuilds it from the root, the way updateAsync already
                // does on an updater throw. A throw here surfaces through
                // this Deferred -- including CancellationException, which
                // must still reset the clone before it propagates, never be
                // swallowed. Port JS SDK `document.ts`
                // `executeUndoRedo`/`executeUndoRedoInternal` (ec7558e9,
                // #1394).
                this@Document.clone = null
                throw err
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /**
     * Pops the next history entry for [isUndo] and applies it to the clone
     * and the root.
     */
    private suspend fun executeUndoRedoInternal(
        isUndo: Boolean,
        mintGenerationAtEntry: Int,
    ): OperationResult {
        val ops = if (isUndo) {
            internalHistory.popUndo()
        } else {
            internalHistory.popRedo()
        }
        if (ops == null) {
            // Empty stack is a no-op so callers need not always guard with
            // canUndo()/canRedo(). Mirrors JS SDK PR #1238.
            return Result.success(Unit)
        }

        val clone = ensureClone()
        val context = ChangeContext(
            prevId = changeID,
            root = clone.root,
        )

        // Tree node ids this entry re-mints, and what they replace, together
        // with which of the split's own tickets `curr` is -- so the drain
        // below can tell whether the ROOT execution got that far (the hook
        // that records a pair fires on the clone pass first, and the two
        // passes need not mint the same number of elements). Collected while
        // the change is built below and applied to the history stacks only
        // once both passes have run. A copy-reinsert reverse's pairs carry
        // ticketIndex -1: unlike a split, buildFreshNodes never mints a
        // partial result, so those pairs are unconditional -- see the drain
        // below and TreeEditOperation.getReissuedContentIDs. Port
        // yorkie-js-sdk `b173c093` (#1426).
        val pending = mutableListOf<PendingTreeRepoint>()
        // The splitting/content-reissuing operations whose re-pointing hook
        // is still registered, so it can be taken off once the change has
        // been applied to both the clone and the root -- the hook closes
        // over this entry, which must not outlive it.
        val splitOps = mutableListOf<TreeEditOperation>()
        val reissueOps = mutableListOf<TreeEditOperation>()

        /**
         * Re-points every [TreeEditOperation]/[TreeStyleOperation] later in
         * this same entry (by loop index -- never by [TreeEditOperation]
         * equality, which is structural) from [prev] to [curr].
         */
        fun repointRest(
            fromIndex: Int,
            prev: CrdtTreeNodeID,
            curr: CrdtTreeNodeID,
        ) {
            for (later in ops.subList(fromIndex + 1, ops.size)) {
                if (later !is HistoryOperation.Op) continue
                when (val laterOp = later.operation) {
                    is TreeEditOperation -> laterOp.reconcileNodeID(prev, curr)
                    is TreeStyleOperation -> laterOp.reconcileNodeID(prev, curr)
                    else -> Unit
                }
            }
        }

        for ((index, historyOp) in ops.withIndex()) {
            when (historyOp) {
                is HistoryOperation.Op -> {
                    val op = historyOp.operation
                    val ticket = context.issueTimeTicket()
                    op.executedAt = ticket

                    // Strip split-sibling links from a reverse op's restored
                    // value in place: this element is freshly reinstated by
                    // the editing client -- never a split product of the live
                    // tree it lands in -- but `execute()`'s deep copy of it
                    // preserves whatever insPrevID/insNextID chain it already
                    // carries. Must run before `context.push(op)`/`execute()`
                    // below, and mirrors the identical strip the decode path
                    // runs (ElementConverter.kt) so a local reverse value and
                    // a peer-decoded one agree. Port JS SDK document.ts
                    // (c8928853, #1375).
                    when (op) {
                        is SetOperation -> dropSplitLinksInElement(op.value)
                        is AddOperation -> dropSplitLinksInElement(op.value)
                        is ArraySetOperation -> dropSplitLinksInElement(op.value)
                        else -> Unit
                    }

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

                    // A split reverse -- the undo of a merge-delete, or the
                    // redo of a split -- mints one element per split level,
                    // and each needs a ticket of its own: issueTimeTicket's
                    // fallback (used when splitTickets is empty) reconstructs
                    // them by counting delimiters up from executedAt, which
                    // runs straight over the ticket the NEXT operation in
                    // this same undo/redo entry is issued above -- landing
                    // two LIVE elements under one id, on every replica and
                    // the server, since the change carries both operations.
                    // Recording them here -- after the removedNodeSnapshots
                    // reservation above, so issue order matches JS -- is what
                    // stops any other replica reconstructing them either.
                    // Independent of (never both at once with, see
                    // toReverseOperation) the removedNodeSnapshots branch
                    // above: every copy-reinsert/restore-mode reverse this op
                    // could be already carries splitLevel == 0.
                    //
                    // One per level is an upper bound, not an exact count:
                    // CrdtTree.edit's walk can stop early at the root, and
                    // issueTimeTicket only ever consumes as many as it needs
                    // -- a ticket nobody consumes just advances the
                    // delimiter. Port JS SDK document.ts (b69169c0, #1404).
                    if (op is TreeEditOperation && op.splitLevel > 0) {
                        op.splitTickets = List(op.splitLevel) { context.issueTimeTicket() }

                        // The split re-creates elements a merge removed,
                        // under the new tickets. Operations recorded against
                        // the old elements -- the rest of this entry (the
                        // style that followed a split, say) and any other
                        // entry in the stacks -- still name them. Once
                        // garbage collection has purged the old elements, a
                        // replica applying such an operation cannot find
                        // them and rejects the whole change. Re-point them,
                        // so the change is encoded with the ids every
                        // replica will have.
                        //
                        // Driven from the split itself, as it takes each
                        // ticket: only then is it known that the element
                        // exists -- the split stops as soon as it runs out
                        // of ancestors, so a level it never reached mints
                        // nothing, and re-pointing that level up front, on
                        // the strength of splitLevel alone, would leave an
                        // operation naming a ticket no node ever received.
                        // The rest of THIS entry cannot wait for the split
                        // to return either: those operations run in the same
                        // change, right after it, so the hook fires
                        // mid-execution, while they are still pending. The
                        // history stacks could wait, but are collected here
                        // and applied below (after both passes) for a
                        // different reason -- the new reverse ops are built
                        // from the executed state and must not be pushed
                        // before the stacks are re-pointed. Port
                        // yorkie-js-sdk `b173c093` (#1426).
                        op.onSplitTicketConsumed { ticketIndex ->
                            // Read through the operation rather than off a
                            // captured snapshot: an earlier re-point in this
                            // same entry may already have replaced
                            // replacedIDs wholesale (reconcileNodeID).
                            val prev = op.getReplacedIDs().getOrNull(ticketIndex)
                                ?: return@onSplitTicketConsumed
                            val curr = CrdtTreeNodeID(op.splitTickets[ticketIndex], 0)
                            // Called once per execution, and the change is
                            // executed twice (clone, then root). repointRest
                            // no longer finds prev the second time, but the
                            // pending entry would be a duplicate.
                            if (pending.any { it.op === op && it.prev == prev }) {
                                return@onSplitTicketConsumed
                            }
                            repointRest(index, prev, curr)
                            pending += PendingTreeRepoint(op, prev, curr, ticketIndex)
                        }
                        splitOps += op
                    }

                    // A copy-reinsert reverse -- the undo of a plain content
                    // delete -- mints fresh ids for the content it restores
                    // (buildFreshNodes), one per removed node; the undo/redo
                    // entry can still carry a LATER operation recorded
                    // against one of the removed originals (a style applied
                    // to a node the same update then deleted). Register the
                    // hook so its pairs re-point the rest of this entry
                    // before they run -- same timing contract as the split
                    // hook above, and for the same reason: the ids are only
                    // known from inside this op's own execute(). Port
                    // yorkie-js-sdk `b173c093` (#1426).
                    if (op is TreeEditOperation && op.removedNodeSnapshots != null) {
                        op.onContentIDsReissued { pairs ->
                            for ((prev, curr) in pairs) {
                                // Same duplicate guard as the split hook:
                                // buildFreshNodes mints the SAME ids on both
                                // the clone and the root pass (a pure
                                // function of removedNodeSnapshots and
                                // executedAt, both fixed before either pass
                                // runs), so the second call would otherwise
                                // double-push.
                                if (pending.any { it.op === op && it.prev == prev }) {
                                    continue
                                }
                                repointRest(index, prev, curr)
                                pending += PendingTreeRepoint(op, prev, curr, ticketIndex = -1)
                            }
                        }
                        reissueOps += op
                    }

                    context.push(op)
                }

                is HistoryOperation.Presence -> {
                    // Presence undo — not implemented in v0.6.36 scope
                }
            }
        }

        if (!context.hasChange) {
            return Result.success(Unit)
        }

        val change = context.toChange().apply { isUndoRedo = true }
        // Same late-refusal check as updateAsync's: this pass is itself a suspend
        // function and can resume after discardLocalChanges() ran while it was
        // suspended, or after a WHOLE resync finished (CJ-MED-1, caught by the
        // generation half). Checked here, BEFORE the clone pass, so a refused
        // undo/redo touches neither the clone nor the root tree and pushes nothing
        // onto the history stacks below -- previously this ran only after both
        // passes executed and the reverse ops were already pushed, so a refusal
        // left the trees mutated and the opposite stack carrying an entry for a
        // change that was never minted. Burn the lamport the same way the root-pass
        // catch below does, and detach the split/reissue handlers registered above
        // so a refused entry's hooks do not outlive it.
        try {
            ensureMintableBeforeAppend(mintGenerationAtEntry)
        } catch (e: YorkieException) {
            splitOps.forEach { it.onSplitTicketConsumed(null) }
            reissueOps.forEach { it.onContentIDsReissued(null) }
            if (!context.isPresenceOnlyChange()) {
                changeID = context.getNextId().copy(clientSeq = changeID.clientSeq)
            }
            throw e
        }
        val undoRedoResult = try {
            // Execute on clone (validation). Never touches the root, so a
            // throw here needs no lamport burn -- it propagates to
            // executeUndoRedo's outer catch, which resets the clone the same
            // as every other path. Moved inside this try (rather than run
            // separately above it) so the `finally` below detaches every
            // split-ticket handler whichever of the two passes throws, or
            // neither does -- a handler must never outlive this entry. Port
            // yorkie-js-sdk `b173c093` (#1426).
            change.execute(clone.root, clone.presences, OpSource.UndoRedo)
            // Execute on root (real application). Mirrors updateAsync's own
            // root-pass catch above: a throw partway through still consumed
            // this context's lamport-advanced tickets for whatever prefix
            // landed, so burn the lamport and this actor's version-vector
            // entry here too, clientSeq unchanged (this undo/redo change is
            // never appended to localChanges on this path -- that happens
            // only below, on success). Port JS SDK `document.ts`
            // `executeUndoRedo`'s root pass (`e29d17e0`, #1397; iOS
            // `915dd77fe6`).
            try {
                change.execute(root, _presences.value, OpSource.UndoRedo)
            } catch (err: Throwable) {
                if (!context.isPresenceOnlyChange()) {
                    changeID = context.getNextId().copy(clientSeq = changeID.clientSeq)
                }
                throw err
            }
        } finally {
            // Detach the handlers whether or not either pass succeeded, so a
            // later execution of these operations cannot re-point anything
            // and the closures do not keep this popped entry alive. Port
            // yorkie-js-sdk `b173c093` (#1426).
            splitOps.forEach { it.onSplitTicketConsumed(null) }
            reissueOps.forEach { it.onContentIDsReissued(null) }
        }
        val opInfos = undoRedoResult.opInfos
        val reverseOps = undoRedoResult.reverseOps

        // Now that the operations have run, re-point the history stacks at
        // the ids this entry actually minted. Deferred to here because the
        // new reverse ops built below are already built from the executed
        // state and so already carry the new ids -- they must not be pushed
        // before this runs.
        //
        // Two things can still make a collected pair moot, and both are only
        // knowable now. `change.execute` skips an operation whose target
        // element was removed during undo/redo, so it minted nothing at all
        // (excluded by the executedOperations check). And a pair is
        // collected as the split takes its ticket on WHICHEVER execution
        // runs first -- the clone -- while the stacks have to follow the
        // root: the clone and the root are separate trees, so a split that
        // crossed a level in the clone can stop short of it in the root,
        // leaving a pair naming a ticket no node in the root ever received
        // (checked against getConsumedSplitTicketCount, which describes the
        // ROOT pass since it is reset per execute() and this reads it after
        // the root pass above). `op` is compared by reference (===), never
        // by the data class's structural equals. A content-reissue pair
        // (`ticketIndex == -1`) skips that check entirely and applies
        // whenever its op executed: buildFreshNodes mints unconditionally,
        // with no early-exit for the clone/root passes to disagree over.
        // Port yorkie-js-sdk `b173c093` (#1426).
        for ((op, prev, curr, ticketIndex) in pending) {
            if (undoRedoResult.executedOperations.none { it === op }) continue
            if (ticketIndex >= 0 && ticketIndex >= op.getConsumedSplitTicketCount()) continue
            internalHistory.reconcileTreeNodeID(op.parentCreatedAt, prev, curr)
        }

        // And then what the TREE says the split re-created, the same signal
        // the local `update()` and remote `reconcileHistoryEdits` paths
        // re-point from. The pairs above come from the reverse op, which
        // names the merge IT reverses; the split as executed can reverse one
        // this entry never knew about -- a peer merged two blocks while the
        // undo sat on the stack, and the redo of an unrelated split
        // separates them again. Neither signal subsumes the other: the tree
        // derives a pair only where the merge left mergedFrom stamps behind
        // (CrdtTree.mergeSourceOf), which a split's own boundary-deletion
        // undo does not. Both are applied, in that order; a pair the other
        // already handled sweeps nothing and costs nothing. Port
        // yorkie-js-sdk `b173c093` (#1426).
        undoRedoResult.executedOperations.forEach { executedOp ->
            if (executedOp is TreeEditOperation) {
                executedOp.getSplitRecreatedIDs().forEach { (prev, curr) ->
                    internalHistory.reconcileTreeNodeID(executedOp.parentCreatedAt, prev, curr)
                }
            }
        }

        val reverseHistoryOps = reverseOps.map { HistoryOperation.Op(it) }
        if (reverseHistoryOps.isNotEmpty()) {
            if (isUndo) {
                internalHistory.pushRedo(reverseHistoryOps)
            } else {
                internalHistory.pushUndo(reverseHistoryOps)
            }
        }

        // The test is whether an operation RAN, not whether it produced an
        // OpInfo. Those differ: a style may change CRDT state without
        // anything an editor could render, because canStyle admits a node
        // another client removed concurrently and a tombstone has no
        // index to report. Gating on opInfos dropped such a reverse style
        // on the floor -- it mutated this replica and never reached the
        // others, the divergence canStyle exists to prevent, reintroduced
        // through undo. executedOperations already excludes an operation
        // whose target was garbage-collected while the undo was pending
        // (its own execute found no target to act on), so that case is
        // still gated out here. A target merely tombstoned by a peer is
        // not: JS skips Set/Remove there under OpSource.UndoRedo, Android
        // still executes, sends the change and emits LocalChange
        // (replicas converge either way). The gate matches JS
        // document.ts's `!operations.length`; a no-op restore or
        // retombstone counts as not executed here, where JS always
        // reports a result for it.
        if (!change.hasPresenceChange && undoRedoResult.executedOperations.isEmpty()) {
            return Result.success(Unit)
        }

        localChanges += change
        if (change.hasOperations) {
            mintedOperations = true
        }
        onLocalChange?.invoke()
        changeID = context.getNextId()

        // Gate the publish on the same executedOperations the early return
        // above already checked, not on opInfos: an identity-preserving
        // restore/retombstone that revived or re-removed a node under a
        // removed ancestor executes (changed the CRDT, is queued and sent)
        // but visibleRangeOf cannot measure a position for it, so its
        // TreeEditOpInfo list is empty (CrdtTree.kt's restore/retombstone,
        // TreeEditOperation.executeRestore). The old opInfos-based gate
        // suppressed this event entirely, even though the change still
        // reached the peer -- an editor watching only Document.events would
        // never learn the undo ran. Port yorkie-js-sdk `790422ce` (#1421).
        if (undoRedoResult.executedOperations.isNotEmpty()) {
            eventStream.emit(Event.LocalChange(change.toChangeInfo(opInfos)))
        }

        return Result.success(Unit)
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
     *
     * The checkpoint only advances past this point, so a pack whose apply throws is
     * redelivered by the server on every subsequent pull until the cause is fixed; one
     * metadata-only line (document key + the pre-forward [checkPoint] + the failing
     * exception's class name ONLY, never its message -- a cause's own message can
     * carry content, e.g. a collection's "requested value: ..." text; the full message
     * -- including the cause's -- is still reachable via a thrown [ChangeApplyError]'s
     * own `errorMessage`/[Throwable.message], see its KDoc) is logged before
     * rethrowing, so the stuck document is diagnosable instead of silently retried
     * forever. [err] reaching this catch for a Remote source is always a
     * [ChangeApplyError] by construction ([applyChanges]'s own catch already wrapped
     * it); the class name logged is [err]'s own [Throwable.cause] (the original,
     * innermost failure) when present, so the line names the root failure type rather
     * than the uninformative "ChangeApplyError" every entry would otherwise share. When
     * [err] is a [ChangeApplyError] its own [ChangeApplyError.changeID],
     * [ChangeApplyError.opIndex] and [ChangeApplyError.operation] are ALSO logged --
     * these are payload-safe by the same contract as [ChangeApplyError.operation]
     * itself (type + ticket only, never the cause's message) -- so a redelivered pack
     * is diagnosable down to which change/operation is stuck, not just the failure's
     * class name. The
     * no-cause-message guarantee is unchanged. A [CancellationException] skips the log
     * line and propagates unchanged: it is the caller's scope tearing down, not the
     * document getting stuck. Ports JS SDK `document.ts` `applyChangePack`
     * (`4251ab7e`, #1403).
     */
    internal suspend fun applyChangePack(pack: ChangePack): Unit = withContext(dispatcher) {
        try {
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
        } catch (err: CancellationException) {
            throw err
        } catch (err: Throwable) {
            // When `err` is a ChangeApplyError, its own changeID/opIndex/operation are
            // payload-safe (never the cause's message) and name which change/operation
            // is stuck, so they are logged alongside the root failure's class name.
            val changeApplyDetails = (err as? ChangeApplyError)?.let {
                "; changeID=${it.changeID}, opIndex=${it.opIndex}, operation=${it.operation}"
            }.orEmpty()
            logError(
                "Document.applyChangePack",
                "\"$key\" cannot apply the pack at checkpoint ${checkPoint.toTestString()}; " +
                    "the server will redeliver it until this is resolved: " +
                    (err.cause ?: err)::class.simpleName +
                    changeApplyDetails,
            )
            throw err
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
     * Removes the local changes the server has confirmed, and forwards only
     * the client seq of the checkpoint. Used for a response [pack] dropped
     * without applying its remote state: the server seq must stay put so the
     * skipped state is pulled again once realtime sync resumes, while the
     * confirmed changes must not be pushed again.
     *
     * The pack's metadata is not remote state, and is taken in full: the
     * compaction epoch and the removal flag describe the document itself,
     * not the content being skipped, and neither is re-sent by a later pull
     * the way the skipped changes are. Ports JS SDK `document.ts`
     * `acknowledgePushedChanges` (`bed81143`, #1401).
     */
    internal suspend fun acknowledgePushedChanges(pack: ChangePack): Unit =
        withContext(dispatcher) {
            removePushedLocalChanges(pack.checkPoint.clientSeq)
            checkPoint = checkPoint.forward(
                CheckPoint(checkPoint.serverSeq, pack.checkPoint.clientSeq),
            )
            epoch = pack.epoch

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
        // A snapshot absorbs remote state -- the document is no longer
        // "never synced" from here on, even if a later resetForReanchor
        // clears its checkpoint back to InitialCheckPoint (context §3.2).
        absorbedRemote = true
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
     *
     * [Change.execute] applies its operations one at a time and does not
     * roll back, so a change that throws partway leaves [clone] holding a
     * prefix of it the root never took (the clone's own copy of this
     * operation already ran before the root's copy threw). Drop the clone
     * so the next access rebuilds it from the root, the way [updateAsync]
     * already does on an updater throw. Port JS SDK `document.ts`
     * `applyChange`/`applyChangeInternal` (ec7558e9, #1394).
     *
     * A [source] of [OpSource.Remote] that throws is always a [ChangeApplyError] by
     * construction ([Change.execute] names an operation failure; [applyChangesInternal]
     * names a change-level failure that is not already one) -- this adds the document
     * key, the one piece neither of those call sites can know. The `?:` fallback only
     * covers a pre-loop [ensureClone] failure, which is outside #1403's scope. Every
     * other [source] rethrows the original error unchanged (JS SDK `document.ts`
     * `applyChangePack`/`applyChange`, `4251ab7e`, #1403). Two exceptions are never
     * named/wrapped even for a Remote source, and only reset the clone before
     * propagating: a [CancellationException] -- [applyChangesInternal] suspends
     * (`ensureClone`'s `withContext`, the event publish, `emitPresences`), so this can
     * be the caller's scope tearing down, not a change failing to apply -- and a
     * [java.lang.Error] (e.g. `OutOfMemoryError`) thrown by that SAME Document-owned
     * bookkeeping (not by an operation's own `execute`).
     *
     * **Precise scope of the Error/CancellationException guards:** these two guards only ever see
     * an `Error`/`CancellationException` that originates in this function's or
     * [applyChangesInternal]'s OWN code around the two `change.execute` calls --
     * `ensureClone`, presence/event emission, [reconcileHistoryEdits]. An
     * `Error`/`CancellationException` thrown by an operation's own `execute` is
     * already wrapped into a [ChangeApplyError] by [Change.execute] (with the
     * original reachable via its `cause`) BEFORE either guard runs, so it never
     * reaches them unwrapped -- an application catching `Error`-level conditions by
     * their real type sees that real type only for a failure in Document's own
     * bookkeeping, not for one an operation itself threw. See [Change.execute]'s KDoc.
     */
    private suspend fun applyChanges(changes: List<Change>, source: OpSource = OpSource.Remote) {
        try {
            applyChangesInternal(changes, source)
        } catch (err: CancellationException) {
            this.clone = null
            throw err
        } catch (err: Throwable) {
            this.clone = null
            if (source != OpSource.Remote || err is Error) {
                throw err
            }
            throw (err as? ChangeApplyError)?.withDocKey(key) ?: ChangeApplyError(
                docKey = key,
                changeID = "<unavailable>",
                cause = err,
            )
        }
    }

    private suspend fun applyChangesInternal(changes: List<Change>, source: OpSource) {
        if (source == OpSource.Remote) {
            // A remote change absorbs server state -- never-synced tracking
            // (context §3.2) stops here regardless of checkpoint, which a
            // later resetForReanchor may still roll back.
            absorbedRemote = true
        }
        val clone = ensureClone()
        changes.forEach { change ->
            try {
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

                // Gate on executedOperations, not opInfos: a peer's
                // identity-preserving restore/retombstone that revived or
                // re-removed a node under a removed ancestor still executed
                // (reconcileHistoryEdits above already ran for it), but its
                // TreeEditOpInfo list is empty when visibleRangeOf cannot
                // measure a position -- the apply must still publish once,
                // not be silently dropped. Port yorkie-js-sdk `790422ce`
                // (#1421).
                if (remoteResult.executedOperations.isNotEmpty()) {
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
            } catch (e: CancellationException) {
                // eventStream.emit/emitPresences above suspend; a cancellation here is
                // the caller's scope tearing down, not a change failing to apply --
                // never name/wrap it.
                throw e
            } catch (e: Throwable) {
                // Names the change alone when no single operation's own throw already
                // named it (Change.execute only names an operation-level failure);
                // Local/LocalNoHistory/UndoRedo pass their error through unchanged --
                // only a Remote change blocks the whole replica set's progress and
                // needs naming (JS SDK `document.ts`, `4251ab7e`, #1403). A
                // java.lang.Error (e.g. OutOfMemoryError) from THIS function's own
                // bookkeeping around the two change.execute calls above (presence/event
                // handling, reconcileHistoryEdits) is never wrapped: an application
                // catching Error-level conditions specially must still see the real
                // type there. This does NOT cover an Error thrown by an operation's own
                // execute inside change.execute -- that is already a ChangeApplyError
                // by the time it reaches this catch (Change.execute wraps it first), so
                // it fails the `e !is ChangeApplyError` check below and is rethrown
                // unchanged regardless (see Change.execute's KDoc).
                if (source == OpSource.Remote && e !is ChangeApplyError && e !is Error) {
                    throw ChangeApplyError(changeID = change.id.toTestString(), cause = e)
                }
                throw e
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
                    // A split re-creates the elements a merge took away,
                    // under brand-new ids -- see CrdtTree.mergeSourceOf. That
                    // happens whichever replica sent the split: the local
                    // `update`/`executeUndoRedoInternal` paths re-point this
                    // replica's own stacks when the split is its own, and
                    // this one re-points for a peer's split AND a local
                    // skipHistory write (this function serves both). Left
                    // un-re-pointed, an entry naming the element a peer
                    // replaced addresses a node garbage collection will
                    // purge, and the change it eventually pushes is rejected
                    // on every replica. Done before the index reconciliation
                    // below, for the same reason the undo/redo path defers
                    // its own re-point to after execution: the pairs are
                    // only known once the split has run. Port yorkie-js-sdk
                    // `b173c093` (#1426).
                    executedOp.getSplitRecreatedIDs().forEach { (prev, curr) ->
                        internalHistory.reconcileTreeNodeID(executedOp.parentCreatedAt, prev, curr)
                    }

                    if (executedOp.isRestoreOp) {
                        // Identity-preserving restore/retombstone can revive
                        // or re-remove several nodes in one operation, each
                        // at its OWN position; replay every measured range in
                        // the order executeRestore published it so pending
                        // tree-undo offsets shift once per node instead of
                        // once for the whole operation at a delta that only
                        // ever reflected the first node (port yorkie-js-sdk
                        // `790422ce`, #1421; fixes the previously pinned upstream
                        // defect — the single always-[0,0] placeholder this used
                        // to read from shifted nothing for a remote restore).
                        executedOp.getExecutedRanges().forEach { (from, to, insertedSize) ->
                            internalHistory.reconcileTreeEdit(
                                executedOp.parentCreatedAt,
                                from,
                                to,
                                insertedSize,
                            )
                        }
                    } else {
                        // A zero-effect edit (e.g. a dropped/out-of-range op) emits no
                        // TreeEditOpInfo at all -- gate on that the same as before, but
                        // reconcile from normalizePos()'s pre-edit index + removed size
                        // (a CRDT value captured before the edit mutated the tree), NOT
                        // from the opInfo's own from/to: those report where a remote
                        // split LANDED, which a concurrent boundary migration can move
                        // away from the index this edit actually consumed (port
                        // yorkie-js-sdk `a74b2513`/`4d714068`, #1455/#1478, M1 fix).
                        // getContentSize() (insertedContentSize + splitSize) replaces
                        // the prior node-COUNT measurement (opInfo.nodes?.size): a
                        // split-only edit has no `value`, so the node count was always
                        // 0 and never shifted stacked tree-undo offsets, even though
                        // the split visibly grows the index. A multi-character text
                        // insert also now shifts by its padded size instead of by 1
                        // (port e41069df, yorkie-js-sdk#1360, the second half of that
                        // fix). Restore/retombstone ops use their own per-node
                        // executedRanges loop above instead.
                        if (opInfosForOp.any { it is OperationInfo.TreeEditOpInfo }) {
                            val (from, to) = executedOp.normalizePos()
                            internalHistory.reconcileTreeEdit(
                                executedOp.parentCreatedAt,
                                from,
                                to,
                                executedOp.getContentSize(),
                            )
                        }
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
     * Re-issues every ticket this document's local changes minted under its
     * current actor to [actor], when [reissue] is true AND every pre-attach
     * condition holds ([neverSynced], has local changes, [actor] actually
     * differs from the current one); otherwise behaves exactly like the
     * plain [setActor] above. Returns whether a re-issue happened --
     * [dev.yorkie.core.Client] uses that, together with
     * [hasMintedOperations], to decide whether this attach takes the
     * per-client pre-attach-reissue claim.
     *
     * The whole check + rebuild + replay + swap runs in ONE [withContext]
     * hop with NO inner suspension -- [reissueOperations] (a wire-format
     * walk) and [Change.execute] are both non-suspending -- so no other
     * coroutine on this document's [dispatcher] can ever observe a partially
     * rebuilt state. D2 (backlog 019): a re-issue requested while an
     * [updateAsync]/undo/redo is in flight on this SAME dispatcher refuses
     * instead of letting a resumed updater mint old-actor operations against
     * a root already swapped to the new actor; the document is left
     * completely untouched (nothing is assigned below until the rebuild has
     * fully succeeded, so a throw anywhere in the rebuild also leaves the
     * document untouched). Ports yorkie-js-sdk `document.ts`'s
     * `reissueActor` (`ed25355e`, #1449, yorkie#2111).
     */
    internal suspend fun setActor(actor: String, reissue: Boolean): Boolean =
        withContext(dispatcher) {
            val prevActor = changeID.actor
            if (!reissue || actor == prevActor || localChanges.isEmpty() || !neverSynced) {
                setActor(actor)
                return@withContext false
            }
            if (isUpdating) {
                throw YorkieException(
                    ErrInvalidArgument,
                    "cannot re-issue document($key) actor while an update is in flight",
                )
            }

            // ---- Build aside: nothing below assigns to `this` until every
            // step here has already succeeded. ----
            val newRoot =
                CrdtRoot(CrdtObject(createdAt = InitialTimeTicket, memberNodes = ElementRht()))
            var accPresences: Presences = UninitializedPresences
            val newLocalChanges = localChanges.map { change ->
                val newId = change.id.setActor(actor)
                    .setVersionVector(
                        reissueVersionVector(change.id.versionVector, prevActor, actor),
                    )
                val newOperations = reissueOperations(change.operations, prevActor, actor)
                change.copy(id = newId, operations = newOperations)
                    .also { it.isUndoRedo = change.isUndoRedo }
            }
            for (change in newLocalChanges) {
                val result = change.execute(newRoot, accPresences, replaySourceOf(change))
                result.newPresences?.let { accPresences = it }
            }
            val newChangeID = changeID.setActor(actor)
                .setVersionVector(reissueVersionVector(changeID.versionVector, prevActor, actor))

            // ---- Atomic swap: no suspension between any of these. ----
            root = newRoot
            _presences.value = accPresences
            val onlineNow = onlineClients.value
            if (prevActor in onlineNow) {
                onlineClients.value = onlineNow - prevActor + actor
            }
            changeID = newChangeID
            localChanges.clear()
            localChanges.addAll(newLocalChanges)
            clone = null
            clearHistory()
            true
        }

    /**
     * The replay source [setActor]'s re-issue runs each rebuilt change
     * under: [OpSource.UndoRedo] reproduces an undo/redo's own skip guards
     * (none are actually consumed on Android today -- see [Change.execute]'s
     * own KDoc -- but replaying under the ORIGINAL source is still what
     * makes this replay reproduce the original run byte-for-byte), every
     * other change replays under [OpSource.Local].
     */
    private fun replaySourceOf(change: Change): OpSource {
        return if (change.isUndoRedo) OpSource.UndoRedo else OpSource.Local
    }

    /**
     * Moves [from]'s entry of [vector] to [to], taking the max of the two
     * lamports if [to] already has an entry of its own (context §3.2's
     * `reissueVersionVector`). [vector] itself is never mutated --
     * [VersionVector.deepCopy] is edited and returned.
     */
    private fun reissueVersionVector(
        vector: VersionVector,
        from: String,
        to: String,
    ): VersionVector {
        val fromLamport = vector.get(from) ?: return vector.deepCopy()
        val copy = vector.deepCopy()
        copy.unset(from)
        val existing = copy.get(to)
        copy.set(to, if (existing != null) maxOf(existing, fromLamport) else fromLamport)
        return copy
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
        // A restored document is never re-issued (context §3.2; D1 -- a
        // restored document replays its log under Local and never
        // re-issues): its checkpoint/changeID came from a prior session that
        // already synced or minted, regardless of what this fresh checkpoint
        // now reads.
        absorbedRemote = true
        mintedOperations = true
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
        // Replaying a persisted append log means this document was already
        // synced in a prior session -- never re-issued, regardless of what
        // a fresh in-memory checkpoint reads (context §3.2). A replayed
        // change that minted operations also counts toward the claim.
        absorbedRemote = true
        if (changes.any { it.hasOperations }) {
            mintedOperations = true
        }

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
     * Moves this document's [ChangeID.clientSeq] counter forward to [clientSeq], never
     * backward. Mirrors JS `Document.advanceClientSeqTo` (yorkie-js-sdk#1395).
     *
     * Exists for the one caller (the store-backed restore's log-discontinuity repair, in
     * `Client.kt`) that has to undo a persisted header without undoing the counter inside
     * it. [restoreFromBytes] returns [checkPoint], [epoch] and [changeID] to what the
     * snapshot carries, which is right for a header the appended log cannot back — except
     * for the counter. A counter is not a claim about content the way a checkpoint is: it
     * records which [ChangeID.clientSeq] values this client has already MINTED, and the
     * server has taken some of them. Rewinding it to the snapshot's counter mints those
     * sequences a second time; the server skips them as duplicates, and the next ack —
     * whose clientSeq covers them — drops them from [localChanges] as pushed. The edits are
     * lost with no event.
     *
     * The position the caller hands over is the acked checkpoint, not the header's own
     * counter: the server validates continuity from the position it holds, so the next
     * change must be its clientSeq plus one; the counter can lead that (an edit minted
     * while a sync was in flight), and resuming there would mint past the server and wedge
     * every later push on `ErrInvalidClientSeq`.
     *
     * Forward-only by construction: a caller that hands over a stale position cannot pull
     * this document back into reusing sequence numbers. `internal`, not `public` — JS
     * exposes this on `Document` for its client code only (no module-private concept in
     * JS); every other store-backed-restore helper on this class ([restoreFromBytes],
     * [restoreMetaFromBytes], [restoreAppendedChanges], [persistBase]) is `internal` too,
     * and this matches them rather than being `public`.
     */
    internal suspend fun advanceClientSeqTo(clientSeq: UInt): Unit = withContext(dispatcher) {
        if (clientSeq > changeID.clientSeq) {
            changeID = changeID.copy(clientSeq = clientSeq)
        }
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
        // Only absorbedRemote is cleared -- a re-anchored document is once
        // again eligible to be considered never-synced by checkpoint/status
        // alone. mintedOperations is NEVER cleared (context §3.2): whatever
        // this document already minted stays minted even across a reanchor.
        absorbedRemote = false
        // Lifts a resync's mint suspension (D3): a successful resync calls this right
        // before re-attaching, so the fresh attach starts mintable again. A refused
        // detach instead rolls back via restoreLocalChanges, which clears the same flag.
        // mintGeneration is bumped too (CJ-MED-1, round 4 fix): a pass that captured its
        // generation BEFORE the discard and resumes only now, after the whole resync
        // already finished, must still be refused -- mintingSuspended alone is already
        // false again right here.
        mintingSuspended = false
        mintGeneration++
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
         * `AuthError` means that an authentication error occurred. Published for
         * [AuthErrorMethod.Watch]/[AuthErrorMethod.Broadcast] on `ErrUnauthenticated`, and
         * -- new as of this document's push-pull path -- for [AuthErrorMethod.PushPull] on
         * `ErrPermissionDenied` (an authorization webhook denial, distinct from an
         * unauthenticated token): published once per denial EPISODE, not once per
         * sync-loop tick, and only `shouldRefreshToken`-free (this path never asks for a
         * token refresh, since the token itself was accepted). An episode ends only on the
         * next push-pull that SUCCEEDS on this attachment; a different failure (e.g. a
         * transient `RESOURCE_EXHAUSTED`) in between does not clear it or re-publish.
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

            // WriteDenied is raised when `Client.resyncAsync` discards this document's
            // un-pushed local changes after the server refused to accept them (JS SDK
            // `client.ts` `61ca06c9`/#1463). Ported verbatim from JS's
            // `local-changes-dropped` reason `write-denied`.
            WriteDenied("write-denied"),
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

    public data class Options
    @JvmOverloads
    constructor(
        /**
         * Disables garbage collection if true.
         */
        public val disableGC: Boolean = false,
        /**
         * Seeds the presence-free state before attach. The server-fixated
         * value from the attach response takes precedence once attached.
         */
        public val disablePresence: Boolean = false,
        /**
         * How many entries `history.undo()` can go back, and likewise for
         * `history.redo()`. Each [updateAsync] that changes the document is
         * one entry; the oldest is dropped once a stack is full. Must be a
         * positive integer, otherwise construction throws
         * [YorkieException] with [ErrInvalidArgument]. Defaults to 50. A
         * reverse entry keeps a copy of the content it would restore, so a
         * larger depth holds more memory.
         */
        public val maxUndoDepth: Int = MaxUndoRedoStackDepth,
    ) {
        init {
            if (maxUndoDepth < 1) {
                throw YorkieException(
                    ErrInvalidArgument,
                    "maxUndoDepth must be a positive integer: $maxUndoDepth",
                )
            }
        }
    }

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
                    // A document rebuilt from a byte envelope is never
                    // re-issued (context §3.2): its snapshot/checkpoint/log
                    // came from a prior session that already synced or
                    // minted, regardless of what this fresh checkpoint reads.
                    doc.absorbedRemote = true
                    doc.mintedOperations = true
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
 * One tree node id a split OR a copy-reinsert reverse re-created during
 * [Document.executeUndoRedoInternal], and what it replaces: [op] is the
 * [TreeEditOperation] (compared by reference elsewhere, never by its
 * data-class `equals`), [prev] is the element the reverse names, [curr] is
 * the freshly minted replacement, and [ticketIndex] is which of [op]'s
 * recorded split tickets [curr] is -- so the drain after both execution
 * passes can tell whether the ROOT pass really consumed that ticket
 * ([TreeEditOperation.getConsumedSplitTicketCount]) -- or `-1` for a
 * copy-reinsert's reissued content id, which is minted unconditionally (no
 * early-exit for the two passes to disagree over) and so skips that check.
 * Port yorkie-js-sdk `b173c093` (#1426).
 */
private data class PendingTreeRepoint(
    val op: TreeEditOperation,
    val prev: CrdtTreeNodeID,
    val curr: CrdtTreeNodeID,
    val ticketIndex: Int,
)

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
