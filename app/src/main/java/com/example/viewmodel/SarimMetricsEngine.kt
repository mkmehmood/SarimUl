package com.example.viewmodel

import com.example.data.local.*
import com.example.data.model.AuditFinding
import com.example.data.model.ChartSeries
import com.example.data.model.MonthlySummaryRow
import com.example.data.model.RankedBarItem
import com.example.data.model.RecentActivityItem
import com.example.data.model.TimePeriod
import com.example.data.repository.FormulaCalculator
import com.example.data.repository.FormulaCostSummary
import com.example.util.SarimFormat
import java.util.Calendar
import kotlin.math.abs

data class StoreStockMetrics(
  val store: StoreEntity,
  val producedKg: Double,
  val soldKg: Double,
  val transferredInKg: Double,
  val transferredOutKg: Double,
  val remainingKg: Double,
  val avgCostPerKg: Double,
  val totalValue: Double,
  val totalCost: Double,
  val netProfit: Double,
)

data class BusinessSnapshot(
  val formulaSummaries: Map<String, FormulaCostSummary>,
  val storeMetrics: Map<String, StoreStockMetrics>,
  val repsList: List<String>,
  val netProfit: Double,
  val salesTotal: Double,
  val salesCount: Int,
  val producedKg: Double,
  val returnsKg: Double,
  val remainingKg: Double,
  val cashIn: Double,
  val cashOut: Double,
  val expensesTotal: Double,
  val owedToYou: Double,
  val unpaidSalesCount: Int,
  val totalStockValue: Double,
  val outOfStockCount: Int,
  val todayProducedKg: Double,
  val todaySalesValue: Double,
  val todayCashIn: Double,
  val todayCashOut: Double,
  val auditFindings: List<AuditFinding>,
  val monthlyRows: List<MonthlySummaryRow>,
  val owedAgingUpTo30: Double,
  val owedAging31To60: Double,
  val owedAgingOver60: Double,
  val cashInHandBalance: Double,
  val rawInventoryValue: Double,
  val formulaUnitsInventoryValue: Double,
  val directReceivables: Double,
  val repReceivables: Double,
  val supplierPayables: Double,
  val entityPayables: Double,
  val recentActivity: List<RecentActivityItem> = emptyList(),
  val salesByStore: List<RankedBarItem> = emptyList(),
  val salesByCustomerTrend: Pair<List<String>, List<ChartSeries>> = emptyList<String>() to emptyList(),
  val salesByStoreTrend: Pair<List<String>, List<ChartSeries>> = emptyList<String>() to emptyList(),
  val customerOldestDays: Map<String, Int> = emptyMap(),
  val prodByFormula: List<RankedBarItem> = emptyList(),
  val prodByFormulaTrend: Pair<List<String>, List<ChartSeries>> = emptyList<String>() to emptyList(),
  val prodByStoreTrend: Pair<List<String>, List<ChartSeries>> = emptyList<String>() to emptyList(),
  val factoryOutputByMonth: List<Double> = emptyList(),
  val factoryOutputByFormula: List<RankedBarItem> = emptyList(),
  val formulaLinkedMap: Map<String, Boolean> = emptyMap(),
)

object SarimMetricsEngine {
  private val trendPaletteHex = listOf(
    0xFF34D399L,
    0xFF38BDF8L,
    0xFFF59E0BL,
    0xFFA78BFAL,
    0xFFF87171L,
  )

  fun periodStart(period: TimePeriod, now: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    return when (period) {
      TimePeriod.DAILY -> SarimFormat.startOfDay(now)
      TimePeriod.WEEKLY -> {
        cal.add(Calendar.DAY_OF_YEAR, -6)
        SarimFormat.startOfDay(cal.timeInMillis)
      }
      TimePeriod.MONTHLY -> {
        cal.set(Calendar.DAY_OF_MONTH, 1)
        SarimFormat.startOfDay(cal.timeInMillis)
      }
      TimePeriod.YEARLY -> {
        cal.set(Calendar.DAY_OF_YEAR, 1)
        SarimFormat.startOfDay(cal.timeInMillis)
      }
      TimePeriod.ALL_TIME -> 0L
    }
  }

