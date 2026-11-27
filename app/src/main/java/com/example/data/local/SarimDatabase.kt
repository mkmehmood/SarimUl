package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
  version = 4,
  exportSchema = false,
)
abstract class SarimDatabase : RoomDatabase() {
  abstract fun dao(): SarimDao

  val safeReader: SafeSqliteCacheReader by lazy { SafeSqliteCacheReader(dao()) }

  companion object {
    const val SCHEMA_VERSION = 4

    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending ADD COLUMN submittedByUid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE pending ADD COLUMN submittedByEmail TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE pending ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE app_accounts ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE app_accounts ADD COLUMN approved INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE app_accounts ADD COLUMN ownerUid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE app_accounts ADD COLUMN createdBy TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE app_accounts SET uid = id WHERE uid = ''")
      }
    }

    @Volatile private var INSTANCE: SarimDatabase? = null

    fun getInstance(context: Context): SarimDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          SarimDatabase::class.java,
          "naswar_dealers.sqlite",
        )
          .addMigrations(MIGRATION_3_4)
          .fallbackToDestructiveMigration(true)
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
