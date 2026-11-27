package com.example.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.CalculatorSettlementEntity
import com.example.data.local.JsonCodecs
import com.example.data.model.LineItemBreakdown
import com.example.data.model.TimePeriod
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlin.math.abs

private enum class CompChartMetric(val label: String) {
  NET_PROFIT("Net Profit (Value)"),
  GROSS_REV("Gross Revenue (Value)"),
  QTY_SOLD("Quantity Sold"),
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
  var pickingFrom by remember { mutableStateOf(false) }
  var pickingTo by remember { mutableStateOf(false) }

  // Comparison state
  var compPeriod by remember { mutableStateOf(TimePeriod.ALL_TIME) }
  var compMetric by remember { mutableStateOf(CompChartMetric.NET_PROFIT) }

  // Individual state
  var sharedQtyText by remember { mutableStateOf("0") }
  var returnStoreKey by remember(state.stores) { mutableStateOf(state.stores.firstOrNull()?.storeKey ?: "zubair") }
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

  val isComparison = selectedRepOption == "COMPARISON"

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
          SarimDatePickerField("From", fromDate, onDateSelected = { fromDate = it }, modifier = Modifier.weight(1f), testTag = "calc_from_date")
          SarimDatePickerField("To", toDate, onDateSelected = { toDate = it }, modifier = Modifier.weight(1f), testTag = "calc_to_date")
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
                HorizontalDivider(color = palette.border)
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
      // Individual Representative Mode (Section 9.3)
      val fromStart = SarimFormat.startOfDay(fromDate)
      val toEnd = SarimFormat.endOfDay(toDate)
      val repSales = state.sales.filter {
        it.repName.equals(selectedRepOption, ignoreCase = true) && it.dateEpoch in fromStart..toEnd
      }
      val totalSoldKg = repSales.filter { it.mode == "SALE" }.sumOf { it.quantityKg }
      val returnedKg = repSales.sumOf { it.returnedQtyKg }
      val expiredKg = repSales.sumOf { it.expiredQtyKg }
      val sharedNum = SarimFormat.parseSafeDouble(sharedQtyText) ?: 0.0
      val netSoldKg = (totalSoldKg - returnedKg - expiredKg - sharedNum).coerceAtLeast(0.0)
      val avgUnitPrice = if (totalSoldKg > 0.0001) repSales.filter { it.mode == "SALE" }.sumOf { it.totalValue } / totalSoldKg else 400.0
      val totalCost = repSales.filter { it.mode == "SALE" }.sumOf { it.totalCost }
      val grossRevenue = netSoldKg * avgUnitPrice
      val creditQtyNum = SarimFormat.parseSafeDouble(creditSalesQtyText) ?: 0.0
      val creditValue = creditQtyNum * avgUnitPrice
      val commPerUnit = SarimFormat.parseSafeDouble(commissionPerUnitText) ?: 0.0
      val grossCommission = netSoldKg * commPerUnit
      val commPaidTotal = commissionItems.sumOf { it.amount }
      val commPayable = grossCommission - commPaidTotal
      val recoveredNum = SarimFormat.parseSafeDouble(recoveredCreditText) ?: 0.0
      val fieldExpTotal = expenseItems.sumOf { it.amount }
      val expectedCash = (grossRevenue - creditValue + recoveredNum - fieldExpTotal - commPaidTotal)
      val receivedNum = SarimFormat.parseSafeDouble(receivedCashText) ?: 0.0
      val cashDiff = receivedNum - expectedCash
      val statusLabel = when {
        abs(cashDiff) <= 1.0 -> "OK"
        cashDiff < 0 -> "Short"
        else -> "Over"
      }
      val statusColor = when (statusLabel) {
        "OK" -> palette.green
        "Short" -> palette.red
        else -> palette.amber
      }

