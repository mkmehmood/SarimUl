package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object PdfExporter {
  fun sharePdfReport(
    context: Context,
    fileNamePrefix: String,
    title: String,
    subtitle: String,
    lines: List<String>,
  ): Result<Unit> {
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
}
