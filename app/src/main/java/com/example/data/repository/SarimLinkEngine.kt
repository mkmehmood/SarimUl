package com.example.data.repository

import com.example.data.backup.YearCloseMerge
import com.example.data.local.CalculatorSettlementEntity
import com.example.data.local.CustomerEntity
import com.example.data.local.DailyLedgerEntity
import com.example.data.local.FactoryBatchEntity
import com.example.data.local.FormulaEntity
import com.example.data.local.JsonCodecs
import com.example.data.local.PaymentEntityRecord
import com.example.data.local.PaymentTransactionEntity
import com.example.data.local.PaymentTransferEntity
import com.example.data.local.ProductionEntryEntity
import com.example.data.local.RawMaterialEntity
import com.example.data.local.RawMaterialPurchaseEntity
import com.example.data.local.ReturnLogEntity
import com.example.data.local.SaleTransactionEntity
import com.example.data.local.StockTransferEntity
import com.example.data.local.StoreEntity
import com.example.util.SarimFormat
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

/**
 * Pure deterministic transaction-link graph ported from mkmehmood/sarim web
 * (modules/link-graph.js, modules/rep-return-link.js, modules/ledger-link.js,
 *  modules/link-repair.js, modules/finance.js, modules/utilities-payments.js, modules/data-ops.js).
 */
object SarimLinkEngine {

  fun round2(v: Double): Double {
    if (!v.isFinite()) return 0.0
    return round(v * 100.0) / 100.0
  }

  /** True when an Admin sale (`!isRepSale`) is delivered to a Rep ("Sales Representative (For Calculator Link)"). */
  fun isRepLinkedAdminSale(s: SaleTransactionEntity): Boolean {
    if (s.isRepSale || s.isDeleted) return false
    val rep = s.repName.trim()
    return rep.isNotBlank() &&
      !rep.equals("Direct", ignoreCase = true) &&
      !rep.equals("NONE", ignoreCase = true) &&
      !rep.equals("ADMIN", ignoreCase = true)
  }

  fun reconciledRepReturnIds(
    settlements: List<CalculatorSettlementEntity>,
    excludeCalcId: String? = null,
  ): Set<String> {
    val ids = linkedSetOf<String>()
    settlements.forEach { h ->
      if (!h.isDeleted && h.id != excludeCalcId) {
        JsonCodecs.decodeStrings(h.linkedRepReturnIdsJson).forEach { if (it.isNotBlank()) ids += it }
      }
    }
    return ids
  }

  fun reconciledAdminSalesIds(
    settlements: List<CalculatorSettlementEntity>,
    excludeCalcId: String? = null,
  ): Set<String> {
    val ids = linkedSetOf<String>()
    settlements.forEach { h ->
      if (!h.isDeleted && h.id != excludeCalcId) {
        JsonCodecs.decodeStrings(h.linkedSalesIdsJson).forEach { if (it.isNotBlank()) ids += it }
      }
    }
    return ids
  }

  fun reconciledRepSalesIds(
    settlements: List<CalculatorSettlementEntity>,
    excludeCalcId: String? = null,
  ): Set<String> {
    val ids = linkedSetOf<String>()
    settlements.forEach { h ->
      if (!h.isDeleted && h.id != excludeCalcId) {
        JsonCodecs.decodeStrings(h.linkedRepSalesIdsJson).forEach { if (it.isNotBlank()) ids += it }
      }
    }
    return ids
  }

  /**
   * Matches web `finance.js::debtDelta`.
   * Note: When a Collection has `allocations`, only unallocated excess cash & expired goods credit remain on
   * the collection row so allocated credit sales (`partialPaymentReceived`) are not double-subtracted.
   */
  fun debtDelta(sale: SaleTransactionEntity, reconciledReturnIds: Set<String> = emptySet()): Double {
    if (sale.isDeleted) return 0.0
    if (sale.isRepReturn || sale.paymentType == "REP_RETURN") {
      val v = round2(sale.returnValue.coerceAtLeast(0.0))
      if (v <= 0.0) return 0.0
      return if (sale.id in reconciledReturnIds) 0.0 else -v
    }
    if (sale.mode == "OLD_DEBT" || sale.transactionType == "OLD_DEBT") {
      return if (sale.creditReceived) 0.0 else round2((sale.totalValue - sale.partialPaymentReceived).coerceAtLeast(0.0))
    }
    if (sale.mode == "SALE" && sale.paymentType == "CREDIT") {
      return if (sale.creditReceived) 0.0 else round2((sale.totalValue - sale.partialPaymentReceived).coerceAtLeast(0.0))
    }
    if (sale.mode == "COLLECTION" || sale.isCollection) {
      val allocs = JsonCodecs.decodeAllocations(sale.allocationsJson)
      val cashCollected = when {
        sale.collectedAmount > 0.0 -> sale.collectedAmount
        sale.amountReceived > 0.0 -> sale.amountReceived
        else -> sale.totalValue
      }
      if (allocs.isNotEmpty()) {
        val totalAlloc = allocs.sumOf { it.amount }
        val cashPaid = minOf(cashCollected, totalAlloc)
        val cashLeft = round2((cashCollected - cashPaid).coerceAtLeast(0.0))
        val creditAllocated = maxOf(sale.expiredAllocated, round2((totalAlloc - cashPaid).coerceAtLeast(0.0)))
        val expLeft = round2((sale.expiredValue - creditAllocated).coerceAtLeast(0.0))
        return round2(-(cashLeft + expLeft))
      }
      val expLeft = round2((sale.expiredValue - sale.expiredAllocated).coerceAtLeast(0.0))
      return round2(-(cashCollected.coerceAtLeast(0.0) + expLeft))
    }
    if (sale.mode == "MONEY_OUT" || sale.isMoneyOut) {
      val amt = sale.totalValue.takeIf { it > 0.0 } ?: sale.amountReceived
      return round2(amt.coerceAtLeast(0.0))
    }
    return 0.0
  }

  fun partyOutstanding(
    name: String,
    sales: List<SaleTransactionEntity>,
    isRepProfile: Boolean = false,
    settlements: List<CalculatorSettlementEntity> = emptyList(),
    excludeSaleId: String? = null,
  ): Double {
    val clean = name.trim()
    if (clean.isBlank()) return 0.0
    val reconciledReturns = reconciledRepReturnIds(settlements)
    var total = 0.0
    sales.forEach { s ->
      if (!s.isDeleted &&
        s.isRepSale == isRepProfile &&
        s.id != excludeSaleId &&
        s.customerName.equals(clean, ignoreCase = true)
      ) {
        total = round2(total + debtDelta(s, reconciledReturns))
      }
    }
    return total.coerceAtLeast(0.0)
  }

  fun goodsCreditUnitPrice(
    name: String,
    sales: List<SaleTransactionEntity>,
    fallbackPrice: Double = 0.0,
    isRepProfile: Boolean = false,
    excludeSaleId: String? = null,
  ): Double {
    val clean = name.trim()
    if (clean.isBlank()) return fallbackPrice
    val matches = sales.filter { s ->
      !s.isDeleted &&
        s.isRepSale == isRepProfile &&
        s.id != excludeSaleId &&
        s.customerName.equals(clean, ignoreCase = true) &&
        s.paymentType == "CREDIT" &&
        s.mode != "OLD_DEBT" &&
        s.transactionType != "OLD_DEBT" &&
        !s.isRepTransfer &&
        s.quantityKg > 0.0
    }
    fun weightedPrice(list: List<SaleTransactionEntity>): Double {
      val totQty = list.sumOf { it.quantityKg }
      val totVal = list.sumOf { it.totalValue }
      return if (totQty > 0.0001 && totVal > 0.0) round2(totVal / totQty) else 0.0
    }
    val unpaid = weightedPrice(matches.filter { !it.creditReceived })
    if (unpaid > 0.0) return unpaid
    val latest = matches.sortedWith(
      compareByDescending<SaleTransactionEntity> { it.dateStr.ifBlank { SarimFormat.dateInput(it.dateEpoch) } }
        .thenByDescending { it.dateEpoch }
    ).take(1)
    val latestPrice = weightedPrice(latest)
    if (latestPrice > 0.0) return latestPrice
    return fallbackPrice
  }

