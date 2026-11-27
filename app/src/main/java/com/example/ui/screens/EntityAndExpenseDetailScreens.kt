package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StandaloneScreen
import com.example.data.model.TimePeriod
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

@Composable
fun EntityDetailsScreen(
  entityId: String,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val entity = remember(entityId, state.paymentEntities) { state.paymentEntities.firstOrNull { it.id == entityId } }

  if (entity == null) {
    SarimEmptyState("Entity not found", "Return to Payments to select a valid entity.")
    return
  }

  val txs = remember(state.paymentTransactions, entity) {
    state.paymentTransactions.filter {
      !it.isDeleted && (it.entityId == entity.id || it.entityOrExpenseName.equals(entity.name, ignoreCase = true))
    }.sortedByDescending { it.dateEpoch }
  }

  var searchQuery by remember { mutableStateOf("") }
  var filter by remember { mutableStateOf("All") }

  val filtered = remember(txs, searchQuery, filter) {
    val q = searchQuery.trim().lowercase()
    txs.filter { tx ->
      val fOk = when (filter) {
        "In" -> tx.type == "PAYMENT_IN"
        "Out" -> tx.type == "PAYMENT_OUT"
        else -> true
      }
      val qOk = q.isEmpty() || tx.description.lowercase().contains(q) || tx.amount.toString().contains(q)
      fOk && qOk
    }
  }

  val months = remember(filtered) { filtered.map { SarimFormat.formatMonthYear(it.dateEpoch) }.distinct() }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SarimCard {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InitialsAvatar(entity.name, palette.cyan, sizeDp = 48.dp, badgeText = entity.ledgerPage)
        Column(modifier = Modifier.weight(1f)) {
          Text(entity.name, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
          Text(
            "${txs.size} transactions · ${entity.phone.ifBlank { entity.wallet.ifBlank { "Payee / Partner" } }}",
            color = palette.textMuted,
            fontSize = 12.sp,
            modifier = Modifier.testTag("manageEntityStats"),
          )
        }
        IconButton(onClick = { viewModel.openStandalone(StandaloneScreen.AddEntity(entity.id)) }) {
          Icon(Icons.Filled.Edit, contentDescription = "Edit entity", tint = palette.cyan)
        }
      }
    }

    SarimCard(borderColor = palette.purple.copy(alpha = 0.45f)) {
      val bal = entity.currentBalance
      Text("ENTITY BALANCE", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
      Text(
        SarimFormat.formatRs(kotlin.math.abs(bal), privacyHidden = state.hideMoneyFigures),
        color = if (bal >= 0) palette.amber else palette.green,
        style = TabularNumberStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
      )
      Text(
        if (bal >= 0) "Payable (we owe them)" else "Receivable (they owe us)",
        color = palette.textMuted,
        fontSize = 12.sp,
      )
    }

    SarimTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      label = "",
      placeholder = "Search history...",
      leadingIcon = Icons.Filled.Search,
    )

    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf("All", "In", "Out").forEach { chip ->
        FilterChip(selected = filter == chip, onClick = { filter = chip }, label = { Text(chip) })
      }
      months.forEach { m -> SarimChip(m, palette.cyan) }
    }

    SarimCard {
      Text("History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      AccordionHistoryList(
        records = filtered.map { tx ->
          HistoryRecordDisplay(
            item = tx,
            dateEpoch = tx.dateEpoch,
            title = tx.type.replace("_", " "),
            subtitle = tx.description.ifBlank { entity.name },
            amountText = SarimFormat.formatRs(tx.amount, privacyHidden = state.hideMoneyFigures),
            amountColor = if (tx.type == "PAYMENT_IN") palette.green else palette.red,
          )
        },
        yearSummary = { items -> "${items.size} tx" },
        monthSummary = { items -> "${items.size} entry" },
        daySummary = { items -> "${items.size} row" },
        emptySubtitle = "No payment history found for ${entity.name}.",
      ) { rec ->
        if (rec.item.description.isNotBlank()) Text(rec.item.description, color = palette.textMuted, fontSize = 12.sp)
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
fun ExpenseDetailsScreen(
  expenseName: String,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val rangeOptions = listOf("Today", "This Week", "This Month", "This Year", "All Time")
  var rangeIndex by remember { mutableIntStateOf(4) }
  var searchQuery by remember { mutableStateOf("") }
  var quickAmount by remember { mutableStateOf("") }
  var quickDesc by remember { mutableStateOf("") }

  val periodEnum = when (rangeIndex) {
    0 -> TimePeriod.DAILY
    1 -> TimePeriod.WEEKLY
    2 -> TimePeriod.MONTHLY
    3 -> TimePeriod.YEARLY
    else -> TimePeriod.ALL_TIME
  }
  val startEpoch = remember(periodEnum) { SarimMetricsEngine.periodStart(periodEnum) }

  val txs = remember(state.paymentTransactions, expenseName, startEpoch, searchQuery) {
    val q = searchQuery.trim().lowercase()
    state.paymentTransactions.filter {
      !it.isDeleted &&
        it.type == "EXPENSE" &&
        it.entityOrExpenseName.equals(expenseName, ignoreCase = true) &&
        it.dateEpoch >= startEpoch &&
        (q.isEmpty() || it.description.lowercase().contains(q) || it.amount.toString().contains(q))
    }.sortedByDescending { it.dateEpoch }
  }
  val total = remember(txs) { txs.sumOf { it.amount } }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SarimCard(borderColor = palette.red.copy(alpha = 0.4f)) {
      Text(expenseName, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
      Text(
        "Total: ${SarimFormat.formatRs(total, showSymbol = false, privacyHidden = state.hideMoneyFigures)}",
        color = palette.red,
        style = TabularNumberStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
      )
    }

    SarimTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      label = "",
      placeholder = "Search history...",
      leadingIcon = Icons.Filled.Search,
      testTag = "expense_history_search",
    )

    SarimPopoverSelector(
      label = "Range",
      selectedText = rangeOptions[rangeIndex],
      options = rangeOptions,
      onSelect = { idx, _ -> rangeIndex = idx },
      testTag = "expenseOverlayRange",
    )

    // Quick Add Expense
    SarimCard {
      Text("Quick Add Expense", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      SarimTextField(
        value = quickAmount,
        onValueChange = { quickAmount = it },
        label = "Amount (Rs)",
        placeholder = "0.00",
        prefixText = "Rs",
        keyboardType = KeyboardType.Decimal,
        testTag = "quickExpenseAmount",
      )
      SarimTextField(
        value = quickDesc,
        onValueChange = { quickDesc = it },
        label = "Description (optional)",
        placeholder = "e.g. Monthly rent",
        testTag = "quickExpenseDescription",
      )
      SarimPrimaryButton(
        text = "Save Expense",
        onClick = {
          val amt = SarimFormat.parseSafeDouble(quickAmount)
          if (amt == null || amt <= 0.0) {
            viewModel.showDialog(AppDialogRequest("Invalid Amount", "Enter a positive expense amount.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          viewModel.launchSafely(
            errorTitle = "Save Expense Failed",
            successMessage = "Saved $expenseName expense",
            onSuccess = {
              quickAmount = ""
              quickDesc = ""
            },
          ) {
            viewModel.repository.savePaymentTransaction(
              editingId = null,
              dateEpoch = System.currentTimeMillis(),
              type = "EXPENSE",
              name = expenseName,
              amount = amt,
              description = quickDesc,
              photoUri = "",
              addToLedger = false,
            )
          }
        },
      )
      Text("Adds a new expense record under $expenseName for today's date.", color = palette.textMuted, fontSize = 11.5.sp)
    }

    SarimCard {
      Text("History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      AccordionHistoryList(
        records = txs.map { tx ->
          HistoryRecordDisplay(
            item = tx,
            dateEpoch = tx.dateEpoch,
            title = expenseName,
            subtitle = tx.description.ifBlank { "Operating expense" },
            amountText = SarimFormat.formatRs(tx.amount, privacyHidden = state.hideMoneyFigures),
            amountColor = palette.red,
          )
        },
        yearSummary = { items -> SarimFormat.formatRs(items.sumOf { it.item.amount }, privacyHidden = state.hideMoneyFigures) },
        monthSummary = { items -> "${items.size} entries" },
        daySummary = { items -> "${items.size} row" },
        emptySubtitle = "No expense records for $expenseName in this range.",
      ) { rec ->
        if (rec.item.description.isNotBlank()) Text(rec.item.description, color = palette.textMuted, fontSize = 12.sp)
      }
    }

    if (txs.isNotEmpty()) {
      SarimDangerButton(
        text = "Delete All Expenses",
        modifier = Modifier.fillMaxWidth(),
        onClick = {
          viewModel.showDialog(
            AppDialogRequest(
              title = "Delete All Expenses",
              body = "Delete all $expenseName expense records?",
              tone = DialogTone.PERMANENT,
              confirmLabel = "Delete All",
              onConfirm = {
                viewModel.launchSafely(
                  errorTitle = "Delete Failed",
                  onSuccess = { viewModel.closeStandalone() },
                ) {
                  viewModel.repository.deleteAllExpensesByName(expenseName)
                }
              },
            )
          )
        },
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}
