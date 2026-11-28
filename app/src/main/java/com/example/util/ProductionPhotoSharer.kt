package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.text.TextPaint
import android.util.Base64
import com.example.data.local.JsonCodecs
import com.example.data.local.ProductionEntryEntity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Port of web `modules/prod-photos.js`: renders a statement-green metadata caption footer onto each
 * production photo (Date, Store, Gross/Container/Net weight, and Value unless redacted; never cost or profit),
 * saves JPEG files into cache, copies summary text to the clipboard, and shares multi-file intents.
 */
object ProductionPhotoSharer {
  fun buildCaptionLines(entry: ProductionEntryEntity, privacyHidden: Boolean): List<String> {
    val dateLabel = entry.dateStr.ifBlank { SarimFormat.dateInput(entry.dateEpoch) }
    val storeLabel = entry.storeName.ifBlank { entry.storeKey }
    return buildList {
      add("$storeLabel · $dateLabel")
      add(
        "Gross: ${SarimFormat.formatKg(entry.grossWeightKg)} | Container: ${SarimFormat.formatKg(entry.containerWeightKg)} | Net: ${SarimFormat.formatKg(entry.netWeightKg)}",
      )
      if (!privacyHidden) {
        add("Value: ${SarimFormat.formatRs(entry.totalValue, false, false)}")
      }
    }
  }

  /** Statement-style (Urdu, RTL) message shared with the photos: same builder as statements / SMS. */
  fun formatSummaryText(entries: List<ProductionEntryEntity>, privacyHidden: Boolean): String =
    entries.joinToString("\n\n") { entry ->
      val dateLabel = entry.dateStr.ifBlank { SarimFormat.dateInput(entry.dateEpoch) }
      val storeLabel = entry.storeName.ifBlank { entry.storeKey }
      val columns = listOf(
        PdfColumn("Item", 100f),
        PdfColumn("Value", 100f, right = true),
      )
      val rows = buildList {
        add(listOf("Gross Weight", SarimFormat.formatKg(entry.grossWeightKg)))
        add(listOf("Container", SarimFormat.formatKg(entry.containerWeightKg)))
        add(listOf("Net Weight", SarimFormat.formatKg(entry.netWeightKg)))
        if (!privacyHidden) {
          add(listOf("Total Value", SarimFormat.formatRs(entry.totalValue, false, false)))
        }
      }
      StatementShare.buildReportText(
        title = "Production Record",
        name = storeLabel,
        phone = "",
        dateLabel = dateLabel,
        rangeLabel = dateLabel,
        columns = columns,
        rows = rows,
        summary = emptyList(),
        closingLabel = "",
        closingValue = "",
      )
    }

