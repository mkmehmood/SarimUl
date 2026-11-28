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
  /** Header info block (label to value), left and right of the web statement header. */
  val infoLeft: List<Pair<String, String>> = emptyList(),
  val infoRight: List<Pair<String, String>> = emptyList(),
  /** An opening ("Prior") row leads the table. */
  val hasPrior: Boolean = false,
  val totals: StatementTotals? = null,
  /** Banner / rule colour; defaults per [StatementKind]. */
  val accent: Int? = null,
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
    StatementKind.CUSTOMER -> Color.rgb(40, 167, 69)
    StatementKind.REP -> Color.rgb(79, 70, 229)
    StatementKind.ENTITY -> Color.rgb(0, 122, 200)
  }

  private val ORANGE = Color.rgb(200, 100, 0)
  private val RED = Color.rgb(220, 53, 69)
  private val GREEN = Color.rgb(40, 167, 69)
  private val GRAY = Color.rgb(100, 100, 100)

  /** Web `styleStatementRow` type column colours (later matches win, like the web). */
  private fun typeColor(t: String): Int? {
    var c: Int? = null
    if (t.contains("CASH")) c = GREEN
    if (t.contains("CREDIT")) c = ORANGE
    if (t.contains("COLLECTION")) c = GREEN
    if (t.contains("PARTIAL")) c = ORANGE
    if (t.contains("OLD DEBT")) c = RED
    if (t.contains("MONEY OUT")) c = RED
    return c
  }

  private fun saleBalanceColor(t: String): Int? = when {
    t == "SETTLED" -> GRAY
    t.contains("OVERPAID") -> GREEN
    else -> RED
  }

  private fun entityTypeColor(t: String): Int? = when (t) {
    "OUT" -> RED
    "CR" -> ORANGE
    else -> GREEN
  }

  private fun entityBalanceColor(t: String): Int? = when {
    t.contains("SETTLED") -> GRAY
    t.contains("OWE") -> RED
    else -> GREEN
  }

  private fun mm(v: Float) = v * 72f / 25.4f

  /** Builds the web-layout statement PDF for [spec]. */
  fun createPdf(context: Context, spec: StatementSpec, fileBase: String, now: Long): Result<java.io.File> {
    val isEntity = spec.kind == StatementKind.ENTITY
    val isRep = spec.kind == StatementKind.REP
    val columns = if (isEntity) listOf(
      PdfColumn("Date", mm(22f), center = true),
      PdfColumn("Description", mm(55f)),
      PdfColumn("Type", mm(13f), center = true, bold = true, colorFor = { entityTypeColor(it) }),
      PdfColumn("Payment OUT", mm(28f), right = true, bold = true, color = RED),
      PdfColumn("Payment IN", mm(28f), right = true, bold = true, color = GREEN),
      PdfColumn("Running Balance", mm(30f), center = true, bold = true, colorFor = { entityBalanceColor(it) }),
    ) else listOf(
      PdfColumn("Date", mm(22f), center = true),
      PdfColumn("Type", mm(22f), center = true, bold = true, colorFor = { typeColor(it) }),
      PdfColumn("Details", mm(if (isRep) 52f else 62f)),
      PdfColumn("Debit (Sale)", mm(if (isRep) 27f else 24f), right = true, bold = true, color = RED),
      PdfColumn("Credit (Rcvd)", mm(if (isRep) 27f else 24f), right = true, bold = true, color = GREEN),
      PdfColumn("Balance", mm(if (isRep) 26f else 24f), center = true, bold = true, colorFor = { saleBalanceColor(it) }),
    )
    val rows = spec.lines.map { l ->
      val opening = l.type == "OPENING"
      if (isEntity) {
        val type = when (l.type) { "PAYMENT_IN" -> "IN"; "PAYMENT_OUT" -> "OUT"; "OPENING" -> "—"; else -> l.type }
        listOf(l.date, l.details, type, l.debit, l.credit, l.balance)
      } else {
        listOf(l.date, if (opening) "—" else l.type, l.details, l.debit, l.credit, l.balance)
      }
    }
    return StatementPdf.create(
      context,
      StatementPdfSpec(
        fileBaseName = fileBase,
        title = spec.kind.pdfTitle,
        rangeLabel = spec.rangeLabel,
        accent = spec.accent ?: accent(spec.kind),
        infoLeft = spec.infoLeft.ifEmpty { listOf("Name" to spec.name, "Phone" to spec.phone.ifBlank { "N/A" }) },
        infoRight = spec.infoRight,
        generated = SarimFormat.formatDate(now),
        footerStamp = "${SarimFormat.formatDate(now)} at ${SarimFormat.formatClock(now)}",
        sectionLabel = if (isEntity) "PAYMENT TRANSACTIONS" else "INDIVIDUAL TRANSACTIONS",
        columns = columns,
        rows = rows,
        openingRow = spec.hasPrior,
        totals = spec.totals,
        emptyText = if (isEntity) "No payment transactions recorded for this period." else "No sales recorded for this period.",
      ),
    )
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

    val pdf = createPdf(context, spec, base, now).getOrElse { return it.localizedMessage ?: "Unable to create the statement." }
    return ReportSender.sendRendered(context, pdf, spec.kind.pdfTitle, spec.phone, base, format)
      ?.replace("report", "statement")
  }
}