      item {
        SarimCard {
          Text("Product Quantities", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimTextField(SarimFormat.formatQty(totalSoldKg), {}, "Total Sold Qty", readOnly = true, suffixText = "kg", modifier = Modifier.weight(1f))
            SarimTextField(SarimFormat.formatQty(returnedKg), {}, "Returned Qty", readOnly = true, suffixText = "kg", modifier = Modifier.weight(1f))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimTextField(SarimFormat.formatQty(expiredKg), {}, "Expired / Damage", readOnly = true, suffixText = "kg", modifier = Modifier.weight(1f))
            SarimTextField(sharedQtyText, { sharedQtyText = it }, "Shared (Deduction)", suffixText = "kg", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
          }
          SarimTextField(SarimFormat.formatQty(netSoldKg), {}, "Net Sold Qty", readOnly = true, dashedReadOnly = true, suffixText = "kg")

          if (returnedKg > 0.0 && state.stores.isNotEmpty()) {
            SarimFieldLabel("Return Stock To Physical Inventory")
            SarimToggleGroup(
              state.stores,
              state.stores.firstOrNull { it.storeKey == returnStoreKey } ?: state.stores.first(),
              { it.name },
              { returnStoreKey = it.storeKey },
            )
            Text("This will increase the store's stock level without creating a new production record.", color = palette.textMuted, fontSize = 11.sp)
          }

          if (expiredKg > 0.0) {
            SarimCallout("Expired Stock — Added to CHORA Raw Material (${SarimFormat.formatKg(expiredKg)})", palette.amber)
          }

          SarimTextField(creditSalesQtyText, { creditSalesQtyText = it }, "Credit Sales Qty", suffixText = "kg", keyboardType = KeyboardType.Decimal)

          HorizontalDivider(color = palette.border)
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

          HorizontalDivider(color = palette.border)
          Text("Collections & Handover Cash", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimTextField(recoveredCreditText, { recoveredCreditText = it }, "Recovered Credit", prefixText = "Rs", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
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
          SarimTextField(receivedCashText, { receivedCashText = it }, "Physical Cash Received from Rep", prefixText = "Rs", keyboardType = KeyboardType.Decimal)

          // Result Card
          Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceElevated, border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Expected Cash: ${SarimFormat.formatRs(expectedCash, true, hideMoney)}", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                SarimChip("$statusLabel ${if (statusLabel != "OK") SarimFormat.formatRs(abs(cashDiff), false, hideMoney) else ""}".trim(), statusColor)
              }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SarimStatTile("Gross Comm", SarimFormat.formatRs(grossCommission, false, hideMoney), Modifier.weight(1f), palette.cyan)
                SarimStatTile("Paid", SarimFormat.formatRs(commPaidTotal, false, hideMoney), Modifier.weight(1f), palette.amber)
                SarimStatTile("Payable", SarimFormat.formatRs(commPayable, false, hideMoney), Modifier.weight(1f), palette.green)
              }
            }
          }

          SarimCheckboxRow(
            checked = addToLedger,
            onCheckedChange = { addToLedger = it },
            label = "Add cash received, expenses and commission to Daily Ledger",
          )

          SarimPrimaryButton(
            text = "Save Transaction",
            onClick = {
              if (SarimFormat.isFutureDate(toDate)) {
                viewModel.showDialog(AppDialogRequest("Invalid Date", "Settlement date cannot be in the future.", confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              val commitSettlement = {
                viewModel.launchSafely(
                  errorTitle = "Settlement Save Failed",
                  successMessage = "Settlement saved for $selectedRepOption",
                  onSuccess = {
                    receivedCashText = ""
                    commissionItems = emptyList()
                    expenseItems = emptyList()
                  },
                ) {
                  viewModel.repository.saveCalculatorSettlement(
                    CalculatorSettlementEntity(
                      repName = selectedRepOption,
                      fromDateEpoch = fromDate,
                      toDateEpoch = toDate,
                      dateEpoch = toDate,
                      totalSoldKg = totalSoldKg,
                      returnedKg = returnedKg,
                      expiredKg = expiredKg,
                      sharedDeductionKg = sharedNum,
                      netSoldKg = netSoldKg,
                      returnStoreKey = returnStoreKey,
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
              if (statusLabel != "OK") {
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Settlement",
                    body = "Cash is ${statusLabel.lowercase()} by ${SarimFormat.formatRs(abs(cashDiff))}. Save anyway?",
                    confirmLabel = "Save",
                    onConfirm = { commitSettlement() },
                  )
                )
              } else {
                commitSettlement()
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
          TextButton(onClick = { showExpensesBreakdown = !showExpensesBreakdown }) {
            Text(if (showExpensesBreakdown) "Hide Expenses breakdown" else "Expenses breakdown", color = palette.cyan)
          }
          AnimatedVisibility(visible = showExpensesBreakdown) {
            val allExp = periodFiltered.flatMap { JsonCodecs.decodeLineItems(it.expenseItemsJson) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              allExp.forEach { Text("• ${it.category}: ${SarimFormat.formatRs(it.amount, true, hideMoney)}", color = palette.textMuted, fontSize = 12.sp) }
            }
          }
          TextButton(onClick = { showCommissionBreakdown = !showCommissionBreakdown }) {
            Text(if (showCommissionBreakdown) "Hide Commission breakdown" else "Commission breakdown", color = palette.green)
          }
          AnimatedVisibility(visible = showCommissionBreakdown) {
            val allComm = periodFiltered.flatMap { JsonCodecs.decodeLineItems(it.commissionItemsJson) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              allComm.forEach { Text("• ${it.category}: ${SarimFormat.formatRs(it.amount, true, hideMoney)}", color = palette.textMuted, fontSize = 12.sp) }
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
        val records = repSettlements.map { s ->
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
          )
        }
        AccordionHistoryList(
          records = records,
          yearSummary = { items -> "${items.size} settlements" },
          monthSummary = { items -> "${items.size} · Cash ${SarimFormat.formatRs(items.sumOf { it.item.receivedCash }, false, hideMoney)}" },
          daySummary = { items -> "${items.size} entries" },
        ) { rec ->
          val s = rec.item
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SarimStatTile("Expected", SarimFormat.formatRs(s.expectedCash, false, hideMoney), Modifier.weight(1f))
              SarimStatTile("Expenses", SarimFormat.formatRs(s.fieldExpenses, false, hideMoney), Modifier.weight(1f), palette.red)
              SarimStatTile("Commission", SarimFormat.formatRs(s.commissionPaid, false, hideMoney), Modifier.weight(1f), palette.amber)
            }
            SarimDangerButton(
              text = "Delete Settlement",
              onClick = {
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Delete Settlement",
                    body = "Unlink reconciled sales, reverse return/CHORA changes, and move this settlement to Recycle Bin?",
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
              },
            )
          }
        }
      }
    }
  }

  if (showExpensesPopup) {
    LineItemsPopupDialog(
      chip = "Expenses",
      caption = "Search or type a category, tap it, add the amount and save. The field shows the total paid.",
      categoryPlaceholder = "Search or type a category (e.g. Fuel)",
      initialItems = expenseItems,
      knownCategories = listOf("Fuel", "Food", "Toll", "Repair", "Loading"),
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
      knownCategories = listOf("Advance", "Bonus", "Weekly Payout"),
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
  onDismiss: () -> Unit,
  onSave: (List<LineItemBreakdown>) -> Unit,
) {
  val palette = LocalSarimPalette.current
  var items by remember { mutableStateOf(initialItems) }
  var catText by remember { mutableStateOf("") }
  var amtText by remember { mutableStateOf("") }

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
      shape = RoundedCornerShape(28.dp),
      color = palette.surface.copy(alpha = 0.96f),
      border = BorderStroke(1.dp, palette.green.copy(alpha = 0.45f)),
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          SarimSecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f), testTag = "popup_cancel_btn")
          SarimPrimaryButton("Save", onClick = { onSave(items) }, modifier = Modifier.weight(1f), testTag = "popup_save_btn")
        }
      }
    }
  }
}
