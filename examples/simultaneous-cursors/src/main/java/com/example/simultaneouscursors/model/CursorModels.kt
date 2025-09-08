package com.example.simultaneouscursors.model

import androidx.compose.runtime.Immutable

@Immutable
data class CursorPosition(
    val xPos: Float = 0f,
    val yPos: Float = 0f
)

@Immutable
data class CursorPresence(
    val cursorShape: CursorShape = CursorShape.CURSOR,
    val cursor: CursorPosition = CursorPosition(),
    val pointerDown: Boolean = false
)

@Immutable
data class ClientPresence(
    val clientID: String,
    val presence: CursorPresence
)

enum class CursorShape(val displayName: String, val iconName: String) {
    HEART("Heart", "heart"),
    THUMBS("Thumbs", "thumbs"),
    PEN("Pen", "pen"),
    CURSOR("Cursor", "cursor");

    companion object {
        fun fromString(value: String): CursorShape {
            return entries.find { it.iconName == value } ?: CURSOR
        }
    }
} 