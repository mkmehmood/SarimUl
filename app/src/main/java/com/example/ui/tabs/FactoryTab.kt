package com.example.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.JsonCodecs
import com.example.data.model.StandaloneScreen
import com.example.data.model.TimePeriod
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

private enum class InventorySort(val label: String) {
  MATERIAL("Material"),
  TOTAL_VALUE("Total Value"),
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
  var selectedFormulaId by remember(state.formulas) { mutableStateOf(state.formulas.firstOrNull()?.id ?: "") }
  var unitsText by remember { mutableStateOf("1") }
  var availFormulaId by remember(state.formulas) { mutableStateOf(state.formulas.firstOrNull()?.id ?: "") }
  var invSort by remember { mutableStateOf(InventorySort.MATERIAL) }
  var invAsc by remember { mutableStateOf(true) }
  var summaryPeriod by remember { mutableStateOf(TimePeriod.MONTHLY) }
  var showMaterialsBreakdown by remember { mutableStateOf(false) }

  val selectedFormula = state.formulas.firstOrNull { it.id == selectedFormulaId } ?: state.formulas.firstOrNull()
  val selectedSummary = selectedFormula?.let { snap?.formulaSummaries?.get(it.id) }
  val unitsInt = (SarimFormat.parseSafeDouble(unitsText)?.toInt() ?: 0).coerceAtLeast(0)
  val batchTotalCost = (selectedSummary?.totalCostPerUnit ?: 0.0) * unitsInt

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item { ShimmerHeading("Factory", fontSizeSp = 26) }

