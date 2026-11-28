package com.example.data.remote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Approval push notifications. Port of the web app's `modules/push.js` + `worker/` flow:
 *  - an admin device stores its FCM token in `users/{workspace}/fcm_tokens/{uid}_{deviceId}`;
 *  - when a team member submits something for approval, the app tells the push Worker the pending id
 *    (`POST /notify` with the Firebase ID token) and the Worker pushes a generic alert to admin devices;
 *  - tapping the alert opens the Approvals screen.
 */
object SarimPush {
  const val PUSH_WORKER_URL = "https://sarim-push.3mahmoodkhan.workers.dev/notify"
  const val CHANNEL_ID = "approvals"
  const val COL_FCM_TOKENS = "fcm_tokens"
  const val EXTRA_TYPE = "type"
  const val EXTRA_PENDING_ID = "pendingId"
  const val TYPE_APPROVAL = "approval"

  private const val PREFS = "sarim_push"
  private const val KEY_DEVICE_ID = "sarimPushDeviceId"
  private const val KEY_ROOT_UID = "tokenRootUid"
  private const val NOTIFY_GAP_MS = 15_000L
  private val RETRY_DELAYS_MS = longArrayOf(0L, 3_000L, 8_000L)

  @Volatile private var lastNotifyAt = 0L

  fun pushDocId(uid: String, deviceId: String): String = "${uid}_$deviceId"

  fun deviceId(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
    val id = UUID.randomUUID().toString().replace(Regex("[^A-Za-z0-9]"), "").take(16)
    prefs.edit().putString(KEY_DEVICE_ID, id).apply()
    return id
  }

  /** Remember where this device's token doc lives so it can be refreshed / removed later. */
  fun rememberTokenRoot(context: Context, rootUid: String?) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
      if (rootUid.isNullOrBlank()) remove(KEY_ROOT_UID) else putString(KEY_ROOT_UID, rootUid)
    }.apply()
  }

  fun tokenRoot(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROOT_UID, null)?.takeIf { it.isNotBlank() }

  /** Exactly the keys the Firestore rules allow for fcm_tokens docs. */
  fun tokenDocMap(uid: String, token: String): Map<String, Any> = mapOf(
    "uid" to uid,
    "token" to token,
    "platform" to "android",
    "updatedAt" to FieldValue.serverTimestamp(),
  )

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    val channel = NotificationChannel(CHANNEL_ID, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Items waiting for your approval"
      lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
    }
    mgr.createNotificationChannel(channel)
  }

  fun canPostNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  fun tapIntent(context: Context, pendingId: String?): Intent =
    Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
      putExtra(EXTRA_TYPE, TYPE_APPROVAL)
      if (!pendingId.isNullOrBlank()) putExtra(EXTRA_PENDING_ID, pendingId)
    }

  /** Shown when a push arrives while the app is open (the system shows it itself otherwise). */
  fun showApprovalNotification(context: Context, title: String, body: String, pendingId: String?) {
    if (!canPostNotifications(context)) return
    ensureChannel(context)
    val pi = PendingIntent.getActivity(
      context,
      (pendingId ?: "approval").hashCode(),
      tapIntent(context, pendingId),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val n = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_monochrome)
      .setContentTitle(title)
      .setContentText(body)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setAutoCancel(true)
      .setContentIntent(pi)
      .build()
    runCatching { NotificationManagerCompat.from(context).notify("approval-${pendingId ?: "x"}", 1, n) }
  }

  private suspend fun postNotify(idToken: String, pendingId: String): Int = withContext(Dispatchers.IO) {
    val conn = (URL(PUSH_WORKER_URL).openConnection() as HttpURLConnection)
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = 8_000
      conn.readTimeout = 8_000
      conn.doOutput = true
      conn.setRequestProperty("authorization", "Bearer $idToken")
      conn.setRequestProperty("content-type", "application/json")
      conn.outputStream.use { it.write("""{"pendingId":"${pendingId.replace("\"", "")}"}""".toByteArray()) }
      conn.responseCode
    } finally {
      conn.disconnect()
    }
  }

  /**
   * Tell the Worker something is waiting for approval. Same rules as the web: at most one call per
   * 15 s, and retry a few times on 403 (the pending doc may not be visible to the Worker yet).
   * The app sends only the pending id; the Worker picks recipients and writes the text.
   */
  suspend fun notifyAdmins(idTokenProvider: suspend () -> String?, pendingId: String) {
    if (pendingId.isBlank()) return
    val now = System.currentTimeMillis()
    if (now - lastNotifyAt < NOTIFY_GAP_MS) return
    lastNotifyAt = now
    for (wait in RETRY_DELAYS_MS) {
      if (wait > 0) delay(wait)
      val status = try {
        val token = idTokenProvider() ?: return
        postNotify(token, pendingId)
      } catch (_: Exception) {
        return
      }
      if (status != 403) return
    }
  }
}