  data class GoodsCreditPlan(val value: Double, val capped: Boolean)

  fun planGoodsCredit(qtyKg: Double, unitPrice: Double, owed: Double): GoodsCreditPlan {
    if (qtyKg <= 0.0 || unitPrice <= 0.0) return GoodsCreditPlan(0.0, false)
    val raw = round2(qtyKg * unitPrice)
    if (owed < 0.0) return GoodsCreditPlan(raw, false)
    if (owed <= 0.001) return GoodsCreditPlan(0.0, true)
    val cappedVal = minOf(raw, round2(owed))
    return GoodsCreditPlan(cappedVal, raw > owed + 0.001)
  }

  /** Matches web `link-graph.js::collectionEligible`. OLD_DEBT is always eligible; regular credit must be <= collectionDate. */
  fun collectionEligible(sales: List<SaleTransactionEntity>, collectionDateStr: String): List<SaleTransactionEntity> {
    if (collectionDateStr.isBlank()) return sales
    return sales.filter { s ->
      s.mode == "OLD_DEBT" || s.transactionType == "OLD_DEBT" ||
        s.dateStr.ifBlank { SarimFormat.dateInput(s.dateEpoch) } <= collectionDateStr
    }
  }

  /** Matches web `link-graph.js::sortForCollection`: newest credit sales first, OLD_DEBT last. */
  fun sortForCollection(sales: List<SaleTransactionEntity>): List<SaleTransactionEntity> {
    return sales.sortedWith { a, b ->
      val ka = if (a.mode == "OLD_DEBT" || a.transactionType == "OLD_DEBT") 1 else 0
      val kb = if (b.mode == "OLD_DEBT" || b.transactionType == "OLD_DEBT") 1 else 0
      if (ka != kb) return@sortedWith ka - kb
      val da = a.dateStr.ifBlank { SarimFormat.dateInput(a.dateEpoch) }
      val db = b.dateStr.ifBlank { SarimFormat.dateInput(b.dateEpoch) }
      val dc = db.compareTo(da)
      if (dc != 0) dc else b.dateEpoch.compareTo(a.dateEpoch)
    }
  }

  fun planCollectionAllocation(
    poolAmount: Double,
    dues: List<Pair<String, Double>>,
  ): List<JsonCodecs.AllocationItem> {
    var rem = round2(poolAmount.coerceAtLeast(0.0))
    val allocs = mutableListOf<JsonCodecs.AllocationItem>()
    for ((saleId, dueRaw) in dues) {
      if (rem <= 0.001) break
      val due = round2(dueRaw.coerceAtLeast(0.0))
      if (due <= 0.001) continue
      val take = round2(minOf(rem, due))
      val full = take >= due - 0.001
      allocs += JsonCodecs.AllocationItem(saleId = saleId, amount = take, full = full)
      rem = round2(rem - take)
    }
    return allocs
  }

  fun getPendingRepDeliveries(
    repName: String,
    sales: List<SaleTransactionEntity>,
    settlements: List<CalculatorSettlementEntity>,
    excludeCalcId: String? = null,
  ): List<SaleTransactionEntity> {
    val clean = repName.trim()
    if (clean.isBlank() || clean.equals("COMPARISON", true) || clean.equals("COMBINED", true)) return emptyList()
    val taken = reconciledAdminSalesIds(settlements, excludeCalcId)
    return sales.filter { s ->
      !s.isDeleted &&
        !s.isRepSale &&
        s.customerName.equals(clean, ignoreCase = true) &&
        s.mode == "SALE" &&
        s.transactionType != "OLD_DEBT" &&
        s.paymentType == "CREDIT" &&
        !s.creditReceived &&
        s.id !in taken &&
        (s.usedInCalcId.isNullOrBlank() || s.usedInCalcId == excludeCalcId)
    }.sortedBy { it.dateEpoch }
  }

  fun getPendingRepReturns(
    repName: String,
    sales: List<SaleTransactionEntity>,
    settlements: List<CalculatorSettlementEntity>,
    toDateStr: String? = null,
    excludeCalcId: String? = null,
  ): List<SaleTransactionEntity> {
    val clean = repName.trim()
    if (clean.isBlank()) return emptyList()
    val done = reconciledRepReturnIds(settlements, excludeCalcId)
    return sales.filter { s ->
      !s.isDeleted &&
        !s.isRepSale &&
        (s.isRepReturn || s.paymentType == "REP_RETURN") &&
        s.customerName.equals(clean, ignoreCase = true) &&
        s.id !in done &&
        (toDateStr.isNullOrBlank() || s.dateStr.ifBlank { SarimFormat.dateInput(s.dateEpoch) } <= toDateStr)
    }.sortedBy { it.dateEpoch }
  }

  private fun findExactCoverSubset(
    items: List<SaleTransactionEntity>,
    targetR: Long,
    targetE: Long,
  ): List<SaleTransactionEntity>? {
    val n = items.size
    if (n == 0 || n > 24) return null
    val rVals = LongArray(n) { (items[it].returnedQtyKg.coerceAtLeast(0.0) * 100.0).roundToLong() }
    val eVals = LongArray(n) { (items[it].expiredQtyKg.coerceAtLeast(0.0) * 100.0).roundToLong() }
    val sufR = LongArray(n + 1)
    val sufE = LongArray(n + 1)
    for (i in n - 1 downTo 0) {
      sufR[i] = sufR[i + 1] + rVals[i]
      sufE[i] = sufE[i + 1] + eVals[i]
    }
    if (sufR[0] < targetR || sufE[0] < targetE) return null

    var steps = 0
    val chosen = ArrayList<SaleTransactionEntity>(n)
    fun dfs(idx: Int, curR: Long, curE: Long): Boolean {
      if (++steps > 200_000) return false
      if (curR == targetR && curE == targetE) return true
      if (idx >= n) return false
      if (curR + sufR[idx] < targetR || curE + sufE[idx] < targetE) return false

      val nextR = curR + rVals[idx]
      val nextE = curE + eVals[idx]
      if (nextR <= targetR && nextE <= targetE) {
        chosen.add(items[idx])
        if (dfs(idx + 1, nextR, nextE)) return true
        chosen.removeAt(chosen.lastIndex)
      }
      return dfs(idx + 1, curR, curE)
    }
    return if (dfs(0, 0L, 0L)) chosen.toList() else null
  }

  /**
   * Exact port of web `modules/rep-return-link.js::pickReturnsCovered`:
   * 1. Exact chronological subset-sum match up to 24 pending returns (200k step cap).
   * 2. Greedy chronological fit within remaining returned/expired targets.
   * 3. Bounded chronological remainder pass if needed.
   */
  fun pickReturnsCovered(
    pending: List<SaleTransactionEntity>,
    retTarget: Double,
    expTarget: Double,
  ): List<SaleTransactionEntity> {
    val cleanR = retTarget.coerceAtLeast(0.0)
    val cleanE = expTarget.coerceAtLeast(0.0)
    if (cleanR <= 1e-6 && cleanE <= 1e-6) return emptyList()

    val sorted = pending.filter {
      it.returnedQtyKg.coerceAtLeast(0.0) > 0.0 || it.expiredQtyKg.coerceAtLeast(0.0) > 0.0
    }.sortedWith(
      compareBy<SaleTransactionEntity> { it.dateStr.ifBlank { SarimFormat.dateInput(it.dateEpoch) } }
        .thenBy { it.dateEpoch }
    )
    if (sorted.isEmpty()) return emptyList()

    val exact = findExactCoverSubset(
      sorted,
      (cleanR * 100.0).roundToLong(),
      (cleanE * 100.0).roundToLong(),
    )
    if (exact != null) return exact

    var remR = cleanR
    var remE = cleanE
    val chosen = mutableListOf<SaleTransactionEntity>()
    val chosenIds = hashSetOf<String>()
    for (p in sorted) {
      val r = p.returnedQtyKg.coerceAtLeast(0.0)
      val e = p.expiredQtyKg.coerceAtLeast(0.0)
      if (r <= remR + 1e-6 && e <= remE + 1e-6) {
        chosen += p
        chosenIds += p.id
        remR = (remR - r).coerceAtLeast(0.0)
        remE = (remE - e).coerceAtLeast(0.0)
      }
    }
    var need = remR + remE
    for (p in sorted) {
      if (need <= 1e-6) break
      if (p.id in chosenIds) continue
      val qty = p.returnedQtyKg.coerceAtLeast(0.0) + p.expiredQtyKg.coerceAtLeast(0.0)
      if (qty <= 0.0) continue
      chosen += p
      chosenIds += p.id
      need -= qty
    }
    return chosen
  }

