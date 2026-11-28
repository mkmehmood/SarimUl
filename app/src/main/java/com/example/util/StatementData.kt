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

  /** Web `buildSaleRow` type label (two-line labels use a line break). */
  private fun typeLabel(t: SaleTransactionEntity): String {
    val key = saleType(t)
    return when {
      t.transactionType == "OLD_DEBT" -> "OLD DEBT"
      key == "REP_RETURN" -> "REP RETURN"
      key == "CASH" -> "CASH"
      key == "CREDIT" -> when {
        t.creditReceived -> "CREDIT\n(PAID)"
        t.partialPaymentReceived > 0.0 -> "CREDIT\n(PARTIAL)"
        else -> "CREDIT"
      }
      key == "PARTIAL_PAYMENT" -> "PARTIAL\nPAYMENT"
      key == "MONEY_OUT" -> "MONEY OUT"
      else -> key.replace('_', ' ')
    }
  }

  private fun fmtAmt(v: Double) = SarimFormat.formatSouthAsian(v, 2, true)

  /** Web `buildSaleRow` details column. */
  private fun saleDetails(t: SaleTransactionEntity, hide: Boolean): String {
    val key = saleType(t)
    fun at(price: Double) = if (price > 0.0) " @${amt(price, hide)}" else ""
    val qty = "${fmtAmt(t.quantityKg)} kg × ${amt(t.pricePerKg, hide)}"
    return when {
      t.transactionType == "OLD_DEBT" -> t.notes.ifBlank { "Previous balance" }.take(55)
      key == "CASH" -> qty + (if (t.storeName.isNotBlank()) "\n${t.storeName}" else "")
      key == "CREDIT" -> qty + when {
        t.creditReceived -> "\nSettled by collection"
        t.partialPaymentReceived > 0.0 ->
          "\nPaid: ${amt(t.partialPaymentReceived, hide)} | Due: ${amt(t.totalValue - t.partialPaymentReceived, hide)}"
        else -> ""
      }
      key == "REP_RETURN" -> {
        val e = t.expiredQtyKg
        val r = t.returnedQtyKg
        val base = if (e > 0.0) "Return ${fmtAmt(r)} kg | Expired credit ${fmtAmt(e)} kg${at(t.returnUnitPrice)}"
        else "Return ${fmtAmt(r)} kg${at(t.returnUnitPrice)}"
        base + if (t.returnValue > 0.0) " = ${amt(t.returnValue, hide)}" else ""
      }
      key == "COLLECTION" -> {
        val pay = t.amountReceived.takeIf { it > 0.0 } ?: t.collectedAmount.takeIf { it > 0.0 } ?: 0.0
        val expV = t.expiredValue.takeIf { it > 0.0 } ?: (t.expiredQtyKg * t.expiredUnitPrice)
        val hasExp = expV > 0.0 || t.expiredQtyKg > 0.0
        val hasPay = pay > 0.0 || t.expiredQtyKg <= 0.0
        listOfNotNull(
          if (hasPay) "Payment ${amt(pay.takeIf { it > 0.0 } ?: t.totalValue, hide)}" else null,
          if (hasExp) {
            "Expired credit " + (if (t.expiredQtyKg > 0.0) "${fmtAmt(t.expiredQtyKg)} kg" else amt(expV, hide)) +
              at(t.expiredUnitPrice) + (if (t.expiredQtyKg > 0.0) " = ${amt(expV, hide)}" else "")
          } else null,
        ).joinToString(" | ")
      }
      key == "PARTIAL_PAYMENT" -> "Partial payment received"
      key == "MONEY_OUT" -> "Cash paid out" + if (t.notes.isNotBlank()) "\n${t.notes}" else ""
      else -> ""
    }
  }

  /** Web balance cell: SETTLED / amount / "OVERPAID" over the amount. */
  private fun balDisplay(b: Double, hide: Boolean) = when {
    abs(b) < 0.01 -> "SETTLED"
    b > 0 -> amt(b, hide)
    else -> "OVERPAID\n${amt(-b, hide)}"
  }

  private fun dueWords(bal: Double, hide: Boolean, prefix: String = ""): String = when {
    abs(bal) < 0.01 -> "SETTLED"
    bal > 0 -> "Outstanding Due: $prefix${amt(bal, hide)}"
    else -> "Overpaid by: $prefix${amt(-bal, hide)}"
  }

  private val RED = android.graphics.Color.rgb(220, 53, 69)
  private val GREEN = android.graphics.Color.rgb(40, 167, 69)
  private val GRAY = android.graphics.Color.rgb(100, 100, 100)
  private val BLUE = android.graphics.Color.rgb(30, 80, 160)

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
      if (hasPrior) {
        add(StatementShare.StatementLine("Prior", "OPENING", "Opening Balance", "", "", if (abs(opening) < 0.01) "SETTLED" else amt(abs(opening), hide)))
      }
      inside.forEach { t ->
        val (d, c) = saleDebitCredit(t)
        totDebit += d
        totCredit += c
        add(
          StatementShare.StatementLine(
            date = SarimFormat.formatDate(t.dateEpoch),
            type = typeLabel(t),
            details = saleDetails(t, hide),
            debit = if (d > 0.0) amt(d, hide) else "-",
            credit = if (c > 0.0) amt(c, hide) else "-",
            balance = balDisplay(t.balanceAfter, hide),
          )
        )
      }
    }
    val finalBal = inside.lastOrNull()?.balanceAfter ?: opening
    val showOpening = hasPrior && abs(opening) >= 0.01
    val pre = if (kind == StatementKind.REP) "Rs " else ""
    val closingColor = when {
      abs(finalBal) < 0.01 -> GRAY
      finalBal > 0 -> RED
      else -> GREEN
    }
    val totals = StatementTotals(
      fill = if (kind == StatementKind.REP) android.graphics.Color.rgb(240, 235, 255) else android.graphics.Color.rgb(245, 255, 245),
      top = if (showOpening) SummaryText("Opening Balance: ${if (opening > 0) "+" else "-"}$pre${amt(abs(opening), hide)}", BLUE) else null,
      left = SummaryText((if (showOpening) "Period Debit: " else "Total Debit (Sales): ") + pre + amt(totDebit, hide), RED),
      right = SummaryText((if (showOpening) "Period Credit: " else "Total Credit (Rcvd): ") + pre + amt(totCredit, hide), GREEN),
      closing = SummaryText(dueWords(finalBal, hide, pre), closingColor),
    )
    val isRep = kind == StatementKind.REP
    return StatementSpec(
      kind = kind,
      name = customer.name,
      phone = customer.phone,
      rangeLabel = rangeLabel(from, to),
      lines = lines,
      summary = emptyList(),
      closingLabel = dueWords(finalBal, hide),
      closingValue = "",
      closingForText = balDisplay(finalBal, hide).replace('\n', ' '),
      infoLeft = listOf(
        "Customer" to customer.name,
        "Phone" to customer.phone.ifBlank { "N/A" },
        (if (isRep) "Sales Rep" to customer.repName.ifBlank { "N/A" } else "Address" to customer.address.ifBlank { "N/A" }.take(60)),
      ),
      hasPrior = hasPrior,
      totals = totals,
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

  private fun entityBalText(b: Double, hide: Boolean) = when {
    abs(b) < 0.01 -> "SETTLED"
    b < -0.005 -> "${amt(-b, hide)} REC"
    else -> amt(b, hide)
  }

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
      if (prior != null) {
        add(StatementShare.StatementLine("Prior", "OPENING", "Opening Balance", "", "", if (abs(opening) < 0.01) "SETTLED" else amt(abs(opening), hide)))
      }
      inside.forEach { (t, b) ->
        val isIn = t.type == "PAYMENT_IN"
        if (isIn) totIn += t.amount else totOut += t.amount
        add(
          StatementShare.StatementLine(
            date = SarimFormat.formatDate(t.dateEpoch),
            type = t.type,
            details = t.description.ifBlank { "-" }.take(35),
            debit = if (!isIn) amt(t.amount, hide) else "-",
            credit = if (isIn) amt(t.amount, hide) else "-",
            balance = entityBalText(b, hide),
          )
        )
      }
    }
    val finalBal = inside.lastOrNull()?.second ?: opening
    val closing = entityBalText(finalBal, hide)
    val showOpening = prior != null && abs(opening) >= 0.01
    val isPayee = entity.type.equals("payee", true)
    val totals = StatementTotals(
      fill = android.graphics.Color.rgb(245, 245, 245),
      top = if (showOpening) SummaryText("Opening Bal: ${if (opening > 0) "+" else "-"}${amt(abs(opening), hide)}", BLUE) else null,
      left = SummaryText((if (showOpening) "Period OUT: " else "Total OUT: ") + amt(totOut, hide), RED),
      right = SummaryText((if (showOpening) "Period IN: " else "Cash IN: ") + amt(totIn, hide), GREEN),
      closing = SummaryText(
        "Net Balance: $closing",
        when {
          abs(finalBal) < 0.01 -> GRAY
          finalBal < 0 -> RED
          else -> GREEN
        },
      ),
    )
    return StatementSpec(
      kind = StatementKind.ENTITY,
      name = entity.name,
      phone = entity.phone,
      rangeLabel = rangeLabel(from, to),
      lines = lines,
      summary = emptyList(),
      closingLabel = "Net Balance: $closing",
      closingValue = "",
      closingForText = closing,
      infoLeft = listOf("Name" to entity.name, "Phone" to entity.phone.ifBlank { "N/A" }),
      infoRight = if (entity.wallet.isNotBlank()) listOf("Wallet/Account" to entity.wallet) else emptyList(),
      hasPrior = prior != null,
      totals = totals,
      accent = if (isPayee) android.graphics.Color.rgb(230, 100, 20) else android.graphics.Color.rgb(0, 122, 200),
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
