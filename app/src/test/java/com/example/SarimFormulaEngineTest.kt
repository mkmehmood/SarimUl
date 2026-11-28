package com.example

import com.example.data.model.FormulaIngredient
import com.example.data.repository.CorrectionManual
import com.example.data.repository.EngineFormula
import com.example.data.repository.EngineMaterial
import com.example.data.repository.FormRow
import com.example.data.repository.MixIngredient
import com.example.data.repository.SarimFormulaEngine as E
import org.junit.Assert.*
import org.junit.Test

class SarimFormulaEngineTest {
  private fun near(a: Double, b: Double, tol: Double = 1e-6) = assertTrue("$a vs $b", Math.abs(a - b) < tol)

  private fun mix(vararg p: Pair<String, Double>) = p.map { MixIngredient(it.first, it.first, it.second) }

  private val basic = mix("chem" to 1.68, "pow" to 0.7, "tam" to 0.3, "lime" to 0.32)
  private val shot = mix("chem" to 1.68, "bar" to 0.212, "pow" to 0.704, "tam" to 0.303, "lime" to 0.101)

  // ---- planBatch (web batch-calc.test.js)

  @Test fun unitWeightIsSumOfQuantities() = near(E.unitWeight(basic), 3.0)

  @Test fun batchForTargetTotalWeight() {
    val p = E.planBatch(basic, "total", 100.0)
    near(p.totalWeight, 100.0)
    near(p.units, 100.0 / 3)
    near(p.rows.sumOf { it.total }, 100.0)
    near(p.rows[0].total, 56.0)
  }

  @Test fun batchFromUnits() {
    val p = E.planBatch(basic, "units", 10.0)
    near(p.totalWeight, 30.0)
    near(p.rows[1].total, 7.0)
  }

  @Test fun batchUsingUpIngredientOnHand() {
    val p = E.planBatch(basic, "ingredient", 84.0, "chem")
    near(p.units, 50.0)
    near(p.totalWeight, 150.0)
    near(p.rows[0].total, 84.0)
  }

  @Test fun emptyPlanForBadInput() {
    assertEquals(0.0, E.planBatch(basic, "total", null).units, 0.0)
    assertEquals(0.0, E.planBatch(basic, "total", -5.0).units, 0.0)
    assertEquals(0, E.planBatch(emptyList(), "total", 10.0).rows.size)
    assertEquals(0.0, E.planBatch(basic, "total", E.MAX_BATCH_KG + 1).units, 0.0)
  }

  // ---- planCorrection

  @Test fun remainingIsOriginalMinusUsed() {
    val r = E.planCorrection(shot, 89.98, 15.0)
    assertTrue(r.ok)
    near(r.remaining, 74.98)
    near(r.rows.sumOf { it.present }, 74.98)
  }

  @Test fun nothingAddedWhenRemainderFollowsFormula() {
    val r = E.planCorrection(shot, 90.0, 15.0)
    assertEquals(0.0, r.addTotal, 0.0)
    assertFalse(r.adjusted)
  }

  @Test fun typedAddScalesEveryOtherIngredient() {
    val r = E.planCorrection(shot, 90.0, 15.0, manual = CorrectionManual.Add("chem", 5.0))
    near(r.rows[0].add, 5.0)
    assertTrue(r.adjusted)
    assertEquals("chem", r.manualId)
    near(r.rows[0].target / r.requiredTotal, 1.68 / E.unitWeight(shot))
    near(r.rows[2].target / r.requiredTotal, 0.704 / E.unitWeight(shot))
    near(r.requiredTotal, r.remaining + r.addTotal)
  }

  @Test fun typedTotalScalesEveryIngredient() {
    val r = E.planCorrection(shot, 90.0, 15.0, manual = CorrectionManual.Total(30.0))
    near(r.addTotal, 30.0)
    near(r.requiredTotal, 105.0)
    assertTrue(r.adjusted)
    assertEquals("", r.manualId)
    near(r.rows.sumOf { it.present + it.add }, 105.0)
  }

  @Test fun typedTotalNeverBelowMinimum() {
    val none = E.planCorrection(shot, 90.0, 15.0, manual = CorrectionManual.Total(0.0))
    assertFalse(none.adjusted)
    assertEquals(0.0, none.addTotal, 0.0)
    val skew = E.planCorrection(
      basic, 28.0, 0.0,
      actual = mapOf("chem" to 16.8, "pow" to 5.0, "tam" to 3.0, "lime" to 3.2),
      manual = CorrectionManual.Total(1.0),
    )
    near(skew.minScale, 10.0)
    near(skew.addTotal, 2.0)
    val neg = E.planCorrection(shot, 90.0, 15.0, manual = CorrectionManual.Add("chem", -3.0))
    assertFalse(neg.adjusted)
    assertEquals(0.0, neg.addTotal, 0.0)
  }

  @Test fun usesActualMixedAmounts() {
    val r = E.planCorrection(basic, 28.0, 0.0, actual = mapOf("chem" to 16.8, "pow" to 5.0, "tam" to 3.0, "lime" to 3.2))
    near(r.minScale, 10.0)
    near(r.rows[1].add, 2.0)
    near(r.rows[0].add, 0.0)
    near(r.requiredTotal, 30.0)
  }

