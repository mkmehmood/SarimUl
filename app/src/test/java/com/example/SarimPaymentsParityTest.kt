package com.example

import com.example.data.local.PaymentTransactionEntity
import com.example.data.local.SaleTransactionEntity
import com.example.data.repository.SarimLinkEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Payments tab parity with the web app's Cash In Hand Tracker (date window + outstanding credits). */
class SarimPaymentsParityTest {

  private val day = 24L * 60 * 60 * 1000
  private val base = 1_700_000_000_000L

  private fun sale(at: Long, mode: String, paymentType: String, value: Double, partial: Double = 0.0) =
    SaleTransactionEntity(
      dateEpoch = at,
      mode = mode,
      customerId = "c1",
      customerName = "Ali",
      totalValue = value,
      paymentType = paymentType,
      partialPaymentReceived = partial,
    )

  @Test
  fun cashBreakdown_respectsEndOfWindow() {
    val payments = listOf(
      PaymentTransactionEntity(dateEpoch = base, type = "PAYMENT_IN", entityOrExpenseName = "A", amount = 100.0),
      PaymentTransactionEntity(dateEpoch = base + 3 * day, type = "PAYMENT_IN", entityOrExpenseName = "A", amount = 50.0),
    )
    val windowed = SarimLinkEngine.computeCashInHandBreakdown(emptyList(), emptyList(), payments, cutoffEpoch = base - day, endEpoch = base + day)
    assertEquals(100.0, windowed.paymentInCash, 0.001)
    val open = SarimLinkEngine.computeCashInHandBreakdown(emptyList(), emptyList(), payments)
    assertEquals(150.0, open.paymentInCash, 0.001)
  }

  @Test
  fun outstandingCredits_followWebCashTracker() {
    val sales = listOf(
      sale(base, "SALE", "CREDIT", 1000.0, partial = 200.0), // +800
      sale(base, "SALE", "CASH", 500.0), // no credit
      sale(base, "COLLECTION", "COLLECTION", 300.0), // -300
      sale(base, "MONEY_OUT", "MONEY_OUT", 50.0), // +50
      sale(base + 10 * day, "SALE", "CREDIT", 999.0), // outside window
    )
    val credits = SarimLinkEngine.computeOutstandingCredits(sales, emptyList(), cutoffEpoch = base - day, endEpoch = base + day)
    assertEquals(550.0, credits.salesTabCredits, 0.001)
    assertEquals(0.0, credits.calculatorCredits, 0.001)
    assertEquals(550.0, credits.total, 0.001)
  }
}
