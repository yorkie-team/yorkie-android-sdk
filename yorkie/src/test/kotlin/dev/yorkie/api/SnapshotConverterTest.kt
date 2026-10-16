package dev.yorkie.api

import android.util.Base64
import dev.yorkie.api.v1.JSONElementKt.tree
import dev.yorkie.api.v1.copy
import dev.yorkie.api.v1.nodeAttr
import dev.yorkie.api.v1.textNode
import dev.yorkie.api.v1.textNodeID
import dev.yorkie.api.v1.treeNode
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.issueTime
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `snapshot_converter_test.ts` (yorkie-js-sdk#1338 /
 * `2291bf67`, RTCOLLABPLATFORM-771). Pins [snapshotToBytes] as the exact
 * inverse of [ByteString?.toSnapshot] and the [ChangeID] binary helpers.
 */
class SnapshotConverterTest {

    // Encoding a ChangeID with a non-empty VersionVector routes through
    // VersionVectorConverter.kt -> android.util.Base64, which is an unmocked
    // stub under plain JVM unit tests. Bridge to java.util.Base64 for every
    // case; harmless for the snapshot-only cases which never touch it.
    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    @Test
    fun `should round-trip root and presences`() = runTest {
        val document = Document("")
        document.updateAsync { root, presence ->
            root.setNewObject("obj").apply {
                this["nested"] = true
                this["count"] = 42
            }
            root.setNewArray("arr").apply {
                put(1)
                put(2)
                put("three")
            }
            root.setNewText("text").edit(0, 0, "hello")
            root.setNewCounter("counter", 5)
            presence.put(mapOf("cursor" to "7", "name" to "alice"))
        }.await()

        val bytes = snapshotToBytes(document.getRootObject(), document.allPresences.value)
        val (root, presences) = bytes.toSnapshot()

        assertEquals(document.toJson(), root.toJson())
        assertEquals(document.allPresences.value.toMap(), presences)
    }

    @Test
    fun `should round-trip an empty document`() = runTest {
        val document = Document("")

        val bytes = snapshotToBytes(document.getRootObject(), document.allPresences.value)
        val (root, presences) = bytes.toSnapshot()

        assertEquals(document.toJson(), root.toJson())
        assertEquals(emptyMap(), presences)
    }

    // Port yorkie-js-sdk snapshot_converter_test.ts (v0.7.23, 248551a1,
    // #1364, yorkie#2006). Before this fix, toPBTextNodes omitted isRemoved
    // for text attributes (though it iterates removed ones via
    // attributesWithTimeTicket) and toRgaTreeSplitNode used setAttribute
    // (LWW set, decodes live) instead of setInternal — so a tombstoned text
    // attribute round-tripped through a snapshot decoded back LIVE, stamped
    // with the removal's own ticket.
    @Test
    fun `should round-trip a tombstoned text attribute`() {
        // Built directly on CrdtObject/CrdtText (mirrors
        // CrdtTextTest."restore registers a recreated node's copied
        // attribute tombstone..."), not through Document.updateAsync:
        // updateAsync mutates a detached CLONE and commits by replaying the
        // pushed Operation list, so a raw CrdtText.removeStyle call made
        // inside an updater — with no corresponding Operation pushed — is
        // silently discarded when the clone's edits are applied back to the
        // live root. JsonText also has no public removeStyle (Tree-only in
        // the public API), so there is no Operation to push anyway.
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket)
        val text = CrdtText(RgaTreeSplit(), tick(1))
        obj.set("text", text, tick(1))

        text.edit(text.indexRangeToPosRange(0, 0), "hello", tick(2))
        text.style(text.indexRangeToPosRange(0, 5), mapOf("bold" to "true"), tick(3))
        val removedAt = tick(4)
        text.removeStyle(text.indexRangeToPosRange(0, 5), listOf("bold"), removedAt)

        val beforeAttr = text.rgaTreeSplit.single().value.attributesWithTimeTicket
            .single { it.key == "bold" }
        assertEquals(true, beforeAttr.isRemoved)
        assertEquals(removedAt, beforeAttr.executedAt)

        val bytes = snapshotToBytes(obj, emptyMap())
        val (decodedRoot, _) = bytes.toSnapshot()

