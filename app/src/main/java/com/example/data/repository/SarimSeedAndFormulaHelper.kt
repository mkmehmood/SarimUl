package com.example.data.repository

import com.example.data.local.*
import java.util.Locale

data class FormulaCostSummary(
  val unitWeightKg: Double,
  val rawMaterialCostPerUnit: Double,
  val totalCostPerUnit: Double,
  /**
   * Real cost per kg used by sales / profit maths. Matches the web chain:
   * (total cost per unit / cost-adjustment factor) / kg per unit.
   */
  val costPerKgAdjusted: Double,
  /** Web "Cost per kg (Sales/Calc)" card figure: total cost per unit / cost-adjustment factor. */
  val salesCalcCost: Double = 0.0,
  /** Additional cost carried in from linked sub-formulas (shown separately on the web card). */
  val linkedAdditionalCost: Double = 0.0,
  val problems: List<FormulaProblem> = emptyList(),
)

object FormulaCalculator {
  fun toEngine(formula: FormulaEntity): EngineFormula = EngineFormula(
    id = formula.id,
    name = formula.name,
    ingredients = JsonCodecs.decodeIngredients(formula.ingredientsJson),
    additionalCost = formula.additionalCostPerUnit,
    factor = formula.costAdjustmentFactor,
  )

  fun engineFormulas(all: Map<String, FormulaEntity>): Map<String, EngineFormula> =
    all.mapValues { toEngine(it.value) }

  fun engineMaterials(all: Map<String, RawMaterialEntity>): Map<String, EngineMaterial> =
    all.mapValues { EngineMaterial(it.value.id, it.value.name, it.value.avgCostPerKg) }

  fun describe(
    formula: FormulaEntity,
    allFormulas: Map<String, FormulaEntity>,
    allMaterials: Map<String, RawMaterialEntity>,
  ): FormulaDescription = SarimFormulaEngine.describe(toEngine(formula), engineFormulas(allFormulas), engineMaterials(allMaterials))

  @Suppress("UNUSED_PARAMETER")
  fun computeFormulaSummary(
    formula: FormulaEntity,
    allFormulas: Map<String, FormulaEntity>,
    allMaterials: Map<String, RawMaterialEntity>,
    visited: Set<String> = emptySet(),
  ): FormulaCostSummary {
    val d = describe(formula, allFormulas, allMaterials)
    return FormulaCostSummary(
      unitWeightKg = d.weight,
      rawMaterialCostPerUnit = d.raw,
      totalCostPerUnit = d.perUnit,
      costPerKgAdjusted = if (d.weight > 0.0001) d.perKg / d.weight else 0.0,
      salesCalcCost = d.perKg,
      linkedAdditionalCost = d.extraCost,
      problems = d.problems,
    )
  }

  /** Raw-material kg needed for [units] of [formula], linked formulas expanded (web `flattenFormula` x units). */
  fun flattenRawMaterials(
    formula: FormulaEntity,
    units: Double,
    allFormulas: Map<String, FormulaEntity>,
    allMaterials: Map<String, RawMaterialEntity>,
    visited: Set<String> = emptySet(),
  ): Map<String, Double> {
    if (units <= 0.0) return emptyMap()
    val flat = SarimFormulaEngine.flatten(toEngine(formula), engineFormulas(allFormulas), engineMaterials(allMaterials), visited.toList())
    val out = LinkedHashMap<String, Double>()
    flat.items.forEach { out[it.id] = (out[it.id] ?: 0.0) + it.quantity * units }
    return out
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
