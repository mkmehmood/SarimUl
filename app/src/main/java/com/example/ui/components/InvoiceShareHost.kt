package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.example.data.local.CustomerEntity
import com.example.data.local.PaymentEntityRecord
import com.example.data.local.PaymentTransactionEntity
import com.example.data.local.SaleTransactionEntity
import com.example.util.SarimFormat
import com.example.util.StatementData
import com.example.util.StatementKind
import com.example.viewmodel.PendingInvoiceShare
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.delay

/** The entries that belong to one customer / rep customer (same rule the customer screen uses). */
fun customerSalesOf(state: SarimUiState, customer: CustomerEntity): List<SaleTransactionEntity> =
  state.sales.filter {
    !it.isDeleted && it.isRepSale == customer.isRepCustomer &&
      (it.customerId == customer.id || it.customerName.equals(customer.name, ignoreCase = true))
  }

/** The in/out payments that belong to one payment entity (same rule the entity screen uses). */
fun entityPaymentsOf(state: SarimUiState, entity: PaymentEntityRecord): List<PaymentTransactionEntity> =
  state.paymentTransactions.filter {
    !it.isDeleted && (it.entityId == entity.id || it.entityOrExpenseName.equals(entity.name, ignoreCase = true))
  }

private fun matchingCustomer(state: SarimUiState, p: PendingInvoiceShare): CustomerEntity? =
  state.customers.firstOrNull { !it.isDeleted && it.isRepCustomer == (p.kind == StatementKind.REP) && it.name.equals(p.name, ignoreCase = true) }

private fun matchingEntity(state: SarimUiState, p: PendingInvoiceShare): PaymentEntityRecord? =
  state.paymentEntities.firstOrNull { !it.isDeleted && it.name.equals(p.name, ignoreCase = true) }

/** True once a record saved after the confirm tap exists for the pending invoice's customer and day. */
private fun savedRowLanded(state: SarimUiState, p: PendingInvoiceShare): Boolean {
  val since = p.requestedAt - 1_000L
  return if (p.kind == StatementKind.ENTITY) {
    state.paymentTransactions.any {
      !it.isDeleted && it.entityOrExpenseName.equals(p.name, ignoreCase = true) &&
        SarimFormat.dateInput(it.dateEpoch) == p.dayKey && it.updatedAt >= since
    }
  } else {
    state.sales.any {
      !it.isDeleted && it.isRepSale == (p.kind == StatementKind.REP) && it.customerName.equals(p.name, ignoreCase = true) &&
        SarimFormat.dateInput(it.dateEpoch) == p.dayKey && it.updatedAt >= since
    }
  }
}

private fun buildPendingInvoice(state: SarimUiState, p: PendingInvoiceShare) =
  if (p.kind == StatementKind.ENTITY) {
    matchingEntity(state, p)?.let { e -> StatementData.entityDayInvoice(e, entityPaymentsOf(state, e), p.dayKey, state.hideMoneyFigures) }
  } else {
    matchingCustomer(state, p)?.let { c -> StatementData.salesDayInvoice(p.kind, c, customerSalesOf(state, c), p.dayKey, state.hideMoneyFigures) }
  }

/**
 * Web `shareInvoiceAfterSave`: after a Payments / Sales / Rep Sales entry was saved with
 * "Share invoice after saving" ticked, wait for the saved entry to show up, then render that
 * day's invoice and send it to the customer's WhatsApp chat.
 */
@Composable
fun InvoiceShareHost(state: SarimUiState, viewModel: SarimViewModel) {
  val context = LocalContext.current
  val pending by viewModel.pendingInvoiceShare.collectAsState()
  val latest by rememberUpdatedState(state)

  LaunchedEffect(pending) {
    val p = pending ?: return@LaunchedEffect
    val deadline = System.currentTimeMillis() + 10_000L
    while (System.currentTimeMillis() < deadline && !savedRowLanded(latest, p)) delay(250)
    delay(400) // let balances settle after the row appears
    val data = buildPendingInvoice(latest, p)
    if (data == null) {
      viewModel.postStatus("Saved, but there is nothing to invoice for that day.")
    } else {
      StatementData.shareDayInvoice(context, data)?.let { viewModel.postStatus("Saved, but the invoice could not be shared: $it") }
    }
    viewModel.clearPendingInvoiceShare()
  }
}
