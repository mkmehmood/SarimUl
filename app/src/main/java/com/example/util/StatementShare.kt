package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream

/** One printed line of an invoice (web `invp-row`). */
data class InvoiceLine(val title: String, val sub: String, val amount: String, val incoming: Boolean = false)

/** Which web statement a share belongs to (web `exportEntityToPDF` / `exportCustomerToPDF` / rep statement). */
enum class StatementKind(val pdfTitle: String, val urduTitle: String, val filePrefix: String, val greeting: String) {
  ENTITY("Account Statement", "اکاؤنٹ اسٹیٹمنٹ", "Entity_Statement", "Account of"),
  CUSTOMER("Customer Account Statement", "کسٹمر اکاؤنٹ اسٹیٹمنٹ", "Customer_Statement", "Billed to"),
  REP("Rep Customer Account Statement", "ریپ کسٹمر اکاؤنٹ اسٹیٹمنٹ", "Rep_Customer_Statement", "Billed to"),
}

/**
 * Android ports of the web app's shareable artefacts: the invoice picture (`prepareInvoiceFile`)
 * and the Urdu statement message (`_buildStatementText`), both sent through [DirectShare].
 */
object StatementShare {
  private const val RLM = "\u200F"
  private const val LRM = "\u200E"

  /** Draws the invoice to a PNG (420 dp wide at 2x like the web render) in the app cache. */
  fun renderInvoicePng(
    context: Context,
    fileBaseName: String,
    name: String,
    phone: String,
    dateLabel: String,
    previousBalance: String?,
    lines: List<InvoiceLine>,
    totalLines: List<InvoiceLine>,
    dueLabel: String,
    dueText: String,
    greeting: String = "Billed to",
    dueNegative: Boolean = false,
  ): Result<File> = runCatching {
    val scale = 2f
    val w = (420 * scale).toInt()
    val pad = 24 * scale
    val rowH = 44 * scale
    val rows = buildList {
      if (previousBalance != null) add(InvoiceLine("Previous balance", "", previousBalance))
      addAll(lines)
      addAll(totalLines)
    }
    val headerH = 150 * scale
    val footerH = 120 * scale
    val h = (headerH + rows.size * rowH + footerH).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.rgb(255, 252, 245))

    val ink = Color.rgb(31, 41, 55)
    val muted = Color.rgb(107, 114, 128)
    val amber = Color.rgb(245, 158, 11)
    val green = Color.rgb(5, 150, 105)
    fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = size * scale
      this.color = color
      typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    fun fit(text: String, p: Paint, maxW: Float): String {
      if (p.measureText(text) <= maxW) return text
      var t = text
      while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
      return "$t…"
    }

    val brand = paint(20f, ink, true)
    val tag = paint(11f, amber, true)
    c.drawText("Gull and Zubair", pad, 40 * scale, brand)
    c.drawText("NASWAR DEALERS", pad, 58 * scale, tag)
    val rule = Paint().apply { color = amber; strokeWidth = 2f * scale }
    c.drawLine(pad, 68 * scale, pad + 60 * scale, 68 * scale, rule)

    c.drawText(greeting, pad, 94 * scale, paint(11f, muted))
    c.drawText(fit(name, paint(17f, ink, true), w - 2 * pad), pad, 114 * scale, paint(17f, ink, true))
    if (phone.isNotBlank()) c.drawText(phone, pad, 131 * scale, paint(11.5f, muted))
    val datePaint = paint(11.5f, muted)
    c.drawText(dateLabel, w - pad - datePaint.measureText(dateLabel), 114 * scale, datePaint)

