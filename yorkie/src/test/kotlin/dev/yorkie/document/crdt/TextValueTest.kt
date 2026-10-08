package dev.yorkie.document.crdt

import com.google.gson.JsonParser
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class TextValueTest {

    @Test
    fun `should handle escape string for attributes`() {
        val text = TextValue(
            """va"lue""",
            Rht().apply {
                set("""it"s""", """York\ie""", TimeTicket.InitialTimeTicket)
                set("its", "Yorkie", TimeTicket.InitialTimeTicket)
            },
        )
        assertEquals(
            """{"attrs":{"it\"s":"York\\ie","its":"Yorkie"},"val":"va\"lue"}""",
            text.toJson(),
        )
        val json = JsonParser.parseString(text.toJson()).asJsonObject
        assertEquals("""va"lue""", json.get("val").asString)
        val attrs = json.getAsJsonObject("attrs")
        assertEquals("""York\ie""", attrs.get("""it"s""").asString)
        assertEquals("Yorkie", attrs.get("its").asString)
    }

    private fun tick(lamport: Long) = TimeTicket(lamport, 0u, "000000000000000000000001")

    // A removed attribute belongs to docSize.gc, not to live
    // (JS text.ts:162-164) — its bytes must never enter a node's own
    // getDataSize, even though the RhtNode still physically sits inside
    // _attributes until GC'd.
    @Test
    fun `getDataSize excludes a removed attribute`() {
        val rht = Rht().apply {
            set("bold", "true", tick(1))
        }
        val withLiveAttr = TextValue("AB", rht)
        val sizeWithLiveAttr = withLiveAttr.getDataSize()

        rht.remove("bold", tick(2))
        val sizeAfterRemove = TextValue("AB", rht).getDataSize()

        val sizeWithNoAttr = TextValue("AB").getDataSize()
        assertEquals(sizeWithNoAttr, sizeAfterRemove)
        assertTrue(sizeWithLiveAttr.data > sizeAfterRemove.data)
    }

    // Once getDataSize (above) skips removed attributes, a
    // tombstoned attribute's bytes were never part of this node's own live
    // contribution, so its GC pair must carry gcOnlySize or registering it
    // would wrongly debit live for bytes it never held (JS text.ts:245-258).
    @Test
    fun `gcPairs carries gcOnlySize for a removed attribute`() {
        val rht = Rht().apply {
            set("bold", "true", tick(1))
        }
        val text = TextValue("AB", rht)
        rht.remove("bold", tick(2))

        val pairs = text.gcPairs
        assertEquals(1, pairs.size)
        val pair = pairs.single()
        assertSame(text, pair.parent)
        val removedAttr = requireNotNull(rht.getNodeMapByKey()["bold"])
        assertEquals(removedAttr.dataSize, pair.gcOnlySize)
    }

    // A split keeps the LEFT piece's value object by shortening
    // it IN PLACE (truncate), rather than replacing it — GC pairs already
    // registered against the object (keyed by identity) would otherwise be
    // orphaned. Proven here directly on TextValue: identity and the
    // attribute table survive a truncate; only content shortens.
    @Test
    fun `truncate shortens content in place and preserves attribute identity`() {
        val rht = Rht().apply {
            set("bold", "true", tick(1))
        }
        val text = TextValue("Hello World", rht)
        val attrBefore = text.attributesWithTimeTicket.first()

        text.truncate(5)

        assertEquals("Hello", text.content)
        assertEquals(5, text.length)
        assertSame(attrBefore, text.attributesWithTimeTicket.first())
    }
}
