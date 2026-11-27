package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.LocalSarimPalette
import com.example.util.SarimFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private enum class CalendarViewMode { DAYS, MONTHS, YEARS }

@Composable
fun SarimDatePickerField(
  label: String,
  dateEpoch: Long,
  onDateSelected: (Long) -> Unit,
  modifier: Modifier = Modifier,
  testTag: String = "date_picker",
) {
  val palette = LocalSarimPalette.current
  var open by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (label.isNotBlank()) SarimFieldLabel(label)
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 48.dp)
        .clip(RoundedCornerShape(12.dp))
        .testTag(testTag)
        .clickable { open = true },
      shape = RoundedCornerShape(12.dp),
      color = palette.surfaceElevated,
      border = BorderStroke(1.dp, palette.border),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = SarimFormat.formatDate(dateEpoch),
          color = palette.textPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
        )
        Icon(Icons.Filled.CalendarMonth, contentDescription = "Select date", tint = palette.green, modifier = Modifier.size(18.dp))
      }
    }
  }

  if (open) {
    CustomCalendarDialog(
      initialEpoch = dateEpoch,
      onDismiss = { open = false },
      onSelect = {
        onDateSelected(it)
        open = false
      },
    )
  }
}

@Composable
fun CustomCalendarDialog(
  initialEpoch: Long,
  onDismiss: () -> Unit,
  onSelect: (Long) -> Unit,
) {
  val palette = LocalSarimPalette.current
  val initCal = remember(initialEpoch) { Calendar.getInstance().apply { timeInMillis = initialEpoch } }
  var displayYear by remember { mutableIntStateOf(initCal.get(Calendar.YEAR)) }
  var displayMonth by remember { mutableIntStateOf(initCal.get(Calendar.MONTH)) }
  var mode by remember { mutableStateOf(CalendarViewMode.DAYS) }

  val todayCal = remember { Calendar.getInstance() }
  val selectedKey = SarimFormat.formatDayKey(initialEpoch)
  val todayKey = SarimFormat.formatDayKey(todayCal.timeInMillis)
  val monthFmt = remember { SimpleDateFormat("MMMM yyyy", Locale.US) }
  val monthShortNames = remember { listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec") }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = palette.surface,
      border = BorderStroke(1.dp, palette.border),
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          IconButton(
            onClick = {
              when (mode) {
                CalendarViewMode.DAYS -> {
                  if (displayMonth == 0) { displayMonth = 11; displayYear-- } else displayMonth--
                }
                CalendarViewMode.MONTHS -> displayYear--
                CalendarViewMode.YEARS -> displayYear -= 12
              }
            },
          ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", tint = palette.textPrimary)
          }

          val headerLabel = when (mode) {
            CalendarViewMode.DAYS -> {
              val c = Calendar.getInstance().apply { set(displayYear, displayMonth, 1) }
              monthFmt.format(c.time)
            }
            CalendarViewMode.MONTHS -> "$displayYear"
            CalendarViewMode.YEARS -> "${displayYear - 5} – ${displayYear + 6}"
          }
          Text(
            text = headerLabel,
            color = palette.green,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier
              .clip(RoundedCornerShape(8.dp))
              .clickable {
                mode = when (mode) {
                  CalendarViewMode.DAYS -> CalendarViewMode.MONTHS
                  CalendarViewMode.MONTHS -> CalendarViewMode.YEARS
                  CalendarViewMode.YEARS -> CalendarViewMode.DAYS
                }
              }
              .padding(horizontal = 10.dp, vertical = 6.dp),
          )

          IconButton(
            onClick = {
              when (mode) {
                CalendarViewMode.DAYS -> {
                  if (displayMonth == 11) { displayMonth = 0; displayYear++ } else displayMonth++
                }
                CalendarViewMode.MONTHS -> displayYear++
                CalendarViewMode.YEARS -> displayYear += 12
              }
            },
          ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = palette.textPrimary)
          }
        }

        when (mode) {
          CalendarViewMode.DAYS -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
              listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEach { d ->
                Text(d, color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
              }
            }
            val firstDay = Calendar.getInstance().apply { set(displayYear, displayMonth, 1) }
            val offset = firstDay.get(Calendar.DAY_OF_WEEK) - 1
            val daysInMonth = firstDay.getActualMaximum(Calendar.DAY_OF_MONTH)
            val cells = List(offset) { null } + (1..daysInMonth).toList()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              cells.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                  for (i in 0 until 7) {
                    val dayNum = week.getOrNull(i)
                    if (dayNum == null) {
                      Spacer(Modifier.weight(1f).height(38.dp))
                    } else {
                      val c = Calendar.getInstance().apply {
                        set(displayYear, displayMonth, dayNum, 12, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                      }
                      val key = SarimFormat.formatDayKey(c.timeInMillis)
                      val isSelected = key == selectedKey
                      val isToday = key == todayKey
                      Box(
                        modifier = Modifier
                          .weight(1f)
                          .height(38.dp)
                          .clip(CircleShape)
                          .background(if (isSelected) palette.green else Color.Transparent)
                          .clickable { onSelect(c.timeInMillis) },
                        contentAlignment = Alignment.Center,
                      ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                          Text(
                            text = dayNum.toString(),
                            color = if (isSelected) Color(0xFF022112) else palette.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                          )
                          if (isToday && !isSelected) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(palette.green))
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          CalendarViewMode.MONTHS -> {
            LazyVerticalGrid(
              columns = GridCells.Fixed(3),
              modifier = Modifier.height(200.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items((0..11).toList()) { mIdx ->
                val isCur = mIdx == displayMonth
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = if (isCur) palette.green.copy(alpha = 0.2f) else palette.surfaceElevated,
                  border = BorderStroke(1.dp, if (isCur) palette.green else palette.border),
                  modifier = Modifier.clickable {
                    displayMonth = mIdx
                    mode = CalendarViewMode.DAYS
                  },
                ) {
                  Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(monthShortNames[mIdx], color = if (isCur) palette.green else palette.textPrimary, fontWeight = FontWeight.SemiBold)
                  }
                }
              }
            }
          }

          CalendarViewMode.YEARS -> {
            val years = ((displayYear - 5)..(displayYear + 6)).toList()
            LazyVerticalGrid(
              columns = GridCells.Fixed(3),
              modifier = Modifier.height(200.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items(years) { yr ->
                val isCur = yr == displayYear
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = if (isCur) palette.green.copy(alpha = 0.2f) else palette.surfaceElevated,
                  border = BorderStroke(1.dp, if (isCur) palette.green else palette.border),
                  modifier = Modifier.clickable {
                    displayYear = yr
                    mode = CalendarViewMode.MONTHS
                  },
                ) {
                  Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(yr.toString(), color = if (isCur) palette.green else palette.textPrimary, fontWeight = FontWeight.SemiBold)
                  }
                }
              }
            }
          }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = {
            onSelect(System.currentTimeMillis())
          }) {
            Text("Today", color = palette.cyan)
          }
          TextButton(onClick = onDismiss) {
            Text("Close", color = palette.textMuted)
          }
        }
      }
    }
  }
}
