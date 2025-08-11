package dev.yorkie.util

data class DocSize(
    val live: DataSize,
    val gc: DataSize,
)

data class DataSize(
    val data: Int,
    val meta: Int,
)

/**
 * `totalDocSize` calculates the total size of a document.
 */
fun totalDocSize(docSize: DocSize?): Int {
    if (docSize == null) {
        return 0
    }
    return totalDataSize(docSize.live) + totalDataSize(docSize.gc)
}

/**
 * `totalDataSize` calculates the total size of a resource.
 */
fun totalDataSize(dataSize: DataSize): Int {
    return dataSize.data + dataSize.meta
}

/**
 * `addDataSizes` adds the size of a resource to the target resource.
 */
fun addDataSizes(origin: DataSize, vararg others: DataSize): DataSize {
    var data = origin.data
    var meta = origin.meta

    others.forEach {
        data += it.data
        meta += it.meta
    }
    return DataSize(
        data = data,
        meta = meta,
    )
}

/**
 * `subDataSize` subtracts the size of a resource from the target resource.
 */
fun subDataSize(origin: DataSize, other: DataSize): DataSize {
    return DataSize(
        data = origin.data - other.data,
        meta = origin.meta - other.meta,
    )
}
