package com.example.util

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.data.local.CustomerEntity
import com.example.data.local.SaleTransactionEntity
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import kotlin.math.abs

/** One printed row of the customer list. */
data class CustomerListRow(
  val name: String,
  val phone: String,
  val address: String,
  val debit: Double,
  val credit: Double,
  val qty: Double,
  val lastSaleEpoch: Long?,
) {
  val net: Double get() = debit - credit
}

/**
 * Exact port of the web "All Customers — Complete List" / "My Customers" PDF
 * (`exportAllCustomersPDF`: jsPDF landscape A4 + autoTable grid).
 */
object CustomerListPdf {
  private const val MM = 72f / 25.4f
  private fun mm(v: Float) = v * MM

  private val HEADER_GREEN = Color.rgb(40, 167, 69)
  private val RED = Color.rgb(220, 53, 69)
  private val GRAY = Color.rgb(100, 100, 100)
  private val GRID = Color.rgb(180, 180, 180)
  private val TOTAL_FILL = Color.rgb(235, 255, 235)

  /** Builds rows from the customer records and their sales (debit − credit = running balance). */
  fun rowsFor(customers: List<CustomerEntity>, sales: List<SaleTransactionEntity>): List<CustomerListRow> {
    val lastByCustomer = sales.filter { !it.isDeleted }.groupBy { it.customerName.trim().lowercase() }
      .mapValues { (_, v) -> v.maxOf { it.dateEpoch } }
    return customers.filter { !it.isDeleted }.map { c ->
      val credit = c.totalReceived.coerceAtLeast(0.0)
      CustomerListRow(
        name = c.name,
        phone = c.phone.ifBlank { "N/A" },
        address = c.address.ifBlank { "N/A" },
        debit = c.currentCredit + credit,
        credit = credit,
        qty = c.totalQuantityKg,
        lastSaleEpoch = lastByCustomer[c.name.trim().lowercase()],
      )
    }.sortedByDescending { it.net }
  }

  private fun amt(v: Double) = SarimFormat.formatSouthAsian(v, 2, true)

  private class Col(val title: String, val widthMm: Float, val align: Paint.Align)

  private val COLS = listOf(
    Col("Customer Name", 42f, Paint.Align.LEFT),
    Col("Phone", 26f, Paint.Align.CENTER),
    Col("Address", 44f, Paint.Align.LEFT),
    Col("Total Debit", 26f, Paint.Align.RIGHT),
    Col("Total Credit", 26f, Paint.Align.RIGHT),
    Col("Net Balance", 30f, Paint.Align.CENTER),
    Col("Qty (kg)", 20f, Paint.Align.RIGHT),
    Col("Last Sale", 22f, Paint.Align.CENTER),
  )

