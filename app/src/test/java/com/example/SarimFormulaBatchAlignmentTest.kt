package com.example

import com.example.data.local.FormulaEntity
import com.example.data.local.JsonCodecs
import com.example.data.local.RawMaterialEntity
import com.example.data.model.FormulaIngredient
import com.example.data.repository.FormulaCalculator
import com.example.data.repository.SarimBatchCalc
import com.example.data.repository.SarimBatchCalc.Ing
import com.example.data.repository.SarimBatchCalc.Manual
import com.example.data.repository.SarimFormulaChecks
import org.junit.Assert.*
import org.junit.Test

/** Mirrors web tests: batch-calc.test.js, formula-links.test.js, formula-checks.test.js. */
class SarimFormulaBatchAlignmentTest {
  private val basic = listOf(Ing("chem", "Chemical", 1.68), Ing("pow", "Powder", 0.7), Ing("tam", "Taman", 0.3), Ing("lime", "Lime", 0.32))
  private val shot = listOf(
    Ing("chem", "Chemical", 1.68), Ing("bar", "Barlanza", 0.212), Ing("pow", "Powder", 0.704),
    Ing("tam", "Taman", 0.303), Ing("lime", "Lime", 0.101),
  )
  private fun near(a: Double, b: Double, tol: Double = 1e-6) = assertEquals(b, a, tol)

  @Test fun planBatch_total_units_ingredient() {
    val t = SarimBatchCalc.planBatch(basic, "total", 100.0, null)
    near(t.totalWeight, 100.0); near(t.units, 100.0 / 3); near(t.rows[0].total, 56.0)
    near(SarimBatchCalc.planBatch(basic, "units", 10.0, null).totalWeight, 30.0)
    val i = SarimBatchCalc.planBatch(basic, "ingredient", 84.0, "chem")
    near(i.units, 50.0); near(i.rows[0].total, 84.0)
  }

  @Test fun planBatch_rejectsBlankNegativeAndOversize() {
    assertEquals(0.0, SarimBatchCalc.planBatch(basic, "total", null, null).units, 0.0)
    assertEquals(0.0, SarimBatchCalc.planBatch(basic, "total", -5.0, null).units, 0.0)
    assertEquals(0.0, SarimBatchCalc.planBatch(basic, "total", 1e9, null).units, 0.0)
    assertEquals(0, SarimBatchCalc.planBatch(emptyList(), "total", 10.0, null).rows.size)
  }

  @Test fun planCorrection_remainingAndDefaults() {
    val r = SarimBatchCalc.planCorrection(shot, 90.0, 15.0, emptyMap(), null)
    assertTrue(r.ok); near(r.remaining, 75.0)
    assertEquals(0.0, r.addTotal, 0.0); assertFalse(r.adjusted)
    assertEquals("nothing-left", SarimBatchCalc.planCorrection(shot, 10.0, 10.0, emptyMap(), null).error)
    assertEquals("empty", SarimBatchCalc.planCorrection(shot, 0.0, 0.0, emptyMap(), null).error)
  }

  @Test fun planCorrection_manualAddAndTotal() {
    val uw = shot.sumOf { it.quantity }
    val a = SarimBatchCalc.planCorrection(shot, 90.0, 15.0, emptyMap(), Manual(ingredientId = "chem", add = 5.0))
    near(a.rows[0].add, 5.0); assertTrue(a.adjusted); assertEquals("chem", a.manualId)
    near(a.rows[0].target / a.requiredTotal, 1.68 / uw); near(a.requiredTotal, a.remaining + a.addTotal)
    val t = SarimBatchCalc.planCorrection(shot, 90.0, 15.0, emptyMap(), Manual(total = 30.0))
    near(t.addTotal, 30.0); near(t.requiredTotal, 105.0); assertEquals("", t.manualId)
    val neg = SarimBatchCalc.planCorrection(shot, 90.0, 15.0, emptyMap(), Manual(ingredientId = "chem", add = -3.0))
    assertFalse(neg.adjusted); assertEquals(0.0, neg.addTotal, 0.0)
  }

  @Test fun planCorrection_actualAmountsAndMinimumTotal() {
    val actual = mapOf("chem" to 16.8, "pow" to 5.0, "tam" to 3.0, "lime" to 3.2)
    val skew = SarimBatchCalc.planCorrection(basic, 28.0, 0.0, actual, Manual(total = 1.0))
    near(skew.minScale, 10.0); near(skew.addTotal, 2.0)
  }

