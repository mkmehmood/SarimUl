package com.example.util

import android.graphics.Color
import com.example.data.local.CustomerEntity
import com.example.data.local.SaleTransactionEntity
import kotlin.math.abs

/** One printed row of the customer list. */
data class CustomerListRow(
  val name: String,
  val phone: String,
  val address: String,
  val debit: Double,
  val credit: Double,
  val qty: Double,
  val lastSaleEpoch: Long?,
) {
  val net: Double get() = debit - credit
}

/**
 * "All Customers — Complete List" / "My Customers": rows and totals for the customer list,
 * shared through the same statement-style PDF / image / text / SMS sender as account statements.
 */
object CustomerListPdf {
  /** Builds rows from the customer records and their sales (debit − credit = running balance). */
  fun rowsFor(customers: List<CustomerEntity>, sales: List<SaleTransactionEntity>): List<CustomerListRow> {
    val lastByCustomer = sales.filter { !it.isDeleted }.groupBy { it.customerName.trim().lowercase() }
      .mapValues { (_, v) -> v.maxOf { it.dateEpoch } }
    return customers.filter { !it.isDeleted }.map { c ->
      val credit = c.totalReceived.coerceAtLeast(0.0)
      CustomerListRow(
        name = c.name,
        phone = c.phone.ifBlank { "N/A" },
        address = c.address.ifBlank { "N/A" },
        debit = c.currentCredit + credit,
        credit = credit,
        qty = c.totalQuantityKg,
        lastSaleEpoch = lastByCustomer[c.name.trim().lowercase()],
      )
    }.sortedByDescending { it.net }
  }

  private fun amt(v: Double) = SarimFormat.formatSouthAsian(v, 2, true)

  /** Customer list as a [ReportSpec] (statement look, all five share formats). */
  fun spec(
    fileBase: String,
    title: String,
    name: String,
    rows: List<CustomerListRow>,
    privacyHidden: Boolean = false,
  ): ReportSpec {
    val mask = "••••"
    fun money(v: Double) = if (privacyHidden) mask else amt(v)
    var totDebt = 0.0; var totPaid = 0.0; var totQty = 0.0; var totNet = 0.0
    var debtors = 0; var settled = 0
    val body = rows.map { c ->
      val net = c.net
      totDebt += c.debit; totPaid += c.credit; totQty += c.qty; totNet += net
      if (net > 0.01) debtors++ else settled++
      listOf(
        c.name,
        c.phone,
        c.address.take(35),
        if (c.debit > 0) money(c.debit) else "-",
        if (c.credit > 0) money(c.credit) else "-",
        when {
          privacyHidden -> mask
          abs(net) < 0.01 -> "SETTLED"
          net > 0 -> amt(net)
          else -> amt(abs(net)) + " CR"
        },
        amt(c.qty),
        c.lastSaleEpoch?.let { SarimFormat.formatDate(it) } ?: "-",
      )
    }
    val columns = listOf(
      PdfColumn("Customer Name", 98f),
      PdfColumn("Phone", 62f),
      PdfColumn("Address", 78f),
      PdfColumn("Total Debit", 64f, right = true),
      PdfColumn("Total Credit", 64f, right = true),
      PdfColumn("Net Balance", 66f, right = true),
      PdfColumn("Qty (kg)", 45f, right = true),
      PdfColumn("Last Sale", 46f),
    )
    val netText = when {
      privacyHidden -> mask
      abs(totNet) < 0.01 -> "SETTLED"
      totNet > 0 -> amt(totNet)
      else -> amt(abs(totNet)) + " CR"
    }
    return ReportSpec(
      fileBase = fileBase,
      title = title,
      name = name,
      rangeLabel = "All Time",
      columns = columns,
      rows = body,
      summary = listOf(
        "Total Debit" to money(totDebt),
        "Total Credit" to money(totPaid),
        "Total Quantity (kg)" to amt(totQty),
        "With balance / Settled" to "$debtors / $settled",
      ),
      closingLabel = "Net Outstanding (${rows.size} customers)",
      closingValue = netText,
      accent = Color.rgb(40, 167, 69),
    )
  }
}
