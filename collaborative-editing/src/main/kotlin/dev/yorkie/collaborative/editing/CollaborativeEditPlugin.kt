package dev.yorkie.collaborative.editing

import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.presence.Presences
import dev.yorkie.document.time.ActorID
import java.io.Closeable
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Collaborative Edit Plugin for Yorkie Android SDK.
 *
 * This plugin provides a bridge between a text editor and the Yorkie CRDT
 * document synchronization system, enabling real-time collaborative editing.
 *
 * ## Milestone 1 Features:
 * - Config injection from editor
 * - Yorkie.Client initialization
 * - Editor and Yorkie event subscriptions
 *
 * ## Milestone 2 Features:
 * - Convert editor operations to Yorkie.Tree operations
 * - Verify editor and tree data consistency after local updates
 * - Support for plain text collaborative editing
 *
 * ## Usage:
 * ```kotlin
 * val plugin = CollaborativeEditPlugin(
 *     config = CollaborativeEditConfig(
 *         serverUrl = "https://api.yorkie.dev",
 *         documentKey = "my-document",
 *         apiKey = "your-api-key",
 *     ),
 *     editorAdapter = myEditorAdapter,
 * )
 *
 * // Initialize and connect
 * plugin.initialize()
 *
 * // Listen to events
 * lifecycleScope.launch {
 *     plugin.events.collect { event ->
 *         when (event) {
 *             is CollaborativeEditEvent.Lifecycle.Ready -> {
 *                 // Plugin is ready
 *             }
 *             is CollaborativeEditEvent.DocumentChanged.RemoteChange -> {
 *                 // Handle remote changes
 *             }
 *             // ... handle other events
 *         }
 *     }
 * }
 *
 * // Clean up when done (in a coroutine)
 * plugin.closeAsync()
 * ```
 *
 * @param config The configuration for connecting to Yorkie server
 * @param editorAdapter The adapter that bridges the editor with this plugin
 * @param treeConverter The converter for transforming editor data to Yorkie tree format
 * @param coroutineScope The scope for launching coroutines (default: GlobalScope)
 */
