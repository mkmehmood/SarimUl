package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.SarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.SarimFormat

/**
 * Shared history-card family. Mirrors the web app's `history-ledger.js` (dated ledger rows + entry
 * sheet) and `history-card.js` (undated hc-card), so every history list, popup and card in the app
 * reads the same way.
 */

/** Same tone vocabulary as the web `is-good / is-bad / is-warn / is-info / is-muted` classes. */
enum class HistoryTone { GOOD, BAD, WARN, INFO, MUTED }

data class HistoryRecordDisplay<T>(
  val item: T,
  /** Epoch millis of the record. A value <= 0 puts the record in the "No date" bucket (web `undated`). */
  val dateEpoch: Long,
  val title: String,
  val subtitle: String,
  val amountText: String,
  val amountColor: Color,
  val chipText: String = "",
  val chipColor: Color? = null,
  val showStripe: Boolean = false,
  val showDots: Boolean = false,
  val detailMetrics: List<Pair<String, String>> = emptyList(),
  val detailNote: String = "",
  /** Optional explicit tone; when null it is derived from [amountColor]. */
  val tone: HistoryTone? = null,
)

/** Edit / Delete / extra actions shown in the entry sheet that [AccordionHistoryList] opens itself. */
class HistoryRecordActions<T>(
  val onEdit: ((T) -> Unit)? = null,
  val editLabel: String = "Edit",
  val onDelete: ((T) -> Unit)? = null,
  val deleteLabel: String = "Delete",
  val extraLabel: (T) -> String? = { null },
  val extraIcon: ImageVector? = null,
  val onExtra: ((T) -> Unit)? = null,
)

@Composable
internal fun historyToneOf(color: Color, explicit: HistoryTone? = null): HistoryTone {
  if (explicit != null) return explicit
  val p = LocalSarimPalette.current
  return when (color) {
    p.green, p.emerald -> HistoryTone.GOOD
    p.red -> HistoryTone.BAD
    p.amber -> HistoryTone.WARN
    p.cyan, p.purple -> HistoryTone.INFO
    else -> HistoryTone.MUTED
  }
}

internal fun historyToneColor(tone: HistoryTone, p: SarimPalette): Color = when (tone) {
  HistoryTone.GOOD -> p.green
  HistoryTone.BAD -> p.red
  HistoryTone.WARN -> p.amber
  HistoryTone.INFO -> p.cyan
  HistoryTone.MUTED -> p.textMuted
}

/**
 * Tone wash used behind ledger rows and the entry sheet (web `.hl-row.is-*` / `.hl-sheet.is-*`).
 * [stripeColor] adds the warn hatch, [dotColor] adds the info dot grid.
 */
internal fun Modifier.historyToneBackground(
  tone: HistoryTone,
  color: Color,
  base: Color,
  stripeColor: Color? = null,
  dotColor: Color? = null,
): Modifier = drawBehind {
  val w = size.width
  val h = size.height
  if (w <= 0f || h <= 0f) return@drawBehind
  val far = maxOf(w, h)
  when (tone) {
    HistoryTone.GOOD -> {
      drawRect(Brush.radialGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent), center = Offset(w, 0f), radius = far * 0.9f))
      drawRect(Brush.radialGradient(listOf(color.copy(alpha = 0.10f), Color.Transparent), center = Offset(0f, h), radius = far * 0.6f))
    }
    HistoryTone.BAD -> {
      drawRect(Brush.horizontalGradient(listOf(color.copy(alpha = 0.17f), Color.Transparent), startX = 0f, endX = w * 0.58f))
      drawRect(Brush.radialGradient(listOf(color.copy(alpha = 0.12f), Color.Transparent), center = Offset(w, 0f), radius = far * 0.8f))
    }
    HistoryTone.WARN -> {
      drawRect(Brush.radialGradient(listOf(color.copy(alpha = 0.18f), Color.Transparent), center = Offset(w, 0f), radius = far * 0.9f))
    }
    HistoryTone.INFO -> {
      drawRect(Brush.radialGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent), center = Offset(w, 0f), radius = far * 0.9f))
    }
    HistoryTone.MUTED -> {
      drawRect(Brush.linearGradient(listOf(base.copy(alpha = 0.06f), Color.Transparent), start = Offset(0f, 0f), end = Offset(w, h)))
    }
  }
  if (stripeColor != null) {
    val step = 14.dp.toPx()
    val strokeW = 3.dp.toPx()
    var x = -h
    while (x < w + h) {
      drawLine(color = stripeColor, start = Offset(x, h), end = Offset(x + h, 0f), strokeWidth = strokeW)
      x += step
    }
  }
  if (dotColor != null) {
    val spacing = 12.dp.toPx()
    var dx = spacing / 2f
    while (dx < w) {
      var dy = spacing / 2f
      while (dy < h) {
        drawCircle(dotColor, radius = 1.2.dp.toPx(), center = Offset(dx, dy))
        dy += spacing
      }
      dx += spacing
    }
  }
}

