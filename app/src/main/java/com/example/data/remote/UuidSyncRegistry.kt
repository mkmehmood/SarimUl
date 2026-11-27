package com.example.data.remote

import com.example.data.local.KvStoreEntity
import com.example.data.local.SafeSqliteCacheReader
import com.example.data.local.SarimDao
import com.example.data.model.SarimUuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DbQuotaStats(
  val reads: Int = 0,
  val writes: Int = 0,
  val lastReset: Long = 0L,
) {
  companion object {
    const val READ_WARN = 40_000
    const val READ_CRIT = 48_000
    const val READ_LIMIT = 50_000
    const val WRITE_WARN = 16_000
    const val WRITE_CRIT = 19_000
    const val WRITE_LIMIT = 20_000
    const val RESET_INTERVAL_MS = 86_400_000L
  }
}

data class CollectionDeltaStats(
  val syncCount: Int = 0,
  val lastSync: Long = 0L,
  val totalReads: Int = 0,
  val totalWrites: Int = 0,
)

data class UuidColStat(
  val uploaded: Int = 0,
  val downloaded: Int = 0,
  val dirty: Int = 0,
)

data class UuidRegistryStatsSnapshot(
  val myDeviceShard: String = "0000",
  val perCollection: Map<String, UuidColStat> = emptyMap(),
  val totalUploaded: Int = 0,
  val totalDownloaded: Int = 0,
)

interface KvStorePersistence {
  suspend fun get(key: String): String?
  suspend fun put(
    key: String,
    value: String,
    collection: String = "sync",
    rowType: String = "meta",
    ts: Long = System.currentTimeMillis(),
  )
  suspend fun delete(key: String)
}

class RoomKvStorePersistence(
  private val dao: SarimDao,
  private val safeReader: SafeSqliteCacheReader,
) : KvStorePersistence {
  override suspend fun get(key: String): String? = safeReader.getKvByUserKeyOnce(key)?.value

  override suspend fun put(
    key: String,
    value: String,
    collection: String,
    rowType: String,
    ts: Long,
  ) {
    dao.upsertKv(
      KvStoreEntity(
        fullKey = key,
        userKey = key,
        collection = collection,
        rowType = rowType,
        value = value,
        ts = ts,
        createdAt = ts,
      )
    )
  }

  override suspend fun delete(key: String) {
    dao.deleteKv(key)
  }
}

class InMemoryKvStorePersistence : KvStorePersistence {
  private val map = linkedMapOf<String, String>()
  override suspend fun get(key: String): String? = synchronized(map) { map[key] }
  override suspend fun put(key: String, value: String, collection: String, rowType: String, ts: Long) {
    synchronized(map) { map[key] = value }
  }
  override suspend fun delete(key: String) {
    synchronized(map) { map.remove(key) }
  }
}

/**
 * Pure-Kotlin port of `DeltaSync`, `UUIDSyncRegistry`, and `dbstats` from sarim_ref
 * (`modules/utilities-sales.js` & `modules/sync.js`), persisted via Room `kv_store`.
 */
