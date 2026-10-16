package dev.yorkie.api

import com.google.protobuf.ByteString
import dev.yorkie.api.v1.NodeAttr
import dev.yorkie.api.v1.copy
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.api.v1.RestoreSpan as PbRestoreSpan
import dev.yorkie.api.v1.TreeRestoreSpan as PbTreeRestoreSpan

/**
 * Rewrites every ticket [from] minted to [to] across every operation in
 * [ops], rebuilding each through the wire format so every nested structure
 * keyed by a [dev.yorkie.document.time.TimeTicket] (RGA/RHT/tree node ids,
 * text split links) comes back with internally consistent indices -- a
 * node's identity is its sort key inside [dev.yorkie.document.crdt.RgaTreeSplit]/
 * [dev.yorkie.document.crdt.ElementRht]/[dev.yorkie.document.crdt.CrdtTree],
 * so mutating a ticket in place without rebuilding the owning structure would
 * corrupt its own index. [ops] itself, and every element reachable from it,
 * is never mutated -- every write lands on a freshly `.copy {}`-built
 * protobuf message or a freshly decoded domain object.
 *
 * Ports yorkie-js-sdk `api/reissue.ts` (`ed25355e`, #1449, yorkie#2111): no
 * runtime reflection is available here (protobuf-javalite is the only
 * runtime dependency), so the JS reflect-walker becomes an explicit
 * `.copy {}` walk over every ticket-bearing field named in the upstream
 * change (context doc §3.3).
 *
 * A lamport-0 ticket is [dev.yorkie.document.time.TimeTicket.InitialTimeTicket],
 * shared by every replica -- never rewritten. A map/RHT KEY (an object
 * member's string key) is never actor-addressed -- never rewritten. [to]
 * must have a 24-hex byte form (same shape every real [ActorID] has); a
 * malformed [to] throws before any operation is touched, via
 * [String.toByteString]'s own even-length/hex validation.
 */
internal fun reissueOperations(
    ops: List<Operation>,
    from: String,
    to: String,
): List<Operation> {
    val fromBytes = from.toByteString()
    val toBytes = to.toByteString()
    return ops.map { it.reissue(fromBytes, toBytes) }
}

private fun Operation.reissue(fromBytes: ByteString, toBytes: ByteString): Operation {
    val reissuedPb = toPBOperation().reissue(fromBytes, toBytes)
    val reissuedOp = listOf(reissuedPb).toOperations().single()
    return reissuedOp.reissueTextValue(this, fromBytes, toBytes)
}

/**
 * A Set/Add/ArraySet whose value is a [CrdtText] loses its content on the
 * ordinary wire round-trip: [CrdtElement.toPBJsonElementSimple] encodes a
 * `CrdtText` value as `type = TEXT` with no bytes (an ordinary Set never
 * carries one; only an undo of a Remove restores one), so
 * [PBJsonElementSimple.toCrdtElement] always decodes it back empty. Rebuild
 * it instead from the FULL `JSONElement` representation ([CrdtText.toPBText]),
 * which does carry every node -- walk THAT, then decode it back -- and
 * splice the rebuilt value into the already-reissued operation. Depends on
 * the `insPrevId` emit/decode fix (ElementConverter.kt) so the rebuilt
 * text's split links survive. Ports yorkie-js-sdk `reissueTextValue`
 * (`api/reissue.ts`, `ed25355e`).
 */
private fun Operation.reissueTextValue(
    original: Operation,
    fromBytes: ByteString,
    toBytes: ByteString,
): Operation {
    val originalText = when (original) {
        is SetOperation -> original.value as? CrdtText
        is AddOperation -> original.value as? CrdtText
        is ArraySetOperation -> original.value as? CrdtText
        else -> null
    } ?: return this
    val rebuilt = originalText.toPBText().reissue(fromBytes, toBytes).toCrdtElement() as CrdtText
    return when (this) {
        is SetOperation -> copy(value = rebuilt)
        is AddOperation -> copy(value = rebuilt)
        is ArraySetOperation -> copy(value = rebuilt)
        else -> this
    }
}

