package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.example.data.model.MainTab
import com.example.ui.theme.avatarColorForName
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

/** One row of the web entity ledger (`renderEntityOverlayContent` entries). */
private data class EntityLedgerRow(
  val tx: PaymentTransactionEntity,
  val label: String,
  val sub: String,
  val isIn: Boolean,
  val isTransfer: Boolean,
  val balanceAfter: Double,
)

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
  var menuExpanded by remember { mutableStateOf(false) }
  val entity = remember(entityId, state.paymentEntities) { state.paymentEntities.firstOrNull { it.id == entityId } }

  if (entity == null) {
    SarimEmptyState("Entity not found", "Return to Payments to select a valid entity.")
    return
  }

  val hide = state.hideMoneyFigures
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
  val transferByDocId = remember(state.paymentTransfers) {
    buildMap {
      state.paymentTransfers.filter { !it.isDeleted }.forEach { t ->
        put(t.outDocId, t)
        put(t.inDocId, t)
      }
    }
  }

  // Web: running "balance after" per entry, walked oldest → newest and anchored on the live balance.
  val ledgerRows = remember(txs, transferByDocId, entity.currentBalance) {
    fun delta(t: PaymentTransactionEntity) = when (t.type) {
      "PAYMENT_IN" -> t.amount
      "PAYMENT_OUT" -> -t.amount
      else -> 0.0
    }
    val asc = txs.sortedWith(compareBy<PaymentTransactionEntity>({ SarimFormat.startOfDay(it.dateEpoch) }, { it.createdAt }))
    var run = entity.currentBalance - asc.sumOf { delta(it) }
    val balAfter = HashMap<String, Double>()
    asc.forEach { t ->
      run += delta(t)
      balAfter[t.id] = run
    }
    txs.map { t ->
      val isIn = t.type == "PAYMENT_IN"
      val transfer = transferByDocId[t.id]
      val label = when {
        transfer != null -> if (isIn) "Transfer in" else "Transfer out"
        t.type == "EXPENSE" -> "Expense"
        isIn -> "Payment in"
        else -> "Payment out"
      }
      val peer = transfer?.let { if (isIn) "From: ${it.fromEntityName}" else "To: ${it.toEntityName}" } ?: ""
      val sub = listOf(peer, t.description).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "No description" }
      EntityLedgerRow(t, label, sub, isIn, transfer != null, balAfter[t.id] ?: 0.0)
    }
  }

  val filtered = remember(ledgerRows, searchQuery, filter) {
    val q = searchQuery.trim().lowercase()
    ledgerRows.filter { r ->
      val fOk = when (filter) {
        "Payment In" -> r.isIn && !r.isTransfer
        "Payment Out" -> !r.isIn && !r.isTransfer
        "Transfers" -> r.isTransfer
        else -> true
      }
      val qOk = q.isEmpty() ||
        r.label.lowercase().contains(q) ||
        r.sub.lowercase().contains(q) ||
        r.tx.amount.toString().contains(q) ||
        SarimFormat.dateInput(r.tx.dateEpoch).contains(q) ||
        SarimFormat.formatDate(r.tx.dateEpoch).lowercase().contains(q)
      fOk && qOk
    }
  }

  val realTxs = txs.filter { it.type != "EXPENSE" }
  val totalIn = realTxs.filter { it.type == "PAYMENT_IN" }.sumOf { it.amount }
  val totalOut = realTxs.filter { it.type == "PAYMENT_OUT" }.sumOf { it.amount }
  // Web "comfortable" mode: with 6 or fewer entries the search box and filter chips are hidden.
  val comfortable = txs.size <= 6

  fun balanceText(b: Double): String =
    if (b < -0.005) "${SarimFormat.formatRs(-b, false, hide)} REC" else SarimFormat.formatRs(b.coerceAtLeast(0.0), false, hide)

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Header: avatar + ledger page badge, name, ⋮ menu, phone · wallet (web `#manageEntityTitle`)
    SarimCard {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InitialsAvatar(entity.name, avatarColorForName(entity.name), sizeDp = 48.dp, badgeText = entity.ledgerPage)
        Column(modifier = Modifier.weight(1f)) {
          Text(entity.name, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
          if (entity.phone.isNotBlank() || entity.wallet.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              if (entity.phone.isNotBlank()) {
                Text(
                  entity.phone,
                  color = palette.cyan,
                  fontSize = 12.5.sp,
                  fontWeight = FontWeight.Medium,
                  modifier = Modifier.testTag("manageEntityStats").combinedClickable(
                    onClick = {
                      runCatching {
                        com.example.util.AppLockGuard.suppressFor()
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${entity.phone}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                      }.onFailure { viewModel.postStatus("No dialer app available") }
                    },
                    onLongClick = {
                      if (!DirectShare.openWhatsAppChat(context, entity.phone)) viewModel.postStatus("WhatsApp or browser unavailable")
                    },
                  ),
                )
              }
              if (entity.phone.isNotBlank() && entity.wallet.isNotBlank()) {
                Text(" · ", color = palette.textMuted, fontSize = 12.5.sp)
              }
              if (entity.wallet.isNotBlank()) {
                Text(entity.wallet, color = palette.textMuted, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
              }
            }
          }
        }
        Box {
          IconButton(
            onClick = { menuExpanded = !menuExpanded },
            modifier = Modifier.size(48.dp).testTag("entity_options_btn"),
          ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More actions", tint = palette.textPrimary)
          }
          DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier
              .clip(RoundedCornerShape(20.dp))
              .background(MaterialTheme.colorScheme.surfaceContainerHigh),
          ) {
            DropdownMenuItem(
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("entity_menu_edit"),
              leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, tint = palette.cyan, modifier = Modifier.size(18.dp)) },
              text = { Text("Edit entity", color = palette.textPrimary, fontWeight = FontWeight.Medium) },
              onClick = {
                menuExpanded = false
                viewModel.openStandalone(StandaloneScreen.AddEntity(entity.id))
              },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            DropdownMenuItem(
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("entity_menu_pdf"),
              leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = palette.green, modifier = Modifier.size(18.dp)) },
              text = { Text("Statement PDF", color = palette.textPrimary, fontWeight = FontWeight.Medium) },
              onClick = {
                menuExpanded = false
                showStatementFlow = true
              },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            DropdownMenuItem(
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("entity_menu_delete"),
              leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = palette.red, modifier = Modifier.size(18.dp)) },
              text = { Text("Delete entity", color = palette.red, fontWeight = FontWeight.Medium) },
              onClick = {
                menuExpanded = false
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
              },
            )
          }
        }
      }
    }

    // Summary (web `entitySummaryHtml`): Payable / Receivable / Balance, then Total In · Total Out.
    run {
      val bal = entity.currentBalance
      val settled = kotlin.math.abs(bal) < 0.005
      val label = when {
        bal > 0.005 -> "Payable"
        bal < -0.005 -> "Receivable"
        else -> "Balance"
      }
      val tone = if (bal > 0.005) palette.red else palette.green
      SarimCard(tonalAccent = tone) {
        Text(label, color = palette.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
          if (settled) "Settled" else SarimFormat.formatRs(kotlin.math.abs(bal), privacyHidden = hide),
          color = tone,
          style = TabularNumberStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
        )
        if (txs.isNotEmpty()) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Total In", SarimFormat.formatRs(totalIn, privacyHidden = hide), modifier = Modifier.weight(1f), accentColor = palette.green)
            SarimStatTile("Total Out", SarimFormat.formatRs(totalOut, privacyHidden = hide), modifier = Modifier.weight(1f), accentColor = palette.red)
          }
        }
      }
    }

    if (!comfortable) {
      SarimTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        label = "",
        placeholder = "Search history...",
        leadingIcon = Icons.Rounded.Search,
        testTag = "entity-trans-search",
      )

      SarimToggleGroup(
        options = listOf("All", "Payment In", "Payment Out", "Transfers"),
        selected = filter,
        labelOf = { it },
        onSelect = { filter = it },
        testTagPrefix = "entity_filter",
      )
    }

    if (txs.isEmpty()) {
      // Web empty ledger: "No payments yet" + "Record first payment".
      SarimCard {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("No payments yet", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Text(
            "Payments in, payments out and transfers for ${entity.name} will appear here.",
            color = palette.textMuted,
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
          )
          SarimPrimaryButton(
            text = "Record first payment",
            onClick = { viewModel.selectTab(MainTab.PAYMENTS) },
            testTag = "entity_record_first_payment",
          )
        }
      }
    } else {
      SarimCard {
        val outLabel = "Out"
        val inLabel = "In"
        AccordionHistoryList(
          records = filtered.map { r ->
            val tx = r.tx
            HistoryRecordDisplay(
              item = tx,
              dateEpoch = tx.dateEpoch,
              title = r.label,
              subtitle = r.sub,
              amountText = (if (r.isIn) "+ " else "") + SarimFormat.formatRs(tx.amount, false, hide),
              amountColor = if (r.isIn) palette.green else palette.red,
              chipText = if (r.isTransfer) "TRANSFER" else balanceText(r.balanceAfter),
              chipColor = if (r.isTransfer) palette.amber else if (r.balanceAfter > 0.005) palette.red else palette.textMuted,
              detailMetrics = buildList {
                add("Type" to r.label)
                add("Date" to SarimFormat.dateInput(tx.dateEpoch) + if (tx.timeStr.isNotBlank()) " ${tx.timeStr}" else "")
                add("Amount" to SarimFormat.formatRs(tx.amount, privacyHidden = hide))
                add("Balance After" to balanceText(r.balanceAfter))
                add("Daily Ledger" to if (tx.addToLedger) "Linked" else "Not linked")
              },
              detailNote = if (tx.description.isNotBlank()) "Note: ${tx.description}" else "",
            )
          },
          yearSummary = { "" },
          monthSummary = { items ->
            val out = items.filter { it.item.type == "PAYMENT_OUT" }.sumOf { it.item.amount }
            val got = items.filter { it.item.type == "PAYMENT_IN" }.sumOf { it.item.amount }
            "$outLabel ${SarimFormat.formatRs(out, false, hide)} · $inLabel ${SarimFormat.formatRs(got, false, hide)}"
          },
          daySummary = { items ->
            if (items.size <= 1) "" else {
              val out = items.filter { it.item.type == "PAYMENT_OUT" }.sumOf { it.item.amount }
              val got = items.filter { it.item.type == "PAYMENT_IN" }.sumOf { it.item.amount }
              "$outLabel ${SarimFormat.formatRs(out, false, hide)} · $inLabel ${SarimFormat.formatRs(got, false, hide)}"
            }
          },
          emptyTitle = "No matching history",
          emptySubtitle = "No matching history. Clear the search or pick All.",
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
                val typeLabel = if (tx.type == "PAYMENT_IN") "Payment Received (IN)" else "Payment Made (OUT)"
                val ledgerNote = if (tx.addToLedger && !tx.ledgerId.isNullOrBlank()) "\n↩ Linked Daily Ledger entry will also be removed." else ""
                val payableNote = when {
                  tx.isPayable && tx.type == "PAYMENT_OUT" -> "\n\n↩ Supplier payable status will be restored — material will revert to pending payment."
                  tx.isPayable && tx.type == "PAYMENT_IN" -> "\n\n↩ Credit purchase record removed — supplier will be unlinked from the material."
                  else -> ""
                }
                val noteLine = if (tx.description.isNotBlank()) "\nNote: ${tx.description}" else ""
                viewModel.showDialog(
                  AppDialogRequest(
                    title = if (tx.type == "PAYMENT_IN") "Delete Payment IN" else "Delete Payment OUT",
                    body = "Delete this $typeLabel?\n\nEntity: ${entity.name}\nAmount: ${viewModel.redactMoney(SarimFormat.formatRs(tx.amount))}\nDate: ${SarimFormat.dateInput(tx.dateEpoch)}$noteLine$payableNote$ledgerNote\n\nThis cannot be undone.",
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
            extraLabel = { tx -> if (tx.photoUri.isNotBlank()) "View photo" else null },
            onExtra = { tx -> viewModel.openPhotoViewer(listOf(tx.photoUri), 0) },
            dayActionLabel = "Invoice",
            onDayAction = { dayItems ->
              val dayKey = SarimFormat.dateInput(dayItems.first().dateEpoch)
              val data = StatementData.entityDayInvoice(entity, txs, dayKey, hide)
              if (data == null) viewModel.postStatus("Nothing to invoice for this day.")
              else StatementData.shareDayInvoice(context, data)?.let { viewModel.postStatus(it) }
            },
          ),
        )
      }
    }
    Spacer(Modifier.height(24.dp))
  }

  if (showStatementFlow) {
    StatementShareFlow(
      viewModel = viewModel,
      personName = entity.name,
      minEpoch = txs.minOfOrNull { it.dateEpoch } ?: 0L,
      maxEpoch = txs.maxOfOrNull { it.dateEpoch } ?: 0L,
      buildSpec = { from, to -> StatementData.entityStatement(entity, txs, from, to, hide) },
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
  // Web `#expenseOverlayStats`: filtered total, record count badge and all-time total badge.
  val allTimeTotal = remember(state.paymentTransactions, expenseName) {
    state.paymentTransactions.filter {
      !it.isDeleted && it.type == "EXPENSE" && it.entityOrExpenseName.equals(expenseName, ignoreCase = true)
    }.sumOf { it.amount }
  }

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
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SarimChip("${txs.size} record${if (txs.size != 1) "s" else ""}", palette.amber)
            SarimChip("All-Time: ${SarimFormat.formatRs(allTimeTotal, showSymbol = false, privacyHidden = state.hideMoneyFigures)}", palette.red)
          }
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
            title = tx.description.ifBlank { "No description" },
            subtitle = SarimFormat.formatDate(tx.dateEpoch) + if (tx.timeStr.isNotBlank()) " · ${tx.timeStr}" else "",
            amountText = SarimFormat.formatRs(tx.amount, privacyHidden = state.hideMoneyFigures),
            amountColor = palette.amber,
            chipText = "Expense",
            chipColor = palette.amber,
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
        emptyTitle = "No expense records",
        emptySubtitle = "No expense records found for selected period",
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
          extraLabel = { tx -> if (tx.photoUri.isNotBlank()) "Photo" else null },
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
