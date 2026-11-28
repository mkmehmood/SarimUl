package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Turns a picked / captured photo into a self-contained JPEG data URL (max 1280 px, quality 75,
 * same as the web `_compressPhoto`). Gallery `content://` grants and cache files do not survive an
 * app restart or a cache clean, which made saved production photos unreadable later; a data URL
 * always stays readable and is also what the web app stores and syncs.
 */
object PhotoStore {
  private const val MAX_DIM = 1280
  private const val QUALITY = 75

  /** Returns a data URL for [uriStr], or the original string when it cannot be read. */
  fun toDataUrl(context: Context, uriStr: String): String {
    val trimmed = uriStr.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("data:", ignoreCase = true)) return uriStr
    return runCatching {
      val uri = if (trimmed.startsWith("/")) Uri.fromFile(java.io.File(trimmed)) else Uri.parse(trimmed)
      val cr = context.contentResolver
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return uriStr
      var sample = 1
      while (bounds.outWidth / (sample * 2) >= MAX_DIM && bounds.outHeight / (sample * 2) >= MAX_DIM) sample *= 2
      var bmp = cr.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
      } ?: return uriStr
      val orientation = cr.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      } ?: ExifInterface.ORIENTATION_NORMAL
      val deg = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
      }
      if (deg != 0f) {
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        if (rotated !== bmp) bmp.recycle()
        bmp = rotated
      }
      val longest = maxOf(bmp.width, bmp.height)
      if (longest > MAX_DIM) {
        val f = MAX_DIM.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
        if (scaled !== bmp) bmp.recycle()
        bmp = scaled
      }
      val out = ByteArrayOutputStream()
      bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
      bmp.recycle()
      "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrDefault(uriStr)
  }
}
