package dev.yorkie.util

import org.apache.commons.collections4.trie.PatriciaTrie

fun PatriciaTrie<String>.findPrefixes(): List<CharArray> {
    val paths = keys.toList()
    var prefix = ""
    return buildList {
        paths.forEachIndexed { index, path ->
            if (index == 0 || !path.startsWith(prefix)) {
                add(path.toCharArray())
                prefix = path
            }
        }
    }
}
