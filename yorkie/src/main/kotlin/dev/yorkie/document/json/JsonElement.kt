package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive

public interface JsonElement {

    companion object {

        @Suppress("UNCHECKED_CAST")
        internal fun <T : JsonElement> CrdtElement.toJsonElement(context: ChangeContext): T {
            return when (this) {
                is CrdtObject -> JsonObject(context, this)
                is CrdtArray -> JsonArray(context, this)
                is CrdtPrimitive -> JsonPrimitive(this)
                else -> error("unknown CrdtElement type: $this")
            } as T
        }
    }
}
