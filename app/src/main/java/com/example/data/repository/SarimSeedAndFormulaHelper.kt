package com.example.data.repository

import com.example.data.local.*
import java.util.Locale

/**
 * Mirrors web `formula-links.js::describeFormula`.
 * [additionalCostPerUnit] = own additional cost + additional cost carried in from linked formulas.
 * [costPerKgAdjusted] = total cost per unit / cost adjustment factor (web "Cost per kg (Sales/Calc)").
 */
data class FormulaCostSummary(
  val unitWeightKg: Double,
  val rawMaterialCostPerUnit: Double,
  val totalCostPerUnit: Double,
  val costPerKgAdjusted: Double,
  val additionalCostPerUnit: Double = 0.0,
)

data class FormulaProblem(val type: String, val name: String) // type: "missing" | "cycle"

data class FlatIngredient(
  val id: String,
  val name: String,
  val quantity: Double,
  val cost: Double,
  val via: List<String> = emptyList(),
)

data class FormulaDescription(
  val items: List<FlatIngredient>,
  val raw: Double,
  val weight: Double,
  val ownAdditionalCost: Double,
  val extraCost: Double,
  val additionalCost: Double,
  val perUnit: Double,
  val perKg: Double,
  val problems: List<FormulaProblem>,
)

object FormulaCalculator {
  private fun r6(v: Double): Double = Math.round(v * 1e6) / 1e6

  private class FlatResult(
    val ingredients: MutableList<FlatIngredient> = mutableListOf(),
    var extraCost: Double = 0.0,
    var ownAdditionalCost: Double = 0.0,
    val problems: MutableList<FormulaProblem> = mutableListOf(),
  ) {
    val additionalCost: Double get() = ownAdditionalCost + extraCost
  }

  private fun resolveMaterial(
    ing: com.example.data.model.FormulaIngredient,
    allMaterials: Map<String, RawMaterialEntity>,
  ): RawMaterialEntity? = allMaterials[ing.refId]
    ?: allMaterials.values.firstOrNull { ing.refName.isNotBlank() && it.name.equals(ing.refName.trim(), ignoreCase = true) }

  /** Web `flattenFormula`: expands linked formulas into raw materials, merging duplicates, skipping cycles / missing links. */
  private fun flatten(
    formula: FormulaEntity,
    allFormulas: Map<String, FormulaEntity>,
    allMaterials: Map<String, RawMaterialEntity>,
    path: List<String>,
  ): FlatResult {
    val out = FlatResult()
    val here = path + formula.id
    out.ownAdditionalCost = formula.additionalCostPerUnit
    fun add(m: FlatIngredient) {
      val idx = out.ingredients.indexOfFirst { it.id == m.id }
      if (idx >= 0) {
        val hit = out.ingredients[idx]
        out.ingredients[idx] = hit.copy(quantity = r6(hit.quantity + m.quantity), via = (hit.via + m.via).distinct())
      } else {
        out.ingredients += m
      }
    }
    for (ing in JsonCodecs.decodeIngredients(formula.ingredientsJson)) {
      if (ing.refType != "FORMULA") {
        val mat = resolveMaterial(ing, allMaterials)
        add(
          FlatIngredient(
            id = mat?.id ?: ing.refId,
            name = mat?.name ?: ing.refName,
            quantity = ing.qtyKg,
            cost = mat?.avgCostPerKg ?: 0.0,
          )
        )
        continue
      }
      val q = ing.qtyKg
      if (q <= 0.0) continue
      val sub = allFormulas[ing.refId]
      if (sub == null) {
        out.problems += FormulaProblem("missing", ing.refName.ifBlank { "Formula" })
        continue
      }
      if (sub.id in here) {
        out.problems += FormulaProblem("cycle", sub.name.ifBlank { "Formula" })
        continue
      }
      val flat = flatten(sub, allFormulas, allMaterials, here)
      out.problems += flat.problems
      val weight = flat.ingredients.sumOf { it.quantity }
      if (weight <= 0.0) continue
      val scale = q / weight
      flat.ingredients.forEach { m ->
        add(m.copy(quantity = r6(m.quantity * scale), via = listOf(sub.name.ifBlank { "Formula" })))
      }
      out.extraCost += flat.additionalCost * scale
    }
    return out
  }