  data class CalcCycleSelection(
    val effectiveFromEpoch: Long,
    val effectiveToEpoch: Long,
    val selectedDeliveries: List<SaleTransactionEntity>,
    val pendingReturns: List<SaleTransactionEntity>,
    val coveredReturnIds: List<String>,
    val pendingRepSales: List<SaleTransactionEntity>,
    val totalSoldKg: Double,
    val returnedKg: Double,
    val expiredKg: Double,
    val creditSalesQty: Double,
    val recoveredCredit: Double,
    val totalCost: Double,
    val avgUnitPrice: Double,
  )

  fun getCalcCycleSelection(
    repName: String,
    sales: List<SaleTransactionEntity>,
    settlements: List<CalculatorSettlementEntity>,
    manualFromEpoch: Long?,
    toEpoch: Long,
    fallbackUnitPrice: Double = 400.0,
    excludeCalcId: String? = null,
  ): CalcCycleSelection {
    val pendingDeliveries = getPendingRepDeliveries(repName, sales, settlements, excludeCalcId)
    val toEnd = SarimFormat.endOfDay(toEpoch)
    val toDateStr = SarimFormat.dateInput(toEpoch)
    val earliestPending = pendingDeliveries.filter { it.dateEpoch <= toEnd }.minOfOrNull { it.dateEpoch }
    val effectiveFrom = manualFromEpoch ?: earliestPending ?: SarimFormat.startOfDay(toEpoch)
    val fromStart = SarimFormat.startOfDay(effectiveFrom)

    val deliveriesInWindow = pendingDeliveries.filter { it.dateEpoch in fromStart..toEnd }
    val pendingReturns = getPendingRepReturns(repName, sales, settlements, toDateStr, excludeCalcId)

    val takenRepSales = reconciledRepSalesIds(settlements, excludeCalcId)
    val pendingRepSales = sales.filter { s ->
      !s.isDeleted &&
        s.isRepSale &&
        s.repName.equals(repName, ignoreCase = true) &&
        s.id !in takenRepSales &&
        (s.usedInCalcId.isNullOrBlank() || s.usedInCalcId == excludeCalcId) &&
        s.dateEpoch in fromStart..toEnd
    }

    val totalSoldKg = round2(deliveriesInWindow.sumOf { it.quantityKg })
    val totalDelivValue = deliveriesInWindow.sumOf { it.totalValue }
    val totalCost = round2(deliveriesInWindow.sumOf { it.totalCost })
    val avgPrice = if (totalSoldKg > 0.0001 && totalDelivValue > 0.0) round2(totalDelivValue / totalSoldKg) else fallbackUnitPrice
    val returnedKg = round2(pendingReturns.sumOf { it.returnedQtyKg })
    val expiredKg = round2(pendingReturns.sumOf { it.expiredQtyKg })
    val creditSalesQty = round2(pendingRepSales.filter { it.mode == "SALE" && it.paymentType == "CREDIT" }.sumOf { it.quantityKg })
    val recoveredCredit = round2(
      pendingRepSales.filter { it.mode == "COLLECTION" || it.isCollection }
        .sumOf { it.amountReceived.takeIf { amt -> amt > 0.0 } ?: it.totalValue }
    )
    val coveredReturnIds = pickReturnsCovered(pendingReturns, returnedKg, expiredKg).map { it.id }

    return CalcCycleSelection(
      effectiveFromEpoch = effectiveFrom,
      effectiveToEpoch = toEpoch,
      selectedDeliveries = deliveriesInWindow,
      pendingReturns = pendingReturns,
      coveredReturnIds = coveredReturnIds,
      pendingRepSales = pendingRepSales,
      totalSoldKg = totalSoldKg,
      returnedKg = returnedKg,
      expiredKg = expiredKg,
      creditSalesQty = creditSalesQty,
      recoveredCredit = recoveredCredit,
      totalCost = totalCost,
      avgUnitPrice = avgPrice,
    )
  }

  /** Matches `link-graph.js::planSupplierCreditConsumption`. */
  fun planSupplierCreditConsumption(
    neededAmount: Double,
    entityInPayments: List<PaymentTransactionEntity>,
  ): List<JsonCodecs.ConsumedCreditItem> {
    var rem = round2(neededAmount.coerceAtLeast(0.0))
    if (rem <= 0.001) return emptyList()
    val candidates = entityInPayments
      .filter { !it.isDeleted && it.type == "PAYMENT_IN" && it.supplierCreditAmount > 0.001 }
      .sortedWith(compareBy<PaymentTransactionEntity> { it.dateEpoch }.thenBy { it.createdAt })
    val consumed = mutableListOf<JsonCodecs.ConsumedCreditItem>()
    for (p in candidates) {
      if (rem <= 0.001) break
      val take = round2(minOf(rem, p.supplierCreditAmount))
      if (take > 0.001) {
        consumed += JsonCodecs.ConsumedCreditItem(id = p.id, amount = take)
        rem = round2(rem - take)
      }
    }
    return consumed
  }

  // =====================================================================
  // Daily Ledger <-> Sales / Payments / Calculator Bidirectional Links
  // =====================================================================

  fun isTabOwnedLedgerRow(entry: DailyLedgerEntity): Boolean {
    val src = entry.sourceType.trim().uppercase()
    if (src in setOf("SALE", "PAYMENT", "CALCULATOR")) return true
    val link = JsonCodecs.decodeLedgerLink(entry.linkJson) ?: return false
    val kind = link["kind"]?.toString()?.trim()?.lowercase().orEmpty()
    // Tab-owned mirror links have sourceType SALE/PAYMENT/CALCULATOR; manual ledger->tab links have kind "entity"/"customer"/"expense".
    return kind.isEmpty() && (link.containsKey("saleId") || link.containsKey("calcId") || link.containsKey("txId"))
  }

  fun tabEntryNotice(entry: DailyLedgerEntity): String {
    val tabName = when (entry.sourceType.trim().uppercase()) {
      "SALE" -> "Sales"
      "PAYMENT" -> "Payments"
      "CALCULATOR" -> "Calculator"
      else -> {
        val link = JsonCodecs.decodeLedgerLink(entry.linkJson).orEmpty()
        when {
          link.containsKey("calcId") -> "Calculator"
          link.containsKey("txId") -> "Payments"
          else -> "Sales"
        }
      }
    }
    return "This entry comes from the $tabName tab. Edit or delete it there and the Daily Ledger updates automatically."
  }

  sealed interface LedgerLinkTarget {
    data object None : LedgerLinkTarget
    data class EntityTarget(val entity: PaymentEntityRecord) : LedgerLinkTarget
    data class CustomerTarget(val name: String, val customer: CustomerEntity?) : LedgerLinkTarget
    data class ExpenseTarget(val name: String, val expenseEntity: PaymentEntityRecord?) : LedgerLinkTarget
    data class AmbiguousTarget(
      val entity: PaymentEntityRecord,
      val customerName: String,
      val customer: CustomerEntity?,
    ) : LedgerLinkTarget
  }

