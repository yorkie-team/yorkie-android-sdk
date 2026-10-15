package dev.yorkie.document.change

import dev.yorkie.document.crdt.CrdtContainer
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.GCPair
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize

/**
 * Used to record the context of modification when editing a document.
 * Each time we add an operation, a new time ticket is issued.
 * Finally returns a [Change] after the modification has been completed.
 */
internal class ChangeContext(
    val prevId: ChangeID,
    val root: CrdtRoot,
    val message: String? = null,
) {
    var presenceChange: PresenceChange? = null

    /**
     * Keys touched by [dev.yorkie.document.presence.DocPresence.put] in this
     * context, accumulated across multiple calls within the same update
     * (presence_patch JS case 3: two `put()`s in one updater contribute a
     * single [PresenceChange.Patch] carrying both keys' latest values, not
     * two separate diffs). `Document`'s commit-time Put-vs-Patch decision
     * (D2, spec 040) reads this only when [presenceChange] is a
     * [PresenceChange.Put] it may rewrite into a [PresenceChange.Patch];
     * `DocPresence` is the only writer. Cleared by `clear()` since a cleared
     * presence has no keys left to patch.
     */
    var presencePatchKeys: MutableSet<String> = mutableSetOf()

    /**
     * Whether this context has already recorded a [PresenceChange.Clear] -- a cleared
     * presence has no base left for a later [dev.yorkie.document.presence.DocPresence.put]
     * in the SAME update to fold a patch onto (CJ-MED-1, round 4 fix): the server still
     * holds whatever base it had before this update started, so a Patch recorded after a
     * `clear()` would fold onto THAT stale base and leave every peer holding keys this
     * update meant to drop. Once raised, [Document]'s commit-time decision
     * ([Document.resolvePresenceChangeForCommit], D2) must not rewrite the provisional
     * [PresenceChange.Put] into a [PresenceChange.Patch] for the rest of this context, even
     * though a later `put()` re-populates [presencePatchKeys]. Mirrors JS `b50f8900`
     * #1477 `Channel.baseless` (`DocPresence` is the only writer, set by `clear()`).
     */
    var presenceBaseless: Boolean = false
    private val operations: MutableList<Operation> = mutableListOf()
    private var delimiter: UInt = TimeTicket.INITIAL_DELIMITER

    /**
     * returns the next ID of this context. It will be set to the
     * document for the next change.returns the next ID of this context.
     */
    private val nextId: ChangeID = prevId.next()

    /**
     * Returns whether this context has change or not.
     */
    val hasChange: Boolean
        get() = operations.isNotEmpty() || presenceChange != null

    /**
     *  Returns the last time ticket issued in this context.
     */
    val lastTimeTicket: TimeTicket
        get() = nextId.createTimeTicket(delimiter)

    /**
     * Pushes the given operation to this context.
     */
    fun push(operation: Operation) {
        operations.add(operation)
    }

    /**
     * Registers the given element to the root.
     */
    fun registerElement(element: CrdtElement, parent: CrdtContainer) {
        root.registerElement(element, parent)
    }

    /**
     * Registers removed element for garbage collection.
     */
    fun registerRemovedElement(element: CrdtElement) {
        root.registerRemovedElement(element)
    }

    /**
     *  Registers the given pair to hash table.
     */
    fun registerGCPair(pair: GCPair<*>) {
        root.registerGCPair(pair)
    }

    /**
     * `toChange` creates a new instance of Change in this context.
     */
    fun toChange(): Change {
        val id = if (isPresenceOnlyChange()) {
            prevId.next(true)
        } else {
            nextId
        }
        return Change(
            id = id,
            operations = operations,
            presenceChange = presenceChange,
            message = message,
        )
    }

    /**
     * Creates a [TimeTicket] to be used to create a new [Operation].
     */
    fun issueTimeTicket(): TimeTicket {
        delimiter++
        return nextId.createTimeTicket(delimiter)
    }

    /**
     * `getNextID` returns the next ID of this context. It will be set to the
     * document for the next change.returns the next ID of this context.
     */
    fun getNextId(): ChangeID {
        return if (isPresenceOnlyChange()) {
            prevId
                .next(true)
                .setLamport(prevId.lamport)
                .setVersionVector(prevId.versionVector)
        } else {
            nextId
        }
    }

    /**
     * `isPresenceOnlyChange` returns whether this context is only for presence
     * change or not.
     */
    fun isPresenceOnlyChange(): Boolean {
        return this.operations.isEmpty()
    }

    /**
     * `acc` accumulates the given DataSize to Live size of the root.
     */
    fun acc(diff: DataSize) {
        root.acc(diff)
    }

    /**
     * `accGC` accumulates the given DataSize to the gc size of the root.
     * Mirrors [acc] for the gc half of a style result's DocSize: a style
     * op's gc-bound attribute writes need somewhere to fold into besides
     * live.
     */
    fun accGC(diff: DataSize) {
        root.accGC(diff)
    }

    /**
     * `accMovedElement` books the size a move added to the moved element. See
     * [CrdtRoot.accMovedElement].
     */
    fun accMovedElement(element: CrdtElement, diff: DataSize) {
        root.accMovedElement(element, diff)
    }
}
