package dev.yorkie.collaborative.editing

import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.operation.OperationInfo

/**
 * Interface that editors must implement to integrate with the Collaborative Edit Plugin.
 *
 * This adapter bridges the gap between the editor's internal data model and
 * Yorkie's Tree data structure, enabling bidirectional synchronization.
 *
 * ## Milestone 1 Features:
 * - Basic structure for event subscription
 *
 * ## Milestone 2 Features:
 * - Editor content access for conversion
 * - Local operation handling
 */
public interface EditorAdapter {

    /**
     * Called when the plugin has been initialized and ready to sync.
     * The editor should set up its initial state from the provided tree.
     *
     * @param tree The initial Yorkie tree data, may be null for new documents
     */
    public fun onInitialized(tree: JsonTree?)

    /**
     * Called when a snapshot is received from the server.
     * The editor should replace its entire content with the snapshot data.
     *
     * @param tree The complete tree state from the snapshot
     */
    public fun onSnapshot(tree: JsonTree?)

    /**
     * Called when remote changes are received from other clients.
     * The editor should apply these changes to its local state.
     *
     * For Milestone 1, this provides the hook. Milestone 3 will implement
     * the actual operation conversion.
     *
     * @param operations List of tree operation information from remote changes
     */
    public fun onRemoteChange(operations: List<OperationInfo>)

    /**
     * Subscribe to editor change events.
     * The plugin will call this to listen for local changes that need to be
     * pushed to Yorkie.
     *
     * @param listener The callback to invoke when local changes occur
     */
    public fun subscribeToChanges(listener: EditorChangeListener)

    /**
     * Unsubscribe from editor change events.
     */
    public fun unsubscribeFromChanges()

    // ========== Milestone 2: Content Access Methods ==========

    /**
     * Get the current editor content as plain text.
     *
     * This method is used by the plugin to:
     * - Initialize the Yorkie tree with editor content
     * - Verify consistency between editor and Yorkie tree
     *
     * @return The current content of the editor
     */
    public fun getContent(): EditorContent

    /**
     * Set the editor content from the given EditorContent.
     *
     * This is called when the tree needs to be synchronized to the editor,
     * such as on initialization or snapshot.
     *
     * @param content The content to set in the editor
     */
    public fun setContent(content: EditorContent)
}

/**
 * Listener interface for editor change events.
 *
 * Editors implement this to notify the plugin about local changes.
 *
 * ## Milestone 2 Features:
 * - Typed operation handling with EditorOperation
 */
public interface EditorChangeListener {

    /**
     * Called when the user makes a local edit to the document.
     *
     * For Milestone 2, this handles typed EditorOperation for conversion
     * to Yorkie tree operations.
     *
     * @param operation The operation representing the local change
     */
    public suspend fun onLocalChange(operation: EditorOperation)

    /**
     * Called when the user's selection or cursor position changes.
     *
     * @param from The start position of the selection
     * @param to The end position of the selection
     */
    public fun onSelectionChange(from: Int, to: Int)
}