  fun resolveLedgerTarget(
    type: String,
    scope: String,
    party: String,
    category: String,
    entities: List<PaymentEntityRecord>,
    customers: List<CustomerEntity>,
    sales: List<SaleTransactionEntity>,
    overrideChoice: String? = null, // null | "NONE" | "ENTITY" | "CUSTOMER" | "EXPENSE"
  ): LedgerLinkTarget {
    if (type.equals("TRANSFER", ignoreCase = true)) return LedgerLinkTarget.None
    if (overrideChoice.equals("NONE", ignoreCase = true)) return LedgerLinkTarget.None

    val cleanParty = party.trim()
    val cleanCat = category.trim()
    val cleanScope = scope.trim()
    if (type.equals("OUT", ignoreCase = true) &&
      (cleanScope.equals("Home", ignoreCase = true) || cleanScope.equals("Personal", ignoreCase = true))
    ) {
      return LedgerLinkTarget.None
    }

    val matchedEntity = if (cleanParty.isNotBlank()) {
      entities.firstOrNull { !it.isDeleted && it.name.equals(cleanParty, ignoreCase = true) }
    } else null

    val matchedCustomer = if (cleanParty.isNotBlank()) {
      customers.firstOrNull { !it.isDeleted && !it.isRepCustomer && it.name.equals(cleanParty, ignoreCase = true) }
    } else null

    val partyInDirectSales = cleanParty.isNotBlank() && sales.any {
      !it.isDeleted && !it.isRepSale && it.customerName.equals(cleanParty, ignoreCase = true)
    }
    val hasCustomerMatch = matchedCustomer != null || partyInDirectSales
    val effectiveCustName = matchedCustomer?.name ?: cleanParty

    when (overrideChoice?.uppercase()) {
      "ENTITY" -> {
        if (matchedEntity != null) return LedgerLinkTarget.EntityTarget(matchedEntity)
      }
      "CUSTOMER" -> {
        if (cleanParty.isNotBlank()) return LedgerLinkTarget.CustomerTarget(effectiveCustName, matchedCustomer)
      }
      "EXPENSE" -> {
        val expName = cleanParty.ifBlank { cleanCat.ifBlank { "General Expense" } }
        val expEnt = matchedEntity?.takeIf { it.isExpenseEntity }
        return LedgerLinkTarget.ExpenseTarget(expName, expEnt)
      }
    }

    if (matchedEntity != null && matchedEntity.isExpenseEntity && type.equals("OUT", ignoreCase = true)) {
      return LedgerLinkTarget.ExpenseTarget(matchedEntity.name, matchedEntity)
    }
    if (matchedEntity != null && hasCustomerMatch) {
      return LedgerLinkTarget.AmbiguousTarget(matchedEntity, effectiveCustName, matchedCustomer)
    }
    if (matchedEntity != null) {
      return LedgerLinkTarget.EntityTarget(matchedEntity)
    }
    if (hasCustomerMatch) {
      return LedgerLinkTarget.CustomerTarget(effectiveCustName, matchedCustomer)
    }

    if (type.equals("OUT", ignoreCase = true)) {
      val nonExpenseCats = setOf("Transfer", "Supplier Payment", "Home / Personal")
      if (cleanCat.isNotBlank() && nonExpenseCats.none { it.equals(cleanCat, ignoreCase = true) } &&
        (cleanScope.equals("Business", ignoreCase = true) || cleanScope.isBlank()) &&
        (cleanParty.isNotBlank() || !cleanCat.equals("General Expense", ignoreCase = true))
      ) {
        return LedgerLinkTarget.ExpenseTarget(cleanParty.ifBlank { cleanCat }, null)
      }
    }
    return LedgerLinkTarget.None
  }

  // =====================================================================
  // Reference / Delete / Edit Guards (Store, Formula, Batch, Lot, Sale)
  // =====================================================================

  data class StoreUsage(
    val prodCount: Int = 0,
    val batchCount: Int = 0,
    val saleCount: Int = 0,
    val returnCount: Int = 0,
    val transferCount: Int = 0,
    val settlementCount: Int = 0,
  ) {
    val totalCount: Int get() = prodCount + batchCount + saleCount + returnCount + transferCount + settlementCount
    fun blockMessage(storeName: String): String? {
      if (totalCount == 0) return null
      val parts = buildList {
        if (prodCount > 0) add("$prodCount production entry(s)")
        if (batchCount > 0) add("$batchCount factory batch(es)")
        if (saleCount > 0) add("$saleCount sale(s)")
        if (returnCount > 0) add("$returnCount return log(s)")
        if (transferCount > 0) add("$transferCount stock transfer(s)")
        if (settlementCount > 0) add("$settlementCount calculator settlement(s)")
      }
      return "Cannot delete store $storeName: referenced by ${parts.joinToString(", ")}."
    }
  }

  fun storeUsage(
    storeKey: String,
    production: List<ProductionEntryEntity>,
    factoryBatches: List<FactoryBatchEntity>,
    sales: List<SaleTransactionEntity>,
    returns: List<ReturnLogEntity>,
    stockTransfers: List<StockTransferEntity>,
    settlements: List<CalculatorSettlementEntity> = emptyList(),
  ): StoreUsage {
    val clean = storeKey.trim()
    if (clean.isBlank()) return StoreUsage()
    return StoreUsage(
      prodCount = production.count { !it.isDeleted && it.storeKey.equals(clean, ignoreCase = true) },
      batchCount = factoryBatches.count { !it.isDeleted && it.store.equals(clean, ignoreCase = true) },
      saleCount = sales.count { !it.isDeleted && it.storeKey.equals(clean, ignoreCase = true) },
      returnCount = returns.count { !it.isDeleted && it.store.equals(clean, ignoreCase = true) },
      transferCount = stockTransfers.count {
        !it.isDeleted && (it.fromStoreKey.equals(clean, ignoreCase = true) || it.toStoreKey.equals(clean, ignoreCase = true))
      },
      settlementCount = settlements.count { !it.isDeleted && it.returnStoreKey.equals(clean, ignoreCase = true) },
    )
  }

  fun formulaUsers(
    formulaId: String,
    stores: List<StoreEntity>,
    formulas: List<FormulaEntity>,
  ): Pair<List<String>, List<String>> {
    val usedByStores = stores
      .filter { !it.isDeleted && it.formulaId == formulaId }
      .map { it.name.ifBlank { it.storeKey } }
    val usedByFormulas = formulas
      .filter { f ->
        !f.isDeleted && f.id != formulaId &&
          JsonCodecs.decodeIngredients(f.ingredientsJson).any {
            it.refType.equals("FORMULA", ignoreCase = true) && it.refId == formulaId
          }
      }
      .map { it.name }
    return usedByStores to usedByFormulas
  }

  fun getFormulaDeleteBlockReason(
    formula: FormulaEntity,
    stores: List<StoreEntity>,
    formulas: List<FormulaEntity>,
  ): String? {
    val (usedByStores, usedByFormulas) = formulaUsers(formula.id, stores, formulas)
    if (usedByStores.isNotEmpty()) {
      return "Cannot delete formula ${formula.name}: used by store(s) ${usedByStores.joinToString(", ")}."
    }
    if (usedByFormulas.isNotEmpty()) {
      return "Cannot delete formula ${formula.name}: referenced by formula(s) ${usedByFormulas.joinToString(", ")}."
    }
    if (formula.unitsAvailable > 0.0005) {
      return "Cannot delete formula ${formula.name}: ${round2(formula.unitsAvailable)} produced units remain available."
    }
    return null
  }

