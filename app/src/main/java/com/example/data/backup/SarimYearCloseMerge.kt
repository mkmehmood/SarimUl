package com.example.data.backup

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/** A web-shaped record (Firestore document / backup JSON object). */
typealias Rec = Map<String, Any?>

/** Services the merge needs from the app (prices, ids, clock) so the maths stays pure and testable. */
class MergeContext(
  val closeEpoch: Long,
  val nowEpoch: Long,
  val nowIsoDate: String,
  val nowTime: String,
  val nowIsoStamp: String,
  val newId: (prefix: String) -> String,
  val salePriceForStore: (storeKey: String) -> Double,
  val salePriceForCustomer: (customer: String, storeKey: String) -> Double,
  val defaultStoreKey: String,
)

class MergeOutcome(
  /** Full new content of each collection: existing merged + kept + new merged + post-close records. */
  val collections: Map<String, List<Rec>>,
  /** Only the newly created merged records per collection (what must be written to the cloud). */
  val newMerged: Map<String, List<Rec>>,
  /** Ids that existed before and are gone now (to delete locally and in the cloud). */
  val removedIds: Map<String, List<String>>,
)

class ConsistencyResult(val valid: Boolean, val errors: List<String>, val warnings: List<String>)

/**
 * Port of the web app's Close Financial Year merge (modules/admin-data.js `merge*Data`).
 * Every old record is folded into one opening-balance record per group; merged records carry
 * `isMerged: true` and an id like `sale-merged-<uuid>`.
 */
object YearCloseMerge {
  val COLLECTIONS = listOf("production", "sales", "calculator", "transactions", "factory", "rep", "expenses", "returns")

  private val MERGED_ID = Regex("^(prod|ret|sale|calc|pay|fprod|exp)-merged-")

  fun isMergedId(id: String?): Boolean = !id.isNullOrBlank() && MERGED_ID.containsMatchIn(id)

  fun isMerged(r: Rec): Boolean = r["isMerged"] == true || isMergedId(str(r["id"]))

