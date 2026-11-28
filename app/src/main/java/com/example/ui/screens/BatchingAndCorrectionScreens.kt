package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.FormulaEntity
import com.example.data.local.SavedMixCalcEntity
import com.example.data.repository.CorrectionManual
import com.example.data.repository.FormulaCalculator
import com.example.data.repository.MixIngredient
import com.example.data.repository.SarimFormulaEngine
import com.example.data.repository.SarimMixCalcRecords
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

private const val LIMIT_MESSAGE = "You can keep up to ${SarimFormulaEngine.MAX_SAVED_CALCS} saved calculations per formula. Delete an old one first."

private fun fmtAmt(v: Double, unit: String): String = SarimFormat.formatQty(v, if (unit == "g") 1 else 3)

private fun modeLabel(mode: String) = when (mode) {
  SarimFormulaEngine.MODE_UNITS -> "Units"
  SarimFormulaEngine.MODE_INGREDIENT -> "Ingredient"
  else -> "Weight"
}

/** Flattened (links expanded) mix ingredients for a formula, exactly like web `resolveAll` + `formulaIngredients`. */
private fun mixIngredientsOf(
  formula: FormulaEntity?,
  engFormulas: Map<String, com.example.data.repository.EngineFormula>,
  engMaterials: Map<String, com.example.data.repository.EngineMaterial>,
): List<MixIngredient> {
  if (formula == null) return emptyList()
  val flat = SarimFormulaEngine.flatten(FormulaCalculator.toEngine(formula), engFormulas, engMaterials)
  return SarimFormulaEngine.mixIngredients(flat.items)
}

private fun calcRows(calc: SavedMixCalcEntity): List<Triple<String, Double, Boolean>> {
  val isFix = calc.kind == SarimMixCalcRecords.KIND_FIX
  val raw = SarimMixCalcRecords.details(calc)["rows"] as? List<*> ?: return emptyList()
  return raw.mapNotNull { r ->
    val m = r as? Map<*, *> ?: return@mapNotNull null
    Triple(m["n"]?.toString().orEmpty(), SarimMixCalcRecords.num(if (isFix) m["a"] else m["t"]), isFix)
  }
}