  fun describeFormula(
    formula: FormulaEntity,
    allFormulas: Map<String, FormulaEntity>,
    allMaterials: Map<String, RawMaterialEntity>,
  ): FormulaDescription {
    val flat = flatten(formula, allFormulas, allMaterials, emptyList())
    var raw = 0.0
    var weight = 0.0
    flat.ingredients.forEach { raw += it.cost * it.quantity; weight += it.quantity }
    val factor = if (formula.costAdjustmentFactor == 0.0) 1.0 else formula.costAdjustmentFactor
    val perUnit = raw + flat.additionalCost
    return FormulaDescription(
      items = flat.ingredients.toList(),
      raw = raw,
      weight = weight,
      ownAdditionalCost = flat.ownAdditionalCost,
      extraCost = flat.extraCost,
      additionalCost = flat.additionalCost,
      perUnit = perUnit,
      perKg = if (factor > 0.0) perUnit / factor else perUnit,
      problems = flat.problems.toList(),
    )
  }

  fun computeFormulaSummary(
    formula: FormulaEntity,
    allFormulas: Map<String, FormulaEntity>,
    allMaterials: Map<String, RawMaterialEntity>,
    @Suppress("UNUSED_PARAMETER") visited: Set<String> = emptySet(),
  ): FormulaCostSummary {
    val d = describeFormula(formula, allFormulas, allMaterials)
    return FormulaCostSummary(
      unitWeightKg = d.weight,
      rawMaterialCostPerUnit = d.raw,
      totalCostPerUnit = d.perUnit,
      costPerKgAdjusted = d.perKg,
      additionalCostPerUnit = d.additionalCost,
    )
  }

  /** Web `formulaUsers`: formulas that link to [formulaId]. */
  fun formulaUsers(formulaId: String, all: Collection<FormulaEntity>): List<FormulaEntity> =
    all.filter { f ->
      f.id != formulaId && JsonCodecs.decodeIngredients(f.ingredientsJson).any { it.refType == "FORMULA" && it.refId == formulaId }
    }

  private fun dependsOn(all: Map<String, FormulaEntity>, from: String, target: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
    if (from == target) return true
    if (!seen.add(from)) return false
    val f = all[from] ?: return false
    return JsonCodecs.decodeIngredients(f.ingredientsJson)
      .filter { it.refType == "FORMULA" }
      .any { dependsOn(all, it.refId, target, seen) }
  }

  /** Web `canLink`: parent may link child unless that would create a loop. */
  fun canLink(all: Map<String, FormulaEntity>, parentId: String, childId: String): Boolean =
    parentId != childId && !dependsOn(all, childId, parentId)

  fun flattenRawMaterials(
    formula: FormulaEntity,
    units: Double,
    allFormulas: Map<String, FormulaEntity>,
    allMaterials: Map<String, RawMaterialEntity>,
    visited: Set<String> = emptySet(),
  ): Map<String, Double> {
    if (formula.id in visited || units <= 0.0) return emptyMap()
    val nextVisited = visited + formula.id
    val result = mutableMapOf<String, Double>()
    val ingredients = JsonCodecs.decodeIngredients(formula.ingredientsJson)

    for (ing in ingredients) {
      if (ing.refType == "FORMULA") {
        val sub = allFormulas[ing.refId] ?: continue
        val subSummary = computeFormulaSummary(sub, allFormulas, allMaterials, nextVisited)
        val subUnitFactor = if (subSummary.unitWeightKg > 0.0001) (ing.qtyKg * units) / subSummary.unitWeightKg else 0.0
        val subFlat = flattenRawMaterials(sub, subUnitFactor, allFormulas, allMaterials, nextVisited)
        subFlat.forEach { (matId, kg) -> result[matId] = (result[matId] ?: 0.0) + kg }
      } else {
        val matId = allMaterials[ing.refId]?.id
          ?: allMaterials.values.firstOrNull { ing.refName.isNotBlank() && it.name.equals(ing.refName.trim(), ignoreCase = true) }?.id
          ?: ing.refId
        result[matId] = (result[matId] ?: 0.0) + (ing.qtyKg * units)
      }
    }
    return result
  }
}

