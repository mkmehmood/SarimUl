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
import com.example.data.repository.SarimBatchCalc
import com.example.data.repository.SarimFormulaChecks
import com.example.data.repository.SarimLinkEngine
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
  val hide = state.hideMoneyFigures
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
        SarimEmptyState("No formulas yet.", "Tap the + button to add one.")
      } else {
        formulas.forEach { formula ->
          val d = FormulaCalculator.describeFormula(formula, formulasMap, materialsMap)
          val ingredients = remember(formula.ingredientsJson) { JsonCodecs.decodeIngredients(formula.ingredientsJson) }
          val usedByStores = remember(stores, formula.id) { stores.filter { it.formulaId == formula.id }.map { it.name } }
          val linkedIn = remember(formulas, formula.id) {
            FormulaCalculator.formulaUsers(formula.id, formulas).map { it.name.ifBlank { "Untitled" } }
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
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("${formula.name.ifBlank { "Untitled" }} (1 Unit)", color = palette.green, fontWeight = FontWeight.Bold, fontSize = 15.5.sp)

              if (ingredients.isEmpty()) {
                Text("No ingredients.", color = palette.textMuted, fontSize = 12.5.sp)
              }
              ingredients.forEach { ing ->
                if (ing.refType != "FORMULA") {
                  val mat = materialsMap[ing.refId]
                    ?: materialsMap.values.firstOrNull { ing.refName.isNotBlank() && it.name.equals(ing.refName.trim(), ignoreCase = true) }
                  StoreRow(
                    "${mat?.name ?: ing.refName} (${SarimBatchCalc.fmtPrecise(ing.qtyKg, "kg")} kg)",
                    SarimFormat.formatRs(ing.qtyKg * (mat?.avgCostPerKg ?: 0.0), privacyHidden = hide),
                  )
                } else {
                  val sub = formulasMap[ing.refId]
                  if (sub == null) {
                    StoreRow("${ing.refName} · sub formula (removed)", "—", labelColor = palette.red, valueColor = palette.red)
                  } else {
                    val sd = FormulaCalculator.describeFormula(sub, formulasMap, materialsMap)
                    val scale = if (sd.weight > 0.0) ing.qtyKg / sd.weight else 0.0
                    StoreRow(
                      "${sub.name.ifBlank { "Untitled" }} · sub formula (${SarimBatchCalc.fmtPrecise(ing.qtyKg, "kg")} kg)",
                      SarimFormat.formatRs(scale * sd.perUnit, privacyHidden = hide),
                      labelColor = palette.textPrimary,
                      bold = true,
                    )
                    sd.items.forEach { m ->
                      StoreRow(
                        "  ↳ ${m.name} (${SarimBatchCalc.fmtPrecise(m.quantity * scale, "kg")} kg)",
                        SarimFormat.formatRs(m.cost * m.quantity * scale, privacyHidden = hide),
                        labelColor = palette.textMuted,
                        valueColor = palette.textMuted,
                      )
                    }
                    if (sd.additionalCost > 0.0) {
                      StoreRow(
                        "  ↳ Additional cost",
                        SarimFormat.formatRs(sd.additionalCost * scale, privacyHidden = hide),
                        labelColor = palette.textMuted,
                        valueColor = palette.textMuted,
                      )
                    }
                  }
                }
              }

              if (d.ownAdditionalCost > 0.0) {
                StoreRow(
                  "Additional Cost (${SarimFormat.formatQty(d.ownAdditionalCost, 2)} per unit)",
                  SarimFormat.formatRs(d.ownAdditionalCost, privacyHidden = hide),
                  labelColor = palette.red,
                  valueColor = palette.red,
                )
              }
              if (d.problems.isNotEmpty()) {
                val names = d.problems.map { "${it.name} (${if (it.type == "cycle") "circular" else "removed"})" }.distinct()
                Text("Check linked formulas: ${names.joinToString(", ")}", color = palette.red, fontSize = 12.5.sp)
              }

              HorizontalDivider(color = palette.border)

              StoreRow("Unit Weight", SarimBatchCalc.fmtPrecise(d.weight, "kg") + " kg")
              StoreRow("Raw Material Cost per Unit", SarimFormat.formatRs(d.raw, privacyHidden = hide))
              if (d.extraCost > 0.0) {
                StoreRow("Additional cost from linked formulas", SarimFormat.formatRs(d.extraCost, privacyHidden = hide))
              }
              StoreRow(
                "Total Cost per Unit",
                SarimFormat.formatRs(d.perUnit, privacyHidden = hide),
                labelColor = palette.textPrimary,
                valueColor = palette.green,
                bold = true,
              )
              StoreRow("Cost per kg (Sales/Calc)", SarimFormat.formatRs(d.perKg, privacyHidden = hide))

              val notes = buildList {
                if (usedByStores.isNotEmpty()) add("Used by ${usedByStores.joinToString(", ")}")
                if (linkedIn.isNotEmpty()) add("Linked in ${linkedIn.joinToString(", ")}")
                add("Tap to edit")
              }
              Text(notes.joinToString(" · "), color = palette.textMuted, fontSize = 11.sp)
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
private fun StoreRow(
  label: String,
  value: String,
  labelColor: Color? = null,
  valueColor: Color? = null,
  bold: Boolean = false,
) {
  val palette = LocalSarimPalette.current
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      label,
      color = labelColor ?: palette.textMuted,
      fontSize = 12.5.sp,
      fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
      modifier = Modifier.weight(1f),
    )
    Text(
      value,
      color = valueColor ?: palette.textPrimary,
      style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold),
    )
  }
}

