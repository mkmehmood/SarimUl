package com.example.util

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.R
import java.io.File
import java.io.FileOutputStream

/** One line of the summary box. */
data class SummaryText(val text: String, val color: Int)

/**
 * Summary box under the table. Web statements use [top] / [left] / [right] / [closing]; generic reports
 * use [kv] rows and a [closingKv] line.
 */
data class StatementTotals(
  val fill: Int,
  val top: SummaryText? = null,
  val left: SummaryText? = null,
  val right: SummaryText? = null,
  val closing: SummaryText? = null,
  val kv: List<Pair<String, String>> = emptyList(),
  val closingKv: Pair<String, String>? = null,
)

/** Everything the web-style statement PDF needs (web `exportCustomerToPDF` / `exportEntityToPDF` / rep statement). */
data class StatementPdfSpec(
  val fileBaseName: String,
  val title: String,
  val rangeLabel: String,
  val accent: Int,
  val infoLeft: List<Pair<String, String>>,
  val infoRight: List<Pair<String, String>> = emptyList(),
  /** "28 NOV 2026" shown as "Generated:" in the header. */
  val generated: String,
  /** "28 NOV 2026 at 11:13 PM" shown in the footer. */
  val footerStamp: String,
  val sectionLabel: String? = null,
  val columns: List<PdfColumn>,
  val rows: List<List<String>>,
  /** First row is the "Prior / Opening Balance" row: blue fill, and the head row takes the same blue (as on the web). */
  val openingRow: Boolean = false,
  val totals: StatementTotals? = null,
  val emptyText: String = "No transactions recorded for this period.",
)

/**
 * Android port of the web jsPDF statement layout: full-width coloured banner with the brand logo,
 * centred title, info block, accent rule, section label, grid table (22/22/62/24/24/24 mm columns,
 * 2.5 mm padding, 7.5 pt body), a centred summary box and the "Page i of n" footer.
 */
object StatementPdf {
  private const val MM = 72f / 25.4f
  private fun mm(v: Float) = v * MM

  private const val PAGE_W = 595
  private const val PAGE_H = 842

  private val GRID = Color.rgb(180, 180, 180)
  private val INK = Color.rgb(40, 40, 40)
  private val LABEL = Color.rgb(80, 80, 80)
  private val OPEN_FILL = Color.rgb(220, 235, 255)
  private val OPEN_INK = Color.rgb(30, 80, 160)

  private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = size
      this.color = color
      textAlign = align
      typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

  private fun fit(text: String, p: Paint, maxW: Float): String {
    if (p.measureText(text) <= maxW) return text
    var t = text
    while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
    return "$t…"
  }

