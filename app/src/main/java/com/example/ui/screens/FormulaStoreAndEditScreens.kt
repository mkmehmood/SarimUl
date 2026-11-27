package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.FormulaEntity
import com.example.data.local.JsonCodecs
import com.example.data.model.FormulaIngredient
import com.example.data.model.StandaloneScreen
import com.example.data.repository.FormulaCalculator
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.launch

@Composable
fun FormulaStoreScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val formulas = remember(state.formulas) { state.formulas.filter { !it.isDeleted } }
  val formulasMap = remember(formulas) { formulas.associateBy { it.id } }
  val materialsMap = remember(state.rawMaterials) {
    state.rawMaterials.filter { !it.isDeleted }.associateBy { it.id }
  }
  val stores = remember(state.stores) { state.stores.filter { !it.isDeleted } }
  var fabExpanded by remember { mutableStateOf(false) }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (formulas.isEmpty()) {
        SarimEmptyState("No formulas saved", "Tap + to create a manufacturing formula.")
      } else {
        formulas.forEach { formula ->
          val summary = FormulaCalculator.computeFormulaSummary(formula, formulasMap, materialsMap)
          val ingredients = remember(formula.ingredientsJson) { JsonCodecs.decodeIngredients(formula.ingredientsJson) }
          val usedByStores = remember(stores, formula.id) { stores.filter { it.formulaId == formula.id }.map { it.name } }
          val linkedInFormulas = remember(formulas, formula.id) {
            formulas.filter { parent ->
              parent.id != formula.id && JsonCodecs.decodeIngredients(parent.ingredientsJson).any { it.refType == "FORMULA" && it.refId == formula.id }
            }.map { it.name }
          }

          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(24.dp))
              .clickable { viewModel.openStandalone(StandaloneScreen.FormulaEdit(formula.id)) },
            shape = RoundedCornerShape(24.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.border),
          ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text("${formula.name} (1 UNIT)", color = palette.green, fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
                SarimChip("${SarimFormat.formatQty(formula.unitsAvailable, 1)} avail", palette.cyan)
              }

              ingredients.forEach { ing ->
                if (ing.refType == "FORMULA") {
                  val sub = formulasMap[ing.refId]
                  val subSummary = sub?.let { FormulaCalculator.computeFormulaSummary(it, formulasMap, materialsMap) }
                  val subCostPerKg = if ((subSummary?.unitWeightKg ?: 0.0) > 0.0001) (subSummary!!.totalCostPerUnit / subSummary.unitWeightKg) else 0.0
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(ing.refName, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                      "${SarimFormat.formatKg(ing.qtyKg)} · ${SarimFormat.formatRs(ing.qtyKg * subCostPerKg, privacyHidden = state.hideMoneyFigures)}",
                      color = palette.textPrimary,
                      style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                    )
                  }
                  sub?.let {
                    JsonCodecs.decodeIngredients(it.ingredientsJson).forEach { subIng ->
                      Text(
                        "  ↳ ${subIng.refName} (${SarimFormat.formatKg(subIng.qtyKg)} / unit)",
                        color = palette.textMuted,
                        fontSize = 11.5.sp,
                      )
                    }
                  }
                } else {
                  val mat = materialsMap[ing.refId] ?: materialsMap.values.firstOrNull { it.name.equals(ing.refName, ignoreCase = true) }
                  val cost = ing.qtyKg * (mat?.avgCostPerKg ?: 0.0)
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(ing.refName, color = palette.textMuted, fontSize = 13.sp)
                    Text(
                      "${SarimFormat.formatKg(ing.qtyKg)} · ${SarimFormat.formatRs(cost, privacyHidden = state.hideMoneyFigures)}",
                      color = palette.textPrimary,
                      style = TabularNumberStyle.copy(fontSize = 12.5.sp),
                    )
                  }
                }
              }

              Text(
                "Additional Cost (${SarimFormat.formatRs(formula.additionalCostPerUnit, privacyHidden = state.hideMoneyFigures)} per unit)",
                color = palette.red,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
              )

              HorizontalDivider(color = palette.border)

              SummaryLine("Unit Weight", SarimFormat.formatKg(summary.unitWeightKg))
              SummaryLine("Raw Material Cost per Unit", SarimFormat.formatRs(summary.rawMaterialCostPerUnit, privacyHidden = state.hideMoneyFigures))
              SummaryLine("Total Cost per Unit", SarimFormat.formatRs(summary.totalCostPerUnit, privacyHidden = state.hideMoneyFigures), bold = true, valueColor = palette.green)
              SummaryLine("Cost per kg (Sales/Calc)", SarimFormat.formatRs(summary.costPerKgAdjusted, privacyHidden = state.hideMoneyFigures))

              val footerParts = buildList {
                add("Tap to edit")
                if (usedByStores.isNotEmpty()) add("Used by ${usedByStores.joinToString(", ")}")
                if (linkedInFormulas.isNotEmpty()) add("Linked in ${linkedInFormulas.joinToString(", ")}")
              }
              Text(footerParts.joinToString(" · "), color = palette.textMuted, fontSize = 11.sp)
            }
          }
        }
      }
      Spacer(Modifier.height(84.dp))
    }

    // FAB speed dial bottom-right
    Column(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .navigationBarsPadding()
        .padding(18.dp),
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      AnimatedVisibility(visible = fabExpanded) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
          SmallFabRow("Correction", Icons.Rounded.Tune, palette.amber) {
            fabExpanded = false
            viewModel.openStandalone(StandaloneScreen.BatchCorrection())
          }
          SmallFabRow("Batching", Icons.Rounded.Build, palette.cyan) {
            fabExpanded = false
            viewModel.openStandalone(StandaloneScreen.Batching())
          }
          SmallFabRow("Formula", Icons.Rounded.Science, palette.green) {
            fabExpanded = false
            viewModel.openStandalone(StandaloneScreen.FormulaEdit(null))
          }
        }
      }

      FloatingActionButton(
        onClick = { fabExpanded = !fabExpanded },
        containerColor = palette.green,
        contentColor = Color(0xFF022112),
        modifier = Modifier.testTag("formulaFabDial"),
      ) {
        Icon(if (fabExpanded) Icons.Rounded.Close else Icons.Rounded.Add, contentDescription = "Formula actions")
      }
    }
  }
}