object SarimSeedHelper {
  suspend fun ensureStructuralDefaults(dao: SarimDao) {
    val now = System.currentTimeMillis()
    if (dao.getSchemaVersion() == null) {
      dao.upsertSchemaVersion(SchemaVersionEntity(version = SarimDatabase.SCHEMA_VERSION, upgradedAt = now))
    }

    if (dao.getKvMetaByUserKey("dbready") == null) {
      dao.upsertKv(KvStoreEntity(fullKey = "dbready", userKey = "dbready", rowType = "meta", value = "true", ts = now, createdAt = now))
      dao.upsertKv(KvStoreEntity(fullKey = "categories", userKey = "categories", rowType = "settings", value = "[\"Operating Expense\"]", ts = now, createdAt = now))
      dao.upsertKv(KvStoreEntity(fullKey = "field_expense_categories", userKey = "field_expense_categories", rowType = "settings", value = "[\"Fuel\",\"Food\",\"Toll\"]", ts = now, createdAt = now))
      dao.upsertKv(KvStoreEntity(fullKey = "commission_categories", userKey = "commission_categories", rowType = "settings", value = "[\"Standard Commission\"]", ts = now, createdAt = now))
    }

    val existingMaterials = dao.getAllRawMaterials()
    if (existingMaterials.none { it.name.equals("CHORA", ignoreCase = true) }) {
      dao.upsertRawMaterial(
        RawMaterialEntity(
          id = "mat_chora",
          name = "CHORA",
          unitName = "kg",
          conversionFactorKg = 1.0,
          stockKg = 0.0,
          collectedKg = 0.0,
          avgCostPerKg = 0.0,
          syncPending = false,
        )
      )
    }

    val existingFormulas = dao.getAllFormulas()
    if (existingFormulas.isEmpty()) {
      dao.upsertFormula(
        FormulaEntity(
          id = "formula_standard",
          name = "STANDARD",
          additionalCostPerUnit = 750.0,
          costAdjustmentFactor = 1.0,
          ingredientsJson = "[]",
          unitsAvailable = 0.0,
          unitsUsed = 0.0,
          syncPending = false,
        )
      )
      dao.upsertFormula(
        FormulaEntity(
          id = "formula_asaan",
          name = "ASAAN",
          additionalCostPerUnit = 500.0,
          costAdjustmentFactor = 1.0,
          ingredientsJson = "[]",
          unitsAvailable = 0.0,
          unitsUsed = 0.0,
          syncPending = false,
        )
      )
    }

    val existingStores = dao.getAllStores()
    if (existingStores.isEmpty()) {
      dao.upsertStore(
        StoreEntity(
          storeKey = "zubair",
          name = "ZUBAIR",
          formulaId = "formula_standard",
          salePricePerKg = 400.0,
          syncPending = false,
        )
      )
      dao.upsertStore(
        StoreEntity(
          storeKey = "mahmood",
          name = "MAHMOOD",
          formulaId = "formula_standard",
          salePricePerKg = 400.0,
          syncPending = false,
        )
      )
      dao.upsertStore(
        StoreEntity(
          storeKey = "asaan",
          name = "ASAAN",
          formulaId = "formula_asaan",
          salePricePerKg = 380.0,
          syncPending = false,
        )
      )
    }
  }

  fun storeKeyFromName(name: String): String {
    return name.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "store" }
  }
}
