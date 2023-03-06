package com.example.texteditor

import androidx.annotation.ColorInt

data class PeerSelectionInfo(
    @ColorInt val color: Int,
    val prevSelection: Pair<Int, Int>? = null,
)
