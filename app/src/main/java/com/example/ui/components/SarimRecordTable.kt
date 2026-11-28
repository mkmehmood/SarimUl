package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.theme.avatarColorForName

/** One muted detail fragment under a row's name (phone number, stock text, ...). */
data class RecordDetail(
  val text: String,
  val color: Color? = null,
  val bold: Boolean = false,
  val underline: Boolean = false,
  val onClick: (() -> Unit)? = null,
  val onLongClick: (() -> Unit)? = null,
)

/** A row of [SarimRecordTable]; mirrors the web app's `rlRowHtml` options. */
data class RecordRow(
  val key: Any,
  val name: String,
  /** Show the initials avatar (web `avatar` != 'none'). */
  val showAvatar: Boolean = true,
  val avatarBadge: String = "",
  val flag: String? = null,
  val details: List<RecordDetail> = emptyList(),
  val amount: String,
  val amountColor: Color? = null,
  val tag: String? = null,
  val onClick: (() -> Unit)? = null,
)

/** A footer stat of [SarimRecordTable]; mirrors the web `.rl-stat`. */
data class RecordStat(val label: String, val value: String, val color: Color? = null)

/**
 * Table-style list used by Customer List, CUSTOMERS and Raw Material Inventory.
 *
 * Mirrors the web app's `.table > .rl-list.rl-scroll + .rl-totals` structure: one bordered container,
 * rows separated by hairline dividers (not individual cards), the row list scrolls by itself once it is
 * taller than [maxListHeight] (web: `max-height: 460px`), and a totals footer stays pinned below it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SarimRecordTable(
  rows: List<RecordRow>,
  stats: List<RecordStat>,
  emptyTitle: String,
  emptyHint: String,
  modifier: Modifier = Modifier,
  maxListHeight: Dp = 460.dp,
) {
  val palette = LocalSarimPalette.current
  val scheme = MaterialTheme.colorScheme
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.largeIncreased,
    color = scheme.surfaceContainerLow,
  ) {
    Column {
      if (rows.isEmpty()) {
        SarimEmptyState(emptyTitle, emptyHint)
      } else {
        val scroll = rememberScrollState()
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxListHeight)
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
          rows.forEachIndexed { index, row ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant.copy(alpha = 0.4f)))
            RecordRowView(row)
          }
        }
      }
      if (stats.isNotEmpty()) {
        Surface(
          color = scheme.surfaceContainerHigh,
          shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 14.dp, vertical = 10.dp),
          ) {
            stats.forEachIndexed { index, stat ->
              if (index > 0) {
                Box(Modifier.fillMaxHeight().width(1.dp).background(scheme.outlineVariant.copy(alpha = 0.45f)))
              }
              Column(
                modifier = Modifier
                  .weight(1f)
                  .padding(start = if (index > 0) 12.dp else 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                Text(stat.label, color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                  stat.value,
                  color = stat.color ?: palette.textPrimary,
                  style = TabularNumberStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun RecordRowView(row: RecordRow) {
  val palette = LocalSarimPalette.current
  val base = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clip(RoundedCornerShape(16.dp))
  Row(
    modifier = (if (row.onClick != null) base.clickable { row.onClick.invoke() } else base)
      .padding(horizontal = 8.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (row.showAvatar) {
      InitialsAvatar(row.name, avatarColorForName(row.name), sizeDp = 40.dp, badgeText = row.avatarBadge)
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          row.name,
          color = palette.textPrimary,
          fontWeight = FontWeight.Bold,
          fontSize = 15.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        row.flag?.let { flag ->
          Surface(shape = RoundedCornerShape(50), color = palette.red.copy(alpha = 0.16f)) {
            Text(flag, color = palette.red, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
          }
        }
      }
      if (row.details.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          row.details.forEach { d ->
            val mod = if (d.onClick != null || d.onLongClick != null) {
              Modifier.combinedClickable(onClick = { d.onClick?.invoke() }, onLongClick = d.onLongClick)
            } else {
              Modifier
            }
            Text(
              d.text,
              color = d.color ?: palette.textMuted,
              fontSize = 12.sp,
              fontWeight = if (d.bold) FontWeight.Bold else FontWeight.Normal,
              textDecoration = if (d.underline) TextDecoration.Underline else null,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = mod,
            )
          }
        }
      }
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(
        row.amount,
        color = row.amountColor ?: palette.textPrimary,
        style = TabularNumberStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
        maxLines = 1,
      )
      row.tag?.let {
        Text(it, color = row.amountColor ?: palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}
