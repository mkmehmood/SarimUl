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

  /**
   * Draws the invoice to a PNG exactly like the web `invoicePaperHtml` (420 css px wide at 2x):
   * logo card, Cinzel brand, arc dial with end dots, dotted line-item panel and the large due amount.
   */
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
    progress: Float = 1f,
    showProgress: Boolean = true,
  ): Result<File> = runCatching {
    val S = 2f
    val W = 420f
    val pad = 16f
    val innerW = W - 2 * pad

    val ink = Color.rgb(5, 150, 105)          // --success (ip-ink)
    val strong = Color.BLACK
    val danger = Color.rgb(220, 38, 38)
    val accent = Color.rgb(16, 185, 129)
    val cyan = Color.rgb(8, 145, 178)
    val amber = Color.rgb(245, 158, 11)
    fun alpha(c: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    fun font(res: String, fallback: Typeface): Typeface {
      val id = context.resources.getIdentifier(res, "font", context.packageName)
      return if (id != 0) (androidx.core.content.res.ResourcesCompat.getFont(context, id) ?: fallback) else fallback
    }
    val cinzel = font("cinzel", Typeface.SERIF)
    val jakarta = font("plus_jakarta_sans", Typeface.SANS_SERIF)
    val playfair = font("playfair_display", Typeface.SERIF)

    fun mk(face: Typeface, size: Float, color: Int, weight: Int, letterEm: Float = 0f, italic: Boolean = false) =
      Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = if (italic) Typeface.create(face, Typeface.ITALIC) else face
        textSize = size * S
        this.color = color
        letterSpacing = letterEm
        if (android.os.Build.VERSION.SDK_INT >= 26) fontVariationSettings = "'wght' $weight"
        else if (weight >= 600) isFakeBoldText = true
      }

    fun wrap(text: String, p: Paint, maxPx: Float): List<String> {
      val out = mutableListOf<String>()
      var line = ""
      for (word in text.split(" ")) {
        val trial = if (line.isEmpty()) word else "$line $word"
        if (p.measureText(trial) <= maxPx) { line = trial; continue }
        if (line.isNotEmpty()) { out += line; line = "" }
        var w = word
        while (p.measureText(w) > maxPx && w.length > 1) {
          var n = w.length
          while (n > 1 && p.measureText(w.substring(0, n)) > maxPx) n--
          out += w.substring(0, n); w = w.substring(n)
        }
        line = w
      }
      out += line
      return out
    }

    val logo = runCatching {
      val id = context.resources.getIdentifier("brand_logo", "drawable", context.packageName)
      if (id != 0) android.graphics.BitmapFactory.decodeResource(context.resources, id) else null
    }.getOrNull()

    // ---- paints ----
    val brandP = mk(cinzel, 21.6f, strong, 600, 0.1f)
    val tagP = mk(jakarta, 10.88f, ink, 700, 0.3f)
    val greetP = mk(jakarta, 10.24f, ink, 700, 0.26f)
    val nameP = mk(cinzel, 23.2f, strong, 600, 0.03f)
    val phoneP = mk(jakarta, 11.84f, strong, 600, 0.16f)
    val whenKP = mk(jakarta, 10.88f, ink, 700, 0.22f)
    val whenVP = mk(jakarta, 10.88f, strong, 700, 0.22f)
    val titleP = mk(jakarta, 13f, strong, 700)
    val subP = mk(jakarta, 11f, strong, 500)
    val dueKP = mk(jakarta, 10.24f, ink, 700, 0.26f)
    val dueVP = mk(cinzel, 40f, if (dueNegative) ink else strong, 600, 0.02f)
    val thanksP = mk(playfair, 15.2f, strong, 500, 0f, italic = true)

    // ---- line items ----
    class Row(val title: String, val sub: String, val amount: String, val kind: Int, val incoming: Boolean) // kind 0 normal,1 prev,2 recv
    val rows = buildList {
      if (previousBalance != null) add(Row("Previous balance", "", previousBalance, 1, false))
      lines.forEach { add(Row(it.title, it.sub, it.amount, 0, it.incoming)) }
      totalLines.forEach { add(Row(it.title, it.sub, it.amount, 2, false)) }
    }
    val lhTitle = 13f * 1.3f
    val lhSub = 11f * 1.3f
    val rowPadX = 16f
    val textLeft = pad + 1f + rowPadX
    val textRight = W - pad - 1f - rowPadX
    class Laid(val r: Row, val titleLines: List<String>, val subLines: List<String>, val h: Float, val amountPaint: Paint, val titlePaint: Paint)
    val laid = rows.map { r ->
      val amountText = (if (r.incoming) "+ " else "") + r.amount
      val amtPaint = mk(jakarta, 13.5f, when { r.kind == 1 -> danger; r.incoming -> ink; else -> strong }, 800)
      amtPaint.fontFeatureSettings = "tnum"
      val tp = if (r.kind == 1) mk(jakarta, 13f, danger, 700) else if (r.kind == 2) mk(jakarta, 13f, strong, 600) else titleP
      val maxW = (textRight - textLeft - 12f) * S - amtPaint.measureText(amountText) / 1f
      val tl = wrap(r.title, tp, maxW)
      val sl = if (r.sub.isBlank()) emptyList() else wrap(r.sub, subP, maxW)
      val h = 5f + tl.size * lhTitle + (if (sl.isNotEmpty()) 1f + sl.size * lhSub else 0f) + 5f
      Laid(r, tl, sl, h, amtPaint, tp)
    }

    // ---- name / dial ----
    val nameLines = wrap(name.uppercase(), nameP, (innerW - 60f) * S)
    val greetH = 10.24f * 1.3f
    val nameH = nameLines.size * 23.2f * 1.1f
    val phoneH = if (phone.isNotBlank() && phone != "N/A") 2f + 11.84f * 1.3f else 0f
    val whenH = 5f + 10.88f * 1.3f
    val dialContent = greetH + 2f + nameH + phoneH + whenH + 3f
    val dialH = maxOf(80f, dialContent)
    val dialW = minOf(innerW, 330f)

    val logoH = if (logo != null) innerW * logo.height / logo.width else 0f
    val brandH = 21.6f * 1.3f
    val tagH = 10.88f * 1.3f
    val panelH = 2f + laid.sumOf { it.h.toDouble() }.toFloat()

    // ---- vertical layout (css px) ----
    var y = 14f
    val logoTop = y
    y += logoH
    val brandTop = y + 6f
    val tagTop = brandTop + brandH + 1f
    val ruleTop = tagTop + tagH + 5f
    y = ruleTop + 1f
    val dialTop = y + 4f
    y = dialTop + dialH
    val panelTop = y
    y += panelH
    val dueKTop = y + 8f
    val dueVTop = dueKTop + 10.24f * 1.3f + 1f
    val thanksTop = dueVTop + 40f * 1.05f + 6f
    val footTop = thanksTop + 15.2f * 1.3f + 6f
    val totalH = footTop + 6f + 12f

    val bmp = Bitmap.createBitmap((W * S).toInt(), (totalH * S).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.WHITE)

    // glow (web .invp-glow)
    run {
      val cx = W / 2f * S; val cy = (-60f + 0.42f * 520f) * S
      val rad = 398.2f * S
      val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(
          cx, cy, rad,
          intArrayOf(alpha(accent, 0.26f), alpha(cyan, 0.14f), alpha(cyan, 0f)),
          floatArrayOf(0f, 0.38f, 0.68f), android.graphics.Shader.TileMode.CLAMP,
        )
      }
      c.drawRect(0f, 0f, W * S, totalH * S, glow)
    }

    // logo card
    if (logo != null) {
      val rect = RectF(pad * S, logoTop * S, (pad + innerW) * S, (logoTop + logoH) * S)
      val r = 14f * S
      val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(30f * S / 2f, 0f, 12f * S, alpha(accent, 0.22f))
      }
      c.drawRoundRect(rect, r, r, shadow)
      val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f * S; color = alpha(accent, 0.14f) }
      val ringRect = RectF(rect); ringRect.inset(-2.5f * S, -2.5f * S)
      c.drawRoundRect(ringRect, r + 2.5f * S, r + 2.5f * S, ring)
      c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
      c.save()
      val clip = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
      c.clipPath(clip)
      val img = RectF(rect); img.inset(2f * S, 2f * S)
      c.drawBitmap(logo, null, img, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
      c.restore()
      c.drawRoundRect(
        RectF(rect).apply { inset(1f * S, 1f * S) }, r - S, r - S,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * S; color = alpha(ink, 0.55f) },
      )
    }

    fun drawCentered(text: String, p: Paint, baselineCss: Float) {
      p.textAlign = Paint.Align.LEFT
      val w = p.measureText(text)
      c.drawText(text, W * S / 2f - w / 2f, baselineCss * S, p)
    }
    fun baseline(top: Float, size: Float, lh: Float) = top + (lh - size * 1.3f) / 2f + size * 1.02f

    drawCentered("GULL AND ZUBAIR", brandP, baseline(brandTop, 21.6f, brandH))
    drawCentered("NASWAR DEALERS", tagP, baseline(tagTop, 10.88f, tagH))

    // rule (gradient line with dot)
    run {
      val x0 = (W / 2f - 60f) * S; val x1 = (W / 2f + 60f) * S
      val sh = android.graphics.LinearGradient(
        x0, 0f, x1, 0f,
        intArrayOf(alpha(strong, 0f), alpha(strong, 0.32f), alpha(strong, 0f)), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP,
      )
      c.drawRect(x0, ruleTop * S, x1, (ruleTop + 1f) * S, Paint().apply { shader = sh })
      val cx = W / 2f * S; val cy = (ruleTop + 0.5f) * S
      c.drawCircle(cx, cy, 4.5f * S, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(accent, 0.14f) })
      c.drawCircle(cx, cy, 2.5f * S, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
    }

    // dial svg (viewBox 0 70 300 80 scaled to dialW wide, bottom aligned in the dial box)
    run {
      val k = dialW / 300f
      val svgH = 80f * k
      val left = (W - dialW) / 2f
      val svgTop = dialTop + dialH - svgH
      c.save()
      c.clipRect((left - 20f) * S, svgTop * S, (left + dialW + 20f) * S, (svgTop + svgH + 20f) * S)
      fun vx(x: Float) = (left + x * k) * S
      fun vy(yv: Float) = (svgTop + (yv - 70f) * k) * S
      val arc = RectF(vx(150f - 124f), vy(150f - 124f), vx(150f + 124f), vy(150f + 124f))
      // fade mask: transparent at y=70 -> opaque at y=100
      val layer = c.saveLayer(RectF((left - 20f) * S, svgTop * S, (left + dialW + 20f) * S, (svgTop + svgH + 20f) * S), null)
      val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * k * S; color = alpha(strong, 0.32f)
        strokeCap = Paint.Cap.BUTT
        val total = (Math.PI * 124.0 * k * S).toFloat()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(total * 0.0045f, total * 0.0135f), 0f)
      }
      val path = android.graphics.Path().apply { moveTo(vx(26f), vy(150f)); arcTo(arc, 180f, 180f, false) }
      c.drawPath(path, track)
      if (showProgress) {
        val p = progress.coerceIn(0f, 1f)
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * k * S; color = ink; strokeCap = Paint.Cap.ROUND }
        if (p > 0f) c.drawArc(arc, 180f, 180f * p, false, fillP)
      }
      val fade = Paint().apply {
        shader = android.graphics.LinearGradient(0f, vy(70f), 0f, vy(100f), Color.TRANSPARENT, Color.BLACK, android.graphics.Shader.TileMode.CLAMP)
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
      }
      c.drawRect((left - 20f) * S, svgTop * S, (left + dialW + 20f) * S, vy(100f), fade)
      // below y=100 stays fully opaque; DST_IN with the gradient clamps to opaque beyond the end
      c.restoreToCount(layer)
      fun dot(cx: Float, cy: Float, r: Float) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader = android.graphics.RadialGradient(cx, cy, 11f * k * S, alpha(amber, 0.55f), alpha(amber, 0f), android.graphics.Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, 11f * k * S, glow)
        c.drawCircle(cx, cy, r * k * S, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = amber })
      }
      dot(vx(26f), vy(150f), 3.8f); dot(vx(274f), vy(150f), 3.8f)
      if (showProgress) {
        val p = progress.coerceIn(0f, 1f)
        val dx = 150f - 124f * Math.cos(Math.PI * p).toFloat()
        val dy = 150f - 124f * Math.sin(Math.PI * p).toFloat()
        dot(vx(dx), vy(dy), 3.6f)
      }
      c.restore()
    }

    // dial text block (bottom aligned inside the dial box)
    run {
      var ty = dialTop + dialH - dialContent
      drawCentered(greeting.uppercase(), greetP, baseline(ty, 10.24f, greetH)); ty += greetH + 2f
      nameLines.forEachIndexed { i, ln ->
        drawCentered(ln, nameP, ty + i * 23.2f * 1.1f + 23.2f * 0.92f)
      }
      ty += nameH
      if (phoneH > 0f) { ty += 2f; drawCentered(phone, phoneP, baseline(ty, 11.84f, 11.84f * 1.3f)); ty += 11.84f * 1.3f }
      ty += 5f
      val kW = whenKP.measureText("DATE"); val vW = whenVP.measureText(dateLabel.uppercase())
      val total = kW + 10f * S + vW
      val sx = W * S / 2f - total / 2f
      val bl = baseline(ty, 10.88f, 10.88f * 1.3f) * S
      c.drawText("DATE", sx, bl, whenKP)
      c.drawText(dateLabel.uppercase(), sx + kW + 10f * S, bl, whenVP)
    }

    // panel
    run {
      val rect = RectF(pad * S, panelTop * S, (pad + innerW) * S, (panelTop + panelH) * S)
      c.drawRoundRect(rect, 20f * S, 20f * S, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(Color.WHITE, 0.72f) })
      c.drawRoundRect(
        RectF(rect).apply { inset(0.5f * S, 0.5f * S) }, 20f * S, 20f * S,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * S; color = alpha(strong, 0.22f) },
      )
      var ry = panelTop + 1f
      laid.forEachIndexed { i, L ->
        val isLast = i == laid.lastIndex
        if (L.r.kind == 2) {
          c.drawLine((pad + 1f + rowPadX) * S, ry * S, (W - pad - 1f - rowPadX) * S, ry * S, Paint().apply { color = alpha(strong, 0.30f); strokeWidth = 1f * S })
        }
        var ty = ry + 5f
        L.titleLines.forEach { ln ->
          c.drawText(ln, textLeft * S, (ty + 13f * 1.05f) * S, L.titlePaint); ty += lhTitle
        }
        if (L.subLines.isNotEmpty()) {
          ty += 1f
          L.subLines.forEach { ln -> c.drawText(ln, textLeft * S, (ty + 11f * 1.02f) * S, subP); ty += lhSub }
        }
        val amountText = (if (L.r.incoming) "+ " else "") + L.r.amount
        val aw = L.amountPaint.measureText(amountText)
        val firstBaseline = ry + 5f + 13f * 1.05f
        c.drawText(amountText, textRight * S - aw, (if (L.r.kind == 2) ry + 5f + 13f * 1.05f else firstBaseline + 0.4f) * S, L.amountPaint)
        ry += L.h
        if (!isLast && L.r.kind != 2 && laid[i + 1].r.kind != 2) {
          val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * S; color = alpha(strong, 0.26f); strokeCap = Paint.Cap.ROUND
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.01f, 4f * S), 0f)
          }
          c.drawLine(textLeft * S, (ry - 1f) * S, textRight * S, (ry - 1f) * S, dot)
        }
      }
    }

    // due
    drawCentered(dueLabel.uppercase(), dueKP, baseline(dueKTop, 10.24f, 10.24f * 1.3f))
    run {
      val glowP = mk(cinzel, 40f, alpha(accent, 0.25f), 600, 0.02f).apply { setShadowLayer(30f * S / 2f, 0f, 0f, alpha(accent, 0.25f)) }
      val w = dueVP.measureText(dueText)
      c.drawText(dueText, W * S / 2f - w / 2f, (dueVTop + 40f * 0.95f) * S, glowP)
      c.drawText(dueText, W * S / 2f - w / 2f, (dueVTop + 40f * 0.95f) * S, dueVP)
    }
    drawCentered("Thank you for your business", thanksP, baseline(thanksTop, 15.2f, 15.2f * 1.3f))
    c.drawCircle(W * S / 2f, (footTop + 3f) * S, 5f * S, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(accent, 0.14f) })
    c.drawCircle(W * S / 2f, (footTop + 3f) * S, 3f * S, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })

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
    fun emit(t: String) = out.add(if (t.isEmpty()) "" else RLM + t)
    emit("*گل اینڈ زبیر نسوار ڈیلرز*")
    emit("${kind.urduTitle} · $rangeLabel")
    emit("")
    emit("نام: $name")
    if (phone.isNotBlank() && phone != "N/A") emit("فون: $LRM$phone$LRM")
    emit("تاریخ اجراء: $dateLabel")
    if (lines.isNotEmpty()) {
      emit("")
      emit("------------------------")
      lines.forEach { r ->
        val parts = buildList<String> {
          add(URDU_KIND[r.type] ?: r.type)
          if (r.details.isNotBlank() && r.type != "OPENING") add(r.details)
          if (r.debit.isNotBlank()) add("بل: ${r.debit}")
          if (r.credit.isNotBlank()) add("وصولی: ${r.credit}")
          if (r.balance.isNotBlank()) add("بقایا: ${r.balance}")
        }
        emit("\u2022 ${r.date} | ${parts.joinToString(" | ")}")
      }
    }
    if (closing.isNotBlank()) {
      emit("")
      emit("------------------------")
      emit("*کل بقایا: $closing*")
    }
    return out.joinToString("\n")
  }
}
