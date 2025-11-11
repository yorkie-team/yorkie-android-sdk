package com.example.todomvc.ui.todo

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.todomvc.R

@Composable
fun TodoToggleAll(
    allCompleted: Boolean,
    onToggleAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggleAll,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(id = R.string.mark_all_complete),
                tint = if (allCompleted) Color(0xFF737373) else Color(0xFFE6E6E6),
                modifier = Modifier.size(32.dp),
            )
        }

        Text(
            text = stringResource(id = R.string.mark_all_complete),
            style = MaterialTheme.typography.body2,
            color = Color(0xFF737373),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
