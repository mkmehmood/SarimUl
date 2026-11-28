package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ApprovalRequestEntity
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

@Composable
fun StockTransferScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val stores = remember(state.stores) { state.stores.filter { !it.isDeleted } }
  val storeNames = remember(stores) { listOf("Select Store") + stores.map { it.name } }

  var fromIndex by remember { mutableIntStateOf(0) }
  var toIndex by remember { mutableIntStateOf(0) }
  var dateEpoch by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var qtyText by remember { mutableStateOf("") }
  var noteText by remember { mutableStateOf("") }

  val fromStore = stores.getOrNull(fromIndex - 1)
  val toStore = stores.getOrNull(toIndex - 1)
  val availableAtSource = fromStore?.let { state.snapshot?.storeMetrics?.get(it.storeKey)?.remainingKg ?: 0.0 } ?: 0.0

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SarimFieldLabel("Move Stock Between Stores")
    SarimCard {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          SarimPopoverSelector(
            label = "From Store",
            selectedText = storeNames.getOrElse(fromIndex) { "Select Store" },
            options = storeNames,
            onSelect = { idx, _ -> fromIndex = idx },
            testTag = "stockTransferFromBtn",
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          SarimPopoverSelector(
            label = "To Store",
            selectedText = storeNames.getOrElse(toIndex) { "Select Store" },
            options = storeNames,
            onSelect = { idx, _ -> toIndex = idx },
            testTag = "stockTransferToBtn",
          )
        }
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        SarimDatePickerField(
          label = "Date",
          dateEpoch = dateEpoch,
          onDateSelected = { dateEpoch = it },
          modifier = Modifier.weight(1f),
          testTag = "stock_transfer_date",
        )
        SarimTextField(
          value = qtyText,
          onValueChange = { qtyText = it },
          label = "Quantity (kg)",
          placeholder = "0.00",
          suffixText = "kg",
          keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f),
          testTag = "stock_transfer_qty",
        )
      }
      SarimTextField(
        value = noteText,
        onValueChange = { noteText = it },
        label = "Note (optional)",
        placeholder = "e.g. Balancing stock ahead of weekend rush",
        maxLength = 120,
        testTag = "stock_transfer_note",
      )

      Text(
        text = if (fromStore != null) {
          "Available at ${fromStore.name}: ${SarimFormat.formatKg(availableAtSource)}"
        } else {
          "Select a source store to view available stock."
        },
        color = if (fromStore != null && availableAtSource <= 0.0) palette.amber else palette.cyan,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.testTag("stockTransferAvailability"),
      )

      SarimPrimaryButton(
        text = "Transfer Stock",
        testTag = "stock_transfer_save_btn",
        onClick = {
          if (fromStore == null || toStore == null) {
            viewModel.showDialog(AppDialogRequest("Select Stores", "Choose both From Store and To Store.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (fromStore.storeKey == toStore.storeKey) {
            viewModel.showDialog(AppDialogRequest("Invalid Transfer", "Source and destination store must be different.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (SarimFormat.isFutureDate(dateEpoch)) {
            viewModel.showDialog(AppDialogRequest("Invalid Date", "Transfer date cannot be in the future.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          val qty = SarimFormat.parseSafeDouble(qtyText)
          if (qty == null || qty < 0.001 || qty > 1_000_000.0) {
            viewModel.showDialog(AppDialogRequest("Invalid Quantity", "Enter a quantity between 0.001 and 1,000,000 kg.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (qty > availableAtSource + 0.0001) {
            viewModel.showDialog(
              AppDialogRequest(
                title = "Insufficient Stock",
                body = "${fromStore.name} only has ${SarimFormat.formatKg(availableAtSource)} available.",
                tone = DialogTone.ERROR,
                cancelLabel = null,
              )
            )
            return@SarimPrimaryButton
          }
          viewModel.confirmSave(
"Stock Transfer",
listOf("From" to fromStore.name, "To" to toStore.name, "Quantity" to SarimFormat.formatKg(qty), "Date" to SarimFormat.formatDate(dateEpoch), "Note" to noteText),
) {
viewModel.launchSafely(
            errorTitle = "Transfer Failed",
            successMessage = "Transferred ${SarimFormat.formatKg(qty)} from ${fromStore.name} to ${toStore.name}",
            onSuccess = {
              qtyText = ""
              noteText = ""
            },
          ) {
            viewModel.repository.saveStockTransfer(fromStore, toStore, dateEpoch, qty, noteText)
          }
}
        },
      )
    }

    SarimCallout(
      text = "Stock transfer is only allowed up to the stock currently available at the source store.",
      toneColor = palette.cyan,
    )

    SarimCard {
      Text("Transfer History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      val transfers = remember(state.stockTransfers) {
        state.stockTransfers.filter { !it.isDeleted }.sortedByDescending { it.dateEpoch }
      }
      AccordionHistoryList(
        records = transfers.map { t ->
          HistoryRecordDisplay(
            item = t,
            dateEpoch = t.dateEpoch,
            title = "${t.fromStoreName} → ${t.toStoreName}",
            subtitle = t.note.ifBlank { "Inter-store transfer" },
            amountText = SarimFormat.formatKg(t.quantityKg),
            amountColor = palette.cyan,
            detailMetrics = listOf(
              "From Store" to t.fromStoreName,
              "To Store" to t.toStoreName,
              "Quantity" to SarimFormat.formatKg(t.quantityKg),
              "Date" to SarimFormat.dateInput(t.dateEpoch),
            ),
            detailNote = if (t.note.isNotBlank()) "Note: ${t.note}" else "",
          )
        },
        yearSummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.quantityKg }) },
        monthSummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.quantityKg }) },
        daySummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.quantityKg }) },
        emptySubtitle = "No stock transfers recorded yet.",
        actions = HistoryRecordActions<com.example.data.local.StockTransferEntity>(
          deleteLabel = "Delete Transfer",
          onDelete = { t ->
            val blockMsg = SarimLinkEngine.getStockTransferDeleteBlockReason(
              transfer = t,
              production = state.productionEntries,
              sales = state.sales,
              stockTransfers = state.stockTransfers,
              settlements = state.settlements,
            )
            if (blockMsg != null) {
              viewModel.showDialog(AppDialogRequest("Cannot Delete Transfer", blockMsg, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
            } else {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Delete Stock Transfer",
                  body = "Reverse ${SarimFormat.formatKg(t.quantityKg)} transfer (${t.fromStoreName} → ${t.toStoreName}) and move to Recycle Bin?",
                  tone = DialogTone.PERMANENT,
                  confirmLabel = "Delete",
                  onConfirm = {
                    viewModel.launchSafely(
                      errorTitle = "Delete Failed",
                      successMessage = "Deleted stock transfer",
                    ) {
                      viewModel.repository.deleteStockTransfer(t.id)
                    }
                  },
                )
              )
            }
          },
        ),
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
fun PaymentTransferScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val entities = remember(state.paymentEntities) { state.paymentEntities.filter { !it.isDeleted && !it.isExpenseEntity } }
  val entityNames = remember(entities) { entities.map { it.name } }

  var fromQuery by remember { mutableStateOf("") }
  var toQuery by remember { mutableStateOf("") }
  var dateEpoch by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var amountText by remember { mutableStateOf("") }
  var noteText by remember { mutableStateOf("") }
  var photoUris by remember { mutableStateOf<List<String>>(emptyList()) }

  val fromEntity = entities.firstOrNull { it.name.equals(fromQuery.trim(), ignoreCase = true) }
  val toEntity = entities.firstOrNull { it.name.equals(toQuery.trim(), ignoreCase = true) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SarimFieldLabel("Move Balance Between Entities")
    SarimCard {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          SarimSearchFieldWithDropdown(
            value = fromQuery,
            onValueChange = { fromQuery = it },
            label = "From Entity",
            placeholder = "Search entity...",
            suggestions = entityNames,
            onSuggestionSelected = { fromQuery = it },
            testTag = "payment_transfer_from_search",
          )
          if (fromEntity != null) {
            Text(
              "Balance: ${SarimFormat.formatRs(fromEntity.currentBalance, privacyHidden = state.hideMoneyFigures)}",
              color = if (fromEntity.currentBalance > 0) palette.green else palette.amber,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
            )
          }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          SarimSearchFieldWithDropdown(
            value = toQuery,
            onValueChange = { toQuery = it },
            label = "To Entity",
            placeholder = "Search entity...",
            suggestions = entityNames,
            onSuggestionSelected = { toQuery = it },
            testTag = "payment_transfer_to_search",
          )
        }
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        SarimDatePickerField(
          label = "Date",
          dateEpoch = dateEpoch,
          onDateSelected = { dateEpoch = it },
          modifier = Modifier.weight(1f),
          testTag = "payment_transfer_date",
        )
        SarimTextField(
          value = amountText,
          onValueChange = { amountText = it },
          label = "Amount",
          placeholder = "0.00",
          prefixText = "Rs",
          keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f),
          testTag = "payment_transfer_amount",
        )
      }

      SarimTextField(
        value = noteText,
        onValueChange = { noteText = it },
        label = "Note (optional)",
        placeholder = "e.g. Settling shared supplier bill",
        maxLength = 120,
        testTag = "payment_transfer_note",
      )

      PhotoAttachmentBar(
        photoUris = photoUris,
        onAddUris = { photoUris = (photoUris + it).take(4) },
        onClearAll = { photoUris = emptyList() },
        onViewPhotos = { idx -> viewModel.openPhotoViewer(photoUris, idx) },
        onError = { msg -> viewModel.postStatus(msg) },
      )

      SarimPrimaryButton(
        text = "Transfer Payment",
        testTag = "payment_transfer_save_btn",
        onClick = {
          if (fromEntity == null || toEntity == null) {
            viewModel.showDialog(AppDialogRequest("Select Entities", "Pick valid From Entity and To Entity records.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (fromEntity.id == toEntity.id) {
            viewModel.showDialog(AppDialogRequest("Invalid Transfer", "Source and destination entities must differ.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (SarimFormat.isFutureDate(dateEpoch)) {
            viewModel.showDialog(AppDialogRequest("Invalid Date", "Future dates are not allowed.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          val amt = SarimFormat.parseSafeDouble(amountText)
          if (amt == null || amt <= 0.0) {
            viewModel.showDialog(AppDialogRequest("Invalid Amount", "Enter a positive transfer amount.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (fromEntity.currentBalance + 0.01 < amt) {
            viewModel.showDialog(
              AppDialogRequest(
                title = "Insufficient Entity Balance",
                body = "${fromEntity.name} has ${SarimFormat.formatRs(fromEntity.currentBalance)}, which is less than ${SarimFormat.formatRs(amt)}.",
                tone = DialogTone.ERROR,
                cancelLabel = null,
              )
            )
            return@SarimPrimaryButton
          }
          viewModel.confirmSave(
"Transfer",
listOf("From" to fromEntity.name, "To" to toEntity.name, "Amount" to SarimFormat.formatRs(amt), "Date" to SarimFormat.formatDate(dateEpoch), "Note" to noteText),
) {
viewModel.launchSafely(
            errorTitle = "Transfer Failed",
            successMessage = "Transferred ${SarimFormat.formatRs(amt)} to ${toEntity.name}",
            onSuccess = {
              amountText = ""
              noteText = ""
              photoUris = emptyList()
            },
          ) {
            viewModel.repository.savePaymentTransfer(
              from = fromEntity,
              to = toEntity,
              dateEpoch = dateEpoch,
              amount = amt,
              note = noteText,
              photoUri = photoUris.firstOrNull().orEmpty(),
            )
          }
}
        },
      )
    }

    SarimCallout(
      text = "The transfer records an OUT for the source entity and an IN for the destination entity. The source entity needs at least that balance.",
      toneColor = palette.amber,
    )

    SarimCard {
      Text("Transfer History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      val list = remember(state.paymentTransfers) {
        state.paymentTransfers.filter { !it.isDeleted }.sortedByDescending { it.dateEpoch }
      }
      AccordionHistoryList(
        records = list.map { t ->
          HistoryRecordDisplay(
            item = t,
            dateEpoch = t.dateEpoch,
            title = "${t.fromEntityName} → ${t.toEntityName}",
            subtitle = t.note.ifBlank { "Balance transfer" },
            amountText = SarimFormat.formatRs(t.amount, privacyHidden = state.hideMoneyFigures),
            amountColor = palette.purple,
            detailMetrics = listOf(
              "From Entity" to t.fromEntityName,
              "To Entity" to t.toEntityName,
              "Amount" to SarimFormat.formatRs(t.amount, privacyHidden = state.hideMoneyFigures),
              "Date" to SarimFormat.dateInput(t.dateEpoch),
            ),
            detailNote = if (t.note.isNotBlank()) "Note: ${t.note}" else "",
          )
        },
        yearSummary = { items -> SarimFormat.formatRs(items.sumOf { it.item.amount }, privacyHidden = state.hideMoneyFigures) },
        monthSummary = { items -> SarimFormat.formatRs(items.sumOf { it.item.amount }, privacyHidden = state.hideMoneyFigures) },
        daySummary = { items -> SarimFormat.formatRs(items.sumOf { it.item.amount }, privacyHidden = state.hideMoneyFigures) },
        emptySubtitle = "No payment transfers recorded yet.",
        actions = HistoryRecordActions<com.example.data.local.PaymentTransferEntity>(
          deleteLabel = "Delete Transfer",
          onDelete = { t ->
            val blockMsg = SarimLinkEngine.getPaymentTransferDeleteBlockReason(
              transfer = t,
              fromEntity = entities.firstOrNull { it.id == t.fromEntityId },
              toEntity = entities.firstOrNull { it.id == t.toEntityId },
            )
            if (blockMsg != null) {
              viewModel.showDialog(AppDialogRequest("Cannot Delete Transfer", blockMsg, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
            } else {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Delete Payment Transfer",
                  body = "Reverse ${SarimFormat.formatRs(t.amount)} transfer (${t.fromEntityName} → ${t.toEntityName}) and move to Recycle Bin?",
                  tone = DialogTone.PERMANENT,
                  confirmLabel = "Delete",
                  onConfirm = {
                    viewModel.launchSafely(
                      errorTitle = "Delete Failed",
                      successMessage = "Deleted payment transfer",
                    ) {
                      viewModel.repository.deletePaymentTransfer(t.id)
                    }
                  },
                )
              )
            }
          },
        ),
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
fun ApprovalsScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val pending = remember(state.approvals) { state.approvals.filter { it.status == "PENDING" }.sortedByDescending { it.createdAt } }

  var searchQuery by remember { mutableStateOf("") }
  var selectedType by remember { mutableStateOf("All") }
  var selectedPerson by remember { mutableStateOf("All") }

  val categories = listOf("All", "Sales", "Payments", "Expenses", "Production", "Factory", "Stock", "Customers", "Other")
  val people = remember(pending) { listOf("All") + pending.map { it.submittedBy }.distinct() }

  val filtered = remember(pending, searchQuery, selectedType, selectedPerson) {
    val q = searchQuery.trim().lowercase()
    pending.filter { req ->
      val typeOk = selectedType == "All" || req.category.equals(selectedType, ignoreCase = true)
      val personOk = selectedPerson == "All" || req.submittedBy.equals(selectedPerson, ignoreCase = true)
      val qOk = q.isEmpty() || req.submittedBy.lowercase().contains(q) || req.targetSummary.lowercase().contains(q) || req.amount.toString().contains(q)
      typeOk && personOk && qOk
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Hero card
    SarimCard(borderColor = if (pending.isEmpty()) palette.green.copy(alpha = 0.5f) else palette.amber.copy(alpha = 0.5f)) {
      if (pending.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          SarimChip("All caught up", palette.green)
          Text("No staff requests waiting for approval.", color = palette.textMuted, fontSize = 13.sp)
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SarimChip("${pending.size}", palette.amber)
              Text("waiting for approval", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text("Review staff submissions before committing to books.", color = palette.textMuted, fontSize = 12.sp)
          }
        }
      }
    }

    if (pending.isNotEmpty()) {
      SarimTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        label = "",
        placeholder = "Search by name, item or amount",
        leadingIcon = Icons.Rounded.Search,
        testTag = "approvals_search",
      )

      // Type chips
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        categories.forEach { cat ->
          val count = if (cat == "All") pending.size else pending.count { it.category.equals(cat, ignoreCase = true) }
          if (cat == "All" || count > 0) {
            FilterChip(
              selected = selectedType == cat,
              onClick = { selectedType = cat },
              label = { Text("$cat ($count)") },
            )
          }
        }
      }

      // Person chips
      if (people.size > 2) {
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          people.forEach { person ->
            FilterChip(
              selected = selectedPerson == person,
              onClick = { selectedPerson = person },
              label = { Text(person) },
            )
          }
        }
      }

      // Bulk buttons
      if (filtered.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimSecondaryButton(
            text = "Approve all (${filtered.size})",
            icon = Icons.Rounded.Check,
            modifier = Modifier.weight(1f),
            onClick = {
              if (!state.isOnline) {
                viewModel.showDialog(AppDialogRequest("No Internet", "Approvals require an active internet connection so every device stays in sync.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimSecondaryButton
              }
              val batch = filtered.toList()
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Approve All Pending?",
                  body = "Approve all ${batch.size} pending request(s)?",
                  tone = DialogTone.CONFIRM,
                  confirmLabel = "Approve All",
                  onConfirm = {
                    viewModel.launchSafely(
                      errorTitle = "Approve All Failed",
                      successMessage = "Approved ${batch.size} request(s)",
                    ) {
                      viewModel.resolveApprovals(batch, approved = true)
                    }
                  },
                )
              )
            },
          )
          SarimDangerButton(
            text = "Discard all (${filtered.size})",
            icon = Icons.Rounded.Close,
            modifier = Modifier.weight(1f),
            onClick = {
              if (!state.isOnline) {
                viewModel.showDialog(AppDialogRequest("No Internet", "Approvals require an active internet connection so every device stays in sync.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimDangerButton
              }
              val batch = filtered.toList()
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Discard All Pending?",
                  body = "Permanently discard all ${batch.size} pending request(s)? This cannot be undone.",
                  tone = DialogTone.PERMANENT,
                  confirmLabel = "Discard All",
                  onConfirm = {
                    viewModel.launchSafely(
                      errorTitle = "Discard All Failed",
                      successMessage = "Discarded ${batch.size} request(s)",
                    ) {
                      viewModel.resolveApprovals(batch, approved = false)
                    }
                  },
                )
              )
            },
          )
        }
      }
    }

    if (filtered.isEmpty() && pending.isNotEmpty()) {
      SarimEmptyState("No matches", "Nothing matches your search or filter.")
    } else {
      filtered.forEach { req: ApprovalRequestEntity ->
        SarimCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("${req.submittedBy} · ${req.category}", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
              Text("${req.actionKind} · ${req.targetSummary}", color = palette.textMuted, fontSize = 12.sp)
            }
            Text(
              SarimFormat.formatRs(req.amount, privacyHidden = state.hideMoneyFigures),
              color = palette.amber,
              style = TabularNumberStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimSecondaryButton(
              text = "Approve",
              icon = Icons.Rounded.Check,
              modifier = Modifier.weight(1f),
              onClick = {
                if (!state.isOnline) {
                  viewModel.showDialog(AppDialogRequest("No Internet", "Approvals require an active internet connection so every device stays in sync.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                  return@SarimSecondaryButton
                }
                viewModel.launchSafely(
                  errorTitle = "Approval Failed",
                  successMessage = "Approved",
                ) {
                  viewModel.resolveApproval(req, approved = true)
                }
              },
            )
            SarimDangerButton(
              text = "Discard",
              icon = Icons.Rounded.Close,
              modifier = Modifier.weight(1f),
              onClick = {
                if (!state.isOnline) {
                  viewModel.showDialog(AppDialogRequest("No Internet", "Approvals require an active internet connection so every device stays in sync.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                  return@SarimDangerButton
                }
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Discard Request?",
                    body = "Discard ${req.actionKind} (${req.targetSummary}) from ${req.submittedBy}?",
                    tone = DialogTone.PERMANENT,
                    confirmLabel = "Discard",
                    onConfirm = {
                      viewModel.launchSafely(
                        errorTitle = "Discard Failed",
                        successMessage = "Discarded by admin",
                      ) {
                        viewModel.resolveApproval(req, approved = false)
                      }
                    },
                  )
                )
              },
            )
          }
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}
