package com.example.ui.tabs

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
import androidx.compose.ui.graphics.lerp
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.backup.YearCloseMerge
import com.example.data.local.JsonCodecs
import com.example.data.local.ProductionEntryEntity
import com.example.data.model.TimePeriod
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.theme.storeColorForKey
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.ProductionPhotoSharer
import com.example.util.SarimFormat
import com.example.util.ShareOutcome
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.launch

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
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
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
  var sheetEntry by remember { mutableStateOf<ProductionEntryEntity?>(null) }
  var photoSelectMode by remember { mutableStateOf(false) }
  var selectedPhotoIds by remember { mutableStateOf<Set<String>>(emptySet()) }
  var prevDayRef by remember { mutableLongStateOf(state.currentLocalDayEpoch) }

  LaunchedEffect(state.currentLocalDayEpoch) {
    val newDay = state.currentLocalDayEpoch
    if (newDay != prevDayRef) {
      selectedDate = SarimLinkEngine.rolloverDateIfUnedited(selectedDate, prevDayRef, newDay, editingEntryId != null)
      prevDayRef = newDay
    }
  }

  var combinedPeriod by remember { mutableStateOf(TimePeriod.DAILY) }
  var compareMetric by remember { mutableStateOf(CompareMetric.WEIGHT) }
  var analyticsPeriod by remember { mutableStateOf(TimePeriod.WEEKLY) }

  val selectedStore = state.stores.firstOrNull { it.storeKey == selectedStoreKey } ?: state.stores.firstOrNull()
  val linkedFormula = state.formulas.firstOrNull { it.id == selectedStore?.formulaId } ?: state.formulas.firstOrNull()
  val formulaSummary = linkedFormula?.let { snap?.formulaSummaries?.get(it.id) }

  val grossNum = SarimFormat.parseSafeDouble(grossWeight) ?: 0.0
  val contNum = SarimFormat.parseSafeDouble(containerWeight) ?: 0.0
  val netKg = (grossNum - contNum).coerceAtLeast(0.0)
  val unitsNum = SarimFormat.parseSafeDouble(formulaUnits) ?: 0.0
  // Web parity: editing an entry of the same formula keeps its original (frozen) unit cost and gets its own units back as credit.
  val editingOriginal = editingEntryId?.let { id -> state.productionEntries.firstOrNull { it.id == id } }
  val sameFormulaEdit = editingOriginal != null && editingOriginal.formulaId == linkedFormula?.id
  val liveUnitCost = formulaSummary?.totalCostPerUnit ?: 0.0
  val unitCost = if (sameFormulaEdit && editingOriginal!!.formulaUnitCost > 0.0 && netKg > 0.0) editingOriginal.formulaUnitCost else liveUnitCost
  val unitCredit = if (sameFormulaEdit) editingOriginal!!.formulaUnitsUsed else 0.0
  val totalFormulaCost = unitsNum * unitCost
  val costPerKg = if (netKg > 0.0001) totalFormulaCost / netKg else 0.0
  val salePricePerKg = selectedStore?.salePricePerKg ?: 0.0
  val profitPerKg = salePricePerKg - costPerKg
  val unitsAvailable = linkedFormula?.unitsAvailable ?: 0.0
  val insufficientUnits = unitsNum > unitsAvailable + unitCredit + 1e-9

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

    if (topView == ProdTopView.STORE_ENTRY || !state.isAdmin) {
      item {
        SarimCard {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (editingEntryId == null) "New Entry" else "Edit Production Entry", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (editingEntryId != null) {
              val hasEdits = editingOriginal == null ||
                grossWeight != editingOriginal.grossWeightKg.toString() ||
                containerWeight != editingOriginal.containerWeightKg.toString() ||
                formulaUnits != editingOriginal.formulaUnitsUsed.toString() ||
                selectedStoreKey != editingOriginal.storeKey
              TextButton(onClick = {
                viewModel.requestCancelEdit(hasEdits) {
                  editingEntryId = null; grossWeight = ""; containerWeight = "0"; formulaUnits = "1"; attachedPhotos = emptyList()
                }
              }) { Text("Cancel Edit", color = palette.amber) }
            }
          }
          if (editingEntryId != null) {
            SarimCallout("Editing entry · Saving updates the original record.", palette.amber)
          }

          // 1. Formula Units Available
          // Web badge colours: good >= 10, warning < 10, danger <= 0
          val availTone = when {
            unitsAvailable <= 0.0 -> palette.red
            unitsAvailable < 10.0 -> palette.amber
            else -> palette.green
          }
          SarimCallout(
            text = "Formula Units Available: ${SarimFormat.formatQty(unitsAvailable, 2)} units available" + (linkedFormula?.name?.let { " · $it" } ?: ""),
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
            onError = { msg -> viewModel.showDialog(AppDialogRequest("Photo Error", msg, tone = com.example.util.DialogTone.ERROR, cancelLabel = null)) },
          )

          // Formula cost panel (web: prod-formula-cost-block)
          val scheme = MaterialTheme.colorScheme
          Surface(shape = MaterialTheme.shapes.large, color = lerp(scheme.surfaceContainerHigh, palette.red, 0.10f)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Formula Unit Cost:", color = palette.textMuted, fontSize = 12.sp)
                Text(SarimFormat.formatRs(unitCost, true, hideMoney) + "/unit", color = palette.red, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total Formula Cost:", color = palette.textMuted, fontSize = 12.sp)
                Text(SarimFormat.formatRs(totalFormulaCost, true, hideMoney), color = palette.red, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cost per kg:", color = palette.textMuted, fontSize = 12.sp)
                Text(SarimFormat.formatRs(costPerKg, true, hideMoney) + "/kg", color = palette.red, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
              }
            }
          }

          // Result box (web: prod-profit-block)
          SarimCard(containerColor = lerp(scheme.surfaceContainerHigh, palette.emerald, 0.09f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Dynamic Cost Price:", color = palette.textMuted, fontSize = 12.5.sp)
              Text(SarimFormat.formatRs(costPerKg, true, hideMoney) + "/kg", color = palette.red, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Sale Price:", color = palette.textMuted, fontSize = 12.5.sp)
              Text(SarimFormat.formatRs(salePricePerKg, true, hideMoney) + "/kg", color = palette.green, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Profit (per kg):", color = palette.textMuted, fontSize = 12.5.sp)
              Text(SarimFormat.formatRs(if (netKg > 0.0) profitPerKg else 0.0, true, hideMoney), color = palette.emerald, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold))
            }
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
                viewModel.showDialog(AppDialogRequest("Invalid Input", "Container weight must be >= 0 and formula units > 0.", tone = com.example.util.DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (grossNum - contNum <= 0.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Net Weight", "Net production must be greater than zero. Please check weights.", tone = com.example.util.DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              val store = selectedStore
              if (store == null) {
                viewModel.showDialog(AppDialogRequest("Store Required", "Please select a store before saving production.", tone = com.example.util.DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              val formula = linkedFormula
              if (formula == null) {
                viewModel.showDialog(AppDialogRequest("Formula Required", "No formula is linked to ${store.name}. Assign a formula in Store Manager first.", tone = com.example.util.DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (salePricePerKg <= 0.0) {
                viewModel.showDialog(AppDialogRequest("Sale Price Missing", "Please set a sale price for ${store.name} in Store Manager first.", tone = com.example.util.DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (unitCost <= 0.0) {
                viewModel.showDialog(AppDialogRequest("Formula Cost Missing", "This formula has no cost (no ingredients or ingredient costs are zero). Set the formula and ingredient costs first.", tone = com.example.util.DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (insufficientUnits) {
                val shortage = unitsNum - (unitsAvailable + unitCredit)
                viewModel.showDialog(
                  AppDialogRequest(
                    "Not Enough Formula Units",
                    "Not enough formula units at ${store.name}\nAvailable: ${SarimFormat.formatQty(unitsAvailable + unitCredit, 2)} units\nRequested: ${SarimFormat.formatQty(unitsNum, 2)} units\nShortage: ${SarimFormat.formatQty(shortage, 2)} units",
                    tone = com.example.util.DialogTone.WARNING,
                    confirmLabel = "OK",
                    cancelLabel = null,
                  )
                )
                return@SarimPrimaryButton
              }
              viewModel.confirmSave(
"Production Entry",
listOf("Store" to store.name, "Date" to SarimFormat.formatDate(selectedDate), "Gross Weight" to grossWeight, "Container" to containerWeight, "Net Weight" to SarimFormat.formatKg(netKg), "Units" to formulaUnits),
isUpdate = editingEntryId != null,
) {
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
                  actor = state.signedInDisplayName.ifBlank { "Admin" },
                )
              }
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
              val scheme = MaterialTheme.colorScheme
              Surface(shape = MaterialTheme.shapes.large, color = lerp(scheme.surfaceContainerHigh, sColor, 0.10f)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(store.name, color = sColor, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
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

    // Production History Accordion (admin only; entry-only for other roles)
    if (state.isAdmin) item {
      val entriesWithPhotos = remember(state.productionEntries) {
        state.productionEntries.filter { JsonCodecs.decodeStrings(it.photoUrisJson).isNotEmpty() }
      }
      val triggerMultiPhotoShare: (List<ProductionEntryEntity>) -> Unit = { chosen ->
        scope.launch {
          viewModel.postStatus("Preparing captioned photos…")
          ProductionPhotoSharer.shareCaptionedPhotos(context, chosen, hideMoney).fold(
            onSuccess = { (count, outcome) ->
              if (outcome == ShareOutcome.FAILED) {
                viewModel.postStatus("Unable to open the share sheet")
              } else {
                selectedPhotoIds = emptySet()
                photoSelectMode = false
                viewModel.postStatus("Shared $count captioned photo(s) — summary copied to clipboard")
              }
            },
            onFailure = { err ->
              viewModel.postStatus(err.localizedMessage ?: "Failed to prepare captioned photos")
            },
          )
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Production History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (entriesWithPhotos.isNotEmpty()) {
          SarimSecondaryButton(
            text = if (photoSelectMode) "Done Selecting" else "Select Photos (${entriesWithPhotos.size})",
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            testTag = "prod_photo_select_mode_btn",
            onClick = {
              photoSelectMode = !photoSelectMode
              if (!photoSelectMode) selectedPhotoIds = emptySet()
            },
          )
        }
      }
      if (photoSelectMode) {
        Spacer(Modifier.height(6.dp))
        SarimCallout(
          text = "Tap photo entries below to select them, then share captioned images.",
          toneColor = palette.cyan,
        )
        if (selectedPhotoIds.isNotEmpty()) {
          Spacer(Modifier.height(6.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            SarimPrimaryButton(
              text = "Share ${selectedPhotoIds.size} Captioned",
              modifier = Modifier.weight(1f),
              testTag = "prod_share_selected_photos_btn",
              onClick = {
                val chosen = entriesWithPhotos.filter { it.id in selectedPhotoIds }
                triggerMultiPhotoShare(chosen)
              },
            )
            SarimSecondaryButton(
              text = "Clear",
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              testTag = "prod_clear_selected_photos_btn",
              onClick = { selectedPhotoIds = emptySet() },
            )
          }
        }
      }
      Spacer(Modifier.height(6.dp))
      val sourceEntries = if (photoSelectMode) entriesWithPhotos else state.productionEntries
      val records = sourceEntries.map { p ->
        val pCostPerKg = if (p.netWeightKg > 0.0001) p.totalFormulaCost / p.netWeightKg else 0.0
        val pSalePerKg = if (p.netWeightKg > 0.0001) p.totalValue / p.netWeightKg else 0.0
        val pPhotos = JsonCodecs.decodeStrings(p.photoUrisJson)
        val isSelected = p.id in selectedPhotoIds
        val chipLabel = when {
          photoSelectMode && isSelected -> "SELECTED · ${pPhotos.size} PHOTO(S)"
          photoSelectMode -> "TAP TO SELECT · ${pPhotos.size} PHOTO(S)"
          else -> SarimFormat.formatRs(p.totalProfit, false, hideMoney)
        }
        val chipClr = when {
          photoSelectMode && isSelected -> palette.green
          photoSelectMode -> palette.cyan
          p.totalProfit >= 0 -> palette.green
          else -> palette.red
        }
        HistoryRecordDisplay(
          item = p,
          dateEpoch = p.dateEpoch,
          title = p.storeName,
          subtitle = "${p.formulaName} · ${SarimFormat.formatQty(p.formulaUnitsUsed, 2)} units",
          amountText = SarimFormat.formatKg(p.netWeightKg),
          amountColor = storeColorForKey(p.storeKey, palette.isDark),
          chipText = chipLabel,
          chipColor = chipClr,
          detailMetrics = listOf(
            "Gross Weight" to SarimFormat.formatKg(p.grossWeightKg),
            "Container Weight" to SarimFormat.formatKg(p.containerWeightKg),
            "Net Weight" to SarimFormat.formatKg(p.netWeightKg),
            "Formula Units" to SarimFormat.formatQty(p.formulaUnitsUsed, 2),
            "Cost / kg" to SarimFormat.formatRs(pCostPerKg, true, hideMoney),
            "Sale / kg" to SarimFormat.formatRs(pSalePerKg, true, hideMoney),
            "Total Cost" to SarimFormat.formatRs(p.totalFormulaCost, false, hideMoney),
            "Total Value" to SarimFormat.formatRs(p.totalValue, false, hideMoney),
            "Total Profit" to SarimFormat.formatRs(p.totalProfit, false, hideMoney),
          ),
        )
      }
      AccordionHistoryList(
        records = records,
        yearSummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.netWeightKg }) },
        monthSummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.netWeightKg }) },
        daySummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.netWeightKg }) },
        itemKey = { it.id },
        focusRecordKey = state.pendingRecordFocus?.takeIf { it.targetKey == "production" }?.recordId,
        onFocusConsumed = { viewModel.consumePendingRecordFocus() },
        onItemClick = { rec ->
          if (photoSelectMode) {
            selectedPhotoIds = if (rec.item.id in selectedPhotoIds) {
              selectedPhotoIds - rec.item.id
            } else {
              selectedPhotoIds + rec.item.id
            }
          } else {
            sheetEntry = rec.item
          }
        },
      )
    }
  }

  sheetEntry?.let { p ->
    val photos = remember(p.photoUrisJson) { JsonCodecs.decodeStrings(p.photoUrisJson) }
    val pCostPerKg = if (p.netWeightKg > 0.0001) p.totalFormulaCost / p.netWeightKg else 0.0
    val pSalePerKg = if (p.netWeightKg > 0.0001) p.totalValue / p.netWeightKg else 0.0
    SarimRecordBottomSheet(
      title = p.storeName,
      subtitle = "${SarimFormat.formatDate(p.dateEpoch)} · ${p.formulaName}",
      amountText = SarimFormat.formatKg(p.netWeightKg),
      amountColor = storeColorForKey(p.storeKey, palette.isDark),
      badgeText = SarimFormat.formatRs(p.totalProfit, false, hideMoney),
      badgeColor = if (p.totalProfit >= 0) palette.green else palette.red,
      metrics = listOf(
        "Gross Weight" to SarimFormat.formatKg(p.grossWeightKg),
        "Container Weight" to SarimFormat.formatKg(p.containerWeightKg),
        "Net Weight" to SarimFormat.formatKg(p.netWeightKg),
        "Formula Units Used" to SarimFormat.formatQty(p.formulaUnitsUsed, 2),
        "Cost / kg" to SarimFormat.formatRs(pCostPerKg, true, hideMoney),
        "Sale / kg" to SarimFormat.formatRs(pSalePerKg, true, hideMoney),
        "Total Cost" to SarimFormat.formatRs(p.totalFormulaCost, false, hideMoney),
        "Total Value" to SarimFormat.formatRs(p.totalValue, false, hideMoney),
        "Total Profit" to SarimFormat.formatRs(p.totalProfit, false, hideMoney),
      ),
      extraContent = if (photos.isNotEmpty()) {
        {
          SarimSecondaryButton(
            text = "Share ${photos.size} Captioned Photo(s)",
            icon = Icons.Rounded.Share,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            testTag = "prod_share_captioned_btn",
            onClick = {
              sheetEntry = null
              scope.launch {
                viewModel.postStatus("Preparing captioned photos…")
                ProductionPhotoSharer.shareCaptionedPhotos(context, listOf(p), hideMoney).fold(
                  onSuccess = { (count, outcome) ->
                    if (outcome == ShareOutcome.FAILED) {
                      viewModel.postStatus("Unable to open the share sheet")
                    } else {
                      viewModel.postStatus("Shared $count captioned photo(s) — summary copied to clipboard")
                    }
                  },
                  onFailure = { err ->
                    viewModel.postStatus(err.localizedMessage ?: "Failed to prepare captioned photos")
                  },
                )
              }
            },
          )
        }
      } else null,
      onDismiss = { sheetEntry = null },
      extraActionLabel = if (photos.isNotEmpty()) "View ${photos.size} Photo(s)" else null,
      onExtraAction = if (photos.isNotEmpty()) { { viewModel.openPhotoViewer(photos, 0) } } else null,
      onEdit = {
        if (YearCloseMerge.isMergedId(p.id)) {
          viewModel.showDialog(AppDialogRequest("Action Not Allowed", "Merged opening balance records cannot be edited directly.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
        } else {
          val retOwner = if (p.isReturn) state.settlements.firstOrNull { !it.isDeleted && it.returnEntryId == p.id } else null
          if (retOwner != null) {
            viewModel.showDialog(AppDialogRequest("Action Not Allowed", "This return belongs to ${retOwner.repName}'s Calculator settlement (${retOwner.dateStr}). Delete that Calculator settlement to edit or remove it.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
          } else {
            editingEntryId = p.id
            selectedDate = p.dateEpoch
            selectedStoreKey = p.storeKey
            grossWeight = p.grossWeightKg.toString()
            containerWeight = p.containerWeightKg.toString()
            formulaUnits = p.formulaUnitsUsed.toString()
            attachedPhotos = photos
            topView = ProdTopView.STORE_ENTRY
          }
        }
      },
      onDelete = {
        val blockReason = SarimLinkEngine.getProductionDeleteBlockReason(
          entry = p,
          production = state.productionEntries,
          settlements = state.settlements,
          stockTransfers = state.stockTransfers,
          sales = state.sales,
        )
        if (blockReason != null) {
          viewModel.showDialog(AppDialogRequest("Action Not Allowed", blockReason, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
        } else {
          val dateKey = SarimFormat.dateInput(p.dateEpoch)
          val salesOnDate = state.sales.count {
            !it.isDeleted && !it.isRepSale && !it.isRepReturn && !it.isRepTransfer &&
              it.mode == "SALE" && it.storeKey.equals(p.storeKey, ignoreCase = true) &&
              SarimFormat.dateInput(it.dateEpoch) == dateKey
          }
          val msg = buildString {
            if (p.isReturn) {
              append("Remove this stock return record?")
              append("\nStore: ${p.storeName}")
              append("\nDate: $dateKey")
              append("\nQty Returned: ${SarimFormat.formatKg(p.netWeightKg)}")
              if (!p.returnLogId.isNullOrBlank()) {
                append("\n\n↩ This will decrease available stock by ${SarimFormat.formatKg(p.netWeightKg)} on $dateKey (its return log is removed with it).")
              } else {
                append("\n\nNo return log is attached to this record.")
              }
              if (salesOnDate > 0) append("\n\n⚠ $salesOnDate sale(s) exist on this date — those records may be affected.")
            } else {
              append("Permanently delete this production record?")
              append("\nStore: ${p.storeName}")
              append("\nDate: $dateKey")
              append("\nNet Qty: ${SarimFormat.formatKg(p.netWeightKg)}")
              if (p.grossWeightKg > 0.0) {
                append("\nGross / Tare: ${SarimFormat.formatKg(p.grossWeightKg)} / ${SarimFormat.formatKg(p.containerWeightKg)}")
              }
              append("\n\n↩ ${SarimFormat.formatKg(p.netWeightKg)} will be removed from $dateKey inventory.")
              if (salesOnDate > 0) {
                append("\n\n⚠ $salesOnDate sale(s) on this date for ${p.storeName} will remain on record, but available stock will drop.")
              }
            }
            append("\n\nThis cannot be undone.")
          }
          viewModel.showDialog(
            AppDialogRequest(
              title = if (p.isReturn) "Remove Return" else "Delete Production",
              body = msg,
              tone = DialogTone.PERMANENT,
              confirmLabel = if (p.isReturn) "Remove" else "Delete",
              onConfirm = {
                viewModel.launchSafely(
                  errorTitle = "Delete Failed",
                  successMessage = if (p.isReturn) "Return removed" else "Production entry deleted",
                ) {
                  viewModel.repository.deleteProductionEntry(p.id)
                }
              },
            )
          )
        }
      },
    )
  }
}
