package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.SarimUuid
import com.example.util.SarimFormat

// =====================================================================
// SQLite Meta & Infrastructure Tables (mirrors modules/business.js)
// =====================================================================

@Entity(tableName = "schema_version")
data class SchemaVersionEntity(
  @PrimaryKey val version: Int = 3,
  @ColumnInfo(name = "upgraded_at") val upgradedAt: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "kv_store",
  indices = [
    Index(value = ["uid", "user_key"], name = "idx_kv_uid_key"),
    Index(value = ["uid", "collection"], name = "idx_kv_collection"),
    Index(value = ["uid", "row_type"], name = "idx_kv_row_type"),
    Index(value = ["ts"], name = "idx_kv_ts"),
  ],
)
data class KvStoreEntity(
  @PrimaryKey @ColumnInfo(name = "full_key") val fullKey: String,
  @ColumnInfo(name = "user_key") val userKey: String,
  val uid: String = "",
  val collection: String = "",
  @ColumnInfo(name = "row_type") val rowType: String = "config", // device, collection, settings, meta, config
  val encrypted: Int = 0,
  val value: String? = null,
  val ts: Long = System.currentTimeMillis(),
  @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "ndapp_outbox",
  indices = [
    Index(value = ["uid", "created_at"], name = "idx_outbox_uid"),
    Index(value = ["uid", "collection"], name = "idx_outbox_col"),
  ],
)
data class NdappOutboxEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_OB),
  val uid: String = "",
  val action: String, // "set", "set-doc", "delete"
  val collection: String = "",
  @ColumnInfo(name = "doc_id") val docId: String = "",
  val payload: String? = null,
  @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
  val attempts: Int = 0,
  @ColumnInfo(name = "last_attempt") val lastAttempt: Long = 0L,
)

// =====================================================================
// Business & Firestore-Synced Tables (mirrors RECORD_STORES & AUX_STATE)
// =====================================================================