/** Web `.badge.status-badge`: tiny uppercase tinted pill. */
@Composable
internal fun HistoryStatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
  Surface(modifier = modifier, shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
    Text(
      text = text.uppercase(),
      color = color,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 0.4.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
    )
  }
}

internal enum class SheetButtonKind { PRIMARY, SECONDARY, DANGER }

/** Web `.cm-sheet-actions .btn`: 42dp pill; Edit is the wide gradient one, Delete the narrow one. */
@Composable
internal fun HistorySheetButton(
  text: String,
  kind: SheetButtonKind,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
) {
  val palette = LocalSarimPalette.current
  val shape = RoundedCornerShape(50)
  val surface: Modifier = when (kind) {
    SheetButtonKind.PRIMARY -> Modifier.background(Brush.horizontalGradient(listOf(palette.gradientStart, palette.gradientEnd)), shape)
    SheetButtonKind.DANGER -> Modifier.background(Brush.horizontalGradient(listOf(palette.red, palette.amber)), shape)
    SheetButtonKind.SECONDARY -> Modifier.background(palette.surfaceElevated, shape).border(1.dp, palette.border, shape)
  }
  val fg = if (kind == SheetButtonKind.SECONDARY) palette.textPrimary else Color(0xFF04160B)
  val tag = when (kind) {
    SheetButtonKind.PRIMARY -> "primary_button"
    SheetButtonKind.DANGER -> "danger_button"
    SheetButtonKind.SECONDARY -> "secondary_button"
  }
  Box(
    modifier = modifier
      .height(42.dp)
      .clip(shape)
      .then(surface)
      .testTag(tag)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
      Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun HistoryChevron(open: Boolean, modifier: Modifier = Modifier) {
  val palette = LocalSarimPalette.current
  val rot by animateFloatAsState(if (open) 0f else -90f, label = "historyChevron")
  Icon(
    imageVector = Icons.Rounded.ExpandMore,
    contentDescription = null,
    tint = palette.textMuted,
    modifier = modifier.size(18.dp).rotate(rot),
  )
}

/** Web `.hc-more` panel: a titled collapsible block used inside cards and sheets. */
@Composable
fun SarimCollapsibleDropdownSection(
  title: String,
  accentColor: Color,
  countText: String = "",
  initiallyExpanded: Boolean = false,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val palette = LocalSarimPalette.current
  var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = palette.surface,
    border = BorderStroke(1.dp, if (expanded) accentColor.copy(alpha = 0.5f) else palette.border),
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .clickable { expanded = !expanded },
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f),
        ) {
          Text(title, color = if (expanded) palette.textPrimary else accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          if (countText.isNotBlank()) HistoryStatusBadge(countText, accentColor)
        }
        HistoryChevron(open = expanded)
      }
      AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          HorizontalDivider(color = palette.border)
          content()
        }
      }
    }
  }
}

private const val UNDATED = "undated"

private val keyDesc = Comparator<String> { a, b ->
  when {
    a == UNDATED && b == UNDATED -> 0
    a == UNDATED -> 1
    b == UNDATED -> -1
    else -> b.compareTo(a)
  }
}

private class DayGroup<T>(val key: String, val items: List<HistoryRecordDisplay<T>>)
private class MonthGroup<T>(val key: String, val days: List<DayGroup<T>>, val items: List<HistoryRecordDisplay<T>>)
private class YearGroup<T>(val key: String, val months: List<MonthGroup<T>>, val items: List<HistoryRecordDisplay<T>>)

private fun dayKeyOf(epoch: Long): String = if (epoch <= 0L) UNDATED else SarimFormat.dateInput(epoch)

private fun <T> buildLedger(records: List<HistoryRecordDisplay<T>>): List<YearGroup<T>> {
  val byYear = records.groupBy { r -> dayKeyOf(r.dateEpoch).let { if (it == UNDATED) UNDATED else it.substring(0, 4) } }
  return byYear.keys.sortedWith(keyDesc).map { yk ->
    val yItems = byYear.getValue(yk)
    val byMonth = yItems.groupBy { r -> dayKeyOf(r.dateEpoch).let { if (it == UNDATED) UNDATED else it.substring(0, 7) } }
    val months = byMonth.keys.sortedWith(keyDesc).map { mk ->
      val mItems = byMonth.getValue(mk)
      val byDay = mItems.groupBy { r -> dayKeyOf(r.dateEpoch) }
      MonthGroup(mk, byDay.keys.sortedWith(keyDesc).map { dk -> DayGroup(dk, byDay.getValue(dk)) }, mItems)
    }
    YearGroup(yk, months, yItems)
  }
}

