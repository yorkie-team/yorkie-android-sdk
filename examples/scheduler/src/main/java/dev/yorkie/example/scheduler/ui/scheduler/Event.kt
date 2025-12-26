package dev.yorkie.example.scheduler.ui.scheduler

import java.util.Calendar
import java.util.Date

/**
 * Represents a calendar event with date and text content
 */
data class Event(
    // Format: DD-MM-YY
    val date: String,
    val text: String,
) {
    companion object {
        /**
         * Parse date from Date object to DD-MM-YY format
         */
        fun parseDate(date: Date): String {
            val calendar = Calendar.getInstance()
            calendar.time = date

            val day = calendar.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
            val month = (calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
            val year = calendar.get(Calendar.YEAR).toString().takeLast(2)

            return "$day-$month-$year"
        }
    }
}
