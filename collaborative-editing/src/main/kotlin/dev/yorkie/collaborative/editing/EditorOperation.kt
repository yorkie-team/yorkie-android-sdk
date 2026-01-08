package dev.yorkie.collaborative.editing

/**
 * Represents editor operations that can be converted to Yorkie Tree operations.
 *
 * These operations are used to capture local editor changes and convert them
 * to Yorkie Tree operations for synchronization.
 *
 * ## Milestone 2 Features:
 * - Plain text edit operations (insert, delete, replace)
 * - Path-based operations for tree structure
 */
public sealed class EditorOperation {

    /**
     * Represents an edit operation on the editor content.
     *
     * For plain text editing (Milestone 2), this captures text insertions,
     * deletions, and replacements using index-based positions.
     *
     * @property from The start index of the edit range
     * @property to The end index of the edit range
     * @property content The content to insert (empty for deletions)
     */
    public data class Edit(
        val from: Int,
        val to: Int,
        val content: String,
    ) : EditorOperation() {

        /**
         * Whether this operation is an insertion (no content being replaced).
         */
        val isInsertion: Boolean
            get() = from == to && content.isNotEmpty()

        /**
         * Whether this operation is a deletion (no content being inserted).
         */
        val isDeletion: Boolean
            get() = from != to && content.isEmpty()

        /**
         * Whether this operation is a replacement (content being replaced).
         */
        val isReplacement: Boolean
            get() = from != to && content.isNotEmpty()
    }

    /**
     * Represents an edit operation using path-based addressing.
     *
     * Path-based operations are useful for tree-structured editors where
     * positions are specified as paths through the tree hierarchy.
     *
     * @property fromPath The path to the start position
     * @property toPath The path to the end position
     * @property content The content to insert (empty for deletions)
     */
    public data class EditByPath(
        val fromPath: List<Int>,
        val toPath: List<Int>,
        val content: String,
    ) : EditorOperation()

    /**
     * Represents a batch of operations to be applied atomically.
     *
     * This is useful for editors that generate multiple operations
     * for a single user action (e.g., composition, autocomplete).
     *
     * @property operations The list of operations to apply
     * @property message Optional message describing the batch
     */
    public data class Batch(
        val operations: List<EditorOperation>,
        val message: String? = null,
    ) : EditorOperation()
}

/**
 * Represents the editor's text content for conversion to Yorkie Tree.
 *
 * This is a simple representation of plain text content that can be
 * converted to a Yorkie Tree structure.
 *
 * @property text The plain text content
 * @property rootType The type of the root element (default: "root")
 * @property paragraphType The type of paragraph elements (default: "p")
 */
public data class EditorContent(
    val text: String,
    val rootType: String = "root",
    val paragraphType: String = "p",
) {
    /**
     * Split the text into paragraphs (by newline).
     */
    public val paragraphs: List<String>
        get() = text.split("\n")
}
