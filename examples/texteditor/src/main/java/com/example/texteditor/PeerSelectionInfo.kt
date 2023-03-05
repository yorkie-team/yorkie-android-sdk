package com.example.texteditor

data class PeerSelectionInfo(
    val color: Int,
    val prevSelection: Pair<Int, Int>? = null,
)
