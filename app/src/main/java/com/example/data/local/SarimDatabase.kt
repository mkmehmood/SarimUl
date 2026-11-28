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
  version = 6,
  exportSchema = false,
)
abstract class SarimDatabase : RoomDatabase() {
  abstract fun dao(): SarimDao

  val safeReader: SafeSqliteCacheReader by lazy { SafeSqliteCacheReader(dao()) }

  companion object {
    const val SCHEMA_VERSION = 6

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

    val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sales ADD COLUMN returnValue REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE sales ADD COLUMN returnUnitPrice REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE sales ADD COLUMN isRepTransfer INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sales ADD COLUMN repTransferFrom TEXT")
        db.execSQL("ALTER TABLE sales ADD COLUMN supplyDate TEXT NOT NULL DEFAULT ''")
      }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE customers ADD COLUMN locationConfirmed INTEGER NOT NULL DEFAULT 0")
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
          .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
          .fallbackToDestructiveMigration(true)
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