    // 1. New Production Entry
    item {
      SarimCard {
        Text("New Production Entry", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        SarimDatePickerField("Date", factoryDate, onDateSelected = { factoryDate = it }, testTag = "factory_date")

        if (state.formulas.isNotEmpty()) {
          SarimFieldLabel("Formula")
          SarimToggleGroup(
            options = state.formulas,
            selected = selectedFormula ?: state.formulas.first(),
            labelOf = { it.name },
            onSelect = { selectedFormulaId = it.id },
          )
        }

        SarimTextField(
          value = unitsText,
          onValueChange = { unitsText = it.filter { ch -> ch.isDigit() } },
          label = "Units to Produce",
          keyboardType = KeyboardType.Number,
          testTag = "factory_production_units",
        )

        SarimCallout(
          text = "Total Cost: ${SarimFormat.formatRs(batchTotalCost, true, hideMoney)}",
          toneColor = palette.red,
        )

        // Formula preview
        Surface(shape = RoundedCornerShape(16.dp), color = palette.surfaceElevated, border = BorderStroke(1.dp, palette.border)) {
          Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selectedFormula == null) {
              Text("Select a formula...", color = palette.textMuted, fontSize = 12.5.sp)
            } else {
              Text("${selectedFormula.name} · ${SarimFormat.formatQty(selectedFormula.unitsAvailable, 1)} avail", color = palette.green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              val ings = JsonCodecs.decodeIngredients(selectedFormula.ingredientsJson)
              if (ings.isEmpty()) {
                Text("No ingredients configured yet. Edit in Formula Store.", color = palette.textMuted, fontSize = 12.sp)
              } else {
                ings.forEach { ing ->
                  Text("• ${ing.refName}: ${SarimFormat.formatKg(ing.qtyKg * unitsInt.coerceAtLeast(1))}", color = palette.textPrimary, fontSize = 12.sp)
                }
              }
              Text(
                "Additional Cost: ${SarimFormat.formatRs(selectedFormula.additionalCostPerUnit * unitsInt.coerceAtLeast(1), true, hideMoney)}",
                color = palette.red,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }
        }

        SarimPrimaryButton(
          text = "Save Factory Production",
          testTag = "btn_save_factory",
          onClick = {
            if (SarimFormat.isFutureDate(factoryDate)) {
              viewModel.showDialog(AppDialogRequest("Invalid Date", "Future dates are not allowed.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (unitsInt < 1 || selectedFormula == null) {
              viewModel.showDialog(AppDialogRequest("Invalid Units", "Units to produce must be at least 1.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            viewModel.launchSafely(
              errorTitle = "Batch Save Failed",
              successMessage = "Factory batch saved for ${selectedFormula.name}",
              onSuccess = { unitsText = "1" },
            ) {
              viewModel.repository.saveFactoryBatch(factoryDate, selectedFormula, unitsInt.toDouble())
            }
          },
        )
      }
    }

    // 2. Units Available Card (admin)
    if (state.isAdmin && state.formulas.isNotEmpty()) {
      item {
        val availFormula = state.formulas.firstOrNull { it.id == availFormulaId } ?: state.formulas.first()
        val fSum = snap?.formulaSummaries?.get(availFormula.id)
        val unitWeight = fSum?.unitWeightKg ?: 0.0
        val costPerUnit = fSum?.totalCostPerUnit ?: 0.0
        val linkedStore = state.stores.firstOrNull { it.formulaId == availFormula.id }
        val salePrice = linkedStore?.salePricePerKg ?: 400.0
        val estProfitTotal = (availFormula.unitsAvailable * unitWeight * salePrice) - (availFormula.unitsAvailable * costPerUnit)

        SarimCard {
          Text("Units Available", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          SarimToggleGroup(
            state.formulas,
            availFormula,
            { it.name },
            { availFormulaId = it.id },
          )
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
        SarimCard {
          Text("Raw Material Inventory", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sortedMats.forEach { mat ->
              val totalVal = mat.stockKg * mat.avgCostPerKg
              val unitsCount = if (mat.conversionFactorKg > 0.001) mat.stockKg / mat.conversionFactorKg else mat.stockKg
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = palette.surfaceElevated,
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(16.dp))
                  .clickable { viewModel.openStandalone(StandaloneScreen.AddRawMaterial(mat.id)) },
              ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(mat.name.uppercase(), color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${SarimFormat.formatQty(unitsCount, 2)} ${mat.unitName} (${SarimFormat.formatKg(mat.stockKg)})", color = palette.cyan, fontSize = 11.5.sp)
                    Text("Collected ${SarimFormat.formatKg(mat.collectedKg)}", color = palette.textMuted, fontSize = 11.sp)
                  }
                  Text(SarimFormat.formatRs(totalVal, true, hideMoney), color = palette.green, style = TabularNumberStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold))
                }
              }
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Total collected weight", SarimFormat.formatKg(state.rawMaterials.sumOf { it.collectedKg }), Modifier.weight(1f), palette.cyan)
            SarimStatTile("Total inventory value", SarimFormat.formatRs(state.rawMaterials.sumOf { it.stockKg * it.avgCostPerKg }, true, hideMoney), Modifier.weight(1f), palette.green)
          }
        }
      }

      // 4. Performance Summary
      item {
        val cutoff = SarimMetricsEngine.periodStart(summaryPeriod)
        val batches = state.factoryBatches.filter { it.dateEpoch >= cutoff }
        SarimCard {
          Text("Performance Summary", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          SarimToggleGroup(TimePeriod.entries, summaryPeriod, { it.label }, { summaryPeriod = it })
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Total Cost", SarimFormat.formatRs(batches.sumOf { it.totalCost }, false, hideMoney), Modifier.weight(1f), palette.red)
            SarimStatTile("Units Made", SarimFormat.formatQty(batches.sumOf { it.unitsProduced }, 1), Modifier.weight(1f), palette.green)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Output kg", SarimFormat.formatKg(batches.sumOf { it.outputKg }), Modifier.weight(1f), palette.cyan)
            SarimStatTile("Raw Used kg", SarimFormat.formatKg(batches.sumOf { it.rawUsedKg }), Modifier.weight(1f), palette.purple)
          }
          TextButton(onClick = { showMaterialsBreakdown = !showMaterialsBreakdown }) {
            Text("MATERIALS BREAKDOWN (${state.rawMaterials.size})", color = palette.cyan)
          }
          AnimatedVisibility(visible = showMaterialsBreakdown) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              state.rawMaterials.forEach { m ->
                Text("• ${m.name}: ${SarimFormat.formatKg(m.stockKg)} in stock", color = palette.textMuted, fontSize = 12.sp)
              }
            }
          }
        }
      }

      // 5. Recent Production Activity Accordion
      item {
        Text("Recent Production Activity", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        val records = state.factoryBatches.map { b ->
          HistoryRecordDisplay(
            item = b,
            dateEpoch = b.dateEpoch,
            title = b.formulaName,
            subtitle = "${SarimFormat.formatQty(b.unitsProduced, 1)} units · ${SarimFormat.formatKg(b.outputKg)}",
            amountText = SarimFormat.formatRs(b.totalCost, true, hideMoney),
            amountColor = palette.red,
          )
        }
        AccordionHistoryList(
          records = records,
          yearSummary = { items -> "${items.size} entries" },
          monthSummary = { items -> "${items.size} entries · Cost ${SarimFormat.formatRs(items.sumOf { it.item.totalCost }, false, hideMoney)}" },
          daySummary = { items -> "${items.size} batches" },
        ) { rec ->
          val b = rec.item
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SarimStatTile("Materials", SarimFormat.formatRs(b.materialsCost, false, hideMoney), Modifier.weight(1f), palette.red)
              SarimStatTile("Extra", SarimFormat.formatRs(b.additionalCost, false, hideMoney), Modifier.weight(1f), palette.amber)
              SarimStatTile("Per Unit", SarimFormat.formatRs(b.costPerUnit, false, hideMoney), Modifier.weight(1f), palette.cyan)
            }
            SarimDangerButton(
              text = "Delete Batch",
              onClick = {
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Delete Factory Batch",
                    body = "Restore raw materials and move this ${b.formulaName} batch to Recycle Bin?",
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
              },
            )
          }
        }
      }
    }
  }
}
