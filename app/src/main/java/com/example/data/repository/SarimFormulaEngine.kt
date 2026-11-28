package com.example.data.repository

import com.example.data.model.FormulaIngredient

/**
 * Pure (no Android / Room) port of the web modules `formula-links.js`, `batch-calc.js`
 * and `formula-checks.js`, so the Formula Store, Batching and Correction screens compute
 * exactly what the web app computes.
 */

data class EngineFormula(
  val id: String,
  val name: String,
  val ingredients: List<FormulaIngredient>,
  val additionalCost: Double,
  val factor: Double,
)

data class EngineMaterial(val id: String, val name: String, val costPerKg: Double)

/** One raw material after every linked formula has been expanded. */
data class FlatItem(val id: String, val name: String, val quantity: Double, val costPerKg: Double)

/** `type` is "cycle" or "missing" (same values the web uses). */
data class FormulaProblem(val type: String, val name: String)

data class FlatFormula(
  val items: List<FlatItem>,
  val ownAdditionalCost: Double,
  val extraCost: Double,
  val problems: List<FormulaProblem>,
) {
  val additionalCost: Double get() = ownAdditionalCost + extraCost
}

data class FormulaDescription(
  val items: List<FlatItem>,
  val raw: Double,
  val weight: Double,
  val ownAdditionalCost: Double,
  val extraCost: Double,
  val additionalCost: Double,
  val perUnit: Double,
  /** Web "Cost per kg (Sales/Calc)" figure: total cost per unit divided by the cost-adjustment factor. */
  val perKg: Double,
  val problems: List<FormulaProblem>,
)

data class MixIngredient(val id: String, val name: String, val quantity: Double)

data class BatchRow(val id: String, val name: String, val perUnit: Double, val total: Double)

data class BatchPlan(val units: Double, val unitWeight: Double, val totalWeight: Double, val rows: List<BatchRow>)

sealed class CorrectionManual {
  /** A typed "Total to Add". */
  data class Total(val value: Double) : CorrectionManual()

  /** A typed "Add" amount for one ingredient. */
  data class Add(val id: String, val value: Double) : CorrectionManual()
}

data class CorrectionRow(val id: String, val name: String, val present: Double, val target: Double, val add: Double)

