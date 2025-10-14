package com.example.simultaneouscursors.model

data class CursorPosition(
    val xPos: Double = 0.0,
    val yPos: Double = 0.0,
)

data class CursorPresence(
    val name: String? = null,
    val cursorShape: CursorShape = CursorShape.CURSOR,
    val cursor: CursorPosition = CursorPosition(),
    val pointerDown: Boolean = false,
    val isMyself: Boolean = false,
)

data class ClientPresence(
    val clientID: String,
    val presence: CursorPresence,
)

enum class CursorShape(val displayName: String, val iconName: String) {
    HEART("Heart", "heart"),
    THUMBS("Thumbs", "thumbs"),
    PEN("Pen", "pen"),
    CURSOR("Cursor", "cursor"),
    ;

    companion object {
        fun fromString(value: String): CursorShape {
            return entries.find { it.iconName == value } ?: CURSOR
        }
    }
}