  @Test fun formatting_matchesWeb() {
    assertEquals("56", SarimBatchCalc.fmtAmt(56.0, "kg"))
    assertEquals("1.68", SarimBatchCalc.fmtAmt(1.68, "kg"))
    assertEquals("12.3", SarimBatchCalc.fmtAmt(12.34, "g"))
    assertEquals("0.2121", SarimBatchCalc.fmtPrecise(0.21212, "kg"))
    assertEquals("33.333", SarimBatchCalc.fixed3(100.0 / 3))
    assertEquals("1500", SarimBatchCalc.convertValue("1.5", "kg", "g"))
    assertEquals("", SarimBatchCalc.convertValue("", "kg", "g"))
  }

  // ---- formula links / describeFormula ----
  private fun mat(id: String, name: String, cost: Double) =
    RawMaterialEntity(id = id, name = name, unitName = "kg", conversionFactorKg = 1.0, stockKg = 0.0, collectedKg = 0.0, avgCostPerKg = cost)

  private fun formula(id: String, name: String, add: Double, factor: Double, vararg ings: FormulaIngredient) =
    FormulaEntity(id = id, name = name, additionalCostPerUnit = add, costAdjustmentFactor = factor, ingredientsJson = JsonCodecs.encodeIngredients(ings.toList()))

  private val mats = listOf(mat("m1", "Urea", 120.0), mat("m2", "DAP", 90.0)).associateBy { it.id }
  private val base = formula("base", "Base", 10.0, 1.0, FormulaIngredient("MATERIAL", "m1", "Urea", 6.0), FormulaIngredient("MATERIAL", "m2", "DAP", 4.0))
  private val top = formula("top", "Top", 3.0, 2.0, FormulaIngredient("MATERIAL", "m1", "Urea", 2.0), FormulaIngredient("FORMULA", "base", "Base", 5.0))
  private val all = listOf(base, top).associateBy { it.id }

  @Test fun describe_flattensLinkedFormulaAndCarriesAdditionalCost() {
    val d = FormulaCalculator.describeFormula(top, all, mats)
    assertEquals(listOf("m1" to 5.0, "m2" to 2.0), d.items.map { it.id to it.quantity })
    near(d.weight, 7.0); near(d.ownAdditionalCost, 3.0); near(d.extraCost, 5.0); near(d.additionalCost, 8.0)
    near(d.raw, 5 * 120.0 + 2 * 90.0)
    // web: perKg = perUnit / factor
    near(d.perKg, d.perUnit / 2.0)
  }

  @Test fun describe_reportsMissingAndCircularLinks() {
    val ghost = formula("g", "Ghost", 0.0, 1.0, FormulaIngredient("FORMULA", "nope", "Nope", 1.0))
    assertEquals("missing", FormulaCalculator.describeFormula(ghost, mapOf("g" to ghost), mats).problems.first().type)
    val a = formula("a", "A", 0.0, 1.0, FormulaIngredient("MATERIAL", "m1", "Urea", 1.0), FormulaIngredient("FORMULA", "b", "B", 1.0))
    val b = formula("b", "B", 0.0, 1.0, FormulaIngredient("MATERIAL", "m2", "DAP", 1.0), FormulaIngredient("FORMULA", "a", "A", 1.0))
    val cyc = FormulaCalculator.describeFormula(a, listOf(a, b).associateBy { it.id }, mats)
    assertTrue(cyc.problems.any { it.type == "cycle" })
  }

  @Test fun canLink_blocksLoopsAndSelf() {
    assertFalse(FormulaCalculator.canLink(all, "base", "top"))
    assertFalse(FormulaCalculator.canLink(all, "base", "base"))
    assertTrue(FormulaCalculator.canLink(all, "top", "base"))
    assertEquals(listOf("top"), FormulaCalculator.formulaUsers("base", all.values).map { it.id })
  }

  // ---- formula-checks ----
  @Test fun formIssue_matchesWebMessages() {
    fun row(l: String, q: String, id: String = "", f: Boolean = false) = SarimFormulaChecks.Row(l, q, id, f)
    assertEquals("", SarimFormulaChecks.formIssue(listOf(row("", ""), row("Urea", "2", "m1"))))
    assertEquals("Pick \"Urea\" from the list, or clear that row.", SarimFormulaChecks.formIssue(listOf(row("Urea", "2"))))
    assertTrue(SarimFormulaChecks.formIssue(listOf(row("", "3"))).startsWith("A row has a quantity"))
    assertTrue(SarimFormulaChecks.formIssue(listOf(row("Urea", "0", "m1"))).startsWith("Enter a quantity greater than 0 for Urea"))
    assertEquals("Urea is listed twice. Combine it into one row.", SarimFormulaChecks.formIssue(listOf(row("Urea", "1", "m1"), row("Urea", "2", "m1"))))
  }
}
