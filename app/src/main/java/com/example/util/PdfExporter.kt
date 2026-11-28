package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** One column of the statement table: header text, width in PDF points, and alignment. */
data class PdfColumn(val title: String, val width: Float, val right: Boolean = false)

object PdfExporter {
  /**
   * Port of the web statement PDFs (`exportEntityToPDF` / `exportCustomerToPDF` / rep statement):
   * branded header, a grid table with a coloured header row and zebra rows, a summary box, and a
   * "Generated on … | GULL AND ZUBAIR NASWAR DEALERS" + "Page i of n" footer on every page.
   */
  fun createStatementPdf(
    context: Context,
    fileBaseName: String,
    kindTitle: String,
    name: String,
    phone: String,
    rangeLabel: String,
    generatedLabel: String,
    columns: List<PdfColumn>,
    rows: List<List<String>>,
    summary: List<Pair<String, String>>,
    closingLabel: String,
    closingValue: String,
    accent: Int = Color.rgb(6, 78, 59),
  ): Result<File> {
    return try {
      val pageW = 595
      val pageH = 842
      val m = 36f
      val rowH = 18f
      val footerReserve = 56f
      val firstStart = 128f
      val nextStart = 48f
      val summaryH = 26f + summary.size * 15f + 30f

      // Pass 1: decide which rows land on which page (and whether the summary needs its own page).
      val pages = mutableListOf<IntRange>()
      var idx = 0
      var first = true
      while (true) {
        val start = if (first) firstStart else nextStart
        val cap = (((pageH - footerReserve - start) - rowH) / rowH).toInt().coerceAtLeast(1)
        val end = minOf(rows.size, idx + cap)
        pages.add(idx until end)
        idx = end
        first = false
        if (idx >= rows.size) break
      }
      run {
        val lastStart = if (pages.size == 1) firstStart else nextStart
        val usedRows = if (rows.isEmpty()) 1 else pages.last().count()
        val endY = lastStart + rowH * (1 + usedRows) + 12f
        if (endY + summaryH > pageH - footerReserve) pages.add(IntRange.EMPTY)
      }

      val doc = PdfDocument()
      fun paint(size: Float, color: Int = Color.BLACK, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        isFakeBoldText = bold
      }
      fun fit(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
      }
      val tableW = columns.sumOf { it.width.toDouble() }.toFloat()
      val grid = Paint().apply { color = Color.rgb(180, 180, 180); strokeWidth = 0.6f; style = Paint.Style.STROKE }
      val zebra = Paint().apply { color = Color.rgb(246, 247, 249); style = Paint.Style.FILL }
      val headFill = Paint().apply { color = accent; style = Paint.Style.FILL }

      pages.forEachIndexed { pi, range ->
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pi + 1).create())
        val c = page.canvas
        var y: Float
        if (pi == 0) {
          c.drawText("GULL AND ZUBAIR NASWAR DEALERS", m, 44f, paint(17f, accent, true))
          c.drawText(kindTitle, m, 64f, paint(12.5f, Color.rgb(40, 40, 40), true))
          c.drawText(fit(name, paint(11f, Color.BLACK, true), pageW - 2 * m), m, 82f, paint(11f, Color.BLACK, true))
          val meta = buildString {
            if (phone.isNotBlank() && phone != "N/A") append("Phone: $phone   ")
            append("Period: $rangeLabel   Date: $generatedLabel")
          }
          c.drawText(fit(meta, paint(9f, Color.DKGRAY), pageW - 2 * m), m, 98f, paint(9f, Color.DKGRAY))
          c.drawLine(m, 108f, pageW - m, 108f, Paint().apply { color = accent; strokeWidth = 1.2f })
          y = firstStart
        } else {
          y = nextStart
        }

        if (range.count() > 0 || rows.isEmpty()) {
          // header row
          c.drawRect(m, y, m + tableW, y + rowH, headFill)
          var x = m
          val hp = paint(8f, Color.WHITE, true)
          columns.forEach { col ->
            val t = fit(col.title, hp, col.width - 8f)
            val tx = if (col.right) x + col.width - 4f - hp.measureText(t) else x + 4f
            c.drawText(t, tx, y + 12.2f, hp)
            x += col.width
          }
          y += rowH
          if (rows.isEmpty()) {
            c.drawText("No transactions recorded for this period.", m + 4f, y + 16f, paint(10f, Color.GRAY))
            y += rowH
          }
          val bp = paint(7.6f)
          range.forEachIndexed { n, ri ->
            val row = rows[ri]
            if (n % 2 == 1) c.drawRect(m, y, m + tableW, y + rowH, zebra)
            var cx = m
            columns.forEachIndexed { ci, col ->
              val t = fit(row.getOrElse(ci) { "" }, bp, col.width - 8f)
              val tx = if (col.right) cx + col.width - 4f - bp.measureText(t) else cx + 4f
              c.drawText(t, tx, y + 12f, bp)
              cx += col.width
            }
            y += rowH
          }
          c.drawRect(m, y - rowH * (range.count() + (if (rows.isEmpty()) 1 else 0) + 1), m + tableW, y, grid)
        }

        // summary box on the last page
        if (pi == pages.lastIndex) {
          val top = y + 12f
          val box = Paint().apply { color = Color.rgb(245, 247, 250); style = Paint.Style.FILL }
          val edge = Paint().apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 0.8f }
          c.drawRoundRect(m, top, m + tableW, top + summaryH, 6f, 6f, box)
          c.drawRoundRect(m, top, m + tableW, top + summaryH, 6f, 6f, edge)
          var sy = top + 18f
          val lp = paint(8.5f, Color.DKGRAY)
          val vp = paint(8.5f, Color.BLACK, true)
          summary.forEach { (k, v) ->
            c.drawText(k, m + 10f, sy, lp)
            c.drawText(v, m + tableW - 10f - vp.measureText(v), sy, vp)
            sy += 15f
          }
          val cp = paint(10f, accent, true)
          c.drawText(closingLabel, m + 10f, sy + 8f, cp)
          c.drawText(closingValue, m + tableW - 10f - cp.measureText(closingValue), sy + 8f, cp)
        }

