package dev.yorkie.presence

import dev.yorkie.core.Attachable
import dev.yorkie.core.ResourceEvent
import dev.yorkie.core.ResourceStatus
import dev.yorkie.util.createSingleThreadDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface ChannelEvent : ResourceEvent {
    /**
     * Current session count value. Zero for non-count events.
     */
    val sessionCount: Long

    data class Changed(
        override val sessionCount: Long,
    ) : ChannelEvent

    data class Initialized(
        override val sessionCount: Long,
    ) : ChannelEvent

    /**
     * Broadcast event received from a remote client on this channel key.
     */
    data class Broadcast(
        val actorID: String?,
        val topic: String,
        val payload: String,
    ) : ChannelEvent {
        override val sessionCount: Long = 0L
    }

    /**
     * Published when a RefreshChannel heartbeat fails non-recoverably.
     * Recoverable session expiry (ErrSessionNotFound) is handled internally
     * by a transparent re-attach and does not emit this event.
     */
    data class SyncError(
        val cause: Throwable,
    ) : ChannelEvent {
        override val sessionCount: Long = 0L
    }
}

class Channel(
    private val key: String,
) : Attachable {
    private val dispatcher: CoroutineDispatcher =
        createSingleThreadDispatcher("Channel($key)")

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var status = ResourceStatus.Detached
    private var actorID: String? = null
    private var sessionId: String? = null

    @Volatile
    private var sessionCount = 0L

    @Volatile
    private var seq = 0L

    private val _eventStream = MutableSharedFlow<ChannelEvent>()
    val eventStream = _eventStream.asSharedFlow()

    /**
     * `applyStatus` applies the channel status into this channel counter.
     */
    override fun applyStatus(status: ResourceStatus) {
        this.status = status
    }

    /**
     * `isAttached` returns whether this channel counter is attached or not.
     */
    fun isAttached(): Boolean {
        return status == ResourceStatus.Attached
    }

    /**
     * `getActorID` returns the actor ID of this channel counter.
     */
    fun getActorID(): String? {
        return actorID
    }

    /**
     * `getSessionId` returns the session ID from the server.
     */
    fun getSessionId(): String? {
        return sessionId
    }

    /**
     * `setSessionId` sets the session ID from the server, or clears it with
     * null so the next refresh runs as a first call. SDK-internal use.
     */
    fun setSessionId(sessionId: String?) {
        this.sessionId = sessionId
    }

    /**
     * `getSessionCount` returns the current online session count value.
     */
    fun getSessionCount(): Long {
        return sessionCount
    }

    /**
     * `getCount` returns the current online session count value.
     */
    @Deprecated(
        "Renamed to getSessionCount",
        replaceWith = ReplaceWith("getSessionCount()"),
    )
    fun getCount(): Long {
        return getSessionCount()
    }

    /**
     * `updateSessionCount` updates the session count and sequence number if the sequence is newer.
     * Returns true if the session count was updated, false if the update was ignored.
     */
    fun updateSessionCount(sessionCount: Long, seq: Long): Boolean {
        // Always accept initialization (seq === 0)
        if (seq == 0L || seq > this.seq) {
            this.sessionCount = sessionCount
            this.seq = seq
            return true
        }

        return false
    }

    /**
     * `updateCount` updates the session count and sequence number if the sequence is newer.
     * Returns true if the session count was updated, false if the update was ignored.
     */
    @Deprecated(
        "Renamed to updateSessionCount",
        replaceWith = ReplaceWith("updateSessionCount(sessionCount, seq)"),
    )
    fun updateCount(count: Long, seq: Long): Boolean {
        return updateSessionCount(count, seq)
    }

    /**
     * `getKey` returns the key of this channel counter.
     */
    override fun getKey(): String {
        return key
    }

    /**
     * `getStatus` returns the status of this channel counter.
     */
    override fun getStatus(): ResourceStatus {
        return status
    }

    /**
     * `setActor` sets the actor ID into this channel counter.
     */
    override fun setActor(actorID: String) {
        this.actorID = actorID
    }

    /**
     * `hasLocalChanges` returns whether this channel has local changes or not.
     * Channel is server-managed, so it always returns false.
     */
    override fun hasLocalChanges(): Boolean {
        return false
    }

    /**
     * `publish` publishes an event to all registered handlers.
     */
    override fun publish(event: ResourceEvent) {
        scope.launch {
            (event as? ChannelEvent)?.let {
                _eventStream.emit(it)
            }
        }
    }

    /**
     * `close` cancels the internal coroutine scope.
     */
    fun close() {
        scope.cancel()
    }
}

@Deprecated(
    "Renamed to Channel",
    replaceWith = ReplaceWith("Channel", "dev.yorkie.presence.Channel"),
)
typealias Presence = Channel

@Deprecated(
    "Renamed to ChannelEvent",
    replaceWith = ReplaceWith("ChannelEvent", "dev.yorkie.presence.ChannelEvent"),
)
typealias PresenceEvent = ChannelEvent
