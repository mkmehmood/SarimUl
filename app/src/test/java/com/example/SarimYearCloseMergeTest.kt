package com.example

import com.example.data.backup.BackupRestoreLogic
import com.example.data.backup.MergeContext
import com.example.data.backup.Rec
import com.example.data.backup.YearCloseMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SarimYearCloseMergeTest {
  private var n = 0
  private fun ctx(close: Long = 2_000L) = MergeContext(
    closeEpoch = close, nowEpoch = 5_000L, nowIsoDate = "2026-11-28", nowTime = "10:00:00 AM", nowIsoStamp = "2026-11-28T10:00:00.000Z",
    newId = { p -> "$p-${++n}" }, salePriceForStore = { 100.0 }, salePriceForCustomer = { _, _ -> 0.0 }, defaultStoreKey = "zubair",
  )

  private fun sale(id: String, cust: String, qty: Int, value: Int, cost: Int, type: String, ts: Long, vararg extra: Pair<String, Any?>): Rec =
    mapOf<String, Any?>("id" to id, "customerName" to cust, "quantity" to qty, "totalValue" to value, "totalCost" to cost,
      "profit" to value - cost, "paymentType" to type, "date" to "2026-01-02", "createdAt" to ts) + extra

  @Test fun foldsCustomerSalesIntoOneOpeningBalance() {
    val sales = listOf<Rec>(
      sale("s1", "Ali", 10, 1000, 600, "CASH", 1000),
      sale("s2", "Ali", 5, 500, 300, "CREDIT", 1100, "creditReceived" to false),
      mapOf("id" to "c1", "customerName" to "Ali", "totalValue" to 200, "paymentType" to "COLLECTION", "createdAt" to 1200),
      sale("s3", "Bob", 4, 400, 240, "CASH", 1300),
      sale("post", "Ali", 1, 100, 60, "CASH", 3000),
    )
    val out = YearCloseMerge.mergeAll(mapOf("sales" to sales), ctx())
    val merged = out.newMerged["sales"]!!
    assertEquals(2, merged.size)
    val ali = merged.first { it["customerName"] == "Ali" }
    assertEquals(1500.0, (ali["totalValue"] as Number).toDouble(), 1e-9)
    assertEquals("CREDIT", ali["paymentType"])
    assertEquals(300.0, (ali["creditValue"] as Number).toDouble(), 1e-9) // 500 unpaid - 200 collected
    assertEquals(true, ali["isMerged"])
    assertEquals(listOf("sale-merged-1", "sale-merged-2", "post").size, out.collections["sales"]!!.size)
    assertTrue("post" in out.collections["sales"]!!.map { it["id"] })        // created after the close stays
    assertEquals(setOf("s1", "s2", "c1", "s3"), out.removedIds["sales"]!!.toSet())
    assertTrue(YearCloseMerge.verifyConsistency(out.collections).valid)
  }

  @Test fun paymentsNetPerEntityAndDropZeroBalances() {
    val tx = listOf<Rec>(
      mapOf("id" to "t1", "entityId" to "e1", "type" to "IN", "amount" to 500, "createdAt" to 1000),
      mapOf("id" to "t2", "entityId" to "e1", "type" to "OUT", "amount" to 200, "createdAt" to 1001),
      mapOf("id" to "t3", "entityId" to "e2", "type" to "IN", "amount" to 100, "createdAt" to 1002),
      mapOf("id" to "t4", "entityId" to "e2", "type" to "OUT", "amount" to 100, "createdAt" to 1003),
    )
    val out = YearCloseMerge.mergeAll(mapOf("transactions" to tx, "entities" to listOf<Rec>(mapOf("id" to "e1", "name" to "Supplier A"))), ctx())
    val merged = out.newMerged["transactions"]!!
    assertEquals(1, merged.size)
    assertEquals(300.0, (merged[0]["amount"] as Number).toDouble(), 1e-9)
    assertEquals("IN", merged[0]["type"])
    assertEquals("Supplier A", merged[0]["entityName"])
  }

  @Test fun productionKeepsTransfersAndSeparatesSellerReturns() {
    val prod = listOf<Rec>(
      mapOf("id" to "p1", "store" to "zubair", "net" to 50, "totalCost" to 1000, "totalSale" to 1500, "profit" to 500, "createdAt" to 1000),
      mapOf("id" to "r1", "store" to "zubair", "isReturn" to true, "returnedBy" to "Rep1", "net" to 5, "totalCost" to 100, "totalSale" to 150, "profit" to 50, "createdAt" to 1002),
      mapOf("id" to "tr1", "store" to "zubair", "isTransfer" to true, "net" to 7, "createdAt" to 1003),
    )
    val out = YearCloseMerge.mergeAll(mapOf("production" to prod), ctx())
    assertEquals(2, out.newMerged["production"]!!.size)
    assertTrue("tr1" in out.collections["production"]!!.map { it["id"] })
    assertEquals(setOf("p1", "r1"), out.removedIds["production"]!!.toSet())
  }

  @Test fun mergedRecordsAreRecognisedAndNotMergedAgain() {
    val merged: Rec = mapOf("id" to "sale-merged-abc", "customerName" to "Ali", "createdAt" to 100)
    assertTrue(YearCloseMerge.isMerged(merged))
    assertFalse(YearCloseMerge.isMerged(mapOf("id" to "sale-123")))
    val out = YearCloseMerge.mergeAll(mapOf("sales" to listOf(merged)), ctx())
    assertEquals(0, out.newMerged["sales"]!!.size)
  }

  @Test fun mergeRestoreAddsUpdatesAndSkipsByVersionAndHonoursDeletions() {
    fun r(id: String, ts: Long): Rec = mapOf("id" to id, "updatedAt" to ts)
    val plan = BackupRestoreLogic.planMergeRestore(
      local = mapOf("sales" to listOf(r("a", 100), r("b", 300))),
      backup = mapOf("sales" to listOf(r("a", 200), r("b", 100), r("c", 50), r("gone", 10), r("a", 150))),
      deleted = setOf("gone"),
    )
    assertEquals(1, plan.added); assertEquals(1, plan.updated); assertEquals(1, plan.skipped)
    assertEquals(listOf("a", "c"), plan.toApply["sales"]!!.map { it["id"] })
  }

  @Test fun yearCloseReversalDropsMergedKeepsPostCloseAndHonoursDeletions() {
    fun r(id: String, ts: Long, vararg kv: Pair<String, Any?>): Rec = mapOf<String, Any?>("id" to id, "updatedAt" to ts, "createdAt" to ts) + kv
    val current = mapOf("sales" to listOf(r("sale-merged-1", 900, "isMerged" to true), r("post1", 950), r("old", 1)))
    val backup = mapOf("sales" to listOf(r("s1", 10), r("s2", 11)))
    val keep = BackupRestoreLogic.planYearCloseRestore(current, backup, setOf("s2"), true, 500, emptySet())
    assertEquals(listOf("s1", "post1"), keep.replaceData["sales"]!!.map { it["id"] })
    assertEquals(listOf("sale-merged-1"), keep.mergedToTombstone)
    val all = BackupRestoreLogic.planYearCloseRestore(current, backup, setOf("s2"), false, 500, emptySet())
    assertEquals(listOf("s1", "s2", "post1"), all.replaceData["sales"]!!.map { it["id"] })
  }

  @Test fun mergeRestoreFiltersRepNamesOutOfCustomersAndYearCloseReplacesInventory() {
    val mergePlan = BackupRestoreLogic.planMergeRestore(
      local = emptyMap(),
      backup = mapOf(
        "customers" to listOf(
          mapOf("id" to "c_rep", "name" to "Farman", "updatedAt" to 100L),
          mapOf("id" to "c_dir", "name" to "Noor Trader", "updatedAt" to 100L),
        )
      ),
      deleted = emptySet(),
      repNames = setOf("farman"),
    )
    assertEquals(listOf("c_dir"), mergePlan.toApply["customers"]!!.map { it["id"] })

    val closeRestore = BackupRestoreLogic.planYearCloseRestore(
      current = mapOf(
        "inventory" to listOf(mapOf("id" to "m_post", "name" to "Post Close Mat", "createdAt" to 900L)),
        "entities" to listOf(mapOf("id" to "e_post", "name" to "Post Ent", "createdAt" to 900L)),
        "clients" to listOf(mapOf("id" to "cl_post", "name" to "New Rep Client", "createdAt" to 900L)),
      ),
      backup = mapOf(
        "inventory" to listOf(mapOf("id" to "m_old", "name" to "Pre Close Mat", "createdAt" to 100L)),
        "entities" to listOf(mapOf("id" to "e_old", "name" to "Pre Ent", "createdAt" to 100L)),
        "clients" to listOf(mapOf("id" to "cl_old", "name" to "Old Rep Client", "createdAt" to 100L)),
      ),
      deleted = emptySet(),
      honourPostCloseDeletions = true,
      backupCreatedAt = 500L,
      repNames = setOf("Farman"),
    )
    assertEquals(listOf("m_old"), closeRestore.replaceData["inventory"]!!.map { it["id"] })
    assertEquals(listOf("e_old"), closeRestore.replaceData["entities"]!!.map { it["id"] })
    assertEquals(setOf("cl_old", "cl_post"), closeRestore.replaceData["clients"]!!.map { it["id"] }.toSet())
  }

  @Test
  fun staleSavedProfitDoesNotFailConsistencyCheck() {
    // Saved profit (400) disagrees with sale - cost (500): the merge must still verify cleanly.
    val ctx = MergeContext(
      closeEpoch = 10_000L, nowEpoch = 10_000L, nowIsoDate = "2026-01-31", nowTime = "12:00:00 AM",
      nowIsoStamp = "2026-01-31T00:00:00.000Z", newId = { "$it-x" },
      salePriceForStore = { 30.0 }, salePriceForCustomer = { _, _ -> 30.0 }, defaultStoreKey = "zubair",
    )
    val prod = listOf(mapOf("id" to "p1", "store" to "zubair", "net" to 50, "totalCost" to 1000, "totalSale" to 1500, "profit" to 400, "createdAt" to 1000L))
    val merged = YearCloseMerge.mergeProduction(prod, ctx)
    val check = YearCloseMerge.verifyConsistency(mapOf("production" to merged))
    assertTrue(check.errors.joinToString(), check.valid)
  }
}