        // footer
        val fp = paint(7f, Color.rgb(160, 160, 160))
        val pageText = "Page ${pi + 1} of ${pages.size}"
        c.drawText(pageText, (pageW - fp.measureText(pageText)) / 2f, pageH - 30f, fp)
        val gen = "Generated on $generatedLabel | GULL AND ZUBAIR NASWAR DEALERS"
        c.drawText(gen, (pageW - fp.measureText(gen)) / 2f, pageH - 18f, fp)
        doc.finishPage(page)
      }

      val dir = File(context.cacheDir, "exports").apply { mkdirs() }
      val safe = fileBaseName.replace(Regex("[^A-Za-z0-9_.\\-]"), "_")
      val file = File(dir, "$safe.pdf")
      FileOutputStream(file).use { out -> doc.writeTo(out) }
      doc.close()
      Result.success(file)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /** Build the report PDF in the app cache and return it. */
  fun createPdfFile(
    context: Context,
    fileNamePrefix: String,
    title: String,
    subtitle: String,
    lines: List<String>,
  ): Result<File> {
    return try {
      val doc = PdfDocument()
      val pageWidth = 595
      val pageHeight = 842
      var pageNumber = 1
      var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
      var page = doc.startPage(pageInfo)
      var canvas = page.canvas

      val titlePaint = Paint().apply {
        color = Color.rgb(6, 78, 59)
        textSize = 18f
        isFakeBoldText = true
      }
      val subPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 11f
      }
      val linePaint = Paint().apply {
        color = Color.BLACK
        textSize = 10.5f
      }
      val dividerPaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
      }

      var y = 44f
      canvas.drawText("GULL AND ZUBAIR NASWAR DEALERS", 36f, y, titlePaint)
      y += 20f
      canvas.drawText(title, 36f, y, linePaint.apply { isFakeBoldText = true; textSize = 13f })
      y += 16f
      canvas.drawText(subtitle, 36f, y, subPaint)
      y += 12f
      canvas.drawLine(36f, y, pageWidth - 36f, y, dividerPaint)
      y += 20f
      linePaint.isFakeBoldText = false
      linePaint.textSize = 10.5f

      for (line in lines) {
        if (y > pageHeight - 48f) {
          doc.finishPage(page)
          pageNumber++
          pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
          page = doc.startPage(pageInfo)
          canvas = page.canvas
          y = 44f
        }
        canvas.drawText(line.take(96), 36f, y, linePaint)
        y += 16f
      }

      doc.finishPage(page)
      val dir = File(context.cacheDir, "exports").apply { mkdirs() }
      val file = File(dir, "${fileNamePrefix}_${System.currentTimeMillis()}.pdf")
      FileOutputStream(file).use { out -> doc.writeTo(out) }
      doc.close()
      Result.success(file)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Port of the web `renderJsPdfToCanvases` + `_exportDocAsImageAndOpenWhatsApp`: rasterise each PDF
   * page to a JPEG (scale 3 like the web) so the statement can be sent as pictures.
   */
  fun pdfToJpegs(context: Context, pdf: File, baseName: String, scale: Float = 3f): Result<List<File>> {
    return try {
      val dir = File(context.cacheDir, "exports").apply { mkdirs() }
      val out = mutableListOf<File>()
      android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        android.graphics.pdf.PdfRenderer(fd).use { renderer ->
          for (i in 0 until renderer.pageCount) {
            renderer.openPage(i).use { page ->
              val bmp = android.graphics.Bitmap.createBitmap(
                (page.width * scale).toInt(), (page.height * scale).toInt(), android.graphics.Bitmap.Config.ARGB_8888,
              )
              bmp.eraseColor(Color.WHITE)
              page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              val suffix = if (renderer.pageCount > 1) "-page${i + 1}" else ""
              val f = File(dir, "$baseName$suffix.jpg")
              FileOutputStream(f).use { os -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, os) }
              bmp.recycle()
              out += f
            }
          }
        }
      }
      if (out.isEmpty()) Result.failure(IllegalStateException("Nothing to export.")) else Result.success(out)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun sharePdfReport(
    context: Context,
    fileNamePrefix: String,
    title: String,
    subtitle: String,
    lines: List<String>,
  ): Result<Unit> {
    val file = createPdfFile(context, fileNamePrefix, title, subtitle, lines).getOrElse { return Result.failure(it) }
    return try {
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      val chooser = Intent.createChooser(shareIntent, "Share $title")
      chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(chooser)
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun createUnifiedEntitiesPdf(
    context: Context,
    periodLabel: String,
    generatedLabel: String,
    entities: List<com.example.data.local.PaymentEntityRecord>,
    materials: List<com.example.data.local.RawMaterialEntity>,
    transactions: List<com.example.data.local.PaymentTransactionEntity>,
    privacyHidden: Boolean = false,
  ): Result<File> {
    val supplierIds = materials.filter { !it.isDeleted && !it.supplierId.isNullOrBlank() }
      .mapNotNull { it.supplierId }.toSet()
    val supplierBalances = mutableMapOf<String, Double>()
    materials.filter { !it.isDeleted && !it.supplierId.isNullOrBlank() && it.paymentStatus == "pending" && it.pendingSupplierAmount > 0.0 }
      .forEach { mat ->
        val sid = mat.supplierId ?: return@forEach
        supplierBalances[sid] = (supplierBalances[sid] ?: 0.0) + mat.pendingSupplierAmount
      }
    val mergedEntityIds = transactions
      .filter { !it.isDeleted && !it.entityId.isNullOrBlank() && com.example.data.backup.YearCloseMerge.isMergedId(it.id) }
      .mapNotNull { it.entityId }.toSet()

    val sorted = entities.filter { !it.isDeleted && !it.isExpenseEntity }
      .map { ent ->
        val isSup = ent.isSupplier || ent.id in supplierIds
        val bal = if (isSup) (supplierBalances[ent.id] ?: ent.currentBalance) else ent.currentBalance
        Triple(ent, isSup, bal)
      }
      .sortedByDescending { kotlin.math.abs(it.third) }

    var totPayable = 0.0
    var totReceivable = 0.0
    val rows = sorted.map { (ent, isSup, bal) ->
      if (bal > 0.01) totPayable += bal else if (bal < -0.01) totReceivable += kotlin.math.abs(bal)
      val status = when {
        kotlin.math.abs(bal) < 0.01 -> "SETTLED"
        bal > 0.01 -> "PAYABLE"
        else -> "RECEIVABLE"
      }
      val balDisp = if (status == "SETTLED") "SETTLED" else SarimFormat.formatRs(kotlin.math.abs(bal), false, privacyHidden)
      val role = when {
        isSup -> "SUPPLIER"
        ent.type.equals("payor", true) -> "PAYOR"
        else -> "PAYEE"
      }
      val source = (if (ent.id in mergedEntityIds) "Year-End / " else "") + if (isSup) "Inventory" else "Transactions"
      listOf(
        ent.name + if (ent.id in mergedEntityIds) " ★" else "",
        role,
        ent.phone.ifBlank { "N/A" },
        source,
        balDisp,
        status,
      )
    }
    val columns = listOf(
      PdfColumn("Name", 136f),
      PdfColumn("Type", 64f),
      PdfColumn("Phone", 78f),
      PdfColumn("Source", 76f),
      PdfColumn("Balance", 94f, right = true),
      PdfColumn("Status", 75f, right = true),
    )
    val netPos = kotlin.math.abs(totReceivable - totPayable)
    val netTag = if (totReceivable > totPayable) "IN OUR FAVOR" else "NET PAYABLE"
    return createStatementPdf(
      context = context,
      fileBaseName = "Unified_Statement_entities_${periodLabel.replace(" ", "_")}_${SarimFormat.dateInput(System.currentTimeMillis())}",
      kindTitle = "Payment Entities — Balances & Ledger",
      name = "All Active Entities (${rows.size})",
      phone = "",
      rangeLabel = periodLabel,
      generatedLabel = generatedLabel,
      columns = columns,
      rows = rows,
      summary = listOf(
        "Total Payables" to SarimFormat.formatRs(totPayable, false, privacyHidden),
        "Total Receivables" to SarimFormat.formatRs(totReceivable, false, privacyHidden),
      ),
      closingLabel = "Net Position ($netTag)",
      closingValue = SarimFormat.formatRs(netPos, false, privacyHidden),
      accent = Color.rgb(0, 150, 136),
    )
  }

  fun createUnifiedExpensesPdf(
    context: Context,
    periodLabel: String,
    generatedLabel: String,
    expenses: List<com.example.data.local.PaymentTransactionEntity>,
    privacyHidden: Boolean = false,
  ): Result<File> {
    val operating = expenses.filter { !it.isDeleted && it.type == "EXPENSE" }.sortedBy { it.dateEpoch }
    val byName = linkedMapOf<String, Double>()
    operating.forEach { exp ->
      val key = exp.entityOrExpenseName.ifBlank { "Unnamed" }
      byName[key] = (byName[key] ?: 0.0) + exp.amount
    }
    val rows = operating.map { exp ->
      val isMerged = com.example.data.backup.YearCloseMerge.isMergedId(exp.id)
      listOf(
        SarimFormat.dateInput(exp.dateEpoch),
        exp.entityOrExpenseName.ifBlank { "-" } + if (isMerged) " ★" else "",
        exp.category?.ifBlank { "operating" } ?: "operating",
        exp.description.ifBlank { "-" },
        SarimFormat.formatRs(exp.amount, false, privacyHidden),
      )
    }
    val columns = listOf(
      PdfColumn("Date", 68f),
      PdfColumn("Name / Vendor", 118f),
      PdfColumn("Category", 76f),
      PdfColumn("Description", 165f),
      PdfColumn("Amount", 96f, right = true),
    )
    val totalAmt = operating.sumOf { it.amount }
    val summary = byName.entries.sortedByDescending { it.value }.take(8).map { (k, v) ->
      k to SarimFormat.formatRs(v, false, privacyHidden)
    }
    return createStatementPdf(
      context = context,
      fileBaseName = "Unified_Statement_expenses_${periodLabel.replace(" ", "_")}_${SarimFormat.dateInput(System.currentTimeMillis())}",
      kindTitle = "Expenses — Transaction Records",
      name = "Operating Expenses (${rows.size})",
      phone = "",
      rangeLabel = periodLabel,
      generatedLabel = generatedLabel,
      columns = columns,
      rows = rows,
      summary = summary,
      closingLabel = "Total Operating Expenses",
      closingValue = SarimFormat.formatRs(totalAmt, false, privacyHidden),
      accent = Color.rgb(217, 119, 6),
    )
  }
}
