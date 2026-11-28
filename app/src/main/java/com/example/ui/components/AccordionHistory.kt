package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
  /** Day footer button (web `cm-share` "Invoice"): receives every record of that day. */
  val dayActionLabel: String = "Invoice",
  val onDayAction: ((List<T>) -> Unit)? = null,
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

/** Web `.badge.status-badge`: expressive tinted pill. */
@Composable
internal fun HistoryStatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(50),
    color = lerp(scheme.surfaceContainerHighest, color, 0.22f),
  ) {
    Text(
      text = text.uppercase(),
      color = color,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 0.4.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
    )
  }
}

internal enum class SheetButtonKind { PRIMARY, SECONDARY, DANGER }

/** Expressive 48dp pill sheet button with spring press feedback. */
@Composable
internal fun HistorySheetButton(
  text: String,
  kind: SheetButtonKind,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
) {
  val palette = LocalSarimPalette.current
  val scheme = MaterialTheme.colorScheme
  val source = remember { MutableInteractionSource() }
  val (scale, corner) = rememberExpressivePress(source, restCorner = 24.dp, pressedCorner = 14.dp)
  val shape = RoundedCornerShape(corner)
  val surface: Modifier = when (kind) {
    SheetButtonKind.PRIMARY -> Modifier.background(Brush.horizontalGradient(listOf(palette.gradientStart, palette.gradientEnd)), shape)
    SheetButtonKind.DANGER -> Modifier.background(Brush.horizontalGradient(listOf(palette.red, palette.amber)), shape)
    SheetButtonKind.SECONDARY -> Modifier.background(scheme.secondaryContainer, shape)
  }
  val fg = if (kind == SheetButtonKind.SECONDARY) scheme.onSecondaryContainer else Color(0xFF04160B)
  val tag = when (kind) {
    SheetButtonKind.PRIMARY -> "primary_button"
    SheetButtonKind.DANGER -> "danger_button"
    SheetButtonKind.SECONDARY -> "secondary_button"
  }
  Box(
    modifier = modifier
      .height(48.dp)
      .graphicsLayer { scaleX = scale; scaleY = scale }
      .clip(shape)
      .then(surface)
      .testTag(tag)
      .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
      Text(text, color = fg, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun HistoryChevron(open: Boolean, modifier: Modifier = Modifier) {
  val palette = LocalSarimPalette.current
  val rot by animateFloatAsState(
    targetValue = if (open) 0f else -90f,
    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
    label = "historyChevron",
  )
  Icon(
    imageVector = Icons.Rounded.ExpandMore,
    contentDescription = null,
    tint = palette.textMuted,
    modifier = modifier.size(20.dp).rotate(rot),
  )
}

/** Web `.hc-more` panel: a titled collapsible tonal block used inside cards and sheets. */
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
  val scheme = MaterialTheme.colorScheme
  var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
  val bgColor by animateColorAsState(
    targetValue = if (expanded) lerp(scheme.surfaceContainerHigh, accentColor, 0.12f) else scheme.surfaceContainerHigh,
    label = "collapsibleBg",
  )
  val shape = MaterialTheme.shapes.large
  Surface(
    shape = shape,
    color = bgColor,
    modifier = modifier
      .fillMaxWidth()
      .clip(shape)
      .clickable { expanded = !expanded },
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f),
        ) {
          Text(title, color = if (expanded) palette.textPrimary else accentColor, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
          if (countText.isNotBlank()) HistoryStatusBadge(countText, accentColor)
        }
        HistoryChevron(open = expanded)
      }
      AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
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

/** Web `.cm-month-head`: name, entry pill badge, optional sums line, chevron. */
@Composable
private fun LedgerGroupHeader(name: String, count: String, sum: String, open: Boolean, onToggle: () -> Unit) {
  val palette = LocalSarimPalette.current
  val scheme = MaterialTheme.colorScheme
  val headerBg by animateColorAsState(
    targetValue = if (open) lerp(scheme.surfaceContainerLow, scheme.primary, 0.06f) else Color.Transparent,
    label = "ledgerGroupBg",
  )
  Column(modifier = Modifier.fillMaxWidth()) {
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = headerBg,
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 52.dp)
        .clip(RoundedCornerShape(18.dp))
        .clickable(onClick = onToggle),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            HistoryStatusBadge(count, if (open) palette.cyan else palette.textMuted)
          }
          if (sum.isNotBlank()) {
            Text(sum, color = palette.textMuted, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
        HistoryChevron(open = open)
      }
    }
    if (!open) {
      HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
    }
  }
}