data class CorrectionPlan(
  val ok: Boolean,
  /** "empty" or "nothing-left" when [ok] is false. */
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

data class FormRow(
  val label: String,
  val qtyText: String,
  val linked: Boolean,
  val isFormula: Boolean,
  val refId: String,
)

object SarimFormulaEngine {
  const val MAX_BATCH_KG = 10_000_000.0
  const val MAX_SAVED_CALCS = 40
  private const val EPS = 1e-9
  const val MODE_TOTAL = "total"
  const val MODE_UNITS = "units"
  const val MODE_INGREDIENT = "ingredient"

  private fun r6(v: Double): Double = Math.round(v * 1e6) / 1e6

  private fun isLink(i: FormulaIngredient) = i.refType.equals("FORMULA", ignoreCase = true) && i.refId.isNotBlank()

  // ---------------------------------------------------------------- formula-links.js

  fun flatten(
    formula: EngineFormula,
    formulas: Map<String, EngineFormula>,
    materials: Map<String, EngineMaterial>,
    path: List<String> = emptyList(),
  ): FlatFormula {
    val out = ArrayList<FlatItem>()
    val problems = ArrayList<FormulaProblem>()
    var extra = 0.0
    val here = path + formula.id

    fun add(m: FlatItem) {
      val idx = out.indexOfFirst { it.id == m.id }
      if (idx >= 0) out[idx] = out[idx].copy(quantity = r6(out[idx].quantity + m.quantity))
      else out.add(m)
    }

    for (i in formula.ingredients) {
      if (!isLink(i)) {
        val mat = materials[i.refId]
          ?: materials.values.firstOrNull { i.refName.isNotBlank() && it.name.trim().equals(i.refName.trim(), ignoreCase = true) }
        // An editor row that has not been picked from the list yet is not an ingredient.
        if (mat == null && i.refId.isBlank()) continue
        add(
          FlatItem(
            id = mat?.id ?: i.refId,
            name = mat?.name ?: i.refName,
            quantity = i.qtyKg,
            costPerKg = mat?.costPerKg?.takeIf { it > 0.0 } ?: 0.0,
          )
        )
        continue
      }
      val q = i.qtyKg
      if (q <= 0.0) continue
      val sub = formulas[i.refId]
      if (sub == null) {
        problems.add(FormulaProblem("missing", i.refName.ifBlank { "Formula" }))
        continue
      }
      if (here.contains(sub.id)) {
        problems.add(FormulaProblem("cycle", sub.name.ifBlank { "Formula" }))
        continue
      }
      val flat = flatten(sub, formulas, materials, here)
      problems.addAll(flat.problems)
      val weight = flat.items.sumOf { it.quantity }
      if (weight <= 0.0) continue
      val scale = q / weight
      flat.items.forEach { m -> add(m.copy(quantity = r6(m.quantity * scale))) }
      extra += flat.additionalCost * scale
    }
    return FlatFormula(out, formula.additionalCost, extra, problems)
  }

  fun describe(
    formula: EngineFormula,
    formulas: Map<String, EngineFormula>,
    materials: Map<String, EngineMaterial>,
  ): FormulaDescription {
    val flat = flatten(formula, formulas, materials)
    var raw = 0.0
    var weight = 0.0
    flat.items.forEach { raw += it.costPerKg * it.quantity; weight += it.quantity }
    var factor = if (formula.factor.isFinite()) formula.factor else 1.0
    if (factor == 0.0) factor = 1.0
    val perUnit = raw + flat.additionalCost
    return FormulaDescription(
      items = flat.items,
      raw = raw,
      weight = weight,
      ownAdditionalCost = flat.ownAdditionalCost,
      extraCost = flat.extraCost,
      additionalCost = flat.additionalCost,
      perUnit = perUnit,
      perKg = if (factor > 0.0) perUnit / factor else perUnit,
      problems = flat.problems,
    )
  }

  fun linksIn(formula: EngineFormula?): List<String> =
    formula?.ingredients.orEmpty().filter { isLink(it) }.map { it.refId }

  fun formulaUsers(formulas: Collection<EngineFormula>, id: String): List<EngineFormula> =
    formulas.filter { it.id != id && linksIn(it).contains(id) }

  fun dependsOn(
    formulas: Map<String, EngineFormula>,
    formulaId: String,
    targetId: String,
    seen: MutableSet<String> = HashSet(),
  ): Boolean {
    if (formulaId == targetId) return true
    if (!seen.add(formulaId)) return false
    return linksIn(formulas[formulaId]).any { dependsOn(formulas, it, targetId, seen) }
  }

  fun canLink(formulas: Map<String, EngineFormula>, parentId: String, childId: String): Boolean =
    parentId != childId && !dependsOn(formulas, childId, parentId)

  // ---------------------------------------------------------------- batch-calc.js

  private fun num(v: Double?): Double = if (v != null && v.isFinite() && v > 0.0 && v <= MAX_BATCH_KG) v else 0.0

  fun mixIngredients(items: List<FlatItem>): List<MixIngredient> =
    items.map { MixIngredient(it.id, it.name.ifBlank { "Untitled" }, num(it.quantity)) }.filter { it.quantity > 0.0 }

  fun unitWeight(ings: List<MixIngredient>): Double = ings.sumOf { it.quantity }

  fun planBatch(ings: List<MixIngredient>, mode: String, value: Double?, ingredientId: String? = null): BatchPlan {
    val uw = unitWeight(ings)
    val v = num(value)
    var units = 0.0
    if (ings.isNotEmpty() && v > 0.0) {
      units = when (mode) {
        MODE_UNITS -> v
        MODE_TOTAL -> v / uw
        MODE_INGREDIENT -> {
          val pick = ings.firstOrNull { it.id == ingredientId } ?: ings[0]
          v / pick.quantity
        }
        else -> 0.0
      }
    }
    return BatchPlan(
      units = units,
      unitWeight = uw,
      totalWeight = units * uw,
      rows = ings.map { BatchRow(it.id, it.name, it.quantity, it.quantity * units) },
    )
  }

  fun planCorrection(
    ings: List<MixIngredient>,
    original: Double?,
    used: Double?,
    actual: Map<String, Double> = emptyMap(),
    manual: CorrectionManual? = null,
  ): CorrectionPlan {
    val uw = unitWeight(ings)
    val orig = num(original)
    if (ings.isEmpty() || orig <= 0.0) return CorrectionPlan(ok = false, error = "empty")
    val usedKg = maxOf(0.0, used?.takeIf { it.isFinite() } ?: 0.0)
    val remaining = orig - usedKg
    if (remaining <= EPS) return CorrectionPlan(ok = false, error = "nothing-left")

    val actualTotal = ings.sumOf { num(actual[it.id]) }
    val present = ings.map { i ->
      if (actualTotal > 0.0) (num(actual[i.id]) / actualTotal) * remaining else (i.quantity / uw) * remaining
    }
    var minScale = 0.0
    ings.forEachIndexed { idx, i -> minScale = maxOf(minScale, present[idx] / i.quantity) }
    var scale = minScale
    var manualId = ""
    when (manual) {
      is CorrectionManual.Total -> if (manual.value.isFinite()) {
        scale = maxOf(minScale, (remaining + maxOf(0.0, manual.value)) / uw)
      }
      is CorrectionManual.Add -> {
        val k = ings.indexOfFirst { it.id == manual.id }
        if (k >= 0 && manual.value.isFinite()) {
          scale = maxOf(minScale, (present[k] + maxOf(0.0, manual.value)) / ings[k].quantity)
          manualId = ings[k].id
        }
      }
      null -> Unit
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

  fun normalizeMode(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "units" -> MODE_UNITS
    "ingredient" -> MODE_INGREDIENT
    else -> MODE_TOTAL // "total", legacy "WEIGHT", unknown
  }

  fun defaultCalcName(formulaName: String, isFix: Boolean, day: String): String =
    "${if (isFix) "Correction" else "Batch"} · ${formulaName.ifBlank { "Formula" }} · $day"

  // ---------------------------------------------------------------- formula-checks.js

  private fun clean(v: String?): String = (v ?: "").replace(Regex("\\s+"), " ").trim()

  fun formIssue(rows: List<FormRow>): String {
    val seen = HashSet<String>()
    for (r in rows) {
      val label = clean(r.label)
      val hasQty = r.qtyText.trim().isNotEmpty()
      val qty = r.qtyText.trim().toDoubleOrNull() ?: if (hasQty) Double.NaN else 0.0
      if (label.isEmpty() && !hasQty) continue
      if (label.isNotEmpty() && !r.linked) return "Pick \"$label\" from the list, or clear that row."
      if (label.isEmpty() && hasQty) return "A row has a quantity but no ingredient. Pick an ingredient or clear the row."
      if (!(qty > 0.0)) return "Enter a quantity greater than 0 for $label, or clear that row."
      val id = if (r.isFormula) "f:${r.refId}" else "m:${r.refId}"
      if (!seen.add(id)) return "$label is listed twice. Combine it into one row."
    }
    return ""
  }
}
