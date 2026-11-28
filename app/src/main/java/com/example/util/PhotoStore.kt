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

/** Lets the UI turn a photo key (what the web app and synced records store) into its image data. */
object PhotoResolver {
  @Volatile var lookup: (suspend (String) -> String?)? = null

  fun isDirectModel(raw: String): Boolean {
    val t = raw.trim()
    return t.startsWith("data:", true) || t.startsWith("content:", true) || t.startsWith("file:", true) ||
      t.startsWith("http", true) || t.startsWith("/")
  }
}

private fun decodeDataUrl(raw: String, maxDim: Int): Bitmap? {
  val t = raw.trim()
  val payload = if (t.startsWith("data:", true)) {
    val comma = t.indexOf(',')
    if (comma < 0) return null
    t.substring(comma + 1)
  } else t
  // Web data URLs can carry line breaks / URL-encoding; Base64.DEFAULT tolerates whitespace.
  val clean = payload.replace("%2B", "+").replace("%2F", "/").replace("%3D", "=")
  val bytes = Base64.decode(clean, Base64.DEFAULT)
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  var sample = 1
  while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * Off-main-thread, down-sampled photo model. Accepts data URLs, content/file/http URIs and photo keys
 * (e.g. "prod:<id>:0" saved by the web app), resolving keys from the local photo store.
 */
@Composable
fun rememberPhotoModel(raw: String, maxDim: Int = 2048): State<Any?> =
  produceState<Any?>(initialValue = if (PhotoResolver.isDirectModel(raw) && !raw.startsWith("data:", true)) raw else null, raw) {
    if (raw.isBlank()) { value = null; return@produceState }
    if (PhotoResolver.isDirectModel(raw) && !raw.startsWith("data:", true)) { value = raw; return@produceState }
    value = withContext(Dispatchers.Default) {
      var data: String? = if (raw.startsWith("data:", true)) raw else null
      if (data == null) data = runCatching { PhotoResolver.lookup?.invoke(raw) }.getOrNull()
      if (data == null) return@withContext null
      if (PhotoResolver.isDirectModel(data) && !data.startsWith("data:", true)) return@withContext data
      runCatching { decodeDataUrl(data, maxDim) }.getOrNull()
    }
  }
