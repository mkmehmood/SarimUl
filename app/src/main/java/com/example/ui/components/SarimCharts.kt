package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChartSeries
import com.example.data.model.RankedBarItem
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.SarimFormat
import kotlin.math.max

private fun safeFinite(v: Double): Double = if (v.isFinite()) v else 0.0

@Composable
fun SparklineWave(
  values: List<Double>,
  color: Color,
  modifier: Modifier = Modifier,
) {
  val safeValues = remember(values) { values.map(::safeFinite) }
  Canvas(modifier = modifier.height(26.dp).fillMaxWidth()) {
    if (safeValues.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
    val minVal = safeValues.minOrNull() ?: 0.0
    val maxVal = safeValues.maxOrNull() ?: 1.0
    val span = (maxVal - minVal).takeIf { it > 0.0001 && it.isFinite() } ?: 1.0
    val stepX = size.width / (safeValues.size - 1).coerceAtLeast(1)
    val path = Path()
    safeValues.forEachIndexed { idx, v ->
      val x = idx * stepX
      val norm = ((v - minVal) / span).toFloat().coerceIn(0f, 1f)
      val y = size.height - (norm * (size.height * 0.8f) + size.height * 0.1f)
      if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
      path = path,
      color = color,
      style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
    )
  }
}

@Composable
fun MultiLineTrendChart(
  labels: List<String>,
  seriesList: List<ChartSeries>,
  modifier: Modifier = Modifier,
  privacyHidden: Boolean = false,
) {
  val palette = LocalSarimPalette.current
  var selectedIndex by remember { mutableStateOf<Int?>(null) }
  var hiddenSeries by remember { mutableStateOf(setOf<String>()) }

  val activeSeries = remember(seriesList, hiddenSeries) {
    seriesList.filter { it.label !in hiddenSeries }.map { s ->
      s.copy(values = s.values.map(::safeFinite))
    }
  }
  val allValues = activeSeries.flatMap { it.values }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // Legend chips (all series wrapped)
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      seriesList.forEach { s ->
        val enabled = s.label !in hiddenSeries
        val c = Color(s.colorHex)
        val scheme = MaterialTheme.colorScheme
        Surface(
          shape = RoundedCornerShape(50),
          color = if (enabled) lerp(scheme.surfaceContainerHigh, c, 0.22f) else scheme.surfaceContainerHigh,
          modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(RoundedCornerShape(50))
            .clickable {
              hiddenSeries = if (enabled) hiddenSeries + s.label else hiddenSeries - s.label
            },
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (enabled) c else palette.textMuted))
            Text(s.label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) palette.textPrimary else palette.textMuted, maxLines = 1)
          }
        }
      }
    }

    if (allValues.isEmpty() || labels.isEmpty()) {
      SarimEmptyState("No chart data", "Entries recorded for this period will plot here.")
      return
    }

    val minVal = (allValues.minOrNull() ?: 0.0).coerceAtMost(0.0)
    val maxVal = max(allValues.maxOrNull() ?: 1.0, minVal + 1.0)
    val span = (maxVal - minVal).takeIf { it.isFinite() }?.coerceAtLeast(1.0) ?: 1.0

    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(165.dp)
        .pointerInput(labels.size) {
          detectTapGestures { offset ->
            if (labels.isNotEmpty() && size.width > 0) {
              val idx = ((offset.x / size.width) * labels.size).toInt().coerceIn(0, labels.lastIndex)
              selectedIndex = if (selectedIndex == idx) null else idx
            }
          }
        },
    ) {
      if (size.width <= 0f || size.height <= 0f) return@Canvas
      val gridColor = palette.border
      for (i in 0..3) {
        val y = size.height * (i / 3f)
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
      }
      val count = labels.size
      val stepX = if (count > 1) size.width / (count - 1) else size.width

      activeSeries.forEach { s ->
        if (s.values.isEmpty()) return@forEach
        val path = Path()
        val c = Color(s.colorHex)
        s.values.forEachIndexed { idx, v ->
          val x = if (count > 1) idx * stepX else size.width / 2f
          val norm = ((v - minVal) / span).toFloat().coerceIn(0f, 1f)
          val y = size.height - (norm * size.height * 0.88f + size.height * 0.06f)
          if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        s.values.forEachIndexed { idx, v ->
          val x = if (count > 1) idx * stepX else size.width / 2f
          val norm = ((v - minVal) / span).toFloat().coerceIn(0f, 1f)
          val y = size.height - (norm * size.height * 0.88f + size.height * 0.06f)
          drawCircle(c, radius = if (selectedIndex == idx) 5.dp.toPx() else 3.dp.toPx(), center = Offset(x, y))
        }
      }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      labels.forEach { lbl ->
        Text(lbl.take(6), fontSize = 9.5.sp, color = palette.textMuted)
      }
    }

    selectedIndex?.let { idx ->
      if (idx in labels.indices) {
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(labels[idx], color = palette.green, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
            activeSeries.forEach { s ->
              val v = s.values.getOrNull(idx) ?: 0.0
              Text(
                "${s.label}: ${SarimFormat.formatRs(v, showSymbol = false, privacyHidden = privacyHidden)}",
                color = palette.textPrimary,
                style = TabularNumberStyle.copy(fontSize = 11.sp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun ColumnBarChart(
  labels: List<String>,
  values: List<Double>,
  colors: List<Color>,
  modifier: Modifier = Modifier,
) {
  val palette = LocalSarimPalette.current
  var selectedIdx by remember { mutableStateOf<Int?>(null) }
  val safeValues = remember(values) { values.map(::safeFinite) }

  if (safeValues.isEmpty() || safeValues.all { it == 0.0 }) {
    SarimEmptyState("No data yet", "Records will appear as a column chart here.")
    return
  }

  val maxVal = (safeValues.maxOrNull() ?: 1.0).takeIf { it.isFinite() }?.coerceAtLeast(1.0) ?: 1.0
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(145.dp)
        .pointerInput(safeValues.size) {
          detectTapGestures { offset ->
            if (safeValues.isNotEmpty() && size.width > 0) {
              val idx = ((offset.x / size.width) * safeValues.size).toInt().coerceIn(0, safeValues.lastIndex)
              selectedIdx = if (selectedIdx == idx) null else idx
            }
          }
        },
    ) {
      if (size.width <= 0f || size.height <= 0f) return@Canvas
      val slotW = size.width / safeValues.size.coerceAtLeast(1)
      val barW = (slotW * 0.55f).coerceAtMost(38.dp.toPx()).coerceAtLeast(1f)
      safeValues.forEachIndexed { i, v ->
        val h = ((v / maxVal).toFloat().coerceIn(0f, 1f)) * (size.height * 0.9f)
        val x = i * slotW + (slotW - barW) / 2f
        val y = size.height - h
        val c = colors.getOrElse(i) { palette.green }
        drawRoundRect(
          color = if (selectedIdx == null || selectedIdx == i) c else c.copy(alpha = 0.45f),
          topLeft = Offset(x, y),
          size = Size(barW, h),
          cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
        )
      }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
      labels.forEach { lbl ->
        Text(lbl.take(8), fontSize = 10.sp, color = palette.textMuted, maxLines = 1)
      }
    }
    selectedIdx?.let { idx ->
      if (idx in labels.indices && idx in safeValues.indices) {
        Text(
          "${labels[idx]}: ${SarimFormat.formatSouthAsian(safeValues[idx])}",
          color = palette.green,
          style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
      }
    }
  }
}

@Composable
fun RankedHorizontalBars(
  items: List<RankedBarItem>,
  barColor: Color,
  modifier: Modifier = Modifier,
  emptyMessage: String = "Nothing recorded in this period",
  onItemClick: ((RankedBarItem) -> Unit)? = null,
) {
  val palette = LocalSarimPalette.current
  if (items.isEmpty()) {
    SarimEmptyState("No records", emptyMessage)
    return
  }
  val maxVal = (items.maxOfOrNull { safeFinite(it.value) } ?: 1.0).coerceAtLeast(1.0)
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    items.forEach { item ->
      val safeVal = safeFinite(item.value)
      val ratio = (safeVal / maxVal).toFloat().let { if (it.isFinite()) it else 0.03f }.coerceIn(0.03f, 1f)
      val rowModifier = if (onItemClick != null) {
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { onItemClick(item) }
          .padding(vertical = 2.dp)
      } else {
        Modifier.fillMaxWidth()
      }
      Column(modifier = rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Text(item.label, color = palette.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.badge.isNotBlank()) SarimChip(item.badge, palette.purple)
          }
          Text(
            item.secondaryText.ifBlank { SarimFormat.formatSouthAsian(safeVal) },
            color = palette.textPrimary,
            style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
          )
        }
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(ratio)
              .fillMaxHeight()
              .clip(RoundedCornerShape(50))
              .background(barColor),
          )
        }
      }
    }
  }
}

@Composable
fun DonutShareChart(
  slices: List<Triple<String, Double, Color>>,
  centerLabel: String,
  modifier: Modifier = Modifier,
) {
  val palette = LocalSarimPalette.current
  val safeSlices = remember(slices) {
    slices.map { (name, v, color) -> Triple(name, safeFinite(v).coerceAtLeast(0.0), color) }
  }
  val total = safeSlices.sumOf { it.second }
  if (total <= 0.0001 || !total.isFinite()) {
    SarimEmptyState("No breakdown", "Nothing recorded in this period")
    return
  }
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Box(modifier = Modifier.size(116.dp), contentAlignment = Alignment.Center) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        var startAngle = -90f
        safeSlices.forEach { (_, valNum, color) ->
          val sweep = ((valNum / total) * 360.0).toFloat()
          if (sweep.isFinite() && sweep > 0.5f) {
            drawArc(
              color = color,
              startAngle = startAngle,
              sweepAngle = (sweep - 2f).coerceAtLeast(0.1f),
              useCenter = false,
              style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Butt),
            )
            startAngle += sweep
          }
        }
      }
      Text(centerLabel, color = palette.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      safeSlices.forEach { (name, valNum, color) ->
        val pct = ((valNum / total) * 100.0).toInt().coerceIn(0, 100)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Box(Modifier.size(8.dp).clip(CircleShape).background(color))
          Text(name, color = palette.textPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text("$pct%", color = palette.textMuted, style = TabularNumberStyle.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
        }
      }
    }
  }
}

