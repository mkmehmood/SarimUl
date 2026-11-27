package com.example.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.JsonCodecs
import com.example.data.local.ProductionEntryEntity
import com.example.data.model.TimePeriod
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.theme.storeColorForKey
import com.example.util.AppDialogRequest
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

private enum class ProdTopView(val label: String) {
  COMBINED("Combined"),
  STORE_ENTRY("Store Entry"),
}

private enum class CompareMetric(val label: String) {
  WEIGHT("Weight (kg)"),
  VALUE("Total Value"),
  COST("Total Cost"),
  PROFIT("Net Profit"),
}

@Composable
fun ProductionTab(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val snap = state.snapshot
  val hideMoney = state.hideMoneyFigures

  var topView by remember { mutableStateOf(ProdTopView.STORE_ENTRY) }
  var selectedDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var grossWeight by remember { mutableStateOf("") }
  var containerWeight by remember { mutableStateOf("0") }
  var selectedStoreKey by remember(state.stores) { mutableStateOf(state.stores.firstOrNull()?.storeKey ?: "zubair") }
  var formulaUnits by remember { mutableStateOf("1") }
  var attachedPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
  var editingEntryId by remember { mutableStateOf<String?>(null) }

  var combinedPeriod by remember { mutableStateOf(TimePeriod.MONTHLY) }
  var compareMetric by remember { mutableStateOf(CompareMetric.WEIGHT) }
  var analyticsPeriod by remember { mutableStateOf(TimePeriod.MONTHLY) }

  val selectedStore = state.stores.firstOrNull { it.storeKey == selectedStoreKey } ?: state.stores.firstOrNull()
  val linkedFormula = state.formulas.firstOrNull { it.id == selectedStore?.formulaId } ?: state.formulas.firstOrNull()
  val formulaSummary = linkedFormula?.let { snap?.formulaSummaries?.get(it.id) }

  val grossNum = SarimFormat.parseSafeDouble(grossWeight) ?: 0.0
  val contNum = SarimFormat.parseSafeDouble(containerWeight) ?: 0.0
  val netKg = (grossNum - contNum).coerceAtLeast(0.0)
  val unitsNum = SarimFormat.parseSafeDouble(formulaUnits) ?: 0.0
  val unitCost = formulaSummary?.totalCostPerUnit ?: 0.0
  val totalFormulaCost = unitsNum * unitCost
  val costPerKg = if (netKg > 0.0001) totalFormulaCost / netKg else 0.0
  val salePricePerKg = selectedStore?.salePricePerKg ?: 0.0
  val profitPerKg = salePricePerKg - costPerKg
  val unitsAvailable = linkedFormula?.unitsAvailable ?: 0.0
  val insufficientUnits = unitsNum > unitsAvailable && editingEntryId == null

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Header
    item {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ShimmerHeading("Manufacturing", fontSizeSp = 26)
        if (state.isAdmin) {
          SarimToggleGroup(
            options = ProdTopView.entries,
            selected = topView,
            labelOf = { it.label },
            onSelect = { topView = it },
            testTagPrefix = "prod_view",
          )
        }
      }
    }

    if (topView == ProdTopView.STORE_ENTRY) {
      item {
        SarimCard {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (editingEntryId == null) "New Entry" else "Edit Production Entry", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (editingEntryId != null) {
              TextButton(onClick = {
                editingEntryId = null; grossWeight = ""; containerWeight = "0"; formulaUnits = "1"; attachedPhotos = emptyList()
              }) { Text("Cancel Edit", color = palette.amber) }
            }
          }

          // 1. Formula Units Available
          val availTone = if (unitsAvailable > 0.001) palette.amber else palette.red
          SarimCallout(
            text = "${SarimFormat.formatQty(unitsAvailable, 2)} units available · ${linkedFormula?.name ?: "No Formula"}",
            toneColor = availTone,
          )

          // 2. Date
          SarimDatePickerField("Select Date", selectedDate, onDateSelected = { selectedDate = it }, testTag = "prod_date")

          // 3-4. Gross & Container weight
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SarimTextField(
              value = grossWeight,
              onValueChange = { grossWeight = it },
              label = "Gross Weight (kg)",
              placeholder = "0.000",
              suffixText = "kg",
              keyboardType = KeyboardType.Decimal,
              modifier = Modifier.weight(1f),
              testTag = "gross_wt",
            )
            SarimTextField(
              value = containerWeight,
              onValueChange = { containerWeight = it },
              label = "Container Weight (kg)",
              placeholder = "0.000",
              suffixText = "kg",
              keyboardType = KeyboardType.Decimal,
              modifier = Modifier.weight(1f),
              testTag = "cont_wt",
            )
          }

          // 5-6. Net Production + Dynamic Cost Value
          SarimTextField(
            value = SarimFormat.formatQty(netKg, 3),
            onValueChange = {},
            label = "Net Production (kg)",
            suffixText = "kg",
            readOnly = true,
            dashedReadOnly = true,
            testTag = "net_wt",
          )
          Text(
            "Dynamic Cost Value: ${SarimFormat.formatRs(totalFormulaCost, true, hideMoney)}",
            color = palette.red,
            style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
          )

          // 7. Select Store
          if (state.stores.isNotEmpty()) {
            SarimFieldLabel("Select Store")
            SarimToggleGroup(
              options = state.stores,
              selected = selectedStore ?: state.stores.first(),
              labelOf = { it.name },
              onSelect = { selectedStoreKey = it.storeKey },
              colorOf = { storeColorForKey(it.storeKey, palette.isDark) },
              testTagPrefix = "prod_store",
            )
          }

          // 8-9. Formula Units Used & Sale Price
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            SarimTextField(
              value = formulaUnits,
              onValueChange = { formulaUnits = it },
              label = "Formula Units Used",
              keyboardType = KeyboardType.Decimal,
              modifier = Modifier.weight(1f),
              testTag = "formula_units",
            )
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
              SarimFieldLabel("Sale Price (per kg)")
              Spacer(Modifier.height(8.dp))
              Text(
                "${SarimFormat.formatSouthAsian(salePricePerKg, 0)}/kg",
                color = palette.green,
                style = TabularNumberStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
              )
              Spacer(Modifier.height(6.dp))
            }
          }
          Text("Units of factory mixture consumed", color = palette.textMuted, fontSize = 11.sp)

          // 10. Photo
          PhotoAttachmentBar(
            photoUris = attachedPhotos,
            onAddUris = { attachedPhotos = attachedPhotos + it },
            onClearAll = { attachedPhotos = emptyList() },
            onViewPhotos = { idx -> viewModel.openPhotoViewer(attachedPhotos, idx) },
            onError = { msg -> viewModel.postStatus(msg) },
          )

          // Formula cost panel
          Surface(shape = RoundedCornerShape(16.dp), color = palette.surfaceElevated, border = BorderStroke(1.dp, palette.red.copy(alpha = 0.35f))) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Formula Unit Cost (per unit)", color = palette.textMuted, fontSize = 12.sp)
                Text(SarimFormat.formatRs(unitCost, true, hideMoney), color = palette.red, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total Formula Cost", color = palette.textMuted, fontSize = 12.sp)
                Text(SarimFormat.formatRs(totalFormulaCost, true, hideMoney), color = palette.red, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cost per kg", color = palette.textMuted, fontSize = 12.sp)
                Text(SarimFormat.formatRs(costPerKg, true, hideMoney), color = palette.red, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
              }
            }
          }

          // Result box
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Cost / kg", SarimFormat.formatRs(costPerKg, false, hideMoney), Modifier.weight(1f), palette.red)
            SarimStatTile("Sale / kg", SarimFormat.formatRs(salePricePerKg, false, hideMoney), Modifier.weight(1f), palette.green)
            SarimStatTile("Profit / kg", SarimFormat.formatRs(profitPerKg, false, hideMoney), Modifier.weight(1f), palette.emerald)
          }

          if (insufficientUnits) {
            SarimCallout("Insufficient formula units available! Please reduce formula units used.", palette.amber)
          }

          SarimPrimaryButton(
            text = if (editingEntryId == null) "Save Production" else "Update Production",
            testTag = "btn_save_production",
            onClick = {
              if (SarimFormat.isFutureDate(selectedDate)) {
                viewModel.showDialog(AppDialogRequest("Invalid Date", "Future dates are not allowed.", confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (grossNum !in 0.001..1_000_000.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Weight", "Gross weight must be between 0.001 and 1,000,000 kg.", confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (contNum < 0.0 || unitsNum <= 0.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Input", "Container weight must be >= 0 and formula units > 0.", confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              val store = selectedStore ?: return@SarimPrimaryButton
              val formula = linkedFormula ?: return@SarimPrimaryButton
              viewModel.launchSafely(
                errorTitle = "Production Save Failed",
                successMessage = "Production entry saved",
                onSuccess = {
                  editingEntryId = null
                  grossWeight = ""
                  containerWeight = "0"
                  formulaUnits = "1"
                  attachedPhotos = emptyList()
                },
              ) {
                viewModel.repository.saveProductionEntry(
                  editingId = editingEntryId,
                  dateEpoch = selectedDate,
                  store = store,
                  formula = formula,
                  grossKg = grossNum,
                  containerKg = contNum,
                  unitsUsed = unitsNum,
                  unitCost = unitCost,
                  photoUris = attachedPhotos,
                )
              }
            },
          )
        }
      }
    } else {
      // Combined View
      item {
        val cutoff = SarimMetricsEngine.periodStart(combinedPeriod)
        val filteredProd = state.productionEntries.filter { it.dateEpoch >= cutoff }
        SarimCard {
          Text("All Stores Overview", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          SarimToggleGroup(TimePeriod.entries, combinedPeriod, { it.label }, { combinedPeriod = it })
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.stores.forEach { store ->
              val items = filteredProd.filter { it.storeKey == store.storeKey }
              val sColor = storeColorForKey(store.storeKey, palette.isDark)
              Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceElevated, border = BorderStroke(1.dp, sColor.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Text(store.name, color = sColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SarimStatTile("Weight", SarimFormat.formatKg(items.sumOf { it.netWeightKg }), Modifier.weight(1f), sColor)
                    SarimStatTile("Value", SarimFormat.formatRs(items.sumOf { it.totalValue }, false, hideMoney), Modifier.weight(1f), palette.green)
                  }
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SarimStatTile("Cost", SarimFormat.formatRs(items.sumOf { it.totalFormulaCost }, false, hideMoney), Modifier.weight(1f), palette.red)
                    SarimStatTile("Net Profit", SarimFormat.formatRs(items.sumOf { it.totalProfit }, false, hideMoney), Modifier.weight(1f), palette.emerald)
                  }
                }
              }
            }
          }
        }
      }
      item {
        val cutoff = SarimMetricsEngine.periodStart(combinedPeriod)
        val filteredProd = state.productionEntries.filter { it.dateEpoch >= cutoff }
        SarimCard {
          Text("Compare Stores By", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          SarimToggleGroup(CompareMetric.entries, compareMetric, { it.label }, { compareMetric = it })
          val labels = state.stores.map { it.name }
          val vals = state.stores.map { store ->
            val items = filteredProd.filter { it.storeKey == store.storeKey }
            when (compareMetric) {
              CompareMetric.WEIGHT -> items.sumOf { it.netWeightKg }
              CompareMetric.VALUE -> items.sumOf { it.totalValue }
              CompareMetric.COST -> items.sumOf { it.totalFormulaCost }
              CompareMetric.PROFIT -> items.sumOf { it.totalProfit.coerceAtLeast(0.0) }
            }
          }
          val colors = state.stores.map { storeColorForKey(it.storeKey, palette.isDark) }
          ColumnBarChart(labels, vals, colors)
        }
      }
    }

    // Analytics card (below both views)
    if (state.isAdmin) {
      item {
        val cutoff = SarimMetricsEngine.periodStart(analyticsPeriod)
        val filtered = state.productionEntries.filter { it.dateEpoch >= cutoff }
        SarimCard {
          Text("Analytics", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          SarimToggleGroup(
            listOf(TimePeriod.WEEKLY, TimePeriod.MONTHLY, TimePeriod.YEARLY, TimePeriod.ALL_TIME),
            analyticsPeriod,
            { it.label },
            { analyticsPeriod = it },
          )
          val labels = state.stores.map { it.name }
          val vals = state.stores.map { s -> filtered.filter { it.storeKey == s.storeKey }.sumOf { it.netWeightKg } }
          val colors = state.stores.map { storeColorForKey(it.storeKey, palette.isDark) }
          ColumnBarChart(labels, vals, colors)
          DonutShareChart(
            slices = state.stores.mapIndexed { idx, s -> Triple(s.name, vals[idx], colors[idx]) },
            centerLabel = "${SarimFormat.formatQty(vals.sum(), 1)} kg",
          )
        }
      }
    }

    // Production History Accordion
    item {
      Text("Production History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      Spacer(Modifier.height(6.dp))
      val records = state.productionEntries.map { p ->
        HistoryRecordDisplay(
          item = p,
          dateEpoch = p.dateEpoch,
          title = p.storeName,
          subtitle = "${p.formulaName} · ${SarimFormat.formatQty(p.formulaUnitsUsed, 2)} units",
          amountText = SarimFormat.formatKg(p.netWeightKg),
          amountColor = storeColorForKey(p.storeKey, palette.isDark),
          chipText = SarimFormat.formatRs(p.totalProfit, false, hideMoney),
          chipColor = if (p.totalProfit >= 0) palette.green else palette.red,
        )
      }
      AccordionHistoryList(
        records = records,
        yearSummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.netWeightKg }) },
        monthSummary = { items -> "${items.size} entries · ${SarimFormat.formatKg(items.sumOf { it.item.netWeightKg })}" },
        daySummary = { items -> "${items.size} · ${SarimFormat.formatKg(items.sumOf { it.item.netWeightKg })}" },
      ) { rec ->
        val p: ProductionEntryEntity = rec.item
        val photos = JsonCodecs.decodeStrings(p.photoUrisJson)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimStatTile("Gross", SarimFormat.formatKg(p.grossWeightKg), Modifier.weight(1f))
          SarimStatTile("Cost", SarimFormat.formatRs(p.totalFormulaCost, false, hideMoney), Modifier.weight(1f), palette.red)
          SarimStatTile("Value", SarimFormat.formatRs(p.totalValue, false, hideMoney), Modifier.weight(1f), palette.green)
        }
        if (photos.isNotEmpty()) {
          TextButton(onClick = { viewModel.openPhotoViewer(photos, 0) }) {
            Text("View ${photos.size} photo(s)", color = palette.cyan)
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimSecondaryButton(
            text = "Edit",
            icon = Icons.Rounded.Edit,
            modifier = Modifier.weight(1f),
            onClick = {
              editingEntryId = p.id
              selectedDate = p.dateEpoch
              selectedStoreKey = p.storeKey
              grossWeight = p.grossWeightKg.toString()
              containerWeight = p.containerWeightKg.toString()
              formulaUnits = p.formulaUnitsUsed.toString()
              attachedPhotos = photos
              topView = ProdTopView.STORE_ENTRY
            },
          )
          SarimDangerButton(
            text = "Delete",
            icon = Icons.Rounded.Delete,
            modifier = Modifier.weight(1f),
            onClick = {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Delete Production Entry?",
                  body = "Store: ${p.storeName}\nNet Weight: ${SarimFormat.formatKg(p.netWeightKg)}\nMoved to Recycle Bin for 90 days.",
                  confirmLabel = "Delete",
                  onConfirm = {
                    viewModel.launchSafely(
                      errorTitle = "Delete Failed",
                      successMessage = "Production entry deleted",
                    ) {
                      viewModel.repository.deleteProductionEntry(p.id)
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
