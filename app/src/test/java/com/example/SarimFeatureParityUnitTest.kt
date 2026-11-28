package com.example

import com.example.data.local.*
import com.example.data.model.AuditFixKind
import com.example.data.model.EditHistoryChange
import com.example.data.model.EditHistoryEntry
import com.example.data.model.TimePeriod
import com.example.data.remote.InMemoryKvStorePersistence
import com.example.data.remote.UuidSyncRegistry
import com.example.data.repository.SarimLinkEngine
import com.example.data.repository.SarimRepository
import com.example.util.LocationHelper
import com.example.util.SarimFormat
import com.example.viewmodel.CalcIndMetric
import com.example.viewmodel.SarimMetricsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class SarimFeatureParityUnitTest {

  private lateinit var dao: InMemorySarimDao
  private lateinit var repo: SarimRepository

  @Before
  fun setUp() {
    dao = InMemorySarimDao()
    val safeReader = SafeSqliteCacheReader(dao)
    val uuidReg = UuidSyncRegistry(InMemoryKvStorePersistence())
    repo = SarimRepository(dao, safeReader, uuidReg)
  }

  @Test
  fun evaluateAutoLocationCluster_confirmsAfterThreeNearbyVisitsAndSkipsOutliersOrManual() {
    val now = 1_750_000_000_000L
    val baseLat = 34.0151
    val baseLng = 71.5249

    val unconfirmedCustomer = CustomerEntity(
      id = "c1",
      name = "Khan Store",
      isRepCustomer = true,
      repName = "Ali",
      address = "",
      locationConfirmed = false,
    )

    fun repVisit(id: String, lat: Double?, lng: Double?, ageMs: Long = 10_000L) = SaleTransactionEntity(
      id = id,
      dateEpoch = now - ageMs,
      mode = "SALE",
      customerId = "c1",
      customerName = "Khan Store",
      repName = "Ali",
      isRepSale = true,
      latitude = lat,
      longitude = lng,
      updatedAt = now - ageMs,
    )

    // Fewer than 3 past visits -> null
    assertNull(
      SarimLinkEngine.evaluateAutoLocationCluster(
        unconfirmedCustomer,
        listOf(repVisit("v1", baseLat, baseLng), repVisit("v2", baseLat + 0.0002, baseLng)),
        baseLat,
        baseLng,
        now = now,
      )
    )

    // 2 nearby visits + 1 outlier (> 150m away) -> still null
    val farLat = baseLat + 0.01 // ~1.1 km away
    assertNull(
      SarimLinkEngine.evaluateAutoLocationCluster(
        unconfirmedCustomer,
        listOf(
          repVisit("v1", baseLat + 0.0001, baseLng),
          repVisit("v2", baseLat - 0.0001, baseLng),
          repVisit("v3", farLat, baseLng),
        ),
        baseLat,
        baseLng,
        now = now,
      )
    )

    // 3 nearby visits within 150m -> returns averaged coordinates
    val nearby = listOf(
      repVisit("v1", baseLat + 0.0001, baseLng + 0.0001),
      repVisit("v2", baseLat - 0.0001, baseLng - 0.0001),
      repVisit("v3", baseLat + 0.0002, baseLng),
    )
    assertTrue(LocationHelper.haversineMeters(baseLat, baseLng, baseLat + 0.0002, baseLng) <= 150.0)
    val confirmed = SarimLinkEngine.evaluateAutoLocationCluster(unconfirmedCustomer, nearby, baseLat, baseLng, now = now)
    assertNotNull(confirmed)
    assertEquals(baseLat, confirmed!!.first, 0.0002)
    assertEquals(baseLng, confirmed.second, 0.0002)

    // Already confirmed -> null
    assertNull(
      SarimLinkEngine.evaluateAutoLocationCluster(
        unconfirmedCustomer.copy(locationConfirmed = true),
        nearby,
        baseLat,
        baseLng,
        now = now,
      )
    )

    // Manual non-GPS address -> null
    assertNull(
      SarimLinkEngine.evaluateAutoLocationCluster(
        unconfirmedCustomer.copy(address = "Main Bazaar Mardan"),
        nearby,
        baseLat,
        baseLng,
        now = now,
      )
    )

    // GPS-prefixed address is still eligible for auto-confirmation
    assertNotNull(
      SarimLinkEngine.evaluateAutoLocationCluster(
        unconfirmedCustomer.copy(address = "GPS: 34.01510, 71.52490"),
        nearby,
        baseLat,
        baseLng,
        now = now,
      )
    )
  }

  @Test
  fun storeUsage_includesReturnLogsInDeleteGuard() {
    val ret = ReturnLogEntity(id = "ret1", date = SarimFormat.dateInput(1_750_000_000_000L), store = "zubair", quantity = 4.0, seller = "Bilal", dateEpoch = 1_750_000_000_000L)
    val usage = SarimLinkEngine.storeUsage(
      storeKey = "ZUBAIR",
      production = emptyList(),
      factoryBatches = emptyList(),
      sales = emptyList(),
      returns = listOf(ret),
      stockTransfers = emptyList(),
    )
    assertEquals(1, usage.returnCount)
    assertEquals(1, usage.totalCount)
    val msg = usage.blockMessage("Zubair Store")
    assertNotNull(msg)
    assertTrue(msg!!.contains("1 return log(s)"))

    // Soft-deleted return log does not block
    val cleanUsage = SarimLinkEngine.storeUsage(
      storeKey = "zubair",
      production = emptyList(),
      factoryBatches = emptyList(),
      sales = emptyList(),
      returns = listOf(ret.copy(isDeleted = true)),
      stockTransfers = emptyList(),
    )
    assertEquals(0, cleanUsage.totalCount)
    assertNull(cleanUsage.blockMessage("Zubair Store"))
  }

  @Test
  fun editHistory_buildDiffsAndJsonRoundTripPreserveRichAndLegacyEntries() {
    val diffs = SarimLinkEngine.buildEditDiffs(
      listOf(
        SarimLinkEngine.QuadrupleDiff("qty", "Quantity", "10.0", "10.0"),
        SarimLinkEngine.QuadrupleDiff("price", "Price/kg", "400", "450"),
        SarimLinkEngine.QuadrupleDiff("note", "Note", "", "Delivered"),
      )
    )
    assertEquals(2, diffs.size)
    assertEquals("price", diffs[0].fieldKey)
    assertEquals("400", diffs[0].from)
    assertEquals("450", diffs[0].to)
    assertEquals("—", diffs[1].from)
    assertEquals("Delivered", diffs[1].to)

    val richEntry = EditHistoryEntry(
      timestamp = 1_750_000_000_000L,
      summary = "Updated price",
      by = "Asad",
      changes = diffs,
    )
    val encoded = JsonCodecs.encodeEditHistory(listOf(richEntry))
    val decoded = JsonCodecs.decodeEditHistory(encoded)
    assertEquals(1, decoded.size)
    assertEquals("Asad", decoded[0].by)
    assertEquals("Updated price", decoded[0].summary)
    assertEquals(2, decoded[0].changes.size)
    assertEquals("Price/kg", decoded[0].changes[0].label)

    // Legacy minimal JSON without "by" or "changes"
    val legacy = JsonCodecs.decodeEditHistory("""[{"timestamp":1740000000000,"summary":"Legacy edit"}]""")
    assertEquals(1, legacy.size)
    assertEquals("", legacy[0].by)
    assertTrue(legacy[0].changes.isEmpty())
    assertEquals("Legacy edit", legacy[0].summary)
  }

  @Test
  fun rolloverDateIfUnedited_rollsOnlyUntouchedNewForms() {
    val cal = Calendar.getInstance().apply {
      set(Calendar.YEAR, 2026)
      set(Calendar.MONTH, Calendar.MAY)
      set(Calendar.DAY_OF_MONTH, 10)
    }
    val day1 = SarimFormat.startOfDay(cal.timeInMillis)
    cal.add(Calendar.DAY_OF_YEAR, 1)
    val day2 = SarimFormat.startOfDay(cal.timeInMillis)
    cal.add(Calendar.DAY_OF_YEAR, -3)
    val customPastDay = SarimFormat.startOfDay(cal.timeInMillis)

    // Unedited today field on new form rolls from day1 -> day2
    assertEquals(day2, SarimLinkEngine.rolloverDateIfUnedited(day1 + 3600_000L, day1, day2, isEditing = false))
    // Editing an existing record keeps original date
    assertEquals(day1 + 3600_000L, SarimLinkEngine.rolloverDateIfUnedited(day1 + 3600_000L, day1, day2, isEditing = true))
    // Manually picked custom past date is preserved
    assertEquals(customPastDay, SarimLinkEngine.rolloverDateIfUnedited(customPastDay, day1, day2, isEditing = false))
  }

  @Test
  fun computeCalcIndChartData_aggregatesPeriodsAndMetrics() {
    val cal = Calendar.getInstance().apply {
      set(2026, Calendar.MARCH, 15, 10, 0, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val refEpoch = cal.timeInMillis
    val s1 = CalculatorSettlementEntity(
      id = "calc1",
      repName = "Bilal",
      fromDateEpoch = refEpoch,
      toDateEpoch = refEpoch,
      dateEpoch = refEpoch,
      totalSoldKg = 20.0,
      returnedKg = 2.0,
      expiredKg = 0.0,
      sharedDeductionKg = 0.0,
      netSoldKg = 18.0,
      returnStoreKey = "",
      creditSalesQty = 0.0,
      commissionPerUnit = 5.0,
      commissionPaid = 90.0,
      recoveredCredit = 0.0,
      fieldExpenses = 0.0,
      receivedCash = 7200.0,
      expectedCash = 7200.0,
      status = "OK",
      statusDiff = 0.0,
      grossCommission = 90.0,
      commissionPayable = 90.0,
      totalCost = 3600.0,
      grossRevenue = 7200.0,
      netProfit = 3510.0,
      creditIssuedValue = 0.0,
      returnType = "",
      returnRep = "",
    )
    // Weekly: 7 buckets, last bucket == refEpoch day -> 3510.0
    val (wLabels, wVals) = SarimMetricsEngine.computeCalcIndChartData(listOf(s1), TimePeriod.WEEKLY, CalcIndMetric.NET_PROFIT, refEpoch)
    assertEquals(7, wLabels.size)
    assertEquals(3510.0, wVals.last(), 0.01)

    // Monthly: day 15 (index 14) -> 7200.0 gross revenue
    val (mLabels, mVals) = SarimMetricsEngine.computeCalcIndChartData(listOf(s1), TimePeriod.MONTHLY, CalcIndMetric.GROSS_REV, refEpoch)
    assertEquals(31, mLabels.size)
    assertEquals(7200.0, mVals[14], 0.01)

    // Yearly: March (index 2) -> 18.0 netSoldKg
    val (yLabels, yVals) = SarimMetricsEngine.computeCalcIndChartData(listOf(s1), TimePeriod.YEARLY, CalcIndMetric.QTY_SOLD, refEpoch)
    assertEquals(12, yLabels.size)
    assertEquals(18.0, yVals[2], 0.01)

    // All Time: 1 month bucket "Mar 26"
    val (aLabels, aVals) = SarimMetricsEngine.computeCalcIndChartData(listOf(s1), TimePeriod.ALL_TIME, CalcIndMetric.NET_PROFIT, refEpoch)
    assertEquals(listOf("Mar 26"), aLabels)
    assertEquals(listOf(3510.0), aVals)
  }

  @Test
  fun overviewAudit_detectsExtendedChecksAndAppliesTypedFixes() = runBlocking {
    val now = System.currentTimeMillis()
    val futureEpoch = now + 5 * 86_400_000L
    val mat = RawMaterialEntity(id = "m1", name = "Tobacco", stockKg = -5.0, avgCostPerKg = 0.0, updatedAt = now)
    val matNoCost = RawMaterialEntity(id = "m2", name = "Slaked Lime", stockKg = 25.0, avgCostPerKg = 0.0, updatedAt = now)
    val formula = FormulaEntity(id = "f1", name = "Classic")
    val dupSale1 = SaleTransactionEntity(
      id = "s1", dateEpoch = now, dateStr = SarimFormat.dateInput(now), timeStr = "10:00",
      mode = "SALE", customerId = "cust_tariq", customerName = "Tariq", quantityKg = 10.0, pricePerKg = 400.0,
      totalValue = 4000.0, totalCost = 0.0, profit = 4000.0, paymentType = "CASH", creditReceived = false,
    )
    val dupSale2 = dupSale1.copy(id = "s2")
    val futureSale = dupSale1.copy(id = "s3", dateEpoch = futureEpoch, dateStr = SarimFormat.dateInput(futureEpoch), creditReceived = true, totalCost = 2000.0, profit = 2000.0)
    val prodBad = ProductionEntryEntity(
      id = "p1", dateEpoch = now, storeKey = "main", storeName = "Main",
      formulaId = "", formulaName = "", grossWeightKg = 52.0, containerWeightKg = 2.0,
      netWeightKg = 50.0, formulaUnitsUsed = 1.0, formulaUnitCost = 1000.0, totalFormulaCost = 1000.0,
      costPerKg = 20.0, salePricePerKg = 60.0, profitPerKg = 40.0, totalValue = 3000.0, totalProfit = 500.0,
    )
    val zeroExp = PaymentTransactionEntity(
      id = "pt1", dateEpoch = now, type = "EXPENSE", entityOrExpenseName = "Tea", amount = 0.0,
    )

    dao.upsertRawMaterial(mat)
    dao.upsertRawMaterial(matNoCost)
    dao.upsertFormula(formula)
    dao.upsertSale(dupSale1)
    dao.upsertSale(dupSale2)
    dao.upsertSale(futureSale)
    dao.upsertProductionEntry(prodBad)
    dao.upsertPaymentTransaction(zeroExp)

    val snap = SarimMetricsEngine.compute(
      stores = emptyList(),
      materials = dao.getAllRawMaterials(),
      formulas = dao.getAllFormulas(),
      production = dao.getAllProductionEntries(),
      sales = dao.getAllSales(),
      customers = emptyList(),
      entities = emptyList(),
      payments = dao.getAllPaymentTransactions(),
      ledger = emptyList(),
      settlements = emptyList(),
      stockTransfers = emptyList(),
      accounts = emptyList(),
      overviewMonths = 6,
    )
    val ids = snap.auditFindings.map { it.id }.toSet()
    assertTrue("dup_rec_sale_s2" in ids)
    assertTrue("future_sale_s3" in ids)
    assertTrue("sale_no_cost_s1" in ids)
    assertTrue("sale_cash_unreceived_s1" in ids)
    assertTrue("prod_no_formula_p1" in ids)
    assertTrue("prod_profit_mismatch_p1" in ids)
    assertTrue("exp_bad_amount_pt1" in ids)
    assertTrue("inv_neg_m1" in ids)
    assertTrue("inv_bad_cost_m2" in ids)

    val cashFinding = snap.auditFindings.first { it.id == "sale_cash_unreceived_s1" }
    assertEquals(AuditFixKind.BOOLEAN, cashFinding.fixKind)
    assertTrue(repo.applyAuditFix("SALE", "s1", "creditReceived", "true"))
    assertTrue(dao.getAllSales().first { it.id == "s1" }.creditReceived)

    assertTrue(repo.applyAuditFix("PRODUCTION", "p1", "formulaId", "f1"))
    assertEquals("Classic", dao.getAllProductionEntries().first { it.id == "p1" }.formulaName)

    assertTrue(repo.deleteAuditRecord("SALE", "s2"))
    assertTrue(dao.getAllSales().first { it.id == "s2" }.isDeleted)

    assertTrue(SarimLinkEngine.isRepLinkedAdminSale(dupSale1.copy(repName = "Bilal", isRepSale = false)))
    assertFalse(SarimLinkEngine.isRepLinkedAdminSale(dupSale1.copy(repName = "Direct", isRepSale = false)))
    assertFalse(SarimLinkEngine.isRepLinkedAdminSale(dupSale1.copy(repName = "Bilal", isRepSale = true)))
  }
}
