package dev.yorkie.collaborative.editing

import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.JsonTree.ElementNode
import dev.yorkie.document.json.JsonTree.TextNode
import dev.yorkie.document.json.JsonTree.TreeNode
import timber.log.Timber

/**
 * Converter for transforming editor data and operations to Yorkie Tree format.
 *
 * This converter handles the bidirectional transformation between
 * the editor's internal representation and Yorkie's Tree data structure.
 *
 * ## Milestone 2 Features:
 * - Convert editor content to Yorkie tree data
 * - Convert editor operations to Yorkie tree operations
 * - Verify editor and tree data consistency
 *
 * ## Tree Structure:
 * For plain text editing, we use a simple tree structure:
 * ```
 * <root>
 *   <p>paragraph 1 text</p>
 *   <p>paragraph 2 text</p>
 * </root>
 * ```
 *
 * ## Index Mapping:
 * The index in the editor corresponds to positions in the flattened tree.
 * For a tree like `<root><p>hello</p></root>`:
 * - Index 0: before 'h' (inside <p>)
 * - Index 1: after 'h'
 * - Index 2: after 'e'
 * - etc.
 *
 * The Yorkie tree uses a different indexing that includes element boundaries:
 * - Index 0: before <p>
 * - Index 1: before 'h' (inside <p>)
 * - Index 2-6: character positions
 * - Index 7: after </p>
 */