  /**
   * Matches web `link-graph.js::usedUnitsByBatch`: FIFO consumption of produced batch units by Production entries.
   */
  fun usedUnitsByBatch(
    factoryBatches: List<FactoryBatchEntity>,
    production: List<ProductionEntryEntity>,
  ): Map<String, Double> {
    val remainingUsedByFormula = linkedMapOf<String, Double>()
    production.filter { !it.isDeleted && !it.isReturn && it.formulaId.isNotBlank() && it.formulaUnitsUsed > 0.0 }
      .forEach { p ->
        remainingUsedByFormula[p.formulaId] = (remainingUsedByFormula[p.formulaId] ?: 0.0) + p.formulaUnitsUsed
      }
    val map = linkedMapOf<String, Double>()
    factoryBatches.filter { !it.isDeleted }
      .sortedWith(compareBy<FactoryBatchEntity> { it.dateEpoch }.thenBy { it.createdAt })
      .forEach { batch ->
        val poolLeft = remainingUsedByFormula[batch.formulaId] ?: 0.0
        val taken = minOf(batch.unitsProduced.coerceAtLeast(0.0), poolLeft.coerceAtLeast(0.0))
        map[batch.id] = round2(taken)
        remainingUsedByFormula[batch.formulaId] = (poolLeft - taken).coerceAtLeast(0.0)
      }
    return map
  }

  fun getFactoryBatchDeleteBlockReason(
    batch: FactoryBatchEntity,
    formula: FormulaEntity?,
    factoryBatches: List<FactoryBatchEntity>,
    production: List<ProductionEntryEntity>,
  ): String? {
    if (formula != null && formula.unitsAvailable + 0.0005 < batch.unitsProduced) {
      return "Cannot delete batch: only ${round2(formula.unitsAvailable)} of ${round2(batch.unitsProduced)} produced units remain unused."
    }
    val usedOnBatch = usedUnitsByBatch(factoryBatches, production)[batch.id] ?: 0.0
    if (formula != null) {
      // Only block via FIFO map if total remaining pool cannot absorb removing this batch's units
      val totalProduced = factoryBatches.filter { !it.isDeleted && it.formulaId == batch.formulaId }.sumOf { it.unitsProduced }
      val totalProdUsed = production.filter { !it.isDeleted && !it.isReturn && it.formulaId == batch.formulaId }.sumOf { it.formulaUnitsUsed }
      if (totalProdUsed > 0.0005 && (totalProduced - batch.unitsProduced) + 0.0005 < totalProdUsed && usedOnBatch > 0.0005) {
        return "Cannot delete batch: $usedOnBatch unit(s) have already been consumed by production."
      }
    }
    return null
  }

  fun getPurchaseLotDeleteBlockReason(
    lot: RawMaterialPurchaseEntity,
    material: RawMaterialEntity?,
  ): String? {
    if (material != null && !material.isDeleted && material.stockKg + 1e-6 < lot.totalKg) {
      val usedKg = round2((lot.totalKg - material.stockKg).coerceAtLeast(0.0))
      return "Cannot delete purchase lot: $usedKg kg from ${material.name} has already been consumed."
    }
    return null
  }

  fun getSaleBlockReason(
    sale: SaleTransactionEntity,
    settlements: List<CalculatorSettlementEntity>,
  ): String? {
    if (!sale.usedInCalcId.isNullOrBlank()) {
      return "This transaction is reconciled in a Calculator settlement (${sale.usedInCalcId}). Delete or update that settlement first."
    }
    if (!sale.isRepSale && sale.id in reconciledAdminSalesIds(settlements)) {
      return "This sale is reconciled in the Calculator. Delete the Calculator settlement first."
    }
    if (sale.isRepSale && sale.id in reconciledRepSalesIds(settlements)) {
      return "This Rep sale is reconciled in the Calculator. Delete the Calculator settlement first."
    }
    if (sale.isRepReturn && sale.id in reconciledRepReturnIds(settlements)) {
      return "This return is already reconciled in the Calculator. Delete the Calculator settlement first."
    }
    val transferHolder = settlements.firstOrNull { !it.isDeleted && it.transferSaleId == sale.id }
    if (transferHolder != null) {
      return "This rep transfer belongs to a Calculator settlement for ${transferHolder.repName}. Delete that settlement first."
    }
    val isCreditOrOldDebt = sale.mode == "OLD_DEBT" || sale.transactionType == "OLD_DEBT" ||
      (sale.mode == "SALE" && sale.paymentType == "CREDIT")
    if (isCreditOrOldDebt &&
      (JsonCodecs.decodeCollectionAllocs(sale.collectionAllocsJson).isNotEmpty() || sale.partialPaymentReceived > 0.01)
    ) {
      return "This credit sale already has collection payments applied. Delete the linked collection entries first."
    }
    return null
  }

  fun getCollectionDeleteBlockReason(
    sale: SaleTransactionEntity,
    rawMaterials: List<RawMaterialEntity>,
  ): String? {
    if ((sale.mode == "COLLECTION" || sale.isCollection) && sale.expiredQtyKg > 0.0001 && !sale.isRepReturn) {
      val chora = rawMaterials.firstOrNull { !it.isDeleted && it.name.equals("CHORA", ignoreCase = true) }
      val choraStock = chora?.stockKg ?: 0.0
      if (choraStock + 1e-6 < sale.expiredQtyKg) {
        return "Cannot delete collection: ${round2(sale.expiredQtyKg)} kg expired Chora exceeds remaining CHORA stock (${round2(choraStock)} kg)."
      }
    }
    return null
  }

  fun getSaleEditLinkIssue(
    existing: SaleTransactionEntity,
    newMode: String,
    newPaymentType: String,
    newCustomerName: String,
    newTotalValue: Double,
    settlements: List<CalculatorSettlementEntity>,
  ): String? {
    if (!existing.usedInCalcId.isNullOrBlank() ||
      existing.id in reconciledAdminSalesIds(settlements) ||
      existing.id in reconciledRepSalesIds(settlements) ||
      existing.id in reconciledRepReturnIds(settlements) ||
      settlements.any { !it.isDeleted && it.transferSaleId == existing.id }
    ) {
      return "Reconciled Calculator transactions cannot be edited directly. Delete the Calculator settlement first."
    }
    val hasPartialOrAllocs = existing.partialPaymentReceived > 0.01 ||
      JsonCodecs.decodeCollectionAllocs(existing.collectionAllocsJson).isNotEmpty()
    val isCreditOrOldDebt = existing.mode == "OLD_DEBT" || existing.transactionType == "OLD_DEBT" ||
      (existing.mode == "SALE" && existing.paymentType == "CREDIT")
    if (isCreditOrOldDebt && hasPartialOrAllocs) {
      if (!existing.customerName.equals(newCustomerName.trim(), ignoreCase = true)) {
        return "Cannot change customer on a credit sale that already has collections applied."
      }
      if (newMode == "SALE" && newPaymentType.equals("CASH", ignoreCase = true)) {
        return "Cannot convert a credit sale with collections applied to Cash."
      }
      if (newTotalValue + 0.01 < existing.partialPaymentReceived) {
        return "Total sale value cannot be less than already collected amount (${round2(existing.partialPaymentReceived)})."
      }
    }
    return null
  }

  fun projectedStoreStockAfterProductionDelete(
    storeKey: String,
    excludingProdId: String?,
    production: List<ProductionEntryEntity>,
    stockTransfers: List<StockTransferEntity>,
    settlements: List<CalculatorSettlementEntity>,
    sales: List<SaleTransactionEntity>,
  ): Double {
    val clean = storeKey.trim()
    val produced = production.filter {
      !it.isDeleted && !it.isReturn && it.storeKey.equals(clean, true) && it.id != excludingProdId
    }.sumOf { it.netWeightKg }
    val tIn = stockTransfers.filter { !it.isDeleted && it.toStoreKey.equals(clean, true) }.sumOf { it.quantityKg }
    val tOut = stockTransfers.filter { !it.isDeleted && it.fromStoreKey.equals(clean, true) }.sumOf { it.quantityKg }
    val returned = settlements.filter {
      !it.isDeleted && !it.returnType.equals("TRANSFER", true) && it.returnStoreKey.equals(clean, true)
    }.sumOf { it.returnedKg }
    val sold = sales.filter {
      !it.isDeleted && !it.isRepSale && !it.isRepReturn && !it.isRepTransfer &&
        it.mode == "SALE" && it.storeKey.equals(clean, true)
    }.sumOf { it.quantityKg }
    return round2(produced + tIn + returned - sold - tOut)
  }

