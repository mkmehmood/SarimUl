package com.example.util

import android.content.Context
import android.graphics.Color

/** Everything needed to send one account statement (web `promptStatementShare` flow). */
data class StatementSpec(
  val kind: StatementKind,
  val name: String,
  val phone: String,
  val rangeLabel: String,
  val lines: List<StatementShare.StatementLine>,
  /** Shown in the PDF summary box, e.g. "Total Debit (Sales)" to a formatted amount. */
  val summary: List<Pair<String, String>>,
  val closingLabel: String,
  val closingValue: String,
  /** Text for the "Total balance" line of the WhatsApp / SMS message. */
  val closingForText: String,
)

object StatementFormat {
  const val PDF = "PDF"
  const val PDF_WHATSAPP = "PDF_WA"
  const val IMAGE_WHATSAPP = "IMAGE"
  const val WHATSAPP_TEXT = "WHATSAPP"
  const val SMS = "SMS"
}

/**
 * Port of the web statement senders: PDF file, PDF/images straight into the customer's WhatsApp chat
 * (`_exportDocAsImageAndOpenWhatsApp`), the Urdu text message (`_shareStatementText`) and SMS.
 * Returns an error message, or null when the share sheet / app was opened.
 */
object StatementSender {
  private fun accent(kind: StatementKind): Int = when (kind) {
    StatementKind.CUSTOMER -> Color.rgb(5, 122, 85)
    StatementKind.REP -> Color.rgb(109, 40, 217)
    StatementKind.ENTITY -> Color.rgb(30, 64, 175)
  }

  fun send(context: Context, spec: StatementSpec, format: String): String? {
    val now = System.currentTimeMillis()
    val generated = SarimFormat.formatDate(now)
    val safeName = spec.name.replace(Regex("[^A-Za-z0-9]"), "_")
    val base = "${spec.kind.filePrefix}_${safeName}_${SarimFormat.dateInput(now)}"

    if (format == StatementFormat.WHATSAPP_TEXT || format == StatementFormat.SMS) {
      val text = StatementShare.buildStatementText(
        kind = spec.kind,
        name = spec.name,
        phone = spec.phone,
        dateLabel = generated,
        rangeLabel = spec.rangeLabel,
        lines = spec.lines,
        closing = spec.closingForText,
      )
      val outcome = if (format == StatementFormat.SMS) DirectShare.sendSms(context, text, spec.phone)
      else DirectShare.shareTextToWhatsApp(context, text, spec.phone, spec.kind.pdfTitle)
      return if (outcome == ShareOutcome.NOT_INSTALLED || outcome == ShareOutcome.FAILED) "No app available to send the message." else null
    }

    val isEntity = spec.kind == StatementKind.ENTITY
    val columns = listOf(
      PdfColumn("Date", 62f),
      PdfColumn("Type", 62f),
      PdfColumn("Details", 147f),
      PdfColumn(if (isEntity) "Payment Out" else "Debit (Sale)", 84f, right = true),
      PdfColumn(if (isEntity) "Payment In" else "Credit (Rcvd)", 84f, right = true),
      PdfColumn("Balance", 84f, right = true),
    )
    val report = ReportSpec(
      fileBase = base,
      title = spec.kind.pdfTitle,
      name = spec.name,
      rangeLabel = spec.rangeLabel,
      columns = columns,
      rows = spec.lines.map { listOf(it.date, it.type.replace('_', ' '), it.details, it.debit, it.credit, it.balance) },
      summary = spec.summary,
      closingLabel = spec.closingLabel,
      closingValue = spec.closingValue,
      accent = accent(spec.kind),
      phone = spec.phone,
    )
    return ReportSender.sendFile(context, report, format, generated)
  }
}
