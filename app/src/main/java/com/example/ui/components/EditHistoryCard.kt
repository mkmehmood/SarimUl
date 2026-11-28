package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.EditHistoryEntry
import com.example.ui.theme.LocalSarimPalette
import com.example.util.SarimFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EditHistoryCard(
  entries: List<EditHistoryEntry>,
  createdAt: Long?,
  onDeleteEntry: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (entries.isEmpty() && (createdAt == null || createdAt <= 0L)) return
  val palette = LocalSarimPalette.current
  var confirmDeleteIndex by remember { mutableStateOf<Int?>(null) }
  val dateTimeFmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US) }

  SarimCard(modifier = modifier) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          Icons.Rounded.History,
          contentDescription = null,
          tint = palette.cyan,
          modifier = Modifier.size(16.dp),
        )
        SarimFieldLabel("Edit History")
      }
      if (entries.isNotEmpty()) {
        SarimChip("${entries.size} edit${if (entries.size == 1) "" else "s"}", palette.cyan)
      }
    }

    if (entries.isEmpty()) {
      Text(
        "No edits recorded yet.",
        color = palette.textMuted,
        fontSize = 12.sp,
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEachIndexed { idx, entry ->
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = palette.surfaceElevated,
            border = BorderStroke(1.dp, palette.border),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
              verticalAlignment = Alignment.Top,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = if (entry.timestamp > 0L) dateTimeFmt.format(Date(entry.timestamp)) else "—",
                    color = palette.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  if (entry.by.isNotBlank()) {
                    SarimChip(entry.by, palette.green)
                  }
                }
                if (entry.changes.isNotEmpty()) {
                  entry.changes.forEach { ch ->
                    val fromTxt = ch.from.ifBlank { "—" }
                    val toTxt = ch.to.ifBlank { "—" }
                    Text(
                      text = "${ch.label}: $fromTxt → $toTxt",
                      color = palette.textPrimary,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Medium,
                    )
                  }
                } else if (entry.summary.isNotBlank()) {
                  Text(
                    text = entry.summary,
                    color = palette.textPrimary,
                    fontSize = 12.sp,
                  )
                }
              }
              IconButton(
                onClick = { confirmDeleteIndex = idx },
                modifier = Modifier
                  .size(48.dp)
                  .testTag("edit_history_delete_$idx"),
              ) {
                Icon(
                  Icons.Rounded.DeleteOutline,
                  contentDescription = "Delete history entry",
                  tint = palette.red,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }
        }
      }
    }

    if (createdAt != null && createdAt > 0L) {
      HorizontalDivider(color = palette.border)
      Text(
        text = "Added · ${dateTimeFmt.format(Date(createdAt))}",
        color = palette.textMuted,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
      )
    }
  }

  confirmDeleteIndex?.let { targetIdx ->
    AlertDialog(
      onDismissRequest = { confirmDeleteIndex = null },
      containerColor = palette.surface,
      title = { Text("Delete Edit Entry", color = palette.textPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "Remove this audit trail entry? Only the history log entry is deleted; current profile data is unchanged.",
          color = palette.textMuted,
          fontSize = 13.sp,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmDeleteIndex = null
            onDeleteEntry(targetIdx)
          },
          modifier = Modifier.testTag("confirm_delete_edit_history"),
        ) {
          Text("Delete", color = palette.red, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmDeleteIndex = null }) {
          Text("Cancel", color = palette.textMuted)
        }
      },
    )
  }
}
