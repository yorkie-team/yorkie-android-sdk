package com.example.texteditor

import androidx.compose.ui.graphics.Color
import dev.yorkie.document.time.ActorID

/**
 * MVI Contract for the Text Editor
 * Defines the UI State, User Intents, and Side Effects
 */
object TextEditorContract {

    /**
     * Represents the complete UI state of the text editor
     */
    data class State(
        val content: String = "",
        val isLoading: Boolean = false,
        val isConnected: Boolean = false,
        val error: String? = null,
        val peerSelections: Map<ActorID, PeerSelection> = emptyMap(),
        val isComposing: Boolean = false,
        val localSelection: TextSelection = TextSelection(0, 0)
    ) {
        data class PeerSelection(
            val range: IntRange,
            val color: Color
        )
        
        data class TextSelection(
            val start: Int,
            val end: Int
        )
    }

    /**
     * Represents all possible user intents in the text editor
     */
    sealed class Intent {
        data class InitializeEditor(val initialContent: String = "") : Intent()
        data class TextChanged(val newText: String, val changeRange: TextRange) : Intent()
        data class SelectionChanged(val start: Int, val end: Int) : Intent()
        object StartHangulComposition : Intent()
        object EndHangulComposition : Intent()
        data class ApplyRemoteChange(val text: String) : Intent()
        object SyncText : Intent()
        object Disconnect : Intent()
        data class RemovePeer(val actorID: ActorID) : Intent()
    }

    /**
     * Represents text change information
     */
    data class TextRange(
        val start: Int,
        val end: Int,
        val text: String
    )

    /**
     * Side effects that don't directly change the UI state
     */
    sealed class Effect {
        data class ShowError(val message: String) : Effect()
        data class LogDebug(val message: String) : Effect()
        object ScrollToSelection : Effect()
    }
}
