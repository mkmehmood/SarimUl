package com.example

import com.example.data.local.ProductionEntryEntity
import com.example.data.local.SaleTransactionEntity
import com.example.data.local.StockTransferEntity
import com.example.data.repository.SarimStoreSummary
import com.example.data.repository.SarimStoreSummary.Period
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class SarimStoreSummaryTest {
  private fun ep(day: Int) = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, day, 12, 0, 0) }.timeInMillis

  private fun prod(store: String, net: Double, day: Int, isReturn: Boolean = false, value: Double = 0.0, profit: Double = 0.0) =
    ProductionEntryEntity(
      dateEpoch = ep(day), storeKey = store, storeName = store.uppercase(), formulaId = "f", formulaName = "F",
      grossWeightKg = net, containerWeightKg = 0.0, netWeightKg = net, formulaUnitsUsed = 1.0, formulaUnitCost = 0.0,
      totalFormulaCost = 0.0, costPerKg = 0.0, salePricePerKg = 0.0, profitPerKg = 0.0, totalValue = value, totalProfit = profit,
      isReturn = isReturn,
    )

  private fun sale(store: String, who: String, id: String, qty: Double, day: Int, rep: Boolean = false) =
    SaleTransactionEntity(
      dateEpoch = ep(day), mode = "SALE", customerId = id, customerName = who, storeKey = store, quantityKg = qty, isRepSale = rep,
    )

  private val stores = listOf("a" to "A", "b" to "B")

  @Test fun perStoreRemainingAndSoldByCustomer() {
    val o = SarimStoreSummary.summarize(
      stores,
      listOf(prod("a", 100.0, 29), prod("a", 20.0, 29, isReturn = true), prod("b", 30.0, 29)),
      listOf(sale("a", "Ali", "c1", 40.0, 29), sale("a", "Bashir", "c2", 10.0, 29), sale("b", "Ali", "c1", 5.0, 29), sale("a", "Rep", "c3", 77.0, 29, rep = true)),
      listOf(StockTransferEntity(dateEpoch = ep(29), fromStoreKey = "a", fromStoreName = "A", toStoreKey = "b", toStoreName = "B", quantityKg = 10.0)),
      Period.DAY, ep(29),
    )
    val a = o.stores[0]
    assertEquals(90.0, a.production, 1e-9); assertEquals(20.0, a.returns, 1e-9); assertEquals(50.0, a.sold, 1e-9)
    assertEquals(60.0, a.remaining, 1e-9); assertEquals(55, a.percentLeft)
    assertEquals(listOf("Ali", "Bashir"), a.soldByCustomer.map { it.name })
    assertEquals("c1", a.soldByCustomer[0].customerId)
    assertEquals(40.0, o.stores[1].production, 1e-9)
    val top = o.combined.soldByCustomer.first()
    assertEquals("Ali", top.name); assertEquals(45.0, top.kg, 1e-9)
    assertTrue(o.combined.soldByCustomer.none { it.name == "Rep" })
  }

  @Test fun periodsAndStoreDayHistory() {
    assertTrue(SarimStoreSummary.inPeriod(ep(23), Period.WEEK, ep(29)))
    assertFalse(SarimStoreSummary.inPeriod(ep(22), Period.WEEK, ep(29)))
    val rows = listOf(prod("a", 100.0, 29, profit = 400.0), prod("a", 50.0, 20), prod("b", 30.0, 29))
    val st = SarimStoreSummary.periodStats(rows.filter { it.storeKey == "a" }, ep(29))
    assertEquals(100.0, st[Period.DAY]!!.qty, 1e-9); assertEquals(150.0, st[Period.MONTH]!!.qty, 1e-9)
    assertEquals(1, SarimStoreSummary.storeDayHistory(rows, "a", ep(29)).size)
  }
}