/** Firestore: users/{uid}/stores/list -> stores[] ({ key, name, formulaId, salePrice }) */
@Entity(tableName = "stores")
data class StoreEntity(
  @PrimaryKey @ColumnInfo(name = "key") val storeKey: String,
  val name: String,
  val formulaId: String,
  @ColumnInfo(name = "salePrice") val salePricePerKg: Double,
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/inventory/{id} (isPurchaseLot = false) */
@Entity(tableName = "inventory")
data class RawMaterialEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_MAT),
  val name: String,
  @ColumnInfo(name = "purchaseUnitName") val unitName: String = "bag",
  @ColumnInfo(name = "conversionFactor") val conversionFactorKg: Double = 1.0,
  @ColumnInfo(name = "quantity") val stockKg: Double = 0.0,
  val collectedKg: Double = 0.0,
  @ColumnInfo(name = "cost") val avgCostPerKg: Double = 0.0,
  @ColumnInfo(name = "totalPayable") val pendingSupplierAmount: Double = 0.0,
  val unit: String = "kg",
  val paymentStatus: String = "paid",
  val supplierId: String? = null,
  val supplierName: String? = null,
  val purchasesTracked: Boolean = true,
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/inventory/{id} (isPurchaseLot = true, materialRefId -> material.id) */
@Entity(tableName = "raw_material_purchases")
data class RawMaterialPurchaseEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_LOT),
  @ColumnInfo(name = "materialRefId") val materialId: String,
  val materialName: String,
  val dateEpoch: Long,
  @ColumnInfo(name = "purchaseUnitName") val unitName: String,
  @ColumnInfo(name = "purchaseQuantity") val unitsBought: Double,
  @ColumnInfo(name = "conversionFactor") val weightPerUnitKg: Double,
  @ColumnInfo(name = "purchaseCost") val costPerUnit: Double,
  @ColumnInfo(name = "purchaseKg") val totalKg: Double,
  @ColumnInfo(name = "totalValue") val totalAmount: Double,
  @ColumnInfo(name = "supplierType") val supplierMode: String = "NONE", // NONE, EXISTING, NEW
  val supplierId: String? = null,
  val supplierName: String? = null,
  val paymentStatus: String = "pending",
  val totalPayable: Double = 0.0,
  val purchaseDate: String = SarimFormat.dateInput(dateEpoch),
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/formulas/config -> formulas[] ({ id, name, additionalCost, factor, ingredients, savedCalcs }) */
@Entity(tableName = "formulas")
data class FormulaEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_FORMULA),
  val name: String,
  @ColumnInfo(name = "additionalCost") val additionalCostPerUnit: Double = 0.0,
  @ColumnInfo(name = "factor") val costAdjustmentFactor: Double = 1.0,
  @ColumnInfo(name = "ingredients") val ingredientsJson: String = "[]",
  val unitsAvailable: Double = 0.0,
  val unitsUsed: Double = 0.0,
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/factory/{id} ({ id, date, time, store, formulaId, formulaName, units, rawMaterialsUsed, materialsCost, additionalCost, totalCost, materialsUsed }) */
@Entity(tableName = "factory")
data class FactoryBatchEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_FPROD),
  val dateEpoch: Long,
  val formulaId: String,
  val formulaName: String,
  @ColumnInfo(name = "units") val unitsProduced: Double,
  @ColumnInfo(name = "rawMaterialsUsed") val rawUsedKg: Double,
  val outputKg: Double,
  val materialsCost: Double,
  val additionalCost: Double,
  val totalCost: Double,
  val costPerUnit: Double,
  val store: String = "",
  val dateStr: String = SarimFormat.dateInput(dateEpoch),
  val timeStr: String = "",
  val materialsUsedJson: String = "[]",
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/production/{id} ({ id, date, time, store, grossWt, contWt, net, formulaUnits, formulaCost, cp, sp, totalSale, profit, photoKeys, isTransfer, isReturn }) */
@Entity(tableName = "production")
data class ProductionEntryEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_PROD),
  val dateEpoch: Long,
  @ColumnInfo(name = "store") val storeKey: String,
  val storeName: String,
  val formulaId: String,
  val formulaName: String,
  @ColumnInfo(name = "grossWt") val grossWeightKg: Double,
  @ColumnInfo(name = "contWt") val containerWeightKg: Double,
  @ColumnInfo(name = "net") val netWeightKg: Double,
  @ColumnInfo(name = "formulaUnits") val formulaUnitsUsed: Double,
  val formulaUnitCost: Double,
  @ColumnInfo(name = "formulaCost") val totalFormulaCost: Double,
  @ColumnInfo(name = "cp") val costPerKg: Double,
  @ColumnInfo(name = "sp") val salePricePerKg: Double,
  val profitPerKg: Double,
  @ColumnInfo(name = "totalSale") val totalValue: Double,
  @ColumnInfo(name = "profit") val totalProfit: Double,
  @ColumnInfo(name = "photoKeys") val photoUrisJson: String = "[]",
  val dateStr: String = SarimFormat.dateInput(dateEpoch),
  val timeStr: String = "",
  val paymentStatus: String = "CASH",
  val formulaMaterialsJson: String = "[]",
  val isReturn: Boolean = false,
  val returnedBy: String? = null,
  val returnNote: String? = null,
  val returnLogId: String? = null,
  @ColumnInfo(name = "editHistory") val editHistoryJson: String = "[]",
  val createdBy: String? = null,
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/customers/{id} (isRepCustomer = false) OR users/{uid}/clients/{id} (isRepCustomer = true) */
@Entity(tableName = "customers")
data class CustomerEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_CUST),
  val name: String,
  val phone: String = "",
  val address: String = "",
  val oldDebit: Double = 0.0,
  val customSalePrice: Double? = null,
  val ledgerPage: String = "",
  val photoUri: String = "",
  val isRepCustomer: Boolean = false,
  @ColumnInfo(name = "salesRep") val repName: String = "",
  val currentCredit: Double = 0.0,
  val totalQuantityKg: Double = 0.0,
  val totalSaleValue: Double = 0.0,
  val totalReceived: Double = 0.0,
  @ColumnInfo(name = "editHistory") val editHistoryJson: String = "[]",
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/sales/{id} (isRepSale = false) OR users/{uid}/rep/{id} (isRepSale = true) */
@Entity(tableName = "sales")
data class SaleTransactionEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_SALE),
  val dateEpoch: Long,
  val mode: String, // SALE, COLLECTION, MONEY_OUT
  val customerId: String,
  val customerName: String,
  @ColumnInfo(name = "salesRep") val repName: String = "Direct",
  @ColumnInfo(name = "supplyStore") val storeKey: String = "",
  val storeName: String = "",
  @ColumnInfo(name = "quantity") val quantityKg: Double = 0.0,
  @ColumnInfo(name = "unitPrice") val pricePerKg: Double = 0.0,
  val totalValue: Double = 0.0,
  val totalCost: Double = 0.0,
  val profit: Double = 0.0,
  val paymentType: String = "CREDIT", // CASH, CREDIT, COLLECTION, PARTIAL_PAYMENT, MONEY_OUT, REP_RETURN
  val amountReceived: Double = 0.0,
  @ColumnInfo(name = "expiredQty") val expiredQtyKg: Double = 0.0,
  @ColumnInfo(name = "returnedQty") val returnedQtyKg: Double = 0.0,
  val balanceAfter: Double = 0.0,
  val addToLedger: Boolean = false,
  val isRepSale: Boolean = false,
  val latitude: Double? = null,
  val longitude: Double? = null,
  val dateStr: String = SarimFormat.dateInput(dateEpoch),
  val timeStr: String = "",
  val customerPhone: String = "",
  val creditReceived: Boolean = false,
  val isCollection: Boolean = false,
  val usedInCalcId: String? = null,
  val relatedSaleId: String? = null,
  val ledgerId: String? = null,
  val notes: String = "",
  val transactionType: String? = null,
  val costSource: String? = null,
  val affectsInventory: Boolean = true,
  val isMoneyOut: Boolean = false,
  val isRepReturn: Boolean = false,
  val expiredValue: Double = 0.0,
  val expiredUnitPrice: Double = 0.0,
  val expiredAllocated: Double = 0.0,
  val collectedAmount: Double = 0.0,
  val allocationsJson: String = "[]",
  val partialPaymentReceived: Double = 0.0,
  val collectionAllocsJson: String = "[]",
  @ColumnInfo(name = "editHistory") val editHistoryJson: String = "[]",
  val createdBy: String? = null,
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/entities/{id} ({ id, name, type ("payee"|"payor"), phone, wallet, ledgerPage, openingBalance, isSupplier, isExpenseEntity, editHistory }) */
@Entity(tableName = "entities")
data class PaymentEntityRecord(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_ENT),
  val name: String,
  val phone: String = "",
  val wallet: String = "",
  val ledgerPage: String = "",
  val openingType: String = "CREDIT", // CREDIT (payable -> payee), DEBIT (receivable -> payor)
  val openingAmount: Double = 0.0,
  val currentBalance: Double = 0.0,
  val photoUri: String = "",
  @ColumnInfo(name = "editHistory") val editHistoryJson: String = "[]",
  val type: String = "payee",
  val address: String = "",
  val isSupplier: Boolean = false,
  val isExpenseEntity: Boolean = false,
  val category: String = "",
  val notes: String = "",
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/transactions/{id} ({ id, date, time, type, entityId, entityName, amount, description, isExpense, expenseId, isPayable, isTransfer, transferPairId, ledgerId }) */
@Entity(tableName = "transactions")
data class PaymentTransactionEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_PAY),
  val dateEpoch: Long,
  val type: String, // EXPENSE, PAYMENT_IN, PAYMENT_OUT
  @ColumnInfo(name = "entityName") val entityOrExpenseName: String,
  val entityId: String? = null,
  val amount: Double,
  val description: String = "",
  val photoUri: String = "",
  val addToLedger: Boolean = false,
  val dateStr: String = SarimFormat.dateInput(dateEpoch),
  val timeStr: String = "",
  val isExpense: Boolean = (type == "EXPENSE"),
  val expenseId: String? = null,
  val isPayable: Boolean = false,
  val ledgerId: String? = null,
  val entityType: String = "",
  val materialId: String? = null,
  val materialIdsJson: String = "[]",
  val consumedCreditsJson: String = "[]",
  val category: String? = null,
  val supplierCreditAmount: Double = 0.0,
  @ColumnInfo(name = "editHistory") val editHistoryJson: String = "[]",
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/expenses/{id} ({ id, name, amount, date, description, category, ledgerId, createdAt, updatedAt, timestamp }) */
@Entity(tableName = "expenses")
data class ExpenseRecordEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_EXP),
  val name: String,
  val amount: Double,
  val date: String,
  val dateEpoch: Long,
  val description: String = "",
  val category: String = "operating", // operating, IN, OUT
  val ledgerId: String? = null,
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/returns/{id} ({ id, date, time, store, quantity, seller, createdAt, updatedAt, timestamp }) */
@Entity(tableName = "returns")
data class ReturnLogEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_RETLOG),
  val date: String,
  val time: String = "",
  val store: String,
  val quantity: Double,
  val seller: String,
  val dateEpoch: Long = System.currentTimeMillis(),
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/daily_ledger/entries -> daily_ledger[] */
@Entity(tableName = "daily_ledger")
data class DailyLedgerEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_DAILY_LEDGER),
  val dateEpoch: Long,
  val type: String, // IN, OUT, TRANSFER
  val amount: Double,
  val mode: String = "Cash",
  val toMode: String = "",
  val party: String = "",
  @ColumnInfo(name = "ref") val refNo: String = "",
  val note: String = "",
  val category: String = "General",
  val sourceType: String = "MANUAL",
  val sourceId: String = "",
  val scope: String = "", // BUSINESS, HOME, or empty
  val dateStr: String = SarimFormat.dateInput(dateEpoch),
  val linkJson: String = "",
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/calculator/{id} ({ id, seller, date, totalSold, returned, expired, shared, returnStore, creditQty, commissionPerUnit, commissionPaid, commissionPaidItems, prevColl, fieldExpenses, fieldExpenseItems, received, totalExpected, statusText, statusClass, grossCommission, commissionPayable, totalCost, revenue, profit, creditValue, linkedSalesIds, linkedRepSalesIds, linkedRepReturnIds }) */
@Entity(tableName = "calculator")
data class CalculatorSettlementEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_CALC),
  @ColumnInfo(name = "seller") val repName: String,
  val fromDateEpoch: Long,
  val toDateEpoch: Long,
  val dateEpoch: Long,
  @ColumnInfo(name = "totalSold") val totalSoldKg: Double,
  @ColumnInfo(name = "returned") val returnedKg: Double,
  @ColumnInfo(name = "expired") val expiredKg: Double,
  @ColumnInfo(name = "shared") val sharedDeductionKg: Double,
  val netSoldKg: Double,
  @ColumnInfo(name = "returnStore") val returnStoreKey: String = "",
  @ColumnInfo(name = "creditQty") val creditSalesQty: Double,
  val commissionPerUnit: Double,
  val commissionPaid: Double,
  @ColumnInfo(name = "commissionPaidItems") val commissionItemsJson: String = "[]",
  @ColumnInfo(name = "prevColl") val recoveredCredit: Double,
  val fieldExpenses: Double,
  @ColumnInfo(name = "fieldExpenseItems") val expenseItemsJson: String = "[]",
  @ColumnInfo(name = "received") val receivedCash: Double,
  @ColumnInfo(name = "totalExpected") val expectedCash: Double,
  @ColumnInfo(name = "statusText") val status: String, // OK, SHORT, OVER
  val statusDiff: Double,
  val grossCommission: Double,
  val commissionPayable: Double,
  val totalCost: Double,
  @ColumnInfo(name = "revenue") val grossRevenue: Double,
  @ColumnInfo(name = "profit") val netProfit: Double,
  @ColumnInfo(name = "creditValue") val creditIssuedValue: Double,
  val addToLedger: Boolean = false,
  val dateStr: String = SarimFormat.dateInput(dateEpoch),
  val returnEntryId: String? = null,
  val returnLogId: String? = null,
  val statusClass: String = "status-ok",
  val returnRep: String? = null,
  val returnType: String? = null,
  val transferSaleId: String? = null,
  val expiredApplied: Boolean = true,
  val linkedSalesIdsJson: String = "[]",
  val linkedRepSalesIdsJson: String = "[]",
  val linkedRepReturnIdsJson: String = "[]",
  @ColumnInfo(name = "editHistory") val editHistoryJson: String = "[]",
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Local view of paired stock transfers; synced to Firestore `production` as paired out/in docs with `isTransfer = true` & `transferPairId`. */
@Entity(tableName = "stock_transfers")
data class StockTransferEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_TRFPAIR),
  val dateEpoch: Long,
  val fromStoreKey: String,
  val fromStoreName: String,
  val toStoreKey: String,
  val toStoreName: String,
  val quantityKg: Double,
  val note: String = "",
  val dateStr: String = SarimFormat.dateInput(dateEpoch),
  val outDocId: String = SarimUuid.generateUUID(SarimUuid.PREFIX_TRF),
  val inDocId: String = SarimUuid.generateUUID(SarimUuid.PREFIX_TRF),
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Local view of paired payment transfers; synced to Firestore `transactions` as paired OUT/IN docs with `isTransfer = true` & `transferPairId`. */
@Entity(tableName = "payment_transfers")
data class PaymentTransferEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_TRFPAIR),
  val dateEpoch: Long,
  val fromEntityId: String,
  val fromEntityName: String,
  val toEntityId: String,
  val toEntityName: String,
  val amount: Double,
  val note: String = "",
  val photoUri: String = "",
  val dateStr: String = SarimFormat.dateInput(dateEpoch),
  val outDocId: String = SarimUuid.generateUUID(SarimUuid.PREFIX_PAY),
  val inDocId: String = SarimUuid.generateUUID(SarimUuid.PREFIX_PAY),
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Embedded inside Firestore `formulas/config -> formulas[i].savedCalcs[]` */
@Entity(tableName = "saved_mix_calcs")
data class SavedMixCalcEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_MIX),
  val kind: String, // BATCHING, CORRECTION
  val name: String,
  val formulaId: String,
  val formulaName: String,
  val unit: String, // g, kg
  val mode: String, // WEIGHT, UNITS, INGREDIENT, CORRECTION
  val inputValue: Double,
  val summaryText: String,
  val detailsJson: String = "{}",
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/pending/{id} ({ id, collection, docId, action, data, isEdit, status, submittedBy, submittedAt, summary }) */
@Entity(tableName = "pending")
data class ApprovalRequestEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(),
  val submittedBy: String,
  val submittedRole: String,
  val category: String, // Sales, Payments, Expenses, Production, Factory, Stock, Customers, Other
  val actionKind: String, // New, Change, Delete
  val targetSummary: String,
  val amount: Double = 0.0,
  @ColumnInfo(name = "data") val payloadJson: String = "{}",
  val status: String = "PENDING", // PENDING, APPROVED, DISCARDED
  val targetCollection: String = "",
  val docId: String = "",
  val action: String = "set",
  val isEdit: Boolean = false,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val submittedByUid: String = "",
  val submittedByEmail: String = "",
  val updatedAt: Long = System.currentTimeMillis(),
)

