package dev.yorkie.collaborative.editing

import dev.yorkie.core.Client

/**
 * Configuration for the Collaborative Edit Plugin.
 *
 * This config is injected from the editor to initialize the plugin with
 * necessary connection details and options.
 *
 * @property serverUrl The Yorkie server URL to connect to
 * @property apiKey Optional API key for authentication with the Yorkie server
 * @property documentKey The unique key for the collaborative document
 * @property initialPresence Optional initial presence data for the user
 * @property syncMode The synchronization mode for the document (default: Realtime)
 * @property clientOptions Optional custom client options
 */
public data class CollaborativeEditConfig(
    val serverUrl: String,
    val documentKey: String,
    val apiKey: String? = null,
    val initialPresence: Map<String, String> = emptyMap(),
    val syncMode: Client.SyncMode = Client.SyncMode.Realtime,
    val clientOptions: Client.Options? = null,
)
