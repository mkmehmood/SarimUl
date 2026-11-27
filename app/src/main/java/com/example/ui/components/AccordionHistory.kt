package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.SarimFormat

data class HistoryRecordDisplay<T>(
  val item: T,
  val dateEpoch: Long,
  val title: String,
  val subtitle: String,
  val amountText: String,
  val amountColor: Color,
  val chipText: String = "",
  val chipColor: Color? = null,
  val showStripe: Boolean = false,
  val showDots: Boolean = false,
)

@Composable
fun <T> AccordionHistoryList(
  records: List<HistoryRecordDisplay<T>>,
  yearSummary: (List<HistoryRecordDisplay<T>>) -> String,
  monthSummary: (List<HistoryRecordDisplay<T>>) -> String,
  daySummary: (List<HistoryRecordDisplay<T>>) -> String,
  emptyTitle: String = "No records found",
  emptySubtitle: String = "No records found for this selection.",
  onItemClick: ((HistoryRecordDisplay<T>) -> Unit)? = null,
  expandedContent: @Composable (HistoryRecordDisplay<T>) -> Unit = {},
) {
  val palette = LocalSarimPalette.current
  if (records.isEmpty()) {
    SarimEmptyState(emptyTitle, emptySubtitle)
    return
  }

  val byYear = remember(records) {
    records.groupBy { SarimFormat.formatYear(it.dateEpoch) }.toSortedMap(compareByDescending { it })
  }
  var expandedYears by remember { mutableStateOf(setOf<Int>()) }
  var expandedMonths by remember { mutableStateOf(setOf<String>()) }
  var expandedDays by remember { mutableStateOf(setOf<String>()) }
  var expandedItemIds by remember { mutableStateOf(setOf<String>()) }

  // Auto-open the most recent year on first display
  LaunchedEffect(byYear.keys) {
    val firstYear = byYear.keys.firstOrNull()
    if (expandedYears.isEmpty() && firstYear != null) {
      expandedYears = setOf(firstYear)
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    byYear.forEach { (year, yearItems) ->
      val yearOpen = year in expandedYears
      Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border),
      ) {
        Column {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .defaultMinSize(minHeight = 48.dp)
              .clickable {
                expandedYears = if (yearOpen) expandedYears - year else expandedYears + year
              }
              .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(year.toString(), color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(yearSummary(yearItems), color = palette.green, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(6.dp))
            Icon(if (yearOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = palette.textMuted)
          }

          AnimatedVisibility(visible = yearOpen) {
            val byMonth = yearItems.groupBy { SarimFormat.formatMonthYear(it.dateEpoch) }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              byMonth.forEach { (monthLabel, monthItems) ->
                val mKey = "$year-$monthLabel"
                val monthOpen = mKey in expandedMonths
                Surface(
                  shape = RoundedCornerShape(16.dp),
                  color = palette.surfaceElevated,
                  border = BorderStroke(1.dp, palette.border),
                ) {
                  Column {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable {
                          expandedMonths = if (monthOpen) expandedMonths - mKey else expandedMonths + mKey
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(monthLabel, color = palette.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(monthSummary(monthItems), color = palette.textMuted, fontSize = 11.sp)
                      }
                      Icon(if (monthOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = palette.textMuted)
                    }

                    AnimatedVisibility(visible = monthOpen) {
                      val byDay = monthItems.groupBy { SarimFormat.formatDate(it.dateEpoch) }
                      Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        byDay.forEach { (dayLabel, dayItems) ->
                          val dKey = "$mKey-$dayLabel"
                          val dayOpen = dKey in expandedDays
                          Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.surface,
                            border = BorderStroke(1.dp, palette.border),
                          ) {
                            Column {
                              Row(
                                modifier = Modifier
                                  .fillMaxWidth()
                                  .defaultMinSize(minHeight = 40.dp)
                                  .clickable {
                                    expandedDays = if (dayOpen) expandedDays - dKey else expandedDays + dKey
                                  }
                                  .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                              ) {
                                Text(dayLabel, color = palette.cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(daySummary(dayItems), color = palette.textMuted, fontSize = 11.sp)
                                Icon(if (dayOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(18.dp))
                              }

                              AnimatedVisibility(visible = dayOpen) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                  dayItems.forEachIndexed { idx, rec ->
                                    val recKey = "$dKey-$idx-${rec.item.hashCode()}"
                                    val recOpen = onItemClick == null && recKey in expandedItemIds
                                    Surface(
                                      shape = RoundedCornerShape(14.dp),
                                      color = palette.surfaceElevated,
                                      border = BorderStroke(
                                        1.dp,
                                        when {
                                          recOpen -> palette.green.copy(alpha = 0.5f)
                                          rec.showStripe -> palette.amber.copy(alpha = 0.35f)
                                          else -> palette.border
                                        },
                                      ),
                                      modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                          if (onItemClick != null) {
                                            onItemClick(rec)
                                          } else {
                                            expandedItemIds = if (recOpen) expandedItemIds - recKey else expandedItemIds + recKey
                                          }
                                        },
                                    ) {
                                      Column(
                                        modifier = Modifier
                                          .drawBehind {
                                            if (rec.showStripe) {
                                              val step = 12.dp.toPx()
                                              val strokeW = 1.5.dp.toPx()
                                              val stripeColor = palette.amber.copy(alpha = 0.08f)
                                              var x = -size.height
                                              while (x < size.width + size.height) {
                                                drawLine(
                                                  color = stripeColor,
                                                  start = Offset(x, size.height),
                                                  end = Offset(x + size.height, 0f),
                                                  strokeWidth = strokeW,
                                                )
                                                x += step
                                              }
                                            }
                                            if (rec.showDots) {
                                              val spacing = 12.dp.toPx()
                                              val dotColor = palette.purple.copy(alpha = 0.14f)
                                              var dx = spacing / 2f
                                              while (dx < size.width) {
                                                var dy = spacing / 2f
                                                while (dy < size.height) {
                                                  drawCircle(dotColor, radius = 1.2.dp.toPx(), center = Offset(dx, dy))
                                                  dy += spacing
                                                }
                                                dx += spacing
                                              }
                                            }
                                          }
                                          .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                      ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                          InitialsAvatar(rec.title, rec.amountColor, sizeDp = 34.dp)
                                          Column(modifier = Modifier.weight(1f)) {
                                            Text(rec.title, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(rec.subtitle, color = palette.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                          }
                                          Column(horizontalAlignment = Alignment.End) {
                                            Text(rec.amountText, color = rec.amountColor, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                                            if (rec.chipText.isNotBlank()) {
                                              SarimChip(rec.chipText, rec.chipColor ?: rec.amountColor)
                                            }
                                          }
                                        }
                                        if (onItemClick == null) {
                                          AnimatedVisibility(visible = recOpen) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                              HorizontalDivider(color = palette.border)
                                              expandedContent(rec)
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SarimRecordBottomSheet(
  title: String,
  subtitle: String,
  amountText: String,
  amountColor: Color,
  metrics: List<Pair<String, String>>,
  onDismiss: () -> Unit,
  badgeText: String = "",
  badgeColor: Color? = null,
  extraContent: (@Composable ColumnScope.() -> Unit)? = null,
  onEdit: (() -> Unit)? = null,
  editLabel: String = "Edit",
  onDelete: (() -> Unit)? = null,
  deleteLabel: String = "Delete",
  extraActionLabel: String? = null,
  extraActionIcon: ImageVector? = null,
  onExtraAction: (() -> Unit)? = null,
) {
  val palette = LocalSarimPalette.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = palette.surface,
    contentColor = palette.textPrimary,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    dragHandle = {
      BottomSheetDefaults.DragHandle(color = palette.textMuted.copy(alpha = 0.45f))
    },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = 18.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(title, color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
          if (subtitle.isNotBlank()) {
            Text(subtitle, color = palette.textMuted, fontSize = 12.sp)
          }
          if (badgeText.isNotBlank()) {
            SarimChip(badgeText, badgeColor ?: amountColor)
          }
        }
        Spacer(Modifier.width(10.dp))
        Text(
          amountText,
          color = amountColor,
          style = TabularNumberStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
        )
      }

      if (metrics.isNotEmpty()) {
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = palette.surfaceElevated,
          border = BorderStroke(1.dp, palette.border),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            metrics.forEachIndexed { idx, (label, value) ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(label, color = palette.textMuted, fontSize = 12.sp)
                Text(value, color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold))
              }
              if (idx < metrics.lastIndex) {
                HorizontalDivider(color = palette.border.copy(alpha = 0.55f))
              }
            }
          }
        }
      }

      if (extraContent != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          extraContent()
        }
      }

      if (onExtraAction != null && extraActionLabel != null) {
        SarimSecondaryButton(
          text = extraActionLabel,
          icon = extraActionIcon,
          onClick = {
            onDismiss()
            onExtraAction()
          },
          modifier = Modifier.fillMaxWidth(),
        )
      }

      if (onEdit != null || onDelete != null) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (onDelete != null) {
            SarimDangerButton(
              text = deleteLabel,
              onClick = {
                onDismiss()
                onDelete()
              },
              modifier = Modifier.weight(1f),
            )
          }
          if (onEdit != null) {
            SarimSecondaryButton(
              text = editLabel,
              onClick = {
                onDismiss()
                onEdit()
              },
              modifier = Modifier.weight(1f),
            )
          }
        }
      }

      Spacer(Modifier.height(10.dp))
    }
  }
}
