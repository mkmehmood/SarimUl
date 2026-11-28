package com.example.data.repository

import com.example.data.local.ProductionEntryEntity
import com.example.data.local.SaleTransactionEntity
import com.example.data.local.StockTransferEntity
import java.util.Calendar

/**
 * Port of the web production summaries:
 *  - `refreshUI` Day / Week / Month / Year / All stats for the selected store ([periodStats])
 *  - `updateAllStoresOverview` per-store cards + "Total combined" with the "Sold by customer" breakdown ([summarize])
 * Everything is relative to the date selected on the Production screen, like the web `sys-date`.
 */
object SarimStoreSummary {
  enum class Period(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), YEAR("Year"), ALL("All time") }

  private fun cal(epoch: Long) = Calendar.getInstance().apply { timeInMillis = epoch }

  private fun startOfDay(epoch: Long): Long = cal(epoch).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
  }.timeInMillis

  /** Web `_inPeriod`: week = selected day and the 6 days before it; month / year = same calendar month / year. */
  fun inPeriod(epoch: Long, period: Period, anchor: Long): Boolean {
    if (period == Period.ALL) return true
    val d = cal(epoch)
    val a = cal(anchor)
    return when (period) {
      Period.DAY -> startOfDay(epoch) == startOfDay(anchor)
      Period.WEEK -> {
        val day = startOfDay(epoch)
        val end = startOfDay(anchor)
        val start = cal(end).apply { add(Calendar.DAY_OF_YEAR, -6) }.timeInMillis
        day in start..end
      }
      Period.MONTH -> d.get(Calendar.YEAR) == a.get(Calendar.YEAR) && d.get(Calendar.MONTH) == a.get(Calendar.MONTH)
      Period.YEAR -> d.get(Calendar.YEAR) == a.get(Calendar.YEAR)
      Period.ALL -> true
    }
  }

  data class Stats(
    val qty: Double = 0.0,
    val value: Double = 0.0,
    val cost: Double = 0.0,
    val profit: Double = 0.0,
    val formulaUnits: Double = 0.0,
    val formulaCost: Double = 0.0,
  )

  /** Store view header: stats of one store's real production (returns excluded) per period, like web `stats.*`. */
  fun periodStats(entries: List<ProductionEntryEntity>, anchor: Long): Map<Period, Stats> =
    Period.entries.associateWith { p ->
      val rows = entries.filter { !it.isDeleted && !it.isReturn && inPeriod(it.dateEpoch, p, anchor) }
      Stats(
        qty = rows.sumOf { it.netWeightKg },
        value = rows.sumOf { it.totalValue },
        cost = rows.sumOf { it.totalFormulaCost },
        profit = rows.sumOf { it.totalProfit },
        formulaUnits = rows.sumOf { it.formulaUnitsUsed },
        formulaCost = rows.sumOf { it.totalFormulaCost },
      )
    }

  /** Web store view history: that store's records on the selected day (newest first). */
  fun storeDayHistory(entries: List<ProductionEntryEntity>, storeKey: String, anchor: Long): List<ProductionEntryEntity> =
    entries.filter { !it.isDeleted && it.storeKey == storeKey && inPeriod(it.dateEpoch, Period.DAY, anchor) }
      .sortedByDescending { it.createdAt }

  data class StoreSummary(
    val storeKey: String,
    val name: String,
    val production: Double,
    val returns: Double,
    val sold: Double,
    val value: Double,
    val cost: Double,
    val profit: Double,
    val formulaUnits: Double,
    val formulaCost: Double,
    /** Customers sold to from this store in the period, biggest first. */
    val soldByCustomer: List<CustomerSold>,
  ) {
    val totalIn: Double get() = production + returns
    val remaining: Double get() = totalIn - sold
    /** Web "% left" chip. */
    val percentLeft: Int? get() = if (totalIn > 0.0) Math.max(0L, Math.round(remaining / totalIn * 100)).toInt() else null
    val marginPercent: Int? get() = if (value > 0.0) Math.round(profit / value * 100).toInt() else null
  }

  /** [customerId] is the first customer id seen under that name ("" when unknown) so rows can open the customer. */
  data class CustomerSold(val name: String, val customerId: String, val kg: Double)

  data class Overview(val stores: List<StoreSummary>, val combined: StoreSummary)

  /** `stores` = (storeKey, name) in display order. */
  fun summarize(
    stores: List<Pair<String, String>>,
    production: List<ProductionEntryEntity>,
    sales: List<SaleTransactionEntity>,
    transfers: List<StockTransferEntity>,
    period: Period,
    anchor: Long,
  ): Overview {
    val prod = production.filter { !it.isDeleted && inPeriod(it.dateEpoch, period, anchor) }
    // Same sale filter as the stock metrics: plain sales only, not rep sales / returns / transfers.
    val soldRows = sales.filter {
      !it.isDeleted && it.mode == "SALE" && !it.isRepSale && !it.isRepReturn && !it.isRepTransfer &&
        inPeriod(it.dateEpoch, period, anchor)
    }
    val moves = transfers.filter { !it.isDeleted && inPeriod(it.dateEpoch, period, anchor) }

    val per = stores.map { (key, name) ->
      val made = prod.filter { it.storeKey == key && !it.isReturn }
      val back = prod.filter { it.storeKey == key && it.isReturn }
      val byCustomer = linkedMapOf<String, CustomerSold>()
      var sold = 0.0
      soldRows.filter { it.storeKey == key }.forEach {
        sold += it.quantityKg
        val who = it.customerName.ifBlank { "Unknown" }
        val prev = byCustomer[who]
        byCustomer[who] = CustomerSold(who, prev?.customerId?.ifBlank { it.customerId } ?: it.customerId, (prev?.kg ?: 0.0) + it.quantityKg)
      }
      // Stock moved between stores counts as production in / out, as web does for transfer records.
      val transferNet = moves.filter { it.toStoreKey == key }.sumOf { it.quantityKg } -
        moves.filter { it.fromStoreKey == key }.sumOf { it.quantityKg }
      StoreSummary(
        storeKey = key, name = name,
        production = made.sumOf { it.netWeightKg } + transferNet,
        returns = back.sumOf { it.netWeightKg },
        sold = sold,
        value = made.sumOf { it.totalValue },
        cost = made.sumOf { it.totalFormulaCost },
        profit = made.sumOf { it.totalProfit },
        formulaUnits = made.sumOf { it.formulaUnitsUsed },
        formulaCost = made.sumOf { it.totalFormulaCost },
        soldByCustomer = byCustomer.values.sortedByDescending { it.kg },
      )
    }

    val merged = linkedMapOf<String, CustomerSold>()
    per.forEach { s ->
      s.soldByCustomer.forEach { c ->
        val prev = merged[c.name]
        merged[c.name] = CustomerSold(c.name, prev?.customerId?.ifBlank { c.customerId } ?: c.customerId, (prev?.kg ?: 0.0) + c.kg)
      }
    }
    val combined = StoreSummary(
      storeKey = "", name = "Total combined",
      production = per.sumOf { it.production }, returns = per.sumOf { it.returns }, sold = per.sumOf { it.sold },
      value = per.sumOf { it.value }, cost = per.sumOf { it.cost }, profit = per.sumOf { it.profit },
      formulaUnits = per.sumOf { it.formulaUnits }, formulaCost = per.sumOf { it.formulaCost },
      soldByCustomer = merged.values.sortedByDescending { it.kg },
    )
    return Overview(per, combined)
  }
}
