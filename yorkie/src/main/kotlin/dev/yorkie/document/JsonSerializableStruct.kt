package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitPos
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket

internal interface JsonSerializable<I, O : JsonSerializable.Struct<I>> {

    interface Struct<I> {

        fun toOriginal(): I
    }

    fun toStruct(): O
}

/**
* [CrdtTreePosStruct] represents the structure of [CrdtTreePos].
* It is used to serialize and deserialize the CRDTTreePos.
*/
public data class CrdtTreePosStruct(
    val createdAt: TimeTicketStruct,
    val offset: Int,
) : JsonSerializable.Struct<CrdtTreePos> {

    override fun toOriginal(): CrdtTreePos {
        return CrdtTreePos(createdAt.toOriginal(), offset)
    }
}

/**
 * [RgaTreeSplitPosStruct] is a structure represents the meta data of the node pos.
 * It is used to serialize and deserialize the node pos.
 */
public data class RgaTreeSplitPosStruct(
    val id: RgaTreeSplitNodeIDStruct,
    val relativeOffset: Int,
) : JsonSerializable.Struct<RgaTreeSplitPos> {

    override fun toOriginal(): RgaTreeSplitPos {
        return RgaTreeSplitPos(id.toOriginal(), relativeOffset)
    }
}

/**
 * [RgaTreeSplitNodeIDStruct] is a structure represents the meta data of the node id.
 * It is used to serialize and deserialize the node id.
 */
public data class RgaTreeSplitNodeIDStruct(
    val createdAt: TimeTicketStruct,
    val offset: Int,
) : JsonSerializable.Struct<RgaTreeSplitNodeID> {

    override fun toOriginal(): RgaTreeSplitNodeID {
        return RgaTreeSplitNodeID(createdAt.toOriginal(), offset)
    }
}

/**
 * [TimeTicketStruct] is a structure represents the meta data of the ticket.
 * It is used to serialize and deserialize the ticket.
 */
public data class TimeTicketStruct(
    val lamport: String,
    val delimiter: UInt,
    val actorID: ActorID,
) : JsonSerializable.Struct<TimeTicket> {

    override fun toOriginal(): TimeTicket {
        return TimeTicket(lamport.toLong(), delimiter, actorID)
    }
}
