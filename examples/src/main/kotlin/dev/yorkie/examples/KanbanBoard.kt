package dev.yorkie.examples

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class KanbanColumn(
    val title: String,
    val cards: List<Card>,
)

data class Card(val title: String)

@Composable
fun KanbanBoard(
    kanbanColumns: List<KanbanColumn>,
    onNewColumnAdded: (KanbanColumn) -> Unit,
    onNewCardAdded: (KanbanColumn, Card) -> Unit,
    onCardDeleted: (String) -> Unit,
) {
    LazyColumn {
        items(kanbanColumns) { kanbanColumn ->
            KanbanColumn(kanbanColumn, onNewCardAdded, onCardDeleted)
        }
        item {
            KanbanAddColumn(onNewColumnAdded)
        }
    }
}

@Composable
fun KanbanColumn(
    kanbanColumn: KanbanColumn,
    onNewCardAdded: (KanbanColumn, Card) -> Unit,
    onCardDeleted: (String) -> Unit,
) {
    val height = (kanbanColumn.cards.size + 2) * 60
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .height(height.dp)
            .background(
                color = Color.LightGray,
                shape = RoundedCornerShape(4.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = kanbanColumn.title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            TextButton(onClick = { onCardDeleted(kanbanColumn.title) }) {
                Text(text = "❌", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
        LazyColumn {
            items(kanbanColumn.cards) { card ->
                KanbanCard(card.title)
            }
        }
        KanbanAddCard(kanbanColumn, onNewCardAdded)
    }
}

@Composable
fun KanbanCard(title: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colors.onPrimary,
        ),
        modifier = Modifier.padding(8.dp),
    ) {
        Box(
            modifier = Modifier.height(45.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp),
                textAlign = TextAlign.Start,
                fontSize = 14.sp,
                text = title,
            )
        }
    }
}

@Composable
fun KanbanAddCard(
    column: KanbanColumn,
    onNewCardAdded: (KanbanColumn, Card) -> Unit,
) {
    var newTitle by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextField(
            modifier = Modifier
                .padding(8.dp)
                .height(45.dp),
            shape = RoundedCornerShape(4.dp),
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            value = newTitle,
            onValueChange = { newTitle = it },
        )
        TextButton(
            onClick = {
                onNewCardAdded(column, Card(newTitle))
                newTitle = ""
            },
        ) {
            Text("+ Add")
        }
    }
}

@Composable
fun KanbanAddColumn(onNewColumnAdded: (KanbanColumn) -> Unit) {
    var newTitle by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .background(
                color = Color.LightGray,
                shape = RoundedCornerShape(4.dp),
            ),
    ) {
        TextButton(
            onClick = {
                onNewColumnAdded(KanbanColumn(newTitle, emptyList()))
                newTitle = ""
            },
        ) {
            Text("+ Add another list")
        }
        TextField(
            modifier = Modifier
                .padding(8.dp)
                .height(45.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            value = newTitle,
            onValueChange = { newTitle = it },
        )
    }
}
