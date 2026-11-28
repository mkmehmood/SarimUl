package com.example

import com.example.data.local.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class InMemorySarimDao : SarimDao {
  private var schemaVersion: SchemaVersionEntity? = null
  private val kv = linkedMapOf<String, KvStoreEntity>()
  private val outbox = linkedMapOf<String, NdappOutboxEntity>()
  private val stores = linkedMapOf<String, StoreEntity>()
  private val materials = linkedMapOf<String, RawMaterialEntity>()
  private val purchases = linkedMapOf<String, RawMaterialPurchaseEntity>()
  private val formulas = linkedMapOf<String, FormulaEntity>()
  private val batches = linkedMapOf<String, FactoryBatchEntity>()
  private val production = linkedMapOf<String, ProductionEntryEntity>()
  private val returns = linkedMapOf<String, ReturnLogEntity>()
  private val customers = linkedMapOf<String, CustomerEntity>()
  private val sales = linkedMapOf<String, SaleTransactionEntity>()
  private val entities = linkedMapOf<String, PaymentEntityRecord>()
  private val transactions = linkedMapOf<String, PaymentTransactionEntity>()
  private val expenses = linkedMapOf<String, ExpenseRecordEntity>()
  private val ledger = linkedMapOf<String, DailyLedgerEntity>()
  private val settlements = linkedMapOf<String, CalculatorSettlementEntity>()
  private val stockTransfers = linkedMapOf<String, StockTransferEntity>()
  private val paymentTransfers = linkedMapOf<String, PaymentTransferEntity>()
  private val mixCalcs = linkedMapOf<String, SavedMixCalcEntity>()
  private val approvals = linkedMapOf<String, ApprovalRequestEntity>()
  private val accounts = linkedMapOf<String, AppAccountEntity>()
  private val deletions = linkedMapOf<String, DeletionTombstoneEntity>()
  private val photos = linkedMapOf<String, PhotoRecordEntity>()

  private inline fun <T> counts(items: Collection<T>, isDel: (T) -> Boolean, isPend: (T) -> Boolean): TableSyncCounts {
    val active = items.count { !isDel(it) }
    val deleted = items.count { isDel(it) }
    val pending = items.count { isPend(it) }
    return TableSyncCounts(items.size, active, deleted, pending)
  }

  override suspend fun upsertSchemaVersion(item: SchemaVersionEntity) { schemaVersion = item }
  override suspend fun getSchemaVersion(): SchemaVersionEntity? = schemaVersion

  override suspend fun getKv(fullKey: String): KvStoreEntity? = kv[fullKey]
  override suspend fun getKvByUserKey(userKey: String): KvStoreEntity? = kv.values.firstOrNull { it.userKey == userKey }
  override suspend fun getAllKv(): List<KvStoreEntity> = kv.values.toList()
  override fun observeAllKvKeys(): Flow<List<String>> = flowOf(kv.keys.sorted())
  override fun observeCategoryAndRepKv(): Flow<List<KvStoreEntity>> = flowOf(
    kv.values.filter { it.userKey in setOf("categories", "field_expense_categories", "commission_categories", "reps") }
  )
  override suspend fun getKvMetaAll(): List<KvMetaRow> = kv.values.sortedBy { it.fullKey }.map {
    KvMetaRow(it.fullKey, it.userKey, it.uid, it.collection, it.rowType, it.encrypted, it.ts, it.createdAt, it.value?.length ?: 0, it.value?.toByteArray()?.size ?: 0, it.value == null)
  }
  override suspend fun getKvMetaByFullKey(fullKey: String): KvMetaRow? = getKvMetaAll().firstOrNull { it.fullKey == fullKey }
  override suspend fun getKvMetaByUserKey(userKey: String): KvMetaRow? = getKvMetaAll().firstOrNull { it.userKey == userKey }
  override suspend fun readKvValueChunk(fullKey: String, startPos: Int, len: Int): String? {
    val v = kv[fullKey]?.value ?: return null
    val s = (startPos - 1).coerceAtLeast(0)
    return if (s >= v.length) "" else v.substring(s, minOf(v.length, s + len))
  }
  override suspend fun countKv(): Int = kv.size
  override suspend fun countDirtyKv(): Int = kv.values.count { it.userKey.endsWith("_dirty") }
  override suspend fun upsertKv(item: KvStoreEntity) { kv[item.fullKey] = item }
  override suspend fun deleteKv(fullKey: String) { kv.remove(fullKey) }

  override suspend fun getOutboxAll(): List<NdappOutboxEntity> = outbox.values.sortedBy { it.createdAt }
  override suspend fun getOutboxMetaAll(): List<OutboxMetaRow> = getOutboxAll().map {
    OutboxMetaRow(it.id, it.uid, it.action, it.collection, it.docId, it.createdAt, it.attempts, it.lastAttempt, it.payload?.length ?: 0, it.payload?.toByteArray()?.size ?: 0, it.payload == null)
  }
  override suspend fun readOutboxPayloadChunk(id: String, startPos: Int, len: Int): String? {
    val p = outbox[id]?.payload ?: return null
    val s = (startPos - 1).coerceAtLeast(0)
    return if (s >= p.length) "" else p.substring(s, minOf(p.length, s + len))
  }
  override suspend fun countOutbox(): Int = outbox.size
  override fun observeOutboxCount(): Flow<Int> = flowOf(outbox.size)
  override suspend fun insertOutbox(item: NdappOutboxEntity) { outbox.putIfAbsent(item.id, item) }
  override suspend fun bumpOutboxAttempt(id: String, ts: Long) { outbox[id]?.let { outbox[id] = it.copy(attempts = it.attempts + 1, lastAttempt = ts) } }
  override suspend fun removeOutbox(id: String) { outbox.remove(id) }
  override suspend fun removeOutboxForDoc(collection: String, docId: String) {
    outbox.entries.removeAll { it.value.collection == collection && it.value.docId == docId }
  }
  override suspend fun clearOutbox() { outbox.clear() }

  override suspend fun getStoreCounts() = counts(stores.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getRawMaterialCounts() = counts(materials.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getPurchaseCounts() = counts(purchases.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getFormulaCounts() = counts(formulas.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getFactoryBatchCounts() = counts(batches.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getProductionCounts() = counts(production.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getReturnCounts() = counts(returns.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getCustomerCounts(isRep: Boolean) = counts(customers.values.filter { it.isRepCustomer == isRep }, { it.isDeleted }, { it.syncPending })
  override suspend fun getSaleCounts(isRep: Boolean) = counts(sales.values.filter { it.isRepSale == isRep }, { it.isDeleted }, { it.syncPending })
  override suspend fun getPaymentEntityCounts() = counts(entities.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getPaymentTransactionCounts() = counts(transactions.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getExpenseCounts() = counts(expenses.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getDailyLedgerCounts() = counts(ledger.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getSettlementCounts() = counts(settlements.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getStockTransferCounts() = counts(stockTransfers.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getPaymentTransferCounts() = counts(paymentTransfers.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getMixCalcCounts() = counts(mixCalcs.values, { it.isDeleted }, { it.syncPending })
  override suspend fun getApprovalCounts() = counts(approvals.values, { false }, { it.syncPending })
  override suspend fun getAccountCounts() = counts(accounts.values, { false }, { it.syncPending })
  override suspend fun getDeletionCounts() = counts(deletions.values, { false }, { !it.syncedToCloud })
  override suspend fun getPhotoCounts() = counts(photos.values, { it.deleted }, { it.syncPending })

  override suspend fun getDeletedSalesSince(cutoff: Long) = sales.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedProductionSince(cutoff: Long) = production.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedSettlementsSince(cutoff: Long) = settlements.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedFactoryBatchesSince(cutoff: Long) = batches.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedPaymentTransactionsSince(cutoff: Long) = transactions.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedExpensesSince(cutoff: Long) = expenses.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedReturnsSince(cutoff: Long) = returns.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedDailyLedgerSince(cutoff: Long) = ledger.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedFormulasSince(cutoff: Long) = formulas.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedRawMaterialsSince(cutoff: Long) = materials.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedPurchasesSince(cutoff: Long) = purchases.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedCustomersSince(cutoff: Long) = customers.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedPaymentEntitiesSince(cutoff: Long) = entities.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedStockTransfersSince(cutoff: Long) = stockTransfers.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }
  override suspend fun getDeletedPaymentTransfersSince(cutoff: Long) = paymentTransfers.values.filter { it.isDeleted && (it.deletedAt ?: 0L) >= cutoff }

  override fun observeStores() = flowOf(stores.values.filter { !it.isDeleted })
  override suspend fun getAllStores() = stores.values.toList()
  override suspend fun upsertStore(store: StoreEntity) { stores[store.storeKey] = store }
  override suspend fun softDeleteStore(key: String, ts: Long) { stores[key]?.let { stores[key] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreStore(key: String) { stores[key]?.let { stores[key] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteStore(key: String) { stores.remove(key) }

  override fun observeRawMaterials() = flowOf(materials.values.filter { !it.isDeleted })
  override suspend fun getAllRawMaterials() = materials.values.toList()
  override suspend fun upsertRawMaterial(item: RawMaterialEntity) { materials[item.id] = item }
  override suspend fun softDeleteRawMaterial(id: String, ts: Long) { materials[id]?.let { materials[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreRawMaterial(id: String) { materials[id]?.let { materials[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteRawMaterial(id: String) { materials.remove(id) }

  override fun observePurchases() = flowOf(purchases.values.filter { !it.isDeleted })
  override suspend fun getAllPurchases() = purchases.values.toList()
  override suspend fun upsertPurchase(item: RawMaterialPurchaseEntity) { purchases[item.id] = item }
  override suspend fun softDeletePurchase(id: String, ts: Long) { purchases[id]?.let { purchases[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restorePurchase(id: String) { purchases[id]?.let { purchases[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeletePurchase(id: String) { purchases.remove(id) }

  override fun observeFormulas() = flowOf(formulas.values.filter { !it.isDeleted })
  override suspend fun getAllFormulas() = formulas.values.toList()
  override suspend fun upsertFormula(item: FormulaEntity) { formulas[item.id] = item }
  override suspend fun softDeleteFormula(id: String, ts: Long) { formulas[id]?.let { formulas[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreFormula(id: String) { formulas[id]?.let { formulas[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteFormula(id: String) { formulas.remove(id) }

  override fun observeFactoryBatches() = flowOf(batches.values.filter { !it.isDeleted })
  override suspend fun getAllFactoryBatches() = batches.values.toList()
  override suspend fun upsertFactoryBatch(item: FactoryBatchEntity) { batches[item.id] = item }
  override suspend fun softDeleteFactoryBatch(id: String, ts: Long) { batches[id]?.let { batches[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreFactoryBatch(id: String) { batches[id]?.let { batches[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteFactoryBatch(id: String) { batches.remove(id) }

  override fun observeProductionEntries() = flowOf(production.values.filter { !it.isDeleted })
  override suspend fun getAllProductionEntries() = production.values.toList()
  override suspend fun upsertProductionEntry(item: ProductionEntryEntity) { production[item.id] = item }
  override suspend fun softDeleteProductionEntry(id: String, ts: Long) { production[id]?.let { production[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreProductionEntry(id: String) { production[id]?.let { production[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteProductionEntry(id: String) { production.remove(id) }

  override fun observeReturns() = flowOf(returns.values.filter { !it.isDeleted })
  override suspend fun getAllReturns() = returns.values.toList()
  override suspend fun upsertReturnLog(item: ReturnLogEntity) { returns[item.id] = item }
  override suspend fun softDeleteReturnLog(id: String, ts: Long) { returns[id]?.let { returns[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreReturnLog(id: String) { returns[id]?.let { returns[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteReturnLog(id: String) { returns.remove(id) }

  override fun observeCustomers() = flowOf(customers.values.filter { !it.isDeleted })
  override suspend fun getAllCustomers() = customers.values.toList()
  override suspend fun upsertCustomer(item: CustomerEntity) { customers[item.id] = item }
  override suspend fun softDeleteCustomer(id: String, ts: Long) { customers[id]?.let { customers[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreCustomer(id: String) { customers[id]?.let { customers[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteCustomer(id: String) { customers.remove(id) }

  override fun observeSales() = flowOf(sales.values.filter { !it.isDeleted })
  override suspend fun getAllSales() = sales.values.toList()
  override suspend fun upsertSale(item: SaleTransactionEntity) { sales[item.id] = item }
  override suspend fun softDeleteSale(id: String, ts: Long) { sales[id]?.let { sales[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreSale(id: String) { sales[id]?.let { sales[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteSale(id: String) { sales.remove(id) }

  override fun observePaymentEntities() = flowOf(entities.values.filter { !it.isDeleted })
  override suspend fun getAllPaymentEntities() = entities.values.toList()
  override suspend fun upsertPaymentEntity(item: PaymentEntityRecord) { entities[item.id] = item }
  override suspend fun softDeletePaymentEntity(id: String, ts: Long) { entities[id]?.let { entities[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restorePaymentEntity(id: String) { entities[id]?.let { entities[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeletePaymentEntity(id: String) { entities.remove(id) }

  override fun observePaymentTransactions() = flowOf(transactions.values.filter { !it.isDeleted })
  override suspend fun getAllPaymentTransactions() = transactions.values.toList()
  override suspend fun upsertPaymentTransaction(item: PaymentTransactionEntity) { transactions[item.id] = item }
  override suspend fun softDeletePaymentTransaction(id: String, ts: Long) { transactions[id]?.let { transactions[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restorePaymentTransaction(id: String) { transactions[id]?.let { transactions[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeletePaymentTransaction(id: String) { transactions.remove(id) }

  override fun observeExpenses() = flowOf(expenses.values.filter { !it.isDeleted })
  override suspend fun getAllExpenses() = expenses.values.toList()
  override suspend fun upsertExpense(item: ExpenseRecordEntity) { expenses[item.id] = item }
  override suspend fun softDeleteExpense(id: String, ts: Long) { expenses[id]?.let { expenses[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreExpense(id: String) { expenses[id]?.let { expenses[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteExpense(id: String) { expenses.remove(id) }

  override fun observeDailyLedger() = flowOf(ledger.values.filter { !it.isDeleted })
  override suspend fun getAllDailyLedger() = ledger.values.toList()
  override suspend fun upsertLedgerEntry(item: DailyLedgerEntity) { ledger[item.id] = item }
  override suspend fun softDeleteLedgerEntry(id: String, ts: Long) { ledger[id]?.let { ledger[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreLedgerEntry(id: String) { ledger[id]?.let { ledger[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteLedgerEntry(id: String) { ledger.remove(id) }

  override fun observeSettlements() = flowOf(settlements.values.filter { !it.isDeleted })
  override suspend fun getAllSettlements() = settlements.values.toList()
  override suspend fun upsertSettlement(item: CalculatorSettlementEntity) { settlements[item.id] = item }
  override suspend fun softDeleteSettlement(id: String, ts: Long) { settlements[id]?.let { settlements[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreSettlement(id: String) { settlements[id]?.let { settlements[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteSettlement(id: String) { settlements.remove(id) }

  override fun observeStockTransfers() = flowOf(stockTransfers.values.filter { !it.isDeleted })
  override suspend fun getAllStockTransfers() = stockTransfers.values.toList()
  override suspend fun upsertStockTransfer(item: StockTransferEntity) { stockTransfers[item.id] = item }
  override suspend fun softDeleteStockTransfer(id: String, ts: Long) { stockTransfers[id]?.let { stockTransfers[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restoreStockTransfer(id: String) { stockTransfers[id]?.let { stockTransfers[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeleteStockTransfer(id: String) { stockTransfers.remove(id) }

  override fun observePaymentTransfers() = flowOf(paymentTransfers.values.filter { !it.isDeleted })
  override suspend fun getAllPaymentTransfers() = paymentTransfers.values.toList()
  override suspend fun upsertPaymentTransfer(item: PaymentTransferEntity) { paymentTransfers[item.id] = item }
  override suspend fun softDeletePaymentTransfer(id: String, ts: Long) { paymentTransfers[id]?.let { paymentTransfers[id] = it.copy(isDeleted = true, deletedAt = ts, syncPending = true) } }
  override suspend fun restorePaymentTransfer(id: String) { paymentTransfers[id]?.let { paymentTransfers[id] = it.copy(isDeleted = false, deletedAt = null, syncPending = true) } }
  override suspend fun hardDeletePaymentTransfer(id: String) { paymentTransfers.remove(id) }

  override fun observeMixCalcs() = flowOf(mixCalcs.values.filter { !it.isDeleted })
  override suspend fun getAllMixCalcs() = mixCalcs.values.toList()
  override suspend fun upsertMixCalc(item: SavedMixCalcEntity) { mixCalcs[item.id] = item }
  override suspend fun hardDeleteMixCalc(id: String) { mixCalcs.remove(id) }

  override fun observeApprovals() = flowOf(approvals.values.toList())
  override suspend fun getAllApprovals() = approvals.values.toList()
  override suspend fun upsertApproval(item: ApprovalRequestEntity) { approvals[item.id] = item }
  override suspend fun deleteApproval(id: String) { approvals.remove(id) }

  override fun observeAccounts() = flowOf(accounts.values.toList())
  override suspend fun getAllAccounts() = accounts.values.toList()
  override suspend fun upsertAccount(item: AppAccountEntity) { accounts[item.id] = item }
  override suspend fun deleteAccount(id: String) { accounts.remove(id) }
  override suspend fun clearAppAccounts() { accounts.clear() }
  override suspend fun clearApprovals() { approvals.clear() }

  override fun observeDeletions() = flowOf(deletions.values.toList())
  override suspend fun getAllDeletions() = deletions.values.toList()
  override suspend fun getAllDeletionRefs() = deletions.values.map { DeletionTombstoneRef(it.id, it.recordId, it.collection) }
  override suspend fun getUnsyncedDeletionMeta() = deletions.values.filter { !it.syncedToCloud }.map {
    DeletionMetaRow(it.id, it.recordId, it.collection, it.recordType, it.deletedAt, it.expiresAt, it.syncedToCloud, it.displayName, it.displayDetail, it.displayAmount, it.deletedBy, it.deletionVersion, it.groupId, it.snapshotJson?.length ?: 0, it.snapshotJson?.toByteArray()?.size ?: 0, it.snapshotJson == null)
  }
  override suspend fun readDeletionSnapshotChunk(id: String, startPos: Int, len: Int): String? {
    val snap = deletions[id]?.snapshotJson ?: return null
    val s = (startPos - 1).coerceAtLeast(0)
    return if (s >= snap.length) "" else snap.substring(s, minOf(snap.length, s + len))
  }
  override suspend fun upsertDeletion(item: DeletionTombstoneEntity) { deletions[item.id] = item }
  override suspend fun deleteDeletion(id: String) { deletions.entries.removeAll { it.value.id == id || it.value.recordId == id } }
  override suspend fun purgeExpiredDeletions(now: Long) { deletions.entries.removeAll { it.value.expiresAt < now } }
  override suspend fun clearAllDeletions() { deletions.clear() }

  override suspend fun getAllActivePhotos() = photos.values.filter { !it.deleted }
  override suspend fun getAllPhotos() = photos.values.toList()
  override suspend fun getPhotoMetaAll() = photos.values.map {
    PhotoMetaRow(it.photoKey, it.docId, it.deleted, it.updatedAt, it.syncPending, it.data?.length ?: 0, it.data?.toByteArray()?.size ?: 0, it.data == null)
  }
  override suspend fun getPendingPhotoMeta() = getPhotoMetaAll().filter { it.syncPending }
  override suspend fun readPhotoDataChunk(photoKey: String, startPos: Int, len: Int): String? {
    val d = photos[photoKey]?.data ?: return null
    val s = (startPos - 1).coerceAtLeast(0)
    return if (s >= d.length) "" else d.substring(s, minOf(d.length, s + len))
  }
  override suspend fun upsertPhoto(item: PhotoRecordEntity) { photos[item.photoKey] = item }
  override suspend fun hardDeletePhoto(key: String) { photos.remove(key) }
  override suspend fun clearPhotos() { photos.clear() }

  override suspend fun clearProductionEntries() { production.clear() }
  override suspend fun clearSales() { sales.clear() }
  override suspend fun clearPaymentTransactions() { transactions.clear() }
  override suspend fun clearExpenses() { expenses.clear() }
  override suspend fun clearReturns() { returns.clear() }
  override suspend fun clearDailyLedger() { ledger.clear() }
  override suspend fun clearSettlements() { settlements.clear() }
  override suspend fun clearStockTransfers() { stockTransfers.clear() }
  override suspend fun clearPaymentTransfers() { paymentTransfers.clear() }
  override suspend fun clearFactoryBatches() { batches.clear() }
  override suspend fun clearPurchases() { purchases.clear() }
  override suspend fun clearCustomers() { customers.clear() }
  override suspend fun clearPaymentEntities() { entities.clear() }
  override suspend fun clearRawMaterials() { materials.clear() }
  override suspend fun clearFormulas() { formulas.clear() }
  override suspend fun clearStores() { stores.clear() }
  override suspend fun clearMixCalcs() { mixCalcs.clear() }
  override suspend fun clearKvStore() { kv.clear() }
}
