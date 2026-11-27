package com.example

import com.example.data.local.ProductionEntryEntity
import com.example.data.local.SaleTransactionEntity
import com.example.data.repository.SarimStockCheck
import com.example.data.repository.StockTone
import org.junit.Assert.*
import org.junit.Test

class SarimStockCheckTest {
  private val day = "2026-11-28"

  private fun prod(store: String, net: Double, date: String = day, isReturn: Boolean = false) = ProductionEntryEntity(
    dateEpoch = 0L, storeKey = store, storeName = store.uppercase(), formulaId = "f", formulaName = "F",
    grossWeightKg = net, containerWeightKg = 0.0, netWeightKg = net, formulaUnitsUsed = 1.0, formulaUnitCost = 0.0,
    totalFormulaCost = 0.0, costPerKg = 0.0, salePricePerKg = 0.0, profitPerKg = 0.0, totalValue = 0.0, totalProfit = 0.0,
    dateStr = date, isReturn = isReturn,
  )

  private fun sale(store: String, qty: Double, date: String = day, id: String = "s$qty") = SaleTransactionEntity(
    id = id, dateEpoch = 0L, mode = "SALE", customerId = "c", customerName = "C",
    storeKey = store, quantityKg = qty, dateStr = date,
  )

  private fun snap(prod: List<ProductionEntryEntity>, sales: List<SaleTransactionEntity>, exclude: String? = null) =
    SarimStockCheck.snapshot("zubair", day, prod, sales, emptyList(), emptyList(), exclude)

  @Test fun availableIsProductionMinusSalesForThatStoreAndDayOnly() {
    val s = snap(
      listOf(prod("zubair", 100.0), prod("zubair", 50.0, date = "2026-11-27"), prod("asaan", 70.0), prod("zubair", 9.0, isReturn = true)),
      listOf(sale("zubair", 30.0), sale("zubair", 5.0, date = "2026-11-27"), sale("asaan", 10.0)),
    )
    assertEquals(100.0, s.producedKg, 1e-9)
    assertEquals(30.0, s.soldKg, 1e-9)
    assertEquals(70.0, s.availableKg, 1e-9)
  }

  @Test fun editedSaleIsNotCountedAgainstItself() {
    val s = snap(listOf(prod("zubair", 100.0)), listOf(sale("zubair", 60.0, id = "mine")), exclude = "mine")
    assertEquals(100.0, s.availableKg, 1e-9)
  }

  @Test fun blocksWhenNothingProducedAndWhenOverSelling() {
    val none = snap(emptyList(), emptyList())
    assertNotNull(SarimStockCheck.blockingIssue("ZUBAIR", "28 Nov 2026", none, 1.0))

    val some = snap(listOf(prod("zubair", 40.0)), emptyList())
    assertNull(SarimStockCheck.blockingIssue("ZUBAIR", "28 Nov 2026", some, 40.0))
    val over = SarimStockCheck.blockingIssue("ZUBAIR", "28 Nov 2026", some, 45.0)
    assertNotNull(over)
    assertEquals("Low inventory", over!!.first)
  }

  @Test fun toneMatchesWeb() {
    assertEquals(StockTone.SHORT, SarimStockCheck.tone(10.0, 11.0))
    assertEquals(StockTone.LOW, SarimStockCheck.tone(10.0, 8.0))
    assertEquals(StockTone.OK, SarimStockCheck.tone(100.0, 10.0))
    assertEquals(StockTone.OK, SarimStockCheck.tone(100.0, 0.0))
  }
}
