package com.example.viewmodel

import com.example.data.local.*
import com.example.data.model.AuditFinding
import com.example.data.model.AuditFixKind
import com.example.data.model.ChartSeries
import com.example.data.model.MonthlySummaryRow
import com.example.data.model.RankedBarItem
import com.example.data.model.RecentActivityItem
import com.example.data.model.TimePeriod
import com.example.data.repository.FormulaCalculator
import com.example.data.repository.FormulaCostSummary
import com.example.util.SarimFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

enum class CalcIndMetric(val label: String) {
  NET_PROFIT("Net Profit"),
  GROSS_REV("Gross Revenue"),
  QTY_SOLD("Qty Sold"),
}

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

  /**
   * Port of `modules/calc-tab.js` `updateIndChart`:
   * Produces bar chart labels and values for a single representative across
   * WEEKLY (7 days ending at referenceDateEpoch), MONTHLY (day 1..daysInMonth),
   * YEARLY (Jan..Dec of referenceDateEpoch's year), or ALL_TIME (last 12 chronological months).
   */
  fun computeCalcIndChartData(
    repSettlements: List<CalculatorSettlementEntity>,
    period: TimePeriod,
    metric: CalcIndMetric,
    referenceDateEpoch: Long = System.currentTimeMillis(),
  ): Pair<List<String>, List<Double>> {
    fun metricValue(s: CalculatorSettlementEntity): Double = when (metric) {
      CalcIndMetric.NET_PROFIT -> s.netProfit.coerceAtLeast(0.0)
      CalcIndMetric.GROSS_REV -> s.grossRevenue
      CalcIndMetric.QTY_SOLD -> s.netSoldKg
    }

    val active = repSettlements.filter { !it.isDeleted }
    val refCal = Calendar.getInstance().apply { timeInMillis = referenceDateEpoch }

    return when (period) {
      TimePeriod.WEEKLY, TimePeriod.DAILY -> {
        val dayFmt = SimpleDateFormat("EEE", Locale.US)
        val labels = mutableListOf<String>()
        val values = mutableListOf<Double>()
        for (i in 6 downTo 0) {
          val c = Calendar.getInstance().apply {
            timeInMillis = referenceDateEpoch
            add(Calendar.DAY_OF_YEAR, -i)
          }
          val key = SarimFormat.dateInput(c.timeInMillis)
          labels += dayFmt.format(c.time)
          values += active.filter { SarimFormat.dateInput(it.dateEpoch) == key }.sumOf(::metricValue)
        }
        labels to values
      }
      TimePeriod.MONTHLY -> {
        val year = refCal.get(Calendar.YEAR)
        val month = refCal.get(Calendar.MONTH)
        val daysInMonth = refCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val labels = (1..daysInMonth).map { it.toString() }
        val totals = DoubleArray(daysInMonth)
        val c = Calendar.getInstance()
        active.forEach { s ->
          c.timeInMillis = s.dateEpoch
          if (c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month) {
            val idx = (c.get(Calendar.DAY_OF_MONTH) - 1).coerceIn(0, daysInMonth - 1)
            totals[idx] += metricValue(s)
          }
        }
        labels to totals.toList()
      }
      TimePeriod.YEARLY -> {
        val year = refCal.get(Calendar.YEAR)
        val labels = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val totals = DoubleArray(12)
        val c = Calendar.getInstance()
        active.forEach { s ->
          c.timeInMillis = s.dateEpoch
          if (c.get(Calendar.YEAR) == year) {
            val mIdx = c.get(Calendar.MONTH).coerceIn(0, 11)
            totals[mIdx] += metricValue(s)
          }
        }
        labels to totals.toList()
      }
      TimePeriod.ALL_TIME -> {
        val labelFmt = SimpleDateFormat("MMM yy", Locale.US)
        val monthBuckets = sortedMapOf<String, Pair<String, Double>>()
        val c = Calendar.getInstance()
        active.forEach { s ->
          c.timeInMillis = s.dateEpoch
          val ymKey = String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
          val lbl = labelFmt.format(c.time)
          val prev = monthBuckets[ymKey]?.second ?: 0.0
          monthBuckets[ymKey] = lbl to (prev + metricValue(s))
        }
        val entries = monthBuckets.values.toList().takeLast(12)
        entries.map { it.first } to entries.map { it.second }
      }
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
      val saleList = sales.filter { it.storeKey == store.storeKey && it.mode == "SALE" && !it.isRepSale && !it.isRepReturn && !it.isRepTransfer }
      val tIn = stockTransfers.filter { it.toStoreKey == store.storeKey }.sumOf { it.quantityKg }
      val tOut = stockTransfers.filter { it.fromStoreKey == store.storeKey }.sumOf { it.quantityKg }
      val produced = prodList.sumOf { it.netWeightKg }
      val sold = saleList.sumOf { it.quantityKg }
      val returned = settlements
        .filter { !it.returnType.equals("TRANSFER", ignoreCase = true) && it.returnStoreKey == store.storeKey }
        .sumOf { it.returnedKg }
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
      .filter {
        it.isNotBlank() &&
          !it.equals("Direct", ignoreCase = true) &&
          !it.equals("NONE", ignoreCase = true) &&
          !it.equals("ADMIN", ignoreCase = true)
      }
      .distinctBy { it.uppercase() }
      .sorted()

    val rangeSales = sales.filter { it.dateEpoch >= rangeStart && it.mode == "SALE" && !it.isRepReturn }
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
      sales.filter { it.dateEpoch >= rangeStart && it.mode == "COLLECTION" && !it.addToLedger }.sumOf {
        it.amountReceived.takeIf { amt -> amt > 0.0 } ?: it.totalValue
      } +
      rangePay.filter { it.type == "PAYMENT_IN" && !it.addToLedger }.sumOf { it.amount }

    val cashOut = rangeLedger.filter { it.type == "OUT" }.sumOf { it.amount } +
      rangePay.filter { (it.type == "PAYMENT_OUT" || it.type == "EXPENSE") && !it.addToLedger }.sumOf { it.amount }

    val owedToYou = customers.filter { it.currentCredit > 0.01 }.sumOf { it.currentCredit }
    val unpaidCount = sales.count { it.mode == "SALE" && !it.isRepReturn && it.paymentType == "CREDIT" && !it.creditReceived }

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

    // Audit Findings for Overview -> Checks panel (port of modules/overview-audit.js)
    val findings = mutableListOf<AuditFinding>()
    val activeSales = sales.filter { !it.isDeleted }
    val activeProd = production.filter { !it.isDeleted }
    val activePay = payments.filter { !it.isDeleted }
    val activeMats = materials.filter { !it.isDeleted }

    fun isLedgerSaleRow(s: SaleTransactionEntity): Boolean =
      s.isCollection || s.isRepTransfer || s.isRepReturn ||
        s.transactionType == "OLD_DEBT" ||
        s.mode in setOf("COLLECTION", "MONEY_OUT", "OLD_DEBT", "REP_RETURN") ||
        s.paymentType in setOf("COLLECTION", "PARTIAL_PAYMENT", "MONEY_OUT", "REP_RETURN")

    // 1. Duplicate ID checks
    listOf(
      "SALE" to activeSales.groupBy { it.id }.filterValues { it.size > 1 },
      "PRODUCTION" to activeProd.groupBy { it.id }.filterValues { it.size > 1 },
      "PAYMENT" to activePay.groupBy { it.id }.filterValues { it.size > 1 },
    ).forEach { (rType, dupMap) ->
      dupMap.forEach { (dupId, group) ->
        val first = group.first()
        val dEpoch = when (first) {
          is SaleTransactionEntity -> first.dateEpoch
          is ProductionEntryEntity -> first.dateEpoch
          is PaymentTransactionEntity -> first.dateEpoch
          else -> now
        }
        findings += AuditFinding(
          id = "dup_id_${rType}_$dupId",
          severity = "ERROR",
          recordType = rType,
          recordId = dupId,
          dateEpoch = dEpoch,
          title = "Duplicate $rType ID · $dupId",
          reasons = listOf("Same record ID appears ${group.size} times"),
          note = "Assign a fresh unique ID to resolve the collision.",
          suggestedFieldLabel = "New Record ID",
          currentFieldValue = dupId,
          fixKind = AuditFixKind.NEW_ID,
          fieldKey = "newId",
        )
      }
    }

    // 2. Duplicate identical sales check
    activeSales.filter { !isLedgerSaleRow(it) && it.mode == "SALE" }
      .groupBy { s ->
        listOf(
          s.customerName.trim().lowercase(),
          s.dateStr.ifBlank { SarimFormat.dateInput(s.dateEpoch) },
          s.timeStr.trim(),
          com.example.data.repository.SarimLinkEngine.round2(s.quantityKg).toString(),
          com.example.data.repository.SarimLinkEngine.round2(s.totalValue).toString(),
        ).joinToString("|")
      }
      .filterValues { it.size > 1 }
      .forEach { (_, group) ->
        group.drop(1).forEach { dup ->
          findings += AuditFinding(
            id = "dup_rec_sale_${dup.id}",
            severity = "WARNING",
            recordType = "SALE",
            recordId = dup.id,
            dateEpoch = dup.dateEpoch,
            title = "Possible duplicate sale · ${dup.customerName}",
            reasons = listOf("Identical record entered ${group.size} times (${SarimFormat.formatKg(dup.quantityKg)}, ${SarimFormat.formatRs(dup.totalValue)})"),
            note = "Verify and delete the duplicate copy if entered twice by mistake.",
            suggestedFieldLabel = "Delete Duplicate",
            currentFieldValue = "",
            fixKind = AuditFixKind.DELETE_ONLY,
            fieldKey = "duplicate",
            deletable = true,
          )
        }
      }

    // 3. Future date checks
    activeSales.filter { SarimFormat.isFutureDate(it.dateEpoch) }.forEach { s ->
      findings += AuditFinding(
        id = "future_sale_${s.id}",
        severity = "WARNING",
        recordType = "SALE",
        recordId = s.id,
        dateEpoch = s.dateEpoch,
        title = "Future dated sale · ${s.customerName}",
        reasons = listOf("Dated in the future (${SarimFormat.dateInput(s.dateEpoch)})"),
        note = "Reset record date to today or an earlier valid date.",
        suggestedFieldLabel = "Date",
        currentFieldValue = SarimFormat.startOfDay(now).toString(),
        fixKind = AuditFixKind.DATE,
        fieldKey = "dateEpoch",
      )
    }
    activeProd.filter { !it.isReturn && SarimFormat.isFutureDate(it.dateEpoch) }.forEach { p ->
      findings += AuditFinding(
        id = "future_prod_${p.id}",
        severity = "WARNING",
        recordType = "PRODUCTION",
        recordId = p.id,
        dateEpoch = p.dateEpoch,
        title = "Future dated production · ${p.storeName}",
        reasons = listOf("Dated in the future (${SarimFormat.dateInput(p.dateEpoch)})"),
        note = "Reset record date to today or an earlier valid date.",
        suggestedFieldLabel = "Date",
        currentFieldValue = SarimFormat.startOfDay(now).toString(),
        fixKind = AuditFixKind.DATE,
        fieldKey = "dateEpoch",
      )
    }
    activePay.filter { SarimFormat.isFutureDate(it.dateEpoch) }.forEach { tx ->
      findings += AuditFinding(
        id = "future_pay_${tx.id}",
        severity = "WARNING",
        recordType = "PAYMENT",
        recordId = tx.id,
        dateEpoch = tx.dateEpoch,
        title = "Future dated payment · ${tx.entityOrExpenseName}",
        reasons = listOf("Dated in the future (${SarimFormat.dateInput(tx.dateEpoch)})"),
        note = "Reset record date to today or an earlier valid date.",
        suggestedFieldLabel = "Date",
        currentFieldValue = SarimFormat.startOfDay(now).toString(),
        fixKind = AuditFixKind.DATE,
        fieldKey = "dateEpoch",
      )
    }

    // 4. Sales checks (excluding ledger / collection / transfer rows)
    activeSales.filter { !isLedgerSaleRow(it) && it.mode == "SALE" }.forEach { s ->
      if (s.quantityKg <= 0.0) {
        findings += AuditFinding(
          id = "sale_bad_qty_${s.id}",
          severity = "ERROR",
          recordType = "SALE",
          recordId = s.id,
          dateEpoch = s.dateEpoch,
          title = "Invalid quantity · ${s.customerName}",
          reasons = listOf("Sale has zero or negative quantity (${s.quantityKg} kg)"),
          note = "Enter a positive quantity in kg.",
          suggestedFieldLabel = "Quantity (kg)",
          currentFieldValue = s.quantityKg.coerceAtLeast(1.0).toString(),
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "quantityKg",
          deletable = true,
        )
      } else if (s.totalValue < 0.0) {
        findings += AuditFinding(
          id = "sale_negative_val_${s.id}",
          severity = "ERROR",
          recordType = "SALE",
          recordId = s.id,
          dateEpoch = s.dateEpoch,
          title = "Negative sale value · ${s.customerName}",
          reasons = listOf("Total value is ${SarimFormat.formatRs(s.totalValue)}"),
          note = "Update unit price to recalculate a positive sale total.",
          suggestedFieldLabel = "Price per kg (Rs)",
          currentFieldValue = s.pricePerKg.coerceAtLeast(0.0).toString(),
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "pricePerKg",
        )
      } else {
        val expectedVal = com.example.data.repository.SarimLinkEngine.round2(s.quantityKg * s.pricePerKg)
        val expectedProfit = com.example.data.repository.SarimLinkEngine.round2(s.totalValue - s.totalCost)
        if (s.pricePerKg > 0.0 && abs(s.totalValue - expectedVal) > maxOf(1.0, s.totalValue * 0.005)) {
          findings += AuditFinding(
            id = "sale_total_mismatch_${s.id}",
            severity = "ERROR",
            recordType = "SALE",
            recordId = s.id,
            dateEpoch = s.dateEpoch,
            title = "Sale total mismatch · ${s.customerName}",
            reasons = listOf("Recorded ${SarimFormat.formatRs(s.totalValue)}, expected ${SarimFormat.formatRs(expectedVal)}"),
            note = "Confirm or update unit price to resync total and profit.",
            suggestedFieldLabel = "Price per kg (Rs)",
            currentFieldValue = s.pricePerKg.toString(),
            fixKind = AuditFixKind.NUMBER,
            fieldKey = "pricePerKg",
          )
        } else if (abs(s.profit - expectedProfit) > 1.0) {
          findings += AuditFinding(
            id = "sale_profit_mismatch_${s.id}",
            severity = "WARNING",
            recordType = "SALE",
            recordId = s.id,
            dateEpoch = s.dateEpoch,
            title = "Profit mismatch · ${s.customerName}",
            reasons = listOf("Recorded profit ${SarimFormat.formatRs(s.profit)}, expected ${SarimFormat.formatRs(expectedProfit)}"),
            note = "Save profit = total value minus total cost.",
            suggestedFieldLabel = "Profit (Rs)",
            currentFieldValue = expectedProfit.toString(),
            fixKind = AuditFixKind.NUMBER,
            fieldKey = "profit",
          )
        }
      }
      if (s.totalCost <= 0.0 && s.totalValue > 0.0) {
        findings += AuditFinding(
          id = "sale_no_cost_${s.id}",
          severity = "WARNING",
          recordType = "SALE",
          recordId = s.id,
          dateEpoch = s.dateEpoch,
          title = "Sale with no cost · ${s.customerName}",
          reasons = listOf("Sale value ${SarimFormat.formatRs(s.totalValue)} has Rs 0 cost recorded"),
          note = "Enter total cost so profit figures stay accurate.",
          suggestedFieldLabel = "Total Cost (Rs)",
          currentFieldValue = s.totalCost.toString(),
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "totalCost",
        )
      }
      if (s.profit < -0.01) {
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
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "pricePerKg",
        )
      }
      if (s.paymentType.equals("CASH", ignoreCase = true) && !s.creditReceived) {
        findings += AuditFinding(
          id = "sale_cash_unreceived_${s.id}",
          severity = "WARNING",
          recordType = "SALE",
          recordId = s.id,
          dateEpoch = s.dateEpoch,
          title = "Cash sale not marked received · ${s.customerName}",
          reasons = listOf("Cash sale for ${SarimFormat.formatRs(s.totalValue)} has creditReceived = false"),
          note = "Mark cash as received to keep credit ledgers consistent.",
          suggestedFieldLabel = "Cash has been received",
          currentFieldValue = "true",
          fixKind = AuditFixKind.BOOLEAN,
          fieldKey = "creditReceived",
        )
      }
    }

    // 5. Link repair checks
    com.example.data.repository.SarimLinkEngine.auditLinks(sales, settlements).forEach { linkIssue ->
      findings += AuditFinding(
        id = linkIssue.id,
        severity = linkIssue.severity,
        recordType = "LINK_REPAIR",
        recordId = linkIssue.recordId,
        dateEpoch = linkIssue.dateEpoch,
        title = linkIssue.title,
        reasons = listOf(linkIssue.detail),
        note = "One-tap repair removes broken references and recalculates collection balances.",
        suggestedFieldLabel = "Repair Link",
        currentFieldValue = "1",
        fixKind = AuditFixKind.LINK_REPAIR,
        fieldKey = "linkRepair",
      )
    }

    // 6. Payment & Expense checks
    val activeEntityIds = entities.filter { !it.isDeleted }.map { it.id }.toSet()
    activePay.forEach { tx ->
      if (tx.type !in setOf("PAYMENT_IN", "PAYMENT_OUT", "EXPENSE")) {
        findings += AuditFinding(
          id = "txn_bad_type_${tx.id}",
          severity = "ERROR",
          recordType = "PAYMENT",
          recordId = tx.id,
          dateEpoch = tx.dateEpoch,
          title = "Invalid payment type · ${tx.entityOrExpenseName}",
          reasons = listOf("Payment type '${tx.type}' is neither IN, OUT, nor EXPENSE"),
          note = "Choose the proper transaction type.",
          suggestedFieldLabel = "Payment Type",
          currentFieldValue = "PAYMENT_OUT",
          fixKind = AuditFixKind.SELECT_PAY_TYPE,
          fieldKey = "type",
        )
      }
      if (tx.type == "EXPENSE" && tx.amount <= 0.0) {
        findings += AuditFinding(
          id = "exp_bad_amount_${tx.id}",
          severity = "ERROR",
          recordType = "PAYMENT",
          recordId = tx.id,
          dateEpoch = tx.dateEpoch,
          title = "Expense with zero or negative amount · ${tx.entityOrExpenseName}",
          reasons = listOf("Amount is ${SarimFormat.formatRs(tx.amount)}"),
          note = "Enter a positive expense amount or delete this record.",
          suggestedFieldLabel = "Amount (Rs)",
          currentFieldValue = tx.amount.coerceAtLeast(1.0).toString(),
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "amount",
          deletable = true,
        )
      } else if (tx.amount < 0.0) {
        findings += AuditFinding(
          id = "txn_bad_amount_${tx.id}",
          severity = "ERROR",
          recordType = "PAYMENT",
          recordId = tx.id,
          dateEpoch = tx.dateEpoch,
          title = "Negative payment amount · ${tx.entityOrExpenseName}",
          reasons = listOf("Payment amount is ${SarimFormat.formatRs(tx.amount)}"),
          note = "Enter a non-negative amount.",
          suggestedFieldLabel = "Amount (Rs)",
          currentFieldValue = "0",
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "amount",
        )
      }
      if (tx.type != "EXPENSE" && !tx.entityId.isNullOrBlank() && tx.entityId !in activeEntityIds) {
        findings += AuditFinding(
          id = "txn_missing_entity_${tx.id}",
          severity = "WARNING",
          recordType = "PAYMENT",
          recordId = tx.id,
          dateEpoch = tx.dateEpoch,
          title = "Orphan payment entity · ${tx.entityOrExpenseName}",
          reasons = listOf("Linked account (${tx.entityId}) was deleted or is missing"),
          note = "Reassign this payment to an active account or restore from Recycle Bin.",
          suggestedFieldLabel = "Account",
          currentFieldValue = entities.firstOrNull { !it.isDeleted && !it.isExpenseEntity }?.id.orEmpty(),
          fixKind = AuditFixKind.SELECT_ENTITY,
          fieldKey = "entityId",
        )
      }
    }

    // 7. Production checks
    activeProd.filter { !it.isReturn }.forEach { p ->
      if (p.netWeightKg <= 0.0) {
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
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "grossWeightKg",
          deletable = true,
        )
      }
      if (p.formulaName.isBlank()) {
        findings += AuditFinding(
          id = "prod_no_formula_${p.id}",
          severity = "ERROR",
          recordType = "PRODUCTION",
          recordId = p.id,
          dateEpoch = p.dateEpoch,
          title = "Production without formula · ${p.storeName}",
          reasons = listOf("Missing formula makes batch cost and profit unreliable"),
          note = "Assign a formula to recalculate cost and profit.",
          suggestedFieldLabel = "Formula",
          currentFieldValue = formulas.firstOrNull { !it.isDeleted }?.id.orEmpty(),
          fixKind = AuditFixKind.SELECT_FORMULA,
          fieldKey = "formulaId",
        )
      }
      val expectedProdProfit = com.example.data.repository.SarimLinkEngine.round2(p.totalValue - p.totalFormulaCost)
      if (p.totalValue > 0.0 && abs(p.totalProfit - expectedProdProfit) > 1.0) {
        findings += AuditFinding(
          id = "prod_profit_mismatch_${p.id}",
          severity = "WARNING",
          recordType = "PRODUCTION",
          recordId = p.id,
          dateEpoch = p.dateEpoch,
          title = "Production profit mismatch · ${p.storeName}",
          reasons = listOf("Saved profit ${SarimFormat.formatRs(p.totalProfit)}, expected ${SarimFormat.formatRs(expectedProdProfit)}"),
          note = "Update profit to sale value minus formula cost.",
          suggestedFieldLabel = "Profit (Rs)",
          currentFieldValue = expectedProdProfit.toString(),
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "totalProfit",
        )
      }
    }

    // 8. Inventory / Material checks
    activeMats.filter { !it.name.equals("CHORA", ignoreCase = true) }.forEach { m ->
      if (m.stockKg < -0.001) {
        findings += AuditFinding(
          id = "inv_neg_${m.id}",
          severity = "ERROR",
          recordType = "MATERIAL",
          recordId = m.id,
          dateEpoch = m.updatedAt,
          title = "Negative material stock · ${m.name}",
          reasons = listOf("Current stock is ${SarimFormat.formatKg(m.stockKg)}"),
          note = "Reset stock quantity to 0 or record the missing purchase.",
          suggestedFieldLabel = "Stock (kg)",
          currentFieldValue = "0",
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "stockKg",
        )
      } else if (m.stockKg <= 0.001) {
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
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "stockKg",
        )
      }
      if (m.stockKg > 0.001 && m.avgCostPerKg <= 0.0) {
        findings += AuditFinding(
          id = "inv_bad_cost_${m.id}",
          severity = "WARNING",
          recordType = "MATERIAL",
          recordId = m.id,
          dateEpoch = m.updatedAt,
          title = "Material with no cost · ${m.name}",
          reasons = listOf("${SarimFormat.formatKg(m.stockKg)} on hand has Rs 0 avg cost/kg"),
          note = "Set a positive cost per kg so formula batch costing works.",
          suggestedFieldLabel = "Avg Cost / kg (Rs)",
          currentFieldValue = m.avgCostPerKg.toString(),
          fixKind = AuditFixKind.NUMBER,
          fieldKey = "avgCostPerKg",
        )
      }
    }

    findings.sortWith(compareBy<AuditFinding> { if (it.severity == "ERROR") 0 else 1 }.thenByDescending { it.dateEpoch })

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
