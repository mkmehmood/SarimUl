package com.example.data.repository

import com.example.data.local.CalculatorSettlementEntity
import com.example.data.local.ProductionEntryEntity
import com.example.data.local.SaleTransactionEntity
import com.example.data.local.StockTransferEntity
import com.example.util.SarimFormat

/** How the stock tile / validation should read: matches web `inventoryNoteState`. */
enum class StockTone { OK, LOW, SHORT }

data class DayStockSnapshot(
  val producedKg: Double,
  val returnsKg: Double,
  val transferInKg: Double,
  val transferOutKg: Double,
  val soldKg: Double,
) {
  val totalInKg: Double get() = producedKg + returnsKg + transferInKg
  val availableKg: Double get() = Math.round((totalInKg - transferOutKg - soldKg) * 1e6) / 1e6
}

/**
 * Per-store, per-day stock check for sales. Ported from the web app
 * (modules/utilities-sales.js `saveCustomerSale` + `computeStoreStockSnapshot`,
 *  modules/inventory-note.js): a sale can only use what the chosen store produced
 * or received back on that same day, minus what that store already sold that day.
 */
object SarimStockCheck {

  fun snapshot(
    storeKey: String,
    dateStr: String,
    production: List<ProductionEntryEntity>,
    sales: List<SaleTransactionEntity>,
    settlements: List<CalculatorSettlementEntity>,
    transfers: List<StockTransferEntity>,
    excludeSaleId: String? = null,
  ): DayStockSnapshot {
    val produced = production
      .filter { !it.isDeleted && !it.isReturn && it.storeKey == storeKey && dayOf(it.dateStr, it.dateEpoch) == dateStr }
      .sumOf { it.netWeightKg }
    val returned = settlements
      .filter {
        !it.isDeleted && !it.returnType.equals("TRANSFER", ignoreCase = true) &&
          it.returnStoreKey == storeKey && dayOf(it.dateStr, it.dateEpoch) == dateStr
      }
      .sumOf { it.returnedKg }
    val tIn = transfers
      .filter { !it.isDeleted && it.toStoreKey == storeKey && dayOf(it.dateStr, it.dateEpoch) == dateStr }
      .sumOf { it.quantityKg }
    val tOut = transfers
      .filter { !it.isDeleted && it.fromStoreKey == storeKey && dayOf(it.dateStr, it.dateEpoch) == dateStr }
      .sumOf { it.quantityKg }
    val sold = sales
      .filter {
        !it.isDeleted && it.id != excludeSaleId && it.mode == "SALE" &&
          !it.isRepSale && !it.isRepReturn && !it.isRepTransfer &&
          it.storeKey == storeKey && (it.supplyDate.ifBlank { dayOf(it.dateStr, it.dateEpoch) }) == dateStr
      }
      .sumOf { it.quantityKg }
    return DayStockSnapshot(produced, returned, tIn, tOut, sold)
  }

  fun tone(availableKg: Double, requestedKg: Double): StockTone {
    val have = if (availableKg.isFinite()) availableKg else 0.0
    val want = if (requestedKg.isFinite()) requestedKg else 0.0
    return when {
      want - have > 1e-6 -> StockTone.SHORT
      have < want * 1.5 -> StockTone.LOW
      else -> StockTone.OK
    }
  }

  /** Tile note, same wording as web `inventoryTileModel`. */
  fun note(availableKg: Double, requestedKg: Double): String {
    val have = availableKg
    val left = (have - requestedKg).coerceAtLeast(0.0)
    return when (tone(have, requestedKg)) {
      StockTone.SHORT -> "Over by ${SarimFormat.formatKg(requestedKg - have)}"
      StockTone.LOW -> "Only ${SarimFormat.formatKg(left)} left"
      StockTone.OK -> if (requestedKg > 0.0) "${SarimFormat.formatKg(left)} left" else "Ready to sell"
    }
  }

  /** 0..1 share of the day's stock this sale would use (web `used` rail). */
  fun usedFraction(availableKg: Double, requestedKg: Double): Float {
    if (requestedKg <= 0.0) return 0f
    return if (availableKg > 0.0) (requestedKg / availableKg).coerceIn(0.0, 1.0).toFloat() else 1f
  }

  /** Returns a blocking message (title, body) or null when the sale may proceed. */
  fun blockingIssue(
    storeName: String,
    dateLabel: String,
    snapshot: DayStockSnapshot,
    requestedKg: Double,
  ): Pair<String, String>? {
    if (snapshot.totalInKg <= 0.0) {
      return "No Stock" to "No production or returns for $storeName on $dateLabel. You cannot sell what has not been produced."
    }
    val remaining = snapshot.availableKg - requestedKg
    if (remaining < -1e-6) {
      return "Low inventory" to
        "Insufficient stock! Available: ${SarimFormat.formatKg(snapshot.availableKg.coerceAtLeast(0.0))}, " +
        "Requested: ${SarimFormat.formatKg(requestedKg)}. Shortage: ${SarimFormat.formatKg(-remaining)}"
    }
    return null
  }

  private fun dayOf(dateStr: String, dateEpoch: Long): String =
    dateStr.ifBlank { SarimFormat.dateInput(dateEpoch) }
}