private fun entriesWord(n: Int) = if (n == 1) "1 entry" else "$n entries"

/** Web `.cm-month-head`: name, entry count, optional sums line, chevron, hairline underneath. */
@Composable
private fun LedgerGroupHeader(name: String, count: String, sum: String, open: Boolean, onToggle: () -> Unit) {
  val palette = LocalSarimPalette.current
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onToggle)
        .padding(horizontal = 4.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(name, color = palette.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
          Text(count, color = palette.textMuted, fontSize = 11.5.sp)
        }
        if (sum.isNotBlank()) {
          Text(sum, color = palette.textMuted, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
      HistoryChevron(open = open)
    }
    HorizontalDivider(color = palette.border)
  }
}

/** Web `.hl-row`: dot + title + status badge, sub line, amount; tone wash; opens the entry sheet. */
@Composable
private fun <T> LedgerRow(rec: HistoryRecordDisplay<T>, index: Int, onClick: () -> Unit) {
  val palette = LocalSarimPalette.current
  val tone = historyToneOf(rec.amountColor, rec.tone)
  val toneColor = historyToneColor(tone, palette)
  val shape = RoundedCornerShape(14.dp)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .historyToneBackground(
        tone = tone,
        color = toneColor,
        base = palette.textPrimary,
        stripeColor = if (rec.showStripe) palette.amber.copy(alpha = 0.10f) else null,
        dotColor = if (rec.showDots) palette.purple.copy(alpha = 0.16f) else null,
      )
      .testTag("history_row_$index")
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(rec.amountColor))
        Text(
          rec.title,
          color = palette.textPrimary,
          fontSize = 13.8.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (rec.chipText.isNotBlank()) HistoryStatusBadge(rec.chipText, rec.chipColor ?: rec.amountColor)
      }
      if (rec.subtitle.isNotBlank()) {
        Text(rec.subtitle, color = palette.textMuted, fontSize = 11.8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
    Text(
      rec.amountText,
      color = rec.amountColor,
      style = TabularNumberStyle.copy(fontSize = 14.4.sp, fontWeight = FontWeight.Bold),
      maxLines = 1,
    )
  }
}

/**
 * Year > month > day history ledger. Records without a date (dateEpoch <= 0) are collected under
 * "No date", exactly like the web ledger. Tapping a row opens the shared entry sheet: the caller's
 * own via [onItemClick], or the built-in one fed by the record's detail metrics, [actions] and
 * [expandedContent].
 */
