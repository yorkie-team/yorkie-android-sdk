package dev.yorkie.example.scheduler.ui.scheduler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yorkie.example.scheduler.ui.theme.CalendarEvent
import dev.yorkie.example.scheduler.ui.theme.CalendarSelected
import dev.yorkie.example.scheduler.ui.theme.CalendarToday
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun CalendarView(
    selectedDate: Date,
    displayMonth: Date,
    events: List<Event>,
    onDateSelected: (Date) -> Unit,
    onNavigateMonth: (MonthDirection) -> Unit,
) {
    val calendar = Calendar.getInstance()
    val currentMonth = calendar.get(Calendar.MONTH)
    val currentYear = calendar.get(Calendar.YEAR)

    calendar.time = displayMonth
    val displayMonthValue = calendar.get(Calendar.MONTH)
    val displayYear = calendar.get(Calendar.YEAR)

    // Get first day of month and number of days
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

    val today = Calendar.getInstance()
    val isCurrentMonth = displayMonthValue == currentMonth && displayYear == currentYear

    Column {
        // Month/Year header with navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onNavigateMonth(MonthDirection.PREVIOUS) },
            ) {
                Text(
                    text = "◀",
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(displayMonth),
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
            )

            IconButton(
                onClick = { onNavigateMonth(MonthDirection.NEXT) },
            ) {
                Text(
                    text = "▶",
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Day headers
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = day,
                        style = MaterialTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Calendar grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
        ) {
            // Empty cells for days before month starts
            items((firstDayOfWeek - 1) % 7) {
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(2.dp),
                )
            }

            // Days of the month
            items(daysInMonth) { day ->
                val dayNumber = day + 1
                val date = Calendar.getInstance().apply {
                    set(displayYear, displayMonthValue, dayNumber)
                }.time

                val dateStr = Event.parseDate(date)
                val hasEvent = events.any {
                    it.date == dateStr
                }
                val isSelected = dateStr == Event.parseDate(selectedDate)
                val isToday = isCurrentMonth && dayNumber == today.get(Calendar.DAY_OF_MONTH)

                CalendarDay(
                    day = dayNumber,
                    hasEvent = hasEvent,
                    isSelected = isSelected,
                    isToday = isToday,
                    onClick = { onDateSelected(date) },
                )
            }
        }
    }
}

@Composable
private fun CalendarDay(
    day: Int,
    hasEvent: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = when {
        isSelected -> CalendarSelected
        isToday -> CalendarToday
        else -> Color.Transparent
    }

    val borderColor = if (isSelected) CalendarSelected else Color.Transparent

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.body2,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color.White else MaterialTheme.colors.onSurface,
            )

            if (hasEvent) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(CalendarEvent),
                )
            }
        }
    }
}
