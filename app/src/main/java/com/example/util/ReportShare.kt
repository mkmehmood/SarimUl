package com.example.util

import android.content.Context
import android.graphics.Color

/**
 * Any tabular report (customer list, unified entities / expenses, expense category, daily ledger)
 * described once, so it can be sent exactly like an account statement: PDF, PDF to WhatsApp,
 * image to WhatsApp, WhatsApp text or SMS.
 */
data class ReportSpec(
  val fileBase: String,
  val title: String,
  val name: String,
  val rangeLabel: String,
  val columns: List<PdfColumn>,
  val rows: List<List<String>>,
  val summary: List<Pair<String, String>>,
  val closingLabel: String,
  val closingValue: String,
  val accent: Int = Color.rgb(40, 167, 69),
  val phone: String = "",
  /** Overrides the "Generated" stamp; defaults to today's date. */
  val generatedLabel: String? = null,
)

/** Shared sender behind [StatementSender] and every other report: one PDF layout, one image path, one text format. */
object ReportSender {
  /** Builds the statement-style PDF for [spec]. */
  fun createPdf(context: Context, spec: ReportSpec, generated: String): Result<java.io.File> =
    PdfExporter.createStatementPdf(
      context = context,
      fileBaseName = spec.fileBase,
      kindTitle = spec.title,
      name = spec.name,
      phone = spec.phone,
      rangeLabel = spec.rangeLabel,
      generatedLabel = generated,
      columns = spec.columns,
      rows = spec.rows,
      summary = spec.summary,
      closingLabel = spec.closingLabel,
      closingValue = spec.closingValue,
      accent = spec.accent,
    )

  /** PDF, PDF to WhatsApp or image to WhatsApp. Returns an error message, or null when the share sheet opened. */
  fun sendFile(context: Context, spec: ReportSpec, format: String, generated: String): String? {
    val pdf = createPdf(context, spec, generated).getOrElse { return it.localizedMessage ?: "Unable to create the report." }
    return sendRendered(context, pdf, spec.title, spec.phone, spec.fileBase, format)
  }

  /** Sends an already rendered PDF as PDF, PDF to WhatsApp, or page images to WhatsApp. */
  fun sendRendered(context: Context, pdf: java.io.File, title: String, phone: String, fileBase: String, format: String): String? {
    return when (format) {
      StatementFormat.PDF ->
        if (DirectShare.shareViaChooser(context, pdf, "application/pdf", title) == ShareOutcome.FAILED) "Unable to share the report." else null
      StatementFormat.PDF_WHATSAPP ->
        if (DirectShare.shareFiles(context, listOf(pdf), "application/pdf", phone, title = title) == ShareOutcome.FAILED) "Unable to share the report." else null
      else -> {
        val imgs = PdfExporter.pdfToJpegs(context, pdf, fileBase.replace(Regex("[^A-Za-z0-9_.\\-]"), "_"))
          .getOrElse { return it.localizedMessage ?: "Unable to create the report image." }
        if (DirectShare.shareFiles(context, imgs, "image/jpeg", phone, title = title) == ShareOutcome.FAILED) "Unable to share the report." else null
      }
    }
  }

  fun send(context: Context, spec: ReportSpec, format: String): String? {
    val generated = spec.generatedLabel ?: SarimFormat.formatDate(System.currentTimeMillis())
    if (format == StatementFormat.WHATSAPP_TEXT || format == StatementFormat.SMS) {
      val text = StatementShare.buildReportText(
        title = spec.title,
        name = spec.name,
        phone = spec.phone,
        dateLabel = generated,
        rangeLabel = spec.rangeLabel,
        columns = spec.columns,
        rows = spec.rows,
        summary = spec.summary,
        closingLabel = spec.closingLabel,
        closingValue = spec.closingValue,
      )
      val outcome = if (format == StatementFormat.SMS) DirectShare.sendSms(context, text, spec.phone)
      else DirectShare.shareTextToWhatsApp(context, text, spec.phone, spec.title)
      return if (outcome == ShareOutcome.NOT_INSTALLED || outcome == ShareOutcome.FAILED) "No app available to send the message." else null
    }
    return sendFile(context, spec, format, generated)
  }
}
