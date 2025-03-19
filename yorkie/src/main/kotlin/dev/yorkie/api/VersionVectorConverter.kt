package dev.yorkie.api

import dev.yorkie.api.v1.versionVector
import dev.yorkie.document.time.VersionVector


internal typealias PBVersionVector = dev.yorkie.api.v1.VersionVector

internal fun PBVersionVector.toVersionVector(): VersionVector {
    return VersionVector(
        vectorMap = vectorMap
    )
}

internal fun VersionVector.toPBVersionVector(): PBVersionVector {
    return versionVector {
        vector.putAll(vectorMap)
    }
}
