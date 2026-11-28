package com.example

import com.example.data.local.PaymentTransactionEntity
import com.example.data.local.ProductionEntryEntity
import com.example.data.local.RawMaterialEntity
import com.example.data.local.SaleTransactionEntity
import com.example.data.local.StoreEntity
import com.example.data.remote.RemoteActivityPlanner
import com.example.data.remote.RemoteActivityRecord
import com.example.data.remote.SarimPush
import com.example.util.AppLockGuard
import com.example.util.ProductionPhotoSharer
import com.example.viewmodel.SarimMetricsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SarimFeatureParityUnitTest {

  @Test
  fun remoteActivityPlanner_diffsFiltersAndCapsAlerts() {
    val now = 1_750_000_000_000L
    val before = mapOf(
      "sales" to setOf("s_existing"),
      "rep" to emptySet(), // empty baseline must not trigger burst alerts on initial load
      "daily_ledger" to setOf("dl_0"),
    )
    val after = mapOf(
      "sales" to listOf(
        RemoteActivityRecord(id = "s_existing", collection = "sales", createdAt = now - 1000L, author = "Bilal", customerName = "Old", totalValue = 100.0),
        RemoteActivityRecord(id = "s_own", collection = "sales", createdAt = now - 1000L, author = "Bilal", customerName = "Own", totalValue = 200.0),
        RemoteActivityRecord(id = "s_deleted", collection = "sales", createdAt = now - 1000L, isDeleted = true, author = "Bilal", customerName = "Del", totalValue = 200.0),
        RemoteActivityRecord(id = "s_merged", collection = "sales", createdAt = now - 1000L, isMerged = true, author = "Bilal", customerName = "Mrg", totalValue = 200.0),
        RemoteActivityRecord(id = "_placeholder_", collection = "sales", createdAt = now - 1000L, author = "Bilal", customerName = "Ph", totalValue = 200.0),
        RemoteActivityRecord(id = "s_stale", collection = "sales", createdAt = now - RemoteActivityPlanner.REMOTE_WINDOW_MS - 1000L, author = "Bilal", customerName = "Stale", totalValue = 200.0),
        RemoteActivityRecord(id = "s_new_1", collection = "sales", createdAt = now - 2000L, author = "Usman", customerName = "Afridi Store", totalValue = 1200.0),
        RemoteActivityRecord(id = "s_new_2", collection = "sales", createdAt = now - 3000L, author = "Usman", customerName = "Khyber Traders", totalValue = 2400.0),
        RemoteActivityRecord(id = "s_new_3", collection = "sales", createdAt = now - 4000L, author = "Usman", customerName = "Swat Mart", totalValue = 3600.0),
        RemoteActivityRecord(id = "s_new_4", collection = "sales", createdAt = now - 5000L, author = "Usman", customerName = "Peshawar Co", totalValue = 4800.0),
        RemoteActivityRecord(id = "s_new_5", collection = "sales", createdAt = now - 6000L, author = "Usman", customerName = "Bannu Depot", totalValue = 6000.0),
        RemoteActivityRecord(id = "s_new_6", collection = "sales", createdAt = now - 7000L, author = "Usman", customerName = "Kohat Shop", totalValue = 7200.0),
      ),
      "rep" to listOf(
        RemoteActivityRecord(id = "r_new", collection = "rep", createdAt = now - 1000L, salesRep = "Tariq", customerName = "RepClient", totalValue = 500.0),
      ),
      "daily_ledger" to listOf(
        RemoteActivityRecord(id = "dl_1", collection = "daily_ledger", createdAt = now - 1000L, type = "OUT", mode = "Cash", category = "Fuel", party = "Pump", amount = 850.0),
      ),
    )

    val alerts = RemoteActivityPlanner.diffSnapshots(
      beforeKnownIdsByCollection = before,
      afterRecordsByCollection = after,
      isOwnRecord = { it == "s_own" },
      formatMoney = { "Rs ${it.toInt()}" },
      nowMs = now,
    )

    // 6 qualifying sales + 1 qualifying daily_ledger = 7 raw alerts -> capped at 5 + 1 overflow ("More new activity")
    assertEquals(6, alerts.size)
    assertEquals("sales", alerts.first().targetKey)
    assertEquals("s_new_1", alerts.first().targetId)
    assertTrue(alerts.none { it.targetId == "r_new" }) // skipped empty baseline
    assertTrue(alerts.none { it.targetId == "s_own" }) // skipped own shard
    assertEquals("More new activity", alerts.last().title)
    assertTrue(alerts.last().body.contains("2 more records"))
  }

  @Test
  fun sarimPush_parseTargetValidatesAndPreservesNestedViewColons() {
    val saleTarget = SarimPush.parseTarget("sales:sale_abc123")
    assertEquals(SarimPush.NotificationTarget("sales", "sale_abc123"), saleTarget)

    val viewTabTarget = SarimPush.parseTarget("view:tab:rep")
    assertEquals(SarimPush.NotificationTarget("view", "tab:rep"), viewTabTarget)

    val explicitTarget = SarimPush.parseTarget(raw = null, explicitKey = "daily_ledger", explicitId = "dl_99")
    assertEquals(SarimPush.NotificationTarget("daily_ledger", "dl_99"), explicitTarget)

    assertNull(SarimPush.parseTarget("view:unknown-screen"))
    assertNull(SarimPush.parseTarget("unknown_collection:id_1"))
    assertNull(SarimPush.parseTarget("sales:"))
    assertNull(SarimPush.parseTarget("malformed"))
  }

  @Test
  fun metricsEngine_computesProfitAuditGroupedChecksAndMonthlySummaryColumns() {
    val now = System.currentTimeMillis()
    val store = StoreEntity(storeKey = "zubair", name = "Zubair Store", formulaId = "f_1", salePricePerKg = 400.0)
    val sales = listOf(
      SaleTransactionEntity(
        id = "sale_win",
        dateEpoch = now - 60_000L,
        mode = "SALE",
        customerId = "c_1",
        customerName = "Good Customer",
        storeKey = "zubair",
        storeName = "Zubair Store",
        quantityKg = 10.0,
        pricePerKg = 400.0,
        totalValue = 4000.0,
        totalCost = 2800.0,
        profit = 1200.0,
        paymentType = "CASH",
        amountReceived = 4000.0,
      ),
      SaleTransactionEntity(
        id = "sale_loss_1",
        dateEpoch = now - 30_000L,
        mode = "SALE",
        customerId = "c_2",
        customerName = "Discount Client",
        storeKey = "zubair",
        storeName = "Zubair Store",
        quantityKg = 10.0,
        pricePerKg = 250.0,
        totalValue = 2500.0,
        totalCost = 3000.0,
        profit = -500.0,
        paymentType = "CREDIT",
      ),
      SaleTransactionEntity(
        id = "sale_no_cost",
        dateEpoch = now - 10_000L,
        mode = "SALE",
        customerId = "c_3",
        customerName = "NoCost Client",
        storeKey = "zubair",
        storeName = "Zubair Store",
        quantityKg = 5.0,
        pricePerKg = 400.0,
        totalValue = 2000.0,
        totalCost = 0.0,
        profit = 2000.0,
        paymentType = "CASH",
        amountReceived = 2000.0,
      ),
    )
    val expenses = listOf(
      PaymentTransactionEntity(
        id = "exp_1",
        dateEpoch = now - 5_000L,
        type = "EXPENSE",
        entityOrExpenseName = "Fuel",
        amount = 450.0,
        isExpense = true,
      ),
    )
    val rawMats = listOf(
      RawMaterialEntity(id = "mat_1", name = "Tobacco Leaf", stockKg = 0.0, avgCostPerKg = 150.0),
    )

    val snap = SarimMetricsEngine.compute(
      stores = listOf(store),
      materials = rawMats,
      formulas = emptyList(),
      production = emptyList(),
      sales = sales,
      customers = emptyList(),
      entities = emptyList(),
      payments = expenses,
      ledger = emptyList(),
      settlements = emptyList(),
      stockTransfers = emptyList(),
      accounts = emptyList(),
    )

    val audit = snap.profitAudit
    assertNotNull(audit)
    assertEquals(1, audit!!.losingCount)
    assertEquals(2, audit.gainingCount)
    assertEquals(500.0, audit.losingSum, 0.01)
    assertEquals(2700.0, audit.salesProfit, 0.01)
    assertEquals(2250.0, audit.netProfit, 0.01)
    assertEquals("sale_loss_1", audit.worstSales.first().saleId)
    assertEquals("Discount Client", audit.lossByCustomer.first().label)
    assertEquals("Zubair Store", audit.lossByStore.first().label)

    assertTrue(snap.auditScannedCount >= 5)
    assertTrue(snap.auditByCheck.any { it.code == "sale_loss" && it.count == 1 })
    assertTrue(snap.auditByCheck.any { it.code == "sale_no_cost" && it.count == 1 })
    assertTrue(snap.auditByCheck.any { it.code == "oos_mat" && it.count == 1 })

    val curMonth = snap.monthlyRows.last()
    assertEquals(2700.0, curMonth.salesProfit, 0.01)
    assertEquals(450.0, curMonth.expenses, 0.01)
  }

  @Test
  fun productionPhotoSharer_redactsFinancialMetadataWhenHidden() {
    val entry = ProductionEntryEntity(
      id = "prod_1",
      dateEpoch = 1_750_000_000_000L,
      storeKey = "zubair",
      storeName = "Zubair Store",
      formulaId = "f1",
      formulaName = "Standard",
      grossWeightKg = 52.5,
      containerWeightKg = 2.5,
      netWeightKg = 50.0,
      formulaUnitsUsed = 1.0,
      formulaUnitCost = 10000.0,
      totalFormulaCost = 10000.0,
      costPerKg = 200.0,
      salePricePerKg = 400.0,
      profitPerKg = 200.0,
      totalValue = 20000.0,
      totalProfit = 10000.0,
      photoUrisJson = "[\"content://media/1\"]",
      dateStr = "2025-06-15",
    )

    val visibleCaptions = ProductionPhotoSharer.buildCaptionLines(entry, privacyHidden = false)
    assertEquals(3, visibleCaptions.size)
    assertTrue(visibleCaptions[0].contains("Zubair Store"))
    assertTrue(visibleCaptions[1].contains("50"))
    assertTrue(visibleCaptions[2].contains("Value"))
    assertFalse(visibleCaptions.joinToString("\n").let { it.contains("Profit") || it.contains("Cost") })

    val hiddenCaptions = ProductionPhotoSharer.buildCaptionLines(entry, privacyHidden = true)
    assertEquals(2, hiddenCaptions.size)
    assertFalse(hiddenCaptions.joinToString("\n").contains("Profit"))
  }

  @Test
  fun appLockGuard_suppressesAndConsumesWindow() {
    val t0 = 10_000L
    AppLockGuard.consumeIfSuppressed(t0 + 1_000_000L) // reset prior state
    assertFalse(AppLockGuard.isSuppressed(t0))

    AppLockGuard.suppressFor(durationMs = 60_000L)
    assertTrue(AppLockGuard.isSuppressed())
    assertTrue(AppLockGuard.consumeIfSuppressed())
    assertFalse(AppLockGuard.isSuppressed())
  }
}