  internal fun num(v: Any?): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: 0.0
    else -> 0.0
  }
  internal fun str(v: Any?): String = when (v) { null -> ""; is String -> v; else -> v.toString() }
  private fun round2(x: Double) = round(x * 100.0) / 100.0
  private fun round4(x: Double) = round(x * 10000.0) / 10000.0

  /** `createdAt || timestamp || 0` — only numeric stamps count; anything else sorts as "old". */
  private fun recTs(r: Rec): Long {
    val c = num(r["createdAt"]); if (c != 0.0) return c.toLong()
    return num(r["timestamp"]).toLong()
  }

  private fun base(ctx: MergeContext, id: String, extra: Map<String, Any?>): Rec = linkedMapOf<String, Any?>(
    "id" to id,
    "date" to ctx.nowIsoDate,
    "time" to ctx.nowTime,
    "createdAt" to ctx.nowEpoch,
    "updatedAt" to ctx.nowEpoch,
    "timestamp" to ctx.nowEpoch,
    "isMerged" to true,
    "mergedAt" to ctx.nowIsoDate,
    "syncedAt" to ctx.nowIsoStamp,
  ).also { it.putAll(extra) }

  private fun dateRange(ctx: MergeContext, items: List<Rec>): Map<String, Any?> {
    val dates = items.map { str(it["date"]) }.filter { it.isNotEmpty() }.sorted()
    return mapOf("from" to (dates.firstOrNull() ?: ctx.nowIsoDate), "to" to (dates.lastOrNull() ?: ctx.nowIsoDate))
  }

  // ---------------------------------------------------------------- entry point

  fun mergeAll(input: Map<String, List<Rec>>, ctx: MergeContext): MergeOutcome {
    val out = linkedMapOf<String, List<Rec>>()
    val created = linkedMapOf<String, List<Rec>>()
    val removed = linkedMapOf<String, List<String>>()
    for (col in COLLECTIONS) {
      val original = input[col].orEmpty()
      val merged: List<Rec> = when (col) {
        "production" -> mergeProduction(original, ctx)
        "sales" -> mergeSales(original, ctx)
        "calculator" -> mergeCalculator(original, ctx)
        "transactions" -> mergePayments(original, input["entities"].orEmpty(), ctx)
        "factory" -> mergeFactory(original, ctx)
        "rep" -> mergeRep(original, ctx)
        "expenses" -> mergeExpenses(original, ctx)
        else -> mergeReturns(original, ctx)
      }
      // `merged` is only the NEW merged records here; assemble the final list below.
      val existingMerged = original.filter { isMerged(it) }
      val postClose = original.filter { !isMerged(it) && recTs(it) > ctx.closeEpoch }
      val kept = if (col == "production") original.filter { !isMerged(it) && recTs(it) <= ctx.closeEpoch && it["isTransfer"] == true } else emptyList()
      val finalList = if (merged.isEmpty() && original.none { !isMerged(it) && recTs(it) <= ctx.closeEpoch }) {
        original
      } else {
        existingMerged + kept + merged + postClose
      }
      out[col] = finalList
      created[col] = merged
      val keptIds = finalList.map { str(it["id"]) }.toSet()
      removed[col] = original.map { str(it["id"]) }.filter { it.isNotEmpty() && it !in keptIds }
    }
    return MergeOutcome(out, created, removed)
  }

  // ---------------------------------------------------------------- production

  fun mergeProduction(production: List<Rec>, ctx: MergeContext): List<Rec> {
    if (production.isEmpty()) return emptyList()
    val nonMerged = production.filter { !isMerged(it) && recTs(it) <= ctx.closeEpoch }
    val prodItems = nonMerged.filter { it["isReturn"] != true && it["isTransfer"] != true }
    val returnItems = nonMerged.filter { it["isReturn"] == true && it["isTransfer"] != true }
    val result = mutableListOf<Rec>()

    prodItems.groupBy { str(it["store"]).ifEmpty { "UNKNOWN" } }.forEach { (store, items) ->
      val net = items.sumOf { num(it["net"]) }
      val totalCost = items.sumOf { num(it["totalCost"]) }
      val totalSale = items.sumOf { num(it["totalSale"]) }
      val profit = items.sumOf { num(it["profit"]) }
      val formulaUnits = items.sumOf { num(it["formulaUnits"]) }
      val formulaCost = items.sumOf { num(it["formulaCost"]) }
      val avgCp = if (net > 0) round4(totalCost / net) else num(items.first()["cp"])
      val canonicalSp = ctx.salePriceForStore(store)
      val avgSp = if (canonicalSp > 0) canonicalSp else num(items.first()["sp"])
      result += base(ctx, ctx.newId("prod-merged"), mapOf(
        "store" to store, "net" to net, "cp" to avgCp, "sp" to avgSp,
        "totalCost" to totalCost, "totalSale" to totalSale, "profit" to profit,
        "formulaUnits" to formulaUnits, "formulaId" to str(items.first()["formulaId"]), "formulaCost" to formulaCost,
        "paymentStatus" to "CASH", "mergedRecordCount" to items.size,
        "mergedSummary" to mapOf("dateRange" to dateRange(ctx, items), "recordCount" to items.size),
      ))
    }

    val sellerTotals = linkedMapOf<String, MutableMap<String, Double>>()
    val sellerStoreTotals = linkedMapOf<String, Double>()
    returnItems.forEach { item ->
      val seller = str(item["returnedBy"]).ifEmpty { str(item["seller"]).ifEmpty { "Unknown" } }
      val store = str(item["store"]).ifEmpty { "UNKNOWN" }
      sellerTotals.getOrPut(seller) { linkedMapOf() }.merge(store, num(item["net"]), Double::plus)
      sellerStoreTotals.merge("$seller::$store", 0.0, Double::plus)
    }
    returnItems.groupBy {
      val seller = str(it["returnedBy"]).ifEmpty { str(it["seller"]).ifEmpty { "Unknown" } }
      seller to str(it["store"]).ifEmpty { "UNKNOWN" }
    }.forEach { (key, items) ->
      val (seller, store) = key
      val net = items.sumOf { num(it["net"]) }
      val totalCost = items.sumOf { num(it["totalCost"]) }
      val totalSale = items.sumOf { num(it["totalSale"]) }
      val profit = items.sumOf { num(it["profit"]) }
      val avgCp = if (net > 0) round4(totalCost / net) else num(items.first()["cp"])
      val canonicalSp = ctx.salePriceForStore(store)
      val avgSp = if (canonicalSp > 0) canonicalSp else num(items.first()["sp"])
      val byStore: Map<String, Any?> = sellerTotals[seller].orEmpty()
      result += base(ctx, ctx.newId("ret-merged"), mapOf(
        "store" to store, "net" to net, "cp" to avgCp, "sp" to avgSp,
        "totalCost" to totalCost, "totalSale" to totalSale, "profit" to profit,
        "formulaUnits" to 0.0, "formulaId" to "", "formulaCost" to 0.0, "paymentStatus" to "CASH",
        "isReturn" to true, "returnedBy" to seller, "returnNote" to "Merged returns by $seller → $store",
        "returnsByStore" to byStore, "mergedRecordCount" to items.size,
        "mergedSummary" to mapOf(
          "dateRange" to dateRange(ctx, items), "recordCount" to items.size,
          "store" to store, "seller" to seller, "returnsByStore" to byStore,
        ),
      ))
    }
    return result
  }

  // ---------------------------------------------------------------- sales + rep sales

  private class Bucket {
    val sales = mutableListOf<Rec>()
    var oldDebt = 0.0
    var collectionTotal = 0.0
    var partialPaymentTotal = 0.0
    val partialPaymentsBySale = linkedMapOf<String, Double>()
    var phone = ""
    var address = ""
    var supplyStore = ""
  }

  private fun classify(b: Bucket, item: Rec) {
    val paymentType = str(item["paymentType"])
    val total = num(item["totalValue"])
    if (paymentType == "PARTIAL_PAYMENT") {
      b.partialPaymentTotal += total
      val linked = str(item["relatedSaleId"]).ifEmpty { str(item["linkedSaleId"]) }
      if (linked.isNotEmpty()) b.partialPaymentsBySale.merge(linked, total, Double::plus)
      return
    }
    if (paymentType == "COLLECTION") { b.collectionTotal += total; return }
    if (str(item["transactionType"]) == "OLD_DEBT") {
      b.oldDebt += max(0.0, total - num(item["partialPaymentReceived"]))
      return
    }
    b.sales += item
  }

  private class Sums(
    val qty: Double, val value: Double, val cost: Double, val profit: Double,
    val realized: Double, val unrealized: Double, val cash: Double, val unpaidCredit: Double,
  )

  private fun sumBucket(sales: List<Rec>): Sums {
    var q = 0.0; var v = 0.0; var c = 0.0; var p = 0.0; var rp = 0.0; var up = 0.0; var cash = 0.0; var unpaid = 0.0
    for (item in sales) {
      q += num(item["quantity"]); v += num(item["totalValue"]); c += num(item["totalCost"]); p += num(item["profit"])
      val pt = str(item["paymentType"]); val received = item["creditReceived"] == true
      if (pt == "CREDIT" && !received) up += num(item["profit"]) else rp += num(item["profit"])
      if (pt == "CASH" || (pt == "CREDIT" && received)) cash += num(item["totalValue"])
      else if (pt == "CREDIT" && !received) unpaid += max(0.0, num(item["totalValue"]) - num(item["partialPaymentReceived"]))
    }
    return Sums(q, v, c, p, rp, up, cash, unpaid)
  }

  private fun settlementFields(
    ctx: MergeContext, b: Bucket, s: Sums, recordCount: Int, originalIds: List<String>,
    grossSaleValue: Double,
  ): Map<String, Any?> {
    val gross = s.unpaidCredit + b.oldDebt
    val net = max(0.0, gross - b.collectionTotal)
    val advance = max(0.0, b.collectionTotal - gross)
    val settled = net <= 0
    val alreadyPaid = if (settled) grossSaleValue else round2(grossSaleValue - net)
    return linkedMapOf(
      "paymentType" to if (settled) "CASH" else "CREDIT",
      "transactionType" to if (b.oldDebt > 0 && b.sales.isEmpty()) "OLD_DEBT" else "SALE",
      "creditReceived" to settled,
      "creditReceivedDate" to if (settled) ctx.nowIsoDate else null,
      "creditValue" to if (settled) 0.0 else net,
      "partialPaymentReceived" to if (settled) 0.0 else alreadyPaid,
      "balancePaid" to alreadyPaid,
      "paid" to settled,
      "notes" to "Combined year-end balance carried forward from financial year close",
      "mergedRecordCount" to recordCount,
      "mergedSummary" to mapOf(
        "cashSales" to s.cash, "unpaidCredit" to s.unpaidCredit, "oldDebt" to b.oldDebt,
        "collectionsReceived" to b.collectionTotal, "partialPayments" to b.partialPaymentTotal,
        "partialPaymentsBySale" to b.partialPaymentsBySale, "advanceCreditHeld" to advance,
        "realizedProfit" to s.realized, "unrealizedProfit" to s.unrealized,
        "grossOutstanding" to gross, "netOutstanding" to net, "isSettled" to settled,
        "dateRange" to dateRange(ctx, b.sales), "recordCount" to recordCount, "originalRecordIds" to originalIds,
      ),
    )
  }

  fun mergeSales(sales: List<Rec>, ctx: MergeContext): List<Rec> {
    if (sales.isEmpty()) return emptyList()
    val buckets = linkedMapOf<String, Bucket>()
    sales.forEach { item ->
      if (isMerged(item) || recTs(item) > ctx.closeEpoch) return@forEach
      val name = str(item["customerName"]).ifEmpty { "Unknown" }
      val b = buckets.getOrPut(name) { Bucket() }
      if (b.phone.isEmpty()) b.phone = str(item["customerPhone"])
      if (b.address.isEmpty()) b.address = str(item["customerAddress"])
      if (b.supplyStore.isEmpty()) b.supplyStore = str(item["supplyStore"]).ifEmpty { ctx.defaultStoreKey }
      classify(b, item)
    }
    val result = mutableListOf<Rec>()
    for ((customer, b) in buckets) {
      if (b.sales.isEmpty() && b.oldDebt <= 0 && b.collectionTotal <= 0) continue
      val s = sumBucket(b.sales)
      val first = b.sales.firstOrNull() ?: emptyMap()
      val recordCount = b.sales.size + (if (b.oldDebt > 0) 1 else 0) + (if (b.collectionTotal > 0) 1 else 0)
      val store = b.supplyStore.ifEmpty { str(first["supplyStore"]).ifEmpty { ctx.defaultStoreKey } }
      val canonical = ctx.salePriceForCustomer(customer, store)
      val unitPrice = if (canonical > 0) canonical else {
        num(first["unitPrice"]).takeIf { it != 0.0 }
          ?: if (num(first["quantity"]) > 0) num(first["totalValue"]) / num(first["quantity"]) else 0.0
      }
      val gross = round2(s.value)
      result += base(ctx, ctx.newId("sale-merged"), linkedMapOf<String, Any?>(
        "customerName" to customer, "customerPhone" to b.phone, "customerAddress" to b.address,
        "quantity" to s.qty, "unitPrice" to unitPrice, "totalValue" to gross, "totalCost" to s.cost, "profit" to s.profit,
        "supplyStore" to store, "salesRep" to "NONE",
      ).also { it.putAll(settlementFields(ctx, b, s, recordCount, b.sales.map { x -> str(x["id"]) }, gross)) })
    }
    return result
  }

  fun mergeRep(rep: List<Rec>, ctx: MergeContext): List<Rec> {
    if (rep.isEmpty()) return emptyList()
    class Key(val customer: String, val rep: String)
    val buckets = linkedMapOf<String, Pair<Key, Bucket>>()
    rep.forEach { item ->
      if (isMerged(item)) return@forEach
      // The web guard is `!item.salesRep === 'NONE' || item.salesRep === 'ADMIN'`, i.e. only ADMIN is skipped.
      if (str(item["salesRep"]) == "ADMIN") return@forEach
      if (recTs(item) > ctx.closeEpoch) return@forEach
      val name = str(item["customerName"]).ifEmpty { "Unknown" }
      val repName = str(item["salesRep"]).ifEmpty { "NONE" }
      val (_, b) = buckets.getOrPut("$name::$repName") { Key(name, repName) to Bucket() }
      if (b.phone.isEmpty()) b.phone = str(item["customerPhone"])
      if (b.supplyStore.isEmpty()) b.supplyStore = str(item["supplyStore"]).ifEmpty { ctx.defaultStoreKey }
      classify(b, item)
    }
    val result = mutableListOf<Rec>()
    for ((key, b) in buckets.values) {
      if (b.sales.isEmpty() && b.oldDebt <= 0 && b.collectionTotal <= 0) continue
      val s = sumBucket(b.sales)
      val first = b.sales.firstOrNull() ?: emptyMap()
      val recordCount = b.sales.size + (if (b.oldDebt > 0) 1 else 0) + (if (b.collectionTotal > 0) 1 else 0)
      val store = b.supplyStore.ifEmpty { str(first["supplyStore"]).ifEmpty { ctx.defaultStoreKey } }
      val canonical = ctx.salePriceForStore(store)
      val unitPrice = if (canonical > 0) canonical else {
        num(first["unitPrice"]).takeIf { it != 0.0 }
          ?: if (num(first["quantity"]) > 0) num(first["totalValue"]) / num(first["quantity"]) else 0.0
      }
      val gross = round2(s.value)
      result += base(ctx, ctx.newId("sale-merged"), linkedMapOf<String, Any?>(
        "customerName" to key.customer, "customerPhone" to b.phone,
        "quantity" to s.qty, "unitPrice" to unitPrice, "totalValue" to gross, "totalCost" to s.cost, "profit" to s.profit,
        "salesRep" to key.rep, "supplyStore" to store,
      ).also { it.putAll(settlementFields(ctx, b, s, recordCount, b.sales.map { x -> str(x["id"]) }, gross)) })
    }
    return result
  }

  // ---------------------------------------------------------------- calculator (rep settlements)

  fun mergeCalculator(calc: List<Rec>, ctx: MergeContext): List<Rec> {
    if (calc.isEmpty()) return emptyList()
    val groups = linkedMapOf<String, MutableList<Rec>>()
    calc.forEach { item ->
      if (isMerged(item) || recTs(item) > ctx.closeEpoch) return@forEach
      groups.getOrPut(str(item["seller"]).ifEmpty { "Unknown" }) { mutableListOf() } += item
    }
    val result = mutableListOf<Rec>()
    for ((seller, items) in groups) {
      val first = items.first()
      val totalSold = items.sumOf { num(it["totalSold"]) }
      val returned = items.sumOf { num(it["returned"]) }
      val returnsByStore = linkedMapOf<String, Double>()
      items.forEach { if (num(it["returned"]) > 0 && str(it["returnStore"]).isNotEmpty()) returnsByStore.merge(str(it["returnStore"]), num(it["returned"]), Double::plus) }
      val totalCost = items.sumOf { num(it["totalCost"]) }
      val mergedNetSold = totalSold - returned
      val canonical = ctx.salePriceForStore(ctx.defaultStoreKey)
      val avgUnitPrice = if (canonical > 0) canonical else num(first["unitPrice"])
      val avgCost = if (mergedNetSold > 0) round4(totalCost / mergedNetSold) else num(first["costPrice"])
      val primaryReturnStore = returnsByStore.entries.maxByOrNull { it.value }?.key
      result += base(ctx, ctx.newId("calc-merged"), mapOf(
        "seller" to seller, "unitPrice" to avgUnitPrice, "costPrice" to avgCost,
        "revenue" to items.sumOf { num(it["revenue"]) }, "profit" to items.sumOf { num(it["profit"]) }, "totalCost" to totalCost,
        "totalSold" to totalSold, "returned" to returned, "returnStore" to primaryReturnStore, "returnsByStore" to returnsByStore,
        "creditQty" to items.sumOf { num(it["creditQty"]) }, "cashQty" to items.sumOf { num(it["cashQty"]) },
        "creditValue" to items.sumOf { num(it["creditValue"]) }, "prevColl" to items.sumOf { num(it["prevColl"]) },
        "totalExpected" to items.sumOf { num(it["totalExpected"]) }, "received" to items.sumOf { num(it["received"]) },
        "statusText" to "OPENING BALANCE", "statusClass" to "result-box callout discrepancy-ok",
        "linkedSalesIds" to emptyList<String>(), "linkedRepSalesIds" to emptyList<String>(), "linkedRepReturnIds" to emptyList<String>(),
        "mergedRecordCount" to items.size,
        "mergedSummary" to mapOf("dateRange" to dateRange(ctx, items), "recordCount" to items.size, "returnsByStore" to returnsByStore),
      ))
    }
    return result
  }

  // ---------------------------------------------------------------- payments

  fun mergePayments(tx: List<Rec>, entities: List<Rec>, ctx: MergeContext): List<Rec> {
    if (tx.isEmpty()) return emptyList()
    val groups = linkedMapOf<String, MutableList<Rec>>()
    tx.forEach { item ->
      if (isMerged(item) || recTs(item) > ctx.closeEpoch) return@forEach
      groups.getOrPut(str(item["entityId"]).ifEmpty { "unknown" }) { mutableListOf() } += item
    }
    val result = mutableListOf<Rec>()
    for ((entityId, items) in groups) {
      val entity = entities.firstOrNull { str(it["id"]) == entityId }
      val totalIn = items.filter { str(it["type"]) == "IN" }.sumOf { num(it["amount"]) }
      val totalOut = items.filter { str(it["type"]) == "OUT" }.sumOf { num(it["amount"]) }
      val net = round2(totalIn - totalOut)
      if (abs(net) < 0.01) continue
      result += base(ctx, ctx.newId("pay-merged"), mapOf(
        "entityId" to entityId,
        "entityName" to (entity?.get("name")?.let { str(it) }?.ifEmpty { null } ?: str(items.first()["entityName"]).ifEmpty { "Unknown Entity" }),
        "entityType" to (entity?.get("type")?.let { str(it) }?.ifEmpty { null } ?: str(items.first()["entityType"]).ifEmpty { "payee" }),
        "amount" to abs(net), "type" to if (net > 0) "IN" else "OUT",
        "description" to if (net > 0) "Opening balance (receivable) — carried from previous year (${items.size} txns)"
        else "Opening balance (payable) — carried from previous year (${items.size} txns)",
        "isPayable" to (net < 0), "isExpense" to false, "mergedRecordCount" to items.size,
        "mergedSummary" to mapOf(
          "originalIn" to totalIn, "originalOut" to totalOut, "netBalance" to net,
          "dateRange" to dateRange(ctx, items), "recordCount" to items.size,
          "hasSupplierMaterials" to items.any { it["isPayable"] == true && str(it["type"]) == "OUT" },
        ),
      ))
    }
    return result
  }

  // ---------------------------------------------------------------- factory

  fun mergeFactory(factory: List<Rec>, ctx: MergeContext): List<Rec> {
    val nonMerged = factory.filter { !isMerged(it) && recTs(it) <= ctx.closeEpoch }
    if (nonMerged.isEmpty()) return emptyList()
    val result = mutableListOf<Rec>()
    nonMerged.groupBy { str(it["formulaId"]) + "|" + str(it["store"]) }.values.forEach { items ->
      val store = str(items.first()["store"])
      val formulaId = str(items.first()["formulaId"])
      val materials = items.sumOf { num(it["materialsCost"]) }
      val additional = items.sumOf { num(it["additionalCost"]) }
      var totalCost = items.sumOf { num(it["totalCost"]) }
      val expected = materials + additional
      if (abs(expected - totalCost) > 0.01) totalCost = expected
      result += base(ctx, ctx.newId("fprod-merged"), linkedMapOf<String, Any?>("store" to store).also { m ->
        if (formulaId.isNotEmpty()) m["formulaId"] = formulaId
        m["units"] = items.sumOf { num(it["units"]) }
        m["totalCost"] = totalCost
        m["materialsCost"] = materials
        m["additionalCost"] = additional
        m["rawMaterialsUsed"] = items.sumOf { num(it["rawMaterialsUsed"]) }
        m["notes"] = "Opening balance ($store) — carried from previous year (${items.size} records)"
        m["mergedRecordCount"] = items.size
        m["mergedSummary"] = mapOf("dateRange" to dateRange(ctx, items), "recordCount" to items.size)
      })
    }
    return result
  }

  // ---------------------------------------------------------------- expenses

  fun mergeExpenses(expenses: List<Rec>, ctx: MergeContext): List<Rec> {
    if (expenses.isEmpty()) return emptyList()
    val groups = linkedMapOf<String, Triple<String, String, MutableList<Rec>>>()
    expenses.forEach { e ->
      if (isMerged(e) || recTs(e) > ctx.closeEpoch) return@forEach
      val cat = str(e["category"]).ifEmpty { "operating" }
      val name = str(e["name"]).ifEmpty { "Unnamed" }.trim()
      groups.getOrPut("$cat||$name") { Triple(cat, name, mutableListOf()) }.third += e
    }
    return groups.values.map { (cat, name, records) ->
      val total = round2(records.sumOf { num(it["amount"]) })
      base(ctx, ctx.newId("exp-merged"), mapOf(
        "name" to name, "amount" to total, "category" to cat,
        "description" to "Year-end merged total for \"$name\" (${records.size} record${if (records.size != 1) "s" else ""})",
        "mergedRecordCount" to records.size,
        "mergedSummary" to mapOf(
          "category" to cat, "expenseName" to name, "totalAmount" to total,
          "dateRange" to dateRange(ctx, records), "recordCount" to records.size,
        ),
      ))
    }
  }

  // ---------------------------------------------------------------- stock returns

  fun mergeReturns(returns: List<Rec>, ctx: MergeContext): List<Rec> {
    if (returns.isEmpty()) return emptyList()
    class G(val store: String, val date: String) { val records = mutableListOf<Rec>(); var qty = 0.0 }
    val groups = linkedMapOf<String, G>()
    returns.forEach { r ->
      if (isMerged(r) || recTs(r) > ctx.closeEpoch) return@forEach
      val store = str(r["store"]).ifEmpty { "UNKNOWN" }
      val date = str(r["date"]).ifEmpty { ctx.nowIsoDate }
      val g = groups.getOrPut("$store||$date") { G(store, date) }
      g.records += r; g.qty += num(r["quantity"])
    }
    return groups.values.map { g ->
      val breakdown = linkedMapOf<String, MutableMap<String, Any?>>()
      g.records.forEach { r ->
        val seller = str(r["seller"]).ifEmpty { "Unknown" }
        val e = breakdown.getOrPut(seller) { linkedMapOf("quantity" to 0.0, "recordCount" to 0) }
        e["quantity"] = num(e["quantity"]) + num(r["quantity"])
        e["recordCount"] = (e["recordCount"] as Int) + 1
      }
      val sellers = g.records.map { str(it["seller"]) }.filter { it.isNotEmpty() }.distinct()
      val qty = round4(g.qty)
      base(ctx, ctx.newId("ret-merged"), mapOf(
        "date" to g.date, "store" to g.store, "quantity" to qty,
        "seller" to (sellers.joinToString(", ").ifEmpty { "Multiple" }),
        "mergedRecordCount" to g.records.size,
        "mergedSummary" to mapOf(
          "store" to g.store, "date" to g.date, "totalQuantity" to qty,
          "contributingSellers" to sellers, "sellerBreakdown" to breakdown, "recordCount" to g.records.size,
        ),
      ))
    }
  }

  // ---------------------------------------------------------------- consistency (verifyMergeConsistency)

  fun verifyConsistency(c: Map<String, List<Rec>>): ConsistencyResult {
    val errors = mutableListOf<String>(); val warnings = mutableListOf<String>()
    val prod = c["production"].orEmpty().filter { isMerged(it) }
    val expectedP = prod.sumOf { num(it["totalSale"]) } - prod.sumOf { num(it["totalCost"]) }
    if (abs(expectedP - prod.sumOf { num(it["profit"]) }) > 0.01) errors += "Production profit mismatch: expected ${round2(expectedP)}, got ${round2(prod.sumOf { num(it["profit"]) })}"
    val sales = c["sales"].orEmpty().filter { isMerged(it) }
    val expectedS = sales.sumOf { num(it["totalValue"]) } - sales.sumOf { num(it["totalCost"]) }
    if (abs(expectedS - sales.sumOf { num(it["profit"]) }) > 0.01) errors += "Sales profit mismatch: expected ${round2(expectedS)}, got ${round2(sales.sumOf { num(it["profit"]) })}"
    sales.forEach { s ->
      if (str(s["paymentType"]) == "CREDIT" && s["creditReceived"] != true) {
        val exp = num(s["totalValue"]) - num(s["partialPaymentReceived"])
        if (abs(exp - num(s["creditValue"])) > 0.01) warnings += "Credit value mismatch for ${str(s["customerName"])}"
      }
    }
    c["transactions"].orEmpty().filter { isMerged(it) }.forEach { p ->
      @Suppress("UNCHECKED_CAST") val sum = p["mergedSummary"] as? Map<String, Any?> ?: return@forEach
      val expectedNet = num(sum["originalIn"]) - num(sum["originalOut"])
      val actual = (if (str(p["type"]) == "IN") 1 else -1) * num(p["amount"])
      if (abs(expectedNet - actual) > 0.01) errors += "Payment balance mismatch for ${str(p["entityName"])}"
    }
    return ConsistencyResult(errors.isEmpty(), errors, warnings)
  }
}
