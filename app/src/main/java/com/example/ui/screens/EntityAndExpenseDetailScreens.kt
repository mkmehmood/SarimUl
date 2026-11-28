package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.PaymentTransactionEntity
import com.example.data.model.StandaloneScreen
import com.example.data.model.TimePeriod
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.DirectShare
import com.example.util.StatementData
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntityDetailsScreen(
  entityId: String,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val context = LocalContext.current
  var showStatementFlow by remember { mutableStateOf(false) }
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
  val focusEntityTxId = state.pendingRecordFocus?.takeIf { it.targetKey == "transactions" }?.recordId
  LaunchedEffect(focusEntityTxId) {
    if (focusEntityTxId != null) {
      searchQuery = ""
      filter = "All"
    }
  }

  // Transfers between entities are stored as a pair of payment documents (web "Transfers" filter).
  val transferDocIds = remember(state.paymentTransfers) {
    state.paymentTransfers.filter { !it.isDeleted }.flatMap { listOf(it.outDocId, it.inDocId) }.toSet()
  }
  val filtered = remember(txs, searchQuery, filter, transferDocIds) {
    val q = searchQuery.trim().lowercase()
    txs.filter { tx ->
      val fOk = when (filter) {
        "Payment In" -> tx.type == "PAYMENT_IN" && tx.id !in transferDocIds
        "Payment Out" -> tx.type == "PAYMENT_OUT" && tx.id !in transferDocIds
        "Transfers" -> tx.id in transferDocIds
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
            modifier = Modifier.testTag("manageEntityStats").then(
              if (entity.phone.isBlank()) Modifier else Modifier.combinedClickable(
                onClick = {
                  runCatching {
                    com.example.util.AppLockGuard.suppressFor()
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${entity.phone}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                  }.onFailure { viewModel.postStatus("No dialer app available") }
                },
                onLongClick = {
                  if (!DirectShare.openWhatsAppChat(context, entity.phone)) viewModel.postStatus("WhatsApp or browser unavailable")
                },
              )
            ),
          )
        }
        IconButton(onClick = { showStatementFlow = true }, modifier = Modifier.minimumInteractiveComponentSize()) {
          Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Share statement", tint = palette.green)
        }
        IconButton(onClick = { viewModel.openStandalone(StandaloneScreen.AddEntity(entity.id)) }, modifier = Modifier.minimumInteractiveComponentSize()) {
          Icon(Icons.Rounded.Edit, contentDescription = "Edit entity", tint = palette.cyan)
        }
        IconButton(
          modifier = Modifier.minimumInteractiveComponentSize(),
          onClick = {
            val blockMsg = SarimLinkEngine.getEntityDeleteBlockReason(
              entity = entity,
              allTxs = state.paymentTransactions,
              sales = state.sales,
              settlements = state.settlements,
              factoryBatches = state.factoryBatches,
            )
            if (blockMsg != null) {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Cannot Delete Entity",
                  body = blockMsg,
                  tone = DialogTone.ERROR,
                  confirmLabel = "OK",
                  cancelLabel = null,
                )
              )
            } else {
              val bal = entity.currentBalance
              val balNote = if (kotlin.math.abs(bal) > 0.01) {
                "\nCurrent ${if (bal >= 0) "Payable" else "Receivable"}: ${viewModel.redactMoney(SarimFormat.formatRs(kotlin.math.abs(bal)))}"
              } else ""
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Delete Entity",
                  body = "Move ${entity.name} and ${txs.size} linked transaction(s) to Recycle Bin?$balNote",
                  tone = DialogTone.PERMANENT,
                  confirmLabel = "Delete",
                  onConfirm = {
                    viewModel.launchSafely(
                      errorTitle = "Delete Failed",
                      successMessage = "Deleted ${entity.name}",
                      onSuccess = { viewModel.closeStandalone() },
                    ) {
                      viewModel.repository.deletePaymentEntity(entity.id)
                    }
                  },
                )
              )
            }
          }
        ) {
          Icon(Icons.Rounded.Delete, contentDescription = "Delete entity", tint = palette.red)
        }
      }
    }

    SarimCard(tonalAccent = palette.purple) {
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
      leadingIcon = Icons.Rounded.Search,
    )

    SarimToggleGroup(
      options = listOf("All", "Payment In", "Payment Out", "Transfers"),
      selected = filter,
      labelOf = { it },
      onSelect = { filter = it },
      testTagPrefix = "entity_filter",
    )

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
            detailMetrics = listOf(
              "Type" to tx.type.replace("_", " "),
              "Date" to SarimFormat.dateInput(tx.dateEpoch),
              "Amount" to SarimFormat.formatRs(tx.amount, privacyHidden = state.hideMoneyFigures),
              "Daily Ledger" to if (tx.addToLedger) "Linked" else "Not linked",
            ),
            detailNote = if (tx.description.isNotBlank()) "Note: ${tx.description}" else "",
          )
        },
        yearSummary = { "" },
        monthSummary = { "" },
        daySummary = { "" },
        emptySubtitle = "No payment history found for ${entity.name}.",
        itemKey = { it.id },
        focusRecordKey = focusEntityTxId,
        onFocusConsumed = { viewModel.consumePendingRecordFocus() },
        actions = HistoryRecordActions<PaymentTransactionEntity>(
          deleteLabel = "Delete",
          onDelete = { tx ->
            val blockMsg = SarimLinkEngine.getPaymentTxDeleteBlockReason(
              tx = tx,
              allTxs = state.paymentTransactions,
              sales = state.sales,
              settlements = state.settlements,
              factoryBatches = state.factoryBatches,
            )
            if (blockMsg != null) {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Cannot Delete Payment",
                  body = blockMsg,
                  tone = DialogTone.ERROR,
                  confirmLabel = "OK",
                  cancelLabel = null,
                )
              )
            } else {
              val ledgerNote = if (tx.addToLedger && !tx.ledgerId.isNullOrBlank()) "\nLinked Daily Ledger entry will also be removed." else ""
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Delete Payment?",
                  body = "${tx.type.replace("_", " ")} · ${viewModel.redactMoney(SarimFormat.formatRs(tx.amount))}\nThis will reverse its effect on ${entity.name}'s balance.$ledgerNote\n\nMoves to Recycle Bin for 90 days.",
                  tone = DialogTone.PERMANENT,
                  confirmLabel = "Delete",
                  onConfirm = {
                    viewModel.launchSafely(errorTitle = "Delete Failed", successMessage = "Moved to Recycle Bin") {
                      viewModel.repository.deletePaymentTransaction(tx.id)
                    }
                  },
                )
              )
            }
          },
          extraLabel = { tx -> if (tx.photoUri.isNotBlank()) "View Attached Photo" else null },
          onExtra = { tx -> viewModel.openPhotoViewer(listOf(tx.photoUri), 0) },
          dayActionLabel = "Invoice",
          onDayAction = { dayItems ->
            val dayKey = SarimFormat.dateInput(dayItems.first().dateEpoch)
            val data = StatementData.entityDayInvoice(entity, txs, dayKey, state.hideMoneyFigures)
            if (data == null) viewModel.postStatus("Nothing to invoice for this day.")
            else StatementData.shareDayInvoice(context, data)?.let { viewModel.postStatus(it) }
          },
        ),
      )
    }
    Spacer(Modifier.height(24.dp))
  }

  if (showStatementFlow) {
    StatementShareFlow(
      viewModel = viewModel,
      personName = entity.name,
      minEpoch = txs.minOfOrNull { it.dateEpoch } ?: 0L,
      maxEpoch = txs.maxOfOrNull { it.dateEpoch } ?: 0L,
      buildSpec = { from, to -> StatementData.entityStatement(entity, txs, from, to, state.hideMoneyFigures) },
      onDismiss = { showStatementFlow = false },
    )
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
  val focusExpenseTxId = state.pendingRecordFocus?.takeIf { it.targetKey == "expenses" }?.recordId
  LaunchedEffect(focusExpenseTxId) {
    if (focusExpenseTxId != null) {
      searchQuery = ""
      rangeIndex = 4
    }
  }
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

  val context = androidx.compose.ui.platform.LocalContext.current

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SarimCard(tonalAccent = palette.red) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(expenseName, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
          Text(
            "Total: ${SarimFormat.formatRs(total, showSymbol = false, privacyHidden = state.hideMoneyFigures)}",
            color = palette.red,
            style = TabularNumberStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
          )
        }
        SarimSecondaryButton(
          text = "Export PDF",
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          testTag = "expense_export_pdf_btn",
          onClick = {
            if (txs.isEmpty()) {
              viewModel.postStatus("No records to export in ${rangeOptions[rangeIndex]}")
              return@SarimSecondaryButton
            }
            val nowMs = System.currentTimeMillis()
            com.example.util.PdfExporter.createExpenseCategoryPdf(
              context = context,
              expenseName = expenseName,
              rangeLabel = rangeOptions[rangeIndex],
              generatedLabel = "${SarimFormat.formatDate(nowMs)} ${SarimFormat.formatClock(nowMs)}",
              expenses = txs,
              privacyHidden = state.hideMoneyFigures,
            ).fold(
              onSuccess = { pdfFile ->
                val out = com.example.util.DirectShare.shareViaChooser(
                  context = context,
                  file = pdfFile,
                  mime = "application/pdf",
                  title = "Expense Report — $expenseName",
                )
                if (out == com.example.util.ShareOutcome.FAILED) {
                  viewModel.postStatus("Unable to share expense PDF")
                }
              },
              onFailure = { err ->
                viewModel.postStatus(err.localizedMessage ?: "Failed to export PDF")
              },
            )
          },
        )
      }
      SarimToggleGroup(
        options = rangeOptions.indices.toList(),
        selected = rangeIndex,
        labelOf = { idx -> rangeOptions[idx].replace("This ", "") },
        onSelect = { rangeIndex = it },
        testTagPrefix = "expense_range",
      )
    }

    SarimTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      label = "",
      placeholder = "Search history...",
      leadingIcon = Icons.Rounded.Search,
      testTag = "expense_history_search",
    )

    // Quick Add Expense
    SarimCard {
      Text("Quick Add Expense", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        SarimTextField(
          value = quickAmount,
          onValueChange = { quickAmount = it },
          label = "Amount (Rs)",
          placeholder = "0.00",
          keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f),
          testTag = "quickExpenseAmount",
        )
        SarimTextField(
          value = quickDesc,
          onValueChange = { quickDesc = it },
          label = "Description (optional)",
          placeholder = "e.g. Monthly rent",
          modifier = Modifier.weight(1.5f),
          testTag = "quickExpenseDescription",
        )
      }
      SarimPrimaryButton(
        text = "Save Expense",
        onClick = {
          val amt = SarimFormat.parseSafeDouble(quickAmount)
          if (amt == null || amt <= 0.0) {
            viewModel.showDialog(AppDialogRequest("Invalid Amount", "Enter a positive expense amount.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          val cashNow = SarimLinkEngine.computeCashInHandBreakdown(
            sales = state.sales,
            settlements = state.settlements,
            payments = state.paymentTransactions,
            factoryBatches = state.factoryBatches,
          ).netCash
          if (amt > cashNow + 0.01) {
            viewModel.showDialog(
              AppDialogRequest(
                title = "Low Cash in Hand",
                body = viewModel.redactMoney(
                  "Insufficient cash in hand.\nAvailable: ${SarimFormat.formatRs(cashNow.coerceAtLeast(0.0))}\nRequired: ${SarimFormat.formatRs(amt)}",
                  "Insufficient cash in hand.",
                ),
                tone = DialogTone.ERROR,
                confirmLabel = "OK",
                cancelLabel = null,
              )
            )
            return@SarimPrimaryButton
          }
          viewModel.confirmSave(
            "Expense",
            listOf(
              "Expense" to expenseName,
              "Amount" to viewModel.redactMoney(SarimFormat.formatRs(amt)),
              "Note" to quickDesc.ifBlank { "—" },
            ),
            isUpdate = false,
          ) {
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
              viewModel.repository.saveCategoryIfNew("categories", expenseName)
            }
          }
        },
      )
      Text("* Adds a new expense record under \"$expenseName\" for today's date.", color = palette.textMuted, fontSize = 11.5.sp)
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
            detailMetrics = listOf(
              "Category" to expenseName,
              "Date" to SarimFormat.dateInput(tx.dateEpoch),
              "Amount" to SarimFormat.formatRs(tx.amount, privacyHidden = state.hideMoneyFigures),
              "Daily Ledger" to if (tx.addToLedger) "Linked" else "Not linked",
            ),
            detailNote = if (tx.description.isNotBlank()) "Note: ${tx.description}" else "",
          )
        },
        yearSummary = { items -> SarimFormat.formatRs(items.sumOf { it.item.amount }, privacyHidden = state.hideMoneyFigures) },
        monthSummary = { items -> SarimFormat.formatRs(items.sumOf { it.item.amount }, privacyHidden = state.hideMoneyFigures) },
        daySummary = { items -> SarimFormat.formatRs(items.sumOf { it.item.amount }, privacyHidden = state.hideMoneyFigures) },
        emptySubtitle = "No expense records for $expenseName in this range.",
        itemKey = { it.id },
        focusRecordKey = focusExpenseTxId,
        onFocusConsumed = { viewModel.consumePendingRecordFocus() },
        actions = HistoryRecordActions<PaymentTransactionEntity>(
          deleteLabel = "Delete",
          onDelete = { tx ->
            val blockMsg = SarimLinkEngine.getPaymentTxDeleteBlockReason(
              tx = tx,
              allTxs = state.paymentTransactions,
              sales = state.sales,
              settlements = state.settlements,
              factoryBatches = state.factoryBatches,
            )
            if (blockMsg != null) {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Cannot Delete Expense",
                  body = blockMsg,
                  tone = DialogTone.ERROR,
                  confirmLabel = "OK",
                  cancelLabel = null,
                )
              )
            } else {
              val ledgerNote = if (tx.addToLedger && !tx.ledgerId.isNullOrBlank()) "\nLinked Daily Ledger entry will also be removed." else ""
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Delete Expense?",
                  body = "$expenseName · ${viewModel.redactMoney(SarimFormat.formatRs(tx.amount))}$ledgerNote\n\nMoves to Recycle Bin for 90 days.",
                  tone = DialogTone.PERMANENT,
                  confirmLabel = "Delete",
                  onConfirm = {
                    viewModel.launchSafely(errorTitle = "Delete Failed", successMessage = "Moved to Recycle Bin") {
                      viewModel.repository.deletePaymentTransaction(tx.id)
                    }
                  },
                )
              )
            }
          },
          extraLabel = { tx -> if (tx.photoUri.isNotBlank()) "View Attached Photo" else null },
          onExtra = { tx -> viewModel.openPhotoViewer(listOf(tx.photoUri), 0) },
        ),
      )
    }

    if (txs.isNotEmpty()) {
      SarimDangerButton(
        text = "Delete All Expenses",
        modifier = Modifier.fillMaxWidth(),
        onClick = {
          val allNameTxs = state.paymentTransactions.filter {
            !it.isDeleted && it.type == "EXPENSE" && it.entityOrExpenseName.equals(expenseName, ignoreCase = true)
          }
          val blockMsg = allNameTxs.firstNotNullOfOrNull { tx ->
            SarimLinkEngine.getPaymentTxDeleteBlockReason(
              tx = tx,
              allTxs = state.paymentTransactions,
              sales = state.sales,
              settlements = state.settlements,
              factoryBatches = state.factoryBatches,
            )
          }
          if (blockMsg != null) {
            viewModel.showDialog(
              AppDialogRequest(
                title = "Cannot Delete All Expenses",
                body = blockMsg,
                tone = DialogTone.ERROR,
                confirmLabel = "OK",
                cancelLabel = null,
              )
            )
          } else {
            val totalAmt = allNameTxs.sumOf { it.amount }
            val linkedCount = allNameTxs.count { it.addToLedger && !it.ledgerId.isNullOrBlank() }
            val minDate = allNameTxs.minOfOrNull { it.dateEpoch }?.let { SarimFormat.dateInput(it) } ?: "—"
            val maxDate = allNameTxs.maxOfOrNull { it.dateEpoch }?.let { SarimFormat.dateInput(it) } ?: "—"
            val ledgerLine = if (linkedCount > 0) "\n$linkedCount linked Daily Ledger entry/entries will also be removed." else ""
            viewModel.showDialog(
              AppDialogRequest(
                title = "Delete All Expenses",
                body = "Delete ${allNameTxs.size} $expenseName expense record(s) totaling ${viewModel.redactMoney(SarimFormat.formatRs(totalAmt))} ($minDate to $maxDate)?$ledgerLine\n\nMoves to Recycle Bin for 90 days.",
                tone = DialogTone.PERMANENT,
                confirmLabel = "Delete All",
                onConfirm = {
                  viewModel.launchSafely(
                    errorTitle = "Delete Failed",
                    successMessage = "Deleted all $expenseName expenses",
                    onSuccess = { viewModel.closeStandalone() },
                  ) {
                    viewModel.repository.deleteAllExpensesByName(expenseName)
                  }
                },
              )
            )
          }
        },
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}
