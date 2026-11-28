package com.example.data.repository

import com.example.data.local.JsonCodecs
import com.example.data.local.SavedMixCalcEntity

/**
 * Saved batching / correction records, kept byte-compatible with the web `formula.savedCalcs[]` entries
 * (see web `screens/batch-mix.screen.html`, `screens/batch-fix.screen.html`, `modules/batch-saved.js`).
 *
 * The full web record is stored in [SavedMixCalcEntity.detailsJson] so nothing is lost when a record
 * made on the web is pulled, edited here, and pushed back (and vice versa).
 */
object SarimSavedCalcs {
  const val KIND_BATCH = "BATCHING"
  const val KIND_FIX = "CORRECTION"

  private fun dbl(v: Any?): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull() ?: 0.0
    else -> 0.0
  }

  private fun str(v: Any?): String = v?.toString().orEmpty()

  @Suppress("UNCHECKED_CAST")
  fun details(c: SavedMixCalcEntity): Map<String, Any?> =
    (JsonCodecs.decodeJson(c.detailsJson) as? Map<String, Any?>).orEmpty()

  /** Web uses total | units | ingredient; older Android rows used WEIGHT | UNITS | INGREDIENT. */
  fun normalizeMode(raw: String): String = when (raw.trim().lowercase()) {
    "units" -> "units"
    "ingredient" -> "ingredient"
    else -> "total"
  }

  fun isFix(c: SavedMixCalcEntity): Boolean = c.kind == KIND_FIX

  fun summaryOf(rec: Map<String, Any?>, isFix: Boolean): String {
    val u = if (str(rec["unit"]) == "g") "g" else "kg"
    return if (isFix) {
      "Add ${SarimBatchCalc.fmtAmt(dbl(rec["addTotal"]), u)} $u → Total ${SarimBatchCalc.fmtAmt(dbl(rec["requiredTotal"]), u)} $u"
    } else {
      "${SarimBatchCalc.fixed3(dbl(rec["units"]))} units · ${SarimBatchCalc.fmtAmt(dbl(rec["totalWeight"]), u)} $u"
    }
  }

  fun buildBatch(
    id: String,
    name: String,
    formulaId: String,
    formulaName: String,
    unit: String,
    mode: String,
    valueText: String,
    ingredientId: String,
    plan: SarimBatchCalc.BatchPlan,
    now: Long,
    createdAt: Long,
  ): SavedMixCalcEntity {
    val rec = linkedMapOf<String, Any?>(
      "id" to id,
      "kind" to "batch",
      "name" to name,
      "unit" to unit,
      "mode" to mode,
      "value" to valueText,
      "ingredientId" to ingredientId,
      "units" to SarimBatchCalc.round6(plan.units),
      "unitWeight" to SarimBatchCalc.round6(SarimBatchCalc.toUnit(plan.unitWeight, unit)),
      "totalWeight" to SarimBatchCalc.round6(SarimBatchCalc.toUnit(plan.totalWeight, unit)),
      "rows" to plan.rows.map {
        linkedMapOf<String, Any?>(
          "n" to it.name,
          "p" to SarimBatchCalc.round6(SarimBatchCalc.toUnit(it.perUnit, unit)),
          "t" to SarimBatchCalc.round6(SarimBatchCalc.toUnit(it.total, unit)),
        )
      },
      "createdAt" to createdAt,
      "updatedAt" to now,
    )
    return SavedMixCalcEntity(
      id = id, kind = KIND_BATCH, name = name, formulaId = formulaId, formulaName = formulaName,
      unit = unit, mode = mode, inputValue = valueText.toDoubleOrNull() ?: 0.0,
      summaryText = summaryOf(rec, false), detailsJson = JsonCodecs.encodeJson(rec),
      syncPending = true, createdAt = createdAt, updatedAt = now,
    )
  }

  fun buildFix(
    id: String,
    name: String,
    formulaId: String,
    formulaName: String,
    unit: String,
    originalText: String,
    usedText: String,
    actual: Map<String, String>,
    manualTotalText: String?,
    manualIngredientId: String?,
    manualAddText: String?,
    plan: SarimBatchCalc.CorrectionPlan,
    now: Long,
    createdAt: Long,
  ): SavedMixCalcEntity {
    val rec = linkedMapOf<String, Any?>(
      "id" to id,
      "kind" to "fix",
      "name" to name,
      "unit" to unit,
      "original" to originalText,
      "used" to usedText,
      "actual" to actual.filterValues { it.isNotBlank() }.map { (k, v) -> linkedMapOf<String, Any?>("i" to k, "v" to v) },
      "remaining" to SarimBatchCalc.round6(plan.remaining),
      "requiredTotal" to SarimBatchCalc.round6(plan.requiredTotal),
      "addTotal" to SarimBatchCalc.round6(plan.addTotal),
      "rows" to plan.rows.map {
        linkedMapOf<String, Any?>(
          "n" to it.name,
          "p" to SarimBatchCalc.round6(it.present),
          "a" to SarimBatchCalc.round6(it.add),
          "t" to SarimBatchCalc.round6(it.target),
        )
      },
      "createdAt" to createdAt,
      "updatedAt" to now,
    )
    if (manualTotalText != null) rec["manualTotal"] = manualTotalText
    else if (manualIngredientId != null && manualAddText != null) {
      rec["manual"] = linkedMapOf<String, Any?>("i" to manualIngredientId, "v" to manualAddText)
    }
    return SavedMixCalcEntity(
      id = id, kind = KIND_FIX, name = name, formulaId = formulaId, formulaName = formulaName,
      unit = unit, mode = "fix", inputValue = plan.addTotal,
      summaryText = summaryOf(rec, true), detailsJson = JsonCodecs.encodeJson(rec),
      syncPending = true, createdAt = createdAt, updatedAt = now,
    )
  }

  /** Entity -> the exact map the web stores in `formula.savedCalcs[]`. */
  fun toWebRecord(c: SavedMixCalcEntity): Map<String, Any?> {
    val fix = isFix(c)
    val rec = linkedMapOf<String, Any?>()
    val stored = details(c)
    if (stored.isNotEmpty()) rec.putAll(stored)
    else if (fix) {
      // Legacy Android row without details: keep what we know so the web can still list it.
      rec["remaining"] = c.inputValue
      rec["addTotal"] = 0.0
      rec["requiredTotal"] = c.inputValue
      rec["rows"] = emptyList<Any>()
    } else {
      rec["mode"] = normalizeMode(c.mode)
      rec["value"] = c.inputValue.toString()
      rec["units"] = 0.0
      rec["totalWeight"] = c.inputValue
      rec["rows"] = emptyList<Any>()
    }
    rec["id"] = c.id
    rec["kind"] = if (fix) "fix" else "batch"
    rec["name"] = c.name
    rec["unit"] = if (c.unit == "g") "g" else "kg"
    rec["createdAt"] = c.createdAt
    rec["updatedAt"] = c.updatedAt
    return rec
  }

  /** Web `savedCalcs[]` entry -> entity (keeps the whole record in detailsJson). */
  fun fromWebRecord(
    formulaId: String,
    formulaName: String,
    sc: Map<*, *>,
    fallbackTs: Long,
    createdAt: Long,
    updatedAt: Long,
  ): SavedMixCalcEntity? {
    val id = str(sc["id"]).trim()
    val name = str(sc["name"]).trim()
    if (id.isBlank() || name.isBlank()) return null
    val fix = str(sc["kind"]).let { it == "fix" || it.equals("CORRECTION", ignoreCase = true) }
    val rec = LinkedHashMap<String, Any?>()
    sc.forEach { (k, v) -> if (k != null) rec[k.toString()] = v }
    val unit = if (str(sc["unit"]) == "g") "g" else "kg"
    return SavedMixCalcEntity(
      id = id,
      kind = if (fix) KIND_FIX else KIND_BATCH,
      name = name,
      formulaId = formulaId,
      formulaName = formulaName,
      unit = unit,
      mode = if (fix) "fix" else normalizeMode(str(sc["mode"])),
      inputValue = if (fix) dbl(sc["addTotal"]) else (sc["totalWeight"]?.let { dbl(it) } ?: dbl(sc["inputValue"])),
      summaryText = str(sc["summaryText"]).ifBlank { summaryOf(rec, fix) },
      detailsJson = JsonCodecs.encodeJson(rec),
      syncPending = false,
      createdAt = if (createdAt > 0) createdAt else fallbackTs,
      updatedAt = if (updatedAt > 0) updatedAt else fallbackTs,
    )
  }

  // ---- read helpers for the UI ----

  data class SavedRow(val name: String, val perUnit: Double, val present: Double, val add: Double, val total: Double)

  fun rowsOf(rec: Map<String, Any?>): List<SavedRow> =
    (rec["rows"] as? List<*>).orEmpty().mapNotNull { r ->
      val m = r as? Map<*, *> ?: return@mapNotNull null
      SavedRow(str(m["n"]), dbl(m["p"]), dbl(m["p"]), dbl(m["a"]), dbl(m["t"]))
    }

  fun num(rec: Map<String, Any?>, key: String): Double = dbl(rec[key])
  fun text(rec: Map<String, Any?>, key: String): String = str(rec[key])

  fun actualOf(rec: Map<String, Any?>): Map<String, String> =
    (rec["actual"] as? List<*>).orEmpty().mapNotNull { a ->
      val m = a as? Map<*, *> ?: return@mapNotNull null
      str(m["i"]) to str(m["v"])
    }.toMap()

  fun manualOf(rec: Map<String, Any?>): SarimBatchCalc.Manual? {
    if (rec.containsKey("manualTotal")) return SarimBatchCalc.Manual(total = str(rec["manualTotal"]).toDoubleOrNull())
    val m = rec["manual"] as? Map<*, *> ?: return null
    return SarimBatchCalc.Manual(ingredientId = str(m["i"]), add = str(m["v"]).toDoubleOrNull())
  }
}
