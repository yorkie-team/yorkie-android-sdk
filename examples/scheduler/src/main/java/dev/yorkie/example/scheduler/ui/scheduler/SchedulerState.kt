package dev.yorkie.example.scheduler.ui.scheduler

import java.util.Date

/**
 * State of the scheduler application
 */
data class SchedulerState(
    val events: List<Event> = emptyList(),
    val selectedDate: Date = Date(),
    val displayMonth: Date = Date(),
    val eventText: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val showEventDialog: Boolean = false,
    val peers: List<String> = emptyList(),
)
