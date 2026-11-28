package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** Coil 2.x cannot load `data:` URLs (the viewer stayed black); decode them to a Bitmap Coil can show. */
fun photoModel(raw: String): Any {
  if (!raw.startsWith("data:", ignoreCase = true)) return raw
  val comma = raw.indexOf(',')
  if (comma < 0) return raw
  return runCatching {
    val bytes = Base64.decode(raw.substring(comma + 1), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: raw
  }.getOrDefault(raw)
}

/**
 * Off-main-thread, down-sampled version of [photoModel]. Decoding a multi-megabyte base64 photo inside
 * composition blocked the UI thread (app "not responding"); this decodes on Dispatchers.Default instead
 * and shows nothing until the bitmap is ready.
 */
@Composable
fun rememberPhotoModel(raw: String, maxDim: Int = 2048): State<Any?> =
  produceState<Any?>(initialValue = if (raw.startsWith("data:", ignoreCase = true)) null else raw, raw) {
    if (!raw.startsWith("data:", ignoreCase = true)) { value = raw; return@produceState }
    value = withContext(Dispatchers.Default) {
      val comma = raw.indexOf(',')
      if (comma < 0) return@withContext null
      runCatching {
        val bytes = Base64.decode(raw.substring(comma + 1), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
      }.getOrNull()
    }
  }
