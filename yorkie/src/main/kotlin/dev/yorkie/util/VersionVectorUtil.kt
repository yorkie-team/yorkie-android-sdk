package dev.yorkie.util

import dev.yorkie.document.time.VersionVector

fun versionVectorHelper(
    versionVector: VersionVector,
    actorData: Array<Pair<String, Long>>,
): Boolean {
    if (versionVector.size() != actorData.size) {
        return false
    }

    for ((actor, lamport) in actorData) {
        val vvLamport = versionVector.get(actor) ?: return false

        if (vvLamport != lamport) {
            return false
        }
    }

    return true
}
