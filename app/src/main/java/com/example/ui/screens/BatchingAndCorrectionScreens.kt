package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.FormulaEntity
import com.example.data.local.JsonCodecs
import com.example.data.local.SavedMixCalcEntity
import com.example.data.repository.FormulaCalculator
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import java.util.UUID

private data class FlatMixIngredient(
  val id: String,
  val name: String,
  val kgPerUnit: Double,
)

private fun flattenFormulaIngredients(
  formula: FormulaEntity,
  formulasMap: Map<String, FormulaEntity>,
  materialsMap: Map<String, com.example.data.local.RawMaterialEntity>,
): List<FlatMixIngredient> {
  val rawMap = FormulaCalculator.flattenRawMaterials(formula, 1.0, formulasMap, materialsMap)
  return rawMap.entries.map { (matId, kg) ->
    val mat = materialsMap[matId]
    FlatMixIngredient(matId, mat?.name ?: matId, kg)
  }.filter { it.kgPerUnit > 0.0 }
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
  val savedList = remember(state.mixCalcs) { state.mixCalcs.filter { !it.isDeleted && it.kind == "BATCHING" } }

  var editingCalcId by remember(savedId) { mutableStateOf(savedId) }
  var selectedFormulaIndex by remember { mutableIntStateOf(0) }
  var unit by remember { mutableStateOf("kg") } // g, kg
  var mode by remember { mutableStateOf("WEIGHT") } // WEIGHT, UNITS, INGREDIENT
  var selectedIngIndex by remember { mutableIntStateOf(0) }
  var valueText by remember { mutableStateOf("") }
  var saveName by remember { mutableStateOf("") }

  LaunchedEffect(savedId, savedList, formulas) {
    val found = savedId?.let { id -> savedList.firstOrNull { it.id == id } }
    if (found != null) {
      val fIdx = formulas.indexOfFirst { it.id == found.formulaId }
      if (fIdx >= 0) selectedFormulaIndex = fIdx + 1
      unit = found.unit
      mode = found.mode
      valueText = found.inputValue.toString()
      saveName = found.name
    }
  }

  val formulaOptions = remember(formulas) { listOf("Select formula") + formulas.map { it.name } }
  val selectedFormula = formulas.getOrNull(selectedFormulaIndex - 1)
  val flatIngs = remember(selectedFormula, formulasMap, materialsMap) {
    selectedFormula?.let { flattenFormulaIngredients(it, formulasMap, materialsMap) } ?: emptyList()
  }
  val totalUnitWeightKg = remember(flatIngs) { flatIngs.sumOf { it.kgPerUnit } }
  val multiplierToUnit = if (unit == "g") 1000.0 else 1.0
  val inputVal = SarimFormat.parseSafeDouble(valueText) ?: 0.0

  val batchScaleUnits = remember(mode, inputVal, flatIngs, selectedIngIndex, totalUnitWeightKg, unit) {
    when {
      selectedFormula == null || inputVal <= 0.0 || totalUnitWeightKg <= 0.0 -> 0.0
      mode == "UNITS" -> inputVal
      mode == "WEIGHT" -> (inputVal / multiplierToUnit) / totalUnitWeightKg
      mode == "INGREDIENT" -> {
        val ing = flatIngs.getOrNull(selectedIngIndex)
        if (ing != null && ing.kgPerUnit > 0.0) (inputVal / multiplierToUnit) / ing.kgPerUnit else 0.0
      }
      else -> 0.0
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SarimCard {
      SarimPopoverSelector(
        label = "Formula",
        selectedText = formulaOptions.getOrElse(selectedFormulaIndex) { "Select formula" },
        options = formulaOptions,
        onSelect = { idx, _ -> selectedFormulaIndex = idx; selectedIngIndex = 0 },
        testTag = "bm_formula_btn",
      )
      if (selectedFormula != null) {
        Text(
          "1 Unit = ${SarimFormat.formatKg(totalUnitWeightKg)} across ${flatIngs.size} base ingredient(s)",
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
        onSelect = { unit = it },
      )

      SarimFieldLabel("Mode")
      SarimToggleGroup(
        options = listOf("WEIGHT", "UNITS", "INGREDIENT"),
        selected = mode,
        labelOf = { it.lowercase().replaceFirstChar { c -> c.uppercase() } },
        onSelect = { mode = it },
      )

      if (mode == "INGREDIENT" && flatIngs.isNotEmpty()) {
        SarimPopoverSelector(
          label = "Ingredient",
          selectedText = flatIngs.getOrNull(selectedIngIndex)?.name ?: "Select ingredient",
          options = flatIngs.map { it.name },
          onSelect = { idx, _ -> selectedIngIndex = idx },
          testTag = "bm_ing_btn",
        )
      }

      val valLabel = when (mode) {
        "WEIGHT" -> "Total Batch Weight ($unit)"
        "UNITS" -> "Formula Units"
        else -> "Ingredient Amount ($unit)"
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
    } else if (batchScaleUnits > 0.0) {
      SarimCard(borderColor = palette.green.copy(alpha = 0.4f)) {
        Text("Weigh Amounts", color = palette.green, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
          "Yield: ${SarimFormat.formatQty(batchScaleUnits, 3)} unit(s) · Total ${SarimFormat.formatQty(batchScaleUnits * totalUnitWeightKg * multiplierToUnit, 2)} $unit",
          color = palette.textMuted,
          fontSize = 12.sp,
        )
        HorizontalDivider(color = palette.border)
        flatIngs.forEach { ing ->
          val qty = ing.kgPerUnit * batchScaleUnits * multiplierToUnit
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(ing.name, color = palette.textPrimary, fontSize = 13.5.sp)
            Text(
              "${SarimFormat.formatQty(qty, 2)} $unit",
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
          text = "Save Calculation",
          modifier = Modifier.weight(1.4f),
          onClick = {
            if (selectedFormula == null || batchScaleUnits <= 0.0) {
              viewModel.showDialog(AppDialogRequest("Incomplete Calculation", "Choose a formula and enter a valid positive value.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val title = saveName.trim().ifBlank { "${selectedFormula.name} (${SarimFormat.formatQty(batchScaleUnits, 2)} units)" }
            viewModel.launchSafely(
              errorTitle = "Save Calculation Failed",
              successMessage = "Saved batching calculation",
              onSuccess = {
                editingCalcId = null
                saveName = ""
              },
            ) {
              viewModel.repository.saveMixCalc(
                SavedMixCalcEntity(
                  id = editingCalcId ?: UUID.randomUUID().toString(),
                  kind = "BATCHING",
                  name = title,
                  formulaId = selectedFormula.id,
                  formulaName = selectedFormula.name,
                  unit = unit,
                  mode = mode,
                  inputValue = inputVal,
                  summaryText = "$mode $inputVal $unit → ${SarimFormat.formatQty(batchScaleUnits, 2)} units",
                )
              )
            }
          },
        )
      }
    }

    SarimCard {
      Text("Saved Calculations (${savedList.size})", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      if (savedList.isEmpty()) {
        Text("No saved calculations yet.", color = palette.textMuted, fontSize = 12.5.sp)
      } else {
        savedList.forEach { calc ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                editingCalcId = calc.id
                val fIdx = formulas.indexOfFirst { it.id == calc.formulaId }
                if (fIdx >= 0) selectedFormulaIndex = fIdx + 1
                unit = calc.unit
                mode = calc.mode
                valueText = calc.inputValue.toString()
                saveName = calc.name
              }
              .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(calc.name, color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
              Text(calc.summaryText, color = palette.textMuted, fontSize = 11.5.sp)
            }
            IconButton(
              onClick = {
                viewModel.launchSafely(errorTitle = "Delete Failed") {
                  viewModel.repository.deleteMixCalc(calc.id)
                }
              }
            ) {
              Icon(Icons.Filled.Delete, contentDescription = "Delete saved calc", tint = palette.red, modifier = Modifier.size(18.dp))
            }
          }
          HorizontalDivider(color = palette.border)
        }
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
  val savedList = remember(state.mixCalcs) { state.mixCalcs.filter { !it.isDeleted && it.kind == "CORRECTION" } }

  var editingCalcId by remember(savedId) { mutableStateOf(savedId) }
  var selectedFormulaIndex by remember { mutableIntStateOf(0) }
  var unit by remember { mutableStateOf("g") } // default g
  var originalText by remember { mutableStateOf("") }
  var usedLostText by remember { mutableStateOf("0") }
  var showActualOverrides by remember { mutableStateOf(false) }
  val actualAmounts = remember { mutableStateMapOf<String, String>() }
  var targetTotalAddText by remember { mutableStateOf("") }
  var saveName by remember { mutableStateOf("") }

  val formulaOptions = remember(formulas) { listOf("Select formula") + formulas.map { it.name } }
  val selectedFormula = formulas.getOrNull(selectedFormulaIndex - 1)
  val flatIngs = remember(selectedFormula, formulasMap, materialsMap) {
    selectedFormula?.let { flattenFormulaIngredients(it, formulasMap, materialsMap) } ?: emptyList()
  }
  val totalUnitWeightKg = remember(flatIngs) { flatIngs.sumOf { it.kgPerUnit } }

  val originalVal = SarimFormat.parseSafeDouble(originalText) ?: 0.0
  val usedVal = SarimFormat.parseSafeDouble(usedLostText) ?: 0.0
  val remainingVal = (originalVal - usedVal).coerceAtLeast(0.0)

  // Compute remaining ingredient breakdown (either formula proportions or user's actual amounts scaled to remaining)
  val remainingPerIng = remember(flatIngs, totalUnitWeightKg, remainingVal, showActualOverrides, actualAmounts.toMap(), originalVal) {
    if (flatIngs.isEmpty() || totalUnitWeightKg <= 0.0) emptyMap()
    else if (!showActualOverrides || actualAmounts.values.all { it.isBlank() }) {
      flatIngs.associate { it.id to remainingVal * (it.kgPerUnit / totalUnitWeightKg) }
    } else {
      val actualSum = flatIngs.sumOf { SarimFormat.parseSafeDouble(actualAmounts[it.id].orEmpty()) ?: 0.0 }
      val remainRatio = if (actualSum > 0.0001) remainingVal / actualSum else 1.0
      flatIngs.associate { ing ->
        val rawAct = SarimFormat.parseSafeDouble(actualAmounts[ing.id].orEmpty()) ?: 0.0
        ing.id to (rawAct * remainRatio)
      }
    }
  }

  // Minimum new total weight T such that for every ingredient i: T * ratio_i >= remaining_i
  val minRequiredNewTotal = remember(flatIngs, totalUnitWeightKg, remainingPerIng) {
    if (flatIngs.isEmpty() || totalUnitWeightKg <= 0.0) 0.0
    else flatIngs.maxOfOrNull { ing ->
      val ratio = ing.kgPerUnit / totalUnitWeightKg
      if (ratio > 0.000001) (remainingPerIng[ing.id] ?: 0.0) / ratio else 0.0
    } ?: 0.0
  }
  val minTotalAdd = (minRequiredNewTotal - remainingVal).coerceAtLeast(0.0)
  val customAddVal = SarimFormat.parseSafeDouble(targetTotalAddText)
  val effectiveTotalAdd = if (customAddVal != null && customAddVal >= minTotalAdd) customAddVal else minTotalAdd
  val effectiveNewTotal = remainingVal + effectiveTotalAdd

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SarimCard {
      SarimPopoverSelector(
        label = "Formula",
        selectedText = formulaOptions.getOrElse(selectedFormulaIndex) { "Select formula" },
        options = formulaOptions,
        onSelect = { idx, _ -> selectedFormulaIndex = idx; actualAmounts.clear() },
        testTag = "bf_formula_btn",
      )

      SarimFieldLabel("Unit")
      SarimToggleGroup(
        options = listOf("g", "kg"),
        selected = unit,
        labelOf = { it },
        onSelect = { unit = it },
      )

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SarimTextField(
          value = originalText,
          onValueChange = { originalText = it },
          label = "Original Batch ($unit)",
          placeholder = "0",
          keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f),
          testTag = "bf_original",
        )
        SarimTextField(
          value = usedLostText,
          onValueChange = { usedLostText = it },
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
          "${SarimFormat.formatQty(remainingVal, 2)} $unit",
          color = palette.red,
          style = TabularNumberStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
          modifier = Modifier.testTag("bfRemaining"),
        )
      }

      if (selectedFormula != null) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { showActualOverrides = !showActualOverrides }
            .padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Mixed differently than the formula?", color = palette.cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          Icon(if (showActualOverrides) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = palette.cyan)
        }
        AnimatedVisibility(visible = showActualOverrides) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            flatIngs.forEach { ing ->
              SarimTextField(
                value = actualAmounts[ing.id] ?: "",
                onValueChange = { actualAmounts[ing.id] = it },
                label = "${ing.name} actual ($unit)",
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
    } else if (remainingVal > 0.0 && totalUnitWeightKg > 0.0) {
      SarimCard(borderColor = palette.amber.copy(alpha = 0.45f)) {
        Text("Required Additions", color = palette.amber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
          "Type a new amount in any Add box or in Total to Add – all other ingredients update automatically.",
          color = palette.textMuted,
          fontSize = 11.5.sp,
        )
        HorizontalDivider(color = palette.border)

        flatIngs.forEach { ing ->
          val ratio = ing.kgPerUnit / totalUnitWeightKg
          val inRem = remainingPerIng[ing.id] ?: 0.0
          val totalReq = effectiveNewTotal * ratio
          val toAdd = (totalReq - inRem).coerceAtLeast(0.0)
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1.2f)) {
              Text(ing.name, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
              Text("Rem ${SarimFormat.formatQty(inRem, 1)} → Req ${SarimFormat.formatQty(totalReq, 1)} $unit", color = palette.textMuted, fontSize = 11.sp)
            }
            Text(
              "+${SarimFormat.formatQty(toAdd, 2)} $unit",
              color = palette.green,
              style = TabularNumberStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
            )
          }
        }

        HorizontalDivider(color = palette.border)
        SarimTextField(
          value = targetTotalAddText.ifBlank { SarimFormat.formatQty(effectiveTotalAdd, 2) },
          onValueChange = { targetTotalAddText = it },
          label = "Total to Add ($unit)",
          keyboardType = KeyboardType.Decimal,
          testTag = "bfTotalAdd",
        )
        Text(
          "New total batch: ${SarimFormat.formatQty(effectiveNewTotal, 2)} $unit",
          color = palette.green,
          fontWeight = FontWeight.Bold,
          fontSize = 13.5.sp,
        )
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
            onClick = { editingCalcId = null; originalText = ""; usedLostText = "0"; saveName = "" },
          )
        }
        SarimPrimaryButton(
          text = "Save Correction",
          modifier = Modifier.weight(1.4f),
          onClick = {
            if (selectedFormula == null || remainingVal <= 0.0) {
              viewModel.showDialog(AppDialogRequest("Incomplete Correction", "Choose a formula and enter positive batch quantities.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val title = saveName.trim().ifBlank { "${selectedFormula.name} fix (+${SarimFormat.formatQty(effectiveTotalAdd, 1)} $unit)" }
            viewModel.launchSafely(
              errorTitle = "Save Correction Failed",
              successMessage = "Saved batch correction",
              onSuccess = {
                editingCalcId = null
                saveName = ""
              },
            ) {
              viewModel.repository.saveMixCalc(
                SavedMixCalcEntity(
                  id = editingCalcId ?: UUID.randomUUID().toString(),
                  kind = "CORRECTION",
                  name = title,
                  formulaId = selectedFormula.id,
                  formulaName = selectedFormula.name,
                  unit = unit,
                  mode = "CORRECTION",
                  inputValue = remainingVal,
                  summaryText = "Add ${SarimFormat.formatQty(effectiveTotalAdd, 2)} $unit → Total ${SarimFormat.formatQty(effectiveNewTotal, 2)} $unit",
                )
              )
            }
          },
        )
      }
    }

    SarimCard {
      Text("Saved Corrections (${savedList.size})", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      if (savedList.isEmpty()) {
        Text("No saved corrections yet.", color = palette.textMuted, fontSize = 12.5.sp)
      } else {
        savedList.forEach { calc ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(calc.name, color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
              Text(calc.summaryText, color = palette.textMuted, fontSize = 11.5.sp)
            }
            IconButton(
              onClick = {
                viewModel.launchSafely(errorTitle = "Delete Failed") {
                  viewModel.repository.deleteMixCalc(calc.id)
                }
              }
            ) {
              Icon(Icons.Filled.Delete, contentDescription = "Delete saved correction", tint = palette.red, modifier = Modifier.size(18.dp))
            }
          }
          HorizontalDivider(color = palette.border)
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}
