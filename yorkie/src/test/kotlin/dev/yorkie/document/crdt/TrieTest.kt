package dev.yorkie.document.crdt

import dev.yorkie.util.findPrefixes
import org.apache.commons.collections4.trie.PatriciaTrie
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TrieTest {
    private lateinit var trie: PatriciaTrie<String>
    private val philWords = listOf("phil", "philosophy", "philanthropy", "philadelphia")
    private val unWords = listOf("un", "undo", "unpack", "unhappy")
    private val otherWords = listOf("english", "hello")
    private val words = philWords + unWords + otherWords

    @Before
    fun setUp() {
        trie = PatriciaTrie()
    }

    @Test
    fun `should find words with specific prefixes`() {
        words.forEach { trie[it] = it }
        val philResult = trie.prefixMap("phil").values.toList()
        val unResult = trie.prefixMap("un").values.toList()
        assertEquals(philWords.sorted(), philResult)
        assertEquals(unWords.sorted(), unResult)
    }

    @Test
    fun `should find prefixes`() {
        words.forEach { trie[it] = it }
        val commonPrefixes = listOf("phil", "un") + otherWords
        assertEquals(commonPrefixes.sorted(), trie.findPrefixes().map { it.joinToString("") })
    }
}
