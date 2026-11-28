package com.example.data.repository

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure port of web `modules/batch-calc.js` + the number helpers in `batch-common.js`.
 * All quantities are in kg unless a function says otherwise; screens convert to/from grams.
 */
object SarimBatchCalc {
  const val MAX_BATCH_KG = 10_000_000.0
  const val MAX_SAVED_CALCS = 40
  private const val EPS = 1e-9

  data class Ing(val id: String, val name: String, val quantity: Double)

  data class BatchRow(val id: String, val name: String, val perUnit: Double, val total: Double)

  data class BatchPlan(
    val units: Double,
    val unitWeight: Double,
    val totalWeight: Double,
    val rows: List<BatchRow>,
  )

  data class CorrectionRow(val id: String, val name: String, val present: Double, val target: Double, val add: Double)

  /** Mirrors web `manual`: either a total-to-add, or an add amount typed on one ingredient. */
  data class Manual(val total: Double? = null, val ingredientId: String? = null, val add: Double? = null)

  data class CorrectionPlan(
    val ok: Boolean,
    val error: String = "",
    val remaining: Double = 0.0,
    val scale: Double = 0.0,
    val minScale: Double = 0.0,
    val manualId: String = "",
    val adjusted: Boolean = false,
    val rows: List<CorrectionRow> = emptyList(),
    val requiredTotal: Double = 0.0,
    val addTotal: Double = 0.0,
  )

  /** Web `num`: only finite values in (0, MAX_BATCH_KG] count, everything else is 0. */
  fun num(v: Double?): Double = if (v != null && v.isFinite() && v > 0.0 && v <= MAX_BATCH_KG) v else 0.0

  /** Web `formulaIngredients`: flattened ingredients with a positive, in-range quantity. */
  fun ingredients(items: List<FlatIngredient>): List<Ing> =
    items.map { Ing(it.id, it.name.ifBlank { "Untitled" }, num(it.quantity)) }.filter { it.quantity > 0.0 }

  fun unitWeight(ings: List<Ing>): Double = ings.sumOf { it.quantity }

  /** [mode] is "total" | "units" | "ingredient". [value] is kg for total/ingredient, a unit count for units. */
  fun planBatch(ings: List<Ing>, mode: String, value: Double?, ingredientId: String?): BatchPlan {
    val uw = unitWeight(ings)
    val v = num(value)
    var units = 0.0
    if (ings.isNotEmpty() && v > 0.0) {
      units = when (mode) {
        "units" -> v
        "total" -> v / uw
        "ingredient" -> {
          val pick = ings.firstOrNull { it.id == ingredientId } ?: ings.first()
          v / pick.quantity
        }
        else -> 0.0
      }
    }
    val rows = ings.map { BatchRow(it.id, it.name, it.quantity, it.quantity * units) }
    return BatchPlan(units, uw, units * uw, rows)
  }

  /**
   * [actual] maps ingredient id -> amount actually mixed (same unit as [original]/[used]).
   * Everything is computed in whatever unit the caller passes; only ratios matter.
   */
  fun planCorrection(
    ings: List<Ing>,
    original: Double,
    used: Double,
    actual: Map<String, Double>,
    manual: Manual?,
  ): CorrectionPlan {
    val uw = unitWeight(ings)
    val orig = num(original)
    if (ings.isEmpty() || orig <= 0.0) return CorrectionPlan(ok = false, error = "empty")
    val usedClean = if (used.isFinite()) maxOf(0.0, used) else 0.0
    val remaining = orig - usedClean
    if (remaining <= EPS) return CorrectionPlan(ok = false, error = "nothing-left")
    val actualTotal = ings.sumOf { num(actual[it.id]) }
    val present = ings.map { i ->
      if (actualTotal > 0.0) (num(actual[i.id]) / actualTotal) * remaining else (i.quantity / uw) * remaining
    }
    var minScale = 0.0
    ings.forEachIndexed { idx, i -> minScale = maxOf(minScale, present[idx] / i.quantity) }
    var scale = minScale
    var manualId = ""
    val k = if (manual?.ingredientId != null) ings.indexOfFirst { it.id == manual.ingredientId } else -1
    if (manual?.total != null && manual.total.isFinite()) {
      scale = maxOf(minScale, (remaining + maxOf(0.0, manual.total)) / uw)
    } else if (k >= 0 && manual?.add != null && manual.add.isFinite()) {
      scale = maxOf(minScale, (present[k] + maxOf(0.0, manual.add)) / ings[k].quantity)
      manualId = ings[k].id
    }
    val rows = ings.mapIndexed { idx, i ->
      val target = i.quantity * scale
      val add = maxOf(0.0, target - present[idx])
      CorrectionRow(i.id, i.name, present[idx], target, if (add < EPS) 0.0 else add)
    }
    return CorrectionPlan(
      ok = true,
      remaining = remaining,
      scale = scale,
      minScale = minScale,
      manualId = manualId,
      adjusted = scale > minScale + EPS,
      rows = rows,
      requiredTotal = rows.sumOf { it.target },
      addTotal = rows.sumOf { it.add },
    )
  }

  // ---- number formatting (same rounding as web fmtAmt / fmtPrecise / fixed3 / convertValue) ----

  private fun plain(v: Double): String {
    if (!v.isFinite()) return "0"
    val s = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    return if (s == "-0") "0" else s
  }

  /** Web `fmtAmt`: grams to 1 decimal, kg to 3 decimals. */
  fun fmtAmt(v: Double, unit: String): String =
    plain(if (unit == "g") Math.round(v * 10) / 10.0 else Math.round(v * 1000) / 1000.0)

  /** Web `fmtPrecise`: grams to 1 decimal, kg to 4 decimals. */
  fun fmtPrecise(v: Double, unit: String): String =
    plain(if (unit == "g") Math.round(v * 10) / 10.0 else Math.round(v * 10000) / 10000.0)

  /** Web `fixed3`. */
  fun fixed3(v: Double): String = String.format(Locale.US, "%.3f", Math.round(v * 1000) / 1000.0)

  fun round6(v: Double): Double = Math.round(v * 1e6) / 1e6

  /** Web `convertValue`: converts a typed number between g and kg, leaving blanks / bad input alone. */
  fun convertValue(raw: String, from: String, to: String): String {
    val n = raw.trim().toDoubleOrNull()
    if (n == null || !n.isFinite() || from == to) return raw
    return plain(Math.round((if (to == "g") n * 1000 else n / 1000) * 1e6) / 1e6)
  }

  fun toUnit(kg: Double, unit: String): Double = if (unit == "g") kg * 1000 else kg
  fun toKg(value: Double, unit: String): Double = if (unit == "g") value / 1000 else value

  /** Web `defaultCalcName`. */
  fun defaultCalcName(formulaName: String, isFix: Boolean, now: Long): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
    return "${if (isFix) "Correction" else "Batch"} · ${formulaName.ifBlank { "Formula" }} · $day"
  }
}
