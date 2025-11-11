package com.example.scheduler.ui.scheduler

/**
 * Actions that can be performed on the scheduler
 */
sealed class SchedulerAction {
    data class AddEvent(val date: String, val text: String) : SchedulerAction()
    data class UpdateEvent(val date: String, val text: String) : SchedulerAction()
    data class DeleteEvent(val date: String) : SchedulerAction()
    data class SetSelectedDate(val date: java.util.Date) : SchedulerAction()
    data class SetEventText(val text: String) : SchedulerAction()
    data class ShowEventDialog(val show: Boolean) : SchedulerAction()
    data class NavigateMonth(val direction: MonthDirection) : SchedulerAction()
}

enum class MonthDirection {
    PREVIOUS,
    NEXT,
}
