package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
)

@Composable
fun <T> AccordionHistoryList(
  records: List<HistoryRecordDisplay<T>>,
  yearSummary: (List<HistoryRecordDisplay<T>>) -> String,
  monthSummary: (List<HistoryRecordDisplay<T>>) -> String,
  daySummary: (List<HistoryRecordDisplay<T>>) -> String,
  emptyTitle: String = "No records found",
  emptySubtitle: String = "No records found for this selection.",
  expandedContent: @Composable (HistoryRecordDisplay<T>) -> Unit,
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
                                    val recOpen = recKey in expandedItemIds
                                    Surface(
                                      shape = RoundedCornerShape(14.dp),
                                      color = palette.surfaceElevated,
                                      border = BorderStroke(1.dp, if (recOpen) palette.green.copy(alpha = 0.5f) else palette.border),
                                      modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                          expandedItemIds = if (recOpen) expandedItemIds - recKey else expandedItemIds + recKey
                                        },
                                    ) {
                                      Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
