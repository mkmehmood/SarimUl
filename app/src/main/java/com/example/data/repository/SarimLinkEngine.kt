package com.example.data.repository

import com.example.data.local.CalculatorSettlementEntity
import com.example.data.local.JsonCodecs
import com.example.data.local.PaymentTransactionEntity
import com.example.data.local.SaleTransactionEntity
import com.example.util.SarimFormat
import kotlin.math.abs
import kotlin.math.round

/**
 * Pure deterministic transaction-link graph ported from mkmehmood/sarim web
 * (modules/link-graph.js, modules/rep-return-link.js, modules/finance.js, modules/utilities-payments.js).
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

  /** Exact port of `modules/rep-return-link.js::pickReturnsCovered`. */
  fun pickReturnsCovered(
    pending: List<SaleTransactionEntity>,
    retTarget: Double,
    expTarget: Double,
  ): List<SaleTransactionEntity> {
    var remR = retTarget.coerceAtLeast(0.0)
    var remE = expTarget.coerceAtLeast(0.0)
    if (remR <= 0.0 && remE <= 0.0) return emptyList()
    val sorted = pending.sortedWith(
      compareBy<SaleTransactionEntity> { it.dateStr.ifBlank { SarimFormat.dateInput(it.dateEpoch) } }
        .thenBy { it.dateEpoch }
    )
    val chosen = mutableListOf<SaleTransactionEntity>()
    val chosenIds = hashSetOf<String>()
    for (p in sorted) {
      val r = p.returnedQtyKg.coerceAtLeast(0.0)
      val e = p.expiredQtyKg.coerceAtLeast(0.0)
      if (r <= 0.0 && e <= 0.0) continue
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
}