// ---- Wire-level walk -------------------------------------------------------

private fun PBTimeTicket.reissue(fromBytes: ByteString, toBytes: ByteString): PBTimeTicket {
    // InitialTimeTicket (lamport 0) is shared by every replica -- never
    // rewritten, mirroring yorkie-js-sdk's reissue.ts.
    return if (lamport != 0L && actorId == fromBytes) copy { actorId = toBytes } else this
}

private fun PBOperation.reissue(fromBytes: ByteString, toBytes: ByteString): PBOperation {
    return when {
        hasSet() -> copy {
            set = set.copy {
                parentCreatedAt = parentCreatedAt.reissue(fromBytes, toBytes)
                executedAt = executedAt.reissue(fromBytes, toBytes)
                value = value.reissue(fromBytes, toBytes)
            }
        }

        hasAdd() -> copy {
            add = add.copy {
                parentCreatedAt = parentCreatedAt.reissue(fromBytes, toBytes)
                prevCreatedAt = prevCreatedAt.reissue(fromBytes, toBytes)
                executedAt = executedAt.reissue(fromBytes, toBytes)
                value = value.reissue(fromBytes, toBytes)
            }
        }

        hasMove() -> copy {
            move = move.copy {
                parentCreatedAt = parentCreatedAt.reissue(fromBytes, toBytes)
                prevCreatedAt = prevCreatedAt.reissue(fromBytes, toBytes)
                createdAt = createdAt.reissue(fromBytes, toBytes)
                executedAt = executedAt.reissue(fromBytes, toBytes)
            }
        }

        hasRemove() -> copy {
            remove = remove.copy {
                parentCreatedAt = parentCreatedAt.reissue(fromBytes, toBytes)
                createdAt = createdAt.reissue(fromBytes, toBytes)
                executedAt = executedAt.reissue(fromBytes, toBytes)
            }
        }

        hasEdit() -> copy {
            edit = edit.copy {
                parentCreatedAt = parentCreatedAt.reissue(fromBytes, toBytes)
                from = from.reissue(fromBytes, toBytes)
                to = to.reissue(fromBytes, toBytes)
                executedAt = executedAt.reissue(fromBytes, toBytes)
                if (restoreSpans.isNotEmpty()) {
                    val rewritten = restoreSpans.map { it.reissue(fromBytes, toBytes) }
                    restoreSpans.clear()
                    restoreSpans.addAll(rewritten)
                }
                if (retombstoneSpans.isNotEmpty()) {
                    val rewritten = retombstoneSpans.map { it.reissue(fromBytes, toBytes) }
                    retombstoneSpans.clear()
                    retombstoneSpans.addAll(rewritten)
                }
            }
        }

        hasStyle() -> copy {
            style = style.copy {
                parentCreatedAt = parentCreatedAt.reissue(fromBytes, toBytes)
                from = from.reissue(fromBytes, toBytes)
                to = to.reissue(fromBytes, toBytes)
                executedAt = executedAt.reissue(fromBytes, toBytes)
            }
        }

        hasIncrease() -> copy {
            increase = increase.copy {
                parentCreatedAt = parentCreatedAt.reissue(fromBytes, toBytes)
                executedAt = executedAt.reissue(fromBytes, toBytes)
                value = value.reissue(fromBytes, toBytes)
                // `actor` (string) is a counter merge tiebreak actor, not a
                // TimeTicket-embedded one -- untouched, same as a map key.
            }
        }

        hasTreeEdit() -> copy {
            treeEdit = treeEdit.copy {
                parentCreatedAt = parentCreatedAt.reissue(fromBytes, toBytes)
                from = from.reissue(fromBytes, toBytes)
                to = to.reissue(fromBytes, toBytes)
                executedAt = executedAt.reissue(fromBytes, toBytes)
                if (contents.isNotEmpty()) {
                    val rewritten = contents.map { it.reissue(fromBytes, toBytes) }
                    contents.clear()
                    contents.addAll(rewritten)
                }
                if (splitTickets.isNotEmpty()) {
                    val rewritten = splitTickets.map { it.reissue(fromBytes, toBytes) }
                    splitTickets.clear()
                    splitTickets.addAll(rewritten)
                }
                if (restoreSpans.isNotEmpty()) {
                    val rewritten = restoreSpans.map { it.reissue(fromBytes, toBytes) }
                    restoreSpans.clear()
                    restoreSpans.addAll(rewritten)
                }
                if (retombstoneSpans.isNotEmpty()) {
                    val rewritten = retombstoneSpans.map { it.reissue(fromBytes, toBytes) }
                    retombstoneSpans.clear()
                    retombstoneSpans.addAll(rewritten)
                }
            }
        }

        hasTreeStyle() -> copy {
            treeStyle = treeStyle.copy {
                parentCreatedAt = parentCreatedAt.reissue(fromBytes, toBytes)
                from = from.reissue(fromBytes, toBytes)
                to = to.reissue(fromBytes, toBytes)
                executedAt = executedAt.reissue(fromBytes, toBytes)
            }
        }

        hasArraySet() -> copy {
            arraySet = arraySet.copy {
                parentCreatedAt = parentCreatedAt.reissue(fromBytes, toBytes)
                createdAt = createdAt.reissue(fromBytes, toBytes)
                executedAt = executedAt.reissue(fromBytes, toBytes)
                value = value.reissue(fromBytes, toBytes)
            }
        }

        else -> this
    }
}

