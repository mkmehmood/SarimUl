package com.example.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.PendingIntent
import com.example.R
import com.example.data.remote.SarimPush

/**
 * Port of the web `notify.js`: system notifications for the user's own actions (saved / failed / deleted).
 * Messages are de-duplicated, batched into one notification, rate limited (burst of 4 per 30s), muted for a
 * startup quiet period, and tapping one opens the record via the same target path as push taps.
 */
class OwnActionNotifier(context: Context) {
  private val app = context.applicationContext
  private val handler = Handler(Looper.getMainLooper())
  private val startedAt = System.currentTimeMillis()
  private val queue = LinkedHashMap<String, Item>()
  private val recent = LinkedHashMap<String, Long>()
  private val posted = ArrayDeque<Long>()
  private var firstQueuedAt = 0L
  private var flushRunnable: Runnable? = null

  private data class Item(val text: String, val failed: Boolean, val target: SarimPush.NotificationTarget?)

  companion object {
    private const val DEDUPE_MS = 15_000L
    private const val QUIET_MS = 600L
    private const val MAX_WAIT_MS = 2_000L
    private const val STARTUP_QUIET_MS = 25_000L
    private const val BURST_LIMIT = 4
    private const val BURST_WINDOW_MS = 30_000L
    const val CHANNEL_ID = "own_actions"
    private const val GROUP_TAG = "own-actions"
    private val ACTION_WORDS = listOf("saved", "added", "deleted", "removed", "updated", "recorded", "created", "restored", "transferred", "failed", "could not", "couldn't", "error")
    private val NOISE = listOf("copied", "loading", "syncing", "signed in", "welcome", "refreshing")

    fun isActionMessage(msg: String): Boolean {
      val m = msg.lowercase()
      return NOISE.none { it in m } && ACTION_WORDS.any { it in m }
    }

    fun friendlyFailure(msg: String): String {
      val m = msg.lowercase()
      return when {
        "permission" in m || "denied" in m -> "That change wasn't allowed on your account."
        "network" in m || "offline" in m || "unavailable" in m -> "No connection. Your change is saved on this device and will upload automatically."
        else -> msg.take(140)
      }
    }
  }

  fun notifyStatus(message: String, target: SarimPush.NotificationTarget? = null) {
    if (!isActionMessage(message)) return
    val failed = listOf("failed", "could not", "couldn't", "error").any { it in message.lowercase() }
    enqueue(Item(if (failed) friendlyFailure(message) else message.take(140), failed, target))
  }

  fun notifyFailure(title: String, body: String, target: SarimPush.NotificationTarget? = null) =
    enqueue(Item("$title: ${friendlyFailure(body)}", true, target))

  private fun enqueue(item: Item) {
    handler.post {
      val now = System.currentTimeMillis()
      if (now - startedAt < STARTUP_QUIET_MS) return@post
      recent.entries.removeAll { now - it.value > DEDUPE_MS }
      if (recent.containsKey(item.text)) return@post
      recent[item.text] = now
      if (queue.isEmpty()) firstQueuedAt = now
      queue[item.text] = item
      flushRunnable?.let { handler.removeCallbacks(it) }
      val wait = minOf(QUIET_MS, (firstQueuedAt + MAX_WAIT_MS - now).coerceAtLeast(0L))
      flushRunnable = Runnable { flush() }.also { handler.postDelayed(it, wait) }
    }
  }

  private fun flush() {
    flushRunnable = null
    val items = queue.values.toList()
    queue.clear()
    if (items.isEmpty()) return
    val now = System.currentTimeMillis()
    while (posted.isNotEmpty() && now - posted.first() > BURST_WINDOW_MS) posted.removeFirst()
    if (posted.size >= BURST_LIMIT) return
    posted.addLast(now)
    if (!SarimPush.canPostNotifications(app)) return
    ensureChannel()
    val anyFailed = items.any { it.failed }
    val title = when {
      items.size > 1 -> "${items.size} updates"
      anyFailed -> "Action failed"
      else -> "Done"
    }
    val body = items.joinToString("\n") { it.text }
    val target = items.singleOrNull()?.target
    val pi = PendingIntent.getActivity(
      app, body.hashCode(), SarimPush.activityTapIntent(app, target),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val n = NotificationCompat.Builder(app, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_monochrome)
      .setContentTitle(title)
      .setContentText(items.first().text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setAutoCancel(true)
      .setContentIntent(pi)
      .build()
    runCatching { NotificationManagerCompat.from(app).notify(GROUP_TAG, (now and 0x7FFFFFFF).toInt(), n) }
  }

  private fun ensureChannel() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
    val mgr = app.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
    mgr.createNotificationChannel(
      android.app.NotificationChannel(CHANNEL_ID, "My Actions", android.app.NotificationManager.IMPORTANCE_LOW).apply {
        description = "Confirmations for things you saved, deleted or that failed"
      }
    )
  }
}