/** One editable ingredient row. [refId] is blank until a suggestion was picked (web: "Pick X from the list"). */
private data class EditRow(
  val key: Long,
  val refType: String = "MATERIAL",
  val refId: String = "",
  val label: String = "",
  val qtyText: String = "",
)

private const val FORMULA_PREFIX = "[Formula] "
private const val MATERIAL_PREFIX = "[Mat] "

@Composable
fun FormulaEditScreen(
  formulaId: String?,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val hide = state.hideMoneyFigures
  val allFormulas = remember(state.formulas) { state.formulas.filter { !it.isDeleted } }
  val allMaterials = remember(state.rawMaterials) { state.rawMaterials.filter { !it.isDeleted } }
  val existing = remember(formulaId, allFormulas) { formulaId?.let { id -> allFormulas.firstOrNull { it.id == id } } }
  val formulasMap = remember(allFormulas) { allFormulas.associateBy { it.id } }
  val materialsMap = remember(allMaterials) { allMaterials.associateBy { it.id } }

  var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
  var additionalCostText by remember(existing) { mutableStateOf(existing?.additionalCostPerUnit?.let { SarimBatchCalc.fmtPrecise(it, "kg") } ?: "0") }
  var costFactorText by remember(existing) { mutableStateOf(existing?.costAdjustmentFactor?.let { SarimBatchCalc.fmtPrecise(it, "kg") } ?: "1") }
  val keyGen = remember(existing) { java.util.concurrent.atomic.AtomicLong(1L) }
  val rows = remember(existing) {
    val init = existing?.let { JsonCodecs.decodeIngredients(it.ingredientsJson) } ?: emptyList()
    mutableStateListOf<EditRow>().apply {
      init.forEach { ing ->
        val label = if (ing.refType == "FORMULA") {
          formulasMap[ing.refId]?.name ?: ing.refName
        } else {
          (materialsMap[ing.refId]
            ?: allMaterials.firstOrNull { ing.refName.isNotBlank() && it.name.equals(ing.refName.trim(), ignoreCase = true) })?.name
            ?: ing.refName
        }
        val refId = if (ing.refType == "FORMULA") ing.refId else (materialsMap[ing.refId]?.id
          ?: allMaterials.firstOrNull { ing.refName.isNotBlank() && it.name.equals(ing.refName.trim(), ignoreCase = true) }?.id
          ?: ing.refId)
        add(EditRow(keyGen.getAndIncrement(), ing.refType, refId, label, SarimBatchCalc.fmtPrecise(ing.qtyKg, "kg")))
      }
    }
  }

  fun draftIngredients(): List<FormulaIngredient> = rows.filter { it.refId.isNotBlank() }.map {
    FormulaIngredient(refType = it.refType, refId = it.refId, refName = it.label, qtyKg = it.qtyText.trim().toDoubleOrNull() ?: 0.0)
  }

  val addCostVal = SarimFormat.parseSafeDouble(additionalCostText) ?: 0.0
  val factorVal = (SarimFormat.parseSafeDouble(costFactorText) ?: 1.0).let { if (it == 0.0) 1.0 else it }
  val draftFormula = FormulaEntity(
    id = existing?.id ?: "__new__",
    name = name,
    additionalCostPerUnit = addCostVal,
    costAdjustmentFactor = factorVal,
    ingredientsJson = JsonCodecs.encodeIngredients(draftIngredients()),
  )
  val mapWithDraft = remember(formulasMap, draftFormula) { formulasMap + (draftFormula.id to draftFormula) }
  val liveSummary = FormulaCalculator.describeFormula(draftFormula, mapWithDraft, materialsMap)

  // Web `_pickableFormulas`: only formulas that have ingredients and would not create a loop.
  val linkable = remember(allFormulas, existing, formulasMap) {
    allFormulas.filter { f ->
      f.id != existing?.id &&
        JsonCodecs.decodeIngredients(f.ingredientsJson).isNotEmpty() &&
        (existing == null || FormulaCalculator.canLink(formulasMap, existing.id, f.id))
    }
  }
  val candidateLabels = remember(allMaterials, linkable) {
    allMaterials.map { "$MATERIAL_PREFIX${it.name}" } + linkable.map { "$FORMULA_PREFIX${it.name}" }
  }

  fun warn(message: String) {
    viewModel.showDialog(AppDialogRequest("Warning", message, tone = DialogTone.WARNING, cancelLabel = null))
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
          label = "Cost Adjustment for Sales & Calc (X)",
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

      rows.toList().forEach { row ->
        key(row.key) {
          fun update(transform: (EditRow) -> EditRow) {
            val i = rows.indexOfFirst { it.key == row.key }
            if (i >= 0) rows[i] = transform(rows[i])
          }
          val unitCost = if (row.refType == "FORMULA") {
            val sub = formulasMap[row.refId]
            val sd = sub?.let { FormulaCalculator.describeFormula(it, formulasMap, materialsMap) }
            if ((sd?.weight ?: 0.0) > 0.0) sd!!.perUnit / sd.weight else 0.0
          } else {
            materialsMap[row.refId]?.avgCostPerKg ?: 0.0
          }

          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Box(modifier = Modifier.weight(1.4f)) {
                SarimSearchFieldWithDropdown(
                  value = row.label,
                  onValueChange = { typed ->
                    // Typing again drops the earlier pick, like the web search input.
                    update { it.copy(label = typed, refId = if (typed == it.label) it.refId else "") }
                  },
                  label = "",
                  placeholder = "Material or Formula",
                  suggestions = candidateLabels,
                  onSuggestionSelected = { chosen ->
                    if (chosen.startsWith(FORMULA_PREFIX)) {
                      val matched = linkable.firstOrNull { it.name.equals(chosen.removePrefix(FORMULA_PREFIX).trim(), ignoreCase = true) }
                      if (matched != null) update { it.copy(refType = "FORMULA", refId = matched.id, label = matched.name) }
                    } else {
                      val matched = allMaterials.firstOrNull { it.name.equals(chosen.removePrefix(MATERIAL_PREFIX).trim(), ignoreCase = true) }
                      if (matched != null) update { it.copy(refType = "MATERIAL", refId = matched.id, label = matched.name) }
                    }
                  },
                )
              }
              Text(
                SarimFormat.formatRs(unitCost, showSymbol = false, privacyHidden = hide),
                color = palette.textMuted,
                style = TabularNumberStyle.copy(fontSize = 12.sp),
                modifier = Modifier.weight(0.65f),
              )
              SarimTextField(
                value = row.qtyText,
                onValueChange = { v -> update { it.copy(qtyText = v) } },
                label = "",
                placeholder = "0",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(0.75f),
              )
              IconButton(
                onClick = { rows.removeAll { it.key == row.key } },
                modifier = Modifier.size(36.dp),
              ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Remove row", tint = palette.red, modifier = Modifier.size(18.dp))
              }
            }
            // Linked formula: show the materials it brings in, scaled to the kg entered (web "factory-link-child" rows).
            if (row.refType == "FORMULA" && row.refId.isNotBlank()) {
              val sub = formulasMap[row.refId]
              val q = row.qtyText.trim().toDoubleOrNull() ?: 0.0
              if (sub != null && q > 0.0) {
                val sd = FormulaCalculator.describeFormula(sub, formulasMap, materialsMap)
                val scale = if (sd.weight > 0.0) q / sd.weight else 0.0
                sd.items.forEach { m ->
                  Text(
                    "  ↳ ${m.name} (${SarimBatchCalc.fmtPrecise(m.quantity * scale, "kg")} kg)",
                    color = palette.textMuted,
                    fontSize = 11.5.sp,
                  )
                }
              }
            }
            HorizontalDivider(color = palette.border)
          }
        }
      }

      SarimSecondaryButton(
        text = "+ Add Ingredient Row",
        modifier = Modifier.fillMaxWidth(),
        onClick = { rows.add(EditRow(keyGen.getAndIncrement())) },
      )
      Text(
        "Tip: search a material, or link a Formula so its materials follow any later changes to it.",
        color = palette.textMuted,
        fontSize = 11.5.sp,
      )
    }

    SarimCard {
      if (liveSummary.problems.isNotEmpty()) {
        val names = liveSummary.problems.map { "${it.name} (${if (it.type == "cycle") "circular" else "removed"})" }.distinct()
        Text("Check linked formulas: ${names.joinToString(", ")}", color = palette.red, fontSize = 12.5.sp)
      }
      SummaryLine("Unit Weight", SarimBatchCalc.fmtPrecise(liveSummary.weight, "kg") + " kg")
      SummaryLine("Raw Material Cost per Unit", SarimFormat.formatRs(liveSummary.raw, privacyHidden = hide))
      if (liveSummary.extraCost > 0.0) {
        SummaryLine("Additional cost from linked formulas", SarimFormat.formatRs(liveSummary.extraCost, privacyHidden = hide))
      }
      SummaryLine("Total Cost per Unit", SarimFormat.formatRs(liveSummary.perUnit, privacyHidden = hide), bold = true, valueColor = palette.green)
      SummaryLine("Cost per kg (Sales/Calc)", SarimFormat.formatRs(liveSummary.perKg, privacyHidden = hide))
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (existing != null) {
        SarimDangerButton(
          text = "Delete Formula",
          modifier = Modifier.weight(1f),
          onClick = {
            // Same guards as web deleteFormulaStoreEntry: stores, links, unused units.
            val block = SarimLinkEngine.getFormulaDeleteBlockReason(existing, state.stores, state.formulas)
            if (block != null) {
              warn(block)
              return@SarimDangerButton
            }
            val pastBatches = state.factoryBatches.count { !it.isDeleted && it.formulaId == existing.id }
            val extra = if (pastBatches > 0) "\n\nIt was used for $pastBatches factory batch${if (pastBatches == 1) "" else "es"}. Their recorded costs stay as they are." else ""
            viewModel.showDialog(
              AppDialogRequest(
                title = "Delete Formula",
                body = "Delete this formula from the store?$extra",
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
        modifier = Modifier.weight(1f),
        testTag = "save_formula_btn",
        onClick = {
          val issue = SarimFormulaChecks.formIssue(
            rows.map { SarimFormulaChecks.Row(it.label, it.qtyText, it.refId, it.refType == "FORMULA") }
          )
          if (issue.isNotEmpty()) return@SarimPrimaryButton warn(issue)
          val clean = name.trim().replace(Regex("\\s+"), " ")
          if (clean.isEmpty()) return@SarimPrimaryButton warn("Enter a formula name")
          val validRows = draftIngredients()
          if (validRows.isEmpty()) return@SarimPrimaryButton warn("Add at least one ingredient with quantity")
          if (clean.length > 60) return@SarimPrimaryButton warn("Formula name is too long (at most 60 characters).")

          val costRaw = additionalCostText.trim()
          val cost = if (costRaw.isEmpty()) 0.0 else SarimFormat.parseSafeDouble(costRaw)
          if (cost == null) return@SarimPrimaryButton warn("Additional cost must be a plain number such as 12.5.")
          if (cost < 0.0) return@SarimPrimaryButton warn("Additional cost cannot be less than 0.")
          if (cost > 100_000_000.0) return@SarimPrimaryButton warn("Additional cost cannot be more than 100,000,000.")

          val factorRaw = costFactorText.trim()
          val factor = if (factorRaw.isEmpty()) 1.0 else SarimFormat.parseSafeDouble(factorRaw)
          if (factor == null) return@SarimPrimaryButton warn("Cost factor must be a plain number such as 12.5.")
          if (!(factor > 0.0)) return@SarimPrimaryButton warn("Cost factor must be greater than 0.")
          if (factor > 100.0) return@SarimPrimaryButton warn("Cost factor cannot be more than 100.")

          if (validRows.any { it.qtyKg < 0.001 || it.qtyKg > 1_000_000.0 }) {
            return@SarimPrimaryButton warn("Each ingredient quantity must be between 0.001 and 1,000,000 kg.")
          }
          val dup = SarimFormulaChecks.duplicateName(clean, allFormulas.map { it.id to it.name }, existing?.id)
          if (dup != null) {
            return@SarimPrimaryButton warn("A formula named \"$dup\" already exists. Choose a different name or edit that formula.")
          }
          for (ing in validRows.filter { it.refType == "FORMULA" }) {
            if (formulasMap[ing.refId] == null) {
              return@SarimPrimaryButton warn("The linked formula \"${ing.refName}\" no longer exists. Remove that row.")
            }
            if (existing != null && !FormulaCalculator.canLink(formulasMap, existing.id, ing.refId)) {
              return@SarimPrimaryButton warn("\"${ing.refName}\" already uses this formula, so it can't be linked back.")
            }
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
              viewModel.repository.saveFormula(existing?.id, clean, cost, factor, validRows)
            }
          }
        },
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}
