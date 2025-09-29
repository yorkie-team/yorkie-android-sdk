package com.example.scheduler.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.scheduler.Event
import com.example.scheduler.R
import com.example.scheduler.SchedulerAction
import com.example.scheduler.SchedulerState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SchedulerApp(state: SchedulerState, onAction: (SchedulerAction) -> Unit) {
    if (state.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    } else if (state.error != null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.colors.error,
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            // Peers indicator
            if (state.peers.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.peers_online, state.peers.joinToString(", ")),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Calendar
            CalendarView(
                selectedDate = state.selectedDate,
                displayMonth = state.displayMonth,
                events = state.events,
                onDateSelected = { date ->
                    onAction(SchedulerAction.SetSelectedDate(date))
                },
                onNavigateMonth = { direction ->
                    onAction(SchedulerAction.NavigateMonth(direction))
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Selected date info
            val dateFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
            Text(
                text = stringResource(
                    id = R.string.selected_date,
                    dateFormat.format(state.selectedDate),
                ),
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Events for selected date
            val selectedDateStr = Event.parseDate(state.selectedDate)
            val dayEvents = state.events.filter { it.date == selectedDateStr }

            if (dayEvents.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_events),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn {
                    items(dayEvents) { event ->
                        EventItem(
                            event = event,
                            onEdit = { text ->
                                onAction(SchedulerAction.UpdateEvent(event.date, text))
                            },
                            onDelete = {
                                onAction(SchedulerAction.DeleteEvent(event.date))
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Add event section
            EventInputSection(
                eventText = state.eventText,
                onTextChange = { text ->
                    onAction(SchedulerAction.SetEventText(text))
                },
                onAddEvent = {
                    onAction(SchedulerAction.AddEvent(selectedDateStr, state.eventText))
                    onAction(SchedulerAction.SetEventText(""))
                },
            )
        }
    }
}
