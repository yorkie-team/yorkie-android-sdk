package dev.yorkie.document.crdt

import com.google.gson.JsonParser
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
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
}