private fun PBJsonElementSimple.reissue(
    fromBytes: ByteString,
    toBytes: ByteString,
): PBJsonElementSimple {
    return copy {
        createdAt = createdAt.reissue(fromBytes, toBytes)
        if (hasMovedAt()) movedAt = movedAt.reissue(fromBytes, toBytes)
        if (hasRemovedAt()) removedAt = removedAt.reissue(fromBytes, toBytes)
        val recurseIntoValue = type == PBValueType.VALUE_TYPE_JSON_OBJECT ||
            type == PBValueType.VALUE_TYPE_JSON_ARRAY ||
            type == PBValueType.VALUE_TYPE_TREE
        // TEXT carries no value bytes here (see reissueTextValue above);
        // every other type's value bytes carry no ticket of their own.
        if (recurseIntoValue && !value.isEmpty) {
            value = PBJsonElement.parseFrom(value).reissue(fromBytes, toBytes).toByteString()
        }
    }
}

private fun PBJsonElement.reissue(fromBytes: ByteString, toBytes: ByteString): PBJsonElement {
    return when {
        hasJsonObject() -> copy { jsonObject = jsonObject.reissue(fromBytes, toBytes) }
        hasJsonArray() -> copy { jsonArray = jsonArray.reissue(fromBytes, toBytes) }
        hasText() -> copy { text = text.reissue(fromBytes, toBytes) }
        hasPrimitive() -> copy { primitive = primitive.reissue(fromBytes, toBytes) }
        hasCounter() -> copy { counter = counter.reissue(fromBytes, toBytes) }
        hasTree() -> copy { tree = tree.reissue(fromBytes, toBytes) }
        else -> this
    }
}

private fun PBJsonObject.reissue(fromBytes: ByteString, toBytes: ByteString): PBJsonObject {
    return copy {
        createdAt = createdAt.reissue(fromBytes, toBytes)
        if (hasMovedAt()) movedAt = movedAt.reissue(fromBytes, toBytes)
        if (hasRemovedAt()) removedAt = removedAt.reissue(fromBytes, toBytes)
        if (hasRevivedAt()) revivedAt = revivedAt.reissue(fromBytes, toBytes)
        val rewritten = nodes.map { it.reissue(fromBytes, toBytes) }
        nodes.clear()
        nodes.addAll(rewritten)
    }
}

