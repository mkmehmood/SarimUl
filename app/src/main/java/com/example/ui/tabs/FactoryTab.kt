package com.example.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.backup.YearCloseMerge
import com.example.data.local.FactoryBatchEntity
import com.example.data.local.FormulaEntity
import com.example.data.local.JsonCodecs
import com.example.data.model.StandaloneScreen
import com.example.data.model.TimePeriod
import com.example.data.repository.FormulaCalculator
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.SarimDisplayNames
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

private enum class InventorySort(val label: String) {
  MATERIAL("Material"),
  TOTAL_VALUE("Total Value"),
}

private data class IngredientLine(
  val name: String,
  val kg: Double,
  val cost: Double,
  val missing: Boolean,
  val shortKg: Double,
)

/** Same behaviour as the web picker: nothing -> "No formula", one -> plain label, 2+ -> toggle. */
@Composable
private fun FormulaChoice(
  options: List<FormulaEntity>,
  selected: FormulaEntity?,
  onSelect: (FormulaEntity) -> Unit,
) {
  val palette = LocalSarimPalette.current
  when {
    options.isEmpty() || selected == null -> Text("No formula", color = palette.textMuted, fontSize = 13.sp)
    options.size == 1 -> Text(options.first().name, color = palette.green, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    else -> SarimToggleGroup(
      options = options,
      selected = selected,
      labelOf = { it.name },
      onSelect = onSelect,
    )
  }
}

@Composable
fun FactoryTab(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val snap = state.snapshot
  val hideMoney = state.hideMoneyFigures

  var factoryDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var selectedFormulaId by remember { mutableStateOf("") }
  var unitsText by remember { mutableStateOf("1") }
  var availFormulaId by remember { mutableStateOf("") }
  var invSort by remember { mutableStateOf(InventorySort.MATERIAL) }
  var invAsc by remember { mutableStateOf(true) }
  var summaryPeriod by remember { mutableStateOf(TimePeriod.MONTHLY) }
  var showMaterialsBreakdown by remember { mutableStateOf(false) }
  var editingBatchId by remember { mutableStateOf<String?>(null) }
  var sheetBatch by remember { mutableStateOf<FactoryBatchEntity?>(null) }
  var prevDayRef by remember { mutableLongStateOf(state.currentLocalDayEpoch) }

  LaunchedEffect(state.currentLocalDayEpoch) {
    val newDay = state.currentLocalDayEpoch
    if (newDay != prevDayRef) {
      factoryDate = com.example.data.repository.SarimLinkEngine.rolloverDateIfUnedited(factoryDate, prevDayRef, newDay, editingBatchId != null)
      prevDayRef = newDay
    }
  }

  // Only formulas selected on a store in Store Manager can be produced (matches the web Factory tab).
  val liveFormulas = state.formulas.filter { !it.isDeleted }
  val usedFormulaIds = state.stores.filter { !it.isDeleted && it.formulaId.isNotBlank() }.map { it.formulaId }.toSet()
  val editingFormulaId = editingBatchId?.let { id -> state.factoryBatches.firstOrNull { it.id == id }?.formulaId }
  val entryFormulas = liveFormulas.filter { it.id in usedFormulaIds || it.id == editingFormulaId }
  // Units Available also keeps formulas that still have stock even if no store uses them any more.
  val availFormulas = liveFormulas.filter { it.id in usedFormulaIds || it.unitsAvailable > 0.0 }

  val selectedFormula = entryFormulas.firstOrNull { it.id == selectedFormulaId } ?: entryFormulas.firstOrNull()
  val selectedSummary = selectedFormula?.let { snap?.formulaSummaries?.get(it.id) }
  val unitsInt = (SarimFormat.parseSafeDouble(unitsText)?.toInt() ?: 0).coerceAtLeast(0)
  val batchTotalCost = (selectedSummary?.totalCostPerUnit ?: 0.0) * unitsInt
  val previewUnits = unitsInt.coerceAtLeast(1)
  val ingredientLines = remember(selectedFormula, previewUnits, state.formulas, state.rawMaterials) {
    if (selectedFormula == null) emptyList() else {
      val mats = state.rawMaterials.filter { !it.isDeleted }.associateBy { it.id }
      val formulasById = liveFormulas.associateBy { it.id }
      FormulaCalculator.flattenRawMaterials(selectedFormula, previewUnits.toDouble(), formulasById, mats)
        .map { (matId, kg) ->
          val mat = mats[matId]
          IngredientLine(
            name = mat?.name?.takeIf { it.isNotBlank() } ?: "Material",
            kg = kg,
            cost = kg * (mat?.avgCostPerKg ?: 0.0),
            missing = mat == null,
            shortKg = if (mat != null && mat.stockKg + 1e-6 < kg) kg - mat.stockKg else 0.0,
          )
        }
    }
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item { ShimmerHeading("Factory", fontSizeSp = 26) }

    // 1. New Production Entry
    item {
      SarimCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(if (editingBatchId == null) "New Production Entry" else "Edit Factory Batch", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
          if (editingBatchId != null) {
            val origBatch = state.factoryBatches.firstOrNull { it.id == editingBatchId }
            val hasEdits = origBatch == null ||
              selectedFormulaId != origBatch.formulaId ||
              unitsInt != origBatch.unitsProduced.toInt().coerceAtLeast(1)
            TextButton(onClick = {
              viewModel.requestCancelEdit(hasEdits) { editingBatchId = null; unitsText = "1" }
            }) {
              Text("Cancel Edit", color = palette.amber)
            }
          }
        }
        if (editingBatchId != null) {
          SarimCallout("Editing entry · Saving updates the original record.", palette.amber)
        }
        SarimDatePickerField("Date", factoryDate, onDateSelected = { factoryDate = it }, testTag = "factory_date")

        SarimFieldLabel("Formula")
        FormulaChoice(entryFormulas, selectedFormula) { selectedFormulaId = it.id }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          SarimTextField(
            value = unitsText,
            onValueChange = { unitsText = it.filter { ch -> ch.isDigit() } },
            label = "Units to Produce",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
            testTag = "factory_production_units",
          )
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SarimFieldLabel("Total Cost")
            SarimCallout(
              text = SarimFormat.formatRs(batchTotalCost, true, hideMoney),
              toneColor = palette.red,
              icon = null,
              modifier = Modifier.defaultMinSize(minHeight = SarimFieldDefaults.MinHeight),
            )
          }
        }

        // Formula preview (flattened through sub-formulas, live cost + stock check, like the web tab)
        Surface(
          shape = MaterialTheme.shapes.large,
          color = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, palette.green, if (selectedFormula != null) 0.08f else 0f),
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selectedFormula == null) {
              Text(
                "No formula selected. Choose a formula for a store in Store Manager first.",
                color = palette.textMuted,
                fontSize = 12.5.sp,
              )
            } else {
              Text(
                "${selectedFormula.name} Formula ($previewUnits ${if (previewUnits == 1) "Unit" else "Units"})",
                color = palette.green,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
              )
              if (ingredientLines.isEmpty()) {
                Text("No ingredients configured yet. Edit in Formula Store.", color = palette.textMuted, fontSize = 12.sp)
              } else {
                ingredientLines.forEach { line ->
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                      Text("${line.name} (${SarimFormat.formatKg(line.kg)})", color = palette.textPrimary, fontSize = 12.sp)
                      when {
                        line.missing -> Text("not in inventory", color = palette.red, fontSize = 11.sp)
                        line.shortKg > 0.0 -> Text("short ${SarimFormat.formatKg(line.shortKg)}", color = palette.amber, fontSize = 11.sp)
                      }
                    }
                    Text(
                      SarimFormat.formatRs(line.cost, true, hideMoney),
                      color = palette.textPrimary,
                      style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    )
                  }
                }
                if (selectedFormula.additionalCostPerUnit > 0.0) {
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                      "Additional Cost (${SarimFormat.formatRs(selectedFormula.additionalCostPerUnit, false, hideMoney)} per unit)",
                      color = palette.red,
                      fontSize = 12.sp,
                      modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Text(
                      SarimFormat.formatRs(selectedFormula.additionalCostPerUnit * previewUnits, true, hideMoney),
                      color = palette.red,
                      style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    )
                  }
                }
              }
            }
          }
        }

        SarimPrimaryButton(
          text = if (editingBatchId == null) "Save Factory Production" else "Update Factory Production",
          testTag = "btn_save_factory",
          onClick = {
            if (SarimFormat.isFutureDate(factoryDate)) {
              viewModel.showDialog(AppDialogRequest("Invalid Date", "Future dates are not allowed.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (selectedFormula == null) {
              viewModel.showDialog(AppDialogRequest("No Formula", "Select a formula for a store in Store Manager before saving production.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (unitsInt < 1) {
              viewModel.showDialog(AppDialogRequest("Invalid Units", "Units to produce must be at least 1.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            val editId = editingBatchId
            val origBatch = editId?.let { id -> state.factoryBatches.firstOrNull { it.id == id } }
            val matsById = state.rawMaterials.filter { !it.isDeleted }.associateBy { it.id }
            val formulasById = liveFormulas.associateBy { it.id }
            val restoredByMatId = origBatch?.let { ob ->
              JsonCodecs.decodeFormulaMaterialsUsed(ob.materialsUsedJson)
                .groupBy { it.id }
                .mapValues { (_, list) -> list.sumOf { it.quantity } }
            }.orEmpty()
            val neededFlattened = FormulaCalculator.flattenRawMaterials(selectedFormula, unitsInt.toDouble(), formulasById, matsById)
            val stockShortages = neededFlattened.mapNotNull { (matId, needKg) ->
              val mat = matsById[matId]
              if (mat == null) {
                "• Material ($matId): not in inventory"
              } else {
                val available = mat.stockKg + (restoredByMatId[matId] ?: 0.0)
                if (available + 1e-6 < needKg) {
                  "• ${mat.name}: need ${SarimFormat.formatKg(needKg)}, available ${SarimFormat.formatKg(available)} (short ${SarimFormat.formatKg(needKg - available)})"
                } else null
              }
            }
            if (stockShortages.isNotEmpty()) {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Insufficient Stock!",
                  body = "Not enough raw materials to produce $unitsInt unit(s) of ${selectedFormula.name}:\n\n${stockShortages.joinToString("\n")}",
                  tone = DialogTone.ERROR,
                  confirmLabel = "OK",
                  cancelLabel = null,
                )
              )
              return@SarimPrimaryButton
            }
            viewModel.confirmSave(
              "Production",
              listOf("Formula" to selectedFormula.name, "Units" to unitsInt.toString(), "Date" to SarimFormat.formatDate(factoryDate)),
              isUpdate = editId != null,
            ) {
              viewModel.launchSafely(
                errorTitle = "Batch Save Failed",
                successMessage = if (editId == null) "Factory batch saved for ${selectedFormula.name}" else "Factory batch updated for ${selectedFormula.name}",
                onSuccess = { editingBatchId = null; unitsText = "1" },
              ) {
                viewModel.repository.saveFactoryBatch(factoryDate, selectedFormula, unitsInt.toDouble(), editingId = editId)
              }
            }
          },
        )
      }
    }

    // 2. Units Available Card (admin)
    if (state.isAdmin) {
      item {
        val availFormula = availFormulas.firstOrNull { it.id == availFormulaId } ?: availFormulas.firstOrNull()
        if (availFormula == null) {
          SarimCollapsibleCard(title = "Units Available", tonalAccent = palette.green, initiallyExpanded = false) {
            FormulaChoice(emptyList(), null) {}
          }
          return@item
        }
        val fSum = snap?.formulaSummaries?.get(availFormula.id)
        val unitWeight = fSum?.unitWeightKg ?: 0.0
        val costPerUnit = fSum?.totalCostPerUnit ?: 0.0
        val linkedStore = state.stores.firstOrNull { it.formulaId == availFormula.id }
        val salePrice = linkedStore?.salePricePerKg ?: 400.0
        val estProfitTotal = (availFormula.unitsAvailable * unitWeight * salePrice) - (availFormula.unitsAvailable * costPerUnit)

        SarimCollapsibleCard(
          title = "Units Available",
          subtitle = availFormula.name,
          badgeText = "${SarimFormat.formatQty(availFormula.unitsAvailable, 1)} u",
          badgeColor = palette.green,
          tonalAccent = palette.green,
          initiallyExpanded = false,
        ) {
          FormulaChoice(availFormulas, availFormula) { availFormulaId = it.id }
          SarimFieldLabel("UNITS")
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Available", SarimFormat.formatQty(availFormula.unitsAvailable, 2), Modifier.weight(1f), palette.green)
            SarimStatTile("Used", SarimFormat.formatQty(availFormula.unitsUsed, 2), Modifier.weight(1f), palette.amber)
          }
          SarimFieldLabel("OUTPUT")
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Quantity (kg)", SarimFormat.formatKg(availFormula.unitsAvailable * unitWeight), Modifier.weight(1f), palette.cyan)
            SarimStatTile("Raw Used (kg)", SarimFormat.formatKg(availFormula.unitsUsed * unitWeight), Modifier.weight(1f), palette.purple)
          }
          SarimFieldLabel("COST & PROFIT")
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Per Unit", SarimFormat.formatRs(costPerUnit, false, hideMoney), Modifier.weight(1f), palette.red)
            SarimStatTile("Est. Profit", SarimFormat.formatRs(estProfitTotal, false, hideMoney), Modifier.weight(1f), palette.emerald)
          }
        }
      }

      // 3. Raw Material Inventory
      item {
        val sortedMats = remember(state.rawMaterials, invSort, invAsc) {
          val base = state.rawMaterials
          when (invSort) {
            InventorySort.MATERIAL -> if (invAsc) base.sortedBy { it.name } else base.sortedByDescending { it.name }
            InventorySort.TOTAL_VALUE -> if (invAsc) base.sortedBy { it.stockKg * it.avgCostPerKg } else base.sortedByDescending { it.stockKg * it.avgCostPerKg }
          }
        }
        SarimCollapsibleCard(
          title = "Raw Material Inventory",
          subtitle = "${state.rawMaterials.size} materials",
          badgeText = SarimFormat.formatRs(state.rawMaterials.sumOf { it.stockKg * it.avgCostPerKg }, false, hideMoney),
          badgeColor = palette.cyan,
          tonalAccent = palette.cyan,
          initiallyExpanded = false,
        ) {
          SarimToggleGroup(
            InventorySort.entries,
            invSort,
            { s ->
              if (s == invSort) "${s.label} ${if (invAsc) "▲" else "▼"}" else "${s.label} ⇅"
            },
            { s ->
              if (s == invSort) invAsc = !invAsc else { invSort = s; invAsc = true }
            },
          )
          SarimRecordTable(
            rows = sortedMats.map { mat ->
              val totalVal = mat.stockKg * mat.avgCostPerKg
              val unitsCount = if (mat.conversionFactorKg > 0.001) mat.stockKg / mat.conversionFactorKg else mat.stockKg
              val out = mat.stockKg <= 0.0
              RecordRow(
                key = mat.id,
                name = mat.name,
                showAvatar = false,
                flag = if (out) "Out of stock" else null,
                details = listOf(
                  RecordDetail("${SarimFormat.formatQty(unitsCount, 2)} ${mat.unitName} (${SarimFormat.formatKg(mat.stockKg)})", color = palette.textPrimary, bold = true),
                  RecordDetail("Collected ${SarimFormat.formatKg(mat.collectedKg)}"),
                ),
                amount = SarimFormat.formatRs(totalVal, true, hideMoney),
                amountColor = if (out) palette.red else palette.textPrimary,
                onClick = { viewModel.openStandalone(StandaloneScreen.AddRawMaterial(mat.id)) },
              )
            },
            stats = listOf(
              RecordStat("Total collected weight", SarimFormat.formatKg(state.rawMaterials.sumOf { it.collectedKg })),
              RecordStat("Total inventory value", SarimFormat.formatRs(state.rawMaterials.sumOf { it.stockKg * it.avgCostPerKg }, true, hideMoney)),
            ),
            emptyTitle = "No raw materials yet",
            emptyHint = "Add a material from the menu to start tracking stock.",
          )
        }
      }

      // 4. Performance Summary
      item {
        val cutoff = SarimMetricsEngine.periodStart(summaryPeriod)
        val batches = state.factoryBatches.filter { it.dateEpoch >= cutoff }
        SarimCollapsibleCard(
          title = "Performance Summary",
          subtitle = "${summaryPeriod.label} · ${SarimFormat.formatQty(batches.sumOf { it.unitsProduced }, 1)} u",
          badgeText = SarimFormat.formatRs(batches.sumOf { it.totalCost }, false, hideMoney),
          badgeColor = palette.cyan,
          tonalAccent = palette.cyan,
          initiallyExpanded = false,
        ) {
          SarimToggleGroup(TimePeriod.entries, summaryPeriod, { it.label }, { summaryPeriod = it })
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Total Cost", SarimFormat.formatRs(batches.sumOf { it.totalCost }, false, hideMoney), Modifier.weight(1f), palette.red)
            SarimStatTile("Units Made", SarimFormat.formatQty(batches.sumOf { it.unitsProduced }, 1), Modifier.weight(1f), palette.green)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Output kg", SarimFormat.formatKg(batches.sumOf { it.outputKg }), Modifier.weight(1f), palette.cyan)
            SarimStatTile("Raw Used kg", SarimFormat.formatKg(batches.sumOf { it.rawUsedKg }), Modifier.weight(1f), palette.purple)
          }
          TextButton(
            onClick = { showMaterialsBreakdown = !showMaterialsBreakdown },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Text("MATERIALS BREAKDOWN (${state.rawMaterials.size})", color = palette.cyan, fontWeight = FontWeight.Bold)
          }
          AnimatedVisibility(visible = showMaterialsBreakdown) {
            Surface(
              shape = RoundedCornerShape(18.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.rawMaterials.forEach { m ->
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(m.name, color = palette.textMuted, fontSize = 12.sp)
                    Text("${SarimFormat.formatKg(m.stockKg)} in stock", color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                  }
                }
              }
            }
          }
        }
      }

      // 5. Recent Production Activity Accordion
      item {
        SarimCollapsibleCard(
          title = "Recent Production Activity",
          badgeText = "${state.factoryBatches.size}",
          badgeColor = palette.purple,
          tonalAccent = palette.purple,
          initiallyExpanded = false,
        ) {
          val records = state.factoryBatches.map { b ->
            HistoryRecordDisplay(
              item = b,
              dateEpoch = b.dateEpoch,
              title = b.formulaName,
              subtitle = "${SarimFormat.formatQty(b.unitsProduced, 1)} units · ${SarimFormat.formatKg(b.outputKg)}",
              amountText = SarimFormat.formatRs(b.totalCost, true, hideMoney),
              amountColor = palette.red,
              showDots = true,
              detailMetrics = listOf(
                "Units Produced" to SarimFormat.formatQty(b.unitsProduced, 2),
                "Output kg" to SarimFormat.formatKg(b.outputKg),
                "Raw Used kg" to SarimFormat.formatKg(b.rawUsedKg),
                "Materials Cost" to SarimFormat.formatRs(b.materialsCost, false, hideMoney),
                "Additional Cost" to SarimFormat.formatRs(b.additionalCost, false, hideMoney),
                "Cost / Unit" to SarimFormat.formatRs(b.costPerUnit, false, hideMoney),
              ),
            )
          }
          AccordionHistoryList(
            records = records,
            yearSummary = { items -> "Cost ${SarimFormat.formatRs(items.sumOf { it.item.totalCost }, false, hideMoney)}" },
            monthSummary = { items -> "Cost ${SarimFormat.formatRs(items.sumOf { it.item.totalCost }, false, hideMoney)}" },
            daySummary = { items -> "Cost ${SarimFormat.formatRs(items.sumOf { it.item.totalCost }, false, hideMoney)}" },
            onItemClick = { rec -> sheetBatch = rec.item },
          )
        }
      }
    }
  }

  sheetBatch?.let { b ->
    val usedMats = remember(b.materialsUsedJson) { JsonCodecs.decodeFormulaMaterialsUsed(b.materialsUsedJson) }
    SarimRecordBottomSheet(
      title = b.formulaName,
      subtitle = "${SarimFormat.formatDate(b.dateEpoch)}${if (b.timeStr.isNotBlank()) " · ${b.timeStr}" else ""}",
      amountText = SarimFormat.formatRs(b.totalCost, true, hideMoney),
      amountColor = palette.red,
      metrics = listOf(
        "Units Produced" to SarimFormat.formatQty(b.unitsProduced, 2),
        "Output kg" to SarimFormat.formatKg(b.outputKg),
        "Raw Used kg" to SarimFormat.formatKg(b.rawUsedKg),
        "Materials Cost" to SarimFormat.formatRs(b.materialsCost, false, hideMoney),
        "Additional Cost" to SarimFormat.formatRs(b.additionalCost, false, hideMoney),
        "Cost Per Unit" to SarimFormat.formatRs(b.costPerUnit, false, hideMoney),
      ),
      extraContent = if (usedMats.isNotEmpty()) {
        {
          SarimCollapsibleDropdownSection(
            title = "MATERIALS BREAKDOWN",
            accentColor = palette.cyan,
            countText = "${usedMats.size}",
          ) {
            usedMats.forEach { mat ->
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(SarimDisplayNames.materialName(mat.id, mat.name, state.rawMaterials, state.formulas), color = palette.textMuted, fontSize = 12.sp)
                Text(SarimFormat.formatKg(mat.quantity), color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
              }
            }
          }
        }
      } else null,
      onDismiss = { sheetBatch = null },
      onEdit = {
        val formula = state.formulas.firstOrNull { !it.isDeleted && it.id == b.formulaId }
        val blockReason = when {
          YearCloseMerge.isMergedId(b.id) -> "Merged opening balance records cannot be edited directly."
          else -> SarimLinkEngine.getFactoryBatchDeleteBlockReason(b, formula, state.factoryBatches, state.productionEntries)
            ?.replace("Cannot delete batch:", "Cannot edit batch:")
        }
        if (blockReason != null) {
          viewModel.showDialog(AppDialogRequest("Action Not Allowed", blockReason, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
        } else {
          editingBatchId = b.id
          factoryDate = b.dateEpoch
          selectedFormulaId = b.formulaId
          unitsText = b.unitsProduced.toInt().coerceAtLeast(1).toString()
        }
      },
      deleteLabel = "Delete & Restore",
      onDelete = {
        val formula = state.formulas.firstOrNull { !it.isDeleted && it.id == b.formulaId }
        val blockReason = when {
          YearCloseMerge.isMergedId(b.id) -> "Merged opening balance records cannot be deleted."
          else -> SarimLinkEngine.getFactoryBatchDeleteBlockReason(b, formula, state.factoryBatches, state.productionEntries)
        }
        if (blockReason != null) {
          viewModel.showDialog(AppDialogRequest("Action Not Allowed", blockReason, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
        } else {
          val restoreLines = usedMats.joinToString("\n") { mat ->
            val matName = SarimDisplayNames.materialName(mat.id, mat.name, state.rawMaterials, state.formulas)
            " • $matName: ${SarimFormat.formatKg(mat.quantity)} restored"
          }
          val msg = buildString {
            append("Delete this factory production batch permanently?")
            append("\nFormula: ${b.formulaName}")
            append("\nDate: ${SarimFormat.dateInput(b.dateEpoch)}")
            append("\nUnits Produced: ${SarimFormat.formatQty(b.unitsProduced, 1)}")
            if (b.totalCost > 0.0) append("\nTotal Cost: ${SarimFormat.formatRs(b.totalCost, false, hideMoney)}")
            if (restoreLines.isNotBlank()) {
              append("\n\n↩ Raw materials restored to inventory:\n$restoreLines")
            } else {
              append("\n\n↩ Raw materials used in this batch will be restored to inventory.")
            }
            append("\n\n⚠ Sales already made from this batch will NOT be reversed — but available stock will change.")
            append("\n\nThis cannot be undone.")
          }
          viewModel.showDialog(
            AppDialogRequest(
              title = "Delete Factory Production",
              body = msg,
              tone = DialogTone.PERMANENT,
              confirmLabel = "Delete",
              onConfirm = {
                viewModel.launchSafely(
                  errorTitle = "Delete Failed",
                  successMessage = "Deleted factory batch",
                ) {
                  viewModel.repository.deleteFactoryBatch(b.id)
                }
              },
            )
          )
        }
      },
    )
  }
}
