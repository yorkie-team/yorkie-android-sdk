@file:Suppress("ktlint:standard:filename")

package dev.yorkie.document.crdt

import androidx.annotation.VisibleForTesting
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_TEXT_TYPE

/**
 * A span whose [TreeRestoreSpan.isText] flag disagrees with its
 * [TreeRestoreSpan.nodeType] describes no node this SDK can build (a text
 * node's type is always [DEFAULT_TEXT_TYPE], an element's never is) -- a
 * corrupted or forged span. [CrdtTree.restore] and [CrdtTree.retombstone]
 * must skip exactly the same spans (Go yorkie#2168 / #2175), so both check
 * this up front rather than letting the disagreement surface as a
 * downstream throw or a double-registration. Port yorkie-js-sdk
 * `restoreSpanKindAgrees` (`5c8dd738`, #1475).
 */
internal fun CrdtTree.restoreSpanKindAgrees(span: TreeRestoreSpan): Boolean =
    span.isText == (span.nodeType == DEFAULT_TEXT_TYPE)

/**
 * Resolves [CrdtTree.recreateFromSpan]'s parent, or `null` if the node it
 * would recreate must stay unplaced: the parent is absent (purged), the
 * parent is a text node (a text node can hold no children -- a span naming
 * one is corrupted or forged), or [id] is already held by a surviving node
 * (exactly, or inside a same-insertion text run that covers it) --
 * recreating there would double-register an identity a concurrent op
 * already placed. Go's `recreateParent` applies the same three rules; both
 * SDKs must agree, or a stored history replays differently per replica.
 * Port yorkie-js-sdk `recreateParent` (`5c8dd738`, #1475; Go yorkie#2168 /
 * #2175).
 */
internal fun CrdtTree.recreateParent(span: TreeRestoreSpan, id: CrdtTreeNodeID): CrdtTreeNode? {
    val parentID = span.parentID ?: return null
    val parent = findFloorNode(parentID)
    if (parent == null || parent.id != parentID || parent.isText) return null

    val holder = findFloorNode(id)
    if (holder != null &&
        (
            holder.id.offset == id.offset ||
                (holder.isText && holder.id.offset + holder.value.length > id.offset)
            )
    ) {
        return null
    }
    return parent
}

/**
 * Test-only accessor for [recreateParent]: a held-identity collision is only
 * reachable through a hand-built fixture (a genuine restore never targets an
 * id a survivor already holds), so the test needs direct access to the
 * function under test. Mirrors [CrdtTree.resolveMergeTargetForTest].
 */
@VisibleForTesting
internal fun CrdtTree.recreateParentForTest(
    span: TreeRestoreSpan,
    id: CrdtTreeNodeID,
): CrdtTreeNode? = recreateParent(span, id)
