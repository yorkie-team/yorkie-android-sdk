package dev.yorkie.api

import dev.yorkie.api.v1.versionVector
import dev.yorkie.document.time.VersionVector

internal typealias PBVersionVector = dev.yorkie.api.v1.VersionVector

internal fun PBVersionVector.toVersionVector(): VersionVector {
    return VersionVector(
        vectorMap = vectorMap.mapKeys {
            bytesToHex(base64ToByteArray(it.key))
        },
    )
}

internal fun VersionVector.toPBVersionVector(): PBVersionVector {
    return versionVector {
        vectorMap.forEach { (actorID, lamport) ->
            val base64ActorID = uint8ArrayToBase64(hexToBytes(actorID))
            vector.put(base64ActorID, lamport)
        }
    }
}