@Composable
private fun SmallFabRow(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  color: Color,
  onClick: () -> Unit,
) {
  val palette = LocalSarimPalette.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = palette.surfaceElevated,
      border = BorderStroke(1.dp, palette.border),
      modifier = Modifier.clickable(onClick = onClick),
    ) {
      Text(label, color = palette.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
    SmallFloatingActionButton(
      onClick = onClick,
      containerColor = color,
      contentColor = Color(0xFF041A0E),
    ) {
      Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
private fun SummaryLine(label: String, value: String, bold: Boolean = false, valueColor: Color? = null) {
  val palette = LocalSarimPalette.current
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = if (bold) palette.textPrimary else palette.textMuted, fontSize = 12.5.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    Text(value, color = valueColor ?: palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold))
  }
}

@Composable
fun FormulaEditScreen(
  formulaId: String?,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val allFormulas = remember(state.formulas) { state.formulas.filter { !it.isDeleted } }
  val allMaterials = remember(state.rawMaterials) { state.rawMaterials.filter { !it.isDeleted } }
  val existing = remember(formulaId, allFormulas) { formulaId?.let { id -> allFormulas.firstOrNull { it.id == id } } }

  var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
  var additionalCostText by remember(existing) { mutableStateOf(existing?.additionalCostPerUnit?.toString() ?: "0") }
  var costFactorText by remember(existing) { mutableStateOf(existing?.costAdjustmentFactor?.toString() ?: "1") }
  val rows = remember(existing) {
    val init = existing?.let { JsonCodecs.decodeIngredients(it.ingredientsJson) } ?: emptyList()
    mutableStateListOf<FormulaIngredient>().apply { addAll(init) }
  }

  val formulasMap = remember(allFormulas) { allFormulas.associateBy { it.id } }
  val materialsMap = remember(allMaterials) { allMaterials.associateBy { it.id } }
  val addCostVal = SarimFormat.parseSafeDouble(additionalCostText) ?: 0.0
  val factorVal = SarimFormat.parseSafeDouble(costFactorText) ?: 1.0
  val draftFormula = FormulaEntity(
    id = existing?.id ?: "draft",
    name = name,
    additionalCostPerUnit = addCostVal,
    costAdjustmentFactor = factorVal,
    ingredientsJson = JsonCodecs.encodeIngredients(rows),
  )
  val liveSummary = FormulaCalculator.computeFormulaSummary(draftFormula, formulasMap, materialsMap)

  val candidateLabels = remember(allMaterials, allFormulas, existing) {
    allMaterials.map { "[Mat] ${it.name}" } +
      allFormulas.filter { it.id != existing?.id }.map { "[Formula] ${it.name}" }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SarimCard {
      SarimTextField(
        value = name,
        onValueChange = { name = it },
        label = "Formula Name",
        placeholder = "e.g. Premium Blend",
        maxLength = 60,
        testTag = "fs_edit_name",
      )
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SarimTextField(
          value = additionalCostText,
          onValueChange = { additionalCostText = it },
          label = "Additional Cost (Per Unit)",
          placeholder = "0",
          prefixText = "Rs",
          keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f),
          testTag = "fs_additional_cost",
        )
        SarimTextField(
          value = costFactorText,
          onValueChange = { costFactorText = it },
          label = "Cost Adjustment (X)",
          placeholder = "1",
          keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f),
          testTag = "fs_cost_factor",
        )
      }
    }

    SarimCard {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Material", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
        Text("Cost/kg", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
        Text("Qty (kg)", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
      }

      rows.forEachIndexed { idx, row ->
        var qtyInput by remember(idx, rows.size) { mutableStateOf(if (row.qtyKg == 0.0) "" else row.qtyKg.toString()) }
        var searchLabel by remember(idx, rows.size) { mutableStateOf(row.refName) }

        val unitCost = if (row.refType == "FORMULA") {
          val sub = formulasMap[row.refId]
          val subSum = sub?.let { FormulaCalculator.computeFormulaSummary(it, formulasMap, materialsMap) }
          if ((subSum?.unitWeightKg ?: 0.0) > 0.0001) subSum!!.totalCostPerUnit / subSum.unitWeightKg else 0.0
        } else {
          materialsMap[row.refId]?.avgCostPerKg ?: 0.0
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.weight(1.4f)) {
              SarimSearchFieldWithDropdown(
                value = searchLabel,
                onValueChange = { searchLabel = it },
                label = "",
                placeholder = "Material or Formula",
                suggestions = candidateLabels,
                onSuggestionSelected = { chosen ->
                  if (chosen.startsWith("[Formula] ")) {
                    val fName = chosen.removePrefix("[Formula] ").trim()
                    val matched = allFormulas.firstOrNull { it.name.equals(fName, ignoreCase = true) }
                    if (matched != null && idx in rows.indices) {
                      searchLabel = matched.name
                      rows[idx] = rows[idx].copy(refType = "FORMULA", refId = matched.id, refName = matched.name)
                    }
                  } else {
                    val mName = chosen.removePrefix("[Mat] ").trim()
                    val matched = allMaterials.firstOrNull { it.name.equals(mName, ignoreCase = true) }
                    if (matched != null && idx in rows.indices) {
                      searchLabel = matched.name
                      rows[idx] = rows[idx].copy(refType = "MATERIAL", refId = matched.id, refName = matched.name)
                    }
                  }
                },
              )
            }
            Text(
              SarimFormat.formatRs(unitCost, showSymbol = false, privacyHidden = state.hideMoneyFigures),
              color = palette.textMuted,
              style = TabularNumberStyle.copy(fontSize = 12.sp),
              modifier = Modifier.weight(0.65f),
            )
            OutlinedTextField(
              value = qtyInput,
              onValueChange = { v ->
                qtyInput = v
                val num = SarimFormat.parseSafeDouble(v) ?: 0.0
                if (idx in rows.indices) {
                  rows[idx] = rows[idx].copy(qtyKg = num)
                }
              },
              singleLine = true,
              placeholder = { Text("0") },
              modifier = Modifier.weight(0.75f),
            )
            IconButton(
              onClick = { if (idx in rows.indices) rows.removeAt(idx) },
              modifier = Modifier.size(36.dp),
            ) {
              Icon(Icons.Rounded.Delete, contentDescription = "Remove row", tint = palette.red, modifier = Modifier.size(18.dp))
            }
          }
          HorizontalDivider(color = palette.border)
        }
      }

      SarimSecondaryButton(
        text = "+ Add Ingredient Row",
        modifier = Modifier.fillMaxWidth(),
        onClick = { rows.add(FormulaIngredient()) },
      )
      Text(
        "Tip: search a material, or link a Formula so its materials follow any later changes to it.",
        color = palette.textMuted,
        fontSize = 11.5.sp,
      )
    }

    SarimCard {
      SummaryLine("Unit Weight", SarimFormat.formatKg(liveSummary.unitWeightKg))
      SummaryLine("Raw Material Cost per Unit", SarimFormat.formatRs(liveSummary.rawMaterialCostPerUnit, privacyHidden = state.hideMoneyFigures))
      SummaryLine("Total Cost per Unit", SarimFormat.formatRs(liveSummary.totalCostPerUnit, privacyHidden = state.hideMoneyFigures), bold = true, valueColor = palette.green)
      SummaryLine("Cost per kg (Sales/Calc)", SarimFormat.formatRs(liveSummary.costPerKgAdjusted, privacyHidden = state.hideMoneyFigures))
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (existing != null) {
        SarimDangerButton(
          text = "Delete Formula",
          modifier = Modifier.weight(1f),
          onClick = {
            viewModel.showDialog(
              AppDialogRequest(
                title = "Delete Formula",
                body = "Move formula ${existing.name} to Recycle Bin?",
                tone = DialogTone.PERMANENT,
                confirmLabel = "Delete",
                onConfirm = {
                  viewModel.launchSafely(
                    errorTitle = "Delete Formula Failed",
                    successMessage = "Deleted formula ${existing.name}",
                    onSuccess = { viewModel.closeStandalone() },
                  ) {
                    viewModel.repository.deleteFormula(existing.id)
                  }
                },
              )
            )
          },
        )
      }
      SarimPrimaryButton(
        text = "Save Formula",
        modifier = Modifier.weight(1.4f),
        testTag = "save_formula_btn",
        onClick = {
          val clean = name.trim()
          if (clean.isEmpty() || clean.length > 60) {
            viewModel.showDialog(AppDialogRequest("Invalid Name", "Formula name is required (max 60 characters).", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (addCostVal !in 0.0..100_000_000.0) {
            viewModel.showDialog(AppDialogRequest("Invalid Cost", "Additional cost must be between 0 and 100,000,000.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (factorVal <= 0.0 || factorVal > 100.0) {
            viewModel.showDialog(AppDialogRequest("Invalid Cost Factor", "Cost adjustment factor must be > 0 and at most 100.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          val dupFormula = state.formulas.any { !it.isDeleted && it.id != existing?.id && it.name.equals(clean, ignoreCase = true) }
          if (dupFormula) {
            viewModel.showDialog(AppDialogRequest("Duplicate Formula", "A formula named \"$clean\" already exists. Choose a different name or edit that formula.", tone = DialogTone.WARNING, cancelLabel = null))
            return@SarimPrimaryButton
          }
          val validRows = rows.filter { it.refId.isNotBlank() }
          if (validRows.isEmpty()) {
            viewModel.showDialog(AppDialogRequest("Ingredients Required", "Add at least one ingredient with quantity.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (validRows.any { it.qtyKg !in 0.001..1_000_000.0 }) {
            viewModel.showDialog(AppDialogRequest("Invalid Quantity", "Ingredient quantity must be between 0.001 and 1,000,000 kg.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          val dupIng = validRows.groupBy { "${it.refType}:${it.refId}" }.any { it.value.size > 1 }
          if (dupIng) {
            viewModel.showDialog(AppDialogRequest("Duplicate Ingredient", "Each material or linked formula may only appear once.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          viewModel.confirmSave(
"Formula",
listOf("Name" to clean),
isUpdate = existing != null,
) {
viewModel.launchSafely(
            errorTitle = "Save Formula Failed",
            successMessage = "Formula saved",
            onSuccess = { viewModel.closeStandalone() },
          ) {
            viewModel.repository.saveFormula(existing?.id, clean, addCostVal, factorVal, validRows)
          }
}
        },
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}
