package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
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

  val countBadge = if (entries.isNotEmpty()) "${entries.size} edit${if (entries.size == 1) "" else "s"}" else ""
  val createdSummary = if (createdAt != null && createdAt > 0L) "Added ${dateTimeFmt.format(Date(createdAt))}" else ""

  SarimCollapsibleCard(
    title = "Edit History",
    subtitle = createdSummary,
    badgeText = countBadge,
    badgeColor = palette.cyan,
    leadingIcon = Icons.Rounded.History,
    iconTint = palette.cyan,
    tonalAccent = palette.cyan,
    initiallyExpanded = false,
    modifier = modifier,
  ) {
    if (entries.isEmpty()) {
      Text(
        "No edits recorded yet.",
        color = palette.textMuted,
        fontSize = 12.sp,
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val scheme = MaterialTheme.colorScheme
        entries.forEachIndexed { idx, entry ->
          Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
              verticalAlignment = Alignment.Top,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Box(
                modifier = Modifier
                  .padding(top = 4.dp)
                  .width(3.dp)
                  .height(26.dp)
                  .clip(RoundedCornerShape(50))
                  .background(palette.cyan.copy(alpha = 0.75f)),
              )
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
                      fontSize = 12.5.sp,
                      fontWeight = FontWeight.Medium,
                    )
                  }
                } else if (entry.summary.isNotBlank()) {
                  Text(
                    text = entry.summary,
                    color = palette.textPrimary,
                    fontSize = 12.5.sp,
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
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
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
      shape = RoundedCornerShape(32.dp),
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
