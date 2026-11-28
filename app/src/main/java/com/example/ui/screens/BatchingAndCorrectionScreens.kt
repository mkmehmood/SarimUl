package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.FormulaEntity
import com.example.data.local.SavedMixCalcEntity
import com.example.data.model.SarimUuid
import com.example.data.repository.FormulaCalculator
import com.example.data.repository.SarimBatchCalc
import com.example.data.repository.SarimSavedCalcs
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

/** Flattened (links expanded, duplicates merged) ingredients of a formula, as the web `resolveAll` does. */
private fun ingredientsOf(
  formula: FormulaEntity?,
  formulasMap: Map<String, FormulaEntity>,
  materialsMap: Map<String, com.example.data.local.RawMaterialEntity>,
): List<SarimBatchCalc.Ing> =
  if (formula == null) emptyList()
  else SarimBatchCalc.ingredients(FormulaCalculator.describeFormula(formula, formulasMap, materialsMap).items)

private fun parse(text: String): Double = text.trim().toDoubleOrNull() ?: 0.0

@Composable
private fun AmountRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
  val palette = LocalSarimPalette.current
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = palette.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
    Text(value, color = valueColor ?: palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
  }
}

@Composable
private fun SavedCalcCard(
  calc: SavedMixCalcEntity,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  val palette = LocalSarimPalette.current
  val rec = remember(calc) { SarimSavedCalcs.toWebRecord(calc) }
  val fix = SarimSavedCalcs.isFix(calc)
  val u = if (calc.unit == "g") "g" else "kg"
  val f = { v: Double -> SarimBatchCalc.fmtAmt(v, u) }
  SarimCard(borderColor = (if (fix) palette.amber else palette.cyan).copy(alpha = 0.4f)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(calc.name, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
      SarimChip(if (fix) "Correction" else "Batch", if (fix) palette.amber else palette.cyan)
    }
    if (fix) {
      AmountRow("Remaining batch", "${f(SarimSavedCalcs.num(rec, "remaining"))} $u")
      AmountRow("New total", "${f(SarimSavedCalcs.num(rec, "requiredTotal"))} $u", palette.green)
      AmountRow("Total to add", "${f(SarimSavedCalcs.num(rec, "addTotal"))} $u", palette.amber)
    } else {
      AmountRow("Units", SarimBatchCalc.fixed3(SarimSavedCalcs.num(rec, "units")))
      AmountRow("Total batch weight", "${f(SarimSavedCalcs.num(rec, "totalWeight"))} $u", palette.green)
    }
    HorizontalDivider(color = palette.border)
    Text(if (fix) "Ingredients to add" else "Required ingredients", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    SarimSavedCalcs.rowsOf(rec).forEach { r ->
      if (fix) AmountRow(r.name, "${if (r.add > 0) "+" else ""}${f(r.add)} $u") else AmountRow(r.name, "${f(r.total)} $u")
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SarimSecondaryButton(text = "Edit", modifier = Modifier.weight(1f), onClick = onEdit)
      SarimDangerButton(text = "Delete", modifier = Modifier.weight(1f), onClick = onDelete)
    }
  }
}

@Composable
fun BatchingScreen(
  savedId: String?,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val formulas = remember(state.formulas) { state.formulas.filter { !it.isDeleted } }
  val formulasMap = remember(formulas) { formulas.associateBy { it.id } }
  val materialsMap = remember(state.rawMaterials) { state.rawMaterials.filter { !it.isDeleted }.associateBy { it.id } }

  var formulaId by remember { mutableStateOf(formulas.firstOrNull()?.id ?: "") }
  var unit by remember { mutableStateOf("kg") }
  var mode by remember { mutableStateOf("total") }
  var ingredientId by remember { mutableStateOf("") }
  var valueText by remember { mutableStateOf("") }
  var saveName by remember { mutableStateOf("") }
  var editingId by remember { mutableStateOf<String?>(null) }

  fun load(c: SavedMixCalcEntity) {
    val rec = SarimSavedCalcs.toWebRecord(c)
    formulaId = c.formulaId
    unit = if (c.unit == "g") "g" else "kg"
    mode = SarimSavedCalcs.normalizeMode(c.mode)
    valueText = SarimSavedCalcs.text(rec, "value")
    ingredientId = SarimSavedCalcs.text(rec, "ingredientId")
    saveName = c.name
    editingId = c.id
  }

  LaunchedEffect(savedId) {
    state.mixCalcs.firstOrNull { it.id == savedId && !it.isDeleted }?.let { load(it) }
  }

  val formula = formulasMap[formulaId]
  val ings = remember(formula, formulasMap, materialsMap) { ingredientsOf(formula, formulasMap, materialsMap) }
  val ingPick = ings.firstOrNull { it.id == ingredientId } ?: ings.firstOrNull()
  val raw = parse(valueText)
  val plan = SarimBatchCalc.planBatch(ings, mode, if (mode == "units") raw else SarimBatchCalc.toKg(raw, unit), ingPick?.id)
  val saved = state.mixCalcs.filter { !it.isDeleted && it.formulaId == formulaId && it.kind == SarimSavedCalcs.KIND_BATCH }
    .sortedByDescending { it.createdAt }
  val fmt = { kg: Double -> SarimBatchCalc.fmtAmt(SarimBatchCalc.toUnit(kg, unit), unit) }

  fun exitEdit() { editingId = null; saveName = "" }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (formulas.isEmpty()) {
      SarimEmptyState("Add a formula in the Formula Store first", "Batching needs at least one formula.")
      return@Column
    }
    SarimCard {
      SarimPopoverSelector(
        label = "Formula",
        selectedText = formula?.name ?: "Select formula",
        options = formulas.map { it.name.ifBlank { "Untitled" } },
        onSelect = { idx, _ -> formulaId = formulas[idx].id; ingredientId = ""; exitEdit() },
        testTag = "bm_formula_btn",
      )
      if (formula != null) {
        Text(
          "1 unit = ${SarimBatchCalc.fmtPrecise(SarimBatchCalc.toUnit(plan.unitWeight, unit), unit)} $unit · ${ings.size} ingredient${if (ings.size == 1) "" else "s"}",
          color = palette.textMuted, fontSize = 11.5.sp,
        )
      }
      SarimToggleGroup(
        options = listOf("g", "kg"), selected = unit, labelOf = { it },
        onSelect = { next ->
          if (next != unit) {
            if (mode != "units") valueText = SarimBatchCalc.convertValue(valueText, unit, next)
            unit = next
          }
        },
      )
      SarimToggleGroup(
        options = listOf("total", "units", "ingredient"),
        selected = mode,
        labelOf = { when (it) { "total" -> "Weight"; "units" -> "Units"; else -> "Ingredient" } },
        onSelect = { mode = it },
      )
      if (mode == "ingredient" && ings.isNotEmpty()) {
        SarimPopoverSelector(
          label = "Ingredient",
          selectedText = ingPick?.name ?: "Select ingredient",
          options = ings.map { "${it.name} (${SarimBatchCalc.fmtAmt(SarimBatchCalc.toUnit(it.quantity, unit), unit)} $unit / unit)" },
          onSelect = { idx, _ -> ingredientId = ings[idx].id },
          testTag = "bm_ing_btn",
        )
      }
      SarimTextField(
        value = valueText,
        onValueChange = { valueText = it },
        label = when (mode) {
          "units" -> "Number of Units"
          "ingredient" -> "Ingredient on Hand ($unit)"
          else -> "Total Batch Weight ($unit)"
        },
        placeholder = "0",
        keyboardType = KeyboardType.Decimal,
        testTag = "bm_value",
      )
    }

    if (plan.units <= 0.0) {
      Text(if (formula == null) "Select a formula" else "Enter a value to build the batch", color = palette.textMuted, fontSize = 12.5.sp)
    } else {
      SarimCard {
        AmountRow("Units", SarimBatchCalc.fixed3(plan.units))
        AmountRow("Weight per Unit", "${SarimBatchCalc.fmtPrecise(SarimBatchCalc.toUnit(plan.unitWeight, unit), unit)} $unit")
        AmountRow("Total Batch Weight", "${fmt(plan.totalWeight)} $unit", palette.green)
      }
      Text("Required Ingredients", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
      SarimCard { plan.rows.forEach { AmountRow(it.name, "${fmt(it.total)} $unit") } }

      SarimCard {
        SarimTextField(value = saveName, onValueChange = { saveName = it }, label = "Name (optional)", placeholder = "e.g. Morning batch", maxLength = 60, testTag = "bm_save_name")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (editingId != null) SarimSecondaryButton(text = "Cancel Edit", modifier = Modifier.weight(1f), onClick = { exitEdit() })
          SarimPrimaryButton(
            text = "${if (editingId != null) "Update" else "Save"} Calculation",
            modifier = Modifier.weight(1f),
            onClick = {
              val f = formula ?: return@SarimPrimaryButton
              if (editingId == null && saved.size >= SarimBatchCalc.MAX_SAVED_CALCS) {
                viewModel.showDialog(AppDialogRequest("Warning", "You can keep up to ${SarimBatchCalc.MAX_SAVED_CALCS} saved calculations per formula. Delete an old one first.", tone = DialogTone.WARNING, cancelLabel = null))
                return@SarimPrimaryButton
              }
              val now = System.currentTimeMillis()
              val old = saved.firstOrNull { it.id == editingId }
              val entity = SarimSavedCalcs.buildBatch(
                id = editingId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_MIX),
                name = saveName.trim().ifBlank { SarimBatchCalc.defaultCalcName(f.name, false, now) },
                formulaId = f.id, formulaName = f.name, unit = unit, mode = mode,
                valueText = valueText, ingredientId = ingPick?.id ?: "", plan = plan,
                now = now, createdAt = old?.createdAt ?: now,
              )
              val wasEditing = editingId != null
              viewModel.launchSafely(
                errorTitle = "Save Calculation Failed",
                successMessage = if (wasEditing) "Calculation updated" else "Calculation saved",
                onSuccess = { exitEdit() },
              ) { viewModel.repository.saveMixCalc(entity) }
            },
          )
        }
      }
    }

    if (saved.isNotEmpty()) {
      Text("Saved Calculations (${saved.size})", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
      saved.forEach { calc ->
        SavedCalcCard(
          calc = calc,
          onEdit = { load(calc) },
          onDelete = {
            viewModel.showDialog(
              AppDialogRequest(
                title = "Delete Calculation", body = "Delete this saved calculation?",
                tone = DialogTone.PERMANENT, confirmLabel = "Delete",
                onConfirm = {
                  viewModel.launchSafely(errorTitle = "Delete Failed", successMessage = "Calculation deleted") {
                    viewModel.repository.deleteMixCalc(calc.id)
                  }
                  if (editingId == calc.id) exitEdit()
                },
              )
            )
          },
        )
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
fun BatchCorrectionScreen(
  savedId: String?,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val formulas = remember(state.formulas) { state.formulas.filter { !it.isDeleted } }
  val formulasMap = remember(formulas) { formulas.associateBy { it.id } }
  val materialsMap = remember(state.rawMaterials) { state.rawMaterials.filter { !it.isDeleted }.associateBy { it.id } }

  var formulaId by remember { mutableStateOf(formulas.firstOrNull()?.id ?: "") }
  var unit by remember { mutableStateOf("g") }
  var originalText by remember { mutableStateOf("") }
  var usedText by remember { mutableStateOf("") }
  val actualText = remember { mutableStateMapOf<String, String>() }
  var showActual by remember { mutableStateOf(false) }
  // Web `manual`: either a typed Total to Add, or a typed Add on one ingredient. Everything else follows the ratio.
  var manualTotal by remember { mutableStateOf<String?>(null) }
  var manualId by remember { mutableStateOf<String?>(null) }
  var manualAdd by remember { mutableStateOf("") }
  var saveName by remember { mutableStateOf("") }
  var editingId by remember { mutableStateOf<String?>(null) }

  fun resetManual() { manualTotal = null; manualId = null; manualAdd = "" }

  fun load(c: SavedMixCalcEntity) {
    val rec = SarimSavedCalcs.toWebRecord(c)
    formulaId = c.formulaId
    unit = if (c.unit == "kg") "kg" else "g"
    originalText = SarimSavedCalcs.text(rec, "original").ifBlank { c.inputValue.toString() }
    usedText = SarimSavedCalcs.text(rec, "used")
    actualText.clear(); actualText.putAll(SarimSavedCalcs.actualOf(rec))
    showActual = actualText.isNotEmpty()
    resetManual()
    if (rec.containsKey("manualTotal")) manualTotal = SarimSavedCalcs.text(rec, "manualTotal")
    else (rec["manual"] as? Map<*, *>)?.let { manualId = it["i"]?.toString(); manualAdd = it["v"]?.toString().orEmpty() }
    saveName = c.name
    editingId = c.id
  }

  LaunchedEffect(savedId) {
    state.mixCalcs.firstOrNull { it.id == savedId && !it.isDeleted }?.let { load(it) }
  }

  val formula = formulasMap[formulaId]
  val ings = remember(formula, formulasMap, materialsMap) { ingredientsOf(formula, formulasMap, materialsMap) }
  val manual = when {
    manualTotal != null -> SarimBatchCalc.Manual(total = manualTotal!!.trim().toDoubleOrNull())
    manualId != null -> SarimBatchCalc.Manual(ingredientId = manualId, add = manualAdd.trim().toDoubleOrNull())
    else -> null
  }
  val actual = ings.associate { it.id to parse(actualText[it.id].orEmpty()) }
  val plan = SarimBatchCalc.planCorrection(ings, parse(originalText), parse(usedText), actual, manual)
  val saved = state.mixCalcs.filter { !it.isDeleted && it.formulaId == formulaId && it.kind == SarimSavedCalcs.KIND_FIX }
    .sortedByDescending { it.createdAt }
  val f = { v: Double -> SarimBatchCalc.fmtAmt(v, unit) }

  fun exitEdit() { editingId = null; saveName = "" }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (formulas.isEmpty()) {
      SarimEmptyState("Add a formula in the Formula Store first", "Corrections need at least one formula.")
      return@Column
    }
    SarimCard {
      SarimPopoverSelector(
        label = "Formula",
        selectedText = formula?.name ?: "Select formula",
        options = formulas.map { it.name.ifBlank { "Untitled" } },
        onSelect = { idx, _ -> formulaId = formulas[idx].id; resetManual(); actualText.clear(); exitEdit() },
        testTag = "bf_formula_btn",
      )
      SarimToggleGroup(
        options = listOf("g", "kg"), selected = unit, labelOf = { it },
        onSelect = { next ->
          if (next != unit) {
            originalText = SarimBatchCalc.convertValue(originalText, unit, next)
            usedText = SarimBatchCalc.convertValue(usedText, unit, next)
            actualText.keys.toList().forEach { actualText[it] = SarimBatchCalc.convertValue(actualText[it].orEmpty(), unit, next) }
            manualTotal = manualTotal?.let { SarimBatchCalc.convertValue(it, unit, next) }
            if (manualId != null) manualAdd = SarimBatchCalc.convertValue(manualAdd, unit, next)
            unit = next
          }
        },
      )
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SarimTextField(
          value = originalText, onValueChange = { originalText = it; resetManual() },
          label = "Original Batch ($unit)", placeholder = "0", keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f), testTag = "bf_original",
        )
        SarimTextField(
          value = usedText, onValueChange = { usedText = it; resetManual() },
          label = "Used / Lost ($unit)", placeholder = "0", keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f), testTag = "bf_used",
        )
      }
      val orig = parse(originalText)
      AmountRow("Remaining Batch", "${f(if (orig > 0) maxOf(0.0, orig - parse(usedText)) else 0.0)} $unit", palette.red)
      TextButton(onClick = { showActual = !showActual }) {
        Text("Mixed differently than the formula?", color = palette.cyan, fontSize = 13.sp)
      }
      if (showActual) {
        ings.forEach { ing ->
          SarimTextField(
            value = actualText[ing.id].orEmpty(),
            onValueChange = { actualText[ing.id] = it; resetManual() },
            label = "${ing.name} ($unit)", placeholder = "Formula default", keyboardType = KeyboardType.Decimal,
          )
        }
      }
    }

    if (!plan.ok) {
      Text(
        when (plan.error) {
          "nothing-left" -> "Used / Lost must be less than the Original Batch"
          else -> if (formula == null) "Select a formula" else "Enter the original batch weight"
        },
        color = palette.textMuted, fontSize = 12.5.sp,
      )
    } else {
      SarimCard(borderColor = palette.amber.copy(alpha = 0.45f)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Required Additions", color = palette.amber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Text("New total ${f(plan.requiredTotal)} $unit", color = palette.red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(
          "Type a new amount in any Add box or in Total to Add – all other ingredients update automatically to keep the formula ratio.",
          color = palette.textMuted, fontSize = 11.5.sp,
        )
        HorizontalDivider(color = palette.border)
        plan.rows.forEach { r ->
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.name, color = palette.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("In remaining ${f(r.present)} $unit → Required ${f(r.target)} $unit", color = palette.textMuted, fontSize = 11.sp)
            SarimTextField(
              value = if (manualId == r.id && manualTotal == null) manualAdd else f(r.add),
              onValueChange = { v ->
                if (v.isBlank()) resetManual() else { manualTotal = null; manualId = r.id; manualAdd = v }
              },
              label = "Add ($unit)", keyboardType = KeyboardType.Decimal,
            )
          }
        }
        HorizontalDivider(color = palette.border)
        AmountRow("Total in Remaining", "${f(plan.remaining)} $unit")
        AmountRow("Total Required", "${f(plan.requiredTotal)} $unit")
        SarimTextField(
          value = manualTotal ?: f(plan.addTotal),
          onValueChange = { v ->
            if (v.isBlank()) resetManual() else { manualId = null; manualAdd = ""; manualTotal = v }
          },
          label = "Total to Add ($unit)", keyboardType = KeyboardType.Decimal, testTag = "bfTotalAdd",
        )
      }

      SarimCard {
        SarimTextField(value = saveName, onValueChange = { saveName = it }, label = "Name (optional)", placeholder = "e.g. Morning batch", maxLength = 60, testTag = "bf_save_name")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (editingId != null) SarimSecondaryButton(text = "Cancel Edit", modifier = Modifier.weight(1f), onClick = { exitEdit() })
          SarimPrimaryButton(
            text = "${if (editingId != null) "Update" else "Save"} Correction",
            modifier = Modifier.weight(1f),
            onClick = {
              val fm = formula ?: return@SarimPrimaryButton
              if (editingId == null && saved.size >= SarimBatchCalc.MAX_SAVED_CALCS) {
                viewModel.showDialog(AppDialogRequest("Warning", "You can keep up to ${SarimBatchCalc.MAX_SAVED_CALCS} saved calculations per formula. Delete an old one first.", tone = DialogTone.WARNING, cancelLabel = null))
                return@SarimPrimaryButton
              }
              val now = System.currentTimeMillis()
              val old = saved.firstOrNull { it.id == editingId }
              val entity = SarimSavedCalcs.buildFix(
                id = editingId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_MIX),
                name = saveName.trim().ifBlank { SarimBatchCalc.defaultCalcName(fm.name, true, now) },
                formulaId = fm.id, formulaName = fm.name, unit = unit,
                originalText = originalText, usedText = usedText, actual = actualText.toMap(),
                manualTotalText = manualTotal, manualIngredientId = manualId, manualAddText = manualAdd.takeIf { manualId != null },
                plan = plan, now = now, createdAt = old?.createdAt ?: now,
              )
              val wasEditing = editingId != null
              viewModel.launchSafely(
                errorTitle = "Save Correction Failed",
                successMessage = if (wasEditing) "Correction updated" else "Correction saved",
                onSuccess = { exitEdit() },
              ) { viewModel.repository.saveMixCalc(entity) }
            },
          )
        }
      }
    }

    if (saved.isNotEmpty()) {
      Text("Saved Corrections (${saved.size})", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
      saved.forEach { calc ->
        SavedCalcCard(
          calc = calc,
          onEdit = { load(calc) },
          onDelete = {
            viewModel.showDialog(
              AppDialogRequest(
                title = "Delete Correction", body = "Delete this saved correction?",
                tone = DialogTone.PERMANENT, confirmLabel = "Delete",
                onConfirm = {
                  viewModel.launchSafely(errorTitle = "Delete Failed", successMessage = "Correction deleted") {
                    viewModel.repository.deleteMixCalc(calc.id)
                  }
                  if (editingId == calc.id) exitEdit()
                },
              )
            )
          },
        )
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}
