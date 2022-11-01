package dev.yorkie.core

import dev.yorkie.document.Document
import dev.yorkie.document.time.ActorID

public data class PeerStatus(
    public val documentKey: Document.Key,
    public val actorId: ActorID,
    public val presenceInfo: PresenceInfo,
)

public data class PresenceInfo(
    public val clock: Int,
    public val data: Map<String, String>,
)