/** Web `.hl-row`: expressive tonal transaction card with leading accent bar, dot, title, badge, subtitle, and amount. */
@Composable
private fun <T> LedgerRow(rec: HistoryRecordDisplay<T>, index: Int, onClick: () -> Unit) {
  val palette = LocalSarimPalette.current
  val scheme = MaterialTheme.colorScheme
  val tone = historyToneOf(rec.amountColor, rec.tone)
  val toneColor = historyToneColor(tone, palette)
  val source = remember { MutableInteractionSource() }
  val (scale, corner) = rememberExpressivePress(source, restCorner = 20.dp, pressedCorner = 12.dp, pressedScale = 0.975f)
  val shape = RoundedCornerShape(corner)
  val baseContainer = lerp(scheme.surfaceContainerHigh, toneColor, 0.09f)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 56.dp)
      .graphicsLayer { scaleX = scale; scaleY = scale }
      .clip(shape)
      .background(baseContainer)
      .historyToneBackground(
        tone = tone,
        color = toneColor,
        base = palette.textPrimary,
        stripeColor = if (rec.showStripe) palette.amber.copy(alpha = 0.10f) else null,
        dotColor = if (rec.showDots) palette.purple.copy(alpha = 0.16f) else null,
      )
      .testTag("history_row_$index")
      .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(
      modifier = Modifier
        .width(3.5.dp)
        .height(28.dp)
        .clip(RoundedCornerShape(50))
        .background(toneColor.copy(alpha = 0.85f)),
    )
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.5.dp).clip(CircleShape).background(rec.amountColor))
        Text(
          rec.title,
          color = palette.textPrimary,
          fontSize = 14.sp,
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
      style = TabularNumberStyle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold),
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
  itemKey: ((T) -> String)? = null,
  focusRecordKey: String? = null,
  onFocusConsumed: (() -> Unit)? = null,
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

  LaunchedEffect(focusRecordKey, records) {
    if (!focusRecordKey.isNullOrBlank() && itemKey != null) {
      val match = records.firstOrNull { itemKey(it.item) == focusRecordKey }
      if (match != null) {
        val dk = dayKeyOf(match.dateEpoch)
        if (dk == UNDATED) {
          openState["y:$UNDATED"] = true
          openState["m:$UNDATED"] = true
          openState["d:$UNDATED"] = true
        } else {
          val yk = dk.substring(0, 4)
          val mk = dk.substring(0, 7)
          openState["y:$yk"] = true
          openState["m:$mk"] = true
          openState["d:$dk"] = true
        }
        if (onItemClick != null) onItemClick(match) else sheetRec = match
        onFocusConsumed?.invoke()
      }
    }
  }

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
                  val scheme = MaterialTheme.colorScheme
                  val dayCorner by animateDpAsState(if (dOpen) 28.dp else 22.dp, label = "dayCorner")
                  val daySurfaceColor by animateColorAsState(
                    if (dOpen) scheme.surfaceContainer else scheme.surfaceContainerLow,
                    label = "daySurface",
                  )
                  val isToday = d.key == today
                  Surface(
                    shape = RoundedCornerShape(dayCorner),
                    color = daySurfaceColor,
                  ) {
                    Column {
                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .defaultMinSize(minHeight = 56.dp)
                          .clickable { openState[dKey] = !dOpen }
                          .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                      ) {
                        Surface(
                          shape = RoundedCornerShape(16.dp),
                          color = if (isToday) lerp(scheme.surfaceContainerHighest, palette.green, 0.24f) else scheme.surfaceContainerHighest,
                          modifier = Modifier.width(44.dp).defaultMinSize(minHeight = 46.dp),
                        ) {
                          Column(
                            modifier = Modifier.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                          ) {
                            Text(
                              if (d.key == UNDATED) "--" else SarimFormat.formatDayNumber(firstEpoch),
                              color = if (isToday) palette.green else palette.textPrimary,
                              style = TabularNumberStyle.copy(fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold),
                            )
                            if (d.key != UNDATED) {
                              Text(
                                SarimFormat.formatWeekday(firstEpoch),
                                color = if (isToday) palette.green else palette.textMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                              )
                            }
                          }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                          Text(entriesWord(d.items.size), color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                          val sum = daySummary(d.items)
                          if (sum.isNotBlank()) {
                            Text(sum, color = palette.textMuted, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                          }
                        }
                        HistoryChevron(open = dOpen)
                      }
                      AnimatedVisibility(visible = dOpen) {
                        Column {
                          HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                          Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                          ) {
                            d.items.forEachIndexed { idx, rec ->
                              LedgerRow(rec, idx) {
                                if (onItemClick != null) onItemClick(rec) else sheetRec = rec
                              }
                            }
                            val dayAction = actions?.onDayAction
                            if (dayAction != null) {
                              Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
                                Surface(
                                  shape = RoundedCornerShape(50),
                                  color = lerp(scheme.surfaceContainerHigh, palette.cyan, 0.16f),
                                  modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .clickable { dayAction(d.items.map { it.item }) },
                                ) {
                                  Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                  ) {
                                    Icon(Icons.Rounded.Share, contentDescription = null, tint = palette.cyan, modifier = Modifier.size(16.dp))
                                    Text(actions?.dayActionLabel ?: "Invoice", color = palette.cyan, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
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
  val scheme = MaterialTheme.colorScheme
  val toneColor = historyToneColor(tone, palette)
  var open by remember(title) { mutableStateOf(initiallyOpen) }
  val showBody = !collapsible || open
  val cardSource = remember { MutableInteractionSource() }
  val (pressScale, pressCorner) = rememberExpressivePress(cardSource, restCorner = 26.dp, pressedCorner = 16.dp, pressedScale = 0.98f)
  val cardShape = RoundedCornerShape(pressCorner)
  val cardContainer by animateColorAsState(
    targetValue = lerp(scheme.surfaceContainerHigh, toneColor, if (showBody && collapsible) 0.11f else 0.05f),
    label = "historyCardContainer",
  )
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .then(if (!collapsible && onClick != null) Modifier.graphicsLayer { scaleX = pressScale; scaleY = pressScale } else Modifier)
      .clip(cardShape)
      .then(
        if (!collapsible && onClick != null) {
          Modifier.clickable(interactionSource = cardSource, indication = LocalIndication.current, onClick = onClick)
        } else {
          Modifier
        },
      ),
    shape = cardShape,
    color = cardContainer,
  ) {
    Column(
      modifier = Modifier
        .drawBehind {
          drawRoundRect(
            color = toneColor.copy(alpha = 0.85f),
            topLeft = Offset(0f, 16.dp.toPx()),
            size = Size(3.5.dp.toPx(), (size.height - 32.dp.toPx()).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(4.dp.toPx()),
          )
        }
        .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 52.dp)
          .clip(RoundedCornerShape(16.dp))
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        InitialsAvatar(avatarName, leadColor ?: toneColor, sizeDp = 36.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(title, color = palette.textPrimary, fontSize = 14.8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (subtitle.isNotBlank()) {
            Text(subtitle, color = palette.textMuted, fontSize = 11.8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
        if (amountText.isNotBlank() || tagText.isNotBlank()) {
          Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
          Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
      } else {
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
      }
    }
  }
}

/** Web `.txn-balance-after` row: muted label on the left, bold value on the right. */
@Composable
fun HistoryKeyValueRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  valueColor: Color? = null,
  showDivider: Boolean = true,
) {
  val palette = LocalSarimPalette.current
  val scheme = MaterialTheme.colorScheme
  Column(modifier = modifier.fillMaxWidth()) {
    if (showDivider) {
      HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
    }
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, color = palette.textMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
      Spacer(Modifier.width(10.dp))
      Text(
        value,
        color = valueColor ?: palette.textPrimary,
        style = TabularNumberStyle.copy(fontSize = 12.8.sp, fontWeight = FontWeight.Bold),
      )
    }
  }
}

/**
 * The one entry popup for every history list (web `#hl-sheet`): grab handle, title + amount head,
 * info line + badge, grouped tonal metrics container, extras, then 48dp expressive action pills.
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
  val scheme = MaterialTheme.colorScheme
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val resolved = historyToneOf(amountColor, tone)
  val toneColor = historyToneColor(resolved, palette)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = scheme.surfaceContainerLow,
    contentColor = palette.textPrimary,
    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    dragHandle = {
      Box(
        Modifier
          .padding(top = 10.dp, bottom = 12.dp)
          .width(44.dp)
          .height(4.dp)
          .clip(RoundedCornerShape(50))
          .background(scheme.outlineVariant),
      )
    },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .historyToneBackground(resolved, toneColor, palette.textPrimary)
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(title, color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(amountText, color = amountColor, style = TabularNumberStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold))
      }
      if (subtitle.isNotBlank() || badgeText.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (subtitle.isNotBlank()) Text(subtitle, color = palette.textMuted, fontSize = 12.5.sp)
          if (badgeText.isNotBlank()) HistoryStatusBadge(badgeText, badgeColor ?: amountColor)
        }
      }
      if (metrics.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Surface(
          shape = MaterialTheme.shapes.large,
          color = scheme.surfaceContainerHigh,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            metrics.forEachIndexed { idx, (label, value) ->
              HistoryKeyValueRow(label, value, showDivider = idx > 0)
            }
          }
        }
      }

      if (extraContent != null) {
        Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          extraContent()
        }
      }

      val hasActions = onEdit != null || onDelete != null || (onExtraAction != null && extraActionLabel != null)
      if (hasActions) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
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
