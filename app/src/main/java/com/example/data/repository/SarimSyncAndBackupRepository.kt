package com.example.data.repository

import com.example.data.local.*
import com.example.data.remote.FirebaseSyncManager
import com.example.data.remote.SarimWebPullMappers
import com.example.data.remote.SarimWebSchema
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
)

class SarimSyncAndBackupRepository(
  private val dao: SarimDao,
  private val syncManager: FirebaseSyncManager,
  private val prefs: PreferencesManager,
  private val safeReader: SafeSqliteCacheReader = SafeSqliteCacheReader(dao),
) {
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

  suspend fun getDatabaseStructure(): List<CollectionStats> {
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

    return listOf(
      CollectionStats(SarimWebSchema.COL_PRODUCTION, prod.activeCount, prod.deletedCount, prod.pendingCount),
      CollectionStats(SarimWebSchema.COL_SALES, regularSales.activeCount, regularSales.deletedCount, regularSales.pendingCount),
      CollectionStats(SarimWebSchema.COL_REP, repSales.activeCount, repSales.deletedCount, repSales.pendingCount),
      CollectionStats(SarimWebSchema.COL_CUSTOMERS, regularCusts.activeCount, regularCusts.deletedCount, regularCusts.pendingCount),
      CollectionStats(SarimWebSchema.COL_CLIENTS, repClients.activeCount, repClients.deletedCount, repClients.pendingCount),
      CollectionStats(SarimWebSchema.COL_TRANSACTIONS, payTx.activeCount, payTx.deletedCount, payTx.pendingCount),
      CollectionStats(SarimWebSchema.COL_ENTITIES, entities.activeCount, entities.deletedCount, entities.pendingCount),
      CollectionStats(SarimWebSchema.COL_EXPENSES, exps.activeCount, exps.deletedCount, exps.pendingCount),
      CollectionStats(
        SarimWebSchema.COL_INVENTORY,
        mats.activeCount + purchases.activeCount,
        mats.deletedCount + purchases.deletedCount,
        mats.pendingCount + purchases.pendingCount,
      ),
      CollectionStats(SarimWebSchema.COL_FACTORY, batches.activeCount, batches.deletedCount, batches.pendingCount),
      CollectionStats(SarimWebSchema.COL_RETURNS, returnsList.activeCount, returnsList.deletedCount, returnsList.pendingCount),
      CollectionStats(SarimWebSchema.COL_CALCULATOR, calcs.activeCount, calcs.deletedCount, calcs.pendingCount),
      CollectionStats(SarimWebSchema.COL_DAILY_LEDGER, ledger.activeCount, ledger.deletedCount, ledger.pendingCount),
      CollectionStats(
        SarimWebSchema.COL_FORMULAS,
        formulas.activeCount + mixCalcs.activeCount,
        formulas.deletedCount,
        formulas.pendingCount + mixCalcs.pendingCount,
      ),
      CollectionStats(SarimWebSchema.COL_STORES, stores.activeCount, stores.deletedCount, stores.pendingCount),
      CollectionStats("stock_transfers", stockTx.activeCount, stockTx.deletedCount, stockTx.pendingCount),
      CollectionStats("payment_transfers", payTrans.activeCount, payTrans.deletedCount, payTrans.pendingCount),
      CollectionStats(SarimWebSchema.COL_PENDING, pendingApprovals.totalCount, 0, pendingApprovals.pendingCount),
      CollectionStats(SarimWebSchema.COL_DELETIONS, deletions.totalCount, 0, deletions.pendingCount),
      CollectionStats(SarimWebSchema.COL_PHOTOS, photos.activeCount, photos.deletedCount, photos.pendingCount),
      CollectionStats("kv_store", kvCount, 0, 0),
      CollectionStats("ndapp_outbox", outboxCount, 0, outboxCount),
      CollectionStats("app_accounts", accounts.totalCount, 0, accounts.pendingCount),
    )
  }

  suspend fun syncWithFirestore(): Result<Int> {
    if (syncManager.currentUser() == null) {
      return Result.failure(IllegalStateException("Sign in to sync with cloud."))
    }
    return try {
      val now = System.currentTimeMillis()
      var synced = 0

      // 1. Drain local ndapp_outbox queue first
      safeReader.getOutboxAllOnce().forEach { op ->
        val res = syncManager.drainOutboxOp(op)
        if (res.isSuccess) {
          dao.removeOutbox(op.id)
          synced++
        } else {
          dao.bumpOutboxAttempt(op.id, now)
        }
      }

      // 2. Push singleton config/ledger documents matching web schema
      val allStores = dao.getAllStores()
      if (allStores.any { it.syncPending }) {
        val ts = dao.getKvMetaByUserKey("stores_timestamp")?.ts ?: now
        val res = syncManager.pushSingletonDoc(SarimWebSchema.COL_STORES, "list", SarimWebSchema.storesListDocPayload(allStores, ts))
        if (res.isSuccess) {
          allStores.filter { it.syncPending }.forEach { dao.upsertStore(it.copy(syncPending = false)) }
          synced++
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
        }
      }

      val allLedger = dao.getAllDailyLedger()
      if (allLedger.any { it.syncPending }) {
        val ts = dao.getKvMetaByUserKey("daily_ledger_timestamp")?.ts ?: now
        val res = syncManager.pushSingletonDoc(SarimWebSchema.COL_DAILY_LEDGER, "entries", SarimWebSchema.dailyLedgerDocPayload(allLedger, ts))
        if (res.isSuccess) {
          allLedger.filter { it.syncPending }.forEach { dao.upsertLedgerEntry(it.copy(syncPending = false)) }
          synced++
        }
      }

      val allAccounts = dao.getAllAccounts()
      if (allAccounts.any { it.syncPending }) {
        val teamPayload = mapOf(
          "team" to allAccounts.map { a ->
            mapOf("id" to a.id, "email" to a.email, "name" to a.name, "roles" to a.rolesCsv.split(",").map { it.trim() }, "createdAt" to a.createdAt)
          },
          "updatedAt" to now,
        )
        val res = syncManager.pushSingletonDoc(SarimWebSchema.COL_SETTINGS, "team", teamPayload)
        if (res.isSuccess) {
          allAccounts.filter { it.syncPending }.forEach { dao.upsertAccount(it.copy(syncPending = false)) }
          synced++
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
          }
        }
      }

      // 3. Push any remaining pending rows across Firestore RECORD_STORES + operational collections
      synced += pushInventoryAndPurchases()
      synced += pushFactoryBatches()
      synced += pushProductionAndTransfers()
      synced += pushCustomersAndClients()
      synced += pushSalesAndRep()
      synced += pushEntitiesTransactionsExpenses()
      synced += pushReturns()
      synced += pushSettlements()
      synced += pushApprovals()
      synced += pushPhotos()

      // 4. Push deletion tombstones
      dao.purgeExpiredDeletions(now)
      val unsyncedDels = safeReader.getUnsyncedDeletionsOnce()
      if (unsyncedDels.isNotEmpty()) {
        val delRes = syncManager.pushDeletionTombstones(unsyncedDels)
        if (delRes.isSuccess) {
          synced += delRes.getOrDefault(0)
          unsyncedDels.forEach { dao.upsertDeletion(it.copy(syncedToCloud = true)) }
        }
      }

      // 5. Pull & merge remote state (deletions first, then singletons, then subcollections)
      pullAndMergeRemoteState(now)

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

  private suspend fun pushInventoryAndPurchases(): Int {
    var count = 0
    val mats = dao.getAllRawMaterials().filter { it.syncPending }
    val purchases = dao.getAllPurchases().filter { it.syncPending }
    val batch = mats.map { it.id to SarimWebSchema.materialToMap(it) } +
      purchases.map { it.id to SarimWebSchema.purchaseLotToMap(it) }
    if (batch.isNotEmpty()) {
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_INVENTORY, batch)
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        mats.forEach { dao.upsertRawMaterial(it.copy(syncPending = false)) }
        purchases.forEach { dao.upsertPurchase(it.copy(syncPending = false)) }
      }
    }
    return count
  }

  private suspend fun pushFactoryBatches(): Int {
    val batches = dao.getAllFactoryBatches().filter { it.syncPending }
    if (batches.isEmpty()) return 0
    val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_FACTORY, batches.map { it.id to SarimWebSchema.factoryBatchToMap(it) })
    if (res.isSuccess) {
      batches.forEach { dao.upsertFactoryBatch(it.copy(syncPending = false)) }
      return res.getOrDefault(0)
    }
    return 0
  }

  private suspend fun pushProductionAndTransfers(): Int {
    val prod = dao.getAllProductionEntries().filter { it.syncPending }
    val transfers = dao.getAllStockTransfers().filter { it.syncPending }
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
      return res.getOrDefault(0)
    }
    return 0
  }

  private suspend fun pushCustomersAndClients(): Int {
    var count = 0
    val all = dao.getAllCustomers().filter { it.syncPending }
    val customers = all.filter { !it.isRepCustomer }
    if (customers.isNotEmpty()) {
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_CUSTOMERS, customers.map { it.id to SarimWebSchema.customerToMap(it) })
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        customers.forEach { dao.upsertCustomer(it.copy(syncPending = false)) }
      }
    }
    val clients = all.filter { it.isRepCustomer }
    if (clients.isNotEmpty()) {
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_CLIENTS, clients.map { it.id to SarimWebSchema.customerToMap(it) })
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        clients.forEach { dao.upsertCustomer(it.copy(syncPending = false)) }
      }
    }
    return count
  }

  private suspend fun pushSalesAndRep(): Int {
    var count = 0
    val all = dao.getAllSales().filter { it.syncPending }
    val sales = all.filter { !it.isRepSale }
    if (sales.isNotEmpty()) {
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_SALES, sales.map { it.id to SarimWebSchema.saleToMap(it) })
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        sales.forEach { dao.upsertSale(it.copy(syncPending = false)) }
      }
    }
    val rep = all.filter { it.isRepSale }
    if (rep.isNotEmpty()) {
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_REP, rep.map { it.id to SarimWebSchema.saleToMap(it) })
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        rep.forEach { dao.upsertSale(it.copy(syncPending = false)) }
      }
    }
    return count
  }

  private suspend fun pushEntitiesTransactionsExpenses(): Int {
    var count = 0
    val entities = dao.getAllPaymentEntities().filter { it.syncPending }
    if (entities.isNotEmpty()) {
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_ENTITIES, entities.map { it.id to SarimWebSchema.entityToMap(it) })
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        entities.forEach { dao.upsertPaymentEntity(it.copy(syncPending = false)) }
      }
    }
    val txs = dao.getAllPaymentTransactions().filter { it.syncPending }
    val transfers = dao.getAllPaymentTransfers().filter { it.syncPending }
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
      }
    }
    val exps = dao.getAllExpenses().filter { it.syncPending }
    if (exps.isNotEmpty()) {
      val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_EXPENSES, exps.map { it.id to SarimWebSchema.expenseToMap(it) })
      if (res.isSuccess) {
        count += res.getOrDefault(0)
        exps.forEach { dao.upsertExpense(it.copy(syncPending = false)) }
      }
    }
    return count
  }

  private suspend fun pushReturns(): Int {
    val rows = dao.getAllReturns().filter { it.syncPending }
    if (rows.isEmpty()) return 0
    val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_RETURNS, rows.map { it.id to SarimWebSchema.returnLogToMap(it) })
    if (res.isSuccess) {
      rows.forEach { dao.upsertReturnLog(it.copy(syncPending = false)) }
      return res.getOrDefault(0)
    }
    return 0
  }

  private suspend fun pushSettlements(): Int {
    val rows = dao.getAllSettlements().filter { it.syncPending }
    if (rows.isEmpty()) return 0
    val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_CALCULATOR, rows.map { it.id to SarimWebSchema.settlementToMap(it) })
    if (res.isSuccess) {
      rows.forEach { dao.upsertSettlement(it.copy(syncPending = false)) }
      return res.getOrDefault(0)
    }
    return 0
  }

  private suspend fun pushApprovals(): Int {
    val rows = dao.getAllApprovals().filter { it.syncPending }
    if (rows.isEmpty()) return 0
    val docs = rows.map { req ->
      req.id to linkedMapOf<String, Any?>(
        "id" to req.id,
        "submittedBy" to req.submittedBy,
        "submittedRole" to req.submittedRole,
        "collection" to req.targetCollection.ifBlank { req.category.lowercase() },
        "docId" to req.docId,
        "action" to req.action,
        "isEdit" to req.isEdit,
        "summary" to req.targetSummary,
        "status" to req.status.lowercase(),
        "submittedAt" to req.createdAt,
        "updatedAt" to System.currentTimeMillis(),
      )
    }
    val res = syncManager.pushCollectionBatch(SarimWebSchema.COL_PENDING, docs)
    if (res.isSuccess) {
      rows.forEach { dao.upsertApproval(it.copy(syncPending = false)) }
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

  private suspend fun pullAndMergeRemoteState(now: Long) {
    // Deletions first
    val remoteDeletions = syncManager.fetchCollectionDocs(SarimWebSchema.COL_DELETIONS).getOrNull().orEmpty()
    val deletedKeySet = hashSetOf<String>()
    remoteDeletions.forEach { (id, raw) ->
      SarimWebPullMappers.mapToDeletionTombstone(id, raw)?.let { d ->
        if (d.expiresAt > now) {
          dao.upsertDeletion(d)
          deletedKeySet += "${d.collection}:${d.recordId}"
          deletedKeySet += d.recordId
        }
      }
    }
    dao.getAllDeletionRefs().forEach { d ->
      deletedKeySet += "${d.collection}:${d.recordId}"
      deletedKeySet += d.recordId
    }

    fun isTombstoned(col: String, id: String): Boolean =
      deletedKeySet.contains("$col:$id") || deletedKeySet.contains(id)

    // Singleton: stores/list
    syncManager.fetchSingletonDoc(SarimWebSchema.COL_STORES, "list").getOrNull()?.let { doc ->
      val remoteTs = SarimWebSchema.parseEpoch(doc["stores_timestamp"])
      val localStores = dao.getAllStores().associateBy { it.storeKey }
      val storesList = doc["stores"] as? List<*> ?: emptyList<Any>()
      storesList.forEach { raw ->
        val map = raw as? Map<*, *> ?: return@forEach
        val remote = SarimWebPullMappers.mapToStore(map)?.copy(updatedAt = remoteTs) ?: return@forEach
        val local = localStores[remote.storeKey]
        if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
          dao.upsertStore(remote)
        }
      }
    }

    // Singleton: formulas/config
    syncManager.fetchSingletonDoc(SarimWebSchema.COL_FORMULAS, "config").getOrNull()?.let { doc ->
      val localFormulas = dao.getAllFormulas().associateBy { it.id }
      val localCalcs = dao.getAllMixCalcs().associateBy { it.id }
      val (remoteFormulas, remoteCalcs) = SarimWebPullMappers.mapFormulasConfigDoc(doc, localFormulas)
      remoteFormulas.forEach { rf ->
        if (!isTombstoned(SarimWebSchema.COL_FORMULAS, rf.id)) {
          val local = localFormulas[rf.id]
          if (local == null || (!local.syncPending && rf.updatedAt >= local.updatedAt)) {
            dao.upsertFormula(rf)
          }
        }
      }
      remoteCalcs.forEach { rc ->
        val local = localCalcs[rc.id]
        if (local == null || (!local.syncPending && rc.updatedAt >= local.updatedAt)) {
          dao.upsertMixCalc(rc)
        }
      }
    }

    // Singleton: daily_ledger/entries
    syncManager.fetchSingletonDoc(SarimWebSchema.COL_DAILY_LEDGER, "entries").getOrNull()?.let { doc ->
      val localLedger = dao.getAllDailyLedger().associateBy { it.id }
      val rows = doc["daily_ledger"] as? List<*> ?: emptyList<Any>()
      rows.forEach { raw ->
        val map = raw as? Map<*, *> ?: return@forEach
        val rl = SarimWebPullMappers.mapToDailyLedger(map) ?: return@forEach
        if (!isTombstoned(SarimWebSchema.COL_DAILY_LEDGER, rl.id)) {
          val local = localLedger[rl.id]
          if (local == null || (!local.syncPending && rl.updatedAt >= local.updatedAt)) {
            dao.upsertLedgerEntry(rl)
          }
        }
      }
    }

    // Subcollection: inventory (materials + purchase lots)
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_INVENTORY).getOrNull()?.let { docs ->
      val localMats = dao.getAllRawMaterials().associateBy { it.id }
      val localLots = dao.getAllPurchases().associateBy { it.id }
      docs.forEach { (id, m) ->
        if (!isTombstoned(SarimWebSchema.COL_INVENTORY, id)) {
          if (SarimWebSchema.bool(m["isPurchaseLot"])) {
            SarimWebPullMappers.mapToPurchaseLot(id, m)?.let { remote ->
              val local = localLots[id]
              if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
                dao.upsertPurchase(remote)
              }
            }
          } else {
            SarimWebPullMappers.mapToMaterial(id, m)?.let { remote ->
              val local = localMats[id]
              if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
                dao.upsertRawMaterial(remote)
              }
            }
          }
        }
      }
    }

    // Subcollection: factory
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_FACTORY).getOrNull()?.let { docs ->
      val localMap = dao.getAllFactoryBatches().associateBy { it.id }
      docs.forEach { (id, m) ->
        if (!isTombstoned(SarimWebSchema.COL_FACTORY, id)) {
          SarimWebPullMappers.mapToFactoryBatch(id, m)?.let { remote ->
            val local = localMap[id]
            if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
              dao.upsertFactoryBatch(remote)
            }
          }
        }
      }
    }

    // Subcollection: production + paired stock transfers
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_PRODUCTION).getOrNull()?.let { docs ->
      val localProd = dao.getAllProductionEntries().associateBy { it.id }
      val localTransfers = dao.getAllStockTransfers().associateBy { it.id }
      docs.forEach { (id, m) ->
        if (!isTombstoned(SarimWebSchema.COL_PRODUCTION, id)) {
          SarimWebSchema.mapToProduction(id, m)?.let { remote ->
            val local = localProd[id]
            if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
              dao.upsertProductionEntry(remote)
            }
          }
        }
      }
      SarimWebPullMappers.reconstructStockTransfers(docs).forEach { rt ->
        val local = localTransfers[rt.id]
        if (local == null || (!local.syncPending && rt.updatedAt >= local.updatedAt)) {
          dao.upsertStockTransfer(rt)
        }
      }
    }

    // Subcollections: customers & clients
    val localCustomers = dao.getAllCustomers().associateBy { it.id }
    listOf(SarimWebSchema.COL_CUSTOMERS to false, SarimWebSchema.COL_CLIENTS to true).forEach { (col, isRep) ->
      syncManager.fetchCollectionDocs(col).getOrNull()?.forEach { (id, m) ->
        if (!isTombstoned(col, id)) {
          SarimWebSchema.mapToCustomer(id, m, isRepCustomer = isRep)?.let { remote ->
            val local = localCustomers[id]
            if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
              dao.upsertCustomer(remote)
            }
          }
        }
      }
    }

    // Subcollections: sales & rep
    val localSales = dao.getAllSales().associateBy { it.id }
    listOf(SarimWebSchema.COL_SALES to false, SarimWebSchema.COL_REP to true).forEach { (col, isRep) ->
      syncManager.fetchCollectionDocs(col).getOrNull()?.forEach { (id, m) ->
        if (!isTombstoned(col, id)) {
          SarimWebSchema.mapToSale(id, m, isRep = isRep)?.let { remote ->
            val local = localSales[id]
            if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
              dao.upsertSale(remote)
            }
          }
        }
      }
    }

    // Subcollection: entities
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_ENTITIES).getOrNull()?.let { docs ->
      val localMap = dao.getAllPaymentEntities().associateBy { it.id }
      docs.forEach { (id, m) ->
        if (!isTombstoned(SarimWebSchema.COL_ENTITIES, id)) {
          SarimWebSchema.mapToEntity(id, m)?.let { remote ->
            val local = localMap[id]
            if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
              dao.upsertPaymentEntity(remote)
            }
          }
        }
      }
    }

    // Subcollection: transactions + paired payment transfers
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_TRANSACTIONS).getOrNull()?.let { docs ->
      val localTx = dao.getAllPaymentTransactions().associateBy { it.id }
      val localTransfers = dao.getAllPaymentTransfers().associateBy { it.id }
      docs.forEach { (id, m) ->
        if (!isTombstoned(SarimWebSchema.COL_TRANSACTIONS, id)) {
          SarimWebSchema.mapToTransaction(id, m)?.let { remote ->
            val local = localTx[id]
            if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
              dao.upsertPaymentTransaction(remote)
            }
          }
        }
      }
      SarimWebPullMappers.reconstructPaymentTransfers(docs).forEach { rt ->
        val local = localTransfers[rt.id]
        if (local == null || (!local.syncPending && rt.updatedAt >= local.updatedAt)) {
          dao.upsertPaymentTransfer(rt)
        }
      }
    }

    // Subcollection: expenses
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_EXPENSES).getOrNull()?.let { docs ->
      val localMap = dao.getAllExpenses().associateBy { it.id }
      docs.forEach { (id, m) ->
        if (!isTombstoned(SarimWebSchema.COL_EXPENSES, id)) {
          SarimWebSchema.mapToExpense(id, m)?.let { remote ->
            val local = localMap[id]
            if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
              dao.upsertExpense(remote)
            }
          }
        }
      }
    }

    // Subcollection: returns
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_RETURNS).getOrNull()?.let { docs ->
      val localMap = dao.getAllReturns().associateBy { it.id }
      docs.forEach { (id, m) ->
        if (!isTombstoned(SarimWebSchema.COL_RETURNS, id)) {
          SarimWebPullMappers.mapToReturnLog(id, m)?.let { remote ->
            val local = localMap[id]
            if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
              dao.upsertReturnLog(remote)
            }
          }
        }
      }
    }

    // Subcollection: calculator
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_CALCULATOR).getOrNull()?.let { docs ->
      val localMap = dao.getAllSettlements().associateBy { it.id }
      docs.forEach { (id, m) ->
        if (!isTombstoned(SarimWebSchema.COL_CALCULATOR, id)) {
          SarimWebPullMappers.mapToSettlement(id, m)?.let { remote ->
            val local = localMap[id]
            if (local == null || (!local.syncPending && remote.updatedAt >= local.updatedAt)) {
              dao.upsertSettlement(remote)
            }
          }
        }
      }
    }

    // Subcollection: pending
    syncManager.fetchCollectionDocs(SarimWebSchema.COL_PENDING).getOrNull()?.let { docs ->
      val localMap = dao.getAllApprovals().associateBy { it.id }
      docs.forEach { (id, m) ->
        SarimWebPullMappers.mapToApproval(id, m)?.let { remote ->
          val local = localMap[id]
          if (local == null || !local.syncPending) {
            dao.upsertApproval(remote)
          }
        }
      }
    }

    // Singleton category lists
    listOf(
      SarimWebSchema.COL_CATEGORIES,
      SarimWebSchema.COL_FIELD_EXP_CATS,
      SarimWebSchema.COL_COMM_CATS,
    ).forEach { catCol ->
      syncManager.fetchSingletonDoc(catCol, "list").getOrNull()?.let { doc ->
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
    }
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

  private suspend fun removeTombstoneAndEnqueueCloudDelete(docId: String, now: Long) {
    dao.deleteDeletion(docId)
    dao.removeOutboxForDoc(SarimWebSchema.COL_DELETIONS, docId)
    dao.insertOutbox(
      NdappOutboxEntity(
        id = "ob_${UUID.randomUUID()}",
        action = "delete",
        collection = SarimWebSchema.COL_DELETIONS,
        docId = docId,
        payload = null,
        createdAt = now,
      )
    )
  }

  suspend fun restoreRecycleItem(item: RecycleBinRecord) {
    val now = System.currentTimeMillis()
    when (item.entityType) {
      "SALE" -> dao.restoreSale(item.id)
      "PRODUCTION" -> dao.restoreProductionEntry(item.id)
      "SETTLEMENT" -> dao.restoreSettlement(item.id)
      "BATCH" -> dao.restoreFactoryBatch(item.id)
      "PAYMENT" -> {
        dao.restorePaymentTransaction(item.id)
        dao.getAllPaymentTransactions().firstOrNull { it.id == item.id }?.expenseId?.let { expId ->
          dao.restoreExpense(expId)
          removeTombstoneAndEnqueueCloudDelete(expId, now)
        }
      }
      "EXPENSE" -> dao.restoreExpense(item.id)
      "RETURN" -> dao.restoreReturnLog(item.id)
      "LEDGER" -> dao.restoreLedgerEntry(item.id)
      "FORMULA" -> dao.restoreFormula(item.id)
      "MATERIAL" -> dao.restoreRawMaterial(item.id)
      "PURCHASE_LOT" -> dao.restorePurchase(item.id)
      "CUSTOMER" -> dao.restoreCustomer(item.id)
      "ENTITY" -> dao.restorePaymentEntity(item.id)
      "STOCK_TRANSFER" -> {
        dao.restoreStockTransfer(item.id)
        dao.getAllStockTransfers().firstOrNull { it.id == item.id }?.let { tr ->
          removeTombstoneAndEnqueueCloudDelete(tr.outDocId, now)
          removeTombstoneAndEnqueueCloudDelete(tr.inDocId, now)
        }
      }
      "PAYMENT_TRANSFER" -> {
        dao.restorePaymentTransfer(item.id)
        dao.getAllPaymentTransfers().firstOrNull { it.id == item.id }?.let { tr ->
          removeTombstoneAndEnqueueCloudDelete(tr.outDocId, now)
          removeTombstoneAndEnqueueCloudDelete(tr.inDocId, now)
        }
      }
    }
    removeTombstoneAndEnqueueCloudDelete(item.id, now)
  }

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

    root.put(SarimWebSchema.COL_FORMULAS, JSONArray().apply {
      dao.getAllFormulas().filter { !it.isDeleted }.forEach { f ->
        put(JSONObject().apply {
          put("id", f.id); put("name", f.name)
          put("additionalCost", f.additionalCostPerUnit); put("additionalCostPerUnit", f.additionalCostPerUnit)
          put("factor", f.costAdjustmentFactor); put("costAdjustmentFactor", f.costAdjustmentFactor)
          put("ingredientsJson", f.ingredientsJson)
          put("unitsAvailable", f.unitsAvailable); put("unitsUsed", f.unitsUsed)
          put("savedCalcs", JSONArray().apply {
            calcsByFormula[f.id].orEmpty().forEach { sc ->
              put(JSONObject().apply {
                put("id", sc.id); put("kind", sc.kind); put("name", sc.name)
                put("unit", sc.unit); put("mode", sc.mode); put("inputValue", sc.inputValue)
                put("summaryText", sc.summaryText)
              })
            }
          })
        })
      }
    })

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
          put("timestamp", l.dateEpoch); put("updatedAt", l.updatedAt)
        })
      }
    })
    // Legacy v1 compatibility key
    root.put("ledger", root.getJSONArray(SarimWebSchema.COL_DAILY_LEDGER))

    return root.toString(2)
  }

  suspend fun restoreBackupJson(raw: String): Result<Int> {
    return try {
      val root = JSONObject(raw)
      var restoredCount = 0

      // Stores (v1 storeKey/salePricePerKg OR web key/salePrice)
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_STORES)).forEach { m ->
        SarimWebPullMappers.mapToStore(m)?.let { s ->
          dao.upsertStore(s.copy(syncPending = true))
          restoredCount++
        }
      }

      // Inventory (web) + fallback legacy materials (v1)
      val invArr = root.optJSONArray(SarimWebSchema.COL_INVENTORY)
      if (invArr != null) {
        SarimWebPullMappers.jsonObjectToMapList(invArr).forEach { m ->
          val id = SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }
          if (SarimWebSchema.bool(m["isPurchaseLot"])) {
            SarimWebPullMappers.mapToPurchaseLot(id, m)?.let {
              dao.upsertPurchase(it.copy(syncPending = true)); restoredCount++
            }
          } else {
            SarimWebPullMappers.mapToMaterial(id, m)?.let {
              dao.upsertRawMaterial(it.copy(syncPending = true)); restoredCount++
            }
          }
        }
      } else {
        root.optJSONArray("materials")?.let { matsArr ->
          for (i in 0 until matsArr.length()) {
            val o = matsArr.getJSONObject(i)
            dao.upsertRawMaterial(
              RawMaterialEntity(
                id = o.getString("id"),
                name = o.getString("name"),
                unitName = o.optString("unitName", "kg"),
                conversionFactorKg = o.optDouble("conversionFactorKg", 1.0),
                stockKg = o.optDouble("stockKg", 0.0),
                collectedKg = o.optDouble("collectedKg", 0.0),
                avgCostPerKg = o.optDouble("avgCostPerKg", 0.0),
                pendingSupplierAmount = o.optDouble("pendingSupplierAmount", 0.0),
                syncPending = true,
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
          val id = o.getString("id")
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
              additionalCostPerUnit = o.optDouble("additionalCostPerUnit", o.optDouble("additionalCost", 0.0)),
              costAdjustmentFactor = o.optDouble("costAdjustmentFactor", o.optDouble("factor", 1.0)),
              ingredientsJson = ingJson,
              unitsAvailable = o.optDouble("unitsAvailable", 0.0),
              unitsUsed = o.optDouble("unitsUsed", 0.0),
              syncPending = true,
            )
          )
          restoredCount++
        }
      }

      // Factory batches
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_FACTORY)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }
        SarimWebPullMappers.mapToFactoryBatch(id, m)?.let {
          dao.upsertFactoryBatch(it.copy(syncPending = true)); restoredCount++
        }
      }

      // Production & stock transfers
      val prodDocs = SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_PRODUCTION))
        .map { m -> (SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }) to m }
      prodDocs.forEach { (id, m) ->
        SarimWebSchema.mapToProduction(id, m)?.let {
          dao.upsertProductionEntry(it.copy(syncPending = true)); restoredCount++
        }
      }
      @Suppress("UNCHECKED_CAST")
      SarimWebPullMappers.reconstructStockTransfers(prodDocs as List<Pair<String, Map<String, Any>>>).forEach {
        dao.upsertStockTransfer(it.copy(syncPending = true)); restoredCount++
      }

      // Customers & Clients
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_CUSTOMERS)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }
        SarimWebSchema.mapToCustomer(id, m, isRepCustomer = SarimWebSchema.bool(m["isRepCustomer"]))?.let {
          dao.upsertCustomer(it.copy(syncPending = true)); restoredCount++
        }
      }
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_CLIENTS)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }
        SarimWebSchema.mapToCustomer(id, m, isRepCustomer = true)?.let {
          dao.upsertCustomer(it.copy(syncPending = true)); restoredCount++
        }
      }

      // Sales & Rep
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_SALES)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }
        SarimWebSchema.mapToSale(id, m, isRep = false)?.let {
          dao.upsertSale(it.copy(syncPending = true)); restoredCount++
        }
      }
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_REP)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }
        SarimWebSchema.mapToSale(id, m, isRep = true)?.let {
          dao.upsertSale(it.copy(syncPending = true)); restoredCount++
        }
      }

      // Entities, Transactions, Expenses, Returns, Calculator
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_ENTITIES)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }
        SarimWebSchema.mapToEntity(id, m)?.let {
          dao.upsertPaymentEntity(it.copy(syncPending = true)); restoredCount++
        }
      }
      val txDocs = SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_TRANSACTIONS))
        .map { m -> (SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }) to m }
      txDocs.forEach { (id, m) ->
        SarimWebSchema.mapToTransaction(id, m)?.let {
          dao.upsertPaymentTransaction(it.copy(syncPending = true)); restoredCount++
        }
      }
      @Suppress("UNCHECKED_CAST")
      SarimWebPullMappers.reconstructPaymentTransfers(txDocs as List<Pair<String, Map<String, Any>>>).forEach {
        dao.upsertPaymentTransfer(it.copy(syncPending = true)); restoredCount++
      }
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_EXPENSES)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }
        SarimWebSchema.mapToExpense(id, m)?.let {
          dao.upsertExpense(it.copy(syncPending = true)); restoredCount++
        }
      }
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_RETURNS)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }
        SarimWebPullMappers.mapToReturnLog(id, m)?.let {
          dao.upsertReturnLog(it.copy(syncPending = true)); restoredCount++
        }
      }
      SarimWebPullMappers.jsonObjectToMapList(root.optJSONArray(SarimWebSchema.COL_CALCULATOR)).forEach { m ->
        val id = SarimWebSchema.str(m["id"]).ifBlank { UUID.randomUUID().toString() }
        SarimWebPullMappers.mapToSettlement(id, m)?.let {
          dao.upsertSettlement(it.copy(syncPending = true)); restoredCount++
        }
      }

      // Daily Ledger (v2 `daily_ledger` or v1 `ledger`)
      val ledgerArr = root.optJSONArray(SarimWebSchema.COL_DAILY_LEDGER) ?: root.optJSONArray("ledger")
      SarimWebPullMappers.jsonObjectToMapList(ledgerArr).forEach { m ->
        SarimWebPullMappers.mapToDailyLedger(m)?.let {
          dao.upsertLedgerEntry(it.copy(syncPending = true)); restoredCount++
        }
      }

      Result.success(restoredCount)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}