@Composable
fun SplitRatioBar(
  leftLabel: String,
  leftValue: Double,
  leftColor: Color,
  rightLabel: String,
  rightValue: Double,
  rightColor: Color,
  modifier: Modifier = Modifier,
  privacyHidden: Boolean = false,
) {
  val palette = LocalSarimPalette.current
  val safeLeft = safeFinite(leftValue).coerceAtLeast(0.0)
  val safeRight = safeFinite(rightValue).coerceAtLeast(0.0)
  val total = safeLeft + safeRight
  val leftRatio = if (total <= 0.0001 || !total.isFinite()) {
    0.5f
  } else {
    ((safeLeft / total).toFloat()).let { if (it.isFinite()) it else 0.5f }.coerceIn(0f, 1f)
  }
  val rightRatio = (1f - leftRatio).coerceIn(0f, 1f)
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        "$leftLabel: ${SarimFormat.formatRs(safeLeft, showSymbol = false, privacyHidden = privacyHidden)}",
        color = leftColor,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        "$rightLabel: ${SarimFormat.formatRs(safeRight, showSymbol = false, privacyHidden = privacyHidden)}",
        color = rightColor,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
      )
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(9.dp)
        .clip(RoundedCornerShape(50))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
      if (leftRatio > 0f) {
        Box(Modifier.weight(leftRatio.coerceAtLeast(0.01f)).fillMaxHeight().background(leftColor))
      }
      if (rightRatio > 0f) {
        Box(Modifier.weight(rightRatio.coerceAtLeast(0.01f)).fillMaxHeight().background(rightColor))
      }
    }
  }
}
