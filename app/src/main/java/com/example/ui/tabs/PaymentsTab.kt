package com.example.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.example.ui.theme.avatarColorForName
import com.example.util.AppDialogRequest
import com.example.util.DialogHighlight
import com.example.util.DialogTone
import com.example.util.PdfExporter
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.launch

private enum class PayTxType(val label: String, val code: String) {
  EXPENSE("Operating Expense", "EXPENSE"),
  PAYMENT_IN("Payment IN (Cash In +)", "PAYMENT_IN"),
  PAYMENT_OUT("Payment OUT (Cash Out −)", "PAYMENT_OUT"),
}

private enum class UnifiedViewMode(val label: String) {
  ENTITIES("Entities & Balances"),
  EXPENSES("Expenses"),
}

@Composable
fun PaymentsTab(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val context = LocalContext.current
  val palette = LocalSarimPalette.current
  val snap = state.snapshot
  val hideMoney = state.hideMoneyFigures

  var dateEpoch by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var nameText by remember { mutableStateOf("") }
  var amountText by remember { mutableStateOf("") }
  var descriptionText by remember { mutableStateOf("") }
  var photoUris by remember { mutableStateOf<List<String>>(emptyList()) }
  var txType by remember { mutableStateOf(PayTxType.PAYMENT_OUT) }
  var addToLedger by remember { mutableStateOf(true) }

  var trackerPeriod by remember { mutableStateOf(TimePeriod.MONTHLY) }
  var unifiedSearch by remember { mutableStateOf("") }
  var unifiedMode by remember { mutableStateOf(UnifiedViewMode.ENTITIES) }
  var unifiedPeriod by remember { mutableStateOf(TimePeriod.MONTHLY) }
  var editingTxId by remember { mutableStateOf<String?>(null) }
  var sheetPayment by remember { mutableStateOf<PaymentTransactionEntity?>(null) }

  val suggestions = remember(state.paymentEntities, state.paymentTransactions) {
    (state.paymentEntities.map { it.name } + state.paymentTransactions.filter { it.type == "EXPENSE" }.map { it.entityOrExpenseName }).distinct()
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item { ShimmerHeading("Payments", fontSizeSp = 26) }

    // 1. Transaction Manager
    item {
      SarimCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(if (editingTxId == null) "Transaction Manager" else "Edit Transaction", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
          TextButton(onClick = {
            editingTxId = null; nameText = ""; amountText = ""; descriptionText = ""; photoUris = emptyList()
          }) { Text(if (editingTxId == null) "Clear" else "Cancel Edit", color = if (editingTxId == null) palette.cyan else palette.amber) }
        }
        if (editingTxId != null) {
          SarimCallout("Editing entry · Saving updates the original record.", palette.amber)
        }

        SarimDatePickerField("Date", dateEpoch, onDateSelected = { dateEpoch = it }, testTag = "expense_date")
        SarimSearchFieldWithDropdown(
          value = nameText,
          onValueChange = { nameText = it },
          label = "Name / Entity",
          placeholder = "Search expenses or entities...",
          suggestions = suggestions,
          onSuggestionSelected = { nameText = it },
          testTag = "expense_name",
        )
        SarimTextField(
          value = amountText,
          onValueChange = { amountText = it },
          label = "Amount",
          placeholder = "0.00",
          prefixText = "Rs",
          keyboardType = KeyboardType.Decimal,
          testTag = "expense_amount",
        )
        SarimTextField(
          value = descriptionText,
          onValueChange = { descriptionText = it },
          label = "Description (Optional)",
          placeholder = "Add details...",
          testTag = "expense_description",
        )
        PhotoAttachmentBar(
          photoUris = photoUris,
          onAddUris = { photoUris = photoUris + it },
          onClearAll = { photoUris = emptyList() },
          onViewPhotos = { idx -> viewModel.openPhotoViewer(photoUris, idx) },
          onError = { msg -> viewModel.postStatus(msg) },
        )

        SarimFieldLabel("Transaction Type")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          PayTxType.entries.forEach { t ->
            val sel = t == txType
            val c = when (t) {
              PayTxType.EXPENSE -> palette.red
              PayTxType.PAYMENT_IN -> palette.green
              PayTxType.PAYMENT_OUT -> palette.amber
            }
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = if (sel) c.copy(alpha = 0.16f) else palette.surfaceElevated,
              border = BorderStroke(1.dp, if (sel) c else palette.border),
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { txType = t },
            ) {
              Text(t.label, color = if (sel) c else palette.textMuted, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
            }
          }
        }

        SarimCheckboxRow(checked = addToLedger, onCheckedChange = { addToLedger = it }, label = "Add to Daily Ledger")

        SarimPrimaryButton(
          text = if (editingTxId == null) "Save Transaction" else "Update Transaction",
          onClick = {
            if (SarimFormat.isFutureDate(dateEpoch)) {
              viewModel.showDialog(AppDialogRequest("Invalid Date", "Future dates are not allowed.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            val amt = SarimFormat.parseSafeDouble(amountText)
            if (nameText.isBlank() || amt == null || amt <= 0.0) {
              viewModel.showDialog(AppDialogRequest("Invalid Entry", "Enter a valid name/entity and positive amount.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            val currentEditId = editingTxId
            viewModel.launchSafely(
              errorTitle = "Save Payment Failed",
              successMessage = if (currentEditId == null) "Payment transaction saved" else "Payment transaction updated",
              onSuccess = { editingTxId = null; nameText = ""; amountText = ""; descriptionText = ""; photoUris = emptyList() },
            ) {
              viewModel.repository.savePaymentTransaction(
                editingId = currentEditId,
                dateEpoch = dateEpoch,
                type = txType.code,
                name = nameText,
                amount = amt,
                description = descriptionText,
                photoUri = photoUris.firstOrNull() ?: "",
                addToLedger = addToLedger,
              )
            }
          },
        )
      }
    }

    if (state.isAdmin && !hideMoney && snap != null) {
      // 2. ECONOMIC HEALTH DASHBOARD
      item {
        val currentAssets = snap.cashInHandBalance + snap.rawInventoryValue + snap.formulaUnitsInventoryValue + snap.directReceivables + snap.repReceivables
        val currentLiabilities = snap.supplierPayables + snap.entityPayables
        val netWorkingCapital = currentAssets - currentLiabilities
        val currentRatio = if (currentLiabilities > 0.01) String.format("%.2f", currentAssets / currentLiabilities) else "—"
        val quickRatio = if (currentLiabilities > 0.01) String.format("%.2f", (snap.cashInHandBalance + snap.directReceivables + snap.repReceivables) / currentLiabilities) else "—"
        val cashRatio = if (currentLiabilities > 0.01) String.format("%.2f", snap.cashInHandBalance / currentLiabilities) else "—"

        SarimCard {
          Text("ECONOMIC HEALTH DASHBOARD", color = palette.green, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
          Text(SarimFormat.formatRs(snap.cashInHandBalance), color = palette.green, style = TabularNumberStyle.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold))
          Text("Operating Cash Flow", color = palette.textMuted, fontSize = 11.5.sp)

          HorizontalDivider(color = palette.border)
          HealthLine("Cash in Hand", SarimFormat.formatRs(snap.cashInHandBalance), palette.green)
          HealthLine("Raw Materials Inventory", SarimFormat.formatRs(snap.rawInventoryValue), palette.cyan)
          HealthLine("Formula Units Inventory", SarimFormat.formatRs(snap.formulaUnitsInventoryValue), palette.purple)
          HealthLine("Direct Customer Credits", SarimFormat.formatRs(snap.directReceivables), palette.amber)
          HealthLine("Representative Credits", SarimFormat.formatRs(snap.repReceivables), palette.amber)
          HealthLine("TOTAL CURRENT ASSETS", SarimFormat.formatRs(currentAssets), palette.green, bold = true)

          HorizontalDivider(color = palette.border)
          HealthLine("Supplier Payables", SarimFormat.formatRs(snap.supplierPayables), palette.red)
          HealthLine("Entity Payables", SarimFormat.formatRs(snap.entityPayables), palette.red)
          HealthLine("TOTAL CURRENT LIABILITIES", SarimFormat.formatRs(currentLiabilities), palette.red, bold = true)

          HorizontalDivider(color = palette.border)
          HealthLine("Net Working Capital", SarimFormat.formatRs(netWorkingCapital), palette.textPrimary, bold = true)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Current Ratio", currentRatio, Modifier.weight(1f), palette.cyan)
            SarimStatTile("Quick Ratio", quickRatio, Modifier.weight(1f), palette.purple)
            SarimStatTile("Cash Ratio", cashRatio, Modifier.weight(1f), palette.green)
          }
        }
      }

      // 3. CASH IN HAND TRACKER
      item {
        val cutoff = SarimMetricsEngine.periodStart(trackerPeriod)
        val breakdown = SarimLinkEngine.computeCashInHandBreakdown(
          sales = state.sales,
          settlements = state.settlements,
          payments = state.paymentTransactions,
          factoryBatches = state.factoryBatches,
          cutoffEpoch = cutoff,
        )

        SarimCard {
          Text("CASH IN HAND TRACKER", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.8.sp)
          SarimToggleGroup(TimePeriod.entries, trackerPeriod, { it.label }, { trackerPeriod = it })
          HealthLine("Sales Tab Cash", SarimFormat.formatRs(breakdown.salesCash), palette.green)
          HealthLine("Collections Cash", SarimFormat.formatRs(breakdown.collectionsCash), palette.green)
          HealthLine("Calculator Cash", SarimFormat.formatRs(breakdown.calcCash), palette.green)
          HealthLine("Payments In", SarimFormat.formatRs(breakdown.paymentInCash), palette.green)
          if (breakdown.moneyOutCash > 0.01) {
            HealthLine("Customer Money Out", SarimFormat.formatRs(breakdown.moneyOutCash), palette.red)
          }
          HealthLine("Payments Out", SarimFormat.formatRs(breakdown.paymentOutCash), palette.red)
          HealthLine("Expenses", SarimFormat.formatRs(breakdown.expensesCash), palette.red)
          if (breakdown.factoryAdditionalCost > 0.01) {
            HealthLine("Factory Extra Cost", SarimFormat.formatRs(breakdown.factoryAdditionalCost), palette.red)
          }
          HealthLine("NET CASH", SarimFormat.formatRs(breakdown.netCash), if (breakdown.netCash >= 0) palette.green else palette.red, bold = true)
        }
      }

      // 4. Unified Records
      item {
        SarimCard {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Unified Records", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            IconButton(
              onClick = {
                val lines = state.paymentEntities.map { "${it.name} | Balance: ${SarimFormat.formatRs(it.currentBalance)}" }
                PdfExporter.sharePdfReport(context, "payment_records", "Unified Payment Records", "${lines.size} entities", lines)
                  .onFailure { err -> viewModel.postStatus(err.localizedMessage ?: "Unable to export PDF") }
              },
            ) { Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Export PDF", tint = palette.cyan) }
          }
          SarimTextField(value = unifiedSearch, onValueChange = { unifiedSearch = it }, label = "", placeholder = "Search...", leadingIcon = Icons.Rounded.Search)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimPopoverSelector(
              label = "View Mode",
              selectedText = unifiedMode.label,
              options = UnifiedViewMode.entries.map { it.label },
              onSelect = { idx, _ -> unifiedMode = UnifiedViewMode.entries[idx] },
              modifier = Modifier.weight(1f),
            )
            SarimPopoverSelector(
              label = "Period",
              selectedText = unifiedPeriod.label,
              options = listOf(TimePeriod.DAILY, TimePeriod.WEEKLY, TimePeriod.MONTHLY, TimePeriod.ALL_TIME).map { it.label },
              onSelect = { idx, _ ->
                unifiedPeriod = listOf(TimePeriod.DAILY, TimePeriod.WEEKLY, TimePeriod.MONTHLY, TimePeriod.ALL_TIME)[idx]
              },
              modifier = Modifier.weight(1f),
            )
          }

          if (unifiedMode == UnifiedViewMode.ENTITIES) {
            val filtered = state.paymentEntities.filter {
              unifiedSearch.isBlank() || it.name.contains(unifiedSearch, ignoreCase = true)
            }
            if (filtered.isEmpty()) {
              SarimEmptyState("No entities yet", "Add an entity from the drawer or save a payment.")
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filtered.forEach { ent ->
                  Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(1.dp, palette.border),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                      viewModel.openStandalone(StandaloneScreen.EntityDetails(ent.id))
                    },
                  ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                      InitialsAvatar(ent.name, avatarColorForName(ent.name), badgeText = ent.ledgerPage)
                      Column(modifier = Modifier.weight(1f)) {
                        Text(ent.name, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        Text(if (ent.currentBalance >= 0) "Payable" else "Receivable", color = palette.textMuted, fontSize = 11.sp)
                      }
                      Text(
                        SarimFormat.formatRs(kotlin.math.abs(ent.currentBalance)),
                        color = if (ent.currentBalance >= 0) palette.red else palette.green,
                        style = TabularNumberStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                      )
                    }
                  }
                }
              }
            }
          } else {
            val cutoff = SarimMetricsEngine.periodStart(unifiedPeriod)
            val grouped = state.paymentTransactions
              .filter { it.type == "EXPENSE" && it.dateEpoch >= cutoff && (unifiedSearch.isBlank() || it.entityOrExpenseName.contains(unifiedSearch, ignoreCase = true)) }
              .groupBy { it.entityOrExpenseName }
            if (grouped.isEmpty()) {
              SarimEmptyState("No expenses", "Operating expenses for this period will list here.")
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                grouped.forEach { (expName, list) ->
                  val total = list.sumOf { it.amount }
                  Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(1.dp, palette.border),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                      viewModel.openStandalone(StandaloneScreen.ExpenseDetails(expName))
                    },
                  ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(expName, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        Text("${list.size} entries", color = palette.textMuted, fontSize = 11.sp)
                      }
                      Text(SarimFormat.formatRs(total), color = palette.red, style = TabularNumberStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold))
                    }
                  }
                }
              }
            }
          }
        }
      }

      // 5. Payment History Accordion
      item {
        Text("Payment History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        val records = state.paymentTransactions.map { p ->
          val isIn = p.type == "PAYMENT_IN"
          HistoryRecordDisplay(
            item = p,
            dateEpoch = p.dateEpoch,
            title = p.entityOrExpenseName,
            subtitle = p.description.ifBlank { p.type },
            amountText = SarimFormat.formatRs(p.amount),
            amountColor = if (isIn) palette.green else palette.red,
            chipText = p.type.replace("_", " "),
            chipColor = if (isIn) palette.green else palette.red,
            detailMetrics = buildList {
              add("Type" to p.type.replace("_", " "))
              add("Date" to SarimFormat.dateInput(p.dateEpoch))
              add("Amount" to SarimFormat.formatRs(p.amount, true, hideMoney))
              add("Daily Ledger" to if (p.addToLedger) "Linked" else "Not linked")
            },
            detailNote = if (p.description.isNotBlank()) "Note: ${p.description}" else "",
          )
        }
        AccordionHistoryList(
          records = records,
          yearSummary = { items -> "${items.size} entries" },
          monthSummary = { items ->
            val inTot = items.filter { it.item.type == "PAYMENT_IN" }.sumOf { it.item.amount }
            val outTot = items.filter { it.item.type != "PAYMENT_IN" }.sumOf { it.item.amount }
            "${items.size} · In ${SarimFormat.formatRs(inTot, false)} · Out ${SarimFormat.formatRs(outTot, false)}"
          },
          daySummary = { items -> "${items.size} entries" },
          hasDropdownDetails = true,
          onItemClick = { rec -> sheetPayment = rec.item },
        ) { rec ->
          val p = rec.item
          val deleteTitle = when (p.type) {
            "PAYMENT_IN" -> "Delete Payment IN"
            "PAYMENT_OUT" -> "Delete Payment OUT"
            else -> "Delete Expense"
          }
          val deleteTypeDesc = when (p.type) {
            "PAYMENT_IN" -> "Payment Received (IN)"
            "PAYMENT_OUT" -> "Payment Made (OUT)"
            else -> "Operating Expense"
          }
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.photoUri.isNotBlank()) {
              SarimSecondaryButton(
                text = "View Attached Photo",
                onClick = { viewModel.openPhotoViewer(listOf(p.photoUri), 0) },
                modifier = Modifier.fillMaxWidth(),
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SarimSecondaryButton(
                text = "Edit",
                onClick = {
                  editingTxId = p.id
                  dateEpoch = p.dateEpoch
                  txType = PayTxType.entries.firstOrNull { it.code == p.type } ?: PayTxType.PAYMENT_OUT
                  nameText = p.entityOrExpenseName
                  amountText = p.amount.toString()
                  descriptionText = p.description
                  photoUris = if (p.photoUri.isNotBlank()) listOf(p.photoUri) else emptyList()
                  addToLedger = p.addToLedger
                },
                modifier = Modifier.weight(1f),
              )
              SarimDangerButton(
                text = "Delete",
                icon = Icons.Rounded.Delete,
                onClick = {
                  viewModel.showDialog(
                    AppDialogRequest(
                      title = deleteTitle,
                      body = "Delete this $deleteTypeDesc?\nEntity: ${p.entityOrExpenseName}\nDate: ${SarimFormat.dateInput(p.dateEpoch)}\nPermanent: This cannot be undone.",
                      tone = DialogTone.PERMANENT,
                      highlight = DialogHighlight("Amount", SarimFormat.formatRs(p.amount, false, hideMoney)),
                      confirmLabel = "Delete",
                      onConfirm = {
                        viewModel.launchSafely(errorTitle = "Delete Payment Failed", successMessage = "Moved to Recycle Bin") {
                          viewModel.repository.deletePaymentTransaction(p.id)
                        }
                      },
                    )
                  )
                },
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }
    }
  }

  sheetPayment?.let { p ->
    val isIn = p.type == "PAYMENT_IN"
    val deleteTitle = when (p.type) {
      "PAYMENT_IN" -> "Delete Payment IN"
      "PAYMENT_OUT" -> "Delete Payment OUT"
      else -> "Delete Expense"
    }
    val deleteTypeDesc = when (p.type) {
      "PAYMENT_IN" -> "Payment Received (IN)"
      "PAYMENT_OUT" -> "Payment Made (OUT)"
      else -> "Operating Expense"
    }
    SarimRecordBottomSheet(
      title = p.entityOrExpenseName,
      subtitle = "${SarimFormat.formatDate(p.dateEpoch)}${if (p.timeStr.isNotBlank()) " · ${p.timeStr}" else ""}",
      amountText = "${if (isIn) "+" else "−"} ${SarimFormat.formatRs(p.amount, false, hideMoney)}",
      amountColor = if (isIn) palette.green else palette.red,
      badgeText = p.type.replace("_", " "),
      badgeColor = if (isIn) palette.green else palette.red,
      metrics = buildList {
        add("Type" to p.type.replace("_", " "))
        add("Date" to SarimFormat.dateInput(p.dateEpoch))
        add("Amount" to SarimFormat.formatRs(p.amount, true, hideMoney))
        if (p.description.isNotBlank()) add("Description" to p.description)
        add("Daily Ledger" to if (p.addToLedger) "Linked" else "Not linked")
      },
      onDismiss = { sheetPayment = null },
      extraActionLabel = if (p.photoUri.isNotBlank()) "View Attached Photo" else null,
      onExtraAction = if (p.photoUri.isNotBlank()) { { viewModel.openPhotoViewer(listOf(p.photoUri), 0) } } else null,
      onEdit = {
        editingTxId = p.id
        dateEpoch = p.dateEpoch
        txType = PayTxType.entries.firstOrNull { it.code == p.type } ?: PayTxType.PAYMENT_OUT
        nameText = p.entityOrExpenseName
        amountText = p.amount.toString()
        descriptionText = p.description
        photoUris = if (p.photoUri.isNotBlank()) listOf(p.photoUri) else emptyList()
        addToLedger = p.addToLedger
      },
      onDelete = {
        viewModel.showDialog(
          AppDialogRequest(
            title = deleteTitle,
            body = "Delete this $deleteTypeDesc?\nEntity: ${p.entityOrExpenseName}\nDate: ${SarimFormat.dateInput(p.dateEpoch)}\nPermanent: This cannot be undone.",
            tone = DialogTone.PERMANENT,
            highlight = DialogHighlight("Amount", SarimFormat.formatRs(p.amount, false, hideMoney)),
            confirmLabel = "Delete",
            onConfirm = {
              viewModel.launchSafely(errorTitle = "Delete Payment Failed", successMessage = "Moved to Recycle Bin") {
                viewModel.repository.deletePaymentTransaction(p.id)
              }
            },
          )
        )
      },
    )
  }
}

@Composable
private fun HealthLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color, bold: Boolean = false) {
  val palette = LocalSarimPalette.current
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = if (bold) palette.textPrimary else palette.textMuted, fontSize = 12.5.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    Text(value, color = valueColor, style = TabularNumberStyle.copy(fontSize = 12.5.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold))
  }
}