  fun getProductionDeleteBlockReason(
    entry: ProductionEntryEntity,
    production: List<ProductionEntryEntity>,
    settlements: List<CalculatorSettlementEntity>,
    stockTransfers: List<StockTransferEntity>,
    sales: List<SaleTransactionEntity>,
  ): String? {
    if (YearCloseMerge.isMergedId(entry.id)) {
      return "Merged opening balance records cannot be deleted."
    }
    if (entry.isReturn) {
      val owner = settlements.firstOrNull { !it.isDeleted && it.returnEntryId == entry.id }
      if (owner != null) {
        return "This return belongs to ${owner.repName}'s Calculator settlement (${owner.dateStr}). Delete that Calculator settlement first."
      }
    }
    if (!entry.isReturn || !entry.returnLogId.isNullOrBlank()) {
      val projected = projectedStoreStockAfterProductionDelete(
        storeKey = entry.storeKey,
        excludingProdId = entry.id,
        production = production,
        stockTransfers = stockTransfers,
        settlements = settlements,
        sales = sales,
      )
      if (projected < -0.001) {
        return "Cannot delete: removing ${SarimFormat.formatKg(entry.netWeightKg)} from ${entry.storeName} stock would leave it short by ${SarimFormat.formatKg(abs(projected))} because that stock was already sold or transferred. Delete those sales first."
      }
    }
    return null
  }

  fun getEntityDeleteBlockReason(
    entity: PaymentEntityRecord,
    allTxs: List<PaymentTransactionEntity>,
    sales: List<SaleTransactionEntity>,
    settlements: List<CalculatorSettlementEntity>,
    factoryBatches: List<FactoryBatchEntity>,
  ): String? {
    val linked = allTxs.filter { !it.isDeleted && it.entityId == entity.id }
    if (linked.any { YearCloseMerge.isMergedId(it.id) }) {
      return "This entity has merged opening balance records and cannot be deleted."
    }
    val nonPayableIn = linked.filter { it.type == "PAYMENT_IN" && !it.isPayable }.sumOf { it.amount }
    if (nonPayableIn > 0.01) {
      val cashNow = computeCashInHandBreakdown(sales, settlements, allTxs, factoryBatches).netCash
      if (cashNow >= -0.01 && (cashNow - nonPayableIn) < -0.01) {
        return "Cannot delete entity ${entity.name}: reversing received payments (${SarimFormat.formatRs(nonPayableIn)}) would drop Cash in Hand below zero."
      }
    }
    return null
  }

  fun getPaymentTxDeleteBlockReason(
    tx: PaymentTransactionEntity,
    allTxs: List<PaymentTransactionEntity>,
    sales: List<SaleTransactionEntity>,
    settlements: List<CalculatorSettlementEntity>,
    factoryBatches: List<FactoryBatchEntity>,
  ): String? {
    if (YearCloseMerge.isMergedId(tx.id)) {
      return "Merged opening balance records cannot be deleted."
    }
    if (tx.type == "PAYMENT_IN") {
      val consumedByOut = allTxs.any { other ->
        !other.isDeleted && other.type == "PAYMENT_OUT" &&
          JsonCodecs.decodeConsumedCredits(other.consumedCreditsJson).any { it.id == tx.id }
      }
      if (consumedByOut) {
        return "This Payment IN has supplier credit already consumed by a Payment OUT. Delete that Payment OUT first."
      }
      if (!tx.isPayable) {
        val cashNow = computeCashInHandBreakdown(sales, settlements, allTxs, factoryBatches).netCash
        if (cashNow >= -0.01 && (cashNow - tx.amount) < -0.01) {
          return "Deleting this payment (${SarimFormat.formatRs(tx.amount)}) would drop Cash in Hand below zero (${SarimFormat.formatRs(cashNow.coerceAtLeast(0.0))} available)."
        }
      }
    }
    return null
  }

  fun getStockTransferDeleteBlockReason(
    transfer: StockTransferEntity,
    production: List<ProductionEntryEntity>,
    sales: List<SaleTransactionEntity>,
    stockTransfers: List<StockTransferEntity>,
    settlements: List<CalculatorSettlementEntity>,
  ): String? {
    val cleanTo = transfer.toStoreKey.trim()
    val produced = production.filter { !it.isDeleted && !it.isReturn && it.storeKey.equals(cleanTo, true) }.sumOf { it.netWeightKg }
    val tIn = stockTransfers.filter { !it.isDeleted && it.id != transfer.id && it.toStoreKey.equals(cleanTo, true) }.sumOf { it.quantityKg }
    val tOut = stockTransfers.filter { !it.isDeleted && it.fromStoreKey.equals(cleanTo, true) }.sumOf { it.quantityKg }
    val returned = settlements.filter {
      !it.isDeleted && !it.returnType.equals("TRANSFER", true) && it.returnStoreKey.equals(cleanTo, true)
    }.sumOf { it.returnedKg }
    val sold = sales.filter {
      !it.isDeleted && !it.isRepSale && !it.isRepReturn && !it.isRepTransfer &&
        it.mode == "SALE" && it.storeKey.equals(cleanTo, true)
    }.sumOf { it.quantityKg }
    val projected = round2(produced + tIn + returned - sold - tOut)
    if (projected < -0.001) {
      return "Cannot delete transfer: destination store ${transfer.toStoreName} has already sold or transferred ${SarimFormat.formatKg(abs(projected))} of this stock."
    }
    return null
  }

  fun getPaymentTransferDeleteBlockReason(
    transfer: PaymentTransferEntity,
    fromEntity: PaymentEntityRecord?,
    toEntity: PaymentEntityRecord?,
  ): String? {
    if (transfer.amount <= 0.0) return "Invalid transfer amount."
    fromEntity?.sizeHintOrUnused()
    toEntity?.sizeHintOrUnused()
    return null
  }

  private fun PaymentEntityRecord.sizeHintOrUnused() = id

  /** Returns (pendingDeliveredKg, alreadyPendingReturnKg, remainingAllowedKg). */
  fun getRepReturnAllowance(
    repName: String,
    excludeId: String?,
    sales: List<SaleTransactionEntity>,
    settlements: List<CalculatorSettlementEntity>,
  ): Triple<Double, Double, Double> {
    val deliveredKg = round2(getPendingRepDeliveries(repName, sales, settlements).sumOf { it.quantityKg })
    val pendingRetKg = round2(
      getPendingRepReturns(repName, sales, settlements)
        .filter { it.id != excludeId }
        .sumOf { it.expiredQtyKg + it.returnedQtyKg }
    )
    val remaining = round2((deliveredKg - pendingRetKg).coerceAtLeast(0.0))
    return Triple(deliveredKg, pendingRetKg, remaining)
  }

  // =====================================================================
  // Cash-in-Hand Breakdown (Matches Payments Cash-in-Hand Tracker & Guard)
  // =====================================================================

  data class CashInHandBreakdown(
    val salesCash: Double,
    val collectionsCash: Double,
    val moneyOutCash: Double,
    val calcCash: Double,
    val paymentInCash: Double,
    val paymentOutCash: Double,
    val expensesCash: Double,
    val factoryAdditionalCost: Double,
    val netCash: Double,
  )

