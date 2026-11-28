package com.example.data.repository

import com.example.data.local.KvStoreEntity
import com.example.data.local.NdappOutboxEntity
import com.example.data.local.SafeSqliteCacheReader
import com.example.data.local.SarimDao
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** One upload that exhausted its retries (web `OfflineQueue.deadLetterQueue` entry). */
data class FailedOp(
  val id: String,
  val uid: String,
  val action: String,
  val collection: String,
  val docId: String,
  val payload: String?,
  val createdAt: Long,
  val attempts: Int,
  val failedAt: Long,
  val finalError: String,
) {
  /** "set · customers · 1a2b3c4d…" like the web list label. */
  val label: String
    get() = listOf(action, collection, if (docId.isNotBlank()) docId.take(8) + "…" else "")
      .filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * Port of the web failed-uploads queue (`OfflineQueue` dead letters in `modules/utilities-core.js`):
 * an upload that failed [MAX_RETRIES] times is parked here instead of retrying forever, with exponential
 * backoff between attempts, Retry / Dismiss / Dismiss all / Export JSON, and an automatic re-queue every
 * [AUTO_RETRY_MS]. Stored in `kv_store` like the web's `sqliteStore`, so no schema change is needed.
 */
class FailedOpsQueue(
  private val dao: SarimDao,
  private val safeReader: SafeSqliteCacheReader,
) {
  companion object {
    const val MAX_RETRIES = 10
    const val RETRY_DELAY_MS = 2_000L
    const val MAX_BACKOFF_MS = 30_000L
    const val AUTO_RETRY_MS = 30L * 60L * 1000L
    const val KEY = "offline_dead_letter_queue"

    /** Wait before attempt number [attempts]+1: 2s, 4s, 8s … capped at 30s (web `processQueue`). */
    fun backoffMs(attempts: Int): Long {
      if (attempts <= 0) return 0L
      val shift = (attempts - 1).coerceAtMost(20)
      return minOf(RETRY_DELAY_MS * (1L shl shift), MAX_BACKOFF_MS)
    }

    /**
     * A connectivity failure is not the upload's fault: the web only retries while `navigator.onLine`,
     * so these never count toward the retry limit.
     */
    fun isTransientNetworkError(t: Throwable?): Boolean {
      var cur = t
      var depth = 0
      while (cur != null && depth < 8) {
        if (cur is IOException) return true
        val msg = cur.message.orEmpty().lowercase()
        if ("unavailable" in msg || "offline" in msg || "unable to resolve host" in msg ||
          "network" in msg || "timeout" in msg || "timed out" in msg
        ) return true
        cur = cur.cause
        depth++
      }
      return false
    }
  }

  private suspend fun read(): List<FailedOp> {
    val raw = safeReader.getKvOnce(KEY)?.value?.takeIf { it.isNotBlank() } ?: return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        FailedOp(
          id = o.optString("id"),
          uid = o.optString("uid"),
          action = o.optString("action"),
          collection = o.optString("collection"),
          docId = o.optString("docId"),
          payload = if (o.isNull("payload")) null else o.optString("payload"),
          createdAt = o.optLong("createdAt"),
          attempts = o.optInt("attempts"),
          failedAt = o.optLong("failedAt"),
          finalError = o.optString("finalError"),
        )
      }
    }.getOrDefault(emptyList())
  }

  private fun toJson(items: List<FailedOp>): JSONArray = JSONArray().also { arr ->
    items.forEach { f ->
      arr.put(
        JSONObject()
          .put("id", f.id).put("uid", f.uid).put("action", f.action)
          .put("collection", f.collection).put("docId", f.docId)
          .put("payload", f.payload ?: JSONObject.NULL)
          .put("createdAt", f.createdAt).put("attempts", f.attempts)
          .put("failedAt", f.failedAt).put("finalError", f.finalError)
      )
    }
  }

  private suspend fun write(items: List<FailedOp>) {
    if (items.isEmpty()) {
      dao.deleteKv(KEY)
      return
    }
    val now = System.currentTimeMillis()
    dao.upsertKv(
      KvStoreEntity(
        fullKey = KEY, userKey = KEY, rowType = "meta",
        value = toJson(items).toString(), ts = now, createdAt = now,
      )
    )
  }

  suspend fun list(): List<FailedOp> = read()

  suspend fun count(): Int = read().size

  /** Park an exhausted upload and take it out of the live outbox. */
  suspend fun add(op: NdappOutboxEntity, error: String, now: Long = System.currentTimeMillis()) {
    val entry = FailedOp(
      id = op.id, uid = op.uid, action = op.action, collection = op.collection, docId = op.docId,
      payload = op.payload, createdAt = op.createdAt, attempts = op.attempts + 1, failedAt = now,
      finalError = error.ifBlank { "Exhausted $MAX_RETRIES retries" },
    )
    write(read().filterNot { it.id == op.id } + entry)
    dao.removeOutbox(op.id)
  }

  private suspend fun requeue(f: FailedOp) {
    dao.insertOutbox(
      NdappOutboxEntity(
        id = f.id, uid = f.uid, action = f.action, collection = f.collection, docId = f.docId,
        payload = f.payload, createdAt = f.createdAt, attempts = 0, lastAttempt = 0L,
      )
    )
  }

  /** Retry: back into the outbox with a fresh retry budget. Returns false if the entry is gone. */
  suspend fun retry(id: String): Boolean {
    val all = read()
    val f = all.firstOrNull { it.id == id } ?: return false
    requeue(f)
    write(all.filterNot { it.id == id })
    return true
  }

  suspend fun dismiss(id: String) = write(read().filterNot { it.id == id })

  suspend fun dismissAll() = write(emptyList())

  /** Web `_scheduleDlqAutoRetry`: anything parked for 30 minutes goes back into the outbox. */
  suspend fun requeueDue(now: Long = System.currentTimeMillis()): Int {
    val all = read()
    val due = all.filter { now - it.failedAt >= AUTO_RETRY_MS }
    if (due.isEmpty()) return 0
    due.forEach { requeue(it) }
    write(all - due.toSet())
    return due.size
  }

  /** Pretty JSON of every failed operation, for "Export JSON". */
  suspend fun exportJson(): String = toJson(read()).toString(2)
}