private fun PBRhtNode.reissue(fromBytes: ByteString, toBytes: ByteString): PBRhtNode {
    // `key` (the object member's name) is never actor-addressed -- untouched.
    return copy { element = element.reissue(fromBytes, toBytes) }
}

private fun PBJsonArray.reissue(fromBytes: ByteString, toBytes: ByteString): PBJsonArray {
    return copy {
        createdAt = createdAt.reissue(fromBytes, toBytes)
        if (hasMovedAt()) movedAt = movedAt.reissue(fromBytes, toBytes)
        if (hasRemovedAt()) removedAt = removedAt.reissue(fromBytes, toBytes)
        if (hasRevivedAt()) revivedAt = revivedAt.reissue(fromBytes, toBytes)
        val rewritten = nodes.map { it.reissue(fromBytes, toBytes) }
        nodes.clear()
        nodes.addAll(rewritten)
    }
}

private fun PBRgaNode.reissue(fromBytes: ByteString, toBytes: ByteString): PBRgaNode {
    return copy {
        if (hasElement()) element = element.reissue(fromBytes, toBytes)
        if (hasPositionCreatedAt()) {
            positionCreatedAt = positionCreatedAt.reissue(fromBytes, toBytes)
        }
        if (hasPositionMovedAt()) positionMovedAt = positionMovedAt.reissue(fromBytes, toBytes)
        if (hasPositionRemovedAt()) {
            positionRemovedAt = positionRemovedAt.reissue(fromBytes, toBytes)
        }
        // `next` is never populated by the encoder and never read by the
        // decoder (ElementConverter.kt's `toCrdtArray`) -- left untouched.
    }
}

private fun PBPrimitive.reissue(fromBytes: ByteString, toBytes: ByteString): PBPrimitive {
    return copy {
        createdAt = createdAt.reissue(fromBytes, toBytes)
        if (hasMovedAt()) movedAt = movedAt.reissue(fromBytes, toBytes)
        if (hasRemovedAt()) removedAt = removedAt.reissue(fromBytes, toBytes)
        if (hasRevivedAt()) revivedAt = revivedAt.reissue(fromBytes, toBytes)
        // `value` is the raw primitive payload -- carries no ticket.
    }
}

private fun PBCounter.reissue(fromBytes: ByteString, toBytes: ByteString): PBCounter {
    return copy {
        createdAt = createdAt.reissue(fromBytes, toBytes)
        if (hasMovedAt()) movedAt = movedAt.reissue(fromBytes, toBytes)
        if (hasRemovedAt()) removedAt = removedAt.reissue(fromBytes, toBytes)
        if (hasRevivedAt()) revivedAt = revivedAt.reissue(fromBytes, toBytes)
        // `value`/`hllRegisters` carry no ticket.
    }
}

private fun PBText.reissue(fromBytes: ByteString, toBytes: ByteString): PBText {
    return copy {
        createdAt = createdAt.reissue(fromBytes, toBytes)
        if (hasMovedAt()) movedAt = movedAt.reissue(fromBytes, toBytes)
        if (hasRemovedAt()) removedAt = removedAt.reissue(fromBytes, toBytes)
        if (hasRevivedAt()) revivedAt = revivedAt.reissue(fromBytes, toBytes)
        val rewritten = nodes.map { it.reissue(fromBytes, toBytes) }
        nodes.clear()
        nodes.addAll(rewritten)
    }
}

private fun PBTextNode.reissue(fromBytes: ByteString, toBytes: ByteString): PBTextNode {
    return copy {
        id = id.reissue(fromBytes, toBytes)
        if (hasRemovedAt()) removedAt = removedAt.reissue(fromBytes, toBytes)
        if (hasInsPrevId()) insPrevId = insPrevId.reissue(fromBytes, toBytes)
        if (attributes.isNotEmpty()) {
            val rewritten = attributes.mapValues { it.value.reissue(fromBytes, toBytes) }
            attributes.clear()
            attributes.putAll(rewritten)
        }
    }
}

