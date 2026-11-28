package com.example.util

import android.content.Context
import com.example.data.local.CustomerEntity
import com.example.data.local.PaymentEntityRecord
import com.example.data.local.PaymentTransactionEntity
import com.example.data.local.SaleTransactionEntity
import kotlin.math.abs

/** Everything the invoice picture needs for one day (web `invoicePaperHtml`). */
data class DayInvoiceData(
  val kind: StatementKind,
  val name: String,
  val phone: String,
  val dayKey: String,
  val dateLabel: String,
  val previous: String?,
  val lines: List<InvoiceLine>,
  val totals: List<InvoiceLine>,
  val dueLabel: String,
  val dueText: String,
  val dueNegative: Boolean,
  /** Opening balance, sold and received of the day: drive the dial arc (web `invoicePaperHtml`). */
  val opening: Double = 0.0,
  val sold: Double = 0.0,
  val got: Double = 0.0,
)

/**
 * Builds the account statements and day invoices of the three web ledgers (customer, rep customer,
 * payment entity) from Android records, and sends the invoice picture.
 */
object StatementData {
  fun rangeLabel(fromEpoch: Long, toEpoch: Long): String =
    if (fromEpoch <= 0L && toEpoch == Long.MAX_VALUE) "All Time"
    else "${SarimFormat.formatDate(fromEpoch)} – ${SarimFormat.formatDate(toEpoch)}"

  private fun endOfDay(epoch: Long): Long = SarimFormat.startOfDay(epoch) + 24L * 60L * 60L * 1000L - 1L

  fun inRange(epoch: Long, from: Long, to: Long): Boolean = epoch >= SarimFormat.startOfDay(from) && epoch <= endOfDay(to)

  private fun rs(v: Double, hide: Boolean) = SarimFormat.formatRs(v, privacyHidden = hide)
  private fun amt(v: Double, hide: Boolean) = SarimFormat.formatRs(v, showSymbol = false, privacyHidden = hide)
  private fun ibalText(b: Double, hide: Boolean) = when {
    abs(b) < 0.01 -> "SETTLED"
    b > 0 -> amt(b, hide)
    else -> "${amt(-b, hide)} CR"
  }
  private fun kgNum(v: Double) = SarimFormat.formatQty(v, 3)
  private fun saleOrder() = compareBy<SaleTransactionEntity>({ it.dateEpoch }, { it.createdAt })
  private fun payOrder() = compareBy<PaymentTransactionEntity>({ it.dateEpoch }, { it.createdAt })

  private fun saleDebitCredit(t: SaleTransactionEntity): Pair<Double, Double> = when {
    t.mode == "SALE" && t.paymentType == "CASH" -> t.totalValue to t.totalValue
    t.mode == "SALE" -> t.totalValue to 0.0
    t.mode == "MONEY_OUT" -> (t.totalValue.takeIf { it > 0.0 } ?: t.amountReceived) to 0.0
    t.mode == "COLLECTION" -> 0.0 to (t.amountReceived.takeIf { it > 0.0 } ?: t.totalValue)
    else -> 0.0 to t.totalValue
  }

  private fun saleType(t: SaleTransactionEntity): String = when {
    t.isRepReturn -> "REP_RETURN"
    t.mode == "SALE" -> t.paymentType
    else -> t.mode
  }

  private fun saleDetails(t: SaleTransactionEntity, hide: Boolean): String = when {
    t.mode == "SALE" && t.quantityKg > 0.0 -> "${SarimFormat.formatKg(t.quantityKg)} @ ${rs(t.pricePerKg, hide)}"
    t.expiredQtyKg > 0.0 && t.mode == "COLLECTION" -> "Expired ${SarimFormat.formatKg(t.expiredQtyKg)}"
    else -> ""
  }

  private fun dueWords(bal: Double, hide: Boolean): String = when {
    abs(bal) < 0.01 -> "SETTLED"
    bal > 0 -> "Outstanding Due: ${rs(bal, hide)}"
    else -> "Overpaid by: ${rs(-bal, hide)}"
  }

