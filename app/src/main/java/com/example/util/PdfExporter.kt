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
data class PdfColumn(
  val title: String,
  val width: Float,
  val right: Boolean = false,
  val center: Boolean = false,
  val bold: Boolean = false,
  val color: Int? = null,
  /** Per-cell text colour (web `didParseCell`), e.g. type / balance columns. */
  val colorFor: ((String) -> Int?)? = null,
)

object PdfExporter {
  /**
   * Generic report in the web statement look (banner, info block, grid table, summary box, footer).
   * Account statements build a [StatementPdfSpec] directly; every other report comes through here.
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
  ): Result<File> = StatementPdf.create(
    context,
    StatementPdfSpec(
      fileBaseName = fileBaseName,
      title = kindTitle,
      rangeLabel = rangeLabel,
      accent = accent,
      infoLeft = buildList {
        add("Name" to name)
        if (phone.isNotBlank() && phone != "N/A") add("Phone" to phone)
      },
      generated = generatedLabel.substringBefore(" at "),
      footerStamp = generatedLabel,
      columns = columns.map { if (it.right) it.copy(bold = true) else it },
      rows = rows,
      totals = StatementTotals(
        fill = Color.rgb(245, 247, 250),
        kv = summary.filter { it.second.isNotBlank() },
        closingKv = if (closingLabel.isNotBlank() || closingValue.isNotBlank()) closingLabel to closingValue else null,
      ),
      emptyText = "No records for this period.",
    ),
  )

  /** Statement-style PDF for any [ReportSpec]. */
  fun createReportPdf(context: Context, spec: ReportSpec, generatedLabel: String): Result<File> =
    ReportSender.createPdf(context, spec, generatedLabel)

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
      AppLockGuard.suppressFor()
      val chooser = Intent.createChooser(shareIntent, "Share $title")
      chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(chooser)
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /** Web `exportUnifiedData` file-name period key: today / week / month / all. */
  internal fun unifiedPeriodKey(periodLabel: String): String = when (periodLabel) {
    "Today" -> "today"
    "This Week" -> "week"
    "This Month" -> "month"
    else -> "all"
  }

  fun createUnifiedEntitiesPdf(
    context: Context,
    periodLabel: String,
    generatedLabel: String,
    entities: List<com.example.data.local.PaymentEntityRecord>,
    materials: List<com.example.data.local.RawMaterialEntity>,
    transactions: List<com.example.data.local.PaymentTransactionEntity>,
    privacyHidden: Boolean = false,
  ): Result<File> =
    createReportPdf(context, unifiedEntitiesSpec(periodLabel, entities, materials, transactions, privacyHidden), generatedLabel)

