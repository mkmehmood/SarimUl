package com.example.data.repository

import com.example.data.local.*
import com.example.data.model.AccountSessionInfo
import com.example.data.model.ConflictRecordSnapshot
import com.example.data.model.RolesHelper
import com.example.data.model.SarimUuid
import com.example.data.remote.*
import com.example.util.SarimFormat
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class RecycleBinRecord(
  val id: String,
  val tabCategory: String,
  val title: String,
  val subtitle: String,
  val amountText: String,
  val deletedAt: Long,
  val entityType: String,
)

data class CollectionStats(
  val name: String,
  val activeCount: Int,
  val deletedCount: Int,
  val pendingSyncCount: Int,
  val fsCount: Int? = null,
  val uploadedCount: Int = 0,
  val downloadedCount: Int = 0,
  val dirtyUuidCount: Int = 0,
  val syncCount: Int = 0,
  val lastSyncMs: Long = 0L,
  val hasLiveListener: Boolean = false,
  val lockOnClose: Boolean = false,
  val sqliteKey: String = name,
  val jsVar: String = name,
  val description: String = "",
)

data class ConfigDocDiagnostic(
  val path: String,
  val description: String,
  val existsInCloud: Boolean? = null,
  val mappings: List<Pair<String, String>> = emptyList(),
  val expectedFields: List<String> = emptyList(),
  val presentFields: Set<String> = emptySet(),
  val fieldPreviews: Map<String, String> = emptyMap(),
  val listenerName: String = "",
)

data class ListenerDiagnostic(
  val name: String,
  val kind: String, // "DOC" | "COLLECTION"
  val handler: String,
  val purpose: String,
  val triggerRule: String,
  val isLive: Boolean = true,
)

data class DatabaseStructureSnapshot(
  val collections: List<CollectionStats> = emptyList(),
  val configDocs: List<ConfigDocDiagnostic> = emptyList(),
  val listeners: List<ListenerDiagnostic> = emptyList(),
  val myDeviceShard: String = "0000",
  val deviceId: String = "",
  val dbStats: DbQuotaStats = DbQuotaStats(),
  val totalUploaded: Int = 0,
  val totalDownloaded: Int = 0,
  val fyCloseCount: Int = 0,
  val lastYearClosedDate: String? = null,
  val lastCloudCheckAt: Long? = null,
  val isCloudLive: Boolean = false,
)

