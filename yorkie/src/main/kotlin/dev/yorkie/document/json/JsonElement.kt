package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject

/**
 * TODO(skhugh): this is temporary and subjected to change when other JsonElements are implemented
 */
public interface JsonElement {

    companion object {

        internal fun CrdtElement.toJsonElement(context: ChangeContext): JsonElement {
            return JsonObject(context, this as CrdtObject)
        }
    }
}