class UuidSyncRegistry(
  private val kv: KvStorePersistence,
) {
  private val mutex = Mutex()

  private val uploaded = linkedMapOf<String, LinkedHashSet<String>>()
  private val downloaded = linkedMapOf<String, LinkedHashSet<String>>()
  private val dirty = linkedMapOf<String, LinkedHashSet<String>>()
  private val lastSyncTs = linkedMapOf<String, Long>()
  private val lastLocalModTs = linkedMapOf<String, Long>()
  private val deltaStats = linkedMapOf<String, CollectionDeltaStats>()

  private var myDeviceShard: String = "0000"
  private var newDeviceRestore: Boolean = false
  private var loaded: Boolean = false
  private var cachedDbStats: DbQuotaStats = DbQuotaStats()

  suspend fun ensureLoaded() {
    if (!loaded) loadAll()
  }

  suspend fun loadAll() = mutex.withLock {
    ALL_TRACKED_COLS.forEach { col ->
      uploaded[col] = decodeStringSet(kv.get("uploadedIds_$col"), MAX_UPLOADED_PER_COL)
      downloaded[col] = decodeStringSet(kv.get("downloadedIds_$col"), MAX_DOWNLOADED_PER_COL)
      dirty[col] = decodeStringSet(kv.get("pendingSync_$col"), MAX_DIRTY_PER_COL)
      kv.get("lastSync_$col")?.toLongOrNull()?.let { lastSyncTs[col] = it }
      kv.get("lastLocalMod_$col")?.toLongOrNull()?.let { lastLocalModTs[col] = it }
    }
    decodeDeltaStats(kv.get("deltastats"), deltaStats)
    cachedDbStats = decodeDbStats(kv.get("dbstats"))
    loaded = true
  }

  suspend fun clearAll() = mutex.withLock {
    uploaded.clear()
    downloaded.clear()
    dirty.clear()
    lastSyncTs.clear()
    lastLocalModTs.clear()
    deltaStats.clear()
    newDeviceRestore = false
    ALL_TRACKED_COLS.forEach { col ->
      kv.delete("uploadedIds_$col")
      kv.delete("downloadedIds_$col")
      kv.delete("pendingSync_$col")
      kv.delete("lastSync_$col")
      kv.delete("lastLocalMod_$col")
    }
    kv.delete("deltastats")
    loaded = true
  }

  suspend fun loadOrInitDeviceId(loginTs: Long? = null, nowMs: Long = System.currentTimeMillis()): String {
    val existing = kv.get("device_id")
    val resolved = if (SarimUuid.isValidDeviceId(existing, loginTs)) {
      existing!!
    } else {
      val created = SarimUuid.newDeviceId(nowMs)
      kv.put("device_id", created, collection = "device", rowType = "device", ts = nowMs)
      created
    }
    val shard = SarimUuid.initDeviceShard(resolved)
    setDeviceShard(shard)
    return resolved
  }

  suspend fun clearDeviceIdStorage() {
    kv.delete("device_id")
    SarimUuid.clearDeviceState()
    setDeviceShard("0000")
  }

  fun setDeviceShard(shard: String) {
    myDeviceShard = shard.trim().lowercase().take(4).padStart(4, '0')
  }

  fun getDeviceShard(): String = myDeviceShard

  fun setNewDeviceRestore(enabled: Boolean) {
    newDeviceRestore = enabled
  }

  fun isNewDeviceRestore(): Boolean = newDeviceRestore

  fun shardOf(docId: String): String? = SarimUuid.extractUUIDMeta(docId)?.deviceShard

  fun isLocalOrigin(docId: String): Boolean {
    if (myDeviceShard == "0000" || myDeviceShard.isBlank()) return false
    val shard = shardOf(docId) ?: return false
    return shard == myDeviceShard
  }

  fun isDirtyId(col: String, docId: String): Boolean =
    dirty[col]?.contains(docId) == true

  fun wasUploaded(col: String, docId: String): Boolean =
    uploaded[col]?.contains(docId) == true

  fun wasDownloaded(col: String, docId: String): Boolean =
    downloaded[col]?.contains(docId) == true

  /**
   * Matches `UUIDSyncRegistry.skipUpload(col, docId)` in `utilities-sales.js`.
   */
  fun skipUpload(col: String, docId: String): Boolean {
    if (isDirtyId(col, docId)) return false
    if (wasUploaded(col, docId)) return true
    val localOrigin = isLocalOrigin(docId)
    if (!localOrigin && shardOf(docId) != null) return false
    return false
  }

  /**
   * Matches `UUIDSyncRegistry.skipDownload(col, docId)` in `utilities-sales.js`.
   */
  fun skipDownload(col: String, docId: String, forceFullPull: Boolean = false): Boolean {
    if (forceFullPull || newDeviceRestore) return false
    if (wasDownloaded(col, docId)) return true
    if (isLocalOrigin(docId)) return !isDirtyId(col, docId)
    return false
  }

  fun shouldApplyCloud(col: String, docId: String, forceFullPull: Boolean = false): Boolean =
    !skipDownload(col, docId, forceFullPull)

  /**
   * Record-aware cloud apply check matching web `sync.js` / `UUIDSyncRegistry.shouldApplyCloud`:
   * when a local record already exists, allow cloud candidates to reach timestamp/UUID conflict resolution
   * even on incremental/realtime snapshots (`forceFullPull = false`).
   */
  fun shouldApplyCloudForRecord(
    col: String,
    docId: String,
    hasLocal: Boolean,
    forceFullPull: Boolean = false,
  ): Boolean = if (hasLocal) true else !skipDownload(col, docId, forceFullPull)

  suspend fun markLocalModified(col: String, docId: String?, nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
    lastLocalModTs[col] = nowMs
    kv.put("lastLocalMod_$col", nowMs.toString(), collection = col, rowType = "meta", ts = nowMs)
    if (!docId.isNullOrBlank()) {
      val set = dirty.getOrPut(col) { linkedSetOf() }
      set.add(docId)
      trimSetToLimit(set, MAX_DIRTY_PER_COL)
      kv.put("pendingSync_$col", encodeStringSet(set), collection = col, rowType = "meta", ts = nowMs)
    }
  }

  suspend fun clearDirty(col: String, docId: String) = mutex.withLock {
    val set = dirty[col] ?: return@withLock
    if (set.remove(docId)) {
      kv.put("pendingSync_$col", encodeStringSet(set), collection = col, rowType = "meta")
    }
  }

  suspend fun markUploaded(col: String, docId: String, nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
    val set = uploaded.getOrPut(col) { linkedSetOf() }
    set.add(docId)
    trimSetToLimit(set, MAX_UPLOADED_PER_COL)
    kv.put("uploadedIds_$col", encodeStringSet(set), collection = col, rowType = "meta", ts = nowMs)
    if (dirty[col]?.remove(docId) == true) {
      kv.put("pendingSync_$col", encodeStringSet(dirty[col].orEmpty()), collection = col, rowType = "meta", ts = nowMs)
    }
  }

  suspend fun markUploadedBatch(col: String, docIds: Collection<String>, nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
    if (docIds.isEmpty()) return@withLock
    val upSet = uploaded.getOrPut(col) { linkedSetOf() }
    val dirtySet = dirty[col]
    var dirtyChanged = false
    docIds.forEach { docId ->
      upSet.add(docId)
      if (dirtySet?.remove(docId) == true) dirtyChanged = true
    }
    trimSetToLimit(upSet, MAX_UPLOADED_PER_COL)
    kv.put("uploadedIds_$col", encodeStringSet(upSet), collection = col, rowType = "meta", ts = nowMs)
    if (dirtyChanged && dirtySet != null) {
      kv.put("pendingSync_$col", encodeStringSet(dirtySet), collection = col, rowType = "meta", ts = nowMs)
    }
  }

  suspend fun markDownloaded(col: String, docId: String, nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
    val set = downloaded.getOrPut(col) { linkedSetOf() }
    set.add(docId)
    trimSetToLimit(set, MAX_DOWNLOADED_PER_COL)
    kv.put("downloadedIds_$col", encodeStringSet(set), collection = col, rowType = "meta", ts = nowMs)
  }

  suspend fun markDownloadedBatch(col: String, docIds: Collection<String>, nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
    if (docIds.isEmpty()) return@withLock
    val set = downloaded.getOrPut(col) { linkedSetOf() }
    docIds.forEach { set.add(it) }
    trimSetToLimit(set, MAX_DOWNLOADED_PER_COL)
    kv.put("downloadedIds_$col", encodeStringSet(set), collection = col, rowType = "meta", ts = nowMs)
  }

  suspend fun getLastSyncTimestamp(col: String): Long = mutex.withLock {
    lastSyncTs[col] ?: kv.get("lastSync_$col")?.toLongOrNull() ?: 0L
  }

  suspend fun setLastSyncTimestamp(col: String, ts: Long = System.currentTimeMillis()) = mutex.withLock {
    lastSyncTs[col] = ts
    kv.put("lastSync_$col", ts.toString(), collection = col, rowType = "meta", ts = ts)
  }

  suspend fun updateSyncStats(col: String, reads: Int, writes: Int, nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
    val prev = deltaStats[col] ?: CollectionDeltaStats()
    deltaStats[col] = CollectionDeltaStats(
      syncCount = prev.syncCount + 1,
      lastSync = nowMs,
      totalReads = prev.totalReads + reads.coerceAtLeast(0),
      totalWrites = prev.totalWrites + writes.coerceAtLeast(0),
    )
    lastSyncTs[col] = nowMs
    kv.put("lastSync_$col", nowMs.toString(), collection = col, rowType = "meta", ts = nowMs)
    kv.put("deltastats", encodeDeltaStats(deltaStats), collection = "sync", rowType = "meta", ts = nowMs)
  }

  suspend fun trackFirestoreRead(count: Int = 1, nowMs: Long = System.currentTimeMillis()): DbQuotaStats = mutex.withLock {
    val cur = normalizeDbStats(cachedDbStats, nowMs)
    val next = cur.copy(reads = cur.reads + count.coerceAtLeast(0))
    cachedDbStats = next
    kv.put("dbstats", encodeDbStats(next), collection = "sync", rowType = "meta", ts = nowMs)
    next
  }

  suspend fun trackFirestoreWrite(count: Int = 1, nowMs: Long = System.currentTimeMillis()): DbQuotaStats = mutex.withLock {
    val cur = normalizeDbStats(cachedDbStats, nowMs)
    val next = cur.copy(writes = cur.writes + count.coerceAtLeast(0))
    cachedDbStats = next
    kv.put("dbstats", encodeDbStats(next), collection = "sync", rowType = "meta", ts = nowMs)
    next
  }

  suspend fun getDbStats(nowMs: Long = System.currentTimeMillis()): DbQuotaStats = mutex.withLock {
    val normalized = normalizeDbStats(cachedDbStats, nowMs)
    if (normalized != cachedDbStats) {
      cachedDbStats = normalized
      kv.put("dbstats", encodeDbStats(normalized), collection = "sync", rowType = "meta", ts = nowMs)
    }
    normalized
  }

  suspend fun getCollectionDeltaStats(col: String): CollectionDeltaStats = mutex.withLock {
    deltaStats[col] ?: CollectionDeltaStats(lastSync = lastSyncTs[col] ?: 0L)
  }

  suspend fun getRegistryStats(): UuidRegistryStatsSnapshot = mutex.withLock {
    val perCol = linkedMapOf<String, UuidColStat>()
    var totUp = 0
    var totDown = 0
    val cols = (ALL_TRACKED_COLS + uploaded.keys + downloaded.keys + dirty.keys).toSet()
    cols.forEach { c ->
      val u = uploaded[c]?.size ?: 0
      val d = downloaded[c]?.size ?: 0
      val p = dirty[c]?.size ?: 0
      totUp += u
      totDown += d
      perCol[c] = UuidColStat(uploaded = u, downloaded = d, dirty = p)
    }
    UuidRegistryStatsSnapshot(
      myDeviceShard = myDeviceShard,
      perCollection = perCol,
      totalUploaded = totUp,
      totalDownloaded = totDown,
    )
  }

  private fun normalizeDbStats(current: DbQuotaStats, nowMs: Long): DbQuotaStats {
    return if (nowMs - current.lastReset >= DbQuotaStats.RESET_INTERVAL_MS || current.lastReset <= 0L) {
      DbQuotaStats(reads = 0, writes = 0, lastReset = nowMs)
    } else {
      current
    }
  }

  private fun trimSetToLimit(set: LinkedHashSet<String>, max: Int) {
    while (set.size > max) {
      val first = set.firstOrNull() ?: break
      set.remove(first)
    }
  }

  private fun encodeStringSet(set: Set<String>): String =
    set.joinToString("\n") { it.replace("\n", "") }

  private fun decodeStringSet(raw: String?, max: Int): LinkedHashSet<String> {
    val out = linkedSetOf<String>()
    if (raw.isNullOrBlank()) return out
    val trimmed = raw.trim()
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
      trimmed.removePrefix("[").removeSuffix("]")
        .split(",")
        .map { it.trim().trim('"', '\'') }
        .filter { it.isNotBlank() }
        .forEach { out.add(it) }
    } else {
      trimmed.split('\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { out.add(it) }
    }
    trimSetToLimit(out, max)
    return out
  }

  private fun encodeDeltaStats(stats: Map<String, CollectionDeltaStats>): String =
    stats.entries.joinToString("\n") { (k, v) ->
      "$k|${v.syncCount}|${v.lastSync}|${v.totalReads}|${v.totalWrites}"
    }

  private fun decodeDeltaStats(raw: String?, target: MutableMap<String, CollectionDeltaStats>) {
    target.clear()
    if (raw.isNullOrBlank()) return
    raw.split('\n').forEach { line ->
      val parts = line.split('|')
      if (parts.size >= 5) {
        val key = parts[0].trim()
        if (key.isNotBlank()) {
          target[key] = CollectionDeltaStats(
            syncCount = parts[1].toIntOrNull() ?: 0,
            lastSync = parts[2].toLongOrNull() ?: 0L,
            totalReads = parts[3].toIntOrNull() ?: 0,
            totalWrites = parts[4].toIntOrNull() ?: 0,
          )
        }
      }
    }
  }

  private fun encodeDbStats(stats: DbQuotaStats): String =
    "${stats.reads}|${stats.writes}|${stats.lastReset}"

  private fun decodeDbStats(raw: String?): DbQuotaStats {
    if (raw.isNullOrBlank()) return DbQuotaStats()
    val parts = raw.trim().split('|')
    if (parts.size < 3) return DbQuotaStats()
    return DbQuotaStats(
      reads = parts[0].toIntOrNull() ?: 0,
      writes = parts[1].toIntOrNull() ?: 0,
      lastReset = parts[2].toLongOrNull() ?: 0L,
    )
  }

  companion object {
    const val MAX_UPLOADED_PER_COL = 5_000
    const val MAX_DOWNLOADED_PER_COL = 10_000
    const val MAX_DIRTY_PER_COL = 5_000

    val ALL_TRACKED_COLS = listOf(
      SarimWebSchema.COL_PRODUCTION,
      SarimWebSchema.COL_SALES,
      SarimWebSchema.COL_CALCULATOR,
      SarimWebSchema.COL_REP,
      SarimWebSchema.COL_CLIENTS,
      SarimWebSchema.COL_CUSTOMERS,
      SarimWebSchema.COL_TRANSACTIONS,
      SarimWebSchema.COL_ENTITIES,
      SarimWebSchema.COL_INVENTORY,
      SarimWebSchema.COL_FACTORY,
      SarimWebSchema.COL_RETURNS,
      SarimWebSchema.COL_EXPENSES,
      SarimWebSchema.COL_DELETIONS,
      SarimWebSchema.COL_PHOTOS,
      SarimWebSchema.COL_STORES,
      SarimWebSchema.COL_FORMULAS,
      SarimWebSchema.COL_DAILY_LEDGER,
      SarimWebSchema.COL_SETTINGS,
      SarimWebSchema.COL_PENDING,
    )
  }
}
