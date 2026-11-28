package com.example.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.backup.YearCloseMerge
import com.example.data.local.CalculatorSettlementEntity
import com.example.data.local.JsonCodecs
import com.example.data.model.LineItemBreakdown
import com.example.data.model.TimePeriod
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.CalcIndMetric
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlin.math.abs

private enum class CompChartMetric(val label: String) {
  NET_PROFIT("Net Profit (Value)"),
  GROSS_REV("Gross Revenue (Value)"),
  QTY_SOLD("Quantity Sold"),
}

private enum class ReturnTargetMode(val label: String) {
  RETURN("Return to Store"),
  TRANSFER("Transfer to Rep"),
}

@Composable
fun CalculatorTab(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val snap = state.snapshot
  val hideMoney = state.hideMoneyFigures

  val reps = snap?.repsList ?: emptyList()
  val selectorOptions = remember(reps) { listOf("COMPARISON") + reps }
  var selectedRepOption by remember { mutableStateOf("COMPARISON") }

  var fromDate by remember { mutableLongStateOf(SarimFormat.daysAgo(30)) }
  var toDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var manualFromOverride by remember { mutableStateOf(false) }

  // Comparison state
  var compPeriod by remember { mutableStateOf(TimePeriod.ALL_TIME) }
  var compMetric by remember { mutableStateOf(CompChartMetric.NET_PROFIT) }

  // Individual state
  var sharedQtyText by remember { mutableStateOf("0") }
  var returnTargetMode by remember { mutableStateOf(ReturnTargetMode.RETURN) }
  var returnStoreKey by remember(state.stores) { mutableStateOf(state.stores.firstOrNull()?.storeKey ?: "zubair") }
  var transferTargetRep by remember { mutableStateOf("") }
  var creditSalesQtyText by remember { mutableStateOf("0") }
  var commissionPerUnitText by remember { mutableStateOf("0") }
  var commissionItems by remember { mutableStateOf<List<LineItemBreakdown>>(emptyList()) }
  var showCommissionPopup by remember { mutableStateOf(false) }

  var recoveredCreditText by remember { mutableStateOf("0") }
  var expenseItems by remember { mutableStateOf<List<LineItemBreakdown>>(emptyList()) }
  var showExpensesPopup by remember { mutableStateOf(false) }
  var receivedCashText by remember { mutableStateOf("") }
  var addToLedger by remember { mutableStateOf(true) }

  var showExpensesBreakdown by remember { mutableStateOf(false) }
  var showCommissionBreakdown by remember { mutableStateOf(false) }
  var overviewPeriod by remember { mutableStateOf(TimePeriod.ALL_TIME) }
  var indChartPeriod by remember { mutableStateOf(TimePeriod.WEEKLY) }
  var indChartMetric by remember { mutableStateOf(CalcIndMetric.NET_PROFIT) }
  var calcHistSearch by remember { mutableStateOf("") }
  var prevDayRef by remember { mutableLongStateOf(state.currentLocalDayEpoch) }
  var editingSettlementId by remember { mutableStateOf<String?>(null) }
  var sheetSettlement by remember { mutableStateOf<CalculatorSettlementEntity?>(null) }

  LaunchedEffect(state.currentLocalDayEpoch) {
    val newDay = state.currentLocalDayEpoch
    if (newDay != prevDayRef) {
      toDate = SarimLinkEngine.rolloverDateIfUnedited(toDate, prevDayRef, newDay, editingSettlementId != null)
      if (!manualFromOverride && editingSettlementId == null) {
        fromDate = SarimLinkEngine.rolloverDateIfUnedited(fromDate, prevDayRef, newDay, false)
      }
      prevDayRef = newDay
    }
  }

  val isComparison = selectedRepOption == "COMPARISON"
  val otherReps = remember(reps, selectedRepOption) {
    reps.filter { !it.equals(selectedRepOption, ignoreCase = true) }
  }
  val fallbackStorePrice = state.stores.firstOrNull()?.salePricePerKg ?: 400.0
  val cycle = remember(selectedRepOption, state.sales, state.settlements, fromDate, toDate, manualFromOverride, editingSettlementId, fallbackStorePrice) {
    if (isComparison) null
    else SarimLinkEngine.getCalcCycleSelection(
      repName = selectedRepOption,
      sales = state.sales,
      settlements = state.settlements,
      manualFromEpoch = if (manualFromOverride || editingSettlementId != null) fromDate else null,
      toEpoch = toDate,
      fallbackUnitPrice = fallbackStorePrice,
      excludeCalcId = editingSettlementId,
    )
  }

  LaunchedEffect(selectedRepOption) {
    if (editingSettlementId == null) {
      manualFromOverride = false
      returnTargetMode = ReturnTargetMode.RETURN
      transferTargetRep = reps.firstOrNull { !it.equals(selectedRepOption, ignoreCase = true) } ?: ""
    }
  }

  LaunchedEffect(selectedRepOption, cycle?.effectiveFromEpoch, cycle?.creditSalesQty, cycle?.recoveredCredit, editingSettlementId) {
    if (!isComparison && editingSettlementId == null && cycle != null) {
      if (!manualFromOverride && fromDate != cycle.effectiveFromEpoch) {
        fromDate = cycle.effectiveFromEpoch
      }
      creditSalesQtyText = if (cycle.creditSalesQty > 0.0) SarimFormat.formatQty(cycle.creditSalesQty) else "0"
      recoveredCreditText = if (cycle.recoveredCredit > 0.0) SarimFormat.formatQty(cycle.recoveredCredit, 2) else "0"
    }
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item { ShimmerHeading("Calculator", fontSizeSp = 26) }

    // Top controls (always visible)
    item {
      SarimCard {
        SarimPopoverSelector(
          label = "Sales Representative",
          selectedText = selectedRepOption,
          options = selectorOptions,
          onSelect = { _, opt -> selectedRepOption = opt },
          testTag = "seller_select_btn",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          SarimDatePickerField(
            "From",
            fromDate,
            onDateSelected = {
              fromDate = it
              manualFromOverride = true
            },
            modifier = Modifier.weight(1f),
            testTag = "calc_from_date",
          )
          SarimDatePickerField("To", toDate, onDateSelected = { toDate = it }, modifier = Modifier.weight(1f), testTag = "calc_to_date")
        }
        if (!isComparison && manualFromOverride && editingSettlementId == null) {
          TextButton(
            onClick = { manualFromOverride = false },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("btn_calc_auto_cycle"),
          ) {
            Text("Reset to unsettled cycle start", color = palette.cyan, fontSize = 12.sp)
          }
        }
      }
    }

    if (isComparison) {
      // Comparison Mode (Section 9.2)
      val cutoff = SarimMetricsEngine.periodStart(compPeriod)
      val periodSettlements = state.settlements.filter { it.dateEpoch >= cutoff }
      val compReps = (reps + periodSettlements.map { it.repName }).distinct()

      item {
        SarimCard {
          Text("Comparison", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
          SarimToggleGroup(
            listOf(TimePeriod.WEEKLY, TimePeriod.MONTHLY, TimePeriod.YEARLY, TimePeriod.ALL_TIME),
            compPeriod,
            { it.label },
            { compPeriod = it },
          )
          SarimPopoverSelector(
            label = "View Chart For",
            selectedText = compMetric.label,
            options = CompChartMetric.entries.map { it.label },
            onSelect = { idx, _ -> compMetric = CompChartMetric.entries[idx] },
          )
        }
      }

      item {
        SarimCard {
          Text("COMPETITION", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
          if (compReps.isEmpty()) {
            SarimEmptyState("No data yet", "Add a Sales Representative account or save a settlement to compare.")
          } else {
            val vals = compReps.map { r ->
              val list = periodSettlements.filter { it.repName == r }
              when (compMetric) {
                CompChartMetric.NET_PROFIT -> list.sumOf { it.netProfit }.coerceAtLeast(0.0)
                CompChartMetric.GROSS_REV -> list.sumOf { it.grossRevenue }
                CompChartMetric.QTY_SOLD -> list.sumOf { it.netSoldKg }
              }
            }
            ColumnBarChart(compReps, vals, compReps.map { palette.green })
          }
        }
      }

      item {
        SarimCard {
          Text("MARKET COMPOSITION", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
          if (compReps.isEmpty() || periodSettlements.isEmpty()) {
            SarimEmptyState("No composition", "Nothing recorded in this period")
          } else {
            val sliceColors = listOf(palette.green, palette.cyan, palette.purple, palette.amber)
            DonutShareChart(
              slices = compReps.mapIndexed { idx, r ->
                Triple(r, periodSettlements.filter { it.repName == r }.sumOf { it.grossRevenue }, sliceColors[idx % sliceColors.size])
              },
              centerLabel = "Share",
            )
          }
        }
      }

      if (compReps.isNotEmpty()) {
        item {
          SarimCard {
            Text("Representative Metrics", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  Text("METRIC", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(130.dp))
                  compReps.forEach { Text(it, color = palette.cyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                val metricRows: List<Pair<String, (List<CalculatorSettlementEntity>) -> String>> = listOf(
                  "Qty Sold" to { l -> SarimFormat.formatKg(l.sumOf { it.totalSoldKg }) },
                  "Returns" to { l -> SarimFormat.formatKg(l.sumOf { it.returnedKg }) },
                  "Expired (→ CHORA)" to { l -> SarimFormat.formatKg(l.sumOf { it.expiredKg }) },
                  "Shared (Deduction)" to { l -> SarimFormat.formatKg(l.sumOf { it.sharedDeductionKg }) },
                  "Total Cost" to { l -> SarimFormat.formatRs(l.sumOf { it.totalCost }, false, hideMoney) },
                  "Gross Revenue" to { l -> SarimFormat.formatRs(l.sumOf { it.grossRevenue }, false, hideMoney) },
                  "Net Profit" to { l -> SarimFormat.formatRs(l.sumOf { it.netProfit }, false, hideMoney) },
                  "Credit Issued" to { l -> SarimFormat.formatRs(l.sumOf { it.creditIssuedValue }, false, hideMoney) },
                  "Credit Recovered" to { l -> SarimFormat.formatRs(l.sumOf { it.recoveredCredit }, false, hideMoney) },
                  "Field Expenses" to { l -> SarimFormat.formatRs(l.sumOf { it.fieldExpenses }, false, hideMoney) },
                  "Commission Paid" to { l -> SarimFormat.formatRs(l.sumOf { it.commissionPaid }, false, hideMoney) },
                )
                metricRows.forEach { (label, fn) ->
                  val isHighlight = label == "Net Profit"
                  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(label, color = if (isHighlight) palette.amber else palette.textPrimary, fontSize = 12.sp, fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(130.dp))
                    compReps.forEach { r ->
                      val list = periodSettlements.filter { it.repName == r }
                      Text(fn(list), color = if (isHighlight) palette.amber else palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Medium), modifier = Modifier.width(96.dp))
                    }
                  }
                }
              }
            }
          }
        }
      }
    } else {
      // Individual Representative Mode (Section 9.3 + Cycle Link Engine)
      val totalSoldKg = cycle?.totalSoldKg ?: 0.0
      val returnedKg = cycle?.returnedKg ?: 0.0
      val expiredKg = cycle?.expiredKg ?: 0.0
      val sharedNum = SarimFormat.parseSafeDouble(sharedQtyText) ?: 0.0
      val netSoldKg = (totalSoldKg - returnedKg - expiredKg - sharedNum).coerceAtLeast(0.0)
      val avgUnitPrice = cycle?.avgUnitPrice ?: fallbackStorePrice
      val totalCost = cycle?.totalCost ?: 0.0
      val grossRevenue = SarimLinkEngine.round2(netSoldKg * avgUnitPrice)
      val creditQtyNum = SarimFormat.parseSafeDouble(creditSalesQtyText) ?: 0.0
      val creditValue = SarimLinkEngine.round2(creditQtyNum * avgUnitPrice)
      val commPerUnit = SarimFormat.parseSafeDouble(commissionPerUnitText) ?: 0.0
      val grossCommission = SarimLinkEngine.round2(netSoldKg * commPerUnit)
      val commPaidTotal = SarimLinkEngine.round2(commissionItems.sumOf { it.amount })
      // Web parity: payable never goes below zero.
      val commPayable = SarimLinkEngine.round2((grossCommission - commPaidTotal).coerceAtLeast(0.0))
      val recoveredNum = SarimFormat.parseSafeDouble(recoveredCreditText) ?: 0.0
      val fieldExpTotal = SarimLinkEngine.round2(expenseItems.sumOf { it.amount })
      // Web parity (calculateSales): cash quantity is never negative.
      val cashQtyKg = (netSoldKg - creditQtyNum).coerceAtLeast(0.0)
      val expectedCash = SarimLinkEngine.round2(cashQtyKg * avgUnitPrice + recoveredNum - fieldExpTotal - commPaidTotal)
      val receivedNum = SarimFormat.parseSafeDouble(receivedCashText) ?: 0.0
      val cashDiff = SarimLinkEngine.round2(receivedNum - expectedCash)
      // Web parity: 0.01 tolerance; "over" is shown in the same tone as a perfect match.
      val statusLabel = when {
        abs(cashDiff) < 0.01 -> "OK"
        cashDiff < 0 -> "Short"
        else -> "Over"
      }
      val statusColor = if (statusLabel == "Short") palette.red else palette.green
      val statusText = when (statusLabel) {
        "OK" -> "PERFECT MATCH"
        "Short" -> "SHORT: ${SarimFormat.formatRs(abs(cashDiff), false, hideMoney)}"
        else -> "OVER: ${SarimFormat.formatRs(abs(cashDiff), false, hideMoney)}"
      }

      item {
        SarimCard {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (editingSettlementId == null) "Product Quantities" else "Edit Settlement", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (editingSettlementId != null) {
              val origSettle = state.settlements.firstOrNull { it.id == editingSettlementId }
              val hasEdits = origSettle == null ||
                sharedNum != origSettle.sharedDeductionKg ||
                creditQtyNum != origSettle.creditSalesQty ||
                recoveredNum != origSettle.recoveredCredit ||
                receivedNum != origSettle.receivedCash ||
                commPaidTotal != origSettle.commissionPaid ||
                fieldExpTotal != origSettle.fieldExpenses
              TextButton(
                onClick = {
                  viewModel.requestCancelEdit(hasEdits) {
                    editingSettlementId = null
                    manualFromOverride = false
                    receivedCashText = ""
                    commissionItems = emptyList()
                    expenseItems = emptyList()
                  }
                },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("btn_cancel_edit_calc"),
              ) { Text("Cancel Edit", color = palette.amber) }
            }
          }
          if (editingSettlementId != null) {
            SarimCallout("Editing settlement · Saving updates the original record & reconciles links.", palette.amber)
          }
          if (cycle != null) {
            SarimCallout(
              "Pending cycle: ${cycle.selectedDeliveries.size} deliveries · ${cycle.pendingReturns.size} rep returns · ${cycle.pendingRepSales.size} rep tx",
              palette.cyan,
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimTextField(SarimFormat.formatQty(totalSoldKg), {}, "Total Sold Qty", readOnly = true, suffixText = "kg", modifier = Modifier.weight(1f))
            SarimTextField(SarimFormat.formatQty(returnedKg), {}, "Returned Qty", readOnly = true, suffixText = "kg", modifier = Modifier.weight(1f))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimTextField(SarimFormat.formatQty(expiredKg), {}, "Expired / Damage", readOnly = true, suffixText = "kg", modifier = Modifier.weight(1f))
            SarimTextField(sharedQtyText, { sharedQtyText = it }, "Shared (Deduction)", suffixText = "kg", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
          }
          SarimTextField(SarimFormat.formatQty(netSoldKg), {}, "Net Sold Qty", readOnly = true, dashedReadOnly = true, suffixText = "kg")

          if (returnedKg > 0.0 && (state.stores.isNotEmpty() || otherReps.isNotEmpty())) {
            if (otherReps.isNotEmpty()) {
              SarimFieldLabel("Returned Stock Destination")
              SarimToggleGroup(
                options = ReturnTargetMode.entries,
                selected = returnTargetMode,
                labelOf = { it.label },
                onSelect = {
                  returnTargetMode = it
                  if (it == ReturnTargetMode.TRANSFER && transferTargetRep.isBlank()) {
                    transferTargetRep = otherReps.first()
                  }
                },
                testTagPrefix = "calc_return_mode",
              )
            }
            if (returnTargetMode == ReturnTargetMode.TRANSFER && otherReps.isNotEmpty()) {
              val chosenTarget = if (transferTargetRep in otherReps) transferTargetRep else otherReps.first()
              SarimPopoverSelector(
                label = "Transfer Returned Stock To Rep",
                selectedText = chosenTarget,
                options = otherReps,
                onSelect = { _, opt -> transferTargetRep = opt },
                testTag = "calc_transfer_rep_btn",
              )
              SarimCallout("Creates a Credit delivery to $chosenTarget without changing physical store stock.", palette.purple)
            } else if (state.stores.isNotEmpty()) {
              SarimFieldLabel("Return Stock To Physical Inventory")
              SarimToggleGroup(
                state.stores,
                state.stores.firstOrNull { it.storeKey == returnStoreKey } ?: state.stores.first(),
                { it.name },
                { returnStoreKey = it.storeKey },
                testTagPrefix = "calc_return_store",
              )
              Text("This will increase the store's stock level without creating a new production record.", color = palette.textMuted, fontSize = 11.sp)
            }
          }

          if (expiredKg > 0.0) {
            SarimCallout("Expired Stock — Added to CHORA Raw Material (${SarimFormat.formatKg(expiredKg)})", palette.amber)
          }

          SarimTextField(creditSalesQtyText, { creditSalesQtyText = it }, "Credit Sales Qty", suffixText = "kg", keyboardType = KeyboardType.Decimal, testTag = "calc_credit_qty")

          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
          Text("Commission", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimTextField(commissionPerUnitText, { commissionPerUnitText = it }, "Commission / Unit", prefixText = "Rs", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            SarimTextField(
              value = if (commPaidTotal > 0) SarimFormat.formatRs(commPaidTotal, false) else "",
              onValueChange = {},
              label = "Commission Paid",
              placeholder = "Tap to add commission",
              readOnly = true,
              onClickIfReadOnly = { showCommissionPopup = true },
              modifier = Modifier.weight(1f),
            )
          }

          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
          Text("Collections & Handover Cash", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimTextField(recoveredCreditText, { recoveredCreditText = it }, "Recovered Credit", prefixText = "Rs", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f), testTag = "calc_recovered_credit")
            SarimTextField(
              value = if (fieldExpTotal > 0) SarimFormat.formatRs(fieldExpTotal, false) else "",
              onValueChange = {},
              label = "Field Expenses",
              placeholder = "Tap to add expenses",
              readOnly = true,
              onClickIfReadOnly = { showExpensesPopup = true },
              modifier = Modifier.weight(1f),
            )
          }
          SarimTextField(receivedCashText, { receivedCashText = it }, "Physical Cash Received from Rep", prefixText = "Rs", keyboardType = KeyboardType.Decimal, testTag = "calc_received_cash")

          // Result box (web: #calc-result-card) — one labelled line per value.
          Surface(
            shape = RoundedCornerShape(24.dp),
            color = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, statusColor, 0.12f),
          ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              CalcResultLine("Expected Cash:", SarimFormat.formatRs(expectedCash, true, hideMoney), palette.cyan)
              CalcResultLine("Status:", statusText, statusColor)
              CalcResultLine("Gross Commission Earned:", SarimFormat.formatRs(grossCommission, false, hideMoney), palette.cyan)
              CalcResultLine("Commission Paid:", SarimFormat.formatRs(commPaidTotal, false, hideMoney), palette.red)
              CalcResultLine("Commission Payable:", SarimFormat.formatRs(commPayable, false, hideMoney), if (commPayable <= 0.01) palette.green else palette.red)
            }
          }

          SarimCheckboxRow(
            checked = addToLedger,
            onCheckedChange = { addToLedger = it },
            label = "Add cash received, expenses and commission to Daily Ledger",
          )

          SarimPrimaryButton(
            text = if (editingSettlementId == null) "Save Transaction" else "Update Settlement",
            testTag = "btn_save_calc_settlement",
            onClick = {
              if (SarimFormat.isFutureDate(toDate)) {
                viewModel.showDialog(AppDialogRequest("Invalid Date", "Settlement date cannot be in the future.", tone = DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (fromDate > toDate) {
                viewModel.showDialog(AppDialogRequest("Invalid Date Range", "The From date must be on or before the To date.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (totalSoldKg <= 0.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Quantity", "Please enter valid units sold (must be greater than 0).", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (fallbackStorePrice <= 0.0) {
                viewModel.showDialog(AppDialogRequest("Sale Price Missing", "Please set a sale price in Store Manager / Factory Formulas first.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (returnedKg < 0.0 || expiredKg < 0.0 || sharedNum < 0.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Quantity", "Returned, expired, and shared quantities cannot be negative.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (returnedKg > totalSoldKg) {
                viewModel.showDialog(AppDialogRequest("Invalid Return", "Returned quantity cannot exceed total sold.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if ((returnedKg + expiredKg + sharedNum) > totalSoldKg + 1e-6) {
                viewModel.showDialog(AppDialogRequest("Invalid Quantities", "Combined returned + expired + shared quantity cannot exceed total sold.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (creditQtyNum < 0.0 || recoveredNum < 0.0 || receivedNum < 0.0 || fieldExpTotal < 0.0 || commPerUnit < 0.0 || commPaidTotal < 0.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Amount", "Credit, cash, expense, and commission fields cannot be negative.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (recoveredNum > 10_000_000_000.0 || receivedNum > 10_000_000_000.0 || fieldExpTotal > 10_000_000_000.0 || commPaidTotal > 10_000_000_000.0) {
                viewModel.showDialog(AppDialogRequest("Amount Too Large", "An amount is too large. Please check the cash, expense and commission fields.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (commPerUnit > 100_000.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Commission", "Commission per unit cannot exceed 100,000.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (creditQtyNum > netSoldKg + 1e-6) {
                viewModel.showDialog(AppDialogRequest("Credit Warning", "Credit sales cannot exceed net sold quantity.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              val useTransfer = returnedKg > 0.0 && returnTargetMode == ReturnTargetMode.TRANSFER && otherReps.isNotEmpty()
              val chosenTargetRep = if (useTransfer) {
                (transferTargetRep.takeIf { it in otherReps } ?: otherReps.first())
              } else null
              val currentEditId = editingSettlementId
              val runConfirmSave: () -> Unit = {
                viewModel.confirmSave(
                  "Settlement",
                  listOf(
                    "Seller" to selectedRepOption,
                    "Date" to SarimFormat.formatDate(toDate),
                    "Total Sold" to SarimFormat.formatKg(totalSoldKg),
                    "Returned" to SarimFormat.formatKg(returnedKg),
                    "Expired" to SarimFormat.formatKg(expiredKg),
                    "Cash Received" to SarimFormat.formatRs(receivedNum, false, hideMoney),
                  ),
                  isUpdate = currentEditId != null,
                ) {
                  viewModel.launchSafely(
                    errorTitle = "Settlement Save Failed",
                    successMessage = if (currentEditId == null) "Settlement saved for $selectedRepOption" else "Settlement updated for $selectedRepOption",
                    onSuccess = {
                      editingSettlementId = null
                      manualFromOverride = false
                      receivedCashText = ""
                      commissionItems = emptyList()
                      expenseItems = emptyList()
                    },
                  ) {
                    viewModel.repository.saveCalculatorSettlement(
                      CalculatorSettlementEntity(
                        id = currentEditId ?: com.example.data.model.SarimUuid.generateUUID(com.example.data.model.SarimUuid.PREFIX_CALC),
                        repName = selectedRepOption,
                        fromDateEpoch = fromDate,
                        toDateEpoch = toDate,
                        dateEpoch = toDate,
                        totalSoldKg = totalSoldKg,
                        returnedKg = returnedKg,
                        expiredKg = expiredKg,
                        sharedDeductionKg = sharedNum,
                        netSoldKg = netSoldKg,
                        returnStoreKey = if (returnedKg > 0.0 && !useTransfer) returnStoreKey else "",
                        returnType = if (returnedKg > 0.0) (if (useTransfer) "TRANSFER" else "RETURN") else null,
                        returnRep = chosenTargetRep,
                        creditSalesQty = creditQtyNum,
                        commissionPerUnit = commPerUnit,
                        commissionPaid = commPaidTotal,
                        commissionItemsJson = JsonCodecs.encodeLineItems(commissionItems),
                        recoveredCredit = recoveredNum,
                        fieldExpenses = fieldExpTotal,
                        expenseItemsJson = JsonCodecs.encodeLineItems(expenseItems),
                        receivedCash = receivedNum,
                        expectedCash = expectedCash,
                        status = statusLabel.uppercase(),
                        statusDiff = cashDiff,
                        grossCommission = grossCommission,
                        commissionPayable = commPayable,
                        totalCost = totalCost,
                        grossRevenue = grossRevenue,
                        netProfit = grossRevenue - totalCost - fieldExpTotal - grossCommission,
                        creditIssuedValue = creditValue,
                        addToLedger = addToLedger,
                      )
                    )
                  }
                }
              }
              if (abs(cashDiff) > 0.01) {
                viewModel.showDialog(
                  AppDialogRequest(
                    title = if (cashDiff < 0) "Cash is Short" else "Cash is Over",
                    body = viewModel.redactMoney(
                      "Cash received ${SarimFormat.formatRs(receivedNum)} but expected ${SarimFormat.formatRs(expectedCash)}.\n${if (cashDiff < 0) "Short" else "Over"} by ${SarimFormat.formatRs(abs(cashDiff))}. Save this settlement anyway?",
                      "Cash received differs from expected (${if (cashDiff < 0) "Short" else "Over"}). Save this settlement anyway?",
                    ),
                    tone = if (cashDiff < 0) DialogTone.WARNING else DialogTone.CONFIRM,
                    confirmLabel = "Save anyway",
                    onConfirm = { _ -> runConfirmSave() },
                  )
                )
              } else {
                runConfirmSave()
              }
            },
          )
        }
      }

      // Performance Overview & Market Debt Tracker
      val repSettlements = state.settlements.filter { it.repName == selectedRepOption }
      val periodFiltered = repSettlements.filter { it.dateEpoch >= SarimMetricsEngine.periodStart(overviewPeriod) }
      item {
        SarimCard {
          Text("Performance Overview ($selectedRepOption)", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          SarimToggleGroup(TimePeriod.entries, overviewPeriod, { it.label }, { overviewPeriod = it })
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Total Sold", SarimFormat.formatKg(periodFiltered.sumOf { it.totalSoldKg }), Modifier.weight(1f), palette.cyan)
            SarimStatTile("Returned", SarimFormat.formatKg(periodFiltered.sumOf { it.returnedKg }), Modifier.weight(1f), palette.amber)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Expired", SarimFormat.formatKg(periodFiltered.sumOf { it.expiredKg }), Modifier.weight(1f), palette.red)
            SarimStatTile("Shared", SarimFormat.formatKg(periodFiltered.sumOf { it.sharedDeductionKg }), Modifier.weight(1f))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Net Sold", SarimFormat.formatKg(periodFiltered.sumOf { it.netSoldKg }), Modifier.weight(1f), palette.green)
            SarimStatTile("Gross Revenue", SarimFormat.formatRs(periodFiltered.sumOf { it.grossRevenue }, false, hideMoney), Modifier.weight(1f), palette.cyan)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Net Profit", SarimFormat.formatRs(periodFiltered.sumOf { it.netProfit }, false, hideMoney), Modifier.weight(1f), palette.emerald)
            SarimStatTile("Expected Cash", SarimFormat.formatRs(periodFiltered.sumOf { it.expectedCash }, false, hideMoney), Modifier.weight(1f), palette.amber)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Comm Payable", SarimFormat.formatRs(periodFiltered.sumOf { it.commissionPayable }, false, hideMoney), Modifier.weight(1f), palette.purple)
            SarimStatTile("Comm Paid", SarimFormat.formatRs(periodFiltered.sumOf { it.commissionPaid }, false, hideMoney), Modifier.weight(1f), palette.green)
          }
          TextButton(
            onClick = { showExpensesBreakdown = !showExpensesBreakdown },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Text(if (showExpensesBreakdown) "Hide Expenses breakdown" else "Expenses breakdown", color = palette.cyan, fontWeight = FontWeight.Bold)
          }
          AnimatedVisibility(visible = showExpensesBreakdown) {
            val allExp = periodFiltered.flatMap { JsonCodecs.decodeLineItems(it.expenseItemsJson) }
            Surface(
              shape = RoundedCornerShape(18.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                allExp.forEach {
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(it.category, color = palette.textMuted, fontSize = 12.sp)
                    Text(SarimFormat.formatRs(it.amount, true, hideMoney), color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                  }
                }
              }
            }
          }
          TextButton(
            onClick = { showCommissionBreakdown = !showCommissionBreakdown },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Text(if (showCommissionBreakdown) "Hide Commission breakdown" else "Commission breakdown", color = palette.green, fontWeight = FontWeight.Bold)
          }
          AnimatedVisibility(visible = showCommissionBreakdown) {
            val allComm = periodFiltered.flatMap { JsonCodecs.decodeLineItems(it.commissionItemsJson) }
            Surface(
              shape = RoundedCornerShape(18.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                allComm.forEach {
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(it.category, color = palette.textMuted, fontSize = 12.sp)
                    Text(SarimFormat.formatRs(it.amount, true, hideMoney), color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                  }
                }
              }
            }
          }
        }
      }

      if (!hideMoney) {
        item {
          val (indLabels, indValues) = remember(repSettlements, indChartPeriod, indChartMetric, state.currentLocalDayEpoch) {
            SarimMetricsEngine.computeCalcIndChartData(
              repSettlements = repSettlements,
              period = indChartPeriod,
              metric = indChartMetric,
              referenceDateEpoch = System.currentTimeMillis(),
            )
          }
          SarimCard {
            Text("Performance Chart ($selectedRepOption)", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            SarimToggleGroup(
              options = listOf(TimePeriod.WEEKLY, TimePeriod.MONTHLY, TimePeriod.YEARLY, TimePeriod.ALL_TIME),
              selected = indChartPeriod,
              labelOf = { it.label },
              onSelect = { indChartPeriod = it },
              testTagPrefix = "calc_ind_period",
            )
            SarimPopoverSelector(
              label = "Chart Metric",
              selectedText = indChartMetric.label,
              options = CalcIndMetric.entries.map { it.label },
              onSelect = { idx, _ -> indChartMetric = CalcIndMetric.entries[idx] },
              testTag = "calc_ind_metric_btn",
            )
            if (indLabels.isEmpty() || indValues.all { it <= 0.0 }) {
              SarimEmptyState("No chart activity", "No recorded settlements match this period yet.")
            } else {
              val barColor = when (indChartMetric) {
                CalcIndMetric.NET_PROFIT -> palette.green
                CalcIndMetric.GROSS_REV -> palette.cyan
                CalcIndMetric.QTY_SOLD -> palette.purple
              }
              ColumnBarChart(
                labels = indLabels,
                values = indValues,
                colors = indLabels.map { barColor },
              )
            }
          }
        }
      }

      item {
        val totalIssued = repSettlements.sumOf { it.creditIssuedValue }
        val totalRec = repSettlements.sumOf { it.recoveredCredit }
        val debt = (totalIssued - totalRec).coerceAtLeast(0.0)
        SarimCard {
          Text("Market debt tracker", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Text(SarimFormat.formatRs(debt, true, hideMoney), color = palette.red, style = TabularNumberStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold))
          SplitRatioBar("Recovered", totalRec, palette.green, "Outstanding", debt, palette.red, privacyHidden = hideMoney)
        }
      }

      item {
        Text("Calculator History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        SarimTextField(
          value = calcHistSearch,
          onValueChange = { calcHistSearch = it },
          label = "",
          placeholder = "Search date, status, or item...",
          leadingIcon = Icons.Rounded.Search,
          testTag = "calc_history_search",
        )
        Spacer(Modifier.height(8.dp))
        val q = calcHistSearch.trim()
        val filteredSettlements = if (q.isBlank()) repSettlements else repSettlements.filter { s ->
          s.repName.contains(q, ignoreCase = true) ||
            s.status.contains(q, ignoreCase = true) ||
            SarimFormat.dateInput(s.dateEpoch).contains(q, ignoreCase = true) ||
            SarimFormat.formatDate(s.dateEpoch).contains(q, ignoreCase = true) ||
            (s.returnRep?.contains(q, ignoreCase = true) == true) ||
            s.expenseItemsJson.contains(q, ignoreCase = true) ||
            s.commissionItemsJson.contains(q, ignoreCase = true)
        }
        val records = filteredSettlements.map { s ->
          val sc = when (s.status) {
            "OK" -> palette.green
            "SHORT" -> palette.red
            else -> palette.amber
          }
          HistoryRecordDisplay(
            item = s,
            dateEpoch = s.dateEpoch,
            title = "${s.repName} · ${s.status}",
            subtitle = "Net ${SarimFormat.formatKg(s.netSoldKg)} · Rev ${SarimFormat.formatRs(s.grossRevenue, false, hideMoney)}",
            amountText = SarimFormat.formatRs(s.receivedCash, true, hideMoney),
            amountColor = sc,
            chipText = if (s.status == "OK") "PERFECT MATCH" else "${s.status}: ${SarimFormat.formatRs(abs(s.statusDiff), false, hideMoney)}",
            chipColor = sc,
            detailMetrics = buildList {
              add("Total Sold" to SarimFormat.formatKg(s.totalSoldKg))
              add("Returned" to SarimFormat.formatKg(s.returnedKg))
              if (s.returnType.equals("TRANSFER", true) && !s.returnRep.isNullOrBlank()) {
                add("Transfer To" to s.returnRep)
              }
              add("Expired" to SarimFormat.formatKg(s.expiredKg))
              add("Net Sold" to SarimFormat.formatKg(s.netSoldKg))
              add("Gross Revenue" to SarimFormat.formatRs(s.grossRevenue, false, hideMoney))
              add("Net Profit" to SarimFormat.formatRs(s.netProfit, false, hideMoney))
              add("Expected Cash" to SarimFormat.formatRs(s.expectedCash, false, hideMoney))
              add("Cash Received" to SarimFormat.formatRs(s.receivedCash, false, hideMoney))
              add("Field Expenses" to SarimFormat.formatRs(s.fieldExpenses, false, hideMoney))
              add("Comm Paid" to SarimFormat.formatRs(s.commissionPaid, false, hideMoney))
            },
          )
        }
        AccordionHistoryList(
          records = records,
          yearSummary = { "" },
          monthSummary = { items -> "Cash ${SarimFormat.formatRs(items.sumOf { it.item.receivedCash }, false, hideMoney)}" },
          daySummary = { items -> "Cash ${SarimFormat.formatRs(items.sumOf { it.item.receivedCash }, false, hideMoney)}" },
          onItemClick = { rec -> sheetSettlement = rec.item },
        )
      }
    }
  }

  sheetSettlement?.let { s ->
    val sc = when (s.status) {
      "OK" -> palette.green
      "SHORT" -> palette.red
      else -> palette.amber
    }
    val expLines = remember(s.expenseItemsJson) { JsonCodecs.decodeLineItems(s.expenseItemsJson) }
    val commLines = remember(s.commissionItemsJson) { JsonCodecs.decodeLineItems(s.commissionItemsJson) }
    SarimRecordBottomSheet(
      title = "${s.repName} · ${s.status}",
      subtitle = "${SarimFormat.formatDate(s.fromDateEpoch)} → ${SarimFormat.formatDate(s.toDateEpoch)}",
      amountText = SarimFormat.formatRs(s.receivedCash, true, hideMoney),
      amountColor = sc,
      badgeText = if (s.status == "OK") "PERFECT MATCH" else "${s.status}: ${SarimFormat.formatRs(abs(s.statusDiff), false, hideMoney)}",
      badgeColor = sc,
      metrics = buildList {
        add("Total Sold" to SarimFormat.formatKg(s.totalSoldKg))
        add("Returned" to SarimFormat.formatKg(s.returnedKg))
        if (s.returnType.equals("TRANSFER", true) && !s.returnRep.isNullOrBlank()) {
          add("Transfer To" to s.returnRep)
        }
        add("Expired (→ CHORA)" to SarimFormat.formatKg(s.expiredKg))
        add("Shared Deduction" to SarimFormat.formatKg(s.sharedDeductionKg))
        add("Net Sold" to SarimFormat.formatKg(s.netSoldKg))
        add("Gross Revenue" to SarimFormat.formatRs(s.grossRevenue, false, hideMoney))
        add("Credit Issued" to SarimFormat.formatRs(s.creditIssuedValue, false, hideMoney))
        add("Recovered Credit" to SarimFormat.formatRs(s.recoveredCredit, false, hideMoney))
        add("Field Expenses" to SarimFormat.formatRs(s.fieldExpenses, false, hideMoney))
        add("Commission Paid" to SarimFormat.formatRs(s.commissionPaid, false, hideMoney))
        add("Expected Cash" to SarimFormat.formatRs(s.expectedCash, false, hideMoney))
        add("Cash Received" to SarimFormat.formatRs(s.receivedCash, false, hideMoney))
        add("Net Profit" to SarimFormat.formatRs(s.netProfit, false, hideMoney))
      },
      extraContent = if (expLines.isNotEmpty() || commLines.isNotEmpty()) {
        {
          if (expLines.isNotEmpty()) {
            SarimCollapsibleDropdownSection(
              title = "EXPENSE BREAKDOWN",
              accentColor = palette.red,
              countText = "${expLines.size}",
            ) {
              expLines.forEach {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text(it.category, color = palette.textMuted, fontSize = 12.sp)
                  Text(SarimFormat.formatRs(it.amount, true, hideMoney), color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                }
              }
            }
          }
          if (commLines.isNotEmpty()) {
            SarimCollapsibleDropdownSection(
              title = "COMMISSION BREAKDOWN",
              accentColor = palette.green,
              countText = "${commLines.size}",
            ) {
              commLines.forEach {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text(it.category, color = palette.textMuted, fontSize = 12.sp)
                  Text(SarimFormat.formatRs(it.amount, true, hideMoney), color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                }
              }
            }
          }
        }
      } else null,
      onDismiss = { sheetSettlement = null },
      onEdit = {
        if (YearCloseMerge.isMergedId(s.id)) {
          viewModel.showDialog(AppDialogRequest("Action Not Allowed", "Merged opening balance records cannot be edited directly.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
        } else {
          editingSettlementId = s.id
          manualFromOverride = true
          selectedRepOption = s.repName
          fromDate = s.fromDateEpoch
          toDate = s.toDateEpoch
          sharedQtyText = s.sharedDeductionKg.toString()
          returnTargetMode = if (s.returnType.equals("TRANSFER", true)) ReturnTargetMode.TRANSFER else ReturnTargetMode.RETURN
          transferTargetRep = s.returnRep.orEmpty()
          if (s.returnStoreKey.isNotBlank()) returnStoreKey = s.returnStoreKey
          creditSalesQtyText = s.creditSalesQty.toString()
          commissionPerUnitText = s.commissionPerUnit.toString()
          commissionItems = commLines
          recoveredCreditText = s.recoveredCredit.toString()
          expenseItems = expLines
          receivedCashText = s.receivedCash.toString()
          addToLedger = s.addToLedger
        }
      },
      onDelete = {
        val choraStock = state.rawMaterials.firstOrNull { !it.isDeleted && it.name.equals("CHORA", ignoreCase = true) }?.stockKg ?: 0.0
        val transferSale = s.transferSaleId?.let { tid -> state.sales.firstOrNull { !it.isDeleted && it.id == tid } }
        val blockReason = when {
          YearCloseMerge.isMergedId(s.id) -> "Merged opening balance records cannot be deleted."
          s.expiredApplied && s.expiredKg > 0.0001 && choraStock + 0.0001 < s.expiredKg ->
            "Only ${SarimFormat.formatKg(choraStock)} of CHORA is left, but this settlement added ${SarimFormat.formatKg(s.expiredKg)}. Part of it has already been used, so it cannot be removed."
          transferSale != null && (
            state.settlements.any { !it.isDeleted && it.id != s.id && (it.id == transferSale.usedInCalcId || transferSale.id in SarimLinkEngine.reconciledAdminSalesIds(listOf(it))) } ||
              transferSale.creditReceived ||
              transferSale.partialPaymentReceived > 0.01 ||
              JsonCodecs.decodeCollectionAllocs(transferSale.collectionAllocsJson).isNotEmpty()
            ) -> "${s.returnRep ?: "The receiving rep"} already has settlements or payments recorded against this transfer. Remove those first."
          s.returnedKg > 0.0001 && !s.returnType.equals("TRANSFER", true) && s.returnStoreKey.isNotBlank() -> {
            val projected = SarimLinkEngine.projectedStoreStockAfterProductionDelete(
              storeKey = s.returnStoreKey,
              excludingProdId = s.returnEntryId,
              production = state.productionEntries,
              stockTransfers = state.stockTransfers,
              settlements = state.settlements.filter { it.id != s.id },
              sales = state.sales,
            )
            if (projected < -0.001) {
              "Cannot delete: store stock on ${s.returnStoreKey} was already sold. Removing the ${SarimFormat.formatKg(s.returnedKg)} return would leave it short by ${SarimFormat.formatKg(abs(projected))}."
            } else null
          }
          else -> null
        }
        if (blockReason != null) {
          viewModel.showDialog(AppDialogRequest("Action Not Allowed", blockReason, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
        } else {
          val linkedSalesCount = JsonCodecs.decodeStrings(s.linkedSalesIdsJson).size
          val linkedRepCount = JsonCodecs.decodeStrings(s.linkedRepSalesIdsJson).size
          val impactMsg = buildString {
            append("Delete ${s.repName} settlement (${SarimFormat.dateInput(s.fromDateEpoch)} → ${SarimFormat.dateInput(s.toDateEpoch)})?")
            val hasImpact = linkedSalesCount > 0 || linkedRepCount > 0 ||
              (s.returnedKg > 0.0 && s.returnStoreKey.isNotBlank()) || !s.transferSaleId.isNullOrBlank() || s.expiredKg > 0.0
            if (hasImpact) {
              append("\n\nThe following cascading changes will occur:")
              if (linkedSalesCount > 0) append("\n • $linkedSalesCount linked delivery sale(s) will revert to Pending Credit.")
              if (linkedRepCount > 0) append("\n • $linkedRepCount rep sale(s) will be restored to pending Calculator fields.")
              if (s.returnedKg > 0.0 && !s.returnType.equals("TRANSFER", true) && s.returnStoreKey.isNotBlank()) {
                append("\n • ${SarimFormat.formatKg(s.returnedKg)} will be removed from store inventory (return reversal).")
              }
              if (!s.transferSaleId.isNullOrBlank()) {
                append("\n • ${SarimFormat.formatKg(s.returnedKg)} transferred to ${s.returnRep ?: "rep"} will be removed from their sales allocation.")
              }
              if (s.expiredKg > 0.0) {
                append("\n • ${SarimFormat.formatKg(s.expiredKg)} will be removed from CHORA raw material.")
              }
            }
            append("\n\nThis cannot be undone.")
          }
          viewModel.showDialog(
            AppDialogRequest(
              title = "Delete ${s.repName} Record",
              body = impactMsg,
              tone = DialogTone.PERMANENT,
              confirmLabel = "Delete",
              onConfirm = {
                viewModel.launchSafely(
                  errorTitle = "Delete Failed",
                  successMessage = "Deleted settlement",
                ) {
                  viewModel.repository.deleteSettlement(s.id)
                }
              },
            )
          )
        }
      },
    )
  }

  if (showExpensesPopup) {
    LineItemsPopupDialog(
      chip = "Expenses",
      caption = "Search or type a category, tap it, add the amount and save. The field shows the total paid.",
      categoryPlaceholder = "Search or type a category (e.g. Fuel)",
      initialItems = expenseItems,
      knownCategories = state.fieldExpenseCategories,
      onNewCategory = { cat -> viewModel.launchSafely { viewModel.repository.saveCategoryIfNew("field_expense_categories", cat) } },
      onDismiss = { showExpensesPopup = false },
      onSave = { expenseItems = it; showExpensesPopup = false },
    )
  }

  if (showCommissionPopup) {
    LineItemsPopupDialog(
      chip = "Commission",
      caption = "Search or type a category, tap it, add the amount and save. The field shows the total paid.",
      categoryPlaceholder = "Search or type a category (e.g. Advance)",
      initialItems = commissionItems,
      knownCategories = state.commissionCategories,
      onNewCategory = { cat -> viewModel.launchSafely { viewModel.repository.saveCategoryIfNew("commission_categories", cat) } },
      onDismiss = { showCommissionPopup = false },
      onSave = { commissionItems = it; showCommissionPopup = false },
    )
  }
}

@Composable
private fun LineItemsPopupDialog(
  chip: String,
  caption: String,
  categoryPlaceholder: String,
  initialItems: List<LineItemBreakdown>,
  knownCategories: List<String>,
  onNewCategory: (String) -> Unit,
  onDismiss: () -> Unit,
  onSave: (List<LineItemBreakdown>) -> Unit,
) {
  val palette = LocalSarimPalette.current
  var items by remember { mutableStateOf(initialItems) }
  var catText by remember { mutableStateOf("") }
  var amtText by remember { mutableStateOf("") }
  var unsavedWarning by remember { mutableStateOf<String?>(null) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
      usePlatformDefaultWidth = false,
    ),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(0.92f)
        .widthIn(max = 460.dp),
      shape = RoundedCornerShape(32.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
      Column(
        modifier = Modifier
          .padding(18.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          SarimChip(chip, palette.green)
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(48.dp).testTag("popup_close_btn"),
          ) {
            Icon(Icons.Rounded.Close, contentDescription = "Close popup", tint = palette.textMuted, modifier = Modifier.size(18.dp))
          }
        }
        Text(caption, color = palette.textMuted, fontSize = 12.sp)
        SarimSearchFieldWithDropdown(
          value = catText,
          onValueChange = { catText = it },
          label = "Category",
          placeholder = categoryPlaceholder,
          suggestions = knownCategories,
          onSuggestionSelected = { catText = it },
          testTag = "popup_category_dropdown",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
          SarimTextField(
            value = amtText,
            onValueChange = { amtText = it },
            label = "Amount",
            prefixText = "Rs",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
            testTag = "popup_amount_input",
          )
          IconButton(
            onClick = {
              val amt = SarimFormat.parseSafeDouble(amtText) ?: 0.0
              if (catText.isNotBlank() && amt > 0.0) {
                items = items + LineItemBreakdown(catText.trim().take(60), amt)
                catText = ""; amtText = ""
              }
            },
            modifier = Modifier.size(48.dp).testTag("popup_add_item_btn"),
          ) {
            Icon(Icons.Rounded.Add, contentDescription = "Add line item", tint = palette.green)
          }
        }
        items.forEachIndexed { idx, row ->
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(row.category, color = palette.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(SarimFormat.formatRs(row.amount), color = palette.green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            IconButton(
              onClick = {
                if (idx in items.indices) {
                  items = items.toMutableList().apply { removeAt(idx) }
                }
              },
              modifier = Modifier.size(40.dp).testTag("popup_remove_item_$idx"),
            ) {
              Icon(Icons.Rounded.Close, contentDescription = "Remove item", tint = palette.red, modifier = Modifier.size(16.dp))
            }
          }
        }
        Text("Total paid: ${SarimFormat.formatRs(items.sumOf { it.amount })}", color = palette.amber, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
        unsavedWarning?.let { msg ->
          SarimCallout(msg, palette.amber)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          SarimSecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f), testTag = "popup_cancel_btn")
          SarimPrimaryButton(
            text = if (unsavedWarning != null) "Save Anyway" else "Save",
            onClick = {
              val pendingAmt = SarimFormat.parseSafeDouble(amtText) ?: 0.0
              if (unsavedWarning == null && catText.isNotBlank() && pendingAmt > 0.0) {
                unsavedWarning = "You entered \"${catText.trim()}\" (${SarimFormat.formatRs(pendingAmt)}) without tapping Add. Tap Add above first, or tap Save Anyway to skip it."
              } else {
                items.forEach { row ->
                  if (knownCategories.none { it.equals(row.category, ignoreCase = true) }) {
                    onNewCategory(row.category)
                  }
                }
                onSave(items)
              }
            },
            modifier = Modifier.weight(1f),
            testTag = "popup_save_btn",
          )
        }
      }
    }
  }
}

@Composable
private fun CalcResultLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
  val palette = LocalSarimPalette.current
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = palette.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
    Text(value, color = valueColor, style = TabularNumberStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold))
  }
}