  /** Customer / rep-customer statement for the From–To range. [allSales] must already be this customer's non-deleted sales. */
  fun salesStatement(
    kind: StatementKind,
    customer: CustomerEntity,
    allSales: List<SaleTransactionEntity>,
    from: Long,
    to: Long,
    hide: Boolean,
  ): StatementSpec {
    val sorted = allSales.sortedWith(saleOrder())
    val inside = sorted.filter { inRange(it.dateEpoch, from, to) }
    val prior = sorted.lastOrNull { it.dateEpoch < SarimFormat.startOfDay(from) }
    val opening = prior?.balanceAfter ?: 0.0
    val hasPrior = prior != null
    var totDebit = 0.0
    var totCredit = 0.0
    val lines = buildList {
      if (hasPrior && abs(opening) >= 0.01) {
        add(StatementShare.StatementLine(SarimFormat.formatDate(from), "OPENING", "Opening Balance", "", "", rs(opening, hide)))
      }
      inside.forEach { t ->
        val (d, c) = saleDebitCredit(t)
        totDebit += d
        totCredit += c
        add(
          StatementShare.StatementLine(
            date = SarimFormat.formatDate(t.dateEpoch),
            type = saleType(t),
            details = saleDetails(t, hide),
            debit = if (d > 0.0) rs(d, hide) else "",
            credit = if (c > 0.0) rs(c, hide) else "",
            balance = rs(t.balanceAfter, hide),
          )
        )
      }
    }
    val finalBal = inside.lastOrNull()?.balanceAfter ?: opening
    val summary = if (hasPrior && abs(opening) >= 0.01) listOf(
      "Opening Balance" to (if (opening > 0) "+" else "-") + rs(abs(opening), hide),
      "Period Debit" to rs(totDebit, hide),
      "Period Credit" to rs(totCredit, hide),
    ) else listOf(
      "Total Debit (Sales)" to rs(totDebit, hide),
      "Total Credit (Rcvd)" to rs(totCredit, hide),
    )
    return StatementSpec(
      kind = kind,
      name = customer.name,
      phone = customer.phone,
      rangeLabel = rangeLabel(from, to),
      lines = lines,
      summary = summary,
      closingLabel = dueWords(finalBal, hide),
      closingValue = "",
      closingForText = rs(finalBal, hide),
    )
  }

  /** Balance after each of the entity's payments, rebuilt backwards from the entity's current balance. */
  private fun entityBalances(entity: PaymentEntityRecord, txs: List<PaymentTransactionEntity>): List<Pair<PaymentTransactionEntity, Double>> {
    val sorted = txs.filter { !it.isDeleted && (it.type == "PAYMENT_IN" || it.type == "PAYMENT_OUT") }.sortedWith(payOrder())
    var bal = entity.currentBalance
    val out = ArrayList<Pair<PaymentTransactionEntity, Double>>(sorted.size)
    for (i in sorted.indices.reversed()) {
      out.add(sorted[i] to bal)
      bal -= if (sorted[i].type == "PAYMENT_IN") sorted[i].amount else -sorted[i].amount
    }
    out.reverse()
    return out
  }

  private fun entityBalText(b: Double, hide: Boolean) = if (b < -0.005) "${rs(-b, hide)} REC" else rs(b, hide)

  fun entityStatement(
    entity: PaymentEntityRecord,
    allTxs: List<PaymentTransactionEntity>,
    from: Long,
    to: Long,
    hide: Boolean,
  ): StatementSpec {
    val withBal = entityBalances(entity, allTxs)
    val inside = withBal.filter { inRange(it.first.dateEpoch, from, to) }
    val prior = withBal.lastOrNull { it.first.dateEpoch < SarimFormat.startOfDay(from) }
    val opening = prior?.second ?: 0.0
    var totOut = 0.0
    var totIn = 0.0
    val lines = buildList {
      if (prior != null && abs(opening) >= 0.01) {
        add(StatementShare.StatementLine(SarimFormat.formatDate(from), "OPENING", "Opening Balance", "", "", entityBalText(opening, hide)))
      }
      inside.forEach { (t, b) ->
        val isIn = t.type == "PAYMENT_IN"
        if (isIn) totIn += t.amount else totOut += t.amount
        add(
          StatementShare.StatementLine(
            date = SarimFormat.formatDate(t.dateEpoch),
            type = t.type,
            details = t.description,
            debit = if (!isIn) rs(t.amount, hide) else "",
            credit = if (isIn) rs(t.amount, hide) else "",
            balance = entityBalText(b, hide),
          )
        )
      }
    }
    val finalBal = inside.lastOrNull()?.second ?: opening
    val closing = entityBalText(finalBal, hide)
    return StatementSpec(
      kind = StatementKind.ENTITY,
      name = entity.name,
      phone = entity.phone,
      rangeLabel = rangeLabel(from, to),
      lines = lines,
      summary = listOf("Total Payment Out" to rs(totOut, hide), "Total Payment In" to rs(totIn, hide)),
      closingLabel = if (finalBal < -0.005) "Receivable: $closing" else "Balance: $closing",
      closingValue = "",
      closingForText = closing,
    )
  }