class SarimSyncAndBackupRepository(
  private val dao: SarimDao,
  private val syncManager: FirebaseSyncManager,
  private val prefs: PreferencesManager,
  private val safeReader: SafeSqliteCacheReader = SafeSqliteCacheReader(dao),
  val uuidRegistry: UuidSyncRegistry = UuidSyncRegistry(RoomKvStorePersistence(dao, safeReader)),
) {
  private val recycleRestoreEngine = SarimRecycleRestoreEngine(dao, uuidRegistry)

  suspend fun countPendingSync(): Int {
    return dao.countOutbox() +
      dao.getStoreCounts().pendingCount +
      dao.getRawMaterialCounts().pendingCount +
      dao.getPurchaseCounts().pendingCount +
      dao.getFormulaCounts().pendingCount +
      dao.getFactoryBatchCounts().pendingCount +
      dao.getProductionCounts().pendingCount +
      dao.getReturnCounts().pendingCount +
      dao.getCustomerCounts(isRep = false).pendingCount +
      dao.getCustomerCounts(isRep = true).pendingCount +
      dao.getSaleCounts(isRep = false).pendingCount +
      dao.getSaleCounts(isRep = true).pendingCount +
      dao.getPaymentEntityCounts().pendingCount +
      dao.getPaymentTransactionCounts().pendingCount +
      dao.getExpenseCounts().pendingCount +
      dao.getDailyLedgerCounts().pendingCount +
      dao.getSettlementCounts().pendingCount +
      dao.getStockTransferCounts().pendingCount +
      dao.getPaymentTransferCounts().pendingCount +
      dao.getMixCalcCounts().pendingCount +
      dao.getApprovalCounts().pendingCount +
      dao.getAccountCounts().pendingCount +
      dao.getDeletionCounts().pendingCount +
      dao.getPhotoCounts().pendingCount +
      dao.countDirtyKv()
  }

  suspend fun getDatabaseStructure(liveFs: LiveFirestoreDiagnostics? = null): List<CollectionStats> {
    uuidRegistry.ensureLoaded()
    val regStats = uuidRegistry.getRegistryStats()
    val stores = dao.getStoreCounts()
    val mats = dao.getRawMaterialCounts()
    val purchases = dao.getPurchaseCounts()
    val formulas = dao.getFormulaCounts()
    val batches = dao.getFactoryBatchCounts()
    val prod = dao.getProductionCounts()
    val returnsList = dao.getReturnCounts()
    val regularCusts = dao.getCustomerCounts(isRep = false)
    val repClients = dao.getCustomerCounts(isRep = true)
    val regularSales = dao.getSaleCounts(isRep = false)
    val repSales = dao.getSaleCounts(isRep = true)
    val entities = dao.getPaymentEntityCounts()
    val payTx = dao.getPaymentTransactionCounts()
    val exps = dao.getExpenseCounts()
    val ledger = dao.getDailyLedgerCounts()
    val calcs = dao.getSettlementCounts()
    val stockTx = dao.getStockTransferCounts()
    val payTrans = dao.getPaymentTransferCounts()
    val mixCalcs = dao.getMixCalcCounts()
    val pendingApprovals = dao.getApprovalCounts()
    val deletions = dao.getDeletionCounts()
    val photos = dao.getPhotoCounts()
    val kvCount = dao.countKv()
    val outboxCount = dao.countOutbox()
    val accounts = dao.getAccountCounts()

    suspend fun stat(
      name: String,
      active: Int,
      deleted: Int,
      pending: Int,
      liveListener: Boolean = true,
      lockOnClose: Boolean = false,
      sqliteKey: String = name,
      jsVar: String = name,
      desc: String = "",
    ): CollectionStats {
      val uuidStat = regStats.perCollection[name]
      val deltaStat = uuidRegistry.getCollectionDeltaStats(name)
      return CollectionStats(
        name = name,
        activeCount = active,
        deletedCount = deleted,
        pendingSyncCount = pending,
        fsCount = liveFs?.collectionCounts?.get(name),
        uploadedCount = uuidStat?.uploaded ?: 0,
        downloadedCount = uuidStat?.downloaded ?: 0,
        dirtyUuidCount = uuidStat?.dirty ?: 0,
        syncCount = deltaStat.syncCount,
        lastSyncMs = deltaStat.lastSync,
        hasLiveListener = liveListener,
        lockOnClose = lockOnClose,
        sqliteKey = sqliteKey,
        jsVar = jsVar,
        description = desc,
      )
    }

    return listOf(
      stat(SarimWebSchema.COL_PRODUCTION, prod.activeCount, prod.deletedCount, prod.pendingCount, true, true, "productionEntries", "production", "Manufacturing & return entries per store"),
      stat(SarimWebSchema.COL_SALES, regularSales.activeCount, regularSales.deletedCount, regularSales.pendingCount, true, true, "salesTransactions", "sales", "Wholesale sales, collections & payouts"),
      stat(SarimWebSchema.COL_REP, repSales.activeCount, repSales.deletedCount, repSales.pendingCount, true, true, "repSales", "repSales", "Representative field sales & collections"),
      stat(SarimWebSchema.COL_CUSTOMERS, regularCusts.activeCount, regularCusts.deletedCount, regularCusts.pendingCount, true, false, "salesCustomers", "customers", "Direct customer ledger & balances"),
      stat(SarimWebSchema.COL_CLIENTS, repClients.activeCount, repClients.deletedCount, repClients.pendingCount, true, false, "repCustomers", "repCustomers", "Rep client accounts & old debt"),
      stat(SarimWebSchema.COL_TRANSACTIONS, payTx.activeCount, payTx.deletedCount, payTx.pendingCount, true, true, "paymentTransactions", "transactions", "Payment IN/OUT & entity transfers"),
      stat(SarimWebSchema.COL_ENTITIES, entities.activeCount, entities.deletedCount, entities.pendingCount, true, false, "paymentEntities", "entities", "Suppliers, banks, & payees/payors"),
      stat(SarimWebSchema.COL_EXPENSES, exps.activeCount, exps.deletedCount, exps.pendingCount, true, true, "expenses", "expenses", "Operating & supplier expenses"),
      stat(SarimWebSchema.COL_INVENTORY, mats.activeCount + purchases.activeCount, mats.deletedCount + purchases.deletedCount, mats.pendingCount + purchases.pendingCount, true, false, "rawMaterials + lots", "inventory", "Raw materials & FIFO purchase lots"),
      stat(SarimWebSchema.COL_FACTORY, batches.activeCount, batches.deletedCount, batches.pendingCount, true, true, "factoryBatches", "factoryBatches", "Factory mixing batches produced"),
      stat(SarimWebSchema.COL_RETURNS, returnsList.activeCount, returnsList.deletedCount, returnsList.pendingCount, true, true, "returnLogs", "returns", "Rep settlement stock returns"),
      stat(SarimWebSchema.COL_CALCULATOR, calcs.activeCount, calcs.deletedCount, calcs.pendingCount, true, true, "settlements", "calculatorHistory", "Rep settlement reconciliations"),
      stat(SarimWebSchema.COL_DAILY_LEDGER, ledger.activeCount, ledger.deletedCount, ledger.pendingCount, true, true, "daily_ledger/entries", "dailyLedger", "Singleton daily cashbook entries"),
      stat(SarimWebSchema.COL_FORMULAS, formulas.activeCount + mixCalcs.activeCount, formulas.deletedCount, formulas.pendingCount + mixCalcs.pendingCount, true, false, "formulas/config", "formulas", "Singleton formulas & mix calcs"),
      stat(SarimWebSchema.COL_STORES, stores.activeCount, stores.deletedCount, stores.pendingCount, true, false, "stores/list", "stores", "Singleton store registry & pricing"),
      stat("stock_transfers", stockTx.activeCount, stockTx.deletedCount, stockTx.pendingCount, false, true, "stockTransfers", "stockTransfers", "Paired in/out store stock transfers"),
      stat("payment_transfers", payTrans.activeCount, payTrans.deletedCount, payTrans.pendingCount, false, true, "paymentTransfers", "paymentTransfers", "Paired entity balance transfers"),
      stat(SarimWebSchema.COL_PENDING, pendingApprovals.totalCount, 0, pendingApprovals.pendingCount, true, false, "approvals", "pendingQueue", "Staff edits awaiting admin review"),
      stat(SarimWebSchema.COL_DELETIONS, deletions.totalCount, 0, deletions.pendingCount, true, false, "deletions", "deletedIds", "90-day tombstone records"),
      stat(SarimWebSchema.COL_PHOTOS, photos.activeCount, photos.deletedCount, photos.pendingCount, false, false, "photos", "photos{}", "Snapshot-synced attachment receipts"),
      stat("kv_store", kvCount, 0, 0, false, false, "kv_store", "kvMeta", "Sync cursors, UUID registry & quota"),
      stat("ndapp_outbox", outboxCount, 0, outboxCount, false, false, "ndapp_outbox", "outbox", "Durable write queue for cloud sync"),
      stat("app_accounts", accounts.totalCount, 0, accounts.pendingCount, false, false, "appAccounts", "accounts", "Workspace member role assignments"),
    )
  }

  suspend fun getDatabaseStructureSnapshot(liveFs: LiveFirestoreDiagnostics? = null): DatabaseStructureSnapshot {
    uuidRegistry.ensureLoaded()
    val cols = getDatabaseStructure(liveFs)
    val regStats = uuidRegistry.getRegistryStats()
    val quota = uuidRegistry.getDbStats()
    val devId = safeReader.getKvByUserKeyOnce("device_id")?.value ?: SarimUuid.currentDeviceId.orEmpty()
    val settingsObj = runCatching { JSONObject(safeReader.getKvByUserKeyOnce("settings")?.value ?: "{}") }.getOrNull()
    val fyCloseCount = settingsObj?.optInt("fyCloseCount", 0) ?: 0
    val lastFyDate = settingsObj?.optString("lastYearClosedDate", "")?.takeIf { it.isNotBlank() }

    val cfgSpecs = listOf(
      ConfigDocDiagnostic(
        path = "settings/config",
        description = "App settings, FY counter, repProfile, reps (init)",
        existsInCloud = liveFs?.configDocs?.get("settings/config")?.exists,
        mappings = listOf("settings" to "settings", "reps" to "reps", "settings_timestamp" to "settings_timestamp"),
        expectedFields = listOf("settings", "settings_timestamp", "reps", "reps_timestamp", "synced"),
        presentFields = liveFs?.configDocs?.get("settings/config")?.presentFields ?: emptySet(),
        fieldPreviews = liveFs?.configDocs?.get("settings/config")?.fieldPreviews ?: emptyMap(),
        listenerName = "onSettingsConfigDoc",
      ),
      ConfigDocDiagnostic(
        path = "settings/team",
        description = "Sales reps directory & role sync",
        existsInCloud = liveFs?.configDocs?.get("settings/team")?.exists,
        mappings = listOf("reps" to "reps", "reps_timestamp" to "updated"),
        expectedFields = listOf("reps", "updated"),
        presentFields = liveFs?.configDocs?.get("settings/team")?.presentFields ?: emptySet(),
        fieldPreviews = liveFs?.configDocs?.get("settings/team")?.fieldPreviews ?: emptyMap(),
        listenerName = "onTeamDoc",
      ),
      ConfigDocDiagnostic(
        path = "settings/yearCloseSignal",
        description = "Cross-device year-close / restore broadcast signal",
        existsInCloud = liveFs?.configDocs?.get("settings/yearCloseSignal")?.exists,
        mappings = listOf("lastHandledYearCloseSignal" to "triggeredAt"),
        expectedFields = listOf("type", "triggeredAt", "triggeredBy", "fyCloseCount"),
        presentFields = liveFs?.configDocs?.get("settings/yearCloseSignal")?.presentFields ?: emptySet(),
        fieldPreviews = liveFs?.configDocs?.get("settings/yearCloseSignal")?.fieldPreviews ?: emptyMap(),
        listenerName = "onYearCloseSignal",
      ),
      ConfigDocDiagnostic(
        path = "formulas/config",
        description = "Factory formulas, costs, adjustment & mix calcs",
        existsInCloud = liveFs?.configDocs?.get("formulas/config")?.exists,
        mappings = listOf("formulas" to "formulas", "savedMixCalcs" to "savedMixCalcs", "formulas_timestamp" to "formulas_timestamp"),
        expectedFields = listOf("formulas", "formulas_timestamp", "savedMixCalcs"),
        presentFields = liveFs?.configDocs?.get("formulas/config")?.presentFields ?: emptySet(),
        fieldPreviews = liveFs?.configDocs?.get("formulas/config")?.fieldPreviews ?: emptyMap(),
        listenerName = "onFormulasDoc",
      ),
      ConfigDocDiagnostic(
        path = "categories/list",
        description = "Operating expense category definitions",
        existsInCloud = liveFs?.configDocs?.get("categories/list")?.exists,
        mappings = listOf("categories" to "categories", "categories_timestamp" to "categories_timestamp"),
        expectedFields = listOf("categories", "categories_timestamp"),
        presentFields = liveFs?.configDocs?.get("categories/list")?.presentFields ?: emptySet(),
        fieldPreviews = liveFs?.configDocs?.get("categories/list")?.fieldPreviews ?: emptyMap(),
        listenerName = "onCategoryDoc",
      ),
      ConfigDocDiagnostic(
        path = "stores/list",
        description = "Store catalog, formulas & per-store sale prices",
        existsInCloud = liveFs?.configDocs?.get("stores/list")?.exists,
        mappings = listOf("stores" to "stores", "stores_timestamp" to "stores_timestamp"),
        expectedFields = listOf("stores", "stores_timestamp"),
        presentFields = liveFs?.configDocs?.get("stores/list")?.presentFields ?: emptySet(),
        fieldPreviews = liveFs?.configDocs?.get("stores/list")?.fieldPreviews ?: emptyMap(),
        listenerName = "onStoresDoc",
      ),
      ConfigDocDiagnostic(
        path = "account/info",
        description = "Account profile metadata & workspace presence",
        existsInCloud = liveFs?.configDocs?.get("account/info")?.exists,
        mappings = listOf("accountSession" to "uid / email / roles"),
        expectedFields = listOf("email", "displayName", "lastActivity"),
        presentFields = liveFs?.configDocs?.get("account/info")?.presentFields ?: emptySet(),
        fieldPreviews = liveFs?.configDocs?.get("account/info")?.fieldPreviews ?: emptyMap(),
        listenerName = "selfUserListener",
      ),
    )

    val listeners = mutableListOf(
      ListenerDiagnostic("users/{uid}", "DOC", "userRef.addSnapshotListener", "Force-logout, suspension, live role changes & lastWrite ping", "Any write to user root doc", true),
      ListenerDiagnostic("settings/config", "DOC", "onSettingsConfigDoc", "Sync settings, FY close count & initial reps list", "settings_timestamp guard", true),
      ListenerDiagnostic("settings/team", "DOC", "onTeamDoc", "Sync sales reps list across team", "updated timestamp change", true),
      ListenerDiagnostic("settings/yearCloseSignal", "DOC", "onYearCloseSignal", "Wipe cache + rebuild after close/restore signal", "triggeredAt > lastHandled && triggeredBy != thisDevice", true),
      ListenerDiagnostic("formulas/config", "DOC", "onFormulasDoc", "Live formula catalog & mix calcs", "formulas_timestamp guard", true),
      ListenerDiagnostic("stores/list", "DOC", "onStoresDoc", "Store list, formula assignments & prices", "stores_timestamp guard", true),
      ListenerDiagnostic("categories/list", "DOC", "onCategoryDoc", "Expense, field-expense & commission categories", "categories_timestamp or union merge", true),
      ListenerDiagnostic("pending", "COLLECTION", "onAdminPending / onOwnPendingResolved", "Real-time staff approval queue & decision feedback", "status == pending or submittedBy.uid == self", true),
      ListenerDiagnostic("deletions", "COLLECTION", "onDeletionsSnapshot", "Propagate soft deletes across devices", "Add / modify / remove on deletions", true),
      ListenerDiagnostic("photos", "COLLECTION", "onPhotosSnapshot", "Receipt & profile photos sync", "Photo doc snapshot updates", true),
    )
    SarimWebSchema.RECORD_COLLECTIONS.forEach { c ->
      listeners += ListenerDiagnostic(c, "COLLECTION", "onRecordCollectionSnapshot($c)", "Live delta merge into Room ($c)", "Doc changes + semantic conflict guards", true)
    }

    return DatabaseStructureSnapshot(
      collections = cols,
      configDocs = cfgSpecs,
      listeners = listeners,
      myDeviceShard = regStats.myDeviceShard,
      deviceId = devId,
      dbStats = quota,
      totalUploaded = regStats.totalUploaded,
      totalDownloaded = regStats.totalDownloaded,
      fyCloseCount = fyCloseCount,
      lastYearClosedDate = lastFyDate,
      lastCloudCheckAt = liveFs?.fetchedAt,
      isCloudLive = liveFs != null,
    )
  }

  suspend fun syncWithFirestore(
    isGated: Boolean = false,
    session: AccountSessionInfo? = null,
  ): Result<Int> {
    val user = syncManager.currentUser()
      ?: return Result.failure(IllegalStateException("Sign in to sync with cloud."))
    val whoUid = session?.uid?.takeIf { it.isNotBlank() } ?: user.uid
    val whoName = session?.name?.takeIf { it.isNotBlank() }
      ?: user.displayName?.takeIf { it.isNotBlank() }
      ?: user.email?.substringBefore("@").orEmpty()
    val whoEmail = session?.email?.takeIf { it.isNotBlank() } ?: user.email.orEmpty()

    return try {
      uuidRegistry.loadAll()
      val now = System.currentTimeMillis()
      var synced = 0
      val touchedCols = linkedSetOf<String>()

      // 1. Drain local ndapp_outbox queue first (gated members route into /pending)
      val outboxWritesByCol = linkedMapOf<String, Int>()
      safeReader.getOutboxAllOnce().forEach { op ->
        val isEditFlag = uuidRegistry.wasDownloaded(op.collection, op.docId)
        val res = syncManager.drainOutboxOp(
          op = op,
          isGated = isGated,
          whoUid = whoUid,
          whoName = whoName,
          whoEmail = whoEmail,
          isEdit = isEditFlag,
        )
        if (res.isSuccess) {
          dao.removeOutbox(op.id)
          if (isGated && SarimWebSchema.isGatedCollection(op.collection)) {
            touchedCols += SarimWebSchema.COL_PENDING
            outboxWritesByCol[SarimWebSchema.COL_PENDING] = (outboxWritesByCol[SarimWebSchema.COL_PENDING] ?: 0) + 1
            markLocalDocNotPending(op.collection, op.docId)
            if (op.action == "delete") {
              dao.getAllDeletions().firstOrNull { it.id == op.docId || it.recordId == op.docId }?.let { del ->
                dao.upsertDeletion(del.copy(syncedToCloud = true))
              }
            }
            uuidRegistry.clearDirty(op.collection, op.docId)
            synced++
          } else if (!isGated) {
            if (op.collection.isNotBlank()) {
              touchedCols += op.collection
              outboxWritesByCol[op.collection] = (outboxWritesByCol[op.collection] ?: 0) + 1
            }
            markLocalDocNotPending(op.collection, op.docId)
            if (op.action == "delete") {
              uuidRegistry.clearDirty(op.collection, op.docId)
            } else if (op.collection.isNotBlank()) {
              uuidRegistry.markUploaded(op.collection, op.docId, now)
            }
            synced++
          }
        } else {
          dao.bumpOutboxAttempt(op.id, now)
        }
      }
      outboxWritesByCol.forEach { (col, writes) ->
        uuidRegistry.updateSyncStats(col, 0, writes, now)
      }

      if (isGated) {
        // Submit any remaining syncPending gated collection rows via /pending
        val extraPending = submitRemainingPendingGatedRows(whoUid, whoName, whoEmail, now)
        if (extraPending > 0) {
          synced += extraPending
          touchedCols += SarimWebSchema.COL_PENDING
        }
        // Photos are writable by any workspace member per Firestore rules
        val pCount = pushPhotos()
        if (pCount > 0) {
          synced += pCount
          touchedCols += SarimWebSchema.COL_PHOTOS
        }
      } else {
        // 2. Push singleton config/ledger/settings documents matching web schema
        val allStores = dao.getAllStores()
        if (allStores.any { it.syncPending }) {
          val ts = dao.getKvMetaByUserKey("stores_timestamp")?.ts ?: now
          val res = syncManager.pushSingletonDoc(SarimWebSchema.COL_STORES, "list", SarimWebSchema.storesListDocPayload(allStores, ts))
          if (res.isSuccess) {
            allStores.filter { it.syncPending }.forEach { dao.upsertStore(it.copy(syncPending = false)) }
            synced++
            touchedCols += SarimWebSchema.COL_STORES
          } else {
            return Result.failure(res.exceptionOrNull() ?: Exception("Store list sync failed"))
          }
        }

        val allFormulas = dao.getAllFormulas()
        val allMixCalcs = dao.getAllMixCalcs()
        if (allFormulas.any { it.syncPending } || allMixCalcs.any { it.syncPending }) {
          val ts = dao.getKvMetaByUserKey("formulas_timestamp")?.ts ?: now
          val matMap = dao.getAllRawMaterials().associateBy { it.id }
          val payload = SarimWebSchema.formulasConfigDocPayload(allFormulas, allMixCalcs, matMap, ts)
          val res = syncManager.pushSingletonDoc(SarimWebSchema.COL_FORMULAS, "config", payload)
          if (res.isSuccess) {
            allFormulas.filter { it.syncPending }.forEach { dao.upsertFormula(it.copy(syncPending = false)) }
            allMixCalcs.filter { it.syncPending }.forEach { dao.upsertMixCalc(it.copy(syncPending = false)) }
            synced++
            touchedCols += SarimWebSchema.COL_FORMULAS
          }
        }

        val allLedger = dao.getAllDailyLedger()
        if (allLedger.any { it.syncPending }) {
          val ts = dao.getKvMetaByUserKey("daily_ledger_timestamp")?.ts ?: now
          val res = syncManager.pushSingletonDoc(SarimWebSchema.COL_DAILY_LEDGER, "entries", SarimWebSchema.dailyLedgerDocPayload(allLedger, ts))
          if (res.isSuccess) {
            allLedger.filter { it.syncPending }.forEach { dao.upsertLedgerEntry(it.copy(syncPending = false)) }
            synced++
            touchedCols += SarimWebSchema.COL_DAILY_LEDGER
          }
        }

        if (dao.getKvMetaByUserKey("settings_dirty") != null || dao.getKvMetaByUserKey("reps_dirty") != null) {
          val settingsKv = safeReader.getKvByUserKeyOnce("settings")
          val settingsObj = runCatching { JSONObject(settingsKv?.value ?: "{}") }.getOrDefault(JSONObject())
          val fyCloseCount = settingsObj.optInt("fyCloseCount", 0)
          val lastClosedAt = if (settingsObj.has("lastYearClosedAt") && !settingsObj.isNull("lastYearClosedAt")) settingsObj.optLong("lastYearClosedAt") else null
          val lastClosedDate = settingsObj.optString("lastYearClosedDate", "").takeIf { it.isNotBlank() }
          val settingsTs = dao.getKvMetaByUserKey("settings_timestamp")?.ts ?: settingsKv?.ts ?: now
          val repsKv = safeReader.getKvByUserKeyOnce("reps")
          val repsList = JsonCodecs.decodeStrings(repsKv?.value ?: "[]")
          val repsTs = dao.getKvMetaByUserKey("reps_timestamp")?.ts ?: repsKv?.ts ?: now
          val configPayload = SarimWebSchema.settingsConfigDocPayload(
            fyCloseCount = fyCloseCount,
            lastYearClosedAt = lastClosedAt,
            lastYearClosedDate = lastClosedDate,
            settingsTs = settingsTs,
            reps = repsList,
            repsTs = repsTs,
            now = now,
          )
          val cfgRes = syncManager.pushSingletonDoc(SarimWebSchema.COL_SETTINGS, "config", configPayload)
          val teamRes = syncManager.pushSingletonDoc(SarimWebSchema.COL_SETTINGS, "team", SarimWebSchema.teamDocPayload(repsList, now))
          if (cfgRes.isSuccess && teamRes.isSuccess) {
            dao.deleteKv("settings_dirty")
            dao.deleteKv("reps_dirty")
            synced++
            touchedCols += SarimWebSchema.COL_SETTINGS
          }
        }

        listOf(
          SarimWebSchema.COL_CATEGORIES,
          SarimWebSchema.COL_FIELD_EXP_CATS,
          SarimWebSchema.COL_COMM_CATS,
        ).forEach { catCol ->
          if (dao.getKvMetaByUserKey("${catCol}_dirty") != null) {
            val list = JsonCodecs.decodeStrings(safeReader.getKvByUserKeyOnce(catCol)?.value ?: "[]")
            val ts = dao.getKvMetaByUserKey("${catCol}_timestamp")?.ts ?: now
            val res = syncManager.pushSingletonDoc(catCol, "list", SarimWebSchema.categoriesDocPayload(catCol, list, ts))
            if (res.isSuccess) {
              dao.deleteKv("${catCol}_dirty")
              synced++
              touchedCols += catCol
            }
          }
        }

        // 3. Push any remaining pending rows across Firestore RECORD_COLLECTIONS
        val invC = pushInventoryAndPurchases()
        if (invC > 0) { synced += invC; touchedCols += SarimWebSchema.COL_INVENTORY }
        val facC = pushFactoryBatches()
        if (facC > 0) { synced += facC; touchedCols += SarimWebSchema.COL_FACTORY }
        val prodC = pushProductionAndTransfers()
        if (prodC > 0) { synced += prodC; touchedCols += SarimWebSchema.COL_PRODUCTION }
        val custC = pushCustomersAndClients()
        if (custC > 0) { synced += custC; touchedCols += SarimWebSchema.COL_CUSTOMERS; touchedCols += SarimWebSchema.COL_CLIENTS }
        val saleC = pushSalesAndRep()
        if (saleC > 0) { synced += saleC; touchedCols += SarimWebSchema.COL_SALES; touchedCols += SarimWebSchema.COL_REP }
        val payC = pushEntitiesTransactionsExpenses()
        if (payC > 0) { synced += payC; touchedCols += SarimWebSchema.COL_ENTITIES; touchedCols += SarimWebSchema.COL_TRANSACTIONS; touchedCols += SarimWebSchema.COL_EXPENSES }
        val retC = pushReturns()
        if (retC > 0) { synced += retC; touchedCols += SarimWebSchema.COL_RETURNS }
        val calcC = pushSettlements()
        if (calcC > 0) { synced += calcC; touchedCols += SarimWebSchema.COL_CALCULATOR }
        val photoC = pushPhotos()
        if (photoC > 0) { synced += photoC; touchedCols += SarimWebSchema.COL_PHOTOS }

        // 4. Push deletion tombstones
        dao.purgeExpiredDeletions(now)
        val unsyncedDels = safeReader.getUnsyncedDeletionsOnce()
        if (unsyncedDels.isNotEmpty()) {
          val delRes = syncManager.pushDeletionTombstones(unsyncedDels)
          if (delRes.isSuccess) {
            synced += delRes.getOrDefault(0)
            unsyncedDels.forEach { dao.upsertDeletion(it.copy(syncedToCloud = true)) }
            touchedCols += SarimWebSchema.COL_DELETIONS
          }
        }
      }

      if (touchedCols.isNotEmpty()) {
        syncManager.emitSyncUpdate(touchedCols)
      }

      // 5. Pull & merge remote state (deletions first, then singletons, then subcollections)
      pullAndMergeRemoteState(now = now, isGated = isGated, selfUid = whoUid)

      // 6. Stamp kv_store sync timestamp
      dao.upsertKv(
        KvStoreEntity(
          fullKey = "lastSync_all",
          userKey = "lastSync_all",
          collection = "sync",
          rowType = "meta",
          value = SarimWebSchema.isoUtc(now),
          ts = now,
          createdAt = now,
        )
      )
      prefs.setLastSync(now)
      Result.success(synced)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  private suspend fun submitRemainingPendingGatedRows(
    whoUid: String,
    whoName: String,
    whoEmail: String,
    now: Long,
  ): Int {
    val items = mutableListOf<PendingSubmitItem>()
    val markCallbacks = mutableListOf<suspend () -> Unit>()

    dao.getAllRawMaterials().filter { it.syncPending }.forEach { m ->
      items += PendingSubmitItem(
        collection = SarimWebSchema.COL_INVENTORY,
        docId = m.id,
        action = if (m.isDeleted) "delete" else "set",
        data = if (m.isDeleted) null else SarimWebSchema.materialToMap(m),
        isEdit = uuidRegistry.wasDownloaded(SarimWebSchema.COL_INVENTORY, m.id),
      )
      markCallbacks += {
        dao.upsertRawMaterial(m.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_INVENTORY, m.id)
      }
    }
    dao.getAllPurchases().filter { it.syncPending }.forEach { p ->
      items += PendingSubmitItem(
        collection = SarimWebSchema.COL_INVENTORY,
        docId = p.id,
        action = if (p.isDeleted) "delete" else "set",
        data = if (p.isDeleted) null else SarimWebSchema.purchaseLotToMap(p),
        isEdit = uuidRegistry.wasDownloaded(SarimWebSchema.COL_INVENTORY, p.id),
      )
      markCallbacks += {
        dao.upsertPurchase(p.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_INVENTORY, p.id)
      }
    }
    dao.getAllFactoryBatches().filter { it.syncPending }.forEach { b ->
      items += PendingSubmitItem(
        collection = SarimWebSchema.COL_FACTORY,
        docId = b.id,
        action = if (b.isDeleted) "delete" else "set",
        data = if (b.isDeleted) null else SarimWebSchema.factoryBatchToMap(b),
        isEdit = uuidRegistry.wasDownloaded(SarimWebSchema.COL_FACTORY, b.id),
      )
      markCallbacks += {
        dao.upsertFactoryBatch(b.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_FACTORY, b.id)
      }
    }
    dao.getAllProductionEntries().filter { it.syncPending }.forEach { p ->
      items += PendingSubmitItem(
        collection = SarimWebSchema.COL_PRODUCTION,
        docId = p.id,
        action = if (p.isDeleted) "delete" else "set",
        data = if (p.isDeleted) null else SarimWebSchema.productionToMap(p),
        isEdit = uuidRegistry.wasDownloaded(SarimWebSchema.COL_PRODUCTION, p.id),
      )
      markCallbacks += {
        dao.upsertProductionEntry(p.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_PRODUCTION, p.id)
      }
    }
    dao.getAllStockTransfers().filter { it.syncPending }.forEach { t ->
      val (outPair, inPair) = SarimWebSchema.stockTransferToOutInMaps(t)
      val act = if (t.isDeleted) "delete" else "set"
      items += PendingSubmitItem(SarimWebSchema.COL_PRODUCTION, outPair.first, act, if (t.isDeleted) null else outPair.second, uuidRegistry.wasDownloaded(SarimWebSchema.COL_PRODUCTION, outPair.first))
      items += PendingSubmitItem(SarimWebSchema.COL_PRODUCTION, inPair.first, act, if (t.isDeleted) null else inPair.second, uuidRegistry.wasDownloaded(SarimWebSchema.COL_PRODUCTION, inPair.first))
      markCallbacks += {
        dao.upsertStockTransfer(t.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_PRODUCTION, outPair.first)
        uuidRegistry.clearDirty(SarimWebSchema.COL_PRODUCTION, inPair.first)
      }
    }
    dao.getAllCustomers().filter { it.syncPending }.forEach { c ->
      val col = if (c.isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
      items += PendingSubmitItem(col, c.id, if (c.isDeleted) "delete" else "set", if (c.isDeleted) null else SarimWebSchema.customerToMap(c), uuidRegistry.wasDownloaded(col, c.id))
      markCallbacks += {
        dao.upsertCustomer(c.copy(syncPending = false))
        uuidRegistry.clearDirty(col, c.id)
      }
    }
    dao.getAllSales().filter { it.syncPending }.forEach { s ->
      val col = if (s.isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
      items += PendingSubmitItem(col, s.id, if (s.isDeleted) "delete" else "set", if (s.isDeleted) null else SarimWebSchema.saleToMap(s), uuidRegistry.wasDownloaded(col, s.id))
      markCallbacks += {
        dao.upsertSale(s.copy(syncPending = false))
        uuidRegistry.clearDirty(col, s.id)
      }
    }
    dao.getAllPaymentEntities().filter { it.syncPending }.forEach { e ->
      items += PendingSubmitItem(SarimWebSchema.COL_ENTITIES, e.id, if (e.isDeleted) "delete" else "set", if (e.isDeleted) null else SarimWebSchema.entityToMap(e), uuidRegistry.wasDownloaded(SarimWebSchema.COL_ENTITIES, e.id))
      markCallbacks += {
        dao.upsertPaymentEntity(e.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_ENTITIES, e.id)
      }
    }
    dao.getAllPaymentTransactions().filter { it.syncPending }.forEach { t ->
      items += PendingSubmitItem(SarimWebSchema.COL_TRANSACTIONS, t.id, if (t.isDeleted) "delete" else "set", if (t.isDeleted) null else SarimWebSchema.transactionToMap(t), uuidRegistry.wasDownloaded(SarimWebSchema.COL_TRANSACTIONS, t.id))
      markCallbacks += {
        dao.upsertPaymentTransaction(t.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_TRANSACTIONS, t.id)
      }
    }
    dao.getAllPaymentTransfers().filter { it.syncPending }.forEach { tr ->
      val (outPair, inPair) = SarimWebSchema.paymentTransferToOutInMaps(tr)
      val act = if (tr.isDeleted) "delete" else "set"
      items += PendingSubmitItem(SarimWebSchema.COL_TRANSACTIONS, outPair.first, act, if (tr.isDeleted) null else outPair.second, uuidRegistry.wasDownloaded(SarimWebSchema.COL_TRANSACTIONS, outPair.first))
      items += PendingSubmitItem(SarimWebSchema.COL_TRANSACTIONS, inPair.first, act, if (tr.isDeleted) null else inPair.second, uuidRegistry.wasDownloaded(SarimWebSchema.COL_TRANSACTIONS, inPair.first))
      markCallbacks += {
        dao.upsertPaymentTransfer(tr.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_TRANSACTIONS, outPair.first)
        uuidRegistry.clearDirty(SarimWebSchema.COL_TRANSACTIONS, inPair.first)
      }
    }
    dao.getAllExpenses().filter { it.syncPending }.forEach { e ->
      items += PendingSubmitItem(SarimWebSchema.COL_EXPENSES, e.id, if (e.isDeleted) "delete" else "set", if (e.isDeleted) null else SarimWebSchema.expenseToMap(e), uuidRegistry.wasDownloaded(SarimWebSchema.COL_EXPENSES, e.id))
      markCallbacks += {
        dao.upsertExpense(e.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_EXPENSES, e.id)
      }
    }
    dao.getAllReturns().filter { it.syncPending }.forEach { r ->
      items += PendingSubmitItem(SarimWebSchema.COL_RETURNS, r.id, if (r.isDeleted) "delete" else "set", if (r.isDeleted) null else SarimWebSchema.returnLogToMap(r), uuidRegistry.wasDownloaded(SarimWebSchema.COL_RETURNS, r.id))
      markCallbacks += {
        dao.upsertReturnLog(r.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_RETURNS, r.id)
      }
    }
    dao.getAllSettlements().filter { it.syncPending }.forEach { c ->
      items += PendingSubmitItem(SarimWebSchema.COL_CALCULATOR, c.id, if (c.isDeleted) "delete" else "set", if (c.isDeleted) null else SarimWebSchema.settlementToMap(c), uuidRegistry.wasDownloaded(SarimWebSchema.COL_CALCULATOR, c.id))
      markCallbacks += {
        dao.upsertSettlement(c.copy(syncPending = false))
        uuidRegistry.clearDirty(SarimWebSchema.COL_CALCULATOR, c.id)
      }
    }

    if (items.isEmpty()) return 0
    val res = syncManager.submitPendingBatch(items, whoUid, whoName, whoEmail)
    if (res.isSuccess) {
      markCallbacks.forEach { it() }
      val allDels = dao.getAllDeletions()
      items.forEach { item ->
        if (item.action == "delete") {
          allDels.firstOrNull { it.id == item.docId || it.recordId == item.docId }?.let { del ->
            if (!del.syncedToCloud) dao.upsertDeletion(del.copy(syncedToCloud = true))
          }
        }
        val pendingDoc = SarimWebSchema.buildPendingDocMap(
          collection = item.collection,
          docId = item.docId,
          action = item.action,
          data = item.data,
          whoUid = whoUid,
          whoName = whoName,
          whoEmail = whoEmail,
          nowMs = now,
          isEdit = item.isEdit,
        )
        SarimWebPullMappers.mapToApproval(SarimWebSchema.pendingId(item.collection, item.docId), pendingDoc)?.let {
          dao.upsertApproval(it)
        }
      }
      uuidRegistry.updateSyncStats(SarimWebSchema.COL_PENDING, 0, items.size, now)
      return res.getOrDefault(0)
    }
    return 0
  }

  private suspend fun markLocalDocNotPending(collection: String, docId: String) {
    when (collection) {
      SarimWebSchema.COL_PRODUCTION -> {
        dao.getAllProductionEntries().firstOrNull { it.id == docId }?.let { dao.upsertProductionEntry(it.copy(syncPending = false)) }
        dao.getAllStockTransfers().firstOrNull { it.outDocId == docId || it.inDocId == docId || it.id == docId }?.let {
          dao.upsertStockTransfer(it.copy(syncPending = false))
        }
      }
      SarimWebSchema.COL_SALES, SarimWebSchema.COL_REP -> {
        dao.getAllSales().firstOrNull { it.id == docId }?.let { dao.upsertSale(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_CUSTOMERS, SarimWebSchema.COL_CLIENTS -> {
        dao.getAllCustomers().firstOrNull { it.id == docId }?.let { dao.upsertCustomer(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_INVENTORY -> {
        dao.getAllRawMaterials().firstOrNull { it.id == docId }?.let { dao.upsertRawMaterial(it.copy(syncPending = false)) }
        dao.getAllPurchases().firstOrNull { it.id == docId }?.let { dao.upsertPurchase(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_FACTORY -> {
        dao.getAllFactoryBatches().firstOrNull { it.id == docId }?.let { dao.upsertFactoryBatch(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_ENTITIES -> {
        dao.getAllPaymentEntities().firstOrNull { it.id == docId }?.let { dao.upsertPaymentEntity(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_TRANSACTIONS -> {
        dao.getAllPaymentTransactions().firstOrNull { it.id == docId }?.let { dao.upsertPaymentTransaction(it.copy(syncPending = false)) }
        dao.getAllPaymentTransfers().firstOrNull { it.outDocId == docId || it.inDocId == docId || it.id == docId }?.let {
          dao.upsertPaymentTransfer(it.copy(syncPending = false))
        }
      }
      SarimWebSchema.COL_EXPENSES -> {
        dao.getAllExpenses().firstOrNull { it.id == docId }?.let { dao.upsertExpense(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_RETURNS -> {
        dao.getAllReturns().firstOrNull { it.id == docId }?.let { dao.upsertReturnLog(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_CALCULATOR -> {
        dao.getAllSettlements().firstOrNull { it.id == docId }?.let { dao.upsertSettlement(it.copy(syncPending = false)) }
      }
    }
  }

  private suspend fun pushInventoryAndPurchases(): Int {
    var count = 0
    val mats = dao.getAllRawMaterials().filter { it.syncPending && !it.isDeleted }
    val purchases = dao.getAllPurchases().filter { it.syncPending && !it.isDeleted }
    val batch = mats.map { it.id to SarimWebSchema.materialToMap(it) } +
      purchases.map { it.id to SarimWebSchema.purchaseLotToMap(it) }
    if (batch.isNotEmpty()) {
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_INVENTORY, batch)
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        mats.forEach { dao.upsertRawMaterial(it.copy(syncPending = false)) }
        purchases.forEach { dao.upsertPurchase(it.copy(syncPending = false)) }
        uuidRegistry.markUploadedBatch(SarimWebSchema.COL_INVENTORY, batch.map { it.first })
        uuidRegistry.updateSyncStats(SarimWebSchema.COL_INVENTORY, 0, batch.size)
      }
    }
    return count
  }

  private suspend fun pushFactoryBatches(): Int {
    val batches = dao.getAllFactoryBatches().filter { it.syncPending && !it.isDeleted }
    if (batches.isEmpty()) return 0
    val docs = batches.map { it.id to SarimWebSchema.factoryBatchToMap(it) }
    val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_FACTORY, docs)
    if (res.isSuccess) {
      batches.forEach { dao.upsertFactoryBatch(it.copy(syncPending = false)) }
      uuidRegistry.markUploadedBatch(SarimWebSchema.COL_FACTORY, docs.map { it.first })
      uuidRegistry.updateSyncStats(SarimWebSchema.COL_FACTORY, 0, docs.size)
      return res.getOrDefault(0)
    }
    return 0
  }

  private suspend fun pushProductionAndTransfers(): Int {
    val prod = dao.getAllProductionEntries().filter { it.syncPending && !it.isDeleted }
    val transfers = dao.getAllStockTransfers().filter { it.syncPending && !it.isDeleted }
    val docs = mutableListOf<Pair<String, Map<String, Any?>>>()
    prod.forEach { docs += it.id to SarimWebSchema.productionToMap(it) }
    transfers.forEach { t ->
      val (outPair, inPair) = SarimWebSchema.stockTransferToOutInMaps(t)
      docs += outPair
      docs += inPair
    }
    if (docs.isEmpty()) return 0
    val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_PRODUCTION, docs)
    if (res.isSuccess) {
      prod.forEach { dao.upsertProductionEntry(it.copy(syncPending = false)) }
      transfers.forEach { dao.upsertStockTransfer(it.copy(syncPending = false)) }
      uuidRegistry.markUploadedBatch(SarimWebSchema.COL_PRODUCTION, docs.map { it.first })
      uuidRegistry.updateSyncStats(SarimWebSchema.COL_PRODUCTION, 0, docs.size)
      return res.getOrDefault(0)
    }
    return 0
  }

  private suspend fun pushCustomersAndClients(): Int {
    var count = 0
    val all = dao.getAllCustomers().filter { it.syncPending && !it.isDeleted }
    val customers = all.filter { !it.isRepCustomer }
    if (customers.isNotEmpty()) {
      val docs = customers.map { it.id to SarimWebSchema.customerToMap(it) }
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_CUSTOMERS, docs)
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        customers.forEach { dao.upsertCustomer(it.copy(syncPending = false)) }
        uuidRegistry.markUploadedBatch(SarimWebSchema.COL_CUSTOMERS, docs.map { it.first })
        uuidRegistry.updateSyncStats(SarimWebSchema.COL_CUSTOMERS, 0, docs.size)
      }
    }
    val clients = all.filter { it.isRepCustomer }
    if (clients.isNotEmpty()) {
      val docs = clients.map { it.id to SarimWebSchema.customerToMap(it) }
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_CLIENTS, docs)
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        clients.forEach { dao.upsertCustomer(it.copy(syncPending = false)) }
        uuidRegistry.markUploadedBatch(SarimWebSchema.COL_CLIENTS, docs.map { it.first })
        uuidRegistry.updateSyncStats(SarimWebSchema.COL_CLIENTS, 0, docs.size)
      }
    }
    return count
  }

  private suspend fun pushSalesAndRep(): Int {
    var count = 0
    val all = dao.getAllSales().filter { it.syncPending && !it.isDeleted }
    val sales = all.filter { !it.isRepSale }
    if (sales.isNotEmpty()) {
      val docs = sales.map { it.id to SarimWebSchema.saleToMap(it) }
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_SALES, docs)
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        sales.forEach { dao.upsertSale(it.copy(syncPending = false)) }
        uuidRegistry.markUploadedBatch(SarimWebSchema.COL_SALES, docs.map { it.first })
        uuidRegistry.updateSyncStats(SarimWebSchema.COL_SALES, 0, docs.size)
      }
    }
    val rep = all.filter { it.isRepSale }
    if (rep.isNotEmpty()) {
      val docs = rep.map { it.id to SarimWebSchema.saleToMap(it) }
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_REP, docs)
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        rep.forEach { dao.upsertSale(it.copy(syncPending = false)) }
        uuidRegistry.markUploadedBatch(SarimWebSchema.COL_REP, docs.map { it.first })
        uuidRegistry.updateSyncStats(SarimWebSchema.COL_REP, 0, docs.size)
      }
    }
    return count
  }

  private suspend fun pushEntitiesTransactionsExpenses(): Int {
    var count = 0
    val entities = dao.getAllPaymentEntities().filter { it.syncPending && !it.isDeleted }
    if (entities.isNotEmpty()) {
      val docs = entities.map { it.id to SarimWebSchema.entityToMap(it) }
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_ENTITIES, docs)
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        entities.forEach { dao.upsertPaymentEntity(it.copy(syncPending = false)) }
        uuidRegistry.markUploadedBatch(SarimWebSchema.COL_ENTITIES, docs.map { it.first })
        uuidRegistry.updateSyncStats(SarimWebSchema.COL_ENTITIES, 0, docs.size)
      }
    }
    val txs = dao.getAllPaymentTransactions().filter { it.syncPending && !it.isDeleted }
    val transfers = dao.getAllPaymentTransfers().filter { it.syncPending && !it.isDeleted }
    val txDocs = mutableListOf<Pair<String, Map<String, Any?>>>()
    txs.forEach { txDocs += it.id to SarimWebSchema.transactionToMap(it) }
    transfers.forEach { tr ->
      val (outPair, inPair) = SarimWebSchema.paymentTransferToOutInMaps(tr)
      txDocs += outPair
      txDocs += inPair
    }
    if (txDocs.isNotEmpty()) {
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_TRANSACTIONS, txDocs)
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        txs.forEach { dao.upsertPaymentTransaction(it.copy(syncPending = false)) }
        transfers.forEach { dao.upsertPaymentTransfer(it.copy(syncPending = false)) }
        uuidRegistry.markUploadedBatch(SarimWebSchema.COL_TRANSACTIONS, txDocs.map { it.first })
        uuidRegistry.updateSyncStats(SarimWebSchema.COL_TRANSACTIONS, 0, txDocs.size)
      }
    }
    val exps = dao.getAllExpenses().filter { it.syncPending && !it.isDeleted }
    if (exps.isNotEmpty()) {
      val docs = exps.map { it.id to SarimWebSchema.expenseToMap(it) }
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_EXPENSES, docs)
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        exps.forEach { dao.upsertExpense(it.copy(syncPending = false)) }
        uuidRegistry.markUploadedBatch(SarimWebSchema.COL_EXPENSES, docs.map { it.first })
        uuidRegistry.updateSyncStats(SarimWebSchema.COL_EXPENSES, 0, docs.size)
      }
    }
    return count
  }

  private suspend fun pushReturns(): Int {
    val rows = dao.getAllReturns().filter { it.syncPending && !it.isDeleted }
    if (rows.isEmpty()) return 0
    val docs = rows.map { it.id to SarimWebSchema.returnLogToMap(it) }
    val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_RETURNS, docs)
    if (res.isSuccess) {
      rows.forEach { dao.upsertReturnLog(it.copy(syncPending = false)) }
      uuidRegistry.markUploadedBatch(SarimWebSchema.COL_RETURNS, docs.map { it.first })
      uuidRegistry.updateSyncStats(SarimWebSchema.COL_RETURNS, 0, docs.size)
      return res.getOrDefault(0)
    }
    return 0
  }

  private suspend fun pushSettlements(): Int {
    val rows = dao.getAllSettlements().filter { it.syncPending && !it.isDeleted }
    if (rows.isEmpty()) return 0
    val docs = rows.map { it.id to SarimWebSchema.settlementToMap(it) }
    val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_CALCULATOR, docs)
    if (res.isSuccess) {
      rows.forEach { dao.upsertSettlement(it.copy(syncPending = false)) }
      uuidRegistry.markUploadedBatch(SarimWebSchema.COL_CALCULATOR, docs.map { it.first })
      uuidRegistry.updateSyncStats(SarimWebSchema.COL_CALCULATOR, 0, docs.size)
      return res.getOrDefault(0)
    }
    return 0
  }

  private suspend fun pushPhotos(): Int {
    val photos = safeReader.getPendingPhotosOnce()
    if (photos.isEmpty()) return 0
    val docs = photos.map { p ->
      p.docId to linkedMapOf<String, Any?>(
        "key" to p.photoKey,
        "data" to p.data,
        "deleted" to p.deleted,
        "updatedAt" to p.updatedAt,
      )
    }
    val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_PHOTOS, docs)
    if (res.isSuccess) {
      photos.forEach { dao.upsertPhoto(it.copy(syncPending = false)) }
      return res.getOrDefault(0)
    }
    return 0
  }

  private suspend fun getTombstoneKeySet(): HashSet<String> {
    val deletedKeySet = hashSetOf<String>()
    dao.getAllDeletionRefs().forEach { d ->
      deletedKeySet += "${d.collection}:${d.recordId}"
      deletedKeySet += d.recordId
    }
    return deletedKeySet
  }

  private fun isTombstoned(keySet: Set<String>, col: String, id: String): Boolean =
    keySet.contains("$col:$id") || keySet.contains(id)

  private suspend fun pullAndMergeRemoteState(
    now: Long,
    isGated: Boolean,
    selfUid: String,
  ) {
    // 1. Deletions first
    dao.purgeExpiredDeletions(now)
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_DELETIONS).getOrNull()?.let { remoteDeletions ->
      val remoteIds = remoteDeletions.map { it.first }.toSet()
      val localSyncedIds = dao.getAllDeletionRefs().map { it.id }.filter { id ->
        safeReader.getUnsyncedDeletionsOnce().none { it.id == id }
      }
      val removedIds = localSyncedIds.filter { it !in remoteIds }
      applyRemoteDeletionsSnapshot(remoteDeletions, removedIds, now)
    }

    // 2. Singleton documents
    syncManager.fetchSingletonDoc(SarimWebSchema.COL_STORES, "list").getOrNull()?.let { doc ->
      applyRemoteStoresDoc(doc)
    }
    syncManager.fetchSingletonDoc(SarimWebSchema.COL_FORMULAS, "config").getOrNull()?.let { doc ->
      applyRemoteFormulasDoc(doc)
    }
    syncManager.fetchSingletonDoc(SarimWebSchema.COL_DAILY_LEDGER, "entries").getOrNull()?.let { doc ->
      applyRemoteDailyLedgerDoc(doc)
    }
    syncManager.fetchSingletonDoc(SarimWebSchema.COL_SETTINGS, "config").getOrNull()?.let { doc ->
      applyRemoteSettingsConfigDoc(doc)
    }
    syncManager.fetchSingletonDoc(SarimWebSchema.COL_SETTINGS, "team").getOrNull()?.let { doc ->
      applyRemoteTeamDoc(doc)
    }

    listOf(
      SarimWebSchema.COL_CATEGORIES,
      SarimWebSchema.COL_FIELD_EXP_CATS,
      SarimWebSchema.COL_COMM_CATS,
    ).forEach { catCol ->
      syncManager.fetchSingletonDoc(catCol, "list").getOrNull()?.let { doc ->
        applyRemoteCategoryDoc(catCol, doc, now)
      }
    }

    // 3. Photos
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_PHOTOS).getOrNull()?.let { docs ->
      applyRemotePhotosSnapshot(docs)
    }

    // 4. Record collections
    SarimWebSchema.RECORD_COLLECTIONS.forEach { col ->
      syncManager.fetchCollectionDocs(col).getOrNull()?.let { docs ->
        applyRemoteRecordSnapshot(collection = col, docs = docs, removedIds = emptyList(), isFullPull = true)
      }
    }

    // 5. Pending approvals
    if (!isGated) {
      syncManager.fetchCollectionDocs(SarimWebSchema.COL_PENDING).getOrNull()?.let { docs ->
        val items = docs.mapNotNull { (id, m) -> SarimWebPullMappers.mapToApproval(id, m) }
          .filter { it.status == "PENDING" }
        replaceAdminPendingFromSnapshot(items)
      }
      val wsUid = runCatching { syncManager.resolveWorkspaceRootUid() }.getOrNull()
      if (!wsUid.isNullOrBlank()) {
        syncManager.loadWorkspaceAccounts(wsUid).getOrNull()?.let { accts ->
          replaceAccountsFromCloud(accts)
        }
      }
    } else if (selfUid.isNotBlank()) {
      syncManager.fetchOwnPendingDocs(selfUid).getOrNull()?.let { docs ->
        val pendingItems = docs.mapNotNull { (id, m) -> SarimWebPullMappers.mapToApproval(id, m) }
        dao.clearApprovals()
        pendingItems.filter { it.status == "PENDING" }.forEach { dao.upsertApproval(it) }
      }
    }
  }

  // =====================================================================
  // Admin Approvals & Member Pending Resolution
  // =====================================================================

  suspend fun resolveApprovals(
    items: List<ApprovalRequestEntity>,
    approved: Boolean,
    deciderUid: String,
    deciderName: String,
  ): Result<Int> {
    if (items.isEmpty()) return Result.success(0)
    val res = if (approved) {
      syncManager.commitApprovalsBatch(items, deciderUid, deciderName)
    } else {
      syncManager.discardApprovalsBatch(items, deciderUid, deciderName)
    }
    if (res.isFailure) return res

    val touchedCols = linkedSetOf(SarimWebSchema.COL_PENDING)
    items.forEach { req ->
      dao.deleteApproval(req.id)
      dao.removeOutboxForDoc(SarimWebSchema.COL_PENDING, req.id)
      if (approved && req.targetCollection.isNotBlank() && req.docId.isNotBlank()) {
        touchedCols += req.targetCollection
        if (req.action == "delete") {
          softOrHardDeleteRecordFromApproval(req)
        } else {
          applyApprovedDocToLocalRoom(req)
        }
      }
    }
    syncManager.emitSyncUpdate(touchedCols)
    return res
  }

  suspend fun handleOwnPendingResolved(req: ApprovalRequestEntity) {
    if (req.status == "DISCARDED" && req.targetCollection.isNotBlank() && req.docId.isNotBlank()) {
      val cloudDoc = syncManager.fetchSingleCloudDoc(req.targetCollection, req.docId).getOrNull()
      if (req.action == "delete") {
        dao.deleteDeletion(req.docId)
        dao.removeOutboxForDoc(req.targetCollection, req.docId)
        if (cloudDoc != null) {
          upsertSingleRemoteDocToRoom(req.targetCollection, req.docId, cloudDoc)
        } else {
          restoreLocalRecordByCollection(req.targetCollection, req.docId)
        }
      } else {
        if (cloudDoc != null) {
          upsertSingleRemoteDocToRoom(req.targetCollection, req.docId, cloudDoc)
        } else {
          hardDeleteLocalRecordByCollection(req.targetCollection, req.docId)
        }
      }
    } else if (req.status == "APPROVED" && req.targetCollection.isNotBlank() && req.docId.isNotBlank()) {
      if (req.action != "delete") {
        val cloudDoc = syncManager.fetchSingleCloudDoc(req.targetCollection, req.docId).getOrNull()
        if (cloudDoc != null) {
          upsertSingleRemoteDocToRoom(req.targetCollection, req.docId, cloudDoc)
        } else {
          markLocalDocNotPending(req.targetCollection, req.docId)
        }
      }
    }
    dao.deleteApproval(req.id)
    syncManager.deleteOwnPendingDoc(req.id)
  }

  private suspend fun applyApprovedDocToLocalRoom(req: ApprovalRequestEntity) {
    val rawMap = runCatching {
      SarimWebSchema.jsonObjectToMap(JSONObject(req.payloadJson))
    }.getOrDefault(emptyMap())
    upsertSingleRemoteDocToRoom(req.targetCollection, req.docId, rawMap)
  }

  private suspend fun softOrHardDeleteRecordFromApproval(req: ApprovalRequestEntity) {
    val now = System.currentTimeMillis()
    dao.upsertDeletion(
      DeletionTombstoneEntity(
        id = req.docId,
        recordId = req.docId,
        collection = req.targetCollection,
        recordType = req.targetCollection,
        deletedAt = now,
        expiresAt = now + SarimWebSchema.TOMBSTONE_EXPIRY_MS,
        syncedToCloud = true,
      )
    )
    hardDeleteLocalRecordByCollection(req.targetCollection, req.docId)
  }

  private suspend fun upsertSingleRemoteDocToRoom(col: String, docId: String, m: Map<String, Any?>) {
    when (col) {
      SarimWebSchema.COL_INVENTORY -> {
        if (SarimWebSchema.bool(m["isPurchaseLot"])) {
          SarimWebPullMappers.mapToPurchaseLot(docId, m)?.let { dao.upsertPurchase(it.copy(syncPending = false)) }
        } else {
          SarimWebPullMappers.mapToMaterial(docId, m)?.let { dao.upsertRawMaterial(it.copy(syncPending = false)) }
        }
      }
      SarimWebSchema.COL_FACTORY -> {
        SarimWebPullMappers.mapToFactoryBatch(docId, m)?.let { dao.upsertFactoryBatch(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_PRODUCTION -> {
        if (!SarimWebSchema.bool(m["isTransfer"])) {
          SarimWebSchema.mapToProduction(docId, m)?.let { dao.upsertProductionEntry(it.copy(syncPending = false)) }
        }
      }
      SarimWebSchema.COL_CUSTOMERS -> {
        SarimWebSchema.mapToCustomer(docId, m, isRepCustomer = false)?.let { dao.upsertCustomer(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_CLIENTS -> {
        SarimWebSchema.mapToCustomer(docId, m, isRepCustomer = true)?.let { dao.upsertCustomer(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_SALES -> {
        SarimWebSchema.mapToSale(docId, m, isRep = false)?.let { dao.upsertSale(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_REP -> {
        SarimWebSchema.mapToSale(docId, m, isRep = true)?.let { dao.upsertSale(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_ENTITIES -> {
        SarimWebSchema.mapToEntity(docId, m)?.let { dao.upsertPaymentEntity(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_TRANSACTIONS -> {
        if (!SarimWebSchema.bool(m["isTransfer"])) {
          SarimWebSchema.mapToTransaction(docId, m)?.let { dao.upsertPaymentTransaction(it.copy(syncPending = false)) }
        }
      }
      SarimWebSchema.COL_EXPENSES -> {
        SarimWebSchema.mapToExpense(docId, m)?.let { dao.upsertExpense(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_RETURNS -> {
        SarimWebPullMappers.mapToReturnLog(docId, m)?.let { dao.upsertReturnLog(it.copy(syncPending = false)) }
      }
      SarimWebSchema.COL_CALCULATOR -> {
        SarimWebPullMappers.mapToSettlement(docId, m)?.let { dao.upsertSettlement(it.copy(syncPending = false)) }
      }
    }
  }

  private suspend fun restoreLocalRecordByCollection(col: String, docId: String) {
    when (col) {
      SarimWebSchema.COL_INVENTORY -> {
        dao.restoreRawMaterial(docId)
        dao.restorePurchase(docId)
      }
      SarimWebSchema.COL_FACTORY -> dao.restoreFactoryBatch(docId)
      SarimWebSchema.COL_PRODUCTION -> {
        dao.restoreProductionEntry(docId)
        dao.getAllStockTransfers().firstOrNull { it.outDocId == docId || it.inDocId == docId || it.id == docId }?.let {
          dao.restoreStockTransfer(it.id)
        }
      }
      SarimWebSchema.COL_CUSTOMERS, SarimWebSchema.COL_CLIENTS -> dao.restoreCustomer(docId)
      SarimWebSchema.COL_SALES, SarimWebSchema.COL_REP -> dao.restoreSale(docId)
      SarimWebSchema.COL_ENTITIES -> dao.restorePaymentEntity(docId)
      SarimWebSchema.COL_TRANSACTIONS -> {
        dao.restorePaymentTransaction(docId)
        dao.getAllPaymentTransfers().firstOrNull { it.outDocId == docId || it.inDocId == docId || it.id == docId }?.let {
          dao.restorePaymentTransfer(it.id)
        }
      }
      SarimWebSchema.COL_EXPENSES -> dao.restoreExpense(docId)
      SarimWebSchema.COL_RETURNS -> dao.restoreReturnLog(docId)
      SarimWebSchema.COL_CALCULATOR -> dao.restoreSettlement(docId)
    }
  }

  private suspend fun hardDeleteLocalRecordByCollection(col: String, docId: String) {
    when (col) {
      SarimWebSchema.COL_INVENTORY -> {
        dao.hardDeleteRawMaterial(docId)
        dao.hardDeletePurchase(docId)
      }
      SarimWebSchema.COL_FACTORY -> dao.hardDeleteFactoryBatch(docId)
      SarimWebSchema.COL_PRODUCTION -> {
        dao.hardDeleteProductionEntry(docId)
        dao.getAllStockTransfers().firstOrNull { it.outDocId == docId || it.inDocId == docId || it.id == docId }?.let {
          dao.hardDeleteStockTransfer(it.id)
        }
      }
      SarimWebSchema.COL_CUSTOMERS, SarimWebSchema.COL_CLIENTS -> dao.hardDeleteCustomer(docId)
      SarimWebSchema.COL_SALES, SarimWebSchema.COL_REP -> dao.hardDeleteSale(docId)
      SarimWebSchema.COL_ENTITIES -> dao.hardDeletePaymentEntity(docId)
      SarimWebSchema.COL_TRANSACTIONS -> {
        dao.hardDeletePaymentTransaction(docId)
        dao.getAllPaymentTransfers().firstOrNull { it.outDocId == docId || it.inDocId == docId || it.id == docId }?.let {
          dao.hardDeletePaymentTransfer(it.id)
        }
      }
      SarimWebSchema.COL_EXPENSES -> dao.hardDeleteExpense(docId)
      SarimWebSchema.COL_RETURNS -> dao.hardDeleteReturnLog(docId)
      SarimWebSchema.COL_CALCULATOR -> dao.hardDeleteSettlement(docId)
    }
  }

  // =====================================================================
  // Realtime Snapshot Handlers
  // =====================================================================

  suspend fun replaceAdminPendingFromSnapshot(items: List<ApprovalRequestEntity>) {
    dao.clearApprovals()
    items.forEach { dao.upsertApproval(it) }
  }

  suspend fun replaceAccountsFromCloud(accounts: List<AppAccountEntity>) {
    dao.clearAppAccounts()
    accounts.forEach { dao.upsertAccount(it) }
  }

  suspend fun applyRemoteSettingsConfigDoc(doc: Map<String, Any>, now: Long = System.currentTimeMillis()) {
    val remoteSettings = doc["settings"] as? Map<*, *>
    val remoteSettingsTs = SarimWebSchema.parseEpoch(doc["settings_timestamp"], null).let {
      if (doc["settings_timestamp"] != null) it else 0L
    }
    if (remoteSettings != null) {
      val localMeta = dao.getKvMetaByUserKey("settings_timestamp")?.ts ?: 0L
      if (dao.getKvMetaByUserKey("settings_dirty") == null || remoteSettingsTs >= localMeta) {
        val obj = JSONObject().apply {
          put("fyCloseCount", (remoteSettings["fyCloseCount"] as? Number)?.toInt() ?: 0)
          remoteSettings["lastYearClosedAt"]?.let { put("lastYearClosedAt", SarimWebSchema.parseEpoch(it)) }
          remoteSettings["lastYearClosedDate"]?.let { put("lastYearClosedDate", it.toString()) }
        }
        dao.upsertKv(
          KvStoreEntity(
            fullKey = "settings",
            userKey = "settings",
            collection = SarimWebSchema.COL_SETTINGS,
            rowType = "settings",
            value = obj.toString(),
            ts = maxOf(remoteSettingsTs, now),
            createdAt = now,
          )
        )
        dao.upsertKv(
          KvStoreEntity(
            fullKey = "settings_timestamp",
            userKey = "settings_timestamp",
            collection = SarimWebSchema.COL_SETTINGS,
            rowType = "meta",
            value = maxOf(remoteSettingsTs, localMeta).toString(),
            ts = maxOf(remoteSettingsTs, localMeta),
            createdAt = now,
          )
        )
        dao.deleteKv("settings_dirty")
      }
    }

    val remoteReps = (doc["reps"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
    if (remoteReps != null) {
      val remoteRepsTs = if (doc["reps_timestamp"] != null) SarimWebSchema.parseEpoch(doc["reps_timestamp"]) else now
      val localRepsTs = dao.getKvMetaByUserKey("reps_timestamp")?.ts ?: 0L
      if (dao.getKvMetaByUserKey("reps_dirty") == null && remoteRepsTs >= localRepsTs) {
        val clean = remoteReps.distinctBy { it.uppercase() }
        dao.upsertKv(
          KvStoreEntity(
            fullKey = "reps",
            userKey = "reps",
            collection = SarimWebSchema.COL_SETTINGS,
            rowType = "settings",
            value = JsonCodecs.encodeStrings(clean),
            ts = remoteRepsTs,
            createdAt = now,
          )
        )
        dao.upsertKv(
          KvStoreEntity(
            fullKey = "reps_timestamp",
            userKey = "reps_timestamp",
            collection = SarimWebSchema.COL_SETTINGS,
            rowType = "meta",
            value = remoteRepsTs.toString(),
            ts = remoteRepsTs,
            createdAt = now,
          )
        )
      }
    }
  }

  suspend fun applyRemoteTeamDoc(doc: Map<String, Any>, now: Long = System.currentTimeMillis()) {
    val remoteReps = (doc["reps"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } } ?: return
    val remoteTs = if (doc["updated"] != null) SarimWebSchema.parseEpoch(doc["updated"]) else now
    val localRepsTs = dao.getKvMetaByUserKey("reps_timestamp")?.ts ?: 0L
    if (dao.getKvMetaByUserKey("reps_dirty") == null && remoteTs >= localRepsTs) {
      val clean = remoteReps.distinctBy { it.uppercase() }
      dao.upsertKv(
        KvStoreEntity(
          fullKey = "reps",
          userKey = "reps",
          collection = SarimWebSchema.COL_SETTINGS,
          rowType = "settings",
          value = JsonCodecs.encodeStrings(clean),
          ts = remoteTs,
          createdAt = now,
        )
      )
      dao.upsertKv(
        KvStoreEntity(
          fullKey = "reps_timestamp",
          userKey = "reps_timestamp",
          collection = SarimWebSchema.COL_SETTINGS,
          rowType = "meta",
          value = remoteTs.toString(),
          ts = remoteTs,
          createdAt = now,
        )
      )
    }
  }

  suspend fun applyRemoteStoresDoc(doc: Map<String, Any>) {
    val remoteTs = SarimWebSchema.parseEpoch(doc["stores_timestamp"])
    val localStores = dao.getAllStores().associateBy { it.storeKey }
    val tombstones = getTombstoneKeySet()
    val storesList = doc["stores"] as? List<*> ?: emptyList<Any>()
    val remoteKeys = linkedSetOf<String>()
    storesList.forEach { raw ->
      val map = raw as? Map<*, *> ?: return@forEach
      val remote = SarimWebPullMappers.mapToStore(map)?.copy(updatedAt = remoteTs) ?: return@forEach
      remoteKeys += remote.storeKey
      if (!isTombstoned(tombstones, SarimWebSchema.COL_STORES, remote.storeKey)) {
        val local = localStores[remote.storeKey]
        if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
          dao.upsertStore(remote)
        }
      }
    }
    if (storesList.isNotEmpty() || doc.containsKey("stores")) {
      localStores.values.filter { !it.syncPending && !it.isDeleted && it.storeKey !in remoteKeys }.forEach { gone ->
        dao.softDeleteStore(gone.storeKey, System.currentTimeMillis())
      }
    }
  }

  suspend fun applyRemoteFormulasDoc(doc: Map<String, Any>) {
    val tombstones = getTombstoneKeySet()
    val localFormulas = dao.getAllFormulas().associateBy { it.id }
    val localCalcs = dao.getAllMixCalcs().associateBy { it.id }
    val (remoteFormulas, remoteCalcs) = SarimWebPullMappers.mapFormulasConfigDoc(doc, localFormulas)
    remoteFormulas.forEach { rf ->
      if (!isTombstoned(tombstones, SarimWebSchema.COL_FORMULAS, rf.id)) {
        val local = localFormulas[rf.id]
        if (local == null || (!local.syncPending && rf.updatedAt >= local.updatedAt)) {
          dao.upsertFormula(rf)
        }
      }
    }
    remoteCalcs.forEach { rc ->
      if (!isTombstoned(tombstones, SarimWebSchema.COL_FORMULAS, rc.id)) {
        val local = localCalcs[rc.id]
        if (local == null || (!local.syncPending && rc.updatedAt >= local.updatedAt)) {
          dao.upsertMixCalc(rc)
        }
      }
    }
  }

  suspend fun applyRemoteDailyLedgerDoc(doc: Map<String, Any>) {
    val tombstones = getTombstoneKeySet()
    val localLedger = dao.getAllDailyLedger().associateBy { it.id }
    val rows = doc["daily_ledger"] as? List<*> ?: emptyList<Any>()
    rows.forEach { raw ->
      val map = raw as? Map<*, *> ?: return@forEach
      val rl = SarimWebPullMappers.mapToDailyLedger(map) ?: return@forEach
      if (!isTombstoned(tombstones, SarimWebSchema.COL_DAILY_LEDGER, rl.id)) {
        val local = localLedger[rl.id]
        if (local == null || (!local.syncPending && rl.updatedAt >= local.updatedAt)) {
          dao.upsertLedgerEntry(rl)
        }
      }
    }
  }

  suspend fun applyRemoteCategoryDoc(catCol: String, doc: Map<String, Any>, now: Long = System.currentTimeMillis()) {
    val remoteList = (doc[catCol] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }.orEmpty()
    val remoteTs = SarimWebSchema.parseEpoch(doc["${catCol}_timestamp"])
    val localKv = safeReader.getKvByUserKeyOnce(catCol)
    val localList = JsonCodecs.decodeStrings(localKv?.value ?: "[]")
    val merged = (localList + remoteList).distinctBy { it.lowercase() }
    if (merged.size != localList.size || dao.getKvMetaByUserKey("${catCol}_dirty") == null) {
      dao.upsertKv(
        KvStoreEntity(
          fullKey = catCol,
          userKey = catCol,
          collection = catCol,
          rowType = "settings",
          value = JsonCodecs.encodeStrings(merged),
          ts = maxOf(remoteTs, localKv?.ts ?: 0L),
          createdAt = localKv?.createdAt ?: now,
        )
      )
    }
  }

  suspend fun applyRemoteDeletionsSnapshot(
    upserts: List<Pair<String, Map<String, Any>>>,
    removedIds: List<String>,
    now: Long = System.currentTimeMillis(),
  ) {
    upserts.forEach { (id, raw) ->
      SarimWebPullMappers.mapToDeletionTombstone(id, raw)?.let { d ->
        if (d.expiresAt > now) {
          dao.upsertDeletion(d)
          hardDeleteNonPendingIfNeeded(d.collection, d.recordId)
        }
      }
    }
    removedIds.forEach { id ->
      dao.deleteDeletion(id)
    }
  }

  private suspend fun hardDeleteNonPendingIfNeeded(col: String, recordId: String) {
    when (col) {
      SarimWebSchema.COL_SALES, SarimWebSchema.COL_REP ->
        dao.getAllSales().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.softDeleteSale(recordId, System.currentTimeMillis())
          dao.upsertSale(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
      SarimWebSchema.COL_PRODUCTION ->
        dao.getAllProductionEntries().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.upsertProductionEntry(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
      SarimWebSchema.COL_CUSTOMERS, SarimWebSchema.COL_CLIENTS ->
        dao.getAllCustomers().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.upsertCustomer(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
      SarimWebSchema.COL_INVENTORY -> {
        dao.getAllRawMaterials().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.upsertRawMaterial(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
        dao.getAllPurchases().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.upsertPurchase(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
      }
      SarimWebSchema.COL_FACTORY ->
        dao.getAllFactoryBatches().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.upsertFactoryBatch(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
      SarimWebSchema.COL_ENTITIES ->
        dao.getAllPaymentEntities().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.upsertPaymentEntity(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
      SarimWebSchema.COL_TRANSACTIONS ->
        dao.getAllPaymentTransactions().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.upsertPaymentTransaction(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
      SarimWebSchema.COL_EXPENSES ->
        dao.getAllExpenses().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.upsertExpense(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
      SarimWebSchema.COL_RETURNS ->
        dao.getAllReturns().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.upsertReturnLog(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
      SarimWebSchema.COL_CALCULATOR ->
        dao.getAllSettlements().firstOrNull { it.id == recordId && !it.syncPending && !it.isDeleted }?.let {
          dao.upsertSettlement(it.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), syncPending = false))
        }
    }
  }

  suspend fun applyRemotePhotosSnapshot(upserts: List<Pair<String, Map<String, Any>>>) {
    if (upserts.isEmpty()) return
    val localByDocId = safeReader.getAllPhotosOnce().associateBy { it.docId }
    upserts.forEach { (docId, m) ->
      if (SarimWebSchema.bool(m["_placeholder"])) return@forEach
      val pKey = SarimWebSchema.str(m["key"]).ifBlank { docId }
      val deleted = SarimWebSchema.bool(m["deleted"])
      val updatedAt = SarimWebSchema.parseEpoch(m["updatedAt"])
      val local = localByDocId[docId]
      if (local == null || (!local.syncPending && updatedAt >= local.updatedAt)) {
        dao.upsertPhoto(
          PhotoRecordEntity(
            photoKey = pKey,
            docId = docId,
            data = if (deleted) null else m["data"]?.toString(),
            deleted = deleted,
            updatedAt = updatedAt,
            syncPending = false,
          )
        )
      }
    }
  }

  suspend fun applyRemoteRecordSnapshot(
    collection: String,
    docs: List<Pair<String, Map<String, Any>>>,
    removedIds: List<String>,
    isFullPull: Boolean = false,
  ) {
    uuidRegistry.ensureLoaded()
    val tombstones = getTombstoneKeySet()
    val remoteIds = docs.map { it.first }.toSet()
    val downloadedIds = mutableListOf<String>()

    when (collection) {
      SarimWebSchema.COL_INVENTORY -> {
        val localMats = dao.getAllRawMaterials().associateBy { it.id }
        val localLots = dao.getAllPurchases().associateBy { it.id }
        docs.forEach { (id, m) ->
          if (!isTombstoned(tombstones, collection, id)) {
            if (SarimWebSchema.bool(m["isPurchaseLot"])) {
              SarimWebPullMappers.mapToPurchaseLot(id, m)?.let { remote ->
                val local = localLots[id]
                if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                  val localSnap = local?.let {
                    ConflictRecordSnapshot(it.id, it.updatedAt, it.dateEpoch, it.createdAt, it.syncPending, supplierId = it.supplierId, paymentStatus = it.paymentStatus)
                  }
                  val remoteSnap = ConflictRecordSnapshot(remote.id, remote.updatedAt, remote.dateEpoch, remote.createdAt, false, supplierId = remote.supplierId, paymentStatus = remote.paymentStatus)
                  if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                    dao.upsertPurchase(remote)
                    downloadedIds += id
                  }
                }
              }
            } else {
              SarimWebPullMappers.mapToMaterial(id, m)?.let { remote ->
                val local = localMats[id]
                if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                  val localSnap = local?.let {
                    ConflictRecordSnapshot(it.id, it.updatedAt, 0L, it.createdAt, it.syncPending, supplierId = it.supplierId, paymentStatus = it.paymentStatus)
                  }
                  val remoteSnap = ConflictRecordSnapshot(remote.id, remote.updatedAt, 0L, remote.createdAt, false, supplierId = remote.supplierId, paymentStatus = remote.paymentStatus)
                  if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                    dao.upsertRawMaterial(remote)
                    downloadedIds += id
                  }
                }
              }
            }
          }
        }
        val toRemove = if (isFullPull) localLots.values.filter { !it.syncPending && !it.isDeleted && it.id !in remoteIds }.map { it.id } else removedIds
        toRemove.forEach { id ->
          localLots[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeletePurchase(id) }
        }
        removedIds.forEach { id ->
          localMats[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeleteRawMaterial(id) }
        }
      }

      SarimWebSchema.COL_FACTORY -> {
        val localMap = dao.getAllFactoryBatches().associateBy { it.id }
        docs.forEach { (id, m) ->
          if (!isTombstoned(tombstones, collection, id)) {
            SarimWebPullMappers.mapToFactoryBatch(id, m)?.let { remote ->
              val local = localMap[id]
              if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                val localSnap = local?.let { ConflictRecordSnapshot(it.id, it.updatedAt, it.dateEpoch, it.createdAt, it.syncPending) }
                val remoteSnap = ConflictRecordSnapshot(remote.id, remote.updatedAt, remote.dateEpoch, remote.createdAt, false)
                if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                  dao.upsertFactoryBatch(remote)
                  downloadedIds += id
                }
              }
            }
          }
        }
        val toRemove = if (isFullPull) localMap.values.filter { !it.syncPending && !it.isDeleted && it.id !in remoteIds }.map { it.id } else removedIds
        toRemove.forEach { id -> localMap[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeleteFactoryBatch(id) } }
      }

      SarimWebSchema.COL_PRODUCTION -> {
        val localProd = dao.getAllProductionEntries().associateBy { it.id }
        val localTransfers = dao.getAllStockTransfers().associateBy { it.id }
        docs.forEach { (id, m) ->
          if (!isTombstoned(tombstones, collection, id)) {
            SarimWebSchema.mapToProduction(id, m)?.let { remote ->
              val local = localProd[id]
              if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                val localSnap = local?.let {
                  ConflictRecordSnapshot(
                    id = it.id, updatedAt = it.updatedAt, timestamp = it.dateEpoch, createdAt = it.createdAt, syncPending = it.syncPending,
                    isReturn = it.isReturn, formulaUnitsUsed = it.formulaUnitsUsed, totalFormulaCost = it.totalFormulaCost,
                  )
                }
                val remoteSnap = ConflictRecordSnapshot(
                  id = remote.id, updatedAt = remote.updatedAt, timestamp = remote.dateEpoch, createdAt = remote.createdAt, syncPending = false,
                  isReturn = remote.isReturn, formulaUnitsUsed = remote.formulaUnitsUsed, totalFormulaCost = remote.totalFormulaCost,
                )
                if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                  dao.upsertProductionEntry(remote)
                  downloadedIds += id
                }
              }
            }
          }
        }
        SarimWebPullMappers.reconstructStockTransfers(docs).forEach { rt ->
          val local = localTransfers[rt.id]
          if (!isTombstoned(tombstones, collection, rt.id) &&
            !isTombstoned(tombstones, collection, rt.outDocId) &&
            !isTombstoned(tombstones, collection, rt.inDocId) &&
            uuidRegistry.shouldApplyCloudForRecord(collection, rt.outDocId, local != null, isFullPull)
          ) {
            val resolved = if (local != null && remoteIds.none { it == rt.inDocId }) {
              rt.copy(inDocId = local.inDocId)
            } else rt
            val apply = local == null || (
              !local.syncPending &&
                SarimUuid.compareRecordVersions(
                  resolved.id, resolved.updatedAt, resolved.dateEpoch, resolved.createdAt,
                  local.id, local.updatedAt, local.dateEpoch, local.createdAt,
                ) >= 0
              )
            if (apply) {
              dao.upsertStockTransfer(resolved)
              downloadedIds += resolved.outDocId
              if (resolved.inDocId in remoteIds) downloadedIds += resolved.inDocId
            }
          }
        }
        val toRemove = if (isFullPull) localProd.values.filter { !it.syncPending && !it.isDeleted && it.id !in remoteIds }.map { it.id } else removedIds
        toRemove.forEach { id ->
          localProd[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeleteProductionEntry(id) }
          localTransfers.values.firstOrNull { !it.syncPending && !it.isDeleted && (it.outDocId == id || it.inDocId == id) }?.let {
            dao.hardDeleteStockTransfer(it.id)
          }
        }
      }

      SarimWebSchema.COL_CUSTOMERS, SarimWebSchema.COL_CLIENTS -> {
        val isRep = collection == SarimWebSchema.COL_CLIENTS
        val localMap = dao.getAllCustomers().filter { it.isRepCustomer == isRep }.associateBy { it.id }
        docs.forEach { (id, m) ->
          if (!isTombstoned(tombstones, collection, id)) {
            SarimWebSchema.mapToCustomer(id, m, isRepCustomer = isRep)?.let { remote ->
              val local = localMap[id]
              if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                val localSnap = local?.let { ConflictRecordSnapshot(it.id, it.updatedAt, 0L, it.createdAt, it.syncPending) }
                val remoteSnap = ConflictRecordSnapshot(remote.id, remote.updatedAt, 0L, remote.createdAt, false)
                if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                  dao.upsertCustomer(remote)
                  downloadedIds += id
                }
              }
            }
          }
        }
        removedIds.forEach { id ->
          localMap[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeleteCustomer(id) }
        }
      }

      SarimWebSchema.COL_SALES, SarimWebSchema.COL_REP -> {
        val isRep = collection == SarimWebSchema.COL_REP
        val localMap = dao.getAllSales().filter { it.isRepSale == isRep }.associateBy { it.id }
        docs.forEach { (id, m) ->
          if (!isTombstoned(tombstones, collection, id)) {
            SarimWebSchema.mapToSale(id, m, isRep = isRep)?.let { remote ->
              val local = localMap[id]
              if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                val localSnap = local?.let { ConflictRecordSnapshot(it.id, it.updatedAt, it.dateEpoch, it.createdAt, it.syncPending) }
                val remoteSnap = ConflictRecordSnapshot(remote.id, remote.updatedAt, remote.dateEpoch, remote.createdAt, false)
                if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                  dao.upsertSale(remote)
                  downloadedIds += id
                }
              }
            }
          }
        }
        val toRemove = if (isFullPull) localMap.values.filter { !it.syncPending && !it.isDeleted && it.id !in remoteIds }.map { it.id } else removedIds
        toRemove.forEach { id -> localMap[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeleteSale(id) } }
      }

      SarimWebSchema.COL_ENTITIES -> {
        val localMap = dao.getAllPaymentEntities().associateBy { it.id }
        docs.forEach { (id, m) ->
          if (!isTombstoned(tombstones, collection, id)) {
            SarimWebSchema.mapToEntity(id, m)?.let { remote ->
              val local = localMap[id]
              if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                val localSnap = local?.let { ConflictRecordSnapshot(it.id, it.updatedAt, 0L, it.createdAt, it.syncPending) }
                val remoteSnap = ConflictRecordSnapshot(remote.id, remote.updatedAt, 0L, remote.createdAt, false)
                if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                  dao.upsertPaymentEntity(remote)
                  downloadedIds += id
                }
              }
            }
          }
        }
        removedIds.forEach { id -> localMap[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeletePaymentEntity(id) } }
      }

      SarimWebSchema.COL_TRANSACTIONS -> {
        val localTx = dao.getAllPaymentTransactions().associateBy { it.id }
        val localTransfers = dao.getAllPaymentTransfers().associateBy { it.id }
        docs.forEach { (id, m) ->
          if (!isTombstoned(tombstones, collection, id)) {
            SarimWebSchema.mapToTransaction(id, m)?.let { remote ->
              val local = localTx[id]
              if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                val localSnap = local?.let { ConflictRecordSnapshot(it.id, it.updatedAt, it.dateEpoch, it.createdAt, it.syncPending) }
                val remoteSnap = ConflictRecordSnapshot(remote.id, remote.updatedAt, remote.dateEpoch, remote.createdAt, false)
                if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                  dao.upsertPaymentTransaction(remote)
                  downloadedIds += id
                }
              }
            }
          }
        }
        SarimWebPullMappers.reconstructPaymentTransfers(docs).forEach { rt ->
          val local = localTransfers[rt.id]
          if (!isTombstoned(tombstones, collection, rt.id) &&
            !isTombstoned(tombstones, collection, rt.outDocId) &&
            !isTombstoned(tombstones, collection, rt.inDocId) &&
            uuidRegistry.shouldApplyCloudForRecord(collection, rt.outDocId, local != null, isFullPull)
          ) {
            val resolved = if (local != null && remoteIds.none { it == rt.inDocId }) {
              rt.copy(inDocId = local.inDocId)
            } else rt
            val apply = local == null || (
              !local.syncPending &&
                SarimUuid.compareRecordVersions(
                  resolved.id, resolved.updatedAt, resolved.dateEpoch, resolved.createdAt,
                  local.id, local.updatedAt, local.dateEpoch, local.createdAt,
                ) >= 0
              )
            if (apply) {
              dao.upsertPaymentTransfer(resolved)
              downloadedIds += resolved.outDocId
              if (resolved.inDocId in remoteIds) downloadedIds += resolved.inDocId
            }
          }
        }
        val toRemove = if (isFullPull) localTx.values.filter { !it.syncPending && !it.isDeleted && it.id !in remoteIds }.map { it.id } else removedIds
        toRemove.forEach { id ->
          localTx[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeletePaymentTransaction(id) }
          localTransfers.values.firstOrNull { !it.syncPending && !it.isDeleted && (it.outDocId == id || it.inDocId == id) }?.let {
            dao.hardDeletePaymentTransfer(it.id)
          }
        }
      }

      SarimWebSchema.COL_EXPENSES -> {
        val localMap = dao.getAllExpenses().associateBy { it.id }
        docs.forEach { (id, m) ->
          if (!isTombstoned(tombstones, collection, id)) {
            SarimWebSchema.mapToExpense(id, m)?.let { remote ->
              val local = localMap[id]
              if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                val localSnap = local?.let { ConflictRecordSnapshot(it.id, it.updatedAt, it.dateEpoch, it.createdAt, it.syncPending) }
                val remoteSnap = ConflictRecordSnapshot(remote.id, remote.updatedAt, remote.dateEpoch, remote.createdAt, false)
                if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                  dao.upsertExpense(remote)
                  downloadedIds += id
                }
              }
            }
          }
        }
        val toRemove = if (isFullPull) localMap.values.filter { !it.syncPending && !it.isDeleted && it.id !in remoteIds }.map { it.id } else removedIds
        toRemove.forEach { id -> localMap[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeleteExpense(id) } }
      }

      SarimWebSchema.COL_RETURNS -> {
        val localMap = dao.getAllReturns().associateBy { it.id }
        docs.forEach { (id, m) ->
          if (!isTombstoned(tombstones, collection, id)) {
            SarimWebPullMappers.mapToReturnLog(id, m)?.let { remote ->
              val local = localMap[id]
              if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                val localSnap = local?.let { ConflictRecordSnapshot(it.id, it.updatedAt, it.dateEpoch, it.createdAt, it.syncPending) }
                val remoteSnap = ConflictRecordSnapshot(remote.id, remote.updatedAt, remote.dateEpoch, remote.createdAt, false)
                if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                  dao.upsertReturnLog(remote)
                  downloadedIds += id
                }
              }
            }
          }
        }
        val toRemove = if (isFullPull) localMap.values.filter { !it.syncPending && !it.isDeleted && it.id !in remoteIds }.map { it.id } else removedIds
        toRemove.forEach { id -> localMap[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeleteReturnLog(id) } }
      }

      SarimWebSchema.COL_CALCULATOR -> {
        val localMap = dao.getAllSettlements().associateBy { it.id }
        docs.forEach { (id, m) ->
          if (!isTombstoned(tombstones, collection, id)) {
            SarimWebPullMappers.mapToSettlement(id, m)?.let { remote ->
              val local = localMap[id]
              if (uuidRegistry.shouldApplyCloudForRecord(collection, id, local != null, isFullPull)) {
                val localSnap = local?.let {
                  ConflictRecordSnapshot(
                    id = it.id, updatedAt = it.updatedAt, timestamp = it.dateEpoch, createdAt = it.createdAt, syncPending = it.syncPending,
                    totalSoldKg = it.totalSoldKg, grossRevenue = it.grossRevenue,
                  )
                }
                val remoteSnap = ConflictRecordSnapshot(
                  id = remote.id, updatedAt = remote.updatedAt, timestamp = remote.dateEpoch, createdAt = remote.createdAt, syncPending = false,
                  totalSoldKg = remote.totalSoldKg, grossRevenue = remote.grossRevenue,
                )
                if (!SarimUuid.shouldLocalWinConflict(collection, localSnap, remoteSnap)) {
                  dao.upsertSettlement(remote)
                  downloadedIds += id
                }
              }
            }
          }
        }
        val toRemove = if (isFullPull) localMap.values.filter { !it.syncPending && !it.isDeleted && it.id !in remoteIds }.map { it.id } else removedIds
        toRemove.forEach { id -> localMap[id]?.let { if (!it.syncPending && !it.isDeleted) dao.hardDeleteSettlement(id) } }
      }
    }

    if (downloadedIds.isNotEmpty()) {
      uuidRegistry.markDownloadedBatch(collection, downloadedIds)
    }
    uuidRegistry.updateSyncStats(collection, docs.size, 0)
  }

  suspend fun wipeAllLocalWorkspaceData() {
    dao.clearSales()
    dao.clearProductionEntries()
    dao.clearPaymentTransactions()
    dao.clearExpenses()
    dao.clearReturns()
    dao.clearDailyLedger()
    dao.clearSettlements()
    dao.clearStockTransfers()
    dao.clearPaymentTransfers()
    dao.clearFactoryBatches()
    dao.clearPurchases()
    dao.clearCustomers()
    dao.clearPaymentEntities()
    dao.clearRawMaterials()
    dao.clearFormulas()
    dao.clearStores()
    dao.clearMixCalcs()
    dao.clearApprovals()
    dao.clearAppAccounts()
    dao.clearAllDeletions()
    dao.clearPhotos()
    dao.clearOutbox()
    dao.clearKvStore()
    uuidRegistry.clearAll()
  }


  // =====================================================================
  // Recycle Bin (soft-deleted records within 90 days)
  // =====================================================================

  suspend fun getRecycleBinItems(): List<RecycleBinRecord> {
    val cutoff = System.currentTimeMillis() - SarimWebSchema.TOMBSTONE_EXPIRY_MS
    val list = mutableListOf<RecycleBinRecord>()
    dao.getDeletedSalesSince(cutoff).forEach {
      list += RecycleBinRecord(
        id = it.id,
        tabCategory = if (it.isRepSale) "Rep Tab" else "Sales Tab",
        title = "${it.mode}: ${it.customerName}",
        subtitle = "${it.quantityKg} kg · ${it.paymentType}",
        amountText = "Rs ${it.totalValue}",
        deletedAt = it.deletedAt ?: it.updatedAt,
        entityType = "SALE",
      )
    }
    dao.getDeletedProductionSince(cutoff).forEach {
      list += RecycleBinRecord(
        id = it.id,
        tabCategory = "Manufacturing Tab",
        title = "Production: ${it.storeName}",
        subtitle = "${it.netWeightKg} kg · ${it.formulaName}",
        amountText = "Rs ${it.totalValue}",
        deletedAt = it.deletedAt ?: it.updatedAt,
        entityType = "PRODUCTION",
      )
    }
    dao.getDeletedSettlementsSince(cutoff).forEach {
      list += RecycleBinRecord(
        id = it.id,
        tabCategory = "Calculator Tab",
        title = "Settlement: ${it.repName}",
        subtitle = "${it.status} · ${it.netSoldKg} kg",
        amountText = "Rs ${it.receivedCash}",
        deletedAt = it.deletedAt ?: it.updatedAt,
        entityType = "SETTLEMENT",
      )
    }
    dao.getDeletedFactoryBatchesSince(cutoff).forEach {
      list += RecycleBinRecord(
        id = it.id,
        tabCategory = "Factory Tab",
        title = "Factory Batch: ${it.formulaName}",
        subtitle = "${it.unitsProduced} units",
        amountText = "Rs ${it.totalCost}",
        deletedAt = it.deletedAt ?: it.updatedAt,
        entityType = "BATCH",
      )
    }
    val deletedPaymentTxs = dao.getDeletedPaymentTransactionsSince(cutoff)
    deletedPaymentTxs.forEach {
      list += RecycleBinRecord(
        id = it.id,
        tabCategory = "Payments Tab",
        title = "${it.type}: ${it.entityOrExpenseName}",
        subtitle = it.description.ifBlank { "Payment entry" },
        amountText = "Rs ${it.amount}",
        deletedAt = it.deletedAt ?: it.updatedAt,
        entityType = "PAYMENT",
      )
    }
    dao.getDeletedExpensesSince(cutoff).forEach { exp ->
      val alreadyShownAsPayment = deletedPaymentTxs.any { it.expenseId == exp.id || it.id == exp.id }
      if (!alreadyShownAsPayment) {
        list += RecycleBinRecord(
          id = exp.id,
          tabCategory = "Payments Tab",
          title = "Expense: ${exp.name}",
          subtitle = exp.description.ifBlank { exp.category },
          amountText = "Rs ${exp.amount}",
          deletedAt = exp.deletedAt ?: exp.updatedAt,
          entityType = "EXPENSE",
        )
      }
    }
    dao.getDeletedReturnsSince(cutoff).forEach { ret ->
      list += RecycleBinRecord(
        id = ret.id,
        tabCategory = "Calculator Tab",
        title = "Return: ${ret.seller}",
        subtitle = "${ret.quantity} kg -> ${ret.store.uppercase()}",
        amountText = "${ret.quantity} kg",
        deletedAt = ret.deletedAt ?: ret.updatedAt,
        entityType = "RETURN",
      )
    }
    dao.getDeletedDailyLedgerSince(cutoff).forEach {
      list += RecycleBinRecord(
        id = it.id,
        tabCategory = "Daily Ledger",
        title = "Ledger ${it.type}: ${it.mode}",
        subtitle = it.party.ifBlank { it.note },
        amountText = "Rs ${it.amount}",
        deletedAt = it.deletedAt ?: it.updatedAt,
        entityType = "LEDGER",
      )
    }
    dao.getDeletedFormulasSince(cutoff).forEach {
      list += RecycleBinRecord(
        id = it.id,
        tabCategory = "Formula Store",
        title = "Formula: ${it.name}",
        subtitle = "${it.unitsAvailable} units",
        amountText = "Rs ${it.additionalCostPerUnit}",
        deletedAt = it.deletedAt ?: it.updatedAt,
        entityType = "FORMULA",
      )
    }
    dao.getDeletedRawMaterialsSince(cutoff).forEach { m ->
      list += RecycleBinRecord(
        id = m.id,
        tabCategory = "Factory Tab",
        title = "Material: ${m.name}",
        subtitle = "${m.stockKg} kg",
        amountText = "Rs ${m.avgCostPerKg}/kg",
        deletedAt = m.deletedAt ?: m.updatedAt,
        entityType = "MATERIAL",
      )
    }
    dao.getDeletedPurchasesSince(cutoff).forEach { p ->
      list += RecycleBinRecord(
        id = p.id,
        tabCategory = "Factory Tab",
        title = "Purchase: ${p.materialName}",
        subtitle = "${p.unitsBought} ${p.unitName} · ${p.totalKg} kg",
        amountText = "Rs ${p.totalAmount}",
        deletedAt = p.deletedAt ?: p.updatedAt,
        entityType = "PURCHASE_LOT",
      )
    }
    dao.getDeletedCustomersSince(cutoff).forEach { c ->
      list += RecycleBinRecord(
        id = c.id,
        tabCategory = if (c.isRepCustomer) "Rep Tab" else "Sales Tab",
        title = "Customer: ${c.name}",
        subtitle = c.phone.ifBlank { "Credit: Rs ${c.currentCredit}" },
        amountText = "Rs ${c.currentCredit}",
        deletedAt = c.deletedAt ?: c.updatedAt,
        entityType = "CUSTOMER",
      )
    }
    dao.getDeletedPaymentEntitiesSince(cutoff).forEach { e ->
      list += RecycleBinRecord(
        id = e.id,
        tabCategory = "Payments Tab",
        title = "Entity: ${e.name}",
        subtitle = e.phone.ifBlank { e.type },
        amountText = "Rs ${e.currentBalance}",
        deletedAt = e.deletedAt ?: e.updatedAt,
        entityType = "ENTITY",
      )
    }
    dao.getDeletedStockTransfersSince(cutoff).forEach { t ->
      list += RecycleBinRecord(
        id = t.id,
        tabCategory = "Manufacturing Tab",
        title = "Stock Transfer: ${t.fromStoreName} -> ${t.toStoreName}",
        subtitle = t.note.ifBlank { "Inter-store transfer" },
        amountText = "${t.quantityKg} kg",
        deletedAt = t.deletedAt ?: t.updatedAt,
        entityType = "STOCK_TRANSFER",
      )
    }
    dao.getDeletedPaymentTransfersSince(cutoff).forEach { t ->
      list += RecycleBinRecord(
        id = t.id,
        tabCategory = "Payments Tab",
        title = "Payment Transfer: ${t.fromEntityName} -> ${t.toEntityName}",
        subtitle = t.note.ifBlank { "Balance transfer" },
        amountText = "Rs ${t.amount}",
        deletedAt = t.deletedAt ?: t.updatedAt,
        entityType = "PAYMENT_TRANSFER",
      )
    }
    return list.sortedByDescending { it.deletedAt }
  }

  private suspend fun removeTombstoneAndEnqueueCloudDelete(docId: String, now: Long) =
    recycleRestoreEngine.removeTombstoneAndEnqueueCloudDelete(docId, now)

  suspend fun restoreRecycleItem(item: RecycleBinRecord) =
    recycleRestoreEngine.restoreRecycleItem(item)

  suspend fun deleteForeverRecycleItem(item: RecycleBinRecord) {
    val now = System.currentTimeMillis()
    when (item.entityType) {
      "SALE" -> dao.hardDeleteSale(item.id)
      "PRODUCTION" -> dao.hardDeleteProductionEntry(item.id)
      "SETTLEMENT" -> dao.hardDeleteSettlement(item.id)
      "BATCH" -> dao.hardDeleteFactoryBatch(item.id)
      "PAYMENT" -> {
        dao.getAllPaymentTransactions().firstOrNull { it.id == item.id }?.expenseId?.let { expId ->
          dao.hardDeleteExpense(expId)
          removeTombstoneAndEnqueueCloudDelete(expId, now)
        }
        dao.hardDeletePaymentTransaction(item.id)
      }
      "EXPENSE" -> dao.hardDeleteExpense(item.id)
      "RETURN" -> dao.hardDeleteReturnLog(item.id)
      "LEDGER" -> dao.hardDeleteLedgerEntry(item.id)
      "FORMULA" -> dao.hardDeleteFormula(item.id)
      "MATERIAL" -> dao.hardDeleteRawMaterial(item.id)
      "PURCHASE_LOT" -> dao.hardDeletePurchase(item.id)
      "CUSTOMER" -> dao.hardDeleteCustomer(item.id)
      "ENTITY" -> dao.hardDeletePaymentEntity(item.id)
      "STOCK_TRANSFER" -> {
        dao.getAllStockTransfers().firstOrNull { it.id == item.id }?.let { tr ->
          removeTombstoneAndEnqueueCloudDelete(tr.outDocId, now)
          removeTombstoneAndEnqueueCloudDelete(tr.inDocId, now)
        }
        dao.hardDeleteStockTransfer(item.id)
      }
      "PAYMENT_TRANSFER" -> {
        dao.getAllPaymentTransfers().firstOrNull { it.id == item.id }?.let { tr ->
          removeTombstoneAndEnqueueCloudDelete(tr.outDocId, now)
          removeTombstoneAndEnqueueCloudDelete(tr.inDocId, now)
        }
        dao.hardDeletePaymentTransfer(item.id)
      }
    }
    removeTombstoneAndEnqueueCloudDelete(item.id, now)
  }

  suspend fun emptyRecycleBin() {
    getRecycleBinItems().forEach { deleteForeverRecycleItem(it) }
  }

  private suspend fun recordDedupDeletion(collection: String, docId: String, now: Long) {
    dao.upsertDeletion(
      DeletionTombstoneEntity(
        id = docId,
        recordId = docId,
        collection = collection,
        recordType = collection,
        deletedAt = now,
        expiresAt = now + SarimWebSchema.TOMBSTONE_EXPIRY_MS,
        syncedToCloud = false,
      )
    )
  }

  suspend fun deduplicateAllData(): Int {
    val now = System.currentTimeMillis()
    var removed = 0

    // Deduplicate active customers by normalized (name, isRepCustomer)
    val dupCusts = dao.getAllCustomers().filter { !it.isDeleted }
      .groupBy { "${it.name.trim().lowercase()}|${it.isRepCustomer}" }
    dupCusts.values.filter { it.size > 1 }.forEach { group ->
      val sorted = group.sortedByDescending { it.updatedAt }
      sorted.drop(1).forEach { dup ->
        dao.softDeleteCustomer(dup.id, now)
        recordDedupDeletion(SarimWebSchema.COL_CUSTOMERS, dup.id, now)
        removed++
      }
    }

    // Deduplicate active payment entities by normalized name
    val dupEntities = dao.getAllPaymentEntities().filter { !it.isDeleted }
      .groupBy { it.name.trim().lowercase() }
    dupEntities.values.filter { it.size > 1 }.forEach { group ->
      val sorted = group.sortedByDescending { it.updatedAt }
      sorted.drop(1).forEach { dup ->
        dao.softDeletePaymentEntity(dup.id, now)
        recordDedupDeletion(SarimWebSchema.COL_ENTITIES, dup.id, now)
        removed++
      }
    }

    // Deduplicate active raw materials by normalized name
    val dupMats = dao.getAllRawMaterials().filter { !it.isDeleted }
      .groupBy { it.name.trim().lowercase() }
    dupMats.values.filter { it.size > 1 }.forEach { group ->
      val sorted = group.sortedByDescending { it.updatedAt }
      sorted.drop(1).forEach { dup ->
        dao.softDeleteRawMaterial(dup.id, now)
        recordDedupDeletion(SarimWebSchema.COL_INVENTORY, dup.id, now)
        removed++
      }
    }

    // Deduplicate active formulas by normalized name
    val dupFormulas = dao.getAllFormulas().filter { !it.isDeleted }
      .groupBy { it.name.trim().lowercase() }
    dupFormulas.values.filter { it.size > 1 }.forEach { group ->
      val sorted = group.sortedByDescending { it.updatedAt }
      sorted.drop(1).forEach { dup ->
        dao.softDeleteFormula(dup.id, now)
        removed++
      }
    }

    return removed
  }

  // =====================================================================
  // JSON Backup Export & Restore (Web + Android compatible schema)
  // =====================================================================

  suspend fun exportBackupJson(): String {
    val mats = dao.getAllRawMaterials()
    val purchases = dao.getAllPurchases()
    val mixCalcs = dao.getAllMixCalcs()
    val calcsByFormula = mixCalcs.filter { !it.isDeleted }.groupBy { it.formulaId }
    val root = JSONObject()
    root.put("version", 2)
    root.put("exportedAt", System.currentTimeMillis())

    root.put(SarimWebSchema.COL_STORES, JSONArray().apply {
      dao.getAllStores().filter { !it.isDeleted }.forEach { s ->
        put(JSONObject().apply {
          put("key", s.storeKey); put("storeKey", s.storeKey)
          put("name", s.name); put("formulaId", s.formulaId)
          put("salePrice", s.salePricePerKg); put("salePricePerKg", s.salePricePerKg)
        })
      }
    })

    root.put(SarimWebSchema.COL_INVENTORY, JSONArray().apply {
      mats.filter { !it.isDeleted }.forEach { m -> put(JSONObject(SarimWebSchema.materialToMap(m))) }
      purchases.filter { !it.isDeleted }.forEach { p -> put(JSONObject(SarimWebSchema.purchaseLotToMap(p))) }
    })

    // Legacy v1 compatibility key
    root.put("materials", JSONArray().apply {
      mats.filter { !it.isDeleted }.forEach { m ->
        put(JSONObject().apply {
          put("id", m.id); put("name", m.name); put("unitName", m.unitName)
          put("conversionFactorKg", m.conversionFactorKg); put("stockKg", m.stockKg)
          put("collectedKg", m.collectedKg); put("avgCostPerKg", m.avgCostPerKg)
          put("pendingSupplierAmount", m.pendingSupplierAmount)
        })
      }
    })

    // Same shape the web writes: formulas[] with ingredients/savedCalcs plus the defaults/costs/adjustment/tracking maps.
    val formulaCfg = SarimWebSchema.formulasConfigDocPayload(
      formulas = dao.getAllFormulas().filter { !it.isDeleted },
      savedCalcs = mixCalcs,
      materials = mats.associateBy { it.id },
      ts = System.currentTimeMillis(),
    )
    root.put(SarimWebSchema.COL_FORMULAS, JSONArray().apply {
      (formulaCfg["formulas"] as? List<*>).orEmpty().forEach { f -> (f as? Map<*, *>)?.let { put(JSONObject(it)) } }
    })
    listOf("defaults", "costs", "adjustment", "tracking").forEach { k ->
      root.put(k, JSONObject((formulaCfg[k] as? Map<*, *>) ?: emptyMap<String, Any?>()))
    }

    root.put(SarimWebSchema.COL_FACTORY, JSONArray().apply {
      dao.getAllFactoryBatches().filter { !it.isDeleted }.forEach { put(JSONObject(SarimWebSchema.factoryBatchToMap(it))) }
    })

    root.put(SarimWebSchema.COL_PRODUCTION, JSONArray().apply {
      dao.getAllProductionEntries().filter { !it.isDeleted }.forEach { put(JSONObject(SarimWebSchema.productionToMap(it))) }
      dao.getAllStockTransfers().filter { !it.isDeleted }.forEach { tr ->
        val (o, i) = SarimWebSchema.stockTransferToOutInMaps(tr)
        put(JSONObject(o.second)); put(JSONObject(i.second))
      }
    })

    val allCust = dao.getAllCustomers().filter { !it.isDeleted }
    root.put(SarimWebSchema.COL_CUSTOMERS, JSONArray().apply {
      allCust.filter { !it.isRepCustomer }.forEach { put(JSONObject(SarimWebSchema.customerToMap(it))) }
    })
    root.put(SarimWebSchema.COL_CLIENTS, JSONArray().apply {
      allCust.filter { it.isRepCustomer }.forEach { put(JSONObject(SarimWebSchema.customerToMap(it))) }
    })

    val allSales = dao.getAllSales().filter { !it.isDeleted }
    root.put(SarimWebSchema.COL_SALES, JSONArray().apply {
      allSales.filter { !it.isRepSale }.forEach { put(JSONObject(SarimWebSchema.saleToMap(it))) }
    })
    root.put(SarimWebSchema.COL_REP, JSONArray().apply {
      allSales.filter { it.isRepSale }.forEach { put(JSONObject(SarimWebSchema.saleToMap(it))) }
    })

    root.put(SarimWebSchema.COL_ENTITIES, JSONArray().apply {
      dao.getAllPaymentEntities().filter { !it.isDeleted }.forEach { put(JSONObject(SarimWebSchema.entityToMap(it))) }
    })
    root.put(SarimWebSchema.COL_TRANSACTIONS, JSONArray().apply {
      dao.getAllPaymentTransactions().filter { !it.isDeleted }.forEach { put(JSONObject(SarimWebSchema.transactionToMap(it))) }
      dao.getAllPaymentTransfers().filter { !it.isDeleted }.forEach { tr ->
        val (o, i) = SarimWebSchema.paymentTransferToOutInMaps(tr)
        put(JSONObject(o.second)); put(JSONObject(i.second))
      }
    })
    root.put(SarimWebSchema.COL_EXPENSES, JSONArray().apply {
      dao.getAllExpenses().filter { !it.isDeleted }.forEach { put(JSONObject(SarimWebSchema.expenseToMap(it))) }
    })
    root.put(SarimWebSchema.COL_RETURNS, JSONArray().apply {
      dao.getAllReturns().filter { !it.isDeleted }.forEach { put(JSONObject(SarimWebSchema.returnLogToMap(it))) }
    })
    root.put(SarimWebSchema.COL_CALCULATOR, JSONArray().apply {
      dao.getAllSettlements().filter { !it.isDeleted }.forEach { put(JSONObject(SarimWebSchema.settlementToMap(it))) }
    })

    val ledgerRows = dao.getAllDailyLedger().filter { !it.isDeleted }
    root.put(SarimWebSchema.COL_DAILY_LEDGER, JSONArray().apply {
      ledgerRows.forEach { l ->
        put(JSONObject().apply {
          put("id", l.id); put("date", l.dateStr); put("dateEpoch", l.dateEpoch)
          put("type", l.type); put("amount", l.amount); put("mode", l.mode)
          put("toMode", l.toMode); put("party", l.party); put("ref", l.refNo)
          put("note", l.note); put("category", l.category); put("scope", l.scope)
          put("sourceType", l.sourceType); put("sourceId", l.sourceId)
          JsonCodecs.decodeLedgerLink(l.linkJson)?.takeIf { it.isNotEmpty() }?.let { put("link", JSONObject(it)) }
          put("timestamp", l.dateEpoch); put("createdAt", l.createdAt); put("updatedAt", l.updatedAt)
        })
      }
    })
    // Legacy v1 compatibility key
    root.put("ledger", root.getJSONArray(SarimWebSchema.COL_DAILY_LEDGER))

    // Web `collectAuxBackupFields`: category lists, rep names and the (device-local) photo maps.
    for (k in BACKUP_STRING_LISTS) {
      root.put(k, JSONArray(JsonCodecs.decodeStrings(dao.getKvByUserKey(k)?.value ?: "[]")))
    }
    root.put("photos", JSONObject())
    root.put("photostamps", JSONObject())

    return root.toString(2)
  }

  private val BACKUP_CATEGORY_KEYS = listOf("categories", "field_expense_categories", "commission_categories")
  private val BACKUP_STRING_LISTS = BACKUP_CATEGORY_KEYS + "reps"

  private fun jsonStrings(arr: JSONArray): List<String> =
    (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }

  /** Union of the stored list and [incoming] (case-insensitive), flagged for sync like any local edit. */
  private suspend fun mergeKvStrings(userKey: String, incoming: List<String>) {
    val existing = dao.getKvByUserKey(userKey)
    val local = JsonCodecs.decodeStrings(existing?.value ?: "[]")
    val merged = (local + incoming).map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    if (merged == local) return
    val now = System.currentTimeMillis()
    dao.upsertKv(
      KvStoreEntity(
        fullKey = userKey, userKey = userKey, collection = SarimWebSchema.COL_SETTINGS.takeIf { userKey == "reps" } ?: userKey,
        rowType = "settings", value = JsonCodecs.encodeStrings(merged), ts = now, createdAt = existing?.createdAt ?: now,
      )
    )
    dao.upsertKv(KvStoreEntity(fullKey = "${userKey}_timestamp", userKey = "${userKey}_timestamp", collection = userKey, rowType = "meta", value = now.toString(), ts = now, createdAt = now))
    dao.upsertKv(KvStoreEntity(fullKey = "${userKey}_dirty", userKey = "${userKey}_dirty", collection = userKey, rowType = "meta", value = "1", ts = now, createdAt = now))
  }

  /**
   * One web `savedCalcs[]` record -> [SavedMixCalcEntity]. With [onlyIfNewer] an existing local calc is
   * kept unless the backup copy is newer (web `mergeNewestById`).
   */
  private suspend fun upsertWebSavedCalc(sc: Map<*, *>, formulaId: String, formulaName: String, onlyIfNewer: Boolean): Boolean {
    val web = LinkedHashMap<String, Any?>(sc.entries.associate { it.key.toString() to it.value })
    val scId = SarimWebSchema.str(web["id"]).trim()
    val scName = SarimWebSchema.str(web["name"]).trim()
    if (scId.isBlank() || scName.isBlank()) return false
    if (onlyIfNewer) {
      val existing = dao.getAllMixCalcs().firstOrNull { it.id == scId }
      val incomingTs = SarimWebSchema.parseEpoch(web["updatedAt"] ?: web["createdAt"] ?: 0L)
      if (existing != null && !existing.isDeleted && incomingTs <= existing.updatedAt) return false
    }
    val kindRaw = SarimWebSchema.str(web["kind"])
    val isFix = kindRaw == "fix" || kindRaw == SarimMixCalcRecords.KIND_FIX
    web["kind"] = if (isFix) "fix" else "batch"
    if (isFix && web["remaining"] == null) web["remaining"] = web["inputValue"]
    if (!isFix && web["totalWeight"] == null) web["totalWeight"] = web["inputValue"]
    dao.upsertMixCalc(
      SavedMixCalcEntity(
        id = scId,
        kind = if (isFix) SarimMixCalcRecords.KIND_FIX else SarimMixCalcRecords.KIND_BATCH,
        name = scName,
        formulaId = formulaId,
        formulaName = formulaName,
        unit = SarimWebSchema.str(web["unit"]).ifBlank { if (isFix) "g" else "kg" },
        mode = if (isFix) "CORRECTION" else SarimFormulaEngine.normalizeMode(SarimWebSchema.str(web["mode"])),
        inputValue = SarimMixCalcRecords.inputValueOf(web),
        summaryText = SarimWebSchema.str(web["summaryText"]),
        detailsJson = SarimMixCalcRecords.detailsJsonOf(web),
        syncPending = true,
        createdAt = SarimWebSchema.parseEpoch(web["createdAt"] ?: System.currentTimeMillis()),
        updatedAt = SarimWebSchema.parseEpoch(web["updatedAt"] ?: System.currentTimeMillis()),
      )
    )
    return true
  }

  /** Merge-restore for formulas this device already has: add/refresh their saved calculations. */
  suspend fun mergeSavedCalcs(formulaId: String, formulaName: String, calcs: List<*>): Int =
    calcs.count { (it as? Map<*, *>)?.let { m -> upsertWebSavedCalc(m, formulaId, formulaName, onlyIfNewer = true) } == true }

  /**
   * Web restores `costs` / `adjustment` / `tracking` from the backup. Android keeps those values on the
   * formula itself, so apply them to formulas that already exist here.
   */
  suspend fun applyFactoryFeeds(root: JSONObject): Int {
    val costs = root.optJSONObject("costs"); val adj = root.optJSONObject("adjustment"); val tracking = root.optJSONObject("tracking")
    if (costs == null && adj == null && tracking == null) return 0
    val now = System.currentTimeMillis()
    var changed = 0
    dao.getAllFormulas().filter { !it.isDeleted }.forEach { f ->
      var next = f
      if (costs != null && costs.has(f.id)) next = next.copy(additionalCostPerUnit = costs.optDouble(f.id, f.additionalCostPerUnit))
      if (adj != null && adj.has(f.id)) next = next.copy(costAdjustmentFactor = adj.optDouble(f.id, f.costAdjustmentFactor))
      tracking?.optJSONObject(f.id)?.let { t ->
        next = next.copy(unitsAvailable = t.optDouble("available", f.unitsAvailable), unitsUsed = t.optDouble("used", f.unitsUsed))
      }
      if (next != f) { dao.upsertFormula(next.copy(syncPending = true, updatedAt = now)); changed++ }
    }
    return changed
  }

  suspend fun restoreBackupJson(raw: String, markPending: Boolean = true): Result<Int> {
    return try {
      val root = JSONObject(raw)
      var restoredCount = 0

      // Stores (v1 storeKey/salePricePerKg OR web key/salePrice)
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_STORES)).forEach { m ->
        SarimWebPullMappers.mapToStore(m)?.let { s ->
          dao.upsertStore(s.copy(syncPending = markPending))
          restoredCount++
        }
      }

      // Inventory (web) + fallback legacy materials (v1)
      val invArr = root.optJSONArray(SarimWebSchema.COL_INVENTORY)
      if (invArr != null) {
        SarimWebPullMappers.jsonObjectToMapList(invArr).forEach { m ->
          val isLot = SarimWebSchema.bool(m["isPurchaseLot"])
          val id = SarimWebSchema.str(m["id"]).ifBlank {
            SarimUuid.generateUUID(if (isLot) SarimUuid.PREFIX_LOT else SarimUuid.PREFIX_MAT)
          }
          if (isLot) {
            SarimWebPullMappers.mapToPurchaseLot(id, m)?.let {
              dao.upsertPurchase(it.copy(syncPending = markPending)); restoredCount++
            }
          } else {
            SarimWebPullMappers.mapToMaterial(id, m)?.let {
              dao.upsertRawMaterial(it.copy(syncPending = markPending)); restoredCount++
            }
          }
        }
      } else {
        root.optJSONArray("materials")?.let { matsArr ->
          for (i in 0 until matsArr.length()) {
            val o = matsArr.getJSONObject(i)
            dao.upsertRawMaterial(
              RawMaterialEntity(
                id = o.optString("id").ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_MAT) },
                name = o.getString("name"),
                unitName = o.optString("unitName", "kg"),
                conversionFactorKg = o.optDouble("conversionFactorKg", 1.0),
                stockKg = o.optDouble("stockKg", 0.0),
                collectedKg = o.optDouble("collectedKg", 0.0),
                avgCostPerKg = o.optDouble("avgCostPerKg", 0.0),
                pendingSupplierAmount = o.optDouble("pendingSupplierAmount", 0.0),
                syncPending = markPending,
              )
            )
            restoredCount++
          }
        }
      }

      // Formulas
      root.optJSONArray(SarimWebSchema.COL_FORMULAS)?.let { formArr ->
        for (i in 0 until formArr.length()) {
          val o = formArr.getJSONObject(i)
          val id = o.optString("id").ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_FORMULA) }
          val name = o.getString("name")
          val ingJson = when {
            o.has("ingredientsJson") -> o.optString("ingredientsJson", "[]")
            o.has("ingredients") -> JsonCodecs.encodeIngredients(
              SarimWebSchema.mapIngredientsFromWeb(SarimWebSchema.jsonObjectToMap(o)["ingredients"])
            )
            else -> "[]"
          }
          dao.upsertFormula(
            FormulaEntity(
              id = id,
              name = name,
              additionalCostPerUnit = o.optDouble("additionalCostPerUnit", o.optDouble("additionalCost", root.optJSONObject("costs")?.optDouble(id, 0.0) ?: 0.0)),
              costAdjustmentFactor = o.optDouble("costAdjustmentFactor", o.optDouble("factor", root.optJSONObject("adjustment")?.optDouble(id, 1.0) ?: 1.0)),
              ingredientsJson = ingJson,
              // Web keeps units available / used in the separate `tracking` map.
              unitsAvailable = if (o.has("unitsAvailable")) o.optDouble("unitsAvailable", 0.0) else root.optJSONObject("tracking")?.optJSONObject(id)?.optDouble("available", 0.0) ?: 0.0,
              unitsUsed = if (o.has("unitsUsed")) o.optDouble("unitsUsed", 0.0) else root.optJSONObject("tracking")?.optJSONObject(id)?.optDouble("used", 0.0) ?: 0.0,
              syncPending = markPending,
            )
          )
          (SarimWebSchema.jsonObjectToMap(o)["savedCalcs"] as? List<*>).orEmpty().forEach { raw ->
            (raw as? Map<*, *>)?.let { upsertWebSavedCalc(it, id, name, onlyIfNewer = false) }
          }
          restoredCount++
        }
      }

      // Factory batches
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_FACTORY)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_FPROD) }
        SarimWebPullMappers.mapToFactoryBatch(id, m)?.let {
          dao.upsertFactoryBatch(it.copy(syncPending = markPending)); restoredCount++
        }
      }

      // Production & stock transfers
      val prodDocs = SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_PRODUCTION))
        .map { m -> (SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_PROD) }) to m }
      prodDocs.forEach { (id, m) ->
        SarimWebSchema.mapToProduction(id, m)?.let {
          dao.upsertProductionEntry(it.copy(syncPending = markPending)); restoredCount++
        }
      }
      @Suppress("UNCHECKED_CAST")
      SarimWebPullMappers.reconstructStockTransfers(prodDocs as List<Pair<String, Map<String, Any>>>).forEach {
        dao.upsertStockTransfer(it.copy(syncPending = markPending)); restoredCount++
      }

      // Customers & Clients
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_CUSTOMERS)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_CUST) }
        SarimWebSchema.mapToCustomer(id, m, isRepCustomer = SarimWebSchema.bool(m["isRepCustomer"]))?.let {
          dao.upsertCustomer(it.copy(syncPending = markPending)); restoredCount++
        }
      }
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_CLIENTS)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_REP_CUST) }
        SarimWebSchema.mapToCustomer(id, m, isRepCustomer = true)?.let {
          dao.upsertCustomer(it.copy(syncPending = markPending)); restoredCount++
        }
      }

      // Sales & Rep
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_SALES)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_SALE) }
        SarimWebSchema.mapToSale(id, m, isRep = false)?.let {
          dao.upsertSale(it.copy(syncPending = markPending)); restoredCount++
        }
      }
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_REP)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_SALE) }
        SarimWebSchema.mapToSale(id, m, isRep = true)?.let {
          dao.upsertSale(it.copy(syncPending = markPending)); restoredCount++
        }
      }

      // Entities, Transactions, Expenses, Returns, Calculator
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_ENTITIES)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_ENT) }
        SarimWebSchema.mapToEntity(id, m)?.let {
          dao.upsertPaymentEntity(it.copy(syncPending = markPending)); restoredCount++
        }
      }
      val txDocs = SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_TRANSACTIONS))
        .map { m -> (SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_PAY) }) to m }
      txDocs.forEach { (id, m) ->
        SarimWebSchema.mapToTransaction(id, m)?.let {
          dao.upsertPaymentTransaction(it.copy(syncPending = markPending)); restoredCount++
        }
      }
      @Suppress("UNCHECKED_CAST")
      SarimWebPullMappers.reconstructPaymentTransfers(txDocs as List<Pair<String, Map<String, Any>>>).forEach {
        dao.upsertPaymentTransfer(it.copy(syncPending = markPending)); restoredCount++
      }
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_EXPENSES)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_EXP) }
        SarimWebSchema.mapToExpense(id, m)?.let {
          dao.upsertExpense(it.copy(syncPending = markPending)); restoredCount++
        }
      }
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_RETURNS)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_RETLOG) }
        SarimWebPullMappers.mapToReturnLog(id, m)?.let {
          dao.upsertReturnLog(it.copy(syncPending = markPending)); restoredCount++
        }
      }
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_CALCULATOR)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { SarimUuid.generateUUID(SarimUuid.PREFIX_CALC) }
        SarimWebPullMappers.mapToSettlement(id, m)?.let {
          dao.upsertSettlement(it.copy(syncPending = markPending)); restoredCount++
        }
      }

      // Daily Ledger (v2 `daily_ledger` or v1 `ledger`)
      val ledgerArr = root.optJSONArray(SarimWebSchema.COL_DAILY_LEDGER) ?: root.optJSONArray("ledger")
      SarimWebPullMappers.jsonObjectToMapList(ledgerArr).forEach { m ->
        SarimWebPullMappers.mapToDailyLedger(m)?.let {
          dao.upsertLedgerEntry(it.copy(syncPending = markPending)); restoredCount++
        }
      }

      // Category lists: union with what this device already has (web `mergeStringLists`).
      for (k in BACKUP_CATEGORY_KEYS) {
        root.optJSONArray(k)?.let { arr -> mergeKvStrings(k, jsonStrings(arr)) }
      }
      // Rep names: only filled in when this device has none yet (web `fillEmptyRepList`).
      root.optJSONArray("reps")?.let { arr ->
        val incoming = jsonStrings(arr)
        if (incoming.isNotEmpty() && JsonCodecs.decodeStrings(dao.getKvByUserKey("reps")?.value ?: "[]").isEmpty()) {
          mergeKvStrings("reps", incoming)
        }
      }

      Result.success(restoredCount)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}
