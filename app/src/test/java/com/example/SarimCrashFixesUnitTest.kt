package com.example

import com.example.data.local.SafeSqliteCacheReader
import com.example.data.remote.SarimWebSchema
import com.example.ui.theme.avatarColorForName
import com.example.ui.theme.storeColorForKey
import com.example.util.SarimFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SarimCrashFixesUnitTest {

  @Test
  fun sarimFormat_handlesNaNAndInfinityGracefully() {
    assertEquals("0", SarimFormat.formatSouthAsian(Double.NaN))
    assertEquals("0", SarimFormat.formatSouthAsian(Double.POSITIVE_INFINITY))
    assertEquals("0", SarimFormat.formatSouthAsian(Double.NEGATIVE_INFINITY))
    assertEquals("Rs 0", SarimFormat.formatRs(Double.NaN))
    assertEquals("0 kg", SarimFormat.formatKg(Double.POSITIVE_INFINITY))
  }

  @Test
  fun sarimFormat_concurrentDateFormattingIsThreadSafe() {
    val pool = Executors.newFixedThreadPool(8)
    val baseEpoch = 1_735_689_600_000L // 2025-01-01T00:00:00Z
    val tasks = (0 until 200).map { i ->
      Callable {
        val ts = baseEpoch + (i * 86_400_000L)
        val d1 = SarimFormat.formatDate(ts)
        val d2 = SarimFormat.formatDateFriendly(ts)
        val d3 = SarimFormat.dateInput(ts)
        val d4 = SarimFormat.formatMonthYear(ts)
        val d5 = SarimFormat.formatWeekday(ts)
        val d6 = SarimFormat.formatTime(ts)
        val d7 = SarimFormat.formatClock(ts)
        d1.isNotBlank() && d2.isNotBlank() && d3.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
          d4.isNotBlank() && d5.isNotBlank() && d6.isNotBlank() && d7.isNotBlank()
      }
    }
    try {
      val results = pool.invokeAll(tasks)
      assertEquals(200, results.size)
      assertTrue(results.all { it.get() })
    } finally {
      pool.shutdown()
      pool.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  @Test
  fun colorHelpers_doNotCrashOnIntMinValueHashCode() {
    val minHashKey = "polygenelubricants"
    assertEquals(Int.MIN_VALUE, minHashKey.hashCode())
    assertNotNull(storeColorForKey(minHashKey, isDark = true))
    assertNotNull(storeColorForKey(minHashKey, isDark = false))
    assertNotNull(avatarColorForName(minHashKey))
    assertNotNull(avatarColorForName(""))
  }

  @Test
  fun sarimWebSchema_filtersNonFiniteNumbersAndParsesIsoDates() {
    assertEquals(12.5, SarimWebSchema.num(12.5), 0.0001)
    assertEquals(0.0, SarimWebSchema.num(Double.NaN), 0.0001)
    assertEquals(0.0, SarimWebSchema.num(Double.POSITIVE_INFINITY), 0.0001)
    assertEquals(0.0, SarimWebSchema.num("NaN"), 0.0001)
    assertEquals(0.0, SarimWebSchema.num("Infinity"), 0.0001)
    assertEquals(99.0, SarimWebSchema.num("not_a_num", default = 99.0), 0.0001)

    val millisNoMs = SarimWebSchema.parseEpoch("2025-02-15T10:30:00Z")
    val millisWithMs = SarimWebSchema.parseEpoch("2025-02-15T10:30:00.000Z")
    assertEquals(millisWithMs, millisNoMs)
  }

  @Test
  fun safeSqliteCacheReader_readsSingleAndMultiChunkBlobsAndHalvesOnCursorWindowError() = runBlocking {
    val small = "hello-kv-store"
    val smallRead = SafeSqliteCacheReader.readTextInChunks(small.length) { startPos, len ->
      small.substring(startPos - 1, minOf(small.length, startPos - 1 + len))
    }
    assertEquals(small, smallRead)

    // 2.5M character payload (~2.5 MB ASCII) with simulated CursorWindow failure above 60k chars
    val pattern = "0123456789ABCDEF"
    val huge = buildString(2_500_000) {
      while (length < 2_500_000) append(pattern)
    }.substring(0, 2_500_000)

    var maxObservedSuccessLen = 0
    var simulatedErrors = 0
    val reassembled = SafeSqliteCacheReader.readTextInChunks(
      totalChars = huge.length,
      initialChunkChars = 200_000,
    ) { startPos, len ->
      if (len > 60_000) {
        simulatedErrors++
        throw IllegalStateException("Row too big to fit into CursorWindow requiredPos=0, totalRows=1")
      }
      if (len > maxObservedSuccessLen) maxObservedSuccessLen = len
      huge.substring(startPos - 1, minOf(huge.length, startPos - 1 + len))
    }

    assertTrue(simulatedErrors >= 1)
    assertTrue(maxObservedSuccessLen <= 60_000)
    assertEquals(huge.length, reassembled.length)
    assertEquals(huge, reassembled)
  }

  @Test
  fun safeSqliteCacheReader_detectsCursorWindowExceptions() {
    assertTrue(SafeSqliteCacheReader.isBlobTooBigError(IllegalStateException("Row too big to fit into CursorWindow")))
    assertTrue(SafeSqliteCacheReader.isBlobTooBigError(RuntimeException("outer", IllegalStateException("CursorWindow allocation failed"))))
    assertFalse(SafeSqliteCacheReader.isBlobTooBigError(IllegalArgumentException("unrelated error")))
  }

  @Test
  fun sarimMetricsEngine_computesParityAnalyticsAndUnpaidCount() {
    val now = System.currentTimeMillis()
    val store = com.example.data.local.StoreEntity(
      storeKey = "zubair",
      name = "Zubair Store",
      formulaId = "f1",
      salePricePerKg = 400.0,
    )
    val formula = com.example.data.local.FormulaEntity(
      id = "f1",
      name = "Standard Formula",
      unitsAvailable = 5.0,
    )
    val prod = com.example.data.local.ProductionEntryEntity(
      id = "p1",
      dateEpoch = now,
      storeKey = "zubair",
      storeName = "Zubair Store",
      formulaId = "f1",
      formulaName = "Standard Formula",
      grossWeightKg = 52.0,
      containerWeightKg = 2.0,
      netWeightKg = 50.0,
      formulaUnitsUsed = 1.0,
      formulaUnitCost = 10_000.0,
      totalFormulaCost = 10_000.0,
      costPerKg = 200.0,
      salePricePerKg = 400.0,
      profitPerKg = 200.0,
      totalValue = 20_000.0,
      totalProfit = 10_000.0,
    )
    val unpaidCreditSale = com.example.data.local.SaleTransactionEntity(
      id = "s1",
      dateEpoch = now,
      mode = "SALE",
      customerId = "c1",
      customerName = "Ali",
      storeKey = "zubair",
      storeName = "Zubair Store",
      quantityKg = 10.0,
      pricePerKg = 400.0,
      totalValue = 4_000.0,
      totalCost = 2_000.0,
      profit = 2_000.0,
      paymentType = "CREDIT",
      creditReceived = false,
    )
    val settledCreditSale = unpaidCreditSale.copy(
      id = "s2",
      creditReceived = true,
    )
    val batch = com.example.data.local.FactoryBatchEntity(
      id = "b1",
      dateEpoch = now,
      formulaId = "f1",
      formulaName = "Standard Formula",
      unitsProduced = 3.0,
      outputKg = 150.0,
      rawUsedKg = 150.0,
      materialsCost = 25_000.0,
      additionalCost = 500.0,
      totalCost = 25_500.0,
      costPerUnit = 8_500.0,
    )

    val snapshot = com.example.viewmodel.SarimMetricsEngine.compute(
      stores = listOf(store),
      materials = emptyList(),
      formulas = listOf(formula),
      production = listOf(prod),
      sales = listOf(unpaidCreditSale, settledCreditSale),
      customers = emptyList(),
      entities = emptyList(),
      payments = emptyList(),
      ledger = emptyList(),
      settlements = emptyList(),
      stockTransfers = emptyList(),
      accounts = emptyList(),
      overviewMonths = 6,
      factoryBatches = listOf(batch),
    )

    assertEquals(1, snapshot.unpaidSalesCount)
    assertTrue(snapshot.salesByStore.any { it.label == "Zubair Store" && it.value == 8_000.0 })
    assertTrue(snapshot.prodByFormula.any { it.label == "Standard Formula" && it.value == 50.0 })
    assertTrue(snapshot.factoryOutputByFormula.any { it.label == "Standard Formula" && it.value == 3.0 })
    assertTrue(snapshot.factoryOutputByMonth.any { it == 3.0 })
    assertEquals(false, snapshot.formulaLinkedMap["f1"])
    assertTrue(snapshot.recentActivity.isNotEmpty())
  }
}