@Composable
private fun SavedCalcCard(
  title: String,
  emptyText: String,
  deleteTitle: String,
  deleteBody: String,
  deletedMessage: String,
  calcs: List<SavedMixCalcEntity>,
  viewModel: SarimViewModel,
  onOpen: (SavedMixCalcEntity) -> Unit,
  onDeleted: (String) -> Unit,
) {
  val palette = LocalSarimPalette.current
  SarimCard {
    Text("$title (${calcs.size})", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    if (calcs.isEmpty()) {
      Text(emptyText, color = palette.textMuted, fontSize = 12.5.sp)
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        calcs.forEach { calc ->
          val rows = calcRows(calc)
          val isFix = calc.kind == SarimMixCalcRecords.KIND_FIX
          SarimHistoryCard(
            title = calc.name,
            subtitle = listOf(SarimFormat.formatDate(calc.updatedAt), calc.summaryText).filter { it.isNotBlank() }.joinToString(" · "),
            tagText = if (isFix) "Correction" else "Batch",
            tone = if (isFix) HistoryTone.WARN else HistoryTone.INFO,
          ) {
            rows.forEach { (n, v, fix) ->
              HistoryKeyValueRow(n, "${if (fix && v > 0.0) "+" else ""}${fmtAmt(v, calc.unit)} ${calc.unit}")
            }
            Row(
              modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              HistorySheetButton(
                text = "Delete",
                kind = SheetButtonKind.DANGER,
                modifier = Modifier.weight(1f),
                onClick = {
                  viewModel.showDialog(
                    AppDialogRequest(
                      title = deleteTitle,
                      body = "$deleteBody (${calc.name})?",
                      tone = DialogTone.PERMANENT,
                      confirmLabel = "Delete",
                      onConfirm = {
                        viewModel.launchSafely(errorTitle = "Delete Failed", successMessage = deletedMessage) {
                          viewModel.repository.deleteMixCalc(calc.id)
                          onDeleted(calc.id)
                        }
                      },
                    )
                  )
                },
              )
              HistorySheetButton(
                text = "Edit",
                kind = SheetButtonKind.PRIMARY,
                modifier = Modifier.weight(3f),
                onClick = { onOpen(calc) },
              )
            }
          }
        }
      }
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
  val engFormulas = remember(formulasMap) { FormulaCalculator.engineFormulas(formulasMap) }
  val engMaterials = remember(materialsMap) { FormulaCalculator.engineMaterials(materialsMap) }
  val allCalcs = remember(state.mixCalcs) { state.mixCalcs.filter { !it.isDeleted } }

  var editingCalcId by remember(savedId) { mutableStateOf(savedId) }
  var loadedFor by remember(savedId) { mutableStateOf<String?>(null) }
  var selectedFormulaIndex by remember { mutableIntStateOf(0) }
  var unit by remember { mutableStateOf("kg") }
  var mode by remember { mutableStateOf(SarimFormulaEngine.MODE_TOTAL) }
  var selectedIngId by remember { mutableStateOf("") }
  var valueText by remember { mutableStateOf("") }
  var saveName by remember { mutableStateOf("") }

  val scrollState = rememberScrollState()
  val scope = rememberCoroutineScope()

  fun openSaved(calc: SavedMixCalcEntity) {
    val d = SarimMixCalcRecords.details(calc)
    editingCalcId = calc.id
    // Web scrolls the form back into view when a saved calculation is opened for editing.
    scope.launch { scrollState.animateScrollTo(0) }
    val fIdx = formulas.indexOfFirst { it.id == calc.formulaId }
    if (fIdx >= 0) selectedFormulaIndex = fIdx + 1
    unit = if (calc.unit == "g") "g" else "kg"
    mode = SarimFormulaEngine.normalizeMode(d["mode"]?.toString() ?: calc.mode)
    selectedIngId = d["ingredientId"]?.toString().orEmpty()
    valueText = SarimMixCalcRecords.text(d["value"] ?: calc.inputValue)
    saveName = calc.name
  }

  LaunchedEffect(savedId, allCalcs, formulas) {
    if (savedId != null && loadedFor != savedId) {
      allCalcs.firstOrNull { it.id == savedId && it.kind == SarimMixCalcRecords.KIND_BATCH }?.let {
        openSaved(it)
        loadedFor = savedId
      }
    }
  }

  val formulaOptions = remember(formulas) { listOf("Select formula") + formulas.map { it.name } }
  val selectedFormula = formulas.getOrNull(selectedFormulaIndex - 1)
  val ings = remember(selectedFormula, engFormulas, engMaterials) { mixIngredientsOf(selectedFormula, engFormulas, engMaterials) }
  val mult = if (unit == "g") 1000.0 else 1.0
  val rawValue = SarimFormat.parseSafeDouble(valueText)
  val valueForPlan = rawValue?.let { if (mode == SarimFormulaEngine.MODE_UNITS) it else it / mult }
  val plan = remember(ings, mode, valueForPlan, selectedIngId) { SarimFormulaEngine.planBatch(ings, mode, valueForPlan, selectedIngId) }
  val savedList = remember(allCalcs, selectedFormula) {
    allCalcs.filter { it.kind == SarimMixCalcRecords.KIND_BATCH && (selectedFormula == null || it.formulaId == selectedFormula.id) }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(scrollState)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SarimCard {
      SarimPopoverSelector(
        label = "Formula",
        selectedText = formulaOptions.getOrElse(selectedFormulaIndex) { "Select formula" },
        options = formulaOptions,
        onSelect = { idx, _ -> selectedFormulaIndex = idx; selectedIngId = "" },
        testTag = "bm_formula_btn",
      )
      if (selectedFormula != null) {
        Text(
          "1 Unit = ${SarimFormat.formatKg(plan.unitWeight)} across ${ings.size} base ingredient(s)",
          color = palette.cyan,
          fontSize = 12.sp,
          modifier = Modifier.testTag("bmFormulaInfo"),
        )
      }

      SarimFieldLabel("Unit")
      SarimToggleGroup(
        options = listOf("g", "kg"),
        selected = unit,
        labelOf = { it },
        onSelect = { next ->
          if (next != unit) {
            if (mode != SarimFormulaEngine.MODE_UNITS) valueText = SarimMixCalcRecords.convertText(valueText, unit, next)
            unit = next
          }
        },
      )

      SarimFieldLabel("Mode")
      SarimToggleGroup(
        options = listOf(SarimFormulaEngine.MODE_TOTAL, SarimFormulaEngine.MODE_UNITS, SarimFormulaEngine.MODE_INGREDIENT),
        selected = mode,
        labelOf = { modeLabel(it) },
        onSelect = { mode = it },
      )

      if (mode == SarimFormulaEngine.MODE_INGREDIENT && ings.isNotEmpty()) {
        val picked = ings.firstOrNull { it.id == selectedIngId } ?: ings.first()
        SarimPopoverSelector(
          label = "Ingredient",
          selectedText = picked.name,
          options = ings.map { it.name },
          onSelect = { idx, _ -> selectedIngId = ings.getOrNull(idx)?.id.orEmpty() },
          testTag = "bm_ing_btn",
        )
      }

      val valLabel = when (mode) {
        SarimFormulaEngine.MODE_UNITS -> "Number of Units"
        SarimFormulaEngine.MODE_INGREDIENT -> "Ingredient on Hand ($unit)"
        else -> "Total Batch Weight ($unit)"
      }
      SarimTextField(
        value = valueText,
        onValueChange = { valueText = it },
        label = valLabel,
        placeholder = "0",
        keyboardType = KeyboardType.Decimal,
        testTag = "bm_value",
      )
    }

    if (selectedFormula == null) {
      SarimEmptyState("Select a formula", "Choose a formula above to calculate exact ingredient weights.")
    } else if (plan.units <= 0.0) {
      SarimEmptyState("Enter a value", "Enter a value to build the batch.")
    } else {
      SarimCard(borderColor = palette.green.copy(alpha = 0.4f)) {
        Text("Weigh Amounts", color = palette.green, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        BatchLine("Units", String.format(java.util.Locale.US, "%.3f", plan.units), palette.textPrimary)
        BatchLine("Weight per Unit", "${fmtAmt(plan.unitWeight * mult, unit)} $unit", palette.textPrimary)
        BatchLine("Total Batch Weight", "${fmtAmt(plan.totalWeight * mult, unit)} $unit", palette.green, bold = true)
        HorizontalDivider(color = palette.border)
        plan.rows.forEach { r ->
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
              Text(r.name, color = palette.textPrimary, fontSize = 13.5.sp)
              Text("${fmtAmt(r.perUnit * mult, unit)} $unit per unit", color = palette.textMuted, fontSize = 11.sp)
            }
            Text(
              "${fmtAmt(r.total * mult, unit)} $unit",
              color = palette.green,
              style = TabularNumberStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
            )
          }
        }
      }
    }

    SarimCard {
      SarimTextField(
        value = saveName,
        onValueChange = { saveName = it },
        label = "Name (optional)",
        placeholder = "e.g. Morning batch",
        maxLength = 60,
        testTag = "bm_save_name",
      )
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editingCalcId != null) {
          SarimSecondaryButton(
            text = "Cancel Edit",
            modifier = Modifier.weight(1f),
            onClick = { editingCalcId = null; valueText = ""; saveName = "" },
          )
        }
        SarimPrimaryButton(
          text = if (editingCalcId != null) "Update Calculation" else "Save Calculation",
          modifier = Modifier.weight(1f),
          onClick = {
            if (selectedFormula == null || plan.units <= 0.0) {
              viewModel.showDialog(AppDialogRequest("Incomplete Calculation", "Enter a value to build the batch first.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val editing = editingCalcId?.let { id -> allCalcs.firstOrNull { it.id == id } }
            if (editing == null && allCalcs.count { it.formulaId == selectedFormula.id } >= SarimFormulaEngine.MAX_SAVED_CALCS) {
              viewModel.showDialog(AppDialogRequest("Limit Reached", LIMIT_MESSAGE, tone = DialogTone.WARNING, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val now = System.currentTimeMillis()
            val title = saveName.trim().ifBlank { SarimFormulaEngine.defaultCalcName(selectedFormula.name, false, SarimFormat.dateInput(now)) }
            val details = linkedMapOf<String, Any?>(
              "mode" to mode,
              "value" to valueText.trim(),
              "ingredientId" to selectedIngId,
              "units" to SarimMixCalcRecords.round6(plan.units),
              "unitWeight" to SarimMixCalcRecords.round6(plan.unitWeight * mult),
              "totalWeight" to SarimMixCalcRecords.round6(plan.totalWeight * mult),
              "rows" to plan.rows.map {
                linkedMapOf<String, Any?>(
                  "n" to it.name,
                  "p" to SarimMixCalcRecords.round6(it.perUnit * mult),
                  "t" to SarimMixCalcRecords.round6(it.total * mult),
                )
              },
            )
            val totalInUnit = SarimMixCalcRecords.round6(plan.totalWeight * mult)
            viewModel.launchSafely(
              errorTitle = "Save Calculation Failed",
              successMessage = if (editing != null) "Calculation updated" else "Calculation saved",
              onSuccess = {
                editingCalcId = null
                saveName = ""
              },
            ) {
              viewModel.repository.saveMixCalc(
                SavedMixCalcEntity(
                  id = editing?.id ?: com.example.data.model.SarimUuid.generateUUID(com.example.data.model.SarimUuid.PREFIX_MIX),
                  kind = SarimMixCalcRecords.KIND_BATCH,
                  name = title,
                  formulaId = selectedFormula.id,
                  formulaName = selectedFormula.name,
                  unit = unit,
                  mode = mode,
                  inputValue = totalInUnit,
                  summaryText = SarimMixCalcRecords.summaryOf(false, unit, totalInUnit, 0.0, plan.units) { fmtAmt(it, unit) },
                  detailsJson = SarimMixCalcRecords.detailsJsonOf(details),
                  createdAt = editing?.createdAt ?: now,
                )
              )
            }
          },
        )
      }
    }

    SavedCalcCard(
      title = "Saved Calculations",
      emptyText = "No saved calculations yet.",
      deleteTitle = "Delete Calculation",
      deleteBody = "Delete this saved calculation",
      deletedMessage = "Calculation deleted",
      calcs = savedList,
      viewModel = viewModel,
      onOpen = { openSaved(it) },
      onDeleted = { id -> if (editingCalcId == id) { editingCalcId = null; saveName = "" } },
    )
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun BatchLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color, bold: Boolean = false) {
  val palette = LocalSarimPalette.current
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = palette.textMuted, fontSize = 12.5.sp)
    Text(value, color = valueColor, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold))
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
  val engFormulas = remember(formulasMap) { FormulaCalculator.engineFormulas(formulasMap) }
  val engMaterials = remember(materialsMap) { FormulaCalculator.engineMaterials(materialsMap) }
  val allCalcs = remember(state.mixCalcs) { state.mixCalcs.filter { !it.isDeleted } }

  var editingCalcId by remember(savedId) { mutableStateOf(savedId) }
  var loadedFor by remember(savedId) { mutableStateOf<String?>(null) }
  var selectedFormulaIndex by remember { mutableIntStateOf(0) }
  var unit by remember { mutableStateOf("g") }
  var originalText by remember { mutableStateOf("") }
  var usedText by remember { mutableStateOf("") }
  var showActual by remember { mutableStateOf(false) }
  val actualAmounts = remember { mutableStateMapOf<String, String>() }
  // Manual override: "" none, "total" = typed Total to Add, "add" = typed Add for one ingredient.
  var manualMode by remember { mutableStateOf("") }
  var manualId by remember { mutableStateOf("") }
  var manualText by remember { mutableStateOf("") }
  var saveName by remember { mutableStateOf("") }

  fun resetManual() { manualMode = ""; manualId = ""; manualText = "" }

  val scrollState = rememberScrollState()
  val scope = rememberCoroutineScope()

  fun openSaved(calc: SavedMixCalcEntity) {
    val d = SarimMixCalcRecords.details(calc)
    editingCalcId = calc.id
    // Web scrolls the form back into view when a saved calculation is opened for editing.
    scope.launch { scrollState.animateScrollTo(0) }
    val fIdx = formulas.indexOfFirst { it.id == calc.formulaId }
    if (fIdx >= 0) selectedFormulaIndex = fIdx + 1
    unit = if (calc.unit == "kg") "kg" else "g"
    if (d["original"] != null) {
      originalText = SarimMixCalcRecords.text(d["original"])
      usedText = SarimMixCalcRecords.text(d["used"])
    } else {
      // Older Android saves only kept the remaining batch: treat it as the original with nothing used.
      originalText = SarimMixCalcRecords.text(calc.inputValue)
      usedText = ""
    }
    actualAmounts.clear()
    (d["actual"] as? List<*>).orEmpty().forEach { a ->
      val m = a as? Map<*, *> ?: return@forEach
      val key = m["i"]?.toString().orEmpty()
      if (key.isNotBlank()) actualAmounts[key] = SarimMixCalcRecords.text(m["v"])
    }
    showActual = actualAmounts.isNotEmpty()
    resetManual()
    if (d["manualTotal"] != null) {
      manualMode = "total"; manualText = SarimMixCalcRecords.text(d["manualTotal"])
    } else {
      val mm = d["manual"] as? Map<*, *>
      if (mm != null) {
        manualMode = "add"; manualId = mm["i"]?.toString().orEmpty(); manualText = SarimMixCalcRecords.text(mm["v"])
      }
    }
    saveName = calc.name
  }

  LaunchedEffect(savedId, allCalcs, formulas) {
    if (savedId != null && loadedFor != savedId) {
      allCalcs.firstOrNull { it.id == savedId && it.kind == SarimMixCalcRecords.KIND_FIX }?.let {
        openSaved(it)
        loadedFor = savedId
      }
    }
  }

  val formulaOptions = remember(formulas) { listOf("Select formula") + formulas.map { it.name } }
  val selectedFormula = formulas.getOrNull(selectedFormulaIndex - 1)
  val ings = remember(selectedFormula, engFormulas, engMaterials) { mixIngredientsOf(selectedFormula, engFormulas, engMaterials) }

  val originalVal = SarimFormat.parseSafeDouble(originalText)
  val usedVal = SarimFormat.parseSafeDouble(usedText) ?: 0.0
  val actualParsed = ings.mapNotNull { i ->
    SarimFormat.parseSafeDouble(actualAmounts[i.id].orEmpty())?.let { i.id to it }
  }.toMap()
  val manualValue = SarimFormat.parseSafeDouble(manualText)
  val manual: CorrectionManual? = when {
    manualValue == null -> null
    manualMode == "total" -> CorrectionManual.Total(manualValue)
    manualMode == "add" -> CorrectionManual.Add(manualId, manualValue)
    else -> null
  }
  val plan = SarimFormulaEngine.planCorrection(ings, originalVal, usedVal, actualParsed, manual)
  val remainingShown = if ((originalVal ?: 0.0) > 0.0) maxOf(0.0, (originalVal ?: 0.0) - usedVal) else 0.0
  val savedList = remember(allCalcs, selectedFormula) {
    allCalcs.filter { it.kind == SarimMixCalcRecords.KIND_FIX && (selectedFormula == null || it.formulaId == selectedFormula.id) }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(scrollState)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SarimCard {
      SarimPopoverSelector(
        label = "Formula",
        selectedText = formulaOptions.getOrElse(selectedFormulaIndex) { "Select formula" },
        options = formulaOptions,
        onSelect = { idx, _ -> selectedFormulaIndex = idx; actualAmounts.clear(); resetManual() },
        testTag = "bf_formula_btn",
      )

      SarimFieldLabel("Unit")
      SarimToggleGroup(
        options = listOf("g", "kg"),
        selected = unit,
        labelOf = { it },
        onSelect = { next ->
          if (next != unit) {
            originalText = SarimMixCalcRecords.convertText(originalText, unit, next)
            usedText = SarimMixCalcRecords.convertText(usedText, unit, next)
            actualAmounts.keys.toList().forEach { k -> actualAmounts[k] = SarimMixCalcRecords.convertText(actualAmounts[k].orEmpty(), unit, next) }
            if (manualMode.isNotEmpty()) manualText = SarimMixCalcRecords.convertText(manualText, unit, next)
            unit = next
          }
        },
      )

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SarimTextField(
          value = originalText,
          onValueChange = { originalText = it; resetManual() },
          label = "Original Batch ($unit)",
          placeholder = "0",
          keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f),
          testTag = "bf_original",
        )
        SarimTextField(
          value = usedText,
          onValueChange = { usedText = it; resetManual() },
          label = "Used / Lost ($unit)",
          placeholder = "0",
          keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f),
          testTag = "bf_used",
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Remaining Batch", color = palette.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
          "${fmtAmt(remainingShown, unit)} $unit",
          color = palette.red,
          style = TabularNumberStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
          modifier = Modifier.testTag("bfRemaining"),
        )
      }

      if (selectedFormula != null) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { showActual = !showActual }
            .padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Mixed differently than the formula?", color = palette.cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          Icon(if (showActual) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = palette.cyan)
        }
        AnimatedVisibility(visible = showActual) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ings.forEach { ing ->
              SarimTextField(
                value = actualAmounts[ing.id] ?: "",
                onValueChange = { actualAmounts[ing.id] = it; resetManual() },
                label = "${ing.name} ($unit)",
                placeholder = "Formula default",
                keyboardType = KeyboardType.Decimal,
              )
            }
          }
        }
      }
    }

    if (selectedFormula == null) {
      SarimEmptyState("Select a formula", "Choose a formula above to compute batch additions.")
    } else if (!plan.ok) {
      SarimEmptyState(
        if (plan.error == "nothing-left") "Nothing left" else "Enter the original batch",
        if (plan.error == "nothing-left") "Used / Lost must be less than the Original Batch." else "Enter the original batch weight.",
      )
    } else {
      SarimCard(borderColor = palette.amber.copy(alpha = 0.45f)) {
        Text("Required Additions", color = palette.amber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
          "Type a new amount in any Add box or in Total to Add – all other ingredients update automatically.",
          color = palette.textMuted,
          fontSize = 11.5.sp,
        )
        HorizontalDivider(color = palette.border)

        plan.rows.forEach { r ->
          val typingHere = manualMode == "add" && manualId == r.id
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Column(modifier = Modifier.weight(1.2f)) {
              Text(r.name, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
              Text(
                "In remaining ${fmtAmt(r.present, unit)} → Required ${fmtAmt(r.present + r.add, unit)} $unit",
                color = palette.textMuted,
                fontSize = 11.sp,
              )
            }
            SarimTextField(
              value = if (typingHere) manualText else fmtAmt(r.add, unit),
              onValueChange = { v ->
                if (v.isBlank()) resetManual() else { manualMode = "add"; manualId = r.id; manualText = v }
              },
              label = "Add ($unit)",
              placeholder = "0",
              keyboardType = KeyboardType.Decimal,
              modifier = Modifier.weight(0.9f),
              testTag = "bfAdd_${r.id}",
            )
          }
        }

        HorizontalDivider(color = palette.border)
        SarimTextField(
          value = if (manualMode == "total") manualText else fmtAmt(plan.addTotal, unit),
          onValueChange = { v ->
            if (v.isBlank()) resetManual() else { manualMode = "total"; manualId = ""; manualText = v }
          },
          label = "Total to Add ($unit)",
          keyboardType = KeyboardType.Decimal,
          testTag = "bfTotalAdd",
        )
        BatchLine("Remaining", "${fmtAmt(plan.remaining, unit)} $unit", palette.textPrimary)
        BatchLine("New total batch", "${fmtAmt(plan.requiredTotal, unit)} $unit", palette.green, bold = true)
      }
    }

    SarimCard {
      SarimTextField(
        value = saveName,
        onValueChange = { saveName = it },
        label = "Name (optional)",
        placeholder = "e.g. Batch #4 recovery",
        maxLength = 60,
        testTag = "bf_save_name",
      )
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editingCalcId != null) {
          SarimSecondaryButton(
            text = "Cancel Edit",
            modifier = Modifier.weight(1f),
            onClick = { editingCalcId = null; originalText = ""; usedText = ""; saveName = ""; actualAmounts.clear(); resetManual() },
          )
        }
        SarimPrimaryButton(
          text = if (editingCalcId != null) "Update Correction" else "Save Correction",
          modifier = Modifier.weight(1f),
          onClick = {
            if (selectedFormula == null || !plan.ok) {
              viewModel.showDialog(AppDialogRequest("Incomplete Correction", "Enter the original batch weight first.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val editing = editingCalcId?.let { id -> allCalcs.firstOrNull { it.id == id } }
            if (editing == null && allCalcs.count { it.formulaId == selectedFormula.id } >= SarimFormulaEngine.MAX_SAVED_CALCS) {
              viewModel.showDialog(AppDialogRequest("Limit Reached", LIMIT_MESSAGE, tone = DialogTone.WARNING, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val now = System.currentTimeMillis()
            val title = saveName.trim().ifBlank { SarimFormulaEngine.defaultCalcName(selectedFormula.name, true, SarimFormat.dateInput(now)) }
            val details = linkedMapOf<String, Any?>(
              "original" to originalText.trim(),
              "used" to usedText.trim(),
              "actual" to actualAmounts.filter { it.value.isNotBlank() }.map { linkedMapOf<String, Any?>("i" to it.key, "v" to it.value) },
              "remaining" to SarimMixCalcRecords.round6(plan.remaining),
              "requiredTotal" to SarimMixCalcRecords.round6(plan.requiredTotal),
              "addTotal" to SarimMixCalcRecords.round6(plan.addTotal),
              "rows" to plan.rows.map {
                linkedMapOf<String, Any?>(
                  "n" to it.name,
                  "p" to SarimMixCalcRecords.round6(it.present),
                  "a" to SarimMixCalcRecords.round6(it.add),
                  "t" to SarimMixCalcRecords.round6(it.target),
                )
              },
            )
            if (manual is CorrectionManual.Total) details["manualTotal"] = manualText.trim()
            else if (manual is CorrectionManual.Add) details["manual"] = linkedMapOf<String, Any?>("i" to manualId, "v" to manualText.trim())
            viewModel.launchSafely(
              errorTitle = "Save Correction Failed",
              successMessage = if (editing != null) "Correction updated" else "Correction saved",
              onSuccess = {
                editingCalcId = null
                saveName = ""
              },
            ) {
              viewModel.repository.saveMixCalc(
                SavedMixCalcEntity(
                  id = editing?.id ?: com.example.data.model.SarimUuid.generateUUID(com.example.data.model.SarimUuid.PREFIX_MIX),
                  kind = SarimMixCalcRecords.KIND_FIX,
                  name = title,
                  formulaId = selectedFormula.id,
                  formulaName = selectedFormula.name,
                  unit = unit,
                  mode = "CORRECTION",
                  inputValue = SarimMixCalcRecords.round6(plan.remaining),
                  summaryText = SarimMixCalcRecords.summaryOf(true, unit, plan.requiredTotal, plan.addTotal, 0.0) { fmtAmt(it, unit) },
                  detailsJson = SarimMixCalcRecords.detailsJsonOf(details),
                  createdAt = editing?.createdAt ?: now,
                )
              )
            }
          },
        )
      }
    }

    SavedCalcCard(
      title = "Saved Corrections",
      emptyText = "No saved corrections yet.",
      deleteTitle = "Delete Correction",
      deleteBody = "Delete this saved correction",
      deletedMessage = "Correction deleted",
      calcs = savedList,
      viewModel = viewModel,
      onOpen = { openSaved(it) },
      onDeleted = { id -> if (editingCalcId == id) { editingCalcId = null; saveName = "" } },
    )
    Spacer(Modifier.height(24.dp))
  }
}
