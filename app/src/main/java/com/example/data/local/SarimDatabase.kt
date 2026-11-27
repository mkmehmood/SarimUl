package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [
    SchemaVersionEntity::class,
    KvStoreEntity::class,
    NdappOutboxEntity::class,
    StoreEntity::class,
    RawMaterialEntity::class,
    RawMaterialPurchaseEntity::class,
    FormulaEntity::class,
    FactoryBatchEntity::class,
    ProductionEntryEntity::class,
    CustomerEntity::class,
    SaleTransactionEntity::class,
    PaymentEntityRecord::class,
    PaymentTransactionEntity::class,
    ExpenseRecordEntity::class,
    ReturnLogEntity::class,
    DailyLedgerEntity::class,
    CalculatorSettlementEntity::class,
    StockTransferEntity::class,
    PaymentTransferEntity::class,
    SavedMixCalcEntity::class,
    ApprovalRequestEntity::class,
    AppAccountEntity::class,
    DeletionTombstoneEntity::class,
    PhotoRecordEntity::class,
  ],
  version = 3,
  exportSchema = false,
)
abstract class SarimDatabase : RoomDatabase() {
  abstract fun dao(): SarimDao

  val safeReader: SafeSqliteCacheReader by lazy { SafeSqliteCacheReader(dao()) }

  companion object {
    const val SCHEMA_VERSION = 3

    @Volatile private var INSTANCE: SarimDatabase? = null

    fun getInstance(context: Context): SarimDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          SarimDatabase::class.java,
          "naswar_dealers.sqlite",
        )
          .fallbackToDestructiveMigration(true)
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
