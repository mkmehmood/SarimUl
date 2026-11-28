package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class TableSyncCounts(
  val totalCount: Int,
  val activeCount: Int,
  val deletedCount: Int,
  val pendingCount: Int,
)

data class DeletionTombstoneRef(
  val id: String,
  val recordId: String,
  val collection: String,
)

data class KvMetaRow(
  val fullKey: String,
  val userKey: String,
  val uid: String,
  val collection: String,
  val rowType: String,
  val encrypted: Int,
  val ts: Long,
  val createdAt: Long,
  val charLen: Int,
  val byteLen: Int,
  val isValueNull: Boolean,
)

data class PhotoMetaRow(
  val photoKey: String,
  val docId: String,
  val deleted: Boolean,
  val updatedAt: Long,
  val syncPending: Boolean,
  val charLen: Int,
  val byteLen: Int,
  val isDataNull: Boolean,
)

data class OutboxMetaRow(
  val id: String,
  val uid: String,
  val action: String,
  val collection: String,
  val docId: String,
  val createdAt: Long,
  val attempts: Int,
  val lastAttempt: Long,
  val charLen: Int,
  val byteLen: Int,
  val isPayloadNull: Boolean,
)

data class DeletionMetaRow(
  val id: String,
  val recordId: String,
  val collection: String,
  val recordType: String,
  val deletedAt: Long,
  val expiresAt: Long,
  val syncedToCloud: Boolean,
  val displayName: String?,
  val displayDetail: String?,
  val displayAmount: String?,
  val deletedBy: String,
  val deletionVersion: String,
  val groupId: String?,
  val charLen: Int,
  val byteLen: Int,
  val isSnapshotNull: Boolean,
)

@Dao
interface SarimDao {
  // =====================================================================
  // SQLite Infrastructure (schema_version, kv_store, ndapp_outbox)
  // =====================================================================

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertSchemaVersion(item: SchemaVersionEntity)

  @Query("SELECT * FROM schema_version LIMIT 1")
  suspend fun getSchemaVersion(): SchemaVersionEntity?

  @Query("SELECT * FROM kv_store WHERE full_key = :fullKey AND (value IS NULL OR LENGTH(CAST(value AS BLOB)) <= 750000) LIMIT 1")
  suspend fun getKv(fullKey: String): KvStoreEntity?

  @Query("SELECT * FROM kv_store WHERE user_key = :userKey AND (value IS NULL OR LENGTH(CAST(value AS BLOB)) <= 750000) LIMIT 1")
  suspend fun getKvByUserKey(userKey: String): KvStoreEntity?

  @Query("SELECT * FROM kv_store WHERE value IS NULL OR LENGTH(CAST(value AS BLOB)) <= 750000")
  suspend fun getAllKv(): List<KvStoreEntity>

  @Query("SELECT full_key FROM kv_store ORDER BY full_key ASC")
  fun observeAllKvKeys(): Flow<List<String>>

  @Query("SELECT * FROM kv_store WHERE user_key IN ('categories', 'field_expense_categories', 'commission_categories', 'reps')")
  fun observeCategoryAndRepKv(): Flow<List<KvStoreEntity>>

  @Query("SELECT full_key AS fullKey, user_key AS userKey, uid, collection, row_type AS rowType, encrypted, ts, created_at AS createdAt, COALESCE(LENGTH(value), 0) AS charLen, COALESCE(LENGTH(CAST(value AS BLOB)), 0) AS byteLen, (value IS NULL) AS isValueNull FROM kv_store ORDER BY full_key ASC")
  suspend fun getKvMetaAll(): List<KvMetaRow>

  @Query("SELECT full_key AS fullKey, user_key AS userKey, uid, collection, row_type AS rowType, encrypted, ts, created_at AS createdAt, COALESCE(LENGTH(value), 0) AS charLen, COALESCE(LENGTH(CAST(value AS BLOB)), 0) AS byteLen, (value IS NULL) AS isValueNull FROM kv_store WHERE full_key = :fullKey LIMIT 1")
  suspend fun getKvMetaByFullKey(fullKey: String): KvMetaRow?

