package dev.yorkie.document

import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

public class Document {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val root: CrdtRoot = CrdtRoot(CrdtObject.create(TimeTicket.InitialTimeTicket))
    private val localChanges = mutableListOf<Change>()
    private val eventStream = MutableSharedFlow<DocEvent<*>>()
    private var changeID = ChangeID.InitialChangeID
    private var clone: CrdtRoot? = null

    public fun update(
        message: String? = null,
        updater: (root: JsonObject) -> Unit,
    ) {
        val clone = ensureClone()
        val context = ChangeContext(
            id = changeID.next(),
            root = clone,
            message = message,
        )

        runCatching {
            val proxy = JsonObject(context, clone.rootObject)
            updater.invoke(proxy)
        }.onFailure {
            this.clone = null
            YorkieLogger.e("Document.update", it.message.orEmpty())
        }

        if (!context.hasOperations) {
            return
        }
        val change = context.getChange()
        change.execute(root)
        localChanges += change
        changeID = change.id

        scope.launch {
            eventStream.emit(change.asLocal())
        }
    }

    private fun ensureClone(): CrdtRoot {
        return clone ?: root.deepCopy().also { clone = it }
    }

    private fun Change.createPaths(): List<String> {
        TODO("need trie")
    }

    private fun Change.asLocal() = DocEvent.LocalChange(
        listOf(DocEvent.ChangeInfo(this, createPaths())),
    )

    public sealed class DocEvent<T>(val value: T) {

        public class LocalChange internal constructor(
            value: List<ChangeInfo>,
        ) : DocEvent<List<ChangeInfo>>(value)

        public class ChangeInfo(val change: Change, val paths: List<String>)
    }
}
