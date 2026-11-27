package com.example.data.repository

import com.example.data.local.*
import java.util.Locale

data class FormulaCostSummary(
  val unitWeightKg: Double,
  val rawMaterialCostPerUnit: Double,
  val totalCostPerUnit: Double,
  val costPerKgAdjusted: Double,
)

object FormulaCalculator {
  fun computeFormulaSummary(
    formula: FormulaEntity,
    allFormulas: Map<String, FormulaEntity>,
    allMaterials: Map<String, RawMaterialEntity>,
    visited: Set<String> = emptySet(),
  ): FormulaCostSummary {
    if (formula.id in visited) {
      return FormulaCostSummary(0.0, 0.0, formula.additionalCostPerUnit, 0.0)
    }
    val nextVisited = visited + formula.id
    val ingredients = JsonCodecs.decodeIngredients(formula.ingredientsJson)
    var totalWeight = 0.0
    var rawCost = 0.0

    for (ing in ingredients) {
      if (ing.refType == "FORMULA") {
        val sub = allFormulas[ing.refId]
        if (sub != null) {
          val subSummary = computeFormulaSummary(sub, allFormulas, allMaterials, nextVisited)
          val subCostPerKg = if (subSummary.unitWeightKg > 0.0001) {
            subSummary.totalCostPerUnit / subSummary.unitWeightKg
          } else {
            0.0
          }
          totalWeight += ing.qtyKg
          rawCost += ing.qtyKg * subCostPerKg
        }
      } else {
        val mat = allMaterials[ing.refId]
          ?: allMaterials.values.firstOrNull { it.name.equals(ing.refName, ignoreCase = true) }
        val unitCost = mat?.avgCostPerKg ?: 0.0
        totalWeight += ing.qtyKg
        rawCost += ing.qtyKg * unitCost
      }
    }

    val totalPerUnit = rawCost + formula.additionalCostPerUnit
    val baseCostPerKg = if (totalWeight > 0.0001) totalPerUnit / totalWeight else 0.0
    val adjustedCostPerKg = baseCostPerKg * formula.costAdjustmentFactor.coerceAtLeast(0.01)

    return FormulaCostSummary(
      unitWeightKg = totalWeight,
      rawMaterialCostPerUnit = rawCost,
      totalCostPerUnit = totalPerUnit,
      costPerKgAdjusted = adjustedCostPerKg,
    )
  }

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