    val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(235, 229, 216); style = Paint.Style.STROKE; strokeWidth = 1f * scale }
    val panelRect = RectF(pad / 2, headerH - 8 * scale, w - pad / 2, headerH + rows.size * rowH + 4 * scale)
    c.drawRoundRect(panelRect, 16 * scale, 16 * scale, panel)
    c.drawRoundRect(panelRect, 16 * scale, 16 * scale, border)

    var y = headerH
    val titleP = paint(13.5f, ink, true)
    val subP = paint(10.5f, muted)
    rows.forEachIndexed { i, r ->
      val amtP = paint(13.5f, if (r.incoming) green else ink, true)
      val amt = (if (r.incoming) "+ " else "") + r.amount
      val amtW = amtP.measureText(amt)
      c.drawText(amt, w - pad - amtW, y + 26 * scale, amtP)
      val maxW = w - 2 * pad - amtW - 12 * scale
      c.drawText(fit(r.title, titleP, maxW), pad, y + 20 * scale, titleP)
      if (r.sub.isNotBlank()) c.drawText(fit(r.sub, subP, maxW), pad, y + 35 * scale, subP)
      if (i < rows.size - 1) c.drawLine(pad, y + rowH, w - pad, y + rowH, Paint().apply { color = Color.rgb(240, 235, 225); strokeWidth = 1f * scale })
      y += rowH
    }

    y += 30 * scale
    c.drawText(dueLabel, pad, y, paint(12f, muted, true))
    val dueP = paint(24f, if (dueNegative) green else ink, true)
    c.drawText(dueText, w - pad - dueP.measureText(dueText), y + 2 * scale, dueP)
    val thanks = paint(11f, muted)
    val t = "Thank you for your business"
    c.drawText(t, (w - thanks.measureText(t)) / 2f, y + 44 * scale, thanks)

    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val safe = fileBaseName.replace(Regex("[^A-Za-z0-9_.\\-]"), "_")
    val file = File(dir, "$safe.png")
    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()
    file
  }

  /** One statement line, shared by the PDF table and the text message (web `_captureAutoTables`). */
  data class StatementLine(
    val date: String,
    /** Machine key: SALE/CASH/CREDIT/COLLECTION/MONEY_OUT/REP_RETURN/PAYMENT_IN/PAYMENT_OUT. */
    val type: String,
    val details: String,
    val debit: String,
    val credit: String,
    val balance: String,
  )

  private val URDU_KIND = mapOf(
    "SALE" to "فروخت", "CASH" to "نقد", "CREDIT" to "ادھار", "COLLECTION" to "وصولی",
    "MONEY_OUT" to "رقم واپسی", "REP_RETURN" to "واپسی",
    "PAYMENT_IN" to "وصولی", "PAYMENT_OUT" to "ادائیگی", "OPENING" to "سابقہ بقایا",
  )

  /** Port of the web `_buildStatementText`: an RTL Urdu WhatsApp/SMS-friendly statement. */
  fun buildStatementText(
    kind: StatementKind,
    name: String,
    phone: String,
    dateLabel: String,
    rangeLabel: String,
    lines: List<StatementLine>,
    closing: String,
  ): String {
    val out = mutableListOf<String>()
    fun add(t: String) = out.add(if (t.isEmpty()) "" else RLM + t)
    add("*گل اینڈ زبیر نسوار ڈیلرز*")
    add("${kind.urduTitle} · $rangeLabel")
    add("")
    add("نام: $name")
    if (phone.isNotBlank() && phone != "N/A") add("فون: $LRM$phone$LRM")
    add("تاریخ اجراء: $dateLabel")
    if (lines.isNotEmpty()) {
      add("")
      add("------------------------")
      lines.forEach { r ->
        val parts = buildList {
          add(URDU_KIND[r.type] ?: r.type)
          if (r.details.isNotBlank() && r.type != "OPENING") add(r.details)
          if (r.debit.isNotBlank()) add("بل: ${r.debit}")
          if (r.credit.isNotBlank()) add("وصولی: ${r.credit}")
          if (r.balance.isNotBlank()) add("بقایا: ${r.balance}")
        }
        add("\u2022 ${r.date} | ${parts.joinToString(" | ")}")
      }
    }
    if (closing.isNotBlank()) {
      add("")
      add("------------------------")
      add("*کل بقایا: $closing*")
    }
    return out.joinToString("\n")
  }
}