private fun PBTextNodeID.reissue(fromBytes: ByteString, toBytes: ByteString): PBTextNodeID {
    return copy { createdAt = createdAt.reissue(fromBytes, toBytes) }
}

private fun PBTextNodePos.reissue(fromBytes: ByteString, toBytes: ByteString): PBTextNodePos {
    return copy { createdAt = createdAt.reissue(fromBytes, toBytes) }
}

private fun NodeAttr.reissue(fromBytes: ByteString, toBytes: ByteString): NodeAttr {
    return copy { updatedAt = updatedAt.reissue(fromBytes, toBytes) }
}

private fun PBTreePos.reissue(fromBytes: ByteString, toBytes: ByteString): PBTreePos {
    return copy {
        parentId = parentId.reissue(fromBytes, toBytes)
        leftSiblingId = leftSiblingId.reissue(fromBytes, toBytes)
    }
}

private fun PBTreeNodeID.reissue(fromBytes: ByteString, toBytes: ByteString): PBTreeNodeID {
    return copy { createdAt = createdAt.reissue(fromBytes, toBytes) }
}

private fun PBTreeNodes.reissue(fromBytes: ByteString, toBytes: ByteString): PBTreeNodes {
    return copy {
        val rewritten = content.map { it.reissue(fromBytes, toBytes) }
        content.clear()
        content.addAll(rewritten)
    }
}

private fun PBTreeNode.reissue(fromBytes: ByteString, toBytes: ByteString): PBTreeNode {
    return copy {
        id = id.reissue(fromBytes, toBytes)
        if (hasRemovedAt()) removedAt = removedAt.reissue(fromBytes, toBytes)
        if (hasInsPrevId()) insPrevId = insPrevId.reissue(fromBytes, toBytes)
        if (hasInsNextId()) insNextId = insNextId.reissue(fromBytes, toBytes)
        if (hasMergedFrom()) mergedFrom = mergedFrom.reissue(fromBytes, toBytes)
        if (hasMergedAt()) mergedAt = mergedAt.reissue(fromBytes, toBytes)
        if (attributes.isNotEmpty()) {
            val rewritten = attributes.mapValues { it.value.reissue(fromBytes, toBytes) }
            attributes.clear()
            attributes.putAll(rewritten)
        }
    }
}

private fun PBTree.reissue(fromBytes: ByteString, toBytes: ByteString): PBTree {
    return copy {
        createdAt = createdAt.reissue(fromBytes, toBytes)
        if (hasMovedAt()) movedAt = movedAt.reissue(fromBytes, toBytes)
        if (hasRemovedAt()) removedAt = removedAt.reissue(fromBytes, toBytes)
        if (hasRevivedAt()) revivedAt = revivedAt.reissue(fromBytes, toBytes)
        val rewritten = nodes.map { it.reissue(fromBytes, toBytes) }
        nodes.clear()
        nodes.addAll(rewritten)
    }
}

private fun PbRestoreSpan.reissue(fromBytes: ByteString, toBytes: ByteString): PbRestoreSpan {
    return copy { createdAt = createdAt.reissue(fromBytes, toBytes) }
}

private fun PbTreeRestoreSpan.reissue(
    fromBytes: ByteString,
    toBytes: ByteString,
): PbTreeRestoreSpan {
    return copy {
        id = id.reissue(fromBytes, toBytes)
        if (hasParentId()) parentId = parentId.reissue(fromBytes, toBytes)
        if (hasLeftSiblingId()) leftSiblingId = leftSiblingId.reissue(fromBytes, toBytes)
        if (hasRightSiblingId()) rightSiblingId = rightSiblingId.reissue(fromBytes, toBytes)
        if (attributes.isNotEmpty()) {
            val rewritten = attributes.mapValues { it.value.reissue(fromBytes, toBytes) }
            attributes.clear()
            attributes.putAll(rewritten)
        }
    }
}