  suspend fun createCaptionedFiles(
    context: Context,
    entries: List<ProductionEntryEntity>,
    privacyHidden: Boolean,
    resolve: (suspend (String) -> String?)? = null,
  ): List<File> = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    val outFiles = mutableListOf<File>()
    var counter = 0
    for (entry in entries) {
      val uris = JsonCodecs.decodeStrings(entry.photoUrisJson)
      if (uris.isEmpty()) continue
      val captionLines = listOf("GULL AND ZUBAIR NASWAR DEALERS") + buildCaptionLines(entry, privacyHidden)
      val safeStore = entry.storeName.ifBlank { entry.storeKey }.replace(Regex("\\W+"), "")
      val safeDate = entry.dateStr.ifBlank { SarimFormat.dateInput(entry.dateEpoch) }
      for ((idx, uriStr) in uris.withIndex()) {
        // Direct data / URI first; then the photo store (web-synced entries hold photo keys, and
        // Android mirrors every attached photo under "prod:<id>:<index>").
        var src = decodeSourceBitmap(context, uriStr)
        if (src == null && resolve != null) {
          for (key in listOf(uriStr, "prod:${entry.id}:$idx")) {
            val data = resolve(key) ?: continue
            src = decodeSourceBitmap(context, data)
            if (src != null) break
          }
        }
        if (src == null) continue
        val captioned = renderCaptionedBitmap(src, captionLines)
        if (captioned !== src) src.recycle()
        counter++
        val outFile = File(dir, "production-${safeDate}-${safeStore}-$counter.jpg")
        runCatching {
          FileOutputStream(outFile).use { fos ->
            captioned.compress(Bitmap.CompressFormat.JPEG, 90, fos)
          }
          outFiles.add(outFile)
        }
        captioned.recycle()
      }
    }
    outFiles
  }

  suspend fun shareCaptionedPhotos(
    context: Context,
    entries: List<ProductionEntryEntity>,
    privacyHidden: Boolean,
    resolve: (suspend (String) -> String?)? = null,
  ): Result<Pair<Int, ShareOutcome>> = runCatching {
    val files = createCaptionedFiles(context, entries, privacyHidden, resolve)
    require(files.isNotEmpty()) {
      "The saved photo(s) can no longer be read (the original file was removed). Re-attach the photo to this record and try again."
    }
    val summary = formatSummaryText(entries.filter { JsonCodecs.decodeStrings(it.photoUrisJson).isNotEmpty() }, privacyHidden)
    withContext(Dispatchers.Main) {
      runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Production details", summary))
      }
    }
    val outcome = DirectShare.shareFiles(
      context = context,
      files = files,
      mime = "image/jpeg",
      text = summary,
      title = if (files.size == 1) "Share Production Photo" else "Share ${files.size} Production Photos",
    )
    files.size to outcome
  }

  private fun decodeSourceBitmap(context: Context, uriStr: String): Bitmap? {
    val raw = uriStr.trim()
    if (raw.isEmpty()) return null
    val trimmed = if (raw.startsWith("/")) "file://$raw" else raw
    return runCatching {
      val rawBytes: ByteArray? = if (trimmed.startsWith("data:", ignoreCase = true)) {
        val comma = trimmed.indexOf(',')
        if (comma > 0) Base64.decode(trimmed.substring(comma + 1), Base64.DEFAULT) else null
      } else {
        null
      }
      if (rawBytes != null) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, 1600)
        BitmapFactory.decodeByteArray(
          rawBytes,
          0,
          rawBytes.size,
          BitmapFactory.Options().apply { inSampleSize = sample },
        )
      } else {
        val uri = Uri.parse(trimmed)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, 1600)
        val bmp = context.contentResolver.openInputStream(uri)?.use {
          BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = context.contentResolver.openInputStream(uri)?.use {
          ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        rotateIfNeeded(bmp, orientation)
      }
    }.getOrNull()
  }

  private fun computeInSampleSize(w: Int, h: Int, maxDim: Int): Int {
    var sample = 1
    while ((w / sample) > maxDim * 2 || (h / sample) > maxDim * 2) {
      sample *= 2
    }
    return sample.coerceAtLeast(1)
  }

  private fun rotateIfNeeded(bmp: Bitmap, orientation: Int): Bitmap {
    val deg = when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> 90f
      ExifInterface.ORIENTATION_ROTATE_180 -> 180f
      ExifInterface.ORIENTATION_ROTATE_270 -> 270f
      else -> 0f
    }
    if (deg == 0f) return bmp
    val m = Matrix().apply { postRotate(deg) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    if (rotated !== bmp) bmp.recycle()
    return rotated
  }

  private fun renderCaptionedBitmap(src: Bitmap, captionLines: List<String>): Bitmap {
    val targetW = src.width.coerceAtMost(1280).coerceAtLeast(480)
    val scale = targetW.toFloat() / src.width.toFloat().coerceAtLeast(1f)
    val targetH = (src.height * scale).roundToInt().coerceAtLeast(240)
    val pad = (targetW * 0.032f).roundToInt().coerceAtLeast(16)
    val fontSize = (targetW * 0.031f).coerceIn(20f, 38f)

    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = fontSize
      isFakeBoldText = true
    }
    val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = fontSize * 0.88f
    }

    val maxTextW = (targetW - pad * 2).toFloat()
    val renderedLines = mutableListOf<Pair<String, Boolean>>()
    captionLines.forEachIndexed { idx, line ->
      val p = if (idx == 0) titlePaint else subPaint
      wrapWords(line, p, maxTextW).forEach { wrapped ->
        renderedLines.add(wrapped to (idx == 0))
      }
    }

    val lineHeight = (fontSize * 1.35f).roundToInt()
    val bandH = pad * 2 + renderedLines.size * lineHeight
    val out = Bitmap.createBitmap(targetW, targetH + bandH, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val imgRect = android.graphics.Rect(0, 0, targetW, targetH)
    c.drawBitmap(src, null, imgRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

    val bandPaint = Paint().apply { color = Color.rgb(40, 167, 69) } // statement banner green
    c.drawRect(0f, targetH.toFloat(), targetW.toFloat(), (targetH + bandH).toFloat(), bandPaint)

    val accentPaint = Paint().apply { color = Color.rgb(30, 126, 52) }
    c.drawRect(0f, targetH.toFloat(), targetW.toFloat(), targetH + 4f, accentPaint)

    var y = targetH + pad + fontSize
    renderedLines.forEach { (txt, isTitle) ->
      c.drawText(txt, pad.toFloat(), y, if (isTitle) titlePaint else subPaint)
      y += lineHeight
    }
    return out
  }

  private fun wrapWords(text: String, paint: Paint, maxW: Float): List<String> {
    val words = text.split(" ")
    val out = mutableListOf<String>()
    var cur = ""
    for (w in words) {
      val next = if (cur.isEmpty()) w else "$cur $w"
      if (paint.measureText(next) > maxW && cur.isNotEmpty()) {
        out.add(cur)
        cur = w
      } else {
        cur = next
      }
    }
    if (cur.isNotEmpty()) out.add(cur)
    return out
  }
}
