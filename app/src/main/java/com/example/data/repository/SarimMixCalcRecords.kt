package com.example.data.repository

import com.example.data.local.JsonCodecs
import com.example.data.local.SavedMixCalcEntity

/**
 * Saved batching / correction calculations, kept in the same shape the web stores them in
 * `formulas[i].savedCalcs[]` so a calculation saved on either side opens and edits on the other.
 * The full web record lives in [SavedMixCalcEntity.detailsJson].
 */
object SarimMixCalcRecords {
  const val KIND_BATCH = "BATCHING"
  const val KIND_FIX = "CORRECTION"

  fun details(e: SavedMixCalcEntity): Map<String, Any?> = JsonCodecs.decodeLedgerLink(e.detailsJson) ?: emptyMap()

  fun num(v: Any?): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull() ?: 0.0
    else -> 0.0
  }

  fun text(v: Any?): String = when (v) {
    null -> ""
    is Double -> plain(v)
    else -> v.toString()
  }

  /** Plain decimal text (never scientific notation) so [com.example.util.SarimFormat.parseSafeDouble] accepts it. */
  fun plain(v: Double): String =
    if (!v.isFinite()) "0" else java.math.BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

  fun round6(v: Double): Double = Math.round((if (v.isFinite()) v else 0.0) * 1e6) / 1e6

  /** Re-expresses a typed amount in another unit (g <-> kg), like web `convertValue`. */
  fun convertText(raw: String, from: String, to: String): String {
    val n = raw.trim().toDoubleOrNull() ?: return raw
    if (!n.isFinite() || from == to) return raw
    return plain(if (to == "g") n * 1000.0 else n / 1000.0)
  }

  /** Web-shaped record to push to Firestore. */
  fun toWebRecord(e: SavedMixCalcEntity): Map<String, Any?> {
    val isFix = e.kind == KIND_FIX
    val out = LinkedHashMap<String, Any?>(details(e))
    out["id"] = e.id
    out["kind"] = if (isFix) "fix" else "batch"
    out["name"] = e.name
    out["unit"] = e.unit
    out["createdAt"] = e.createdAt
    out["updatedAt"] = e.updatedAt
    out["summaryText"] = e.summaryText
    if (isFix) {
      if (out["remaining"] == null) out["remaining"] = e.inputValue
    } else {
      out["mode"] = SarimFormulaEngine.normalizeMode(out["mode"]?.toString() ?: e.mode)
      if (out["totalWeight"] == null) out["totalWeight"] = e.inputValue
      if (out["value"] == null) out["value"] = text(e.inputValue)
    }
    return out
  }

  /** Entity fields derived from a web record (`savedCalcs[i]`). */
  fun inputValueOf(web: Map<String, Any?>): Double =
    if (web["kind"]?.toString() == "fix") num(web["remaining"]) else num(web["totalWeight"])

  fun detailsJsonOf(web: Map<String, Any?>): String = JsonCodecs.encodeLedgerLink(web).ifBlank { "{}" }

  fun summaryOf(isFix: Boolean, unit: String, total: Double, add: Double, units: Double, fmt: (Double) -> String): String =
    if (isFix) "Add ${fmt(add)} $unit → Total ${fmt(total)} $unit" else "${fmt(units)} units · ${fmt(total)} $unit"
}