  /** jsPDF `overflow: linebreak`: honour explicit line breaks, then wrap on spaces. */
  private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
    val out = mutableListOf<String>()
    for (para in text.split('\n')) {
      if (para.isBlank()) { out += ""; continue }
      var line = ""
      for (word in para.trim().split(' ')) {
        val cand = if (line.isEmpty()) word else "$line $word"
        if (p.measureText(cand) <= maxW) { line = cand; continue }
        if (line.isNotEmpty()) out += line
        var w = word
        while (p.measureText(w) > maxW && w.length > 1) {
          var n = w.length
          while (n > 1 && p.measureText(w.substring(0, n)) > maxW) n--
          out += w.substring(0, n)
          w = w.substring(n)
        }
        line = w
      }
      out += line
    }
    return out.ifEmpty { listOf("") }
  }

  private class CellStyle(val size: Float, val bold: Boolean, val color: Int, val fill: Int?)

  fun create(context: Context, spec: StatementPdfSpec): Result<File> = runCatching {
    val padH = mm(2.5f)
    val padV = mm(2.5f)
    val marginX = mm(14f)
    val availW = PAGE_W - 2 * marginX
    val rawW = spec.columns.map { it.width }
    val scale = if (rawW.sum() > availW) availW / rawW.sum() else 1f
    val colW = rawW.map { it * scale }
    val tableW = colW.sum()

    fun bodyStyle(rowIdx: Int, ci: Int, text: String): CellStyle {
      val col = spec.columns[ci]
      if (spec.openingRow && rowIdx == 0) return CellStyle(8f, true, OPEN_INK, OPEN_FILL)
      val color = col.colorFor?.invoke(text) ?: col.color ?: INK
      return CellStyle(7.5f, col.bold, color, null)
    }
    val headStyle = if (spec.openingRow) CellStyle(8f, true, OPEN_INK, OPEN_FILL) else CellStyle(8.5f, true, Color.WHITE, spec.accent)

    fun linesFor(text: String, st: CellStyle, w: Float) = wrap(text, paint(st.size, st.color, st.bold), w - 2 * padH)
    fun heightFor(lines: Int, size: Float) = lines * size * 1.15f + 2 * padV

    val headLines = spec.columns.mapIndexed { i, c -> linesFor(c.title, headStyle, colW[i]) }
    val headH = headLines.maxOf { heightFor(it.size, headStyle.size) }

    val rowLines = spec.rows.mapIndexed { ri, row ->
      spec.columns.indices.map { ci ->
        val text = row.getOrElse(ci) { "" }
        linesFor(text, bodyStyle(ri, ci, text), colW[ci])
      }
    }
    val rowH = spec.rows.indices.map { ri ->
      val size = bodyStyle(ri, 0, "").size
      rowLines[ri].maxOf { heightFor(it.size, size) }
    }

    // ---- pass 1: which rows land on which page, and where the summary goes ----
    val firstStart = if (spec.sectionLabel != null) mm(66f) else mm(61f)
    val nextStart = mm(10f)
    val bottom = mm(285f)
    val pages = mutableListOf<IntRange>()
    var y = firstStart + headH
    var from = 0
    spec.rows.indices.forEach { i ->
      if (y + rowH[i] > bottom && i > from) {
        pages += from until i
        from = i
        y = nextStart + headH
      }
      y += rowH[i]
    }
    pages += from until spec.rows.size
    val tableEndY = if (spec.rows.isEmpty()) firstStart + mm(15f) else y

    val totals = spec.totals
    val boxH = when {
      totals == null -> 0f
      totals.kv.isNotEmpty() -> mm(4f) + totals.kv.size * 15f + (if (totals.closingKv != null) 26f else 6f)
      totals.top != null -> mm(28f)
      else -> mm(20f)
    }
    var summaryTop = tableEndY + mm(5f)
    var summaryPage = pages.lastIndex
    if (totals != null && summaryTop + boxH > bottom) {
      pages += IntRange.EMPTY
      summaryPage = pages.lastIndex
      summaryTop = mm(20f)
    }

    val doc = PdfDocument()
    val logo = runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.brand_logo) }.getOrNull()

    pages.forEachIndexed { pi, range ->
      val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pi + 1).create())
      val c = page.canvas
      var ty: Float

      if (pi == 0) {
        // banner
        c.drawRect(0f, 0f, PAGE_W.toFloat(), mm(22f), Paint().apply { color = spec.accent; style = Paint.Style.FILL })
        logo?.let { c.drawBitmap(it, null, RectF(mm(6f), mm(2.5f), mm(6f + 23.72f), mm(2.5f + 17f)), Paint(Paint.FILTER_BITMAP_FLAG)) }
        c.drawText("GULL AND ZUBAIR NASWAR DEALERS", PAGE_W / 2f, mm(10f) + 5f, paint(16f, Color.WHITE, true, Paint.Align.CENTER))
        c.drawText("Naswar Manufacturers & Dealers", PAGE_W / 2f, mm(17f) + 3f, paint(9f, Color.WHITE, false, Paint.Align.CENTER))
        c.drawText(
          fit("${spec.title} · ${spec.rangeLabel}", paint(12f, Color.rgb(50, 50, 50), true), PAGE_W - 2 * marginX),
          PAGE_W / 2f, mm(30f) + 4f, paint(12f, Color.rgb(50, 50, 50), true, Paint.Align.CENTER),
        )
        // info block
        val lab = paint(9f, LABEL, true)
        val valP = paint(9f, LABEL)
        val left = spec.infoLeft
        val right = spec.infoRight + ("Generated" to spec.generated)
        val leftOff = maxOf(mm(22f), (left.maxOfOrNull { lab.measureText("${it.first}:") } ?: 0f) + mm(4f))
        val rightOff = maxOf(mm(22f), right.maxOf { lab.measureText("${it.first}:") } + mm(4f))
        val infoY = mm(38f)
        left.forEachIndexed { i, (k, v) ->
          val by = infoY + i * mm(5f) + 6f
          c.drawText("$k:", marginX, by, lab)
          c.drawText(fit(v, valP, PAGE_W / 2f - mm(4f) - (marginX + leftOff)), marginX + leftOff, by, valP)
        }
        right.forEachIndexed { i, (k, v) ->
          val by = infoY + i * mm(5f) + 6f
          c.drawText("$k:", PAGE_W / 2f, by, lab)
          c.drawText(fit(v, valP, PAGE_W - marginX - (PAGE_W / 2f + rightOff)), PAGE_W / 2f + rightOff, by, valP)
        }
        c.drawLine(marginX, mm(56f), PAGE_W - marginX, mm(56f), Paint().apply { color = spec.accent; strokeWidth = mm(0.5f) })
        spec.sectionLabel?.let { c.drawText(it, marginX, mm(61f) + 3f, paint(8.5f, spec.accent, true)) }
        ty = firstStart
      } else {
        ty = nextStart
      }

      if (spec.rows.isEmpty() && pi == 0) {
        c.drawText(spec.emptyText, PAGE_W / 2f, firstStart + mm(10f), paint(10f, Color.rgb(150, 150, 150), false, Paint.Align.CENTER))
      } else if (range.count() > 0) {
        val gridP = Paint().apply { color = GRID; style = Paint.Style.STROKE; strokeWidth = mm(0.15f) }
        fun drawRow(cells: List<List<String>>, styles: (Int) -> CellStyle, h: Float, isHead: Boolean) {
          var x = marginX
          spec.columns.forEachIndexed { ci, col ->
            val st = styles(ci)
            val w = colW[ci]
            st.fill?.let { c.drawRect(x, ty, x + w, ty + h, Paint().apply { color = it; style = Paint.Style.FILL }) }
            c.drawRect(x, ty, x + w, ty + h, gridP)
            val align = when {
              isHead || col.center -> Paint.Align.CENTER
              col.right -> Paint.Align.RIGHT
              else -> Paint.Align.LEFT
            }
            val p = paint(st.size, st.color, st.bold, align)
            val tx = when (align) {
              Paint.Align.CENTER -> x + w / 2f
              Paint.Align.RIGHT -> x + w - padH
              else -> x + padH
            }
            cells[ci].forEachIndexed { li, line ->
              c.drawText(line, tx, ty + padV + li * st.size * 1.15f + st.size * 0.85f, p)
            }
            x += w
          }
          ty += h
        }
        drawRow(headLines, { headStyle }, headH, true)
        range.forEach { ri ->
          drawRow(rowLines[ri], { ci -> bodyStyle(ri, ci, spec.rows[ri].getOrElse(ci) { "" }) }, rowH[ri], false)
        }
      }

      if (totals != null && pi == summaryPage) {
        val top = summaryTop
        val r = RectF(marginX, top, PAGE_W - marginX, top + boxH)
        c.drawRoundRect(r, mm(2f), mm(2f), Paint().apply { color = totals.fill; style = Paint.Style.FILL })
        c.drawRoundRect(r, mm(2f), mm(2f), Paint().apply { color = spec.accent; style = Paint.Style.STROKE; strokeWidth = mm(0.3f) })
        if (totals.kv.isNotEmpty()) {
          val lp = paint(8.5f, Color.DKGRAY)
          val vp = paint(8.5f, Color.BLACK, true, Paint.Align.RIGHT)
          var sy = top + mm(4f) + 8f
          totals.kv.forEach { (k, v) ->
            c.drawText(fit(k, lp, tableW * 0.62f), marginX + 10f, sy, lp)
            c.drawText(v, PAGE_W - marginX - 10f, sy, vp)
            sy += 15f
          }
          totals.closingKv?.let { (k, v) ->
            val cp = paint(10f, spec.accent, true)
            c.drawText(k, marginX + 10f, sy + 8f, cp)
            c.drawText(v, PAGE_W - marginX - 10f, sy + 8f, paint(10f, spec.accent, true, Paint.Align.RIGHT))
          }
        } else {
          fun center(t: SummaryText?, cx: Float, by: Float, bold: Boolean = false) {
            if (t == null || t.text.isBlank()) return
            c.drawText(t.text, cx, by, paint(8f, t.color, bold, Paint.Align.CENTER))
          }
          val hasTop = totals.top != null
          center(totals.top, PAGE_W / 2f, top + mm(7f) + 3f)
          val rowY = top + mm(if (hasTop) 15f else 8f) + 3f
          center(totals.left, PAGE_W / 4f, rowY)
          center(totals.right, PAGE_W * 3f / 4f, rowY)
          center(totals.closing, PAGE_W / 2f, top + mm(if (hasTop) 23f else 15f) + 3f, bold = true)
        }
      }

      val fp = paint(7f, Color.rgb(160, 160, 160), false, Paint.Align.CENTER)
      c.drawText("Page ${pi + 1} of ${pages.size}", PAGE_W / 2f, mm(287f), fp)
      c.drawText("Generated on ${spec.footerStamp} | GULL AND ZUBAIR NASWAR DEALERS", PAGE_W / 2f, mm(291f), fp)
      doc.finishPage(page)
    }

    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val safe = spec.fileBaseName.replace(Regex("[^A-Za-z0-9_.\\-]"), "_")
    val file = File(dir, "$safe.pdf")
    FileOutputStream(file).use { doc.writeTo(it) }
    doc.close()
    file
  }
}