  fun computeCashInHandBreakdown(
    sales: List<SaleTransactionEntity>,
    settlements: List<CalculatorSettlementEntity>,
    payments: List<PaymentTransactionEntity>,
    factoryBatches: List<FactoryBatchEntity> = emptyList(),
    cutoffEpoch: Long = 0L,
    endEpoch: Long = Long.MAX_VALUE,
  ): CashInHandBreakdown {
    fun inRange(epoch: Long) = epoch >= cutoffEpoch && epoch <= endEpoch
    val activeSales = sales.filter { !it.isDeleted && !it.isRepSale && inRange(it.dateEpoch) }
    val salesCash = round2(activeSales.filter { it.mode == "SALE" && it.paymentType == "CASH" }.sumOf { it.totalValue })
    val collectionsCash = round2(
      activeSales.filter { it.mode == "COLLECTION" || it.isCollection }.sumOf { s ->
        s.collectedAmount.takeIf { it > 0.0 } ?: s.amountReceived.takeIf { it > 0.0 } ?: s.totalValue
      }
    )
    val moneyOutCash = round2(
      activeSales.filter { it.mode == "MONEY_OUT" || it.isMoneyOut }.sumOf { s ->
        s.collectedAmount.takeIf { it > 0.0 } ?: s.amountReceived.takeIf { it > 0.0 } ?: s.totalValue
      }
    )
    val calcCash = round2(settlements.filter { !it.isDeleted && inRange(it.dateEpoch) }.sumOf { it.receivedCash })
    val activePay = payments.filter { !it.isDeleted && inRange(it.dateEpoch) }
    val paymentInCash = round2(activePay.filter { it.type == "PAYMENT_IN" && !it.isPayable }.sumOf { it.amount })
    val paymentOutCash = round2(activePay.filter { it.type == "PAYMENT_OUT" }.sumOf { it.amount })
    val expensesCash = round2(activePay.filter { it.type == "EXPENSE" }.sumOf { it.amount })
    val factoryAddCost = round2(factoryBatches.filter { !it.isDeleted && inRange(it.dateEpoch) }.sumOf { it.additionalCost })
    val net = round2(salesCash + collectionsCash - moneyOutCash + calcCash + paymentInCash - paymentOutCash - expensesCash - factoryAddCost)
    return CashInHandBreakdown(
      salesCash = salesCash,
      collectionsCash = collectionsCash,
      moneyOutCash = moneyOutCash,
      calcCash = calcCash,
      paymentInCash = paymentInCash,
      paymentOutCash = paymentOutCash,
      expensesCash = expensesCash,
      factoryAdditionalCost = factoryAddCost,
      netCash = net,
    )
  }

  /** Web `calculateCashTracker` "TOTAL OUTSTANDING CREDITS" card for one date window. */
  data class OutstandingCredits(
    val salesTabCredits: Double,
    val calculatorCredits: Double,
  ) {
    val total: Double get() = salesTabCredits + calculatorCredits
  }

  fun computeOutstandingCredits(
    sales: List<SaleTransactionEntity>,
    settlements: List<CalculatorSettlementEntity>,
    cutoffEpoch: Long = 0L,
    endEpoch: Long = Long.MAX_VALUE,
  ): OutstandingCredits {
    fun inRange(epoch: Long) = epoch >= cutoffEpoch && epoch <= endEpoch
    var salesCredits = 0.0
    sales.filter { !it.isDeleted && !it.isRepSale && inRange(it.dateEpoch) }.forEach { s ->
      val value = s.collectedAmount.takeIf { it > 0.0 } ?: s.amountReceived.takeIf { it > 0.0 } ?: s.totalValue
      when {
        s.mode == "COLLECTION" || s.isCollection -> salesCredits -= value
        s.mode == "MONEY_OUT" || s.isMoneyOut -> salesCredits += value
        s.paymentType == "PARTIAL_PAYMENT" -> salesCredits -= value
        s.mode == "SALE" && s.paymentType == "CREDIT" && !s.creditReceived ->
          salesCredits += (s.totalValue - s.partialPaymentReceived).coerceAtLeast(0.0)
      }
    }
    val calc = settlements.filter { !it.isDeleted && inRange(it.dateEpoch) }
      .sumOf { it.creditIssuedValue - it.recoveredCredit }
    return OutstandingCredits(round2(salesCredits), round2(calc))
  }

  // =====================================================================
  // Link Audit & Repair (Matches modules/link-repair.js & audit.js)
  // =====================================================================

  data class LinkIssue(
    val id: String,
    val kind: String, // col_unapplied, link_orphan
    val recordId: String,
    val title: String,
    val detail: String,
    val dateEpoch: Long,
    val severity: String, // WARNING, ERROR
  )

  fun auditLinks(
    sales: List<SaleTransactionEntity>,
    settlements: List<CalculatorSettlementEntity> = emptyList(),
  ): List<LinkIssue> {
    val activeSales = sales.filter { !it.isDeleted }
    val byId = activeSales.associateBy { it.id }
    val issues = mutableListOf<LinkIssue>()

    // 1. Check COLLECTION sales for broken or missing parent allocations
    activeSales.filter { it.mode == "COLLECTION" || it.isCollection }.forEach { col ->
      val allocs = JsonCodecs.decodeAllocations(col.allocationsJson)
      if (allocs.isNotEmpty()) {
        val broken = allocs.any { alloc ->
          val parent = byId[alloc.saleId]
          if (parent == null) true
          else {
            val parentAllocs = JsonCodecs.decodeCollectionAllocs(parent.collectionAllocsJson)
            parentAllocs.none { it.cid == col.id && abs(it.amount - alloc.amount) <= 0.05 }
          }
        }
        if (broken) {
          issues += LinkIssue(
            id = "col_unapplied_${col.id}",
            kind = "col_unapplied",
            recordId = col.id,
            title = "Collection link mismatch · ${col.customerName}",
            detail = "One or more allocated credit sales are missing or out of sync with this collection.",
            dateEpoch = col.dateEpoch,
            severity = "WARNING",
          )
        }
      }
    }

    // 2. Check CREDIT / OLD_DEBT sales for orphan collectionAllocs or mismatched partialPaymentReceived
    val liveCollectionsByParent = linkedMapOf<String, Double>()
    activeSales.filter { it.mode == "COLLECTION" || it.isCollection }.forEach { col ->
      JsonCodecs.decodeAllocations(col.allocationsJson).forEach { alloc ->
        if (alloc.saleId in byId) {
          liveCollectionsByParent[alloc.saleId] = round2((liveCollectionsByParent[alloc.saleId] ?: 0.0) + alloc.amount)
        }
      }
    }

    activeSales.filter {
      it.mode == "OLD_DEBT" || it.transactionType == "OLD_DEBT" || (it.mode == "SALE" && it.paymentType == "CREDIT")
    }.forEach { sale ->
      val colAllocs = JsonCodecs.decodeCollectionAllocs(sale.collectionAllocsJson)
      val hasOrphan = colAllocs.any { ca ->
        val c = byId[ca.cid]
        c == null || !(c.mode == "COLLECTION" || c.isCollection)
      }
      val expectedPartial = if (liveCollectionsByParent.containsKey(sale.id) || colAllocs.isNotEmpty()) {
        liveCollectionsByParent[sale.id] ?: 0.0
      } else {
        sale.partialPaymentReceived
      }
      val partialMismatch = colAllocs.isNotEmpty() && abs(expectedPartial - sale.partialPaymentReceived) > 0.05
      if (hasOrphan || partialMismatch) {
        issues += LinkIssue(
          id = "link_orphan_${sale.id}",
          kind = "link_orphan",
          recordId = sale.id,
          title = "Orphan payment link · ${sale.customerName}",
          detail = "Credit sale references deleted collection(s) or out-of-sync partial payment total.",
          dateEpoch = sale.dateEpoch,
          severity = "ERROR",
        )
      }
    }

    // Ignore unused parameter warning while keeping extension point clean
    settlements.size
    return issues
  }

  data class LinkRepairResult(
    val updatedSales: List<SaleTransactionEntity>,
    val collectionsFixed: Int,
    val parentsFixed: Int,
  )