  // ---------- day invoices ----------

  private fun balText(b: Double, hide: Boolean) = when {
    abs(b) < 0.01 -> "SETTLED"
    b > 0 -> rs(b, hide)
    else -> "${rs(-b, hide)} CR"
  }

  /** Invoice of every entry of [dayKey] (yyyy-MM-dd) for a customer / rep customer, or null if the day is empty. */
  fun salesDayInvoice(
    kind: StatementKind,
    customer: CustomerEntity,
    allSales: List<SaleTransactionEntity>,
    dayKey: String,
    hide: Boolean,
  ): DayInvoiceData? {
    val sorted = allSales.filter { !it.isDeleted }.sortedWith(saleOrder())
    val day = sorted.filter { SarimFormat.dateInput(it.dateEpoch) == dayKey }
    if (day.isEmpty()) return null
    val opening = sorted.lastOrNull { SarimFormat.dateInput(it.dateEpoch) < dayKey }?.balanceAfter ?: 0.0
    val closing = day.last().balanceAfter
    val lines = day.map { t ->
      val isReturn = t.isRepReturn || t.mode == "REP_RETURN" || t.paymentType == "REP_RETURN"
      val isPartial = t.paymentType == "PARTIAL_PAYMENT"
      val isCollection = t.mode == "COLLECTION" || isPartial
      val isOldDebt = t.transactionType == "OLD_DEBT"
      fun at(price: Double) = if (price > 0.0) " @${amt(price, hide)}" else ""
      when {
        isReturn -> {
          val e = t.expiredQtyKg; val r = t.returnedQtyKg
          InvoiceLine(
            if (e > 0) "Return | Expired credit" else "Return",
            if (e > 0) "${kgNum(r)} kg | ${kgNum(e)} kg${at(t.returnUnitPrice)}" else "${kgNum(r)} kg${at(t.returnUnitPrice)}",
            amt(t.returnValue.takeIf { it > 0.0 } ?: t.totalValue, hide), incoming = true,
          )
        }
        isCollection -> {
          val pay = t.amountReceived.takeIf { it > 0.0 } ?: t.totalValue
          val expV = t.expiredValue
          InvoiceLine(
            if (isPartial) "Partial payment" else if (expV > 0) "Payment | Expired credit" else "Collection",
            if (isPartial) "Payment received" else if (expV > 0) "${amt(pay, hide)} | ${kgNum(t.expiredQtyKg)} kg${at(t.expiredUnitPrice)}"
            else if (kind == StatementKind.REP) "Bulk payment" else "Amount received",
            amt(pay + expV, hide), incoming = true,
          )
        }
        t.mode == "MONEY_OUT" -> InvoiceLine("Money out", t.notes.ifBlank { "Paid out to customer" }, amt(t.totalValue.takeIf { it > 0.0 } ?: t.amountReceived, hide))
        isOldDebt -> InvoiceLine("Old debt", t.notes.ifBlank { "Previous balance" }, amt(t.totalValue, hide))
        else -> InvoiceLine(
          if (t.paymentType == "CASH") "Cash sale" else "Credit sale",
          "${kgNum(t.quantityKg)} kg @ ${amt(t.pricePerKg, hide)}",
          amt(t.totalValue, hide),
        )
      }
    }
    val sold = day.filter { it.mode == "SALE" && !(it.isRepReturn || it.paymentType == "REP_RETURN") }.sumOf { it.totalValue }
    val got = day.sumOf { t ->
      when {
        t.mode == "COLLECTION" -> t.amountReceived.takeIf { it > 0.0 } ?: t.totalValue
        t.mode == "SALE" && t.paymentType == "CASH" -> t.totalValue
        else -> 0.0
      }
    }
    val dueLabel = when {
      closing > 0.005 -> "Balance due"
      closing < -0.005 -> "Overpaid"
      else -> "Balance"
    }
    return DayInvoiceData(
      kind = kind,
      name = customer.name,
      phone = customer.phone,
      dayKey = dayKey,
      dateLabel = SarimFormat.formatDate(day.first().dateEpoch),
      previous = if (abs(opening) >= 0.01) ibalText(opening, hide) else null,
      lines = lines,
      totals = listOf(InvoiceLine("Received", "", amt(got, hide))),
      dueLabel = dueLabel,
      dueText = ibalText(closing, hide),
      dueNegative = closing < -0.005,
      opening = opening,
      sold = sold,
      got = got,
    )
  }

