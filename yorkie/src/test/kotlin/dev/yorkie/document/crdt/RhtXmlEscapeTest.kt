package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Pins [Rht.toXml]'s attribute-escaping hardening (port JS
 * `attr_representation_test`'s XML case, `6932da6a`, #1447): a peer-chosen
 * key or value holding `& < > " '` must not forge markup in the rendered
 * XML. The two JS JSON-forging cases in the same upstream commit are
 * already covered on Android without code change -- attribute values are
 * always strings here and [Rht.toJson] already [escapeString]s both key and
 * value -- so only the XML half needs a new pin.
 */
class RhtXmlEscapeTest {

    private lateinit var target: Rht

    @Before
    fun setUp() {
        target = Rht()
    }

    @Test
    fun `cannot forge XML structure through a crafted attribute value`() {
        // Direct port of JS's `cannot forge XML structure through a tree
        // attribute`: unescaped, this value would close the attribute and
        // open a `<script>` element in every reader's rendered markup.
        target.set("color", "red\" onload=\"<script>", TimeTicket.InitialTimeTicket)

        val xml = target.toXml()

        assertEquals("color=\"red&quot; onload=&quot;&lt;script&gt;\"", xml)
        assertFalse(xml.contains("<script>"))
    }

    @Test
    fun `escapes every escapable character in both key and value`() {
        // Adversarial probe: a key AND a value each holding all five
        // escapable characters.
        target.set("a&b<c>d\"e'f", "x&y<z>w\"v'u", TimeTicket.InitialTimeTicket)

        assertEquals(
            "a&amp;b&lt;c&gt;d&quot;e&apos;f=\"x&amp;y&lt;z&gt;w&quot;v&apos;u\"",
            target.toXml(),
        )
    }

    @Test
    fun `round trip of plain values unchanged`() {
        target.set("bold", "true", TimeTicket.InitialTimeTicket)
        target.set("color", "red", TimeTicket.InitialTimeTicket)

        assertEquals("bold=\"true\" color=\"red\"", target.toXml())
    }
}