public class TreeConverter(
    private val config: TreeConverterConfig = TreeConverterConfig(),
) {

    /**
     * Convert editor content to a Yorkie tree structure.
     *
     * @param content The editor content to convert
     * @return The root element node of the tree
     */
    public fun editorContentToTree(content: EditorContent): ElementNode {
        val paragraphs = content.paragraphs
        val children = paragraphs.map { paragraphText ->
            if (paragraphText.isEmpty()) {
                // Empty paragraph with empty text node
                ElementNode(
                    type = config.paragraphType,
                    children = listOf(TextNode("")),
                )
            } else {
                ElementNode(
                    type = config.paragraphType,
                    children = listOf(TextNode(paragraphText)),
                )
            }
        }

        return ElementNode(
            type = config.rootType,
            children = children,
        )
    }

    /**
     * Convert a Yorkie tree to plain text content.
     *
     * @param tree The Yorkie tree to convert
     * @return The plain text content
     */
    public fun treeToEditorContent(tree: JsonTree?): EditorContent {
        if (tree == null) {
            return EditorContent("")
        }

        val text = extractTextFromTree(tree.rootTreeNode)
        return EditorContent(
            text = text,
            rootType = config.rootType,
            paragraphType = config.paragraphType,
        )
    }

    /**
     * Recursively extract text from a tree node.
     */
    private fun extractTextFromTree(node: TreeNode): String {
        return when (node) {
            is TextNode -> node.value
            is ElementNode -> {
                val childTexts = node.children.map { extractTextFromTree(it) }
                if (node.type == config.paragraphType) {
                    // Join paragraph children and add newline at the end
                    childTexts.joinToString("")
                } else {
                    // For root and other elements, join with newlines between paragraphs
                    childTexts.joinToString("\n")
                }
            }
            else -> ""
        }
    }

    /**
     * Convert editor index to Yorkie tree index.
     *
     * The editor uses a flat index (character position in text).
     * The Yorkie tree uses an index that accounts for element boundaries.
     *
     * For a simple structure `<root><p>hello</p></root>`:
     * - Editor index 0 -> Tree index 1 (inside <p>, before 'h')
     * - Editor index 5 -> Tree index 6 (inside <p>, after 'o')
     *
     * @param editorIndex The editor's character index
     * @param tree The current tree state
     * @return The corresponding Yorkie tree index
     */
    public fun editorIndexToTreeIndex(editorIndex: Int, tree: JsonTree): Int {
        // For a simple single-paragraph structure, add 1 for the opening <p> tag
        // For multi-paragraph, we need to account for additional element boundaries
        return editorIndexToTreeIndexInternal(editorIndex, tree.rootTreeNode, 0).first
    }

    /**
     * Internal recursive function to convert editor index to tree index.
     *
     * @return Pair of (treeIndex, consumedEditorChars)
     */
    private fun editorIndexToTreeIndexInternal(
        targetEditorIndex: Int,
        node: TreeNode,
        currentTreeIndex: Int,
    ): Pair<Int, Int> {
        when (node) {
            is TextNode -> {
                val textLength = node.value.length
                // Each character in text node maps 1:1 with tree index
                return if (targetEditorIndex <= textLength) {
                    (currentTreeIndex + targetEditorIndex) to textLength
                } else {
                    (currentTreeIndex + textLength) to textLength
                }
            }
            is ElementNode -> {
                var treeIdx = currentTreeIndex + 1 // Account for opening tag
                var editorCharsConsumed = 0

                for ((index, child) in node.children.withIndex()) {
                    val remainingEditorIndex = targetEditorIndex - editorCharsConsumed

                    if (remainingEditorIndex < 0) {
                        break
                    }

                    val (childTreeIndex, childConsumed) = editorIndexToTreeIndexInternal(
                        remainingEditorIndex,
                        child,
                        treeIdx,
                    )

                    if (remainingEditorIndex <= childConsumed) {
                        return childTreeIndex to (editorCharsConsumed + remainingEditorIndex)
                    }

                    editorCharsConsumed += childConsumed

                    // Add newline between paragraphs in editor representation
                    if (node.type == config.rootType &&
                        child is ElementNode &&
                        child.type == config.paragraphType &&
                        index < node.children.size - 1
                    ) {
                        editorCharsConsumed += 1 // Newline character
                    }

                    treeIdx = childTreeIndex + if (child is ElementNode) 1 else 0 // Closing tag
                }

                return treeIdx to editorCharsConsumed
            }
            else -> return currentTreeIndex to 0
        }
    }

    /**
     * Convert an editor operation to tree edit parameters.
     *
     * @param operation The editor operation
     * @param tree The current tree state
     * @return Triple of (fromIndex, toIndex, content nodes) for tree.edit()
     */
    public fun editorOperationToTreeEdit(
        operation: EditorOperation.Edit,
        tree: JsonTree,
    ): TreeEditParams {
        val fromTreeIndex = editorIndexToTreeIndex(operation.from, tree)
        val toTreeIndex = editorIndexToTreeIndex(operation.to, tree)

        val contentNodes = if (operation.content.isNotEmpty()) {
            listOf(TextNode(operation.content))
        } else {
            emptyList()
        }

        return TreeEditParams(
            fromIndex = fromTreeIndex,
            toIndex = toTreeIndex,
            contents = contentNodes,
        )
    }

    /**
     * Apply an editor operation to the Yorkie tree.
     *
     * This method converts the editor operation and applies it to the tree.
     *
     * @param operation The editor operation to apply
     * @param tree The Yorkie tree to edit
     */
    public fun applyOperationToTree(operation: EditorOperation, tree: JsonTree) {
        when (operation) {
            is EditorOperation.Edit -> {
                val params = editorOperationToTreeEdit(operation, tree)
                Timber.d(
                    "TreeConverter: Applying edit - from=${params.fromIndex}, " +
                        "to=${params.toIndex}, content=${params.contents}",
                )

                if (params.contents.isEmpty()) {
                    // Deletion
                    tree.edit(params.fromIndex, params.toIndex)
                } else {
                    // Insertion or replacement
                    tree.edit(
                        params.fromIndex,
                        params.toIndex,
                        *params.contents.toTypedArray(),
                    )
                }
            }

            is EditorOperation.EditByPath -> {
                val contentNodes = if (operation.content.isNotEmpty()) {
                    arrayOf<TreeNode>(TextNode(operation.content))
                } else {
                    emptyArray()
                }

                Timber.d(
                    "TreeConverter: Applying edit by path - from=${operation.fromPath}, " +
                        "to=${operation.toPath}, content=$contentNodes",
                )

                tree.editByPath(
                    operation.fromPath,
                    operation.toPath,
                    *contentNodes,
                )
            }

            is EditorOperation.Batch -> {
                Timber.d("TreeConverter: Applying batch of ${operation.operations.size} operations")
                operation.operations.forEach { op ->
                    applyOperationToTree(op, tree)
                }
            }
        }
    }

    /**
     * Verify that the editor content matches the Yorkie tree content.
     *
     * This is useful for debugging and ensuring synchronization is correct.
     *
     * @param editorContent The current editor content
     * @param tree The current Yorkie tree
     * @return VerificationResult indicating match or mismatch details
     */
    public fun verifyConsistency(
        editorContent: EditorContent,
        tree: JsonTree?,
    ): VerificationResult {
        if (tree == null) {
            return if (editorContent.text.isEmpty()) {
                VerificationResult.Match
            } else {
                VerificationResult.Mismatch(
                    expected = editorContent.text,
                    actual = "",
                    message = "Tree is null but editor has content",
                )
            }
        }

        val treeContent = treeToEditorContent(tree)

        return if (editorContent.text == treeContent.text) {
            VerificationResult.Match
        } else {
            VerificationResult.Mismatch(
                expected = editorContent.text,
                actual = treeContent.text,
                message = "Editor content does not match tree content",
            )
        }
    }

    public companion object {
        private const val TAG = "TreeConverter"
    }
}

/**
 * Configuration for the TreeConverter.
 *
 * @property rootType The type name for the root element
 * @property paragraphType The type name for paragraph elements
 */
public data class TreeConverterConfig(
    val rootType: String = "root",
    val paragraphType: String = "p",
)

/**
 * Parameters for a tree edit operation.
 */
public data class TreeEditParams(
    val fromIndex: Int,
    val toIndex: Int,
    val contents: List<TreeNode>,
)

/**
 * Result of verifying consistency between editor and tree.
 */
public sealed class VerificationResult {
    /**
     * Editor and tree content match.
     */
    public data object Match : VerificationResult()

    /**
     * Editor and tree content do not match.
     */
    public data class Mismatch(
        val expected: String,
        val actual: String,
        val message: String,
    ) : VerificationResult()
}
