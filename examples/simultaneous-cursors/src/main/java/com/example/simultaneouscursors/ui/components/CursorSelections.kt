package com.example.simultaneouscursors.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.simultaneouscursors.R
import com.example.simultaneouscursors.model.CursorShape

@Composable
fun CursorSelections(
    selectedCursorShape: CursorShape,
    clientsCount: Int,
    onCursorShapeSelect: (CursorShape) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .background(
                color = MaterialTheme.colors.surface,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Cursor shape selection
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CursorShape.entries.forEach { shape ->
                CursorShapeButton(
                    cursorShape = shape,
                    isSelected = selectedCursorShape == shape,
                    onClick = { onCursorShapeSelect(shape) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Users count
        Text(
            text = if (clientsCount == 1) {
                stringResource(R.string.users_count_single)
            } else {
                stringResource(R.string.users_count_multiple, clientsCount)
            },
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
        )
    }
}

@Composable
private fun CursorShapeButton(
    cursorShape: CursorShape,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colors.primary.copy(alpha = 0.1f)
    } else {
        MaterialTheme.colors.surface
    }

    val borderColor = if (isSelected) {
        MaterialTheme.colors.primary
    } else {
        MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable { onClick() }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CursorIcon(
            cursorShape = cursorShape,
            modifier = Modifier
                .size(24.dp),
        )
    }
}
