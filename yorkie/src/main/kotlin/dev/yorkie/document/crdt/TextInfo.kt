package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket

/**
 * The value passed as an argument to [CrdtText.onChanges].
 * [CrdtText.onChanges] is called when the [CrdtText] is modified.
 */
internal data class TextChange(
    val type: TextChangeType,
    val actor: ActorID,
    val from: Number,
    val to: Number,
    var content: String? = null,
)

/**
 * The type of [TextChange].
 */
internal enum class TextChangeType {
    Content, Selection, Style
}

/**
 * Represents the selection of text range in the editor.
 */
internal data class Selection(
    private val from: RgaTreeSplitNodePos,
    private val to: RgaTreeSplitNodePos,
    private val updatedAt: TimeTicket,
)
