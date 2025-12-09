package dev.yorkie.core

import dev.yorkie.document.time.ActorID

/**
 * `ResourceStatus` represents the common status interface for attachable resources.
 */
enum class ResourceStatus {
    Detached,
    Attached,
    Removed,
}

interface ResourceEvent

/**
 * `Attachable` is an interface for resources that can be attached to a client.
 */
interface Attachable {
    /**
     * `getKey` returns the key of this resource.
     */
    fun getKey(): String

    /**
     * `getStatus` returns the status of this resource.
     */
    fun getStatus(): ResourceStatus

    fun applyStatus(status: ResourceStatus)

    /**
     * `setActor` sets the actor ID into this resource.
     */
    fun setActor(actorID: ActorID)

    /**
     * `hasLocalChanges` returns whether this resource has local changes to be synchronized.
     * Returns true for Document when there are uncommitted changes.
     * Returns false for Presence as it is server-managed.
     */
    fun hasLocalChanges(): Boolean

    /**
     * `publish` publishes an event to notify observers about changes in this resource.
     */
    fun publish(event: ResourceEvent)
}