  fun entityDayInvoice(
    entity: PaymentEntityRecord,
    allTxs: List<PaymentTransactionEntity>,
    dayKey: String,
    hide: Boolean,
  ): DayInvoiceData? {
    val withBal = entityBalances(entity, allTxs)
    val day = withBal.filter { SarimFormat.dateInput(it.first.dateEpoch) == dayKey }
    if (day.isEmpty()) return null
    val closing = day.last().second
    val net = day.sumOf { (t, _) -> if (t.type == "PAYMENT_IN") t.amount else -t.amount }
    val opening = closing - net
    val totalIn = day.filter { it.first.type == "PAYMENT_IN" }.sumOf { it.first.amount }
    val totalOut = day.filter { it.first.type == "PAYMENT_OUT" }.sumOf { it.first.amount }
    val lines = day.map { (t, _) ->
      val isIn = t.type == "PAYMENT_IN"
      InvoiceLine(if (isIn) "Payment in" else "Payment out", t.description, amt(t.amount, hide), incoming = isIn)
    }
    val totals = buildList {
      if (totalIn > 0.005) add(InvoiceLine("Total in", "", amt(totalIn, hide)))
      if (totalOut > 0.005) add(InvoiceLine("Total out", "", amt(totalOut, hide)))
    }
    return DayInvoiceData(
      kind = StatementKind.ENTITY,
      name = entity.name,
      phone = entity.phone,
      dayKey = dayKey,
      dateLabel = SarimFormat.formatDate(day.first().first.dateEpoch),
      previous = if (abs(opening) >= 0.01) (if (opening < 0) "${amt(-opening, hide)} REC" else amt(opening, hide)) else null,
      lines = lines,
      totals = totals,
      dueLabel = if (closing < -0.005) "Receivable" else "Balance",
      dueText = if (closing < -0.005) "${amt(-closing, hide)} REC" else amt(maxOf(0.0, closing), hide),
      dueNegative = false,
    )
  }

  /** Render the day invoice to a PNG and send it straight to the customer's WhatsApp chat. Returns an error message or null. */
  fun shareDayInvoice(context: Context, data: DayInvoiceData): String? {
    val png = StatementShare.renderInvoicePng(
      context = context,
      fileBaseName = "Invoice_${data.name}_${data.dayKey}",
      name = data.name,
      phone = data.phone,
      dateLabel = data.dateLabel,
      previousBalance = data.previous,
      lines = data.lines,
      totalLines = data.totals,
      dueLabel = data.dueLabel,
      dueText = data.dueText,
      greeting = data.kind.greeting,
      dueNegative = data.dueNegative,
      progress = run {
        val owed = maxOf(0.0, data.opening) + data.sold
        if (owed > 0.005) (data.got / owed).coerceIn(0.0, 1.0).toFloat() else 1f
      },
      showProgress = data.kind != StatementKind.ENTITY,
    ).getOrElse { return it.localizedMessage ?: "Unable to share invoice" }
    return if (DirectShare.shareFiles(context, listOf(png), "image/png", data.phone, title = "Invoice") == ShareOutcome.FAILED) "Unable to share invoice" else null
  }
}