public class CollaborativeEditPlugin(
    private val config: CollaborativeEditConfig,
    private val editorAdapter: EditorAdapter,
    private val treeConverter: TreeConverter = TreeConverter(),
) : Closeable, EditorChangeListener {
    private val client = Client(
        host = config.serverUrl,
        options = config.clientOptions ?: Client.Options(
            apiKey = config.apiKey,
        ),
    )
    private var document = Document(config.documentKey)

    /**
     * Flag to temporarily ignore local changes during remote updates.
     * This prevents infinite loops when applying remote changes to the editor.
     */
    @Volatile
    private var isApplyingRemoteChanges = false

    private val _events = MutableSharedFlow<CollaborativeEditEvent>(replay = 1)

    /**
     * Flow of plugin events for the editor to observe.
     * Merges lifecycle events with document events transformed to plugin events.
     */
    public val events: Flow<CollaborativeEditEvent> = merge(
        _events,
        document.events.mapNotNull { mapDocumentEvent(it) },
    )

    private var _isInitialized = false
    public val isInitialized: Boolean
        get() = _isInitialized

    private var _isReady = false
    public val isReady: Boolean
        get() = _isReady

    private var myActorId: ActorID? = null

    /**
     * Initialize the plugin by connecting to Yorkie server and attaching the document.
     *
     * This method:
     * 1. Creates a Yorkie Client with the provided configuration
     * 2. Activates the client (connects to server)
     * 3. Attaches the document with the specified key
     * 4. Subscribes to document events
     * 5. Subscribes to editor changes via the adapter
     *
     */
    public suspend fun initialize() {
        if (_isInitialized) {
            Timber.w("CollaborativeEditPlugin is already initialized")
            return
        }

        try {
            // Activate client
            val activateResult = client.activateAsync().await()
            if (activateResult.isFailure) {
                emitError(
                    "Failed to connect to Yorkie server",
                    activateResult.exceptionOrNull(),
                )
                return
            }

            myActorId = client.requireClientId()
            _isInitialized = true

            Timber.i("CollaborativeEditPlugin: Client activated with ID ${myActorId?.value}")
            _events.emit(CollaborativeEditEvent.Lifecycle.Initialized(myActorId!!))

            // Create and attach document
            val attachResult = client.attachDocument(
                document = document,
                initialPresence = config.initialPresence,
                syncMode = config.syncMode,
            ).await()

            if (attachResult.isFailure) {
                emitError(
                    "Failed to attach document: ${config.documentKey}",
                    attachResult.exceptionOrNull(),
                )
                return
            }

            Timber.i("CollaborativeEditPlugin: Document attached: ${config.documentKey}")

            // Subscribe to editor changes
            editorAdapter.subscribeToChanges(this@CollaborativeEditPlugin)

            // Get initial tree and notify editor
            var tree = getTree()

            // If tree doesn't exist, initialize it from editor content
            if (tree == null) {
                val editorContent = editorAdapter.getContent()
                if (editorContent.text.isNotEmpty()) {
                    tree = initializeTreeFromEditorContent(editorContent)
                    Timber.i("CollaborativeEditPlugin: Initialized tree from editor content")
                }
            } else {
                // Tree exists, sync editor with tree content
                val treeContent = treeConverter.treeToEditorContent(tree)
                editorAdapter.setContent(treeContent)
                Timber.i("CollaborativeEditPlugin: Synced editor with existing tree content")
            }

            editorAdapter.onInitialized(tree)

            _isReady = true
            _events.emit(
                CollaborativeEditEvent.Lifecycle.Ready(
                    documentKey = config.documentKey,
                    tree = tree,
                ),
            )
        } catch (e: Exception) {
            emitError("Initialization failed", e)
        }
    }

    /**
     * Map Yorkie document events to plugin events.
     * Side effects (editor adapter callbacks, actorId updates) are handled here.
     *
     * @return The mapped CollaborativeEditEvent, or null if the event should be filtered
     */
    private suspend fun mapDocumentEvent(event: Document.Event): CollaborativeEditEvent? {
        return when (event) {
            is Document.Event.Snapshot -> {
                Timber.d("CollaborativeEditPlugin: Snapshot received")
                val tree = getTree()
                // Convert tree to editor content and update editor
                isApplyingRemoteChanges = true
                try {
                    val treeContent = treeConverter.treeToEditorContent(tree)
                    editorAdapter.setContent(treeContent)
                } finally {
                    isApplyingRemoteChanges = false
                }
                editorAdapter.onSnapshot(tree)
                CollaborativeEditEvent.DocumentChanged.Snapshot(tree)
            }

            is Document.Event.LocalChange -> {
                Timber.d("CollaborativeEditPlugin: Local change applied")
                CollaborativeEditEvent.DocumentChanged.LocalChange(event.changeInfo)
            }

            is Document.Event.RemoteChange -> {
                Timber.d("CollaborativeEditPlugin: Remote change received")
                val operations = event.changeInfo.operations
                editorAdapter.onRemoteChange(operations)
                CollaborativeEditEvent.DocumentChanged.RemoteChange(
                    changeInfo = event.changeInfo,
                    operations = operations,
                )
            }

            is Document.Event.PresenceChanged.MyPresence.Initialized -> {
                Timber.d("CollaborativeEditPlugin: Presence initialized")
                CollaborativeEditEvent.PresenceEvent.Initialized(event.initialized)
            }

            is Document.Event.PresenceChanged.MyPresence.PresenceChanged -> {
                Timber.d("CollaborativeEditPlugin: My presence changed")
                myActorId = event.changed.actorID
                CollaborativeEditEvent.PresenceEvent.MyPresenceChanged(event.changed)
            }

            is Document.Event.PresenceChanged.Others.Watched -> {
                Timber.d("CollaborativeEditPlugin: User watched: ${event.changed.actorID}")
                CollaborativeEditEvent.PresenceEvent.OtherWatched(event.changed)
            }

            is Document.Event.PresenceChanged.Others.Unwatched -> {
                Timber.d("CollaborativeEditPlugin: User unwatched: ${event.changed.actorID}")
                CollaborativeEditEvent.PresenceEvent.OtherUnwatched(event.changed)
            }

            is Document.Event.PresenceChanged.Others.PresenceChanged -> {
                Timber.d(
                    "CollaborativeEditPlugin: Other presence changed: ${event.changed.actorID}",
                )
                CollaborativeEditEvent.PresenceEvent.OtherPresenceChanged(event.changed)
            }

            is Document.Event.SyncStatusChanged.Synced -> {
                Timber.d("CollaborativeEditPlugin: Synced")
                CollaborativeEditEvent.SyncStatus.Synced
            }

            is Document.Event.SyncStatusChanged.SyncFailed -> {
                Timber.w("CollaborativeEditPlugin: Sync failed: ${event.cause?.message}")
                CollaborativeEditEvent.SyncStatus.SyncFailed(event.cause)
            }

            is Document.Event.StreamConnectionChanged.Connected -> {
                Timber.d("CollaborativeEditPlugin: Stream connected")
                CollaborativeEditEvent.ConnectionStatus.Connected
            }

            is Document.Event.StreamConnectionChanged.Disconnected -> {
                Timber.w("CollaborativeEditPlugin: Stream disconnected")
                CollaborativeEditEvent.ConnectionStatus.Disconnected
            }

            else -> {
                Timber.v("CollaborativeEditPlugin: Unhandled event: ${event::class.simpleName}")
                null
            }
        }
    }

    /**
     * Get the Yorkie tree from the document root.
     *
     * @param key The key of the tree in the root object, defaults to "tree"
     * @return The JsonTree if it exists, null otherwise
     */
    public suspend fun getTree(key: String = TREE_KEY): JsonTree? {
        return document.getRoot().getAsOrNull(key)
    }

    /**
     * Get the document root object.
     *
     * @return The root JsonObject
     */
    public suspend fun getRoot(): JsonObject {
        return document.getRoot()
    }

    /**
     * Update the document with editor changes.
     *
     * This is the main entry point for pushing local changes to Yorkie.
     *
     * @param message Optional commit message for the change
     * @param updater The update function that modifies the document
     */
    public suspend fun updateDocument(
        message: String? = null,
        updater: suspend (root: JsonObject) -> Unit,
    ) {
        document.updateAsync(message) { root, _ ->
            updater(root)
        }.await()
    }

    /**
     * Update presence information.
     *
     * @param presence Map of presence key-value pairs to update
     */
    public suspend fun updatePresence(presence: Map<String, String>) {
        document.updateAsync { _, presenceProxy ->
            presenceProxy.put(presence)
        }.await()
    }

    /**
     * Get the current presences of all connected clients.
     *
     * @return The current presences
     */
    public fun getPresences(): Presences {
        return document.presences.value
    }

    /**
     * Get my actor ID.
     *
     * @return The actor ID of this client
     */
    public fun getMyActorId(): ActorID? = myActorId

    /**
     * Change the sync mode of the document.
     *
     * This is useful for switching between push-only mode during composition
     * (e.g., Hangul input) and real-time mode for normal editing.
     *
     * @param syncMode The new sync mode to use
     */
    public fun changeSyncMode(syncMode: Client.SyncMode) {
        client.changeSyncMode(document, syncMode)
        Timber.d("CollaborativeEditPlugin: Sync mode changed to $syncMode")
    }

    /**
     * Force sync the document with the server.
     */
    public suspend fun sync() {
        client.syncAsync(document).await()
    }

    /**
     * Destroy the plugin and clean up resources.
     *
     * This method:
     * 1. Unsubscribes from editor changes
     * 2. Detaches the document
     * 3. Deactivates the client
     * 4. Cancels all coroutines
     */
    private suspend fun destroy() {
        if (!_isInitialized) {
            return
        }

        try {
            // Unsubscribe from editor changes
            editorAdapter.unsubscribeFromChanges()

            // Detach document
            client.detachDocument(document).await()

            // Deactivate client
            client.deactivateAsync().await()

            Timber.i("CollaborativeEditPlugin: Destroyed")
            _events.emit(CollaborativeEditEvent.Lifecycle.Destroyed)
        } catch (e: Exception) {
            Timber.e(e, "Error during plugin destruction")
        } finally {
            _isInitialized = false
            _isReady = false
            client.close()
            document.close()
        }
    }

    /**
     * Closes the plugin synchronously. Blocks the calling thread.
     * Warning: Do not call from the main thread - use [closeAsync] instead.
     */
    @OptIn(DelicateCoroutinesApi::class)
    override fun close() {
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            destroy()
        }
    }

    // ========== Milestone 2: Tree Initialization ==========

    /**
     * Initialize the Yorkie tree from editor content.
     *
     * This creates a new tree in the document root with the editor's
     * current content.
     *
     * @param content The editor content to initialize with
     * @return The created JsonTree
     */
    private suspend fun initializeTreeFromEditorContent(content: EditorContent): JsonTree? {
        val initialRoot = treeConverter.editorContentToTree(content)

        document.updateAsync("Initialize tree from editor") { root, _ ->
            root.setNewTree(TREE_KEY, initialRoot)
        }.await()

        return getTree()
    }

    // ========== EditorChangeListener Implementation ==========

    /**
     * Called when the editor has local changes.
     *
     * Milestone 2 implementation: Converts editor operations to Yorkie.Tree
     * operations and applies them to the document.
     */
    override suspend fun onLocalChange(operation: EditorOperation) {
        // Ignore changes while applying remote updates to prevent loops
        if (isApplyingRemoteChanges) {
            Timber.v("CollaborativeEditPlugin: Ignoring local change during remote update")
            return
        }

        Timber.d("CollaborativeEditPlugin: Local change from editor: $operation")

        try {
            applyLocalOperation(operation)
        } catch (e: Exception) {
            Timber.e(e, "CollaborativeEditPlugin: Error applying local operation")
            emitError("Failed to apply local operation", e)
        }
    }

    /**
     * Apply a local operation to the Yorkie document.
     *
     * This method:
     * 1. Gets the current tree
     * 2. Converts the editor operation to tree operations
     * 3. Applies the operations to the tree
     * 4. Verifies consistency between editor and tree
     */
    private suspend fun applyLocalOperation(operation: EditorOperation) {
        val message = when (operation) {
            is EditorOperation.Edit -> {
                if (operation.isInsertion) {
                    "Insert text"
                } else if (operation.isDeletion) {
                    "Delete text"
                } else {
                    "Replace text"
                }
            }

            is EditorOperation.EditByPath -> "Edit by path"
            is EditorOperation.Batch -> operation.message ?: "Batch operation"
        }

        document.updateAsync(message) { root, _ ->
            val tree = root.getAsOrNull<JsonTree>(TREE_KEY)

            if (tree == null) {
                // Tree doesn't exist, create it first
                Timber.w("CollaborativeEditPlugin: Tree not found, creating new tree")
                val editorContent = editorAdapter.getContent()
                val initialRoot = treeConverter.editorContentToTree(editorContent)
                root.setNewTree(TREE_KEY, initialRoot)
                return@updateAsync
            }

            // Apply the operation to the tree
            treeConverter.applyOperationToTree(operation, tree)

            Timber.d("CollaborativeEditPlugin: Applied operation to tree, xml=${tree.toXml()}")
        }.await()

        // Verify consistency after update
        verifyConsistencyAfterUpdate()
    }

    /**
     * Verify that the editor content matches the Yorkie tree after an update.
     *
     * This helps catch synchronization issues early during development.
     */
    private suspend fun verifyConsistencyAfterUpdate() {
        val tree = getTree()
        val editorContent = editorAdapter.getContent()

        val result = treeConverter.verifyConsistency(editorContent, tree)

        when (result) {
            is VerificationResult.Match -> {
                Timber.v("CollaborativeEditPlugin: Editor and tree are in sync")
            }

            is VerificationResult.Mismatch -> {
                Timber.w(
                    "CollaborativeEditPlugin: Editor/tree mismatch - ${result.message}\n" +
                        "Editor: '${result.expected}'\nTree: '${result.actual}'",
                )
            }
        }
    }

    /**
     * Called when the user's selection changes.
     * Updates the presence with the new selection.
     */
    override fun onSelectionChange(from: Int, to: Int) {
        // TODO: Milestone 4 - Convert selection to Yorkie presence format
        Timber.d("CollaborativeEditPlugin: Selection changed: $from to $to")
    }

    private suspend fun emitError(message: String, cause: Throwable?) {
        Timber.e(cause, "CollaborativeEditPlugin Error: $message")
        _events.emit(CollaborativeEditEvent.Lifecycle.Error(message, cause))
    }

    public companion object {
        /**
         * Default key for the tree in the document root.
         */
        public const val TREE_KEY: String = "tree"
    }
}