  fun planLinkRepairs(sales: List<SaleTransactionEntity>, now: Long = System.currentTimeMillis()): LinkRepairResult {
    val active = sales.filter { !it.isDeleted }
    val byId = active.associateBy { it.id }.toMutableMap()
    val updatedIds = linkedSetOf<String>()
    var collectionsFixed = 0
    var parentsFixed = 0

    val parentChildMap = linkedMapOf<String, MutableList<JsonCodecs.CollectionAllocItem>>()

    // First pass: clean COLLECTION.allocations pointing to deleted/non-existent parent sales
    active.filter { it.mode == "COLLECTION" || it.isCollection }.forEach { col ->
      val origAllocs = JsonCodecs.decodeAllocations(col.allocationsJson)
      if (origAllocs.isNotEmpty()) {
        val validAllocs = origAllocs.filter { a ->
          val p = byId[a.saleId]
          p != null && (p.mode == "OLD_DEBT" || p.transactionType == "OLD_DEBT" || (p.mode == "SALE" && p.paymentType == "CREDIT"))
        }
        validAllocs.forEach { a ->
          parentChildMap.getOrPut(a.saleId) { mutableListOf() } +=
            JsonCodecs.CollectionAllocItem(cid = col.id, amount = a.amount, full = a.full)
        }
        if (validAllocs.size != origAllocs.size) {
          val cashCollected = col.collectedAmount.takeIf { it > 0.0 } ?: col.amountReceived
          val totalAlloc = validAllocs.sumOf { it.amount }
          val cashPaid = minOf(cashCollected, totalAlloc)
          val cashLeft = round2((cashCollected - cashPaid).coerceAtLeast(0.0))
          val expAllocated = round2((totalAlloc - cashPaid).coerceAtLeast(0.0))
          val nextCol = col.copy(
            allocationsJson = JsonCodecs.encodeAllocations(validAllocs),
            totalValue = if (validAllocs.isNotEmpty()) cashLeft else cashCollected,
            expiredAllocated = expAllocated,
            syncPending = true,
            updatedAt = now,
          )
          byId[col.id] = nextCol
          updatedIds += col.id
          collectionsFixed++
        }
      }
    }

    // Second pass: rebuild collectionAllocs, partialPaymentReceived, and creditReceived on Credit / OLD_DEBT sales
    active.filter {
      it.mode == "OLD_DEBT" || it.transactionType == "OLD_DEBT" || (it.mode == "SALE" && it.paymentType == "CREDIT")
    }.forEach { sale ->
      val origAllocs = JsonCodecs.decodeCollectionAllocs(sale.collectionAllocsJson)
      val rebuiltAllocs = parentChildMap[sale.id].orEmpty()
      val newPartial = if (rebuiltAllocs.isNotEmpty()) {
        round2(rebuiltAllocs.sumOf { it.amount }).coerceAtMost(sale.totalValue)
      } else if (origAllocs.isNotEmpty()) {
        // Had allocations before, all pointed to deleted collections -> reset partial to 0
        0.0
      } else {
        sale.partialPaymentReceived
      }
      val newCreditReceived = when {
        !sale.usedInCalcId.isNullOrBlank() -> true
        origAllocs.isNotEmpty() || rebuiltAllocs.isNotEmpty() -> newPartial >= sale.totalValue - 0.01
        else -> sale.creditReceived
      }
      val needsUpdate = origAllocs != rebuiltAllocs ||
        abs(sale.partialPaymentReceived - newPartial) > 0.01 ||
        sale.creditReceived != newCreditReceived

      if (needsUpdate) {
        val nextSale = sale.copy(
          collectionAllocsJson = JsonCodecs.encodeCollectionAllocs(rebuiltAllocs),
          partialPaymentReceived = newPartial,
          creditReceived = newCreditReceived,
          syncPending = true,
          updatedAt = now,
        )
        byId[sale.id] = nextSale
        updatedIds += sale.id
        parentsFixed++
      }
    }

    return LinkRepairResult(
      updatedSales = updatedIds.mapNotNull { byId[it] },
      collectionsFixed = collectionsFixed,
      parentsFixed = parentsFixed,
    )
  }

  /** Dependency rank for Recycle Bin recovery (parents before dependents). */
  fun restoreSortOrder(entityType: String, isCollectionSale: Boolean = false): Int = when (entityType) {
    "STORE", "CUSTOMER", "ENTITY", "MATERIAL", "FORMULA" -> 0
    "PURCHASE_LOT", "BATCH", "PRODUCTION", "RETURN", "EXPENSE" -> 1
    "SALE" -> if (isCollectionSale) 3 else 2
    "PAYMENT", "LEDGER", "STOCK_TRANSFER", "PAYMENT_TRANSFER" -> 2
    "SETTLEMENT" -> 4
    else -> 5
  }

  /**
   * Port of `modules/rep-sales.js` `autoUpdateCustomerLocation`:
   * Automatically confirms a GPS address on a rep customer when at least 3 previous rep
   * visits with GPS lie within 150 m of the current visit, provided the customer has not
   * already confirmed location or entered a manual non-GPS address.
   */
  fun evaluateAutoLocationCluster(
    customer: CustomerEntity,
    allSales: List<SaleTransactionEntity>,
    currentLat: Double,
    currentLng: Double,
    now: Long = System.currentTimeMillis(),
    radiusMeters: Double = com.example.util.LocationHelper.GPS_CLUSTER_RADIUS_METERS,
  ): Pair<Double, Double>? {
    if (customer.locationConfirmed) return null
    val addr = customer.address.trim()
    val isManualAddress = addr.length > 5 && !addr.startsWith("GPS:", ignoreCase = true)
    if (isManualAddress) return null

    val pastVisits = allSales
      .filter { sale ->
        !sale.isDeleted &&
          sale.isRepSale &&
          (sale.customerId == customer.id || sale.customerName.equals(customer.name, ignoreCase = true)) &&
          sale.latitude != null &&
          sale.longitude != null &&
          sale.updatedAt < now - 2_000L
      }
      .sortedByDescending { it.updatedAt }

    if (pastVisits.size < 3) return null

    var clusterCount = 0
    var clusterLat = 0.0
    var clusterLng = 0.0
    for (visit in pastVisits) {
      val vLat = visit.latitude ?: continue
      val vLng = visit.longitude ?: continue
      if (com.example.util.LocationHelper.haversineMeters(currentLat, currentLng, vLat, vLng) <= radiusMeters) {
        clusterCount++
        clusterLat += vLat
        clusterLng += vLng
        if (clusterCount >= 3) break
      }
    }
    if (clusterCount < 3) return null
    val avgLat = (clusterLat + currentLat) / (clusterCount + 1)
    val avgLng = (clusterLng + currentLng) / (clusterCount + 1)
    return avgLat to avgLng
  }

  /**
   * Port of `modules/edit-history.js` `historyDiff`:
   * Compares `(fieldKey, label, before, after)` tuples and returns structured changes.
   */
  fun buildEditDiffs(
    fields: List<QuadrupleDiff>,
  ): List<com.example.data.model.EditHistoryChange> {
    return fields.mapNotNull { item ->
      val fromNorm = item.before.trim()
      val toNorm = item.after.trim()
      if (fromNorm == toNorm) null
      else com.example.data.model.EditHistoryChange(
        fieldKey = item.fieldKey,
        label = item.label,
        from = fromNorm.ifBlank { "—" },
        to = toNorm.ifBlank { "—" },
      )
    }
  }

  data class QuadrupleDiff(
    val fieldKey: String,
    val label: String,
    val before: String,
    val after: String,
  )

  /**
   * Port of `modules/date-rollover.js`:
   * If the current date field is still pointing to [previousDayStartEpoch] (not manually
   * changed by the user) and we are not editing an old record, roll it forward to [newDayEpoch].
   */
  fun rolloverDateIfUnedited(
    currentFieldEpoch: Long,
    previousDayStartEpoch: Long,
    newDayStartEpoch: Long,
    isEditing: Boolean,
  ): Long {
    if (isEditing || previousDayStartEpoch == newDayStartEpoch) return currentFieldEpoch
    return if (SarimFormat.startOfDay(currentFieldEpoch) == previousDayStartEpoch) {
      newDayStartEpoch
    } else {
      currentFieldEpoch
    }
  }
}
