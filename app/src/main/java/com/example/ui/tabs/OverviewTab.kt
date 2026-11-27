package com.example.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.navigation.SarimRoute
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.theme.storeColorForKey
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlin.math.abs

@Composable
fun OverviewTab(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val snap = state.snapshot ?: return
  val hideMoney = state.hideMoneyFigures
  var fixingFinding by remember { mutableStateOf<AuditFinding?>(null) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Header row + 3M / 6M / 12M
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          ShimmerHeading("Overview", fontSizeSp = 26)
          Text(
            "Updated ${SarimFormat.formatTime(state.lastUpdatedMillis)}",
            color = palette.textMuted,
            fontSize = 11.5.sp,
          )
        }
        Box(modifier = Modifier.width(168.dp)) {
          SarimToggleGroup(
            options = OverviewRange.entries,
            selected = state.overviewRange,
            labelOf = { it.label },
            onSelect = { viewModel.setOverviewRange(it) },
            testTagPrefix = "overview_range",
          )
        }
      }
    }

    // 2. KPI Grid (8 tiles in 2 columns)
    item {
      val monthly = snap.monthlyRows
      val profitWave = monthly.map { it.netProfit }
      val salesWave = monthly.map { it.salesValue }
      val prodWave = monthly.map { it.producedKg }
      val inWave = monthly.map { it.cashIn }

      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          KpiWaveTile(
            title = "Net profit",
            value = SarimFormat.formatRs(snap.netProfit, privacyHidden = hideMoney),
            subLine = "Margin ${pctStr(snap.netProfit, snap.salesTotal)} · ${SarimFormat.formatKg(snap.producedKg)}",
            wave = profitWave,
            accent = palette.green,
            highlighted = true,
            modifier = Modifier.weight(1f),
          )
          KpiWaveTile(
            title = "Sales",
            value = SarimFormat.formatRs(snap.salesTotal, privacyHidden = hideMoney),
            subLine = "${snap.salesCount} sales",
            wave = salesWave,
            accent = palette.cyan,
            modifier = Modifier.weight(1f),
            testTag = "kpi_sales_tile",
            onClick = { viewModel.openRoute(SarimRoute.Sales) },
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          KpiWaveTile(
            title = "Produced (kg)",
            value = SarimFormat.formatKg(snap.producedKg),
            subLine = "Returns: ${SarimFormat.formatKg(snap.returnsKg)}",
            wave = prodWave,
            accent = palette.purple,
            modifier = Modifier.weight(1f),
            testTag = "kpi_production_tile",
            onClick = { viewModel.openRoute(SarimRoute.Production) },
          )
          KpiWaveTile(
            title = "Remaining (kg)",
            value = SarimFormat.formatKg(snap.remainingKg),
            subLine = "In ${SarimFormat.formatQty(snap.storeMetrics.values.sumOf { it.producedKg }, 1)} · Sold ${SarimFormat.formatQty(snap.storeMetrics.values.sumOf { it.soldKg }, 1)}",
            wave = prodWave,
            accent = palette.amber,
            modifier = Modifier.weight(1f),
            testTag = "kpi_remaining_tile",
            onClick = { viewModel.openRoute(SarimRoute.Production) },
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          KpiWaveTile(
            title = "Net cash",
            value = SarimFormat.formatRs(snap.cashIn - snap.cashOut, privacyHidden = hideMoney),
            subLine = "In ${SarimFormat.formatRs(snap.cashIn, false, hideMoney)} · Out ${SarimFormat.formatRs(snap.cashOut, false, hideMoney)}",
            wave = inWave,
            accent = palette.green,
            modifier = Modifier.weight(1f),
            testTag = "kpi_ledger_tile",
            onClick = { viewModel.openRoute(SarimRoute.Ledger) },
          )
          KpiWaveTile(
            title = "Expenses",
            value = SarimFormat.formatRs(snap.expensesTotal, privacyHidden = hideMoney),
            subLine = "Operating costs",
            wave = monthly.map { it.expenses },
            accent = palette.red,
            modifier = Modifier.weight(1f),
            testTag = "kpi_expenses_tile",
            onClick = { viewModel.openRoute(SarimRoute.Payments) },
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          KpiWaveTile(
            title = "Owed to you",
            value = SarimFormat.formatRs(snap.owedToYou, privacyHidden = hideMoney),
            subLine = "${snap.unpaidSalesCount} credit sales",
            wave = salesWave,
            accent = palette.amber,
            modifier = Modifier.weight(1f),
            testTag = "kpi_owed_tile",
            onClick = { viewModel.openRoute(SarimRoute.Payments) },
          )
          KpiWaveTile(
            title = "Stock",
            value = SarimFormat.formatRs(snap.totalStockValue, privacyHidden = hideMoney),
            subLine = "${state.rawMaterials.size} materials · ${snap.outOfStockCount} out",
            wave = prodWave,
            accent = palette.cyan,
            modifier = Modifier.weight(1f),
            testTag = "kpi_inventory_tile",
            onClick = { viewModel.openRoute(SarimRoute.Factory) },
          )
        }
      }
    }

    // 3. "Right now" strip
    item {
      SarimCard(contentPadding = 12.dp) {
        Text("RIGHT NOW", color = palette.textMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          RightNowPill("Prod", SarimFormat.formatKg(snap.todayProducedKg), palette.purple, Modifier.weight(1f))
          RightNowPill("Sales", SarimFormat.formatRs(snap.todaySalesValue, false, hideMoney), palette.cyan, Modifier.weight(1f))
          RightNowPill("In", SarimFormat.formatRs(snap.todayCashIn, false, hideMoney), palette.green, Modifier.weight(1f))
          RightNowPill("Out", SarimFormat.formatRs(snap.todayCashOut, false, hideMoney), palette.red, Modifier.weight(1f))
        }
      }
    }

    // 4. Panel tabs
    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OverviewPanel.entries.forEach { panel ->
          val active = state.prefs.activeOverviewPanel == panel
          Surface(
            shape = RoundedCornerShape(50),
            color = if (active) palette.green.copy(alpha = 0.16f) else palette.surface,
            border = BorderStroke(1.dp, if (active) palette.green else palette.border),
            modifier = Modifier
              .defaultMinSize(minHeight = 42.dp)
              .clip(RoundedCornerShape(50))
              .clickable { viewModel.setOverviewPanel(panel) },
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                panel.label,
                color = if (active) palette.green else palette.textPrimary,
                fontSize = 12.5.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
              )
              if (panel == OverviewPanel.CHECKS && snap.auditFindings.isNotEmpty()) {
                val hasError = snap.auditFindings.any { it.severity == "ERROR" }
                SarimChip(
                  snap.auditFindings.size.toString(),
                  if (hasError) palette.red else palette.amber,
                )
              }
            }
          }
        }
      }
    }

    // 5. Panel Content
    when (state.prefs.activeOverviewPanel) {
      OverviewPanel.SALES -> {
        item {
          val labels = snap.monthlyRows.map { it.monthLabel.take(3) }
          SarimCard {
            Text("Money trends", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            MultiLineTrendChart(
              labels = labels,
              seriesList = listOf(
                ChartSeries("Sales", snap.monthlyRows.map { it.salesValue }, 0xFF38BDF8L),
                ChartSeries("Net profit", snap.monthlyRows.map { it.netProfit }, 0xFF34D399L),
                ChartSeries("Sales profit", snap.monthlyRows.map { it.salesProfit }, 0xFFA78BFAL),
                ChartSeries("Cash in", snap.monthlyRows.map { it.cashIn }, 0xFF2DD4BFL),
                ChartSeries("Cash out", snap.monthlyRows.map { it.cashOut }, 0xFFF59E0BL),
                ChartSeries("Expenses", snap.monthlyRows.map { it.expenses }, 0xFFF87171L),
              ),
              privacyHidden = hideMoney,
            )
          }
        }
        item {
          SarimCard {
            Text("Net profit by month", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            ColumnBarChart(
              labels = snap.monthlyRows.map { it.monthLabel.take(3) },
              values = snap.monthlyRows.map { it.netProfit.coerceAtLeast(0.0) },
              colors = snap.monthlyRows.map { if (it.netProfit >= 0) palette.green else palette.red },
            )
          }
        }
        item {
          SarimCard {
            Text("Sales by customer over time", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val (cLabels, cSeries) = snap.salesByCustomerTrend
            if (cSeries.isEmpty()) {
              Text("No customer sales trends in this range.", color = palette.textMuted, fontSize = 12.5.sp)
            } else {
              MultiLineTrendChart(labels = cLabels, seriesList = cSeries, privacyHidden = hideMoney)
            }
          }
        }
        item {
          SarimCard {
            Text("Sales by store over time", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val (sLabels, sSeries) = snap.salesByStoreTrend
            if (sSeries.isEmpty()) {
              Text("No store sales trends in this range.", color = palette.textMuted, fontSize = 12.5.sp)
            } else {
              MultiLineTrendChart(labels = sLabels, seriesList = sSeries, privacyHidden = hideMoney)
            }
          }
        }
        item {
          SarimCard {
            Text("Stock movement (kg)", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val soldTotalKg = snap.storeMetrics.values.sumOf { it.soldKg }
            val prodPlusRetKg = snap.storeMetrics.values.sumOf { it.producedKg } + snap.returnsKg
            RankedHorizontalBars(
              items = listOf(
                RankedBarItem("Sold", soldTotalKg, SarimFormat.formatKg(soldTotalKg)),
                RankedBarItem("Remaining", snap.remainingKg, SarimFormat.formatKg(snap.remainingKg)),
                RankedBarItem("Production + returns in", prodPlusRetKg, SarimFormat.formatKg(prodPlusRetKg)),
              ),
              barColor = palette.cyan,
            )
          }
        }
        item {
          SarimCard {
            Text("Sold by customer (kg)", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val custKgBars = state.customers
              .filter { it.totalQuantityKg > 0.01 }
              .sortedByDescending { it.totalQuantityKg }
              .take(6)
              .map {
                RankedBarItem(
                  label = it.name,
                  value = it.totalQuantityKg,
                  secondaryText = "${SarimFormat.formatKg(it.totalQuantityKg)} · ${SarimFormat.formatRs(it.totalSaleValue, false, hideMoney)}",
                  id = it.id,
                )
              }
            RankedHorizontalBars(
              items = custKgBars,
              barColor = palette.purple,
              emptyMessage = "No customer quantities recorded yet.",
              onItemClick = { item ->
                if (item.id.isNotBlank()) viewModel.openStandalone(StandaloneScreen.CustomerDetails(item.id))
              },
            )
          }
        }
        item {
          SarimCard {
            Text("Who owes you", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SarimStatTile("Total owed", SarimFormat.formatRs(snap.owedToYou, false, hideMoney), Modifier.weight(1f), palette.amber)
              SarimStatTile("≤ 30d", SarimFormat.formatRs(snap.owedAgingUpTo30, false, hideMoney), Modifier.weight(1f), palette.green)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SarimStatTile("31–60d", SarimFormat.formatRs(snap.owedAging31To60, false, hideMoney), Modifier.weight(1f), palette.amber)
              SarimStatTile("> 60d", SarimFormat.formatRs(snap.owedAgingOver60, false, hideMoney), Modifier.weight(1f), palette.red)
            }
            val topDebtors = state.customers.filter { it.currentCredit > 0.01 }
              .sortedByDescending { it.currentCredit }
              .take(6)
              .map { cust ->
                val days = snap.customerOldestDays[cust.id] ?: 0
                val ageSuffix = if (days > 0) " (${days}d)" else ""
                RankedBarItem(
                  label = cust.name,
                  value = cust.currentCredit,
                  secondaryText = "${SarimFormat.formatRs(cust.currentCredit, true, hideMoney)}$ageSuffix",
                  id = cust.id,
                )
              }
            RankedHorizontalBars(
              items = topDebtors,
              barColor = palette.amber,
              emptyMessage = "No outstanding customer balances.",
              onItemClick = { item ->
                if (item.id.isNotBlank()) viewModel.openStandalone(StandaloneScreen.CustomerDetails(item.id))
              },
            )
          }
        }
        item {
          SarimCard {
            Text("Top customers", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val topCust = state.customers.filter { it.totalSaleValue > 0.0 }
              .sortedByDescending { it.totalSaleValue }
              .take(6)
              .map {
                RankedBarItem(
                  label = it.name,
                  value = it.totalSaleValue,
                  secondaryText = "${SarimFormat.formatKg(it.totalQuantityKg)} · ${SarimFormat.formatRs(it.totalSaleValue, false, hideMoney)}",
                  id = it.id,
                )
              }
            RankedHorizontalBars(
              items = topCust,
              barColor = palette.green,
              onItemClick = { item ->
                if (item.id.isNotBlank()) viewModel.openStandalone(StandaloneScreen.CustomerDetails(item.id))
              },
            )
          }
        }
        item {
          SarimCard {
            Text("Sales by store", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            RankedHorizontalBars(snap.salesByStore, palette.green, emptyMessage = "No store sales recorded in this range.")
          }
        }
        item {
          val cashTotal = state.sales.filter { it.mode == "SALE" && it.paymentType == "CASH" }.sumOf { it.totalValue }
          val creditTotal = state.sales.filter { it.mode == "SALE" && it.paymentType == "CREDIT" }.sumOf { it.totalValue }
          SarimCard {
            Text("Cash and credit sales", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            DonutShareChart(
              slices = listOf(
                Triple("Cash", cashTotal, palette.green),
                Triple("Credit", creditTotal, palette.amber),
              ),
              centerLabel = "Split",
            )
          }
        }
      }

      OverviewPanel.PRODUCTION -> {
        item {
          SarimCard {
            Text("Production (kg)", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            ColumnBarChart(
              labels = snap.monthlyRows.map { it.monthLabel.take(3) },
              values = snap.monthlyRows.map { it.producedKg },
              colors = snap.monthlyRows.map { palette.purple },
            )
          }
        }
        item {
          SarimCard {
            Text("Production by formula over time", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val (pfLabels, pfSeries) = snap.prodByFormulaTrend
            if (pfSeries.isEmpty()) {
              Text("No formula production trends in this range.", color = palette.textMuted, fontSize = 12.5.sp)
            } else {
              MultiLineTrendChart(labels = pfLabels, seriesList = pfSeries, privacyHidden = false)
            }
          }
        }
        item {
          SarimCard {
            Text("Production by store over time", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val (psLabels, psSeries) = snap.prodByStoreTrend
            if (psSeries.isEmpty()) {
              Text("No store production trends in this range.", color = palette.textMuted, fontSize = 12.5.sp)
            } else {
              MultiLineTrendChart(labels = psLabels, seriesList = psSeries, privacyHidden = false)
            }
          }
        }
        item {
          SarimCard {
            Text("Factory output (units)", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (snap.factoryOutputByMonth.any { it > 0.0 }) {
              ColumnBarChart(
                labels = snap.monthlyRows.map { it.monthLabel.take(3) },
                values = snap.factoryOutputByMonth,
                colors = snap.monthlyRows.map { palette.purple },
              )
            } else {
              Text("No factory batches recorded in this range.", color = palette.textMuted, fontSize = 12.5.sp)
            }
          }
        }
        item {
          SarimCard {
            Text("Production by formula", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            RankedHorizontalBars(snap.prodByFormula, palette.cyan, emptyMessage = "No formula production recorded.")
          }
        }
        item {
          SarimCard {
            Text("Production by store", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val storeBars = snap.storeMetrics.values
              .sortedByDescending { it.producedKg }
              .map { RankedBarItem(it.store.name, it.producedKg, SarimFormat.formatKg(it.producedKg)) }
            RankedHorizontalBars(storeBars, palette.cyan)
          }
        }
        item {
          SarimCard {
            Text("Factory output by formula", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            RankedHorizontalBars(snap.factoryOutputByFormula, palette.purple, emptyMessage = "No factory output recorded.")
          }
        }
        item {
          SarimCard {
            Text("Formula cost per kg", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val fBars = state.formulas.map { f ->
              val cost = snap.formulaSummaries[f.id]?.costPerKgAdjusted ?: 0.0
              val linkedSuffix = if (snap.formulaLinkedMap[f.id] == true) " (linked)" else ""
              RankedBarItem("${f.name}$linkedSuffix", cost, "${SarimFormat.formatRs(cost, true, hideMoney)}/kg", "${SarimFormat.formatQty(f.unitsAvailable, 1)} u")
            }
            RankedHorizontalBars(fBars, palette.purple)
          }
        }
      }

      OverviewPanel.MONEY -> {
        item {
          SarimCard {
            Text("Cash in and out", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            MultiLineTrendChart(
              labels = snap.monthlyRows.map { it.monthLabel.take(3) },
              seriesList = listOf(
                ChartSeries("Cash In", snap.monthlyRows.map { it.cashIn }, 0xFF4ADE80),
                ChartSeries("Cash Out", snap.monthlyRows.map { it.cashOut }, 0xFFF87171),
                ChartSeries("Expenses", snap.monthlyRows.map { it.expenses }, 0xFFFBBF24),
              ),
              privacyHidden = hideMoney,
            )
          }
        }
        item {
          SarimCard {
            Text("Expenses by category", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val expBars = state.paymentTransactions
              .filter { it.type == "EXPENSE" }
              .groupBy { it.entityOrExpenseName }
              .map { (k, list) -> RankedBarItem(k, list.sumOf { it.amount }, SarimFormat.formatRs(list.sumOf { it.amount }, true, hideMoney)) }
              .sortedByDescending { it.value }
            RankedHorizontalBars(expBars, palette.red)
          }
        }
      }

      OverviewPanel.STOCK -> {
        item {
          SarimCard {
            Text("Stock by value", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val stockBars = state.rawMaterials
              .map { m -> RankedBarItem(m.name, m.stockKg * m.avgCostPerKg, "${SarimFormat.formatKg(m.stockKg)} · ${SarimFormat.formatRs(m.stockKg * m.avgCostPerKg, false, hideMoney)}") }
              .sortedByDescending { it.value }
            RankedHorizontalBars(stockBars, palette.cyan)
          }
        }
        item {
          val oos = state.rawMaterials.filter { !it.name.equals("CHORA", ignoreCase = true) && it.stockKg <= 0.001 }
          SarimCard {
            Text("Out of stock", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (oos.isEmpty()) {
              Text("All active raw materials have stock.", color = palette.green, fontSize = 13.sp)
            } else {
              Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                oos.forEach { SarimChip(it.name, palette.red) }
              }
            }
          }
        }
      }

      OverviewPanel.CHECKS -> {
        item {
          val errs = snap.auditFindings.count { it.severity == "ERROR" }
          val warns = snap.auditFindings.count { it.severity == "WARNING" }
          val chipText = when {
            errs > 0 -> "$errs errors"
            warns > 0 -> "$warns warnings"
            else -> "Clean"
          }
          val chipColor = when {
            errs > 0 -> palette.red
            warns > 0 -> palette.amber
            else -> palette.green
          }
          SarimCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Text("Profit and data check", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
              SarimChip(chipText, chipColor)
            }
            if (snap.auditFindings.any { it.recordType == "LINK_REPAIR" }) {
              SarimSecondaryButton(
                text = "Repair All Sale / Collection Links",
                onClick = { viewModel.repairSaleLinks() },
                modifier = Modifier.fillMaxWidth(),
                testTag = "ov_repair_links_btn",
              )
            }
            if (snap.auditFindings.isEmpty()) {
              SarimCallout("No negative-margin sales, zero-weight batches, or stock anomalies detected.", palette.green)
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snap.auditFindings.forEach { finding ->
                  val sevColor = if (finding.severity == "ERROR") palette.red else palette.amber
                  Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(1.dp, sevColor.copy(alpha = 0.45f)),
                    modifier = Modifier
                      .fillMaxWidth()
                      .clip(RoundedCornerShape(16.dp))
                      .clickable { fixingFinding = finding },
                  ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(finding.title, color = palette.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        SarimChip("Tap to fix", sevColor)
                      }
                      Text(SarimFormat.formatDate(finding.dateEpoch), color = palette.textMuted, fontSize = 11.sp)
                      finding.reasons.forEach { r ->
                        Text("• $r", color = palette.textMuted, fontSize = 12.sp)
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }

      OverviewPanel.ACTIVITY -> {
        item {
          SarimCard {
            Text("Recent activity", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (snap.recentActivity.isEmpty()) {
              Text("No activity recorded in this period.", color = palette.textMuted, fontSize = 12.5.sp)
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snap.recentActivity.forEach { act ->
                  val tone = Color(act.toneHex)
                  Surface(shape = RoundedCornerShape(14.dp), color = palette.surfaceElevated) {
                    Row(
                      modifier = Modifier.fillMaxWidth().padding(10.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                      Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(tone))
                      Column(modifier = Modifier.weight(1f)) {
                        Text(act.title, color = palette.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${act.subtitle} · ${SarimFormat.formatDate(act.dateEpoch)}", color = palette.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                      }
                      Text(
                        act.amountText,
                        color = tone,
                        style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                      )
                    }
                  }
                }
              }
            }
          }
        }
        item {
          SarimCard {
            Text("Monthly summary", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              snap.monthlyRows.reversed().forEach { row ->
                Surface(shape = RoundedCornerShape(14.dp), color = palette.surfaceElevated) {
                  Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                      Text(row.monthLabel, color = palette.green, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                      Text("Net ${SarimFormat.formatRs(row.netProfit, false, hideMoney)}", color = if (row.netProfit >= 0) palette.green else palette.red, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
                    }
                    Text(
                      "Prod ${SarimFormat.formatKg(row.producedKg)} · Sales ${SarimFormat.formatRs(row.salesValue, false, hideMoney)} · In ${SarimFormat.formatRs(row.cashIn, false, hideMoney)} · Out ${SarimFormat.formatRs(row.cashOut, false, hideMoney)}",
                      color = palette.textMuted,
                      fontSize = 11.sp,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  // Fix Dialog in Checks panel (Section 6 Interactions)
  fixingFinding?.let { finding ->
    var inputVal by remember(finding.id) { mutableStateOf(finding.currentFieldValue) }
    Dialog(onDismissRequest = { fixingFinding = null }) {
      Surface(
        shape = RoundedCornerShape(28.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border),
      ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(finding.title, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
          Text("${finding.recordType} · ${SarimFormat.formatDate(finding.dateEpoch)}", color = palette.textMuted, fontSize = 11.5.sp)
          finding.reasons.forEach { Text("• $it", color = palette.amber, fontSize = 12.5.sp) }
          Text(finding.note, color = palette.textMuted, fontSize = 12.sp)
          if (finding.recordType != "LINK_REPAIR") {
            SarimTextField(
              value = inputVal,
              onValueChange = { inputVal = it },
              label = "Update · ${finding.suggestedFieldLabel}",
              keyboardType = KeyboardType.Decimal,
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SarimSecondaryButton("Cancel", onClick = { fixingFinding = null }, modifier = Modifier.weight(1f))
            SarimPrimaryButton(
              text = if (finding.recordType == "LINK_REPAIR") "Repair Link" else "Save Fix",
              onClick = {
                viewModel.applyAuditFix(finding, inputVal)
                fixingFinding = null
              },
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun KpiWaveTile(
  title: String,
  value: String,
  subLine: String,
  wave: List<Double>,
  accent: Color,
  modifier: Modifier = Modifier,
  highlighted: Boolean = false,
  testTag: String? = null,
  onClick: (() -> Unit)? = null,
) {
  val palette = LocalSarimPalette.current
  val deltaPct = if (wave.size >= 2 && abs(wave[wave.size - 2]) > 0.01) {
    val prev = wave[wave.size - 2]
    val curr = wave.last()
    ((curr - prev) / abs(prev)) * 100.0
  } else 0.0

  Surface(
    modifier = modifier
      .clip(RoundedCornerShape(24.dp))
      .let { m -> if (testTag != null) m.testTag(testTag) else m }
      .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
    shape = RoundedCornerShape(24.dp),
    color = if (highlighted) accent.copy(alpha = 0.08f) else palette.surface,
    border = BorderStroke(1.dp, if (highlighted) accent.copy(alpha = 0.55f) else palette.border),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (deltaPct != 0.0) {
          val up = deltaPct >= 0
          Text(
            "${if (up) "▲" else "▼"} ${abs(deltaPct).toInt()}%",
            color = if (up) palette.green else palette.red,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }
      Text(
        value,
        color = palette.textPrimary,
        style = TabularNumberStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      SparklineWave(wave, accent)
      Text(subLine, color = palette.textMuted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun RightNowPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
  val palette = LocalSarimPalette.current
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    color = palette.surfaceElevated,
    border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
  ) {
    Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(label, color = palette.textMuted, fontSize = 10.sp)
      Text(value, color = color, style = TabularNumberStyle.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

private fun pctStr(num: Double, den: Double): String {
  if (den <= 0.01) return "0%"
  return "${((num / den) * 100.0).toInt()}%"
}