  fun create(
    context: Context,
    fileName: String,
    title: String,
    rows: List<CustomerListRow>,
    privacyHidden: Boolean = false,
  ): Result<File> = runCatching {
    require(rows.isNotEmpty()) { "No customers found to export." }
    val pageW = 842
    val pageH = 595
    val pageWmm = pageW / MM
    val pageHmm = pageH / MM
    val now = Date()
    val dayMonthYear = SarimFormat.formatDate(now.time)
    val clock = SarimFormat.formatClock(now.time)
    val generated = "$dayMonthYear at $clock"

    fun paint(size: Float, color: Int = Color.BLACK, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = align
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
      }

    // ---- table content (web customerRows) ----
    var totDebt = 0.0; var totPaid = 0.0; var totQty = 0.0; var totNet = 0.0
    var cntDebtors = 0; var cntSettled = 0
    val body = rows.map { c ->
      val net = c.net
      totDebt += c.debit; totPaid += c.credit; totQty += c.qty; totNet += net
      if (net > 0.01) cntDebtors++ else cntSettled++
      val mask = "••••"
      listOf(
        c.name,
        c.phone,
        c.address.take(35),
        if (privacyHidden) mask else if (c.debit > 0) amt(c.debit) else "-",
        if (privacyHidden) mask else if (c.credit > 0) amt(c.credit) else "-",
        if (privacyHidden) mask else if (abs(net) < 0.01) "SETTLED" else if (net > 0) amt(net) else "OVERPAID\n" + amt(abs(net)),
        amt(c.qty),
        c.lastSaleEpoch?.let { SarimFormat.formatDate(it) } ?: "-",
      )
    }
    val totalRow = listOf(
      "TOTAL (${rows.size} customers)", "", "",
      if (privacyHidden) "••••" else amt(totDebt),
      if (privacyHidden) "••••" else amt(totPaid),
      if (privacyHidden) "••••" else amt(abs(totNet)) + (if (totNet > 0) "" else if (totNet < 0) "" else "SETTLED"),
      amt(totQty), "",
    )
    val allRows = body + listOf(totalRow)

    // ---- geometry ----
    val padding = mm(2f)
    val leftMargin = mm(14f)
    val bottomLimit = pageH - mm(14f)
    val headerFont = 8.5f
    val bodyFont = 7.5f
    val lineFactor = 1.15f
    val colW = COLS.map { mm(it.widthMm) }

    fun wrap(text: String, p: Paint, maxW: Float): List<String> {
      val out = mutableListOf<String>()
      for (para in text.split("\n")) {
        if (para.isEmpty()) { out += ""; continue }
        var line = ""
        for (word in para.split(" ")) {
          val trial = if (line.isEmpty()) word else "$line $word"
          if (p.measureText(trial) <= maxW) { line = trial; continue }
          if (line.isNotEmpty()) { out += line; line = "" }
          var w = word
          while (p.measureText(w) > maxW && w.length > 1) {
            var n = w.length
            while (n > 1 && p.measureText(w.substring(0, n)) > maxW) n--
            out += w.substring(0, n); w = w.substring(n)
          }
          line = w
        }
        out += line
      }
      return out
    }

    fun cellPaint(row: Int, col: Int, isTotal: Boolean, text: String): Paint {
      val size = if (isTotal) headerFont else bodyFont
      var bold = isTotal
      var color = Color.BLACK
      when (col) {
        3 -> { color = RED; bold = true }
        4 -> { color = HEADER_GREEN; bold = true }
        5 -> {
          bold = true
          if (!isTotal) color = when {
            text == "SETTLED" -> GRAY
            text.contains("OVERPAID") -> HEADER_GREEN
            else -> RED
          }
        }
      }
      return paint(size, color, bold)
    }

    class Laid(val lines: List<List<String>>, val heightPt: Float)
    fun layout(cells: List<String>, isHeader: Boolean, isTotal: Boolean, row: Int): Laid {
      val fs = if (isHeader || isTotal) headerFont else bodyFont
      var maxLines = 1
      val per = cells.mapIndexed { i, t ->
        val p = if (isHeader) paint(headerFont, Color.WHITE, true) else cellPaint(row, i, isTotal, t)
        wrap(t, p, colW[i] - 2 * padding).also { if (it.size > maxLines) maxLines = it.size }
      }
      return Laid(per, maxLines * fs * lineFactor + 2 * padding)
    }

    val headerLaid = layout(COLS.map { it.title }, true, false, -1)

    // ---- paginate ----
    val firstStartY = mm(43f)
    val nextStartY = mm(14f)
    val pages = mutableListOf<MutableList<Int>>()
    var cur = mutableListOf<Int>()
    var y = firstStartY + headerLaid.heightPt
    val laidRows = allRows.mapIndexed { i, r -> layout(r, false, i == allRows.lastIndex, i) }
    laidRows.forEachIndexed { i, lr ->
      if (y + lr.heightPt > bottomLimit && cur.isNotEmpty()) {
        pages += cur; cur = mutableListOf(); y = nextStartY + headerLaid.heightPt
      }
      cur += i; y += lr.heightPt
    }
    pages += cur
    val finalY = y

    val doc = PdfDocument()
    val logo = runCatching {
      val id = context.resources.getIdentifier("brand_logo", "drawable", context.packageName)
      if (id != 0) BitmapFactory.decodeResource(context.resources, id) else null
    }.getOrNull()

    pages.forEachIndexed { pi, rowIdx ->
      val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pi + 1).create())
      val c = page.canvas
      var top = nextStartY
      if (pi == 0) {
        c.drawRect(0f, 0f, pageW.toFloat(), mm(22f), Paint().apply { color = HEADER_GREEN })
        logo?.let { c.drawBitmap(it, null, Rect(mm(6f).toInt(), mm(2.5f).toInt(), (mm(6f) + mm(23.72f)).toInt(), (mm(2.5f) + mm(17f)).toInt()), Paint(Paint.FILTER_BITMAP_FLAG)) }
        c.drawText("GULL AND ZUBAIR NASWAR DEALERS", pageW / 2f, mm(10f), paint(16f, Color.WHITE, true, Paint.Align.CENTER))
        c.drawText("Naswar Manufacturers & Dealers", pageW / 2f, mm(17f), paint(9f, Color.WHITE, false, Paint.Align.CENTER))
        c.drawText(title, pageW / 2f, mm(30f), paint(12f, Color.rgb(50, 50, 50), true, Paint.Align.CENTER))
        c.drawText("Generated: $generated", pageW / 2f, mm(36f), paint(8.5f, GRAY, false, Paint.Align.CENTER))
        c.drawLine(mm(14f), mm(39f), pageW - mm(14f), mm(39f), Paint().apply { color = HEADER_GREEN; strokeWidth = mm(0.5f) })
        top = firstStartY
      }
      val grid = Paint().apply { color = GRID; style = Paint.Style.STROKE; strokeWidth = mm(0.15f) }