        val decodedText = decodedRoot["text"] as CrdtText
        val decodedAttr = decodedText.rgaTreeSplit.single().value.attributesWithTimeTicket
            .single { it.key == "bold" }
        assertEquals(true, decodedAttr.isRemoved, "a tombstoned text attribute must decode removed")
        assertEquals(removedAt, decodedAttr.executedAt, "it must carry its OWN removal ticket")
    }

    // The other direction: a proto whose NodeAttr.isRemoved is ABSENT (an
    // older peer / a pre-fix snapshot) must decode LIVE — the exact pre-fix
    // (JS-before) behaviour. Exercises PBTextNode.toRgaTreeSplitNode
    // directly so the hand-crafted proto never touches the (now
    // isRemoved-aware) write path.
    @Test
    fun `should decode a text attribute with isRemoved absent as live`() {
        val createdAt = issueTime()
        val updatedAt = issueTime()
        val pbNode = textNode {
            id = textNodeID {
                this.createdAt = createdAt.toPBTimeTicket()
                offset = 0
            }
            value = "x"
            attributes["bold"] = nodeAttr {
                value = "true"
                this.updatedAt = updatedAt.toPBTimeTicket()
                // isRemoved deliberately left unset (proto3 default false) —
                // the shape an older peer (pre-#1364) would have written.
            }
        }

        val decoded = pbNode.toRgaTreeSplitNode()

        val attr = decoded.value.attributesWithTimeTicket.single { it.key == "bold" }
        assertEquals(false, attr.isRemoved, "an absent isRemoved flag must decode live")
    }

    // iOS-only probe (#277); does NOT apply to Android — the head sentinel
    // RgaTreeSplitIterator.next() skips (RgaTreeSplit.kt:916, head.next) is
    // never encoded. A text snapshot round-trip must add no empty node and
    // leave docSize unchanged. No regression risk here: nothing in this
    // diff touches the iteration start point.
    @Test
    fun `text snapshot round-trip adds no empty node and leaves docSize unchanged`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "hello")
        }.await()

        val crdtText = document.getRootObject()["text"] as CrdtText
        val nodeCountBefore = crdtText.rgaTreeSplit.count()
        val docSizeBefore = document.getDocSize()

        val bytes = snapshotToBytes(document.getRootObject(), document.allPresences.value)
        val (root, _) = bytes.toSnapshot()

        val decodedText = root["text"] as CrdtText
        assertEquals(nodeCountBefore, decodedText.rgaTreeSplit.count())
        assertEquals("hello", decodedText.toString())
        assertEquals(docSizeBefore, CrdtRoot(root).docSize)
    }

    // (JS SDK b69169c0, #1404): `toCrdtTreeRootNode` is
    // SHARED by operation-content decode (`SplitLinkDecodeTest` pins that
    // half) and this snapshot decode path (`PBTree.toCrdtTree`, reached here
    // through a hand-built `JSONElement.Tree` rather than through a real
    // Document/snapshot round trip, which has no way to produce a depth
    // hole on its own). A stored snapshot with a node whose depth names no
    // parent used to resolve through `?: continue`, silently trimming the
    // orphan instead of failing the decode (Migration: the server never
    // writes one, only a crafted/corrupt payload does).
    @Test
    fun `should reject a snapshot tree node with an unwritten parent depth`() {
        val actor = "000000000000000000000001"
        val root = treeNode {
            id = CrdtTreeNodeID(TimeTicket(1, 0u, actor), 0).toPBTreeNodeID()
            type = "r"
            depth = 0
        }
        val orphan = treeNode {
            id = CrdtTreeNodeID(TimeTicket(2, 0u, actor), 0).toPBTreeNodeID()
            type = "p"
            depth = 2 // Depth 1 was never written -- a hole.
        }
        val pbTree = tree {
            nodes.addAll(listOf(orphan, root))
            createdAt = TimeTicket(1, 0u, actor).toPBTimeTicket()
        }

        val exception = assertFailsWith<YorkieException> { pbTree.toCrdtTree() }

        assertEquals(ErrInvalidArgument, exception.code)
        assertEquals("invalid tree node depth: 2", exception.errorMessage)
    }

    // Port yorkie-js-sdk `snapshot_converter_test.ts` "should round-trip the
    // insertion links of split text nodes" (`ed25355e`, #1449, yorkie#2111).
    // Also the Android-only insPrevId round-trip through snapshotToBytes:
    // `toByteString()`/`toPBText()` is exactly the path `buildEnvelopeBytes`
    // (`Document.kt:1573`) and a Text nested in a pushed Object/Array value
    // both funnel through.
    @Test
    fun `should round-trip the insertion links of split text nodes`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "abcdef")
        }.await()
        document.updateAsync { root, _ ->
            (root["text"] as JsonText).edit(2, 4, "x")
        }.await()

        fun linksOf(text: CrdtText) = text.rgaTreeSplit.map { it.id to it.insertionPrev?.id }

        val before = linksOf(document.getRootObject()["text"] as CrdtText)
        assertTrue(before.any { (_, prev) -> prev != null }, "a split must leave an insertion link")

        val restored = (document.getRootObject()["text"] as CrdtText).toByteString()
            .let { PBJsonElement.parseFrom(it).toCrdtElement() as CrdtText }

        assertEquals(before, linksOf(restored))
    }

    @Test
    fun `should only emit an insertion link a decoder can resolve`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "abcdef")
        }.await()
        document.updateAsync { root, _ ->
            (root["text"] as JsonText).edit(2, 4, "x")
        }.await()
        document.updateAsync { root, _ ->
            (root["text"] as JsonText).edit(1, 2, "yz")
        }.await()

        val pbText = (document.getRootObject()["text"] as CrdtText).toPBText().text
        val emitted = mutableSetOf<RgaTreeSplitNodeID>()
        var linked = 0
        pbText.nodesList.forEach { node ->
            if (node.hasInsPrevId()) {
                linked++
                assertTrue(
                    node.insPrevId.toRgaTreeSplitNodeID() in emitted,
                    "forward or dangling insPrevId: ${node.insPrevId}",
                )
            }
            emitted += node.id.toRgaTreeSplitNodeID()
        }
        assertTrue(linked > 0, "at least one split must carry an insertion link")
    }

    @Test
    fun `should drop an insertion link that names no decoded node`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "abcdef")
        }.await()
        document.updateAsync { root, _ ->
            (root["text"] as JsonText).edit(2, 4, "x")
        }.await()

        val original = (document.getRootObject()["text"] as CrdtText).toPBText()
        val nodes = original.text.nodesList
        // Point the first node at the last one: a link no decoder can have
        // resolved by the time the first node is read.
        val forged = original.copy {
            text = text.copy {
                this.nodes.clear()
                this.nodes.addAll(
                    nodes.mapIndexed { index, node ->
                        if (index == 0) node.copy { insPrevId = nodes.last().id } else node
                    },
                )
            }
        }

        val decoded = forged.toCrdtElement() as CrdtText
        assertEquals(null, decoded.rgaTreeSplit.first().insertionPrev)
    }

    @Test
    fun `should drop an insertion link that names another insertion`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "abc")
        }.await()
        document.updateAsync { root, _ ->
            (root["text"] as JsonText).edit(3, 3, "def")
        }.await()

        val original = (document.getRootObject()["text"] as CrdtText).toPBText()
        val nodes = original.text.nodesList
        assertEquals(2, nodes.size)
        // The second insertion decodes after the first, so this link
        // resolves by exact id -- only the same-insertion check rejects it.
        val forged = original.copy {
            text = text.copy {
                this.nodes.clear()
                this.nodes.addAll(
                    nodes.mapIndexed { index, node ->
                        if (index == 1) node.copy { insPrevId = nodes.first().id } else node
                    },
                )
            }
        }

        val decoded = forged.toCrdtElement() as CrdtText
        assertEquals(null, decoded.rgaTreeSplit.last().insertionPrev)
    }

    @Test
    fun `should round-trip a ChangeID through binary`() {
        val changeID = ChangeID(
            clientSeq = 3u,
            lamport = 5,
            actor = "000000000000000000000001",
            versionVector = VersionVector(mapOf("000000000000000000000001" to 5L)),
        )

        val restored = changeID.toByteString().toChangeID()

        assertEquals(changeID.lamport, restored.lamport)
        assertEquals(changeID.actor, restored.actor)
        assertEquals(changeID.versionVector, restored.versionVector)
    }
}
