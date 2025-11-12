package com.example.richtexteditor.ui.richtexteditor

import com.google.gson.annotations.SerializedName
import dev.yorkie.document.RgaTreeSplitNodeIDStruct
import dev.yorkie.document.RgaTreeSplitPosStruct
import dev.yorkie.document.TimeTicketStruct
import dev.yorkie.document.time.ActorID

/**
 * DTO classes for JSON serialization with Gson.
 * These classes use @SerializedName to ensure ProGuard doesn't break deserialization.
 */

/**
 * DTO representation of TimeTicket for JSON serialization
 */
data class TimeTicketDto(
    @SerializedName("lamport")
    val lamport: String,
    @SerializedName("delimiter")
    val delimiter: Int,
    @SerializedName("actorID")
    val actorID: String,
)

/**
 * DTO representation of RgaTreeSplitNodeID for JSON serialization
 */
data class RgaTreeSplitNodeIdDto(
    @SerializedName("createdAt")
    val createdAt: TimeTicketDto,
    @SerializedName("offset")
    val offset: Int,
)

/**
 * DTO representation of RgaTreeSplitPos for JSON serialization
 */
data class RgaTreeSplitPosDto(
    @SerializedName("id")
    val id: RgaTreeSplitNodeIdDto,
    @SerializedName("relativeOffset")
    val relativeOffset: Int,
) {
    /**
     * Converts this DTO to the SDK's RgaTreeSplitPosStruct
     */
    fun toRgaTreeSplitPosStruct(): RgaTreeSplitPosStruct {
        return RgaTreeSplitPosStruct(
            id = RgaTreeSplitNodeIDStruct(
                createdAt = TimeTicketStruct(
                    lamport = id.createdAt.lamport,
                    delimiter = id.createdAt.delimiter.toUInt(),
                    actorID = ActorID(id.createdAt.actorID),
                ),
                offset = id.offset,
            ),
            relativeOffset = relativeOffset,
        )
    }

    companion object {
        /**
         * Creates a DTO from the SDK's RgaTreeSplitPosStruct
         */
        fun fromRgaTreeSplitPosStruct(struct: RgaTreeSplitPosStruct): RgaTreeSplitPosDto {
            return RgaTreeSplitPosDto(
                id = RgaTreeSplitNodeIdDto(
                    createdAt = TimeTicketDto(
                        lamport = struct.id.createdAt.lamport,
                        delimiter = struct.id.createdAt.delimiter.toInt(),
                        actorID = struct.id.createdAt.actorID.value,
                    ),
                    offset = struct.id.offset,
                ),
                relativeOffset = struct.relativeOffset,
            )
        }
    }
}