      fun drawRow(laid: Laid, yTop: Float, isHeader: Boolean, isTotal: Boolean, rowIndex: Int, cells: List<String>) {
        var x = leftMargin
        val fs = if (isHeader || isTotal) headerFont else bodyFont
        cells.forEachIndexed { i, text ->
          val w = colW[i]
          val rect = android.graphics.RectF(x, yTop, x + w, yTop + laid.heightPt)
          if (isHeader) c.drawRect(rect, Paint().apply { color = HEADER_GREEN })
          else if (isTotal) c.drawRect(rect, Paint().apply { color = TOTAL_FILL })
          c.drawRect(rect, grid)
          val p = when {
            isHeader -> paint(headerFont, if (i == 5) RED else Color.WHITE, true)
            else -> cellPaint(rowIndex, i, isTotal, text)
          }
          val align = if (isHeader) Paint.Align.CENTER else COLS[i].align
          p.textAlign = align
          val tx = when (align) {
            Paint.Align.LEFT -> x + padding
            Paint.Align.CENTER -> x + w / 2f
            else -> x + w - padding
          }
          laid.lines[i].forEachIndexed { li, line ->
            c.drawText(line, tx, yTop + padding + li * fs * lineFactor + fs * 0.88f, p)
          }
          x += w
        }
      }
      drawRow(headerLaid, top, true, false, -1, COLS.map { it.title })
      var yy = top + headerLaid.heightPt
      rowIdx.forEach { ri ->
        drawRow(laidRows[ri], yy, false, ri == allRows.lastIndex, ri, allRows[ri])
        yy += laidRows[ri].heightPt
      }
      if (pi == pages.lastIndex) {
        val afterY = finalY + mm(6f)
        if (afterY < pageH - mm(25f)) {
          val totOutstandingStr = if (privacyHidden) "••••" else amt(totNet)
          c.drawText(
            "Customers with outstanding debt: $cntDebtors | Settled accounts: $cntSettled | Total outstanding: $totOutstandingStr",
            mm(14f), afterY, paint(8f, GRAY),
          )
        }
      }
      val foot = paint(7f, Color.rgb(160, 160, 160), false, Paint.Align.CENTER)
      c.drawText("Generated on $generated | GULL AND ZUBAIR NASWAR DEALERS", pageW / 2f, pageH - mm(5f), foot)
      c.drawText("Page ${pi + 1} of ${pages.size}", pageW / 2f, pageH - mm(9f), foot)
      doc.finishPage(page)
    }

    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName)
    FileOutputStream(file).use { doc.writeTo(it) }
    doc.close()
    file
  }
}
