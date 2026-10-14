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

    /**
     * A JS peer's `string` attribute is JSON-encoded on the wire, so
     * `color="red"` is stored as the four characters `"red"`. JS `toXML`
     * unwraps it back through `parseAttrValue` before rendering (port JS
     * `document/crdt/tree.ts` ~L1181-1203, `6932da6a`, #1447); unwrapped
     * here too, instead of rendering the quotes literally.
     */
    @Test
    fun `a JSON-quoted string value renders unquoted`() {
        target.set("color", "\"red\"", TimeTicket.InitialTimeTicket)

        assertEquals("color=\"red\"", target.toXml())
    }

    /**
     * `parseAttrValue` only unwraps when the parsed JSON value is a
     * `string`; an object, number, or boolean is rendered exactly as
     * stored, matching JS's `typeof obj === 'string' ? obj : n.getValue()`.
     */
    @Test
    fun `a JSON object, number, or boolean value stays raw`() {
        target.set("img", "{\"src\":\"yorkie.png\"}", TimeTicket.InitialTimeTicket)
        target.set("size", "12", TimeTicket.InitialTimeTicket)
        target.set("bold", "true", TimeTicket.InitialTimeTicket)

        assertEquals(
            "bold=\"true\" img=\"{&quot;src&quot;:&quot;yorkie.png&quot;}\" size=\"12\"",
            target.toXml(),
        )
    }

    /**
     * Unwrapping runs before escaping, so a JSON-quoted string whose
     * content itself holds markup-forging characters is escaped exactly
     * once -- the unwrap cannot hand `escapeXmlAttr` already-escaped text
     * to double-escape, nor can it skip escaping and let the unwrapped
     * content forge markup.
     */
    @Test
    fun `escaping applies to the value after it is unwrapped`() {
        target.set("color", "\"red\\\" onload=\\\"<script>\"", TimeTicket.InitialTimeTicket)

        val xml = target.toXml()

        assertEquals("color=\"red&quot; onload=&quot;&lt;script&gt;\"", xml)
        assertFalse(xml.contains("<script>"))
    }

    /**
     * A value that is quote-bounded but not valid JSON inside (an
     * unescaped `"` splits it into two literals, which `JSON.parse` would
     * reject) falls back to the raw stored text, matching `parseAttrValue`'s
     * catch branch.
     */
    @Test
    fun `malformed JSON falls back to the raw stored value`() {
        target.set("color", "\"a\"+\"b\"", TimeTicket.InitialTimeTicket)

        assertEquals("color=\"&quot;a&quot;+&quot;b&quot;\"", target.toXml())
    }
}
