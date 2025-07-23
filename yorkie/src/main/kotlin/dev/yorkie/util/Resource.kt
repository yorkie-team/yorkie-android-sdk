package dev.yorkie.util

data class DocSize(
    val live: DataSize,
    val gc: DataSize,
)

data class DataSize(
    val data: Int,
    val meta: Int,
)
