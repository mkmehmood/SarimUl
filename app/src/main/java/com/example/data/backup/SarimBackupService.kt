package com.example.data.backup

import com.example.data.local.SarimDao
import com.example.data.model.SarimUuid
import com.example.data.remote.FirebaseSyncManager
import com.example.data.remote.SarimWebPullMappers
import com.example.data.remote.SarimWebSchema
import com.example.data.repository.SarimRepository
import com.example.data.repository.SarimSyncAndBackupRepository
import com.example.util.SarimFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A decrypted backup waiting for the user to confirm how to restore it. */
class BackupPreview(
  val json: JSONObject,
  val isYearClose: Boolean,
  val createdAt: Long,
  val totalRecords: Int,
  val postCloseDeletions: Int,
)

class RestoreSummary(val added: Int, val updated: Int, val skipped: Int, val isYearClose: Boolean)

class CloseYearResult(
  val backupFile: ByteArray,
  val mergedByCollection: Map<String, Int>,
  val removedTotal: Int,
  val warnings: List<String>,
)

/**
 * Encrypted backup / restore and Close Financial Year, ported from the web app.
 * The record maths lives in the pure [YearCloseMerge] / [BackupRestoreLogic]; this class only moves
 * data between Room, the backup file and Firestore.
 */
class SarimBackupService(
  private val syncRepo: SarimSyncAndBackupRepository,
  private val repository: SarimRepository,
  private val dao: SarimDao,
  private val syncManager: FirebaseSyncManager,
) {
  // ------------------------------------------------------------------ small helpers

  private fun JSONArray?.records(): List<Rec> = SarimWebPullMappers.jsonObjectToMapList(this)

  private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
  }

  private fun clean(v: Any?): Any? = when (v) {
    is Double -> if (v.isNaN() || v.isInfinite()) 0.0 else v
    is Float -> if (v.isNaN() || v.isInfinite()) 0.0 else v.toDouble()
    is Map<*, *> -> v.entries.associate { it.key.toString() to clean(it.value) }
    is Collection<*> -> v.map { clean(it) }
    else -> v
  }

  private fun toJsonArray(list: List<Rec>): JSONArray {
    val arr = JSONArray()
    list.forEach { arr.put(JSONObject(clean(it) as Map<*, *>)) }
    return arr
  }

  private fun recordMaps(root: JSONObject): Map<String, List<Rec>> =
    BackupRestoreLogic.RECORD_KEYS.associateWith { root.optJSONArray(it).records() }

  private fun id(r: Rec): String = r["id"]?.toString().orEmpty()

  private suspend fun exportRoot(): JSONObject = JSONObject(syncRepo.exportBackupJson())

  private suspend fun tombstoneIds(): Set<String> =
    dao.getAllDeletionRefs().flatMap { listOf(it.id, it.recordId) }.filter { it.isNotBlank() }.toSet()

  private suspend fun clearTransactionalTables() {
    dao.clearSales()
    dao.clearProductionEntries()
    dao.clearPaymentTransactions()
    dao.clearExpenses()
    dao.clearReturns()
    dao.clearSettlements()
    dao.clearStockTransfers()
    dao.clearPaymentTransfers()
    dao.clearFactoryBatches()
  }

  private fun importJson(records: Map<String, List<Rec>>, keys: Collection<String>): String {
    val o = JSONObject()
    keys.forEach { k -> o.put(k, toJsonArray(records[k].orEmpty())) }
    return o.toString()
  }

  // ------------------------------------------------------------------ backup

  private suspend fun buildBackupRoot(email: String, uid: String, yearClose: Boolean): JSONObject {
    val root = exportRoot()
    val now = System.currentTimeMillis()
    root.put("settings", repository.readSettingsObject())
    root.put("deleted", JSONArray(tombstoneIds().toList()))
    val meta = JSONObject().put("encryptedFor", email).put("createdAt", now)
    if (yearClose) {
      val (count, lastAt, lastDate) = repository.readYearCloseMeta()
      meta.put("version", 2).put("source", "financial_year_close").put("isYearCloseBackup", true)
        .put(
          "fyCloseSnapshot",
          JSONObject().put("fyCloseCount", count)
            .put("lastYearClosedAt", lastAt ?: JSONObject.NULL)
            .put("lastYearClosedDate", lastDate ?: JSONObject.NULL)
            .put("capturedAt", now),
        )
    } else {
      meta.put("encryptedUid", uid).put("version", 4)
    }
    root.put("_meta", meta)
    return root
  }

  private suspend fun encrypt(root: JSONObject, email: String, password: String, uid: String): ByteArray {
    val bytes = root.toString().toByteArray(Charsets.UTF_8)
    return withContext(Dispatchers.Default) { SarimBackupCrypto.encrypt(bytes, email, password, uid) }
  }

  /** `.gznd` file for a normal backup (readable by the web app). */
  suspend fun createEncryptedBackup(email: String, password: String, uid: String): ByteArray =
    encrypt(buildBackupRoot(email, uid, yearClose = false), email, password, uid)

  // ------------------------------------------------------------------ restore

  /** Decrypt and inspect a backup. Throws [BackupCryptoException] for a wrong password/account/file. */
  suspend fun openBackup(file: ByteArray, email: String, password: String, uid: String): BackupPreview {
    val plain = withContext(Dispatchers.Default) { SarimBackupCrypto.decrypt(file, email, password, uid) }
    val root = try {
      JSONObject(String(plain, Charsets.UTF_8))
    } catch (_: Exception) {
      throw BackupCryptoException("INVALID_FORMAT")
    }
    val arrays = root.keys().asSequence().filter { root.optJSONArray(it) != null }.toSet()
    if (!BackupRestoreLogic.isSupportedBackup(arrays)) throw IllegalArgumentException("This backup file is not supported.")
    val meta = root.optJSONObject("_meta")
    val isYearClose = meta?.optBoolean("isYearCloseBackup", false) == true
    val total = BackupRestoreLogic.RECORD_KEYS.sumOf { root.optJSONArray(it)?.length() ?: 0 }
    val postClose = if (isYearClose) {
      BackupRestoreLogic.postCloseDeletions(recordMaps(root), tombstoneIds() + root.optJSONArray("deleted").strings())
    } else 0
    return BackupPreview(root, isYearClose, meta?.optLong("createdAt") ?: 0L, total, postClose)
  }

  /** Older Android builds wrote plain JSON; those can still be merge-restored (never year-close reversals). */
  suspend fun openPlainBackup(file: ByteArray): BackupPreview {
    val root = try {
      JSONObject(String(file, Charsets.UTF_8))
    } catch (_: Exception) {
      throw BackupCryptoException("INVALID_FORMAT")
    }
    val arrays = root.keys().asSequence().filter { root.optJSONArray(it) != null }.toSet()
    if (!BackupRestoreLogic.isSupportedBackup(arrays)) throw IllegalArgumentException("This backup file is not supported.")
    val total = BackupRestoreLogic.RECORD_KEYS.sumOf { root.optJSONArray(it)?.length() ?: 0 }
    return BackupPreview(root, isYearClose = false, createdAt = root.optLong("exportedAt"), totalRecords = total, postCloseDeletions = 0)
  }

  /** Merge-restore: records are merged, not overwritten; deleted records stay deleted; newer wins. */
  suspend fun applyMergeRestore(preview: BackupPreview): RestoreSummary {
    val root = preview.json
    val deleted = tombstoneIds() + root.optJSONArray("deleted").strings()
    val plan = BackupRestoreLogic.planMergeRestore(recordMaps(exportRoot()), recordMaps(root), deleted)

    val filtered = JSONObject()
    plan.toApply.forEach { (k, v) -> filtered.put(k, toJsonArray(v)) }

    // Reference data: only add what this device does not have yet (web adds missing store keys).
    val localStoreKeys = dao.getAllStores().map { it.storeKey }.toSet()
    root.optJSONArray(SarimWebSchema.COL_STORES)?.let { arr ->
      val out = JSONArray()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val key = o.optString("key").ifBlank { o.optString("storeKey") }
        if (key.isNotBlank() && key !in localStoreKeys) out.put(o)
      }
      filtered.put(SarimWebSchema.COL_STORES, out)
    }
    val localFormulaIds = dao.getAllFormulas().map { it.id }.toSet()
    root.optJSONArray(SarimWebSchema.COL_FORMULAS)?.let { arr ->
      val out = JSONArray()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val fid = o.optString("id")
        if (fid.isNotBlank() && fid !in localFormulaIds && fid !in deleted) out.put(o)
      }
      filtered.put(SarimWebSchema.COL_FORMULAS, out)
    }
    val localLedgerIds = dao.getAllDailyLedger().map { it.id }.toSet()
    (root.optJSONArray(SarimWebSchema.COL_DAILY_LEDGER) ?: root.optJSONArray("ledger"))?.let { arr ->
      val out = JSONArray()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val lid = o.optString("id")
        if (lid.isNotBlank() && lid !in localLedgerIds && lid !in deleted) out.put(o)
      }
      filtered.put(SarimWebSchema.COL_DAILY_LEDGER, out)
    }

    syncRepo.restoreBackupJson(filtered.toString(), markPending = true).getOrThrow()
    return RestoreSummary(plan.added, plan.updated, plan.skipped, isYearClose = false)
  }

  /** Reverse a financial-year close using the pre-close backup (web `_doYearCloseRestore`). */
  suspend fun applyYearCloseRestore(preview: BackupPreview, honourDeletions: Boolean, online: Boolean): RestoreSummary {
    val root = preview.json
    val deleted = tombstoneIds() + root.optJSONArray("deleted").strings()
    val current = recordMaps(exportRoot())
    val backup = recordMaps(root)
    val plan = BackupRestoreLogic.planYearCloseRestore(
      current = current,
      backup = backup,
      deleted = deleted,
      honourPostCloseDeletions = honourDeletions,
      backupCreatedAt = preview.createdAt,
      repNames = emptySet(),
    )

    val transactional = YearCloseMerge.COLLECTIONS
    val reference = BackupRestoreLogic.RECORD_KEYS.filter { it !in transactional }
    clearTransactionalTables()
    syncRepo.restoreBackupJson(importJson(plan.replaceData, transactional), markPending = !online).getOrThrow()
    // clients / customers / inventory / entities are plain upserts and always go through normal sync
    syncRepo.restoreBackupJson(importJson(plan.replaceData, reference), markPending = true).getOrThrow()

    if (online) {
      var failed = false
      for (col in transactional) {
        val keep = plan.replaceData[col].orEmpty()
        val keepIds = keep.map { id(it) }.toSet()
        val remove = current[col].orEmpty().map { id(it) }.filter { it.isNotEmpty() && it !in keepIds }
        if (remove.isEmpty() && keep.isEmpty()) continue
        if (syncManager.commitCollectionChanges(col, remove, keep).isFailure) failed = true
      }
      if (failed) {
        // Fall back to the regular outbox so the restored records still reach the cloud.
        syncRepo.restoreBackupJson(importJson(plan.replaceData, transactional), markPending = true)
      }
    }

    val snap = root.optJSONObject("_meta")?.optJSONObject("fyCloseSnapshot")
    val (currentCount, _, _) = repository.readYearCloseMeta()
    val count = if (snap != null && snap.has("fyCloseCount")) snap.optInt("fyCloseCount") else maxOf(0, currentCount - 1)
    val lastAt = if (snap != null && snap.has("lastYearClosedAt") && !snap.isNull("lastYearClosedAt")) snap.optLong("lastYearClosedAt") else null
    val lastDate = snap?.optString("lastYearClosedDate")?.takeIf { it.isNotBlank() && it != "null" }
    repository.restoreYearCloseMeta(count, lastAt, lastDate)

    val restored = plan.replaceData.values.sumOf { it.size }
    return RestoreSummary(restored, 0, 0, isYearClose = true)
  }

  // ------------------------------------------------------------------ close financial year

  /**
   * Close the financial year like the web app: write an encrypted pre-close backup, fold every old
   * record into one opening-balance record per group, check the totals, update the cloud, then carry
   * customer / entity balances forward. Everything is computed before anything is changed, and a
   * failure after that point restores the pre-close data.
   */
  suspend fun closeFinancialYear(
    email: String,
    password: String,
    uid: String,
    online: Boolean,
    onStage: (String, Float) -> Unit,
    onBackupReady: suspend (ByteArray) -> Unit = {},
  ): CloseYearResult {
    onStage("Creating encrypted year-end backup…", 0.05f)
    val snapshotRoot = buildBackupRoot(email, uid, yearClose = true)
    val backupFile = encrypt(snapshotRoot, email, password, uid)
    // Hand the file over before anything is changed, so it exists even if the app is killed mid-close.
    onBackupReady(backupFile)
    val snapshotJson = snapshotRoot.toString()

    onStage("Calculating opening balances…", 0.25f)
    val maps = recordMaps(snapshotRoot)
    val stores = dao.getAllStores().filter { !it.isDeleted }
    val customers = dao.getAllCustomers().filter { !it.isDeleted && !it.isRepCustomer }
    val priceByStore = stores.associate { it.storeKey to it.salePricePerKg }
    val now = System.currentTimeMillis()
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val ctx = MergeContext(
      closeEpoch = now,
      nowEpoch = now,
      nowIsoDate = SarimFormat.dateInput(now),
      nowTime = SimpleDateFormat("hh:mm:ss a", Locale.US).format(Date(now)),
      nowIsoStamp = iso.format(Date(now)),
      newId = { prefix -> SarimUuid.generateUUID(prefix) },
      salePriceForStore = { priceByStore[it] ?: 0.0 },
      salePriceForCustomer = { name, store ->
        customers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.customSalePrice?.takeIf { it > 0.0 }
          ?: (priceByStore[store] ?: 0.0)
      },
      defaultStoreKey = stores.firstOrNull()?.storeKey.orEmpty(),
    )
    val outcome = YearCloseMerge.mergeAll(maps, ctx)
    val check = YearCloseMerge.verifyConsistency(outcome.collections)
    if (!check.valid) throw IllegalStateException("Data consistency check failed: ${check.errors.joinToString("; ")}. Nothing was changed.")

    onStage("Merging records…", 0.5f)
    val newJson = importJson(outcome.collections, YearCloseMerge.COLLECTIONS)
    try {
      clearTransactionalTables()
      syncRepo.restoreBackupJson(newJson, markPending = !online).getOrThrow()
    } catch (e: Exception) {
      runCatching { clearTransactionalTables(); syncRepo.restoreBackupJson(snapshotJson, markPending = true) }
      throw IllegalStateException("Close year failed and your data was restored: ${e.message}", e)
    }

    if (online) {
      onStage("Updating the cloud…", 0.7f)
      val committed = mutableListOf<String>()
      var failure: Throwable? = null
      for (col in YearCloseMerge.COLLECTIONS) {
        val removed = outcome.removedIds[col].orEmpty()
        val writes = outcome.newMerged[col].orEmpty()
        if (removed.isEmpty() && writes.isEmpty()) continue
        val res = syncManager.commitCollectionChanges(col, removed, writes)
        if (res.isFailure) { failure = res.exceptionOrNull(); break }
        committed += col
      }
      if (failure != null) {
        // Undo: put the original documents back in the cloud and the original data on the device.
        for (col in committed) {
          syncManager.commitCollectionChanges(col, outcome.newMerged[col].orEmpty().map { id(it) }, maps[col].orEmpty())
        }
        runCatching { clearTransactionalTables(); syncRepo.restoreBackupJson(snapshotJson, markPending = true) }
        throw IllegalStateException("Could not update the cloud (${failure.message ?: "network error"}). Your data was left unchanged.", failure)
      }
    }

    onStage("Finishing up…", 0.9f)
    dao.clearAllDeletions()
    if (online) runCatching { syncManager.purgeDeletionTombstones() }
    repository.rollBalancesForwardAndMarkYearClosed()

    return CloseYearResult(
      backupFile = backupFile,
      mergedByCollection = outcome.newMerged.mapValues { it.value.size },
      removedTotal = outcome.removedIds.values.sumOf { it.size },
      warnings = check.warnings,
    )
  }
}