  fun unifiedEntitiesSpec(
    periodLabel: String,
    entities: List<com.example.data.local.PaymentEntityRecord>,
    materials: List<com.example.data.local.RawMaterialEntity>,
    transactions: List<com.example.data.local.PaymentTransactionEntity>,
    privacyHidden: Boolean = false,
  ): ReportSpec {
    val supplierIds = materials.filter { !it.isDeleted && !it.supplierId.isNullOrBlank() }
      .mapNotNull { it.supplierId }.toSet()
    val mergedEntityIds = transactions
      .filter { !it.isDeleted && !it.entityId.isNullOrBlank() && com.example.data.backup.YearCloseMerge.isMergedId(it.id) }
      .mapNotNull { it.entityId }.toSet()

    val sorted = entities.filter { !it.isDeleted && !it.isExpenseEntity }
      .map { ent ->
        val isSup = ent.isSupplier || ent.id in supplierIds
        // Same figure the Unified Records list shows (opening balance + supplier credits included).
        val bal = ent.currentBalance
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
        ent.type.equals("payee", true) -> "PAYEE"
        else -> "PAYOR"
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
    return ReportSpec(
      fileBase = "Unified_Statement_entities_${unifiedPeriodKey(periodLabel)}_${SarimFormat.dateInput(System.currentTimeMillis())}",
      title = "Payment Entities — Balances & Ledger",
      name = "All Active Entities (${rows.size})",
      rangeLabel = periodLabel,
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
  ): Result<File> =
    createReportPdf(context, unifiedExpensesSpec(periodLabel, expenses, privacyHidden), generatedLabel)

  fun unifiedExpensesSpec(
    periodLabel: String,
    expenses: List<com.example.data.local.PaymentTransactionEntity>,
    privacyHidden: Boolean = false,
  ): ReportSpec {
    val operating = expenses.filter { !it.isDeleted && it.type == "EXPENSE" }
      .sortedWith(compareBy<com.example.data.local.PaymentTransactionEntity> { if (com.example.data.backup.YearCloseMerge.isMergedId(it.id)) 0 else 1 }.thenBy { it.dateEpoch })
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
    val summary = if (byName.size > 1) {
      byName.entries.sortedByDescending { it.value }.map { (k, v) -> k to SarimFormat.formatRs(v, false, privacyHidden) }
    } else {
      emptyList()
    }
    return ReportSpec(
      fileBase = "Unified_Statement_expenses_${unifiedPeriodKey(periodLabel)}_${SarimFormat.dateInput(System.currentTimeMillis())}",
      title = "Expenses — Transaction Records",
      name = "Operating Expenses (${rows.size})",
      rangeLabel = periodLabel,
      columns = columns,
      rows = rows,
      summary = summary,
      closingLabel = "Total Operating Expenses",
      closingValue = SarimFormat.formatRs(totalAmt, false, privacyHidden),
      accent = Color.rgb(217, 119, 6),
    )
  }

  /**
   * Mirrors `exportExpenseOverlayToPDF` in `modules/utilities-payments.js`:
   * exports filtered records for a single operating expense category with year-end merged highlight,
   * running cumulative total, and summary totals.
   */
  fun createExpenseCategoryPdf(
    context: Context,
    expenseName: String,
    rangeLabel: String,
    generatedLabel: String,
    expenses: List<com.example.data.local.PaymentTransactionEntity>,
    privacyHidden: Boolean = false,
  ): Result<File> =
    createReportPdf(context, expenseCategorySpec(expenseName, rangeLabel, generatedLabel, expenses, privacyHidden), generatedLabel)

  fun expenseCategorySpec(
    expenseName: String,
    rangeLabel: String,
    generatedLabel: String,
    expenses: List<com.example.data.local.PaymentTransactionEntity>,
    privacyHidden: Boolean = false,
  ): ReportSpec {
    val chronological = expenses
      .filter { !it.isDeleted && it.type == "EXPENSE" }
      .sortedWith(compareBy<com.example.data.local.PaymentTransactionEntity> { it.dateEpoch }.thenBy { it.createdAt })
    val merged = chronological.filter { com.example.data.backup.YearCloseMerge.isMergedId(it.id) }
    val normal = chronological.filterNot { com.example.data.backup.YearCloseMerge.isMergedId(it.id) }
    var running = 0.0
    val rows = (merged + normal).map { tx ->
      running += tx.amount
      val isMerged = com.example.data.backup.YearCloseMerge.isMergedId(tx.id)
      val desc = buildString {
        if (isMerged) append("[Year-End Summary] ")
        append(tx.description.ifBlank { "Operating expense" })
      }
      listOf(
        SarimFormat.dateInput(tx.dateEpoch),
        desc,
        if (tx.addToLedger) "Ledger" else "Payments",
        SarimFormat.formatRs(tx.amount, false, privacyHidden),
        SarimFormat.formatRs(running, false, privacyHidden),
      )
    }
    val columns = listOf(
      PdfColumn("Date", 74f),
      PdfColumn("Description", 205f),
      PdfColumn("Source", 66f),
      PdfColumn("Amount", 88f, right = true),
      PdfColumn("Running Total", 90f, right = true),
    )
    val totalAmt = chronological.sumOf { it.amount }
    val mergedAmt = merged.sumOf { it.amount }
    val summary = buildList {
      add("Period" to rangeLabel)
      add("Matching Records" to "${chronological.size}")
      if (merged.isNotEmpty()) {
        add("Year-End Opening Merged (${merged.size})" to SarimFormat.formatRs(mergedAmt, false, privacyHidden))
      }
    }
    val safeName = expenseName.replace(Regex("[^A-Za-z0-9]"), "_").ifBlank { "Expense" }
    return ReportSpec(
      fileBase = "Expense_${safeName}_${rangeLabel.replace(" ", "_")}_${SarimFormat.dateInput(System.currentTimeMillis())}",
      title = "Expense History: $expenseName",
      name = expenseName,
      rangeLabel = rangeLabel,
      columns = columns,
      rows = rows,
      summary = summary,
      closingLabel = "Total ($expenseName)",
      closingValue = SarimFormat.formatRs(totalAmt, false, privacyHidden),
      accent = Color.rgb(255, 149, 0),
      generatedLabel = generatedLabel,
    )
  }
}
