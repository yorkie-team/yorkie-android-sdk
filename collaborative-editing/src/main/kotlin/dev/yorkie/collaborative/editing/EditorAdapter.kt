package dev.yorkie.collaborative.editing

import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.operation.OperationInfo

/**
 * Interface that editors must implement to integrate with the Collaborative Edit Plugin.
 *
 * This adapter bridges the gap between the editor's internal data model and
 * Yorkie's Tree data structure, enabling bidirectional synchronization.
 *
 * For Milestone 1, this provides the basic structure for event subscription.
 * Future milestones will add operation conversion methods.
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
}

/**
 * Listener interface for editor change events.
 *
 * Editors implement this to notify the plugin about local changes.
 */
public interface EditorChangeListener {

    /**
     * Called when the user makes a local edit to the document.
     *
     * For Milestone 1, this is a placeholder. Milestone 2 will implement
     * the actual operation conversion from editor operations to Yorkie operations.
     *
     * @param changeInfo Information about the change (editor-specific format)
     */
    public fun onLocalChange(changeInfo: Any)

    /**
     * Called when the user's selection or cursor position changes.
     *
     * @param from The start position of the selection
     * @param to The end position of the selection
     */
    public fun onSelectionChange(from: Int, to: Int)
}