  @Query("SELECT full_key AS fullKey, user_key AS userKey, uid, collection, row_type AS rowType, encrypted, ts, created_at AS createdAt, COALESCE(LENGTH(value), 0) AS charLen, COALESCE(LENGTH(CAST(value AS BLOB)), 0) AS byteLen, (value IS NULL) AS isValueNull FROM kv_store WHERE user_key = :userKey LIMIT 1")
  suspend fun getKvMetaByUserKey(userKey: String): KvMetaRow?

  @Query("SELECT SUBSTR(value, :startPos, :len) FROM kv_store WHERE full_key = :fullKey LIMIT 1")
  suspend fun readKvValueChunk(fullKey: String, startPos: Int, len: Int): String?

  @Query("SELECT COUNT(*) FROM kv_store")
  suspend fun countKv(): Int

  @Query("SELECT COUNT(*) FROM kv_store WHERE user_key LIKE '%\\_dirty' ESCAPE '\\'")
  suspend fun countDirtyKv(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertKv(item: KvStoreEntity)

  @Query("DELETE FROM kv_store WHERE full_key = :fullKey")
  suspend fun deleteKv(fullKey: String)

  @Query("SELECT * FROM ndapp_outbox WHERE payload IS NULL OR LENGTH(CAST(payload AS BLOB)) <= 750000 ORDER BY created_at ASC")
  suspend fun getOutboxAll(): List<NdappOutboxEntity>

  @Query("SELECT id, uid, action, collection, doc_id AS docId, created_at AS createdAt, attempts, last_attempt AS lastAttempt, COALESCE(LENGTH(payload), 0) AS charLen, COALESCE(LENGTH(CAST(payload AS BLOB)), 0) AS byteLen, (payload IS NULL) AS isPayloadNull FROM ndapp_outbox ORDER BY created_at ASC")
  suspend fun getOutboxMetaAll(): List<OutboxMetaRow>

  @Query("SELECT SUBSTR(payload, :startPos, :len) FROM ndapp_outbox WHERE id = :id LIMIT 1")
  suspend fun readOutboxPayloadChunk(id: String, startPos: Int, len: Int): String?

  @Query("SELECT COUNT(*) FROM ndapp_outbox")
  suspend fun countOutbox(): Int

  @Query("SELECT COUNT(*) FROM ndapp_outbox")
  fun observeOutboxCount(): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertOutbox(item: NdappOutboxEntity)

  @Query("UPDATE ndapp_outbox SET attempts = attempts + 1, last_attempt = :ts WHERE id = :id")
  suspend fun bumpOutboxAttempt(id: String, ts: Long = System.currentTimeMillis())

  @Query("DELETE FROM ndapp_outbox WHERE id = :id")
  suspend fun removeOutbox(id: String)

  @Query("DELETE FROM ndapp_outbox WHERE collection = :collection AND doc_id = :docId")
  suspend fun removeOutboxForDoc(collection: String, docId: String)

  @Query("DELETE FROM ndapp_outbox")
  suspend fun clearOutbox()

  // =====================================================================
  // Lightweight Pending & Structure Count Projections (Zero-Blob Queries)
  // =====================================================================

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM stores")
  suspend fun getStoreCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM inventory")
  suspend fun getRawMaterialCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM raw_material_purchases")
  suspend fun getPurchaseCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM formulas")
  suspend fun getFormulaCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM factory")
  suspend fun getFactoryBatchCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM production")
  suspend fun getProductionCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM returns")
  suspend fun getReturnCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM customers WHERE isRepCustomer = :isRep")
  suspend fun getCustomerCounts(isRep: Boolean): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM sales WHERE isRepSale = :isRep")
  suspend fun getSaleCounts(isRep: Boolean): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM entities")
  suspend fun getPaymentEntityCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM transactions")
  suspend fun getPaymentTransactionCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM expenses")
  suspend fun getExpenseCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM daily_ledger")
  suspend fun getDailyLedgerCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM calculator")
  suspend fun getSettlementCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM stock_transfers")
  suspend fun getStockTransferCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM payment_transfers")
  suspend fun getPaymentTransferCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN isDeleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM saved_mix_calcs")
  suspend fun getMixCalcCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COUNT(*) AS activeCount, 0 AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM pending")
  suspend fun getApprovalCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COUNT(*) AS activeCount, 0 AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM app_accounts")
  suspend fun getAccountCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COUNT(*) AS activeCount, 0 AS deletedCount, COALESCE(SUM(CASE WHEN syncedToCloud = 0 THEN 1 ELSE 0 END), 0) AS pendingCount FROM deletions")
  suspend fun getDeletionCounts(): TableSyncCounts

  @Query("SELECT COUNT(*) AS totalCount, COALESCE(SUM(CASE WHEN deleted = 0 THEN 1 ELSE 0 END), 0) AS activeCount, COALESCE(SUM(CASE WHEN deleted = 1 THEN 1 ELSE 0 END), 0) AS deletedCount, COALESCE(SUM(CASE WHEN syncPending = 1 THEN 1 ELSE 0 END), 0) AS pendingCount FROM photos")
  suspend fun getPhotoCounts(): TableSyncCounts

  // =====================================================================
  // Soft-Deleted Recycle Bin Queries (Only deleted rows within cutoff)
  // =====================================================================

  @Query("SELECT * FROM sales WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedSalesSince(cutoff: Long): List<SaleTransactionEntity>

  @Query("SELECT * FROM production WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedProductionSince(cutoff: Long): List<ProductionEntryEntity>

  @Query("SELECT * FROM calculator WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedSettlementsSince(cutoff: Long): List<CalculatorSettlementEntity>

  @Query("SELECT * FROM factory WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedFactoryBatchesSince(cutoff: Long): List<FactoryBatchEntity>

  @Query("SELECT * FROM transactions WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedPaymentTransactionsSince(cutoff: Long): List<PaymentTransactionEntity>

  @Query("SELECT * FROM expenses WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedExpensesSince(cutoff: Long): List<ExpenseRecordEntity>

  @Query("SELECT * FROM returns WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedReturnsSince(cutoff: Long): List<ReturnLogEntity>

  @Query("SELECT * FROM daily_ledger WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedDailyLedgerSince(cutoff: Long): List<DailyLedgerEntity>

  @Query("SELECT * FROM formulas WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedFormulasSince(cutoff: Long): List<FormulaEntity>

  @Query("SELECT * FROM inventory WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedRawMaterialsSince(cutoff: Long): List<RawMaterialEntity>

  @Query("SELECT * FROM raw_material_purchases WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedPurchasesSince(cutoff: Long): List<RawMaterialPurchaseEntity>

  @Query("SELECT * FROM customers WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedCustomersSince(cutoff: Long): List<CustomerEntity>

  @Query("SELECT * FROM entities WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedPaymentEntitiesSince(cutoff: Long): List<PaymentEntityRecord>

  @Query("SELECT * FROM stock_transfers WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedStockTransfersSince(cutoff: Long): List<StockTransferEntity>

  @Query("SELECT * FROM payment_transfers WHERE isDeleted = 1 AND COALESCE(deletedAt, 0) >= :cutoff")
  suspend fun getDeletedPaymentTransfersSince(cutoff: Long): List<PaymentTransferEntity>

  // =====================================================================
  // Stores (stores/list)
  // =====================================================================

  @Query("SELECT * FROM stores WHERE isDeleted = 0 ORDER BY name ASC")
  fun observeStores(): Flow<List<StoreEntity>>

  @Query("SELECT * FROM stores")
  suspend fun getAllStores(): List<StoreEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertStore(store: StoreEntity)

  @Query("UPDATE stores SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE `key` = :key")
  suspend fun softDeleteStore(key: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE stores SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE `key` = :key")
  suspend fun restoreStore(key: String)

  @Query("DELETE FROM stores WHERE `key` = :key")
  suspend fun hardDeleteStore(key: String)

  // =====================================================================
  // Raw Materials (inventory) & Purchases (inventory purchase lots)
  // =====================================================================

  @Query("SELECT * FROM inventory WHERE isDeleted = 0 ORDER BY name ASC")
  fun observeRawMaterials(): Flow<List<RawMaterialEntity>>

  @Query("SELECT * FROM inventory")
  suspend fun getAllRawMaterials(): List<RawMaterialEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertRawMaterial(item: RawMaterialEntity)

  @Query("UPDATE inventory SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteRawMaterial(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE inventory SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreRawMaterial(id: String)

  @Query("DELETE FROM inventory WHERE id = :id")
  suspend fun hardDeleteRawMaterial(id: String)

  @Query("SELECT * FROM raw_material_purchases WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observePurchases(): Flow<List<RawMaterialPurchaseEntity>>

  @Query("SELECT * FROM raw_material_purchases")
  suspend fun getAllPurchases(): List<RawMaterialPurchaseEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertPurchase(item: RawMaterialPurchaseEntity)

  @Query("UPDATE raw_material_purchases SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeletePurchase(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE raw_material_purchases SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restorePurchase(id: String)

  @Query("DELETE FROM raw_material_purchases WHERE id = :id")
  suspend fun hardDeletePurchase(id: String)

  // =====================================================================
  // Formulas (formulas/config) & Factory History (factory)
  // =====================================================================

  @Query("SELECT * FROM formulas WHERE isDeleted = 0 ORDER BY name ASC")
  fun observeFormulas(): Flow<List<FormulaEntity>>

  @Query("SELECT * FROM formulas")
  suspend fun getAllFormulas(): List<FormulaEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertFormula(item: FormulaEntity)

  @Query("UPDATE formulas SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteFormula(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE formulas SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreFormula(id: String)

  @Query("DELETE FROM formulas WHERE id = :id")
  suspend fun hardDeleteFormula(id: String)

  @Query("SELECT * FROM factory WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observeFactoryBatches(): Flow<List<FactoryBatchEntity>>

  @Query("SELECT * FROM factory")
  suspend fun getAllFactoryBatches(): List<FactoryBatchEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertFactoryBatch(item: FactoryBatchEntity)

  @Query("UPDATE factory SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteFactoryBatch(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE factory SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreFactoryBatch(id: String)

  @Query("DELETE FROM factory WHERE id = :id")
  suspend fun hardDeleteFactoryBatch(id: String)

  // =====================================================================
  // Production (production) & Returns (returns)
  // =====================================================================

  @Query("SELECT * FROM production WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observeProductionEntries(): Flow<List<ProductionEntryEntity>>

  @Query("SELECT * FROM production")
  suspend fun getAllProductionEntries(): List<ProductionEntryEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertProductionEntry(item: ProductionEntryEntity)

  @Query("UPDATE production SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteProductionEntry(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE production SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreProductionEntry(id: String)

  @Query("DELETE FROM production WHERE id = :id")
  suspend fun hardDeleteProductionEntry(id: String)

  @Query("SELECT * FROM returns WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observeReturns(): Flow<List<ReturnLogEntity>>

  @Query("SELECT * FROM returns")
  suspend fun getAllReturns(): List<ReturnLogEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertReturnLog(item: ReturnLogEntity)

  @Query("UPDATE returns SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteReturnLog(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE returns SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreReturnLog(id: String)

  @Query("DELETE FROM returns WHERE id = :id")
  suspend fun hardDeleteReturnLog(id: String)

  // =====================================================================
  // Customers (customers + clients) & Sales (sales + rep)
  // =====================================================================

  @Query("SELECT * FROM customers WHERE isDeleted = 0 ORDER BY name ASC")
  fun observeCustomers(): Flow<List<CustomerEntity>>

  @Query("SELECT * FROM customers")
  suspend fun getAllCustomers(): List<CustomerEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertCustomer(item: CustomerEntity)

  @Query("UPDATE customers SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteCustomer(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE customers SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreCustomer(id: String)

  @Query("DELETE FROM customers WHERE id = :id")
  suspend fun hardDeleteCustomer(id: String)

  @Query("SELECT * FROM sales WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observeSales(): Flow<List<SaleTransactionEntity>>

  @Query("SELECT * FROM sales")
  suspend fun getAllSales(): List<SaleTransactionEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertSale(item: SaleTransactionEntity)

  @Query("UPDATE sales SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteSale(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE sales SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreSale(id: String)

  @Query("DELETE FROM sales WHERE id = :id")
  suspend fun hardDeleteSale(id: String)

  // =====================================================================
  // Entities (entities), Transactions (transactions), Expenses (expenses)
  // =====================================================================

  @Query("SELECT * FROM entities WHERE isDeleted = 0 ORDER BY name ASC")
  fun observePaymentEntities(): Flow<List<PaymentEntityRecord>>

  @Query("SELECT * FROM entities")
  suspend fun getAllPaymentEntities(): List<PaymentEntityRecord>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertPaymentEntity(item: PaymentEntityRecord)

  @Query("UPDATE entities SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeletePaymentEntity(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE entities SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restorePaymentEntity(id: String)

  @Query("DELETE FROM entities WHERE id = :id")
  suspend fun hardDeletePaymentEntity(id: String)

  @Query("SELECT * FROM transactions WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observePaymentTransactions(): Flow<List<PaymentTransactionEntity>>

  @Query("SELECT * FROM transactions")
  suspend fun getAllPaymentTransactions(): List<PaymentTransactionEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertPaymentTransaction(item: PaymentTransactionEntity)

  @Query("UPDATE transactions SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeletePaymentTransaction(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE transactions SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restorePaymentTransaction(id: String)

  @Query("DELETE FROM transactions WHERE id = :id")
  suspend fun hardDeletePaymentTransaction(id: String)

  @Query("SELECT * FROM expenses WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observeExpenses(): Flow<List<ExpenseRecordEntity>>

  @Query("SELECT * FROM expenses")
  suspend fun getAllExpenses(): List<ExpenseRecordEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertExpense(item: ExpenseRecordEntity)

  @Query("UPDATE expenses SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteExpense(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE expenses SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreExpense(id: String)

  @Query("DELETE FROM expenses WHERE id = :id")
  suspend fun hardDeleteExpense(id: String)

  // =====================================================================
  // Daily Ledger (daily_ledger/entries) & Calculator (calculator)
  // =====================================================================

  @Query("SELECT * FROM daily_ledger WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observeDailyLedger(): Flow<List<DailyLedgerEntity>>

  @Query("SELECT * FROM daily_ledger")
  suspend fun getAllDailyLedger(): List<DailyLedgerEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertLedgerEntry(item: DailyLedgerEntity)

  @Query("UPDATE daily_ledger SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteLedgerEntry(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE daily_ledger SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreLedgerEntry(id: String)

  @Query("DELETE FROM daily_ledger WHERE id = :id")
  suspend fun hardDeleteLedgerEntry(id: String)

  @Query("SELECT * FROM calculator WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observeSettlements(): Flow<List<CalculatorSettlementEntity>>

  @Query("SELECT * FROM calculator")
  suspend fun getAllSettlements(): List<CalculatorSettlementEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertSettlement(item: CalculatorSettlementEntity)

  @Query("UPDATE calculator SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteSettlement(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE calculator SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreSettlement(id: String)

  @Query("DELETE FROM calculator WHERE id = :id")
  suspend fun hardDeleteSettlement(id: String)

  // =====================================================================
  // Transfers, Saved Mix Calcs, Approvals (pending), Accounts
  // =====================================================================

  @Query("SELECT * FROM stock_transfers WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observeStockTransfers(): Flow<List<StockTransferEntity>>

  @Query("SELECT * FROM stock_transfers")
  suspend fun getAllStockTransfers(): List<StockTransferEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertStockTransfer(item: StockTransferEntity)

  @Query("UPDATE stock_transfers SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeleteStockTransfer(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE stock_transfers SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restoreStockTransfer(id: String)

  @Query("DELETE FROM stock_transfers WHERE id = :id")
  suspend fun hardDeleteStockTransfer(id: String)

  @Query("SELECT * FROM payment_transfers WHERE isDeleted = 0 ORDER BY dateEpoch DESC, updatedAt DESC")
  fun observePaymentTransfers(): Flow<List<PaymentTransferEntity>>

  @Query("SELECT * FROM payment_transfers")
  suspend fun getAllPaymentTransfers(): List<PaymentTransferEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertPaymentTransfer(item: PaymentTransferEntity)

  @Query("UPDATE payment_transfers SET isDeleted = 1, deletedAt = :ts, syncPending = 1 WHERE id = :id")
  suspend fun softDeletePaymentTransfer(id: String, ts: Long = System.currentTimeMillis())

  @Query("UPDATE payment_transfers SET isDeleted = 0, deletedAt = NULL, syncPending = 1 WHERE id = :id")
  suspend fun restorePaymentTransfer(id: String)

  @Query("DELETE FROM payment_transfers WHERE id = :id")
  suspend fun hardDeletePaymentTransfer(id: String)

  @Query("SELECT * FROM saved_mix_calcs WHERE isDeleted = 0 ORDER BY createdAt DESC")
  fun observeMixCalcs(): Flow<List<SavedMixCalcEntity>>

  @Query("SELECT * FROM saved_mix_calcs")
  suspend fun getAllMixCalcs(): List<SavedMixCalcEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMixCalc(item: SavedMixCalcEntity)

  @Query("DELETE FROM saved_mix_calcs WHERE id = :id")
  suspend fun hardDeleteMixCalc(id: String)

  @Query("SELECT * FROM pending ORDER BY createdAt DESC")
  fun observeApprovals(): Flow<List<ApprovalRequestEntity>>

  @Query("SELECT * FROM pending")
  suspend fun getAllApprovals(): List<ApprovalRequestEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertApproval(item: ApprovalRequestEntity)

  @Query("DELETE FROM pending WHERE id = :id")
  suspend fun deleteApproval(id: String)

  @Query("SELECT * FROM app_accounts ORDER BY createdAt DESC")
  fun observeAccounts(): Flow<List<AppAccountEntity>>

  @Query("SELECT * FROM app_accounts")
  suspend fun getAllAccounts(): List<AppAccountEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAccount(item: AppAccountEntity)

  @Query("DELETE FROM app_accounts WHERE id = :id")
  suspend fun deleteAccount(id: String)

  @Query("DELETE FROM app_accounts")
  suspend fun clearAppAccounts()

  @Query("DELETE FROM pending")
  suspend fun clearApprovals()

  // =====================================================================
  // Deletions (deletions) & Photos (photos)
  // =====================================================================

  @Query("SELECT * FROM deletions WHERE snapshotJson IS NULL OR LENGTH(CAST(snapshotJson AS BLOB)) <= 750000 ORDER BY deletedAt DESC")
  fun observeDeletions(): Flow<List<DeletionTombstoneEntity>>

  @Query("SELECT * FROM deletions WHERE snapshotJson IS NULL OR LENGTH(CAST(snapshotJson AS BLOB)) <= 750000")
  suspend fun getAllDeletions(): List<DeletionTombstoneEntity>

  @Query("SELECT id, recordId, collection FROM deletions")
  suspend fun getAllDeletionRefs(): List<DeletionTombstoneRef>

  @Query("SELECT id, recordId, collection, recordType, deletedAt, expiresAt, syncedToCloud, displayName, displayDetail, displayAmount, deletedBy, deletionVersion, groupId, COALESCE(LENGTH(snapshotJson), 0) AS charLen, COALESCE(LENGTH(CAST(snapshotJson AS BLOB)), 0) AS byteLen, (snapshotJson IS NULL) AS isSnapshotNull FROM deletions WHERE syncedToCloud = 0")
  suspend fun getUnsyncedDeletionMeta(): List<DeletionMetaRow>

  @Query("SELECT SUBSTR(snapshotJson, :startPos, :len) FROM deletions WHERE id = :id LIMIT 1")
  suspend fun readDeletionSnapshotChunk(id: String, startPos: Int, len: Int): String?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertDeletion(item: DeletionTombstoneEntity)

  @Query("DELETE FROM deletions WHERE id = :id OR recordId = :id")
  suspend fun deleteDeletion(id: String)

  @Query("DELETE FROM deletions WHERE expiresAt < :now")
  suspend fun purgeExpiredDeletions(now: Long = System.currentTimeMillis())

  @Query("DELETE FROM deletions")
  suspend fun clearAllDeletions()

  @Query("SELECT * FROM photos WHERE deleted = 0 AND (data IS NULL OR LENGTH(CAST(data AS BLOB)) <= 750000)")
  suspend fun getAllActivePhotos(): List<PhotoRecordEntity>

  @Query("SELECT * FROM photos WHERE data IS NULL OR LENGTH(CAST(data AS BLOB)) <= 750000")
  suspend fun getAllPhotos(): List<PhotoRecordEntity>

  @Query("SELECT `key` AS photoKey, docId, deleted, updatedAt, syncPending, COALESCE(LENGTH(data), 0) AS charLen, COALESCE(LENGTH(CAST(data AS BLOB)), 0) AS byteLen, (data IS NULL) AS isDataNull FROM photos")
  suspend fun getPhotoMetaAll(): List<PhotoMetaRow>

  @Query("SELECT `key` AS photoKey, docId, deleted, updatedAt, syncPending, COALESCE(LENGTH(data), 0) AS charLen, COALESCE(LENGTH(CAST(data AS BLOB)), 0) AS byteLen, (data IS NULL) AS isDataNull FROM photos WHERE syncPending = 1")
  suspend fun getPendingPhotoMeta(): List<PhotoMetaRow>

  @Query("SELECT SUBSTR(data, :startPos, :len) FROM photos WHERE `key` = :photoKey LIMIT 1")
  suspend fun readPhotoDataChunk(photoKey: String, startPos: Int, len: Int): String?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertPhoto(item: PhotoRecordEntity)

  @Query("DELETE FROM photos WHERE `key` = :key")
  suspend fun hardDeletePhoto(key: String)

  @Query("DELETE FROM photos")
  suspend fun clearPhotos()

  // =====================================================================
  // Clear all transactional tables (for Restore, Sign-Out, or Close Financial Year)
  // =====================================================================

  @Query("DELETE FROM production")
  suspend fun clearProductionEntries()

  @Query("DELETE FROM sales")
  suspend fun clearSales()

  @Query("DELETE FROM transactions")
  suspend fun clearPaymentTransactions()

  @Query("DELETE FROM expenses")
  suspend fun clearExpenses()

  @Query("DELETE FROM returns")
  suspend fun clearReturns()

  @Query("DELETE FROM daily_ledger")
  suspend fun clearDailyLedger()

  @Query("DELETE FROM calculator")
  suspend fun clearSettlements()

  @Query("DELETE FROM stock_transfers")
  suspend fun clearStockTransfers()

  @Query("DELETE FROM payment_transfers")
  suspend fun clearPaymentTransfers()

  @Query("DELETE FROM factory")
  suspend fun clearFactoryBatches()

  @Query("DELETE FROM raw_material_purchases")
  suspend fun clearPurchases()

  @Query("DELETE FROM customers")
  suspend fun clearCustomers()

  @Query("DELETE FROM entities")
  suspend fun clearPaymentEntities()

  @Query("DELETE FROM inventory")
  suspend fun clearRawMaterials()

  @Query("DELETE FROM formulas")
  suspend fun clearFormulas()

  @Query("DELETE FROM stores")
  suspend fun clearStores()

  @Query("DELETE FROM saved_mix_calcs")
  suspend fun clearMixCalcs()

  @Query("DELETE FROM kv_store")
  suspend fun clearKvStore()
}