/** Firestore: users/{uid}/settings/accounts & users/{uid} */
@Entity(tableName = "app_accounts")
data class AppAccountEntity(
  @PrimaryKey val id: String = SarimUuid.generateUUID(SarimUuid.PREFIX_ACCT),
  val email: String,
  val name: String,
  val rolesCsv: String,
  val syncPending: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val uid: String = id,
  val approved: Boolean = true,
  val ownerUid: String = "",
  val createdBy: String = "",
)

/** Firestore: users/{uid}/deletions/{id} (tombstone + recycle bin snapshot, 90-day expiry) */
@Entity(tableName = "deletions")
data class DeletionTombstoneEntity(
  @PrimaryKey val id: String,
  val recordId: String,
  val collection: String,
  val recordType: String = collection,
  val deletedAt: Long = System.currentTimeMillis(),
  val expiresAt: Long = System.currentTimeMillis() + (90L * 24L * 60L * 60L * 1000L),
  val syncedToCloud: Boolean = false,
  val displayName: String? = null,
  val displayDetail: String? = null,
  val displayAmount: String? = null,
  val snapshotJson: String? = null,
  val deletedBy: String = "Admin",
  val deletionVersion: String = "2.0",
  val groupId: String? = null,
)

/** Firestore: users/{uid}/photos/{safeDocId} ({ key, data, deleted, updatedAt }) */
@Entity(tableName = "photos")
data class PhotoRecordEntity(
  @PrimaryKey @ColumnInfo(name = "key") val photoKey: String,
  val docId: String,
  val data: String? = null,
  val deleted: Boolean = false,
  val updatedAt: Long = System.currentTimeMillis(),
  val syncPending: Boolean = true,
)