  fun compute(
    stores: List<StoreEntity>,
    materials: List<RawMaterialEntity>,
    formulas: List<FormulaEntity>,
    production: List<ProductionEntryEntity>,
    sales: List<SaleTransactionEntity>,
    customers: List<CustomerEntity>,
    entities: List<PaymentEntityRecord>,
    payments: List<PaymentTransactionEntity>,
    ledger: List<DailyLedgerEntity>,
    settlements: List<CalculatorSettlementEntity>,
    stockTransfers: List<StockTransferEntity>,
    accounts: List<AppAccountEntity>,
    overviewMonths: Int = 6,
    factoryBatches: List<FactoryBatchEntity> = emptyList(),
    storedReps: List<String> = emptyList(),
  ): BusinessSnapshot {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance().apply {
      timeInMillis = now
      add(Calendar.MONTH, -(overviewMonths - 1))
      set(Calendar.DAY_OF_MONTH, 1)
    }
    val rangeStart = SarimFormat.startOfDay(cal.timeInMillis)
    val todayStart = SarimFormat.startOfDay(now)

    val formulaMap = formulas.associateBy { it.id }
    val materialMap = materials.associateBy { it.id }
    val formulaSummaries = formulas.associate { f ->
      f.id to FormulaCalculator.computeFormulaSummary(f, formulaMap, materialMap)
    }
    val formulaLinkedMap = formulas.associate { f ->
      f.id to JsonCodecs.decodeIngredients(f.ingredientsJson).any { it.refType.equals("FORMULA", ignoreCase = true) }
    }

    val storeMetricsMap = stores.associate { store ->
      val prodList = production.filter { it.storeKey == store.storeKey && !it.isReturn }
      val saleList = sales.filter { it.storeKey == store.storeKey && it.mode == "SALE" && !it.isRepSale }
      val tIn = stockTransfers.filter { it.toStoreKey == store.storeKey }.sumOf { it.quantityKg }
      val tOut = stockTransfers.filter { it.fromStoreKey == store.storeKey }.sumOf { it.quantityKg }
      val produced = prodList.sumOf { it.netWeightKg }
      val sold = saleList.sumOf { it.quantityKg }
      val returned = settlements.filter { it.returnStoreKey == store.storeKey }.sumOf { it.returnedKg }
      val remaining = (produced + tIn + returned - sold - tOut).coerceAtLeast(0.0)
      val totalCost = prodList.sumOf { it.totalFormulaCost }
      val avgCost = if (produced > 0.0001) totalCost / produced else {
        formulaSummaries[store.formulaId]?.costPerKgAdjusted ?: 0.0
      }
      store.storeKey to StoreStockMetrics(
        store = store,
        producedKg = produced,
        soldKg = sold,
        transferredInKg = tIn,
        transferredOutKg = tOut,
        remainingKg = remaining,
        avgCostPerKg = avgCost,
        totalValue = prodList.sumOf { it.totalValue },
        totalCost = totalCost,
        netProfit = prodList.sumOf { it.totalProfit },
      )
    }

    val reps = (
      storedReps +
        accounts.filter { it.approved && it.rolesCsv.contains("REP", ignoreCase = true) }.map { it.name } +
        settlements.map { it.repName } +
        sales.map { it.repName }
      )
      .map { it.trim() }
      .filter { it.isNotBlank() && !it.equals("Direct", ignoreCase = true) }
      .distinctBy { it.uppercase() }
      .sorted()

    val rangeSales = sales.filter { it.dateEpoch >= rangeStart && it.mode == "SALE" }
    val rangeProd = production.filter { it.dateEpoch >= rangeStart && !it.isReturn }
    val rangePay = payments.filter { it.dateEpoch >= rangeStart }
    val rangeLedger = ledger.filter { it.dateEpoch >= rangeStart }
    val rangeBatches = factoryBatches.filter { it.dateEpoch >= rangeStart }

    val salesTotal = rangeSales.sumOf { it.totalValue }
    val salesProfit = rangeSales.sumOf { it.profit }
    val expensesTotal = rangePay.filter { it.type == "EXPENSE" }.sumOf { it.amount }
    val netProfit = salesProfit - expensesTotal
    val producedKg = rangeProd.sumOf { it.netWeightKg }
    val returnsKg = sales.filter { it.dateEpoch >= rangeStart }.sumOf { it.expiredQtyKg + it.returnedQtyKg }
    val totalRemaining = storeMetricsMap.values.sumOf { it.remainingKg }

    val cashIn = rangeLedger.filter { it.type == "IN" }.sumOf { it.amount } +
      rangeSales.filter { it.paymentType == "CASH" && !it.addToLedger }.sumOf { it.totalValue } +
      sales.filter { it.dateEpoch >= rangeStart && it.mode == "COLLECTION" && !it.addToLedger }.sumOf { it.amountReceived } +
      rangePay.filter { it.type == "PAYMENT_IN" && !it.addToLedger }.sumOf { it.amount }

    val cashOut = rangeLedger.filter { it.type == "OUT" }.sumOf { it.amount } +
      rangePay.filter { (it.type == "PAYMENT_OUT" || it.type == "EXPENSE") && !it.addToLedger }.sumOf { it.amount }

    val owedToYou = customers.filter { it.currentCredit > 0.01 }.sumOf { it.currentCredit }
    val unpaidCount = sales.count { it.mode == "SALE" && it.paymentType == "CREDIT" && !it.creditReceived }

    val totalStockVal = materials.sumOf { it.stockKg * it.avgCostPerKg }
    val outOfStockCount = materials.count { !it.name.equals("CHORA", ignoreCase = true) && it.stockKg <= 0.001 }

    val todayProdKg = production.filter { it.dateEpoch >= todayStart && !it.isReturn }.sumOf { it.netWeightKg }
    val todaySalesVal = sales.filter { it.dateEpoch >= todayStart && it.mode == "SALE" }.sumOf { it.totalValue }
    val todayIn = ledger.filter { it.dateEpoch >= todayStart && it.type == "IN" }.sumOf { it.amount }
    val todayOut = ledger.filter { it.dateEpoch >= todayStart && it.type == "OUT" }.sumOf { it.amount }

    // Aging buckets based on each customer's oldest unpaid credit sale
    var upTo30 = 0.0
    var d31To60 = 0.0
    var over60 = 0.0
    val oldestDaysMap = mutableMapOf<String, Int>()
    customers.filter { it.currentCredit > 0.01 }.forEach { cust ->
      val oldest = sales.filter { it.customerId == cust.id && it.mode == "SALE" && it.paymentType == "CREDIT" && !it.creditReceived }
        .minByOrNull { it.dateEpoch }?.dateEpoch
        ?: sales.filter { it.customerId == cust.id && it.mode == "SALE" && it.paymentType == "CREDIT" }
          .minByOrNull { it.dateEpoch }?.dateEpoch
        ?: cust.updatedAt
      val ageDays = ((now - oldest) / (24L * 60L * 60L * 1000L)).coerceAtLeast(0L).toInt()
      oldestDaysMap[cust.id] = ageDays
      when {
        ageDays <= 30 -> upTo30 += cust.currentCredit
        ageDays <= 60 -> d31To60 += cust.currentCredit
        else -> over60 += cust.currentCredit
      }
    }

    // Monthly buckets & summary rows
    val monthWindows = (0 until overviewMonths).map { offset ->
      val mCal = Calendar.getInstance().apply {
        timeInMillis = rangeStart
        add(Calendar.MONTH, offset)
      }
      val mStart = SarimFormat.startOfDay(mCal.timeInMillis)
      mCal.add(Calendar.MONTH, 1)
      val mEnd = mCal.timeInMillis
      Triple(SarimFormat.formatMonthYear(mStart), mStart, mEnd)
    }

    val monthlyRows = monthWindows.map { (label, mStart, mEnd) ->
      val mSales = sales.filter { it.dateEpoch in mStart until mEnd && it.mode == "SALE" }
      val mProd = production.filter { it.dateEpoch in mStart until mEnd && !it.isReturn }
      val mExp = payments.filter { it.dateEpoch in mStart until mEnd && it.type == "EXPENSE" }.sumOf { it.amount }
      val mIn = ledger.filter { it.dateEpoch in mStart until mEnd && it.type == "IN" }.sumOf { it.amount }
      val mOut = ledger.filter { it.dateEpoch in mStart until mEnd && it.type == "OUT" }.sumOf { it.amount }
      val sp = mSales.sumOf { it.profit }
      MonthlySummaryRow(
        monthLabel = label,
        monthStartEpoch = mStart,
        producedKg = mProd.sumOf { it.netWeightKg },
        salesValue = mSales.sumOf { it.totalValue },
        netProfit = sp - mExp,
        salesProfit = sp,
        cashIn = mIn,
        cashOut = mOut,
        expenses = mExp,
      )
    }

    val monthLabels = monthWindows.map { it.first.take(3) }

    val salesByStore = rangeSales
      .groupBy { it.storeName.ifBlank { it.storeKey } }
      .map { (name, list) ->
        RankedBarItem(
          label = name,
          value = list.sumOf { it.totalValue },
          secondaryText = "${list.size} sale${if (list.size == 1) "" else "s"}",
        )
      }
      .sortedByDescending { it.value }

    val topCustomerKeys = rangeSales
      .groupBy { it.customerName.ifBlank { it.customerId } }
      .mapValues { (_, list) -> list.sumOf { it.totalValue } }
      .toList()
      .sortedByDescending { it.second }
      .take(5)
      .map { it.first }
    val salesByCustomerTrend = monthLabels to topCustomerKeys.mapIndexed { idx, custName ->
      ChartSeries(
        label = custName,
        values = monthWindows.map { (_, mStart, mEnd) ->
          rangeSales.filter { it.dateEpoch in mStart until mEnd && it.customerName.ifBlank { it.customerId } == custName }
            .sumOf { it.totalValue }
        },
        colorHex = trendPaletteHex[idx % trendPaletteHex.size],
      )
    }

    val topSalesStoreKeys = salesByStore.take(5).map { it.label }
    val salesByStoreTrend = monthLabels to topSalesStoreKeys.mapIndexed { idx, storeLabel ->
      ChartSeries(
        label = storeLabel,
        values = monthWindows.map { (_, mStart, mEnd) ->
          rangeSales.filter { it.dateEpoch in mStart until mEnd && it.storeName.ifBlank { it.storeKey } == storeLabel }
            .sumOf { it.totalValue }
        },
        colorHex = trendPaletteHex[idx % trendPaletteHex.size],
      )
    }

    val prodByFormula = rangeProd
      .groupBy { it.formulaName.ifBlank { it.formulaId.ifBlank { "Formula" } } }
      .map { (name, list) ->
        RankedBarItem(
          label = name,
          value = list.sumOf { it.netWeightKg },
          secondaryText = "${list.size} batch${if (list.size == 1) "" else "es"}",
        )
      }
      .sortedByDescending { it.value }

    val topProdFormulas = prodByFormula.take(5).map { it.label }
    val prodByFormulaTrend = monthLabels to topProdFormulas.mapIndexed { idx, fName ->
      ChartSeries(
        label = fName,
        values = monthWindows.map { (_, mStart, mEnd) ->
          rangeProd.filter {
            it.dateEpoch in mStart until mEnd &&
              it.formulaName.ifBlank { it.formulaId.ifBlank { "Formula" } } == fName
          }.sumOf { it.netWeightKg }
        },
        colorHex = trendPaletteHex[idx % trendPaletteHex.size],
      )
    }

    val topProdStores = rangeProd
      .groupBy { it.storeName.ifBlank { it.storeKey } }
      .mapValues { (_, list) -> list.sumOf { it.netWeightKg } }
      .toList()
      .sortedByDescending { it.second }
      .take(5)
      .map { it.first }
    val prodByStoreTrend = monthLabels to topProdStores.mapIndexed { idx, sName ->
      ChartSeries(
        label = sName,
        values = monthWindows.map { (_, mStart, mEnd) ->
          rangeProd.filter {
            it.dateEpoch in mStart until mEnd &&
              it.storeName.ifBlank { it.storeKey } == sName
          }.sumOf { it.netWeightKg }
        },
        colorHex = trendPaletteHex[idx % trendPaletteHex.size],
      )
    }

    val factoryOutputByMonth = monthWindows.map { (_, mStart, mEnd) ->
      factoryBatches.filter { it.dateEpoch in mStart until mEnd }.sumOf { it.unitsProduced }
    }
    val factoryOutputByFormula = rangeBatches
      .groupBy { it.formulaName.ifBlank { it.formulaId.ifBlank { "Formula" } } }
      .map { (name, list) ->
        RankedBarItem(
          label = name,
          value = list.sumOf { it.unitsProduced },
          secondaryText = "${list.size} batch${if (list.size == 1) "" else "es"}",
        )
      }
      .sortedByDescending { it.value }

    val recentActivity = buildList {
      rangeSales.forEach { s ->
        add(
          RecentActivityItem(
            id = "sale_${s.id}",
            title = s.customerName.ifBlank { "Sale" },
            subtitle = "Sale · ${s.storeName} · ${SarimFormat.formatKg(s.quantityKg)}",
            amountText = SarimFormat.formatRs(s.totalValue),
            toneHex = 0xFF34D399L,
            dateEpoch = s.dateEpoch,
          )
        )
      }
      rangeProd.forEach { p ->
        add(
          RecentActivityItem(
            id = "prod_${p.id}",
            title = p.storeName,
            subtitle = "Production · ${p.formulaName}",
            amountText = SarimFormat.formatKg(p.netWeightKg),
            toneHex = 0xFF38BDF8L,
            dateEpoch = p.dateEpoch,
          )
        )
      }
      rangePay.forEach { p ->
        val isExp = p.type == "EXPENSE"
        val isIn = p.type == "PAYMENT_IN"
        add(
          RecentActivityItem(
            id = "pay_${p.id}",
            title = p.entityOrExpenseName,
            subtitle = when {
              isExp -> "Expense"
              isIn -> "Payment In"
              else -> "Payment Out"
            } + if (p.description.isNotBlank()) " · ${p.description}" else "",
            amountText = (if (isIn) "+" else "-") + SarimFormat.formatRs(p.amount),
            toneHex = when {
              isIn -> 0xFF34D399L
              isExp -> 0xFFF59E0BL
              else -> 0xFFF87171L
            },
            dateEpoch = p.dateEpoch,
          )
        )
      }
      rangeLedger.filter { it.sourceType == "MANUAL" }.forEach { l ->
        add(
          RecentActivityItem(
            id = "led_${l.id}",
            title = l.party.ifBlank { l.category.ifBlank { l.mode } },
            subtitle = "Ledger ${l.type} · ${l.mode}",
            amountText = SarimFormat.formatRs(l.amount),
            toneHex = when (l.type) {
              "IN" -> 0xFF34D399L
              "OUT" -> 0xFFF87171L
              else -> 0xFFA78BFAL
            },
            dateEpoch = l.dateEpoch,
          )
        )
      }
    }.sortedByDescending { it.dateEpoch }.take(8)

    // Audit Findings for Overview -> Checks panel
    val findings = mutableListOf<AuditFinding>()
    sales.filter { it.mode == "SALE" && it.profit < 0.0 }.forEach { s ->
      findings += AuditFinding(
        id = "loss_sale_${s.id}",
        severity = "WARNING",
        recordType = "SALE",
        recordId = s.id,
        dateEpoch = s.dateEpoch,
        title = "Sale at negative profit · ${s.customerName}",
        reasons = listOf("Sale price (${SarimFormat.formatRs(s.pricePerKg)}/kg) is below cost", "Net loss of ${SarimFormat.formatRs(abs(s.profit))}"),
        note = "Update unit price or review production batch cost.",
        suggestedFieldLabel = "Price per kg (Rs)",
        currentFieldValue = s.pricePerKg.toString(),
      )
    }
    production.filter { !it.isReturn && it.netWeightKg <= 0.0 }.forEach { p ->
      findings += AuditFinding(
        id = "zero_prod_${p.id}",
        severity = "ERROR",
        recordType = "PRODUCTION",
        recordId = p.id,
        dateEpoch = p.dateEpoch,
        title = "Zero net production weight · ${p.storeName}",
        reasons = listOf("Gross weight (${p.grossWeightKg} kg) does not exceed container (${p.containerWeightKg} kg)"),
        note = "Correct gross weight in kg.",
        suggestedFieldLabel = "Gross Weight (kg)",
        currentFieldValue = p.grossWeightKg.toString(),
      )
    }
    materials.filter { !it.name.equals("CHORA", ignoreCase = true) && it.stockKg <= 0.001 }.forEach { m ->
      findings += AuditFinding(
        id = "oos_mat_${m.id}",
        severity = "WARNING",
        recordType = "MATERIAL",
        recordId = m.id,
        dateEpoch = m.updatedAt,
        title = "Out of stock material · ${m.name}",
        reasons = listOf("Current stock is ${SarimFormat.formatKg(m.stockKg)}"),
        note = "Record a new purchase or adjust stock quantity.",
        suggestedFieldLabel = "Stock (kg)",
        currentFieldValue = m.stockKg.toString(),
      )
    }

    val ledgerInAll = ledger.filter { it.type == "IN" }.sumOf { it.amount }
    val ledgerOutAll = ledger.filter { it.type == "OUT" }.sumOf { it.amount }
    val directRec = customers.filter { !it.isRepCustomer && it.currentCredit > 0.0 }.sumOf { it.currentCredit }
    val repRec = customers.filter { it.isRepCustomer && it.currentCredit > 0.0 }.sumOf { it.currentCredit }
    val supPay = materials.sumOf { it.pendingSupplierAmount }
    val entPay = entities.filter { it.currentBalance > 0.0 }.sumOf { it.currentBalance }
    val formulaUnitInv = formulas.sumOf { f ->
      val unitCost = formulaSummaries[f.id]?.totalCostPerUnit ?: 0.0
      f.unitsAvailable * unitCost
    }

    return BusinessSnapshot(
      formulaSummaries = formulaSummaries,
      storeMetrics = storeMetricsMap,
      repsList = reps,
      netProfit = netProfit,
      salesTotal = salesTotal,
      salesCount = rangeSales.size,
      producedKg = producedKg,
      returnsKg = returnsKg,
      remainingKg = totalRemaining,
      cashIn = cashIn,
      cashOut = cashOut,
      expensesTotal = expensesTotal,
      owedToYou = owedToYou,
      unpaidSalesCount = unpaidCount,
      totalStockValue = totalStockVal,
      outOfStockCount = outOfStockCount,
      todayProducedKg = todayProdKg,
      todaySalesValue = todaySalesVal,
      todayCashIn = todayIn,
      todayCashOut = todayOut,
      auditFindings = findings,
      monthlyRows = monthlyRows,
      owedAgingUpTo30 = upTo30,
      owedAging31To60 = d31To60,
      owedAgingOver60 = over60,
      cashInHandBalance = ledgerInAll - ledgerOutAll,
      rawInventoryValue = totalStockVal,
      formulaUnitsInventoryValue = formulaUnitInv,
      directReceivables = directRec,
      repReceivables = repRec,
      supplierPayables = supPay,
      entityPayables = entPay,
      recentActivity = recentActivity,
      salesByStore = salesByStore,
      salesByCustomerTrend = salesByCustomerTrend,
      salesByStoreTrend = salesByStoreTrend,
      customerOldestDays = oldestDaysMap,
      prodByFormula = prodByFormula,
      prodByFormulaTrend = prodByFormulaTrend,
      prodByStoreTrend = prodByStoreTrend,
      factoryOutputByMonth = factoryOutputByMonth,
      factoryOutputByFormula = factoryOutputByFormula,
      formulaLinkedMap = formulaLinkedMap,
    )
  }
}
