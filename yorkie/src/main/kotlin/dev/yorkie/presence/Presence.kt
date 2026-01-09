package dev.yorkie.presence

import dev.yorkie.core.Attachable
import dev.yorkie.core.ResourceEvent
import dev.yorkie.core.ResourceStatus
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.createSingleThreadDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface PresenceEvent : ResourceEvent {
    /**
     * `count` is the current count value.
     */
    val count: Long

    data class Changed(
        override val count: Long,
    ) : PresenceEvent

    data class Initialized(
        override val count: Long,
    ) : PresenceEvent
}

class Presence(
    private val key: String,
) : Attachable {
    private val dispatcher: CoroutineDispatcher =
        createSingleThreadDispatcher("Presence($key)")

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var status = ResourceStatus.Detached
    private var actorID: ActorID? = null
    private var presenceId: String? = null

    @Volatile
    private var count = 0L

    @Volatile
    private var seq = 0L

    private val _eventStream = MutableSharedFlow<PresenceEvent>()
    val eventStream = _eventStream.asSharedFlow()

    /**
     * `applyStatus` applies the presence status into this presence counter.
     */
    override fun applyStatus(status: ResourceStatus) {
        this.status = status
    }

    /**
     * `isAttached` returns whether this presence counter is attached or not.
     */
    fun isAttached(): Boolean {
        return status == ResourceStatus.Attached
    }

    /**
     * `getActorID` returns the actor ID of this presence counter.
     */
    fun getActorID(): ActorID? {
        return actorID
    }

    /**
     * `getPresenceID` returns the presence ID from the server.
     */
    fun getPresenceId(): String? {
        return presenceId
    }

    /**
     * `setPresenceID` sets the presence ID from the server.
     */
    fun setPresenceId(presenceId: String) {
        this.presenceId = presenceId
    }

    /**
     * `getCount` returns the current count value.
     */
    fun getCount(): Long {
        return count
    }

    /**
     * `updateCount` updates the count and sequence number if the sequence is newer.
     * Returns true if the count was updated, false if the update was ignored.
     */
    fun updateCount(count: Long, seq: Long): Boolean {
        // Always accept initialization (seq === 0)
        if (seq == 0L || seq > this.seq) {
            this.count = count
            this.seq = seq
            return true
        }

        return false
    }

    /**
     * `getKey` returns the key of this presence counter.
     */
    override fun getKey(): String {
        return key
    }

    /**
     * `getStatus` returns the status of this presence counter.
     */
    override fun getStatus(): ResourceStatus {
        return status
    }

    /**
     * `setActor` sets the actor ID into this presence counter.
     */
    override fun setActor(actorID: ActorID) {
        this.actorID = actorID
    }

    /**
     * `hasLocalChanges` returns whether this presence has local changes or not.
     * Presence is server-managed, so it always returns false.
     */
    override fun hasLocalChanges(): Boolean {
        return false
    }

    /**
     * `publish` publishes an event to all registered handlers.
     */
    override fun publish(event: ResourceEvent) {
        scope.launch {
            (event as? PresenceEvent)?.let {
                _eventStream.emit(it)
            }
        }
    }
}