  @Test fun dilutesWhenOneIngredientInExcess() {
    val r = E.planCorrection(basic, 33.2, 0.0, actual = mapOf("chem" to 16.8, "pow" to 7.0, "tam" to 3.0, "lime" to 6.4))
    near(r.minScale, 20.0)
    near(r.rows[0].add, 16.8)
    near(r.rows[3].add, 0.0)
  }

  @Test fun rejectsEmptyAndNothingLeft() {
    assertFalse(E.planCorrection(shot, null, null).ok)
    assertEquals("nothing-left", E.planCorrection(shot, 10.0, 10.0).error)
    assertEquals("nothing-left", E.planCorrection(shot, 10.0, 12.0).error)
  }

  // ---- linked formulas

  private fun mat(id: String, cost: Double) = id to EngineMaterial(id, id.uppercase(), cost)
  private val mats = mapOf(mat("a", 10.0), mat("b", 20.0))
  private fun link(id: String, q: Double) = FormulaIngredient("FORMULA", id, id, q)
  private fun m(id: String, q: Double) = FormulaIngredient("MATERIAL", id, id, q)
  private fun f(id: String, add: Double, vararg ings: FormulaIngredient, factor: Double = 1.0) =
    id to EngineFormula(id, id, ings.toList(), add, factor)

  @Test fun linkScalesSubFormulaAndCarriesItsAdditionalCost() {
    val formulas = mapOf(
      f("sub", 100.0, m("a", 1.0), m("b", 1.0)), // 2 kg, raw 30, +100 => 130 per unit
      f("top", 5.0, link("sub", 1.0), m("a", 1.0)),
    )
    val d = E.describe(formulas["top"]!!, formulas, mats)
    near(d.weight, 2.0)
    // half a sub unit: 0.5a + 0.5b, plus a 1 kg => a = 1.5, b = 0.5
    near(d.items.first { it.id == "a" }.quantity, 1.5)
    near(d.items.first { it.id == "b" }.quantity, 0.5)
    near(d.raw, 1.5 * 10 + 0.5 * 20)
    near(d.extraCost, 50.0)
    near(d.additionalCost, 55.0)
    near(d.perUnit, d.raw + 55.0)
  }

  @Test fun perKgDividesByFactorLikeWeb() {
    val formulas = mapOf(f("x", 20.0, m("a", 2.0), factor = 4.0))
    val d = E.describe(formulas["x"]!!, formulas, mats)
    near(d.perUnit, 40.0)
    near(d.perKg, 10.0)
    val zero = mapOf(f("y", 0.0, m("a", 2.0), factor = 0.0))
    near(E.describe(zero["y"]!!, zero, mats).perKg, 20.0)
  }

  @Test fun cycleAndMissingAreReportedNotFollowed() {
    val formulas = mapOf(
      f("p", 0.0, link("q", 1.0), m("a", 1.0)),
      f("q", 0.0, link("p", 1.0), m("b", 1.0)),
      f("r", 0.0, link("gone", 1.0), m("a", 1.0)),
    )
    val cyc = E.flatten(formulas["p"]!!, formulas, mats)
    assertTrue(cyc.problems.any { it.type == "cycle" })
    near(cyc.items.sumOf { it.quantity }, 2.0)
    val miss = E.flatten(formulas["r"]!!, formulas, mats)
    assertEquals("missing", miss.problems.single().type)
  }

  @Test fun canLinkBlocksSelfAndCycles() {
    val formulas = mapOf(f("p", 0.0, link("q", 1.0)), f("q", 0.0, m("a", 1.0)))
    assertFalse(E.canLink(formulas, "p", "p"))
    assertFalse(E.canLink(formulas, "q", "p"))
    assertTrue(E.canLink(formulas, "p", "q"))
  }

  // ---- form check

  @Test fun formIssueMatchesWebMessages() {
    fun row(l: String, q: String, linked: Boolean = true, formula: Boolean = false, id: String = l) = FormRow(l, q, linked, formula, id)
    assertEquals("", E.formIssue(listOf(row("", ""), row("A", "1"))))
    assertEquals("Pick \"Zed\" from the list, or clear that row.", E.formIssue(listOf(row("Zed", "1", linked = false))))
    assertEquals("A row has a quantity but no ingredient. Pick an ingredient or clear the row.", E.formIssue(listOf(row("", "2"))))
    assertEquals("Enter a quantity greater than 0 for A, or clear that row.", E.formIssue(listOf(row("A", "0"))))
    assertEquals("A is listed twice. Combine it into one row.", E.formIssue(listOf(row("A", "1"), row("A", "2"))))
    assertEquals("", E.formIssue(listOf(row("A", "1"), row("A", "2", formula = true))))
  }

  @Test fun modeNormalisation() {
    assertEquals("total", E.normalizeMode("WEIGHT"))
    assertEquals("total", E.normalizeMode("total"))
    assertEquals("units", E.normalizeMode("UNITS"))
    assertEquals("ingredient", E.normalizeMode("INGREDIENT"))
  }
}
