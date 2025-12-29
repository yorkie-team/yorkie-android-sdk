package dev.yorkie.collaborative.editing

import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.presence.PresenceInfo
import dev.yorkie.document.presence.Presences
import dev.yorkie.document.time.ActorID

/**
 * Sealed class representing events that occur in the collaborative editing session.
 *
 * These events are emitted by the plugin to notify the editor about:
 * - Connection status changes
 * - Remote document changes
 * - Presence updates from other users
 */
public sealed class CollaborativeEditEvent {

    /**
     * Plugin lifecycle events.
     */
    public sealed class Lifecycle : CollaborativeEditEvent() {
        /**
         * The plugin has been initialized and connected to the Yorkie server.
         */
        public data class Initialized(
            val actorId: ActorID,
        ) : Lifecycle()

        /**
         * The plugin is ready with the document attached.
         */
        public data class Ready(
            val documentKey: String,
            val tree: JsonTree?,
        ) : Lifecycle()

        /**
         * The plugin has been destroyed and disconnected.
         */
        public data object Destroyed : Lifecycle()

        /**
         * An error occurred during plugin operation.
         */
        public data class Error(
            val message: String,
            val cause: Throwable?,
        ) : Lifecycle()
    }

    /**
     * Sync status events.
     */
    public sealed class SyncStatus : CollaborativeEditEvent() {
        /**
         * Document has been synced successfully.
         */
        public data object Synced : SyncStatus()

        /**
         * Sync failed with an error.
         */
        public data class SyncFailed(
            val cause: Throwable?,
        ) : SyncStatus()
    }

    /**
     * Stream connection events.
     */
    public sealed class ConnectionStatus : CollaborativeEditEvent() {
        /**
         * Stream connection has been established.
         */
        public data object Connected : ConnectionStatus()

        /**
         * Stream connection has been disconnected.
         */
        public data object Disconnected : ConnectionStatus()
    }

    /**
     * Document change events.
     */
    public sealed class DocumentChanged : CollaborativeEditEvent() {
        /**
         * A snapshot has been received from the server.
         */
        public data class Snapshot(
            val tree: JsonTree?,
        ) : DocumentChanged()

        /**
         * Local changes have been applied to the document.
         */
        public data class LocalChange(
            val changeInfo: Document.Event.ChangeInfo,
        ) : DocumentChanged()

        /**
         * Remote changes have been received from other clients.
         */
        public data class RemoteChange(
            val changeInfo: Document.Event.ChangeInfo,
            val operations: List<OperationInfo>,
        ) : DocumentChanged()
    }

    /**
     * Presence events for tracking other users.
     */
    public sealed class PresenceEvent : CollaborativeEditEvent() {
        /**
         * Initial presence information loaded.
         */
        public data class Initialized(
            val presences: Presences,
        ) : PresenceEvent()

        /**
         * My presence has been updated.
         */
        public data class MyPresenceChanged(
            val presenceInfo: PresenceInfo,
        ) : PresenceEvent()

        /**
         * Another user has joined the document.
         */
        public data class OtherWatched(
            val presenceInfo: PresenceInfo,
        ) : PresenceEvent()

        /**
         * Another user has left the document.
         */
        public data class OtherUnwatched(
            val presenceInfo: PresenceInfo,
        ) : PresenceEvent()

        /**
         * Another user's presence has changed.
         */
        public data class OtherPresenceChanged(
            val presenceInfo: PresenceInfo,
        ) : PresenceEvent()
    }
}