@Composable
fun <T> AccordionHistoryList(
  records: List<HistoryRecordDisplay<T>>,
  yearSummary: (List<HistoryRecordDisplay<T>>) -> String,
  monthSummary: (List<HistoryRecordDisplay<T>>) -> String,
  daySummary: (List<HistoryRecordDisplay<T>>) -> String,
  emptyTitle: String = "No records found",
  emptySubtitle: String = "No records found for this selection.",
  @Suppress("UNUSED_PARAMETER") hasDropdownDetails: Boolean = false,
  onItemClick: ((HistoryRecordDisplay<T>) -> Unit)? = null,
  actions: HistoryRecordActions<T>? = null,
  expandedContent: @Composable (HistoryRecordDisplay<T>) -> Unit = {},
) {
  val palette = LocalSarimPalette.current
  if (records.isEmpty()) {
    SarimEmptyState(emptyTitle, emptySubtitle)
    return
  }

  val years = remember(records) { buildLedger(records) }
  val openState = remember { mutableStateMapOf<String, Boolean>() }
  var sheetRec by remember { mutableStateOf<HistoryRecordDisplay<T>?>(null) }

  val today = remember { SarimFormat.dateInput(System.currentTimeMillis()) }
  val curY = today.substring(0, 4)
  val curM = today.substring(0, 7)
  val hasY = years.any { it.key == curY }
  val hasM = years.any { y -> y.months.any { it.key == curM } }
  val latestY = years.first().key
  val latestM = years.first().months.first().key

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    years.forEach { y ->
      val yKey = "y:${y.key}"
      val yOpen = openState[yKey] ?: (if (hasY) y.key == curY else y.key == latestY)
      LedgerGroupHeader(
        name = if (y.key == UNDATED) "No date" else y.key,
        count = entriesWord(y.items.size),
        sum = yearSummary(y.items),
        open = yOpen,
        onToggle = { openState[yKey] = !yOpen },
      )
      AnimatedVisibility(visible = yOpen) {
        Column(modifier = Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          y.months.forEach { m ->
            val mKey = "m:${m.key}"
            val mOpen = openState[mKey] ?: (if (hasM) m.key == curM else m.key == latestM)
            LedgerGroupHeader(
              name = if (m.key == UNDATED) "No date" else SarimFormat.formatMonthYear(m.items.first().dateEpoch),
              count = entriesWord(m.items.size),
              sum = monthSummary(m.items),
              open = mOpen,
              onToggle = { openState[mKey] = !mOpen },
            )
            AnimatedVisibility(visible = mOpen) {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                m.days.forEach { d ->
                  val dKey = "d:${d.key}"
                  val dOpen = openState[dKey] ?: (d.key == today)
                  val firstEpoch = d.items.first().dateEpoch
                  Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.border),
                  ) {
                    Column {
                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clickable { openState[dKey] = !dOpen }
                          .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                      ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(38.dp)) {
                          Text(
                            if (d.key == UNDATED) "--" else SarimFormat.formatDayNumber(firstEpoch),
                            color = palette.textPrimary,
                            style = TabularNumberStyle.copy(fontSize = 16.8.sp, fontWeight = FontWeight.ExtraBold),
                          )
                          if (d.key != UNDATED) {
                            Text(SarimFormat.formatWeekday(firstEpoch), color = palette.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                          }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                          Text(entriesWord(d.items.size), color = palette.textPrimary, fontSize = 13.8.sp, fontWeight = FontWeight.SemiBold)
                          val sum = daySummary(d.items)
                          if (sum.isNotBlank()) {
                            Text(sum, color = palette.textMuted, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                          }
                        }
                        HistoryChevron(open = dOpen)
                      }
                      AnimatedVisibility(visible = dOpen) {
                        Column {
                          HorizontalDivider(color = palette.border)
                          Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                          ) {
                            d.items.forEachIndexed { idx, rec ->
                              LedgerRow(rec, idx) {
                                if (onItemClick != null) onItemClick(rec) else sheetRec = rec
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

  sheetRec?.let { rec ->
    val extraLabel = actions?.extraLabel?.invoke(rec.item)
    val onExtra = actions?.onExtra
    SarimRecordBottomSheet(
      title = rec.title,
      subtitle = listOfNotNull(
        if (rec.dateEpoch > 0L) SarimFormat.formatDate(rec.dateEpoch) else null,
        rec.subtitle.takeIf { it.isNotBlank() },
      ).joinToString(" · "),
      amountText = rec.amountText,
      amountColor = rec.amountColor,
      badgeText = rec.chipText,
      badgeColor = rec.chipColor,
      tone = rec.tone,
      metrics = rec.detailMetrics,
      onDismiss = { sheetRec = null },
      extraContent = {
        if (rec.detailNote.isNotBlank()) {
          Text(rec.detailNote, color = palette.textMuted, fontSize = 11.5.sp)
        }
        expandedContent(rec)
      },
      onEdit = actions?.onEdit?.let { f -> { f(rec.item) } },
      editLabel = actions?.editLabel ?: "Edit",
      onDelete = actions?.onDelete?.let { f -> { f(rec.item) } },
      deleteLabel = actions?.deleteLabel ?: "Delete",
      extraActionLabel = if (onExtra != null) extraLabel else null,
      extraActionIcon = actions?.extraIcon,
      onExtraAction = if (onExtra != null && extraLabel != null) { { onExtra(rec.item) } } else null,
    )
  }
}

/**
 * Web `.hc-card` for lists that carry no date (saved calculations, formula store, ...): left tone
 * stripe, lead avatar, title/sub, amount + tag, and a body that expands on tap. Pass [onClick] with
 * [collapsible] = false for cards whose body is always visible and whose tap does something else.
 */
@Composable
fun SarimHistoryCard(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String = "",
  amountText: String = "",
  tagText: String = "",
  tone: HistoryTone = HistoryTone.MUTED,
  avatarName: String = title,
  leadColor: Color? = null,
  collapsible: Boolean = true,
  initiallyOpen: Boolean = false,
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit = {},
) {
  val palette = LocalSarimPalette.current
  val toneColor = historyToneColor(tone, palette)
  var open by remember(title) { mutableStateOf(initiallyOpen) }
  val showBody = !collapsible || open
  val shape = RoundedCornerShape(16.dp)
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clip(shape)
      .then(if (!collapsible && onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    shape = shape,
    color = palette.surface,
    border = BorderStroke(1.dp, if (showBody && collapsible) toneColor.copy(alpha = 0.5f) else palette.border),
  ) {
    Column(
      modifier = Modifier
        .drawBehind {
          drawRoundRect(
            color = toneColor.copy(alpha = 0.85f),
            topLeft = Offset(0f, 14.dp.toPx()),
            size = Size(3.dp.toPx(), (size.height - 28.dp.toPx()).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(3.dp.toPx()),
          )
        }
        .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .then(
            if (collapsible) {
              Modifier.clickable {
                open = !open
                onClick?.invoke()
              }
            } else {
              Modifier
            },
          ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        InitialsAvatar(avatarName, leadColor ?: toneColor, sizeDp = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
          Text(title, color = palette.textPrimary, fontSize = 14.4.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (subtitle.isNotBlank()) {
            Text(subtitle, color = palette.textMuted, fontSize = 11.8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
        if (amountText.isNotBlank() || tagText.isNotBlank()) {
          Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (amountText.isNotBlank()) {
              Text(amountText, color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 15.2.sp, fontWeight = FontWeight.ExtraBold), maxLines = 1)
            }
            if (tagText.isNotBlank()) HistoryStatusBadge(tagText, toneColor)
          }
        }
        if (collapsible) HistoryChevron(open = open)
      }
      if (collapsible) {
        AnimatedVisibility(visible = showBody) {
          Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
      } else {
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
      }
    }
  }
}

/** Web `.txn-balance-after` row: muted label on the left, bold value on the right, hairline above. */
@Composable
fun HistoryKeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
  val palette = LocalSarimPalette.current
  Column(modifier = modifier.fillMaxWidth()) {
    HorizontalDivider(color = palette.border.copy(alpha = 0.6f))
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, color = palette.textMuted, fontSize = 11.8.sp, modifier = Modifier.weight(1f))
      Spacer(Modifier.width(10.dp))
      Text(
        value,
        color = valueColor ?: palette.textPrimary,
        style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
      )
    }
  }
}

/**
 * The one entry popup for every history list (web `#hl-sheet`): grab handle, title + amount head,
 * info line + badge, key/value rows, extras, then Delete (narrow) and Edit (wide gradient) pills.
 */
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
  tone: HistoryTone? = null,
) {
  val palette = LocalSarimPalette.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val resolved = historyToneOf(amountColor, tone)
  val toneColor = historyToneColor(resolved, palette)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = palette.surface,
    contentColor = palette.textPrimary,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    dragHandle = {
      Box(
        Modifier
          .padding(top = 8.dp, bottom = 12.dp)
          .width(38.dp)
          .height(4.dp)
          .clip(RoundedCornerShape(50))
          .background(palette.border),
      )
    },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .historyToneBackground(resolved, toneColor, palette.textPrimary)
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(title, color = palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(amountText, color = amountColor, style = TabularNumberStyle.copy(fontSize = 16.8.sp, fontWeight = FontWeight.ExtraBold))
      }
      Spacer(Modifier.height(10.dp))
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (subtitle.isNotBlank()) Text(subtitle, color = palette.textMuted, fontSize = 12.sp)
        if (badgeText.isNotBlank()) HistoryStatusBadge(badgeText, badgeColor ?: amountColor)
      }
      Spacer(Modifier.height(6.dp))
      metrics.forEach { (label, value) -> HistoryKeyValueRow(label, value) }

      if (extraContent != null) {
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          extraContent()
        }
      }

      val hasActions = onEdit != null || onDelete != null || (onExtraAction != null && extraActionLabel != null)
      if (hasActions) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (onDelete != null) {
            HistorySheetButton(
              text = deleteLabel,
              kind = SheetButtonKind.DANGER,
              onClick = { onDismiss(); onDelete() },
              modifier = Modifier.weight(1f),
            )
          }
          if (onExtraAction != null && extraActionLabel != null) {
            HistorySheetButton(
              text = extraActionLabel,
              kind = SheetButtonKind.SECONDARY,
              icon = extraActionIcon,
              onClick = { onDismiss(); onExtraAction() },
              modifier = Modifier.weight(1.4f),
            )
          }
          if (onEdit != null) {
            HistorySheetButton(
              text = editLabel,
              kind = SheetButtonKind.PRIMARY,
              onClick = { onDismiss(); onEdit() },
              modifier = Modifier.weight(3f),
            )
          }
        }
      }
    }
  }
}
