package com.example.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.*
import com.example.viewmodel.BaseLedgerViewModel
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

@Composable
fun DailyLedgerScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
  ledgerViewModel: BaseLedgerViewModel,
) {
  val palette = LocalSarimPalette.current
  val context = LocalContext.current
  val ledgerState by ledgerViewModel.uiState.collectAsStateWithLifecycle()

  val activeEntries = ledgerState.entries
  val channelBalances = ledgerState.channelBalances
  val cashGroup = ledgerState.cashGroup
  val bankGroup = ledgerState.bankGroup
  val othersGroup = ledgerState.othersGroup
  val totalCashInHand = ledgerState.totalCashInHand
  val totalIn = ledgerState.totalIn
  val totalOut = ledgerState.totalOut
  val netBalance = ledgerState.netBalance
  val categorySummary = ledgerState.categorySummary
  val knownChannels = ledgerState.knownChannels

  LaunchedEffect(ledgerState.statusMessage, ledgerState.errorMessage) {
    val msg = ledgerState.errorMessage ?: ledgerState.statusMessage
    if (!msg.isNullOrBlank()) {
      viewModel.postStatus(msg)
      ledgerViewModel.clearMessages()
    }
  }

  // Form state
  var editingId by remember { mutableStateOf<String?>(null) }
  var entryType by remember { mutableStateOf("IN") } // IN, OUT, TRANSFER
  var dateEpoch by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var amountText by remember { mutableStateOf("") }
  var modeText by remember { mutableStateOf("Cash") }
  var toModeText by remember { mutableStateOf("Bank Transfer") }
  var partyText by remember { mutableStateOf("") }
  var refText by remember { mutableStateOf("") }
  var noteText by remember { mutableStateOf("") }

  fun resetForm() {
    editingId = null
    amountText = ""
    modeText = "Cash"
    toModeText = "Bank Transfer"
    partyText = ""
    refText = ""
    noteText = ""
  }

  val showSkeleton = state.isSyncing || ledgerState.isLoading

  Crossfade(
    targetState = showSkeleton,
    animationSpec = tween(220),
    label = "ledger_skeleton_crossfade",
  ) { loading ->
    if (loading) {
      LedgerSkeletonContent(isSyncing = state.isSyncing)
    } else {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // Hero card: Cash In Hand + Sync + PDF Export
        SarimCard(borderColor = palette.green.copy(alpha = 0.45f)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("CASH IN HAND", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
              Text(
                text = SarimFormat.formatRs(totalCashInHand, privacyHidden = state.hideMoneyFigures),
                color = if (totalCashInHand >= 0) palette.green else palette.red,
                style = TabularNumberStyle.copy(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold),
                modifier = Modifier.testTag("dl_hero_amt"),
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
              if (!state.isOfflineSession) {
                IconButton(
                  onClick = { viewModel.syncDataNow() },
                  modifier = Modifier.size(44.dp).testTag("dl_sync_btn"),
                ) {
                  Icon(Icons.Filled.Sync, contentDescription = "Sync ledger with Firestore", tint = palette.cyan)
                }
              }
              Box(
                modifier = Modifier
                  .size(44.dp)
                  .clip(RoundedCornerShape(12.dp))
                  .background(Brush.linearGradient(listOf(palette.gradientStart, palette.gradientEnd)))
                  .testTag("dl_pdf_btn")
                  .clickable {
                    val lines = activeEntries.take(80).map {
                      "${SarimFormat.formatDate(it.dateEpoch)} | ${it.type} | ${it.mode} | ${SarimFormat.formatRs(it.amount, privacyHidden = state.hideMoneyFigures)} | ${it.party.ifBlank { it.note }}"
                    }
                    val res = PdfExporter.sharePdfReport(
                      context = context,
                      fileNamePrefix = "daily_ledger",
                      title = "Daily Ledger Cashbook",
                      subtitle = "Net Cash In Hand: ${SarimFormat.formatRs(totalCashInHand, privacyHidden = state.hideMoneyFigures)}",
                      lines = lines.ifEmpty { listOf("No cashbook entries recorded.") },
                    )
                    if (res.isFailure) viewModel.postStatus("Unable to export PDF")
                  },
                contentAlignment = Alignment.Center,
              ) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = "Export Daily Ledger PDF", tint = Color(0xFF022112), modifier = Modifier.size(20.dp))
              }
            }
          }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SarimStatTile(
          label = "Cash",
          value = SarimFormat.formatRs(cashGroup, privacyHidden = state.hideMoneyFigures),
          modifier = Modifier.weight(1f),
          accentColor = palette.green,
        )
        SarimStatTile(
          label = "Bank",
          value = SarimFormat.formatRs(bankGroup, privacyHidden = state.hideMoneyFigures),
          modifier = Modifier.weight(1f),
          accentColor = palette.cyan,
        )
        SarimStatTile(
          label = "Others",
          value = SarimFormat.formatRs(othersGroup, privacyHidden = state.hideMoneyFigures),
          modifier = Modifier.weight(1f),
          accentColor = palette.purple,
        )
      }
    }

    // Summary Tiles
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SarimStatTile(
        label = "Total Cash In",
        value = SarimFormat.formatRs(totalIn, privacyHidden = state.hideMoneyFigures),
        subText = "${ledgerState.inCount} entries",
        accentColor = palette.green,
        modifier = Modifier.weight(1f),
      )
      SarimStatTile(
        label = "Total Cash Out",
        value = SarimFormat.formatRs(totalOut, privacyHidden = state.hideMoneyFigures),
        subText = "${ledgerState.outCount} entries",
        accentColor = palette.red,
        modifier = Modifier.weight(1f),
      )
      SarimStatTile(
        label = "Net Balance",
        value = SarimFormat.formatRs(netBalance, privacyHidden = state.hideMoneyFigures),
        subText = if (netBalance >= 0) "Surplus" else "Deficit",
        accentColor = if (netBalance >= 0) palette.green else palette.red,
        modifier = Modifier.weight(1f),
      )
    }

    // Entry Form Card
    SarimCard {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = when {
            editingId != null -> "Edit Entry"
            entryType == "TRANSFER" -> "Transfer Between Channels"
            else -> "Add Entry"
          },
          color = palette.textPrimary,
          fontWeight = FontWeight.Bold,
          fontSize = 15.5.sp,
        )
        if (editingId != null) {
          SarimChip("Editing", palette.amber)
        }
      }

      // Three wide buttons: + In (green), − Out (red), Transfer (blue/cyan)
      SarimToggleGroup(
        options = listOf("IN", "OUT", "TRANSFER"),
        selected = entryType,
        labelOf = {
          when (it) {
            "IN" -> "+ In"
            "OUT" -> "− Out"
            else -> "Transfer"
          }
        },
        colorOf = {
          when (it) {
            "IN" -> palette.green
            "OUT" -> palette.red
            else -> palette.cyan
          }
        },
        onSelect = { entryType = it },
        testTagPrefix = "dl_type",
      )

      SarimDatePickerField(
        label = "Date",
        dateEpoch = dateEpoch,
        onDateSelected = { dateEpoch = it },
        testTag = "dl_date",
      )

      SarimTextField(
        value = amountText,
        onValueChange = { amountText = it },
        label = "Amount",
        placeholder = "0.00",
        prefixText = "Rs",
        keyboardType = KeyboardType.Decimal,
        testTag = "dl_amount",
      )

      SarimSearchFieldWithDropdown(
        value = modeText,
        onValueChange = { modeText = it },
        label = if (entryType == "TRANSFER") "From Channel (Source)" else "Payment Channel / Mode",
        placeholder = "e.g. Cash, Bank Transfer, Easypaisa...",
        suggestions = knownChannels,
        onSuggestionSelected = { modeText = it },
        testTag = "dl_mode",
      )

      if (entryType == "TRANSFER") {
        SarimSearchFieldWithDropdown(
          value = toModeText,
          onValueChange = { toModeText = it },
          label = "To Channel (Destination)",
          placeholder = "e.g. Bank Transfer, Cash...",
          suggestions = knownChannels,
          onSuggestionSelected = { toModeText = it },
          testTag = "dl_to_mode",
        )
      } else {
        SarimTextField(
          value = partyText,
          onValueChange = { partyText = it },
          label = "Party / Customer / Vendor (optional)",
          placeholder = "e.g. Ali Store",
          maxLength = 80,
          testTag = "dl_party",
        )
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SarimTextField(
          value = refText,
          onValueChange = { refText = it },
          label = "Bill / Ref #",
          placeholder = "Optional",
          maxLength = 40,
          modifier = Modifier.weight(1f),
          testTag = "dl_ref",
        )
        SarimTextField(
          value = noteText,
          onValueChange = { noteText = it },
          label = "Remarks / Note",
          placeholder = "Optional",
          maxLength = 120,
          modifier = Modifier.weight(1.4f),
          testTag = "dl_note",
        )
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editingId != null) {
          SarimSecondaryButton(
            text = "Cancel Edit",
            onClick = { resetForm() },
            modifier = Modifier.weight(1f),
            testTag = "dl_cancel_edit",
          )
        }
        SarimPrimaryButton(
          text = when (entryType) {
            "IN" -> "Add Cash In"
            "OUT" -> "Add Cash Out"
            else -> "Record Transfer"
          },
          modifier = Modifier.weight(1.4f),
          testTag = "dl_save_btn",
          onClick = {
            if (SarimFormat.isFutureDate(dateEpoch)) {
              viewModel.showDialog(AppDialogRequest("Invalid Date", "Future dates are refused on ledger entries.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val amt = SarimFormat.parseSafeDouble(amountText)
            if (amt == null || amt <= 0.0 || amt > 10_000_000_000.0) {
              viewModel.showDialog(AppDialogRequest("Invalid Amount", "Enter a plain positive amount up to 10,000,000,000.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (modeText.trim().isEmpty()) {
              viewModel.showDialog(AppDialogRequest("Channel Required", "Please specify a payment channel.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (entryType == "TRANSFER" && (toModeText.trim().isEmpty() || toModeText.trim().equals(modeText.trim(), ignoreCase = true))) {
              viewModel.showDialog(AppDialogRequest("Invalid Destination", "Choose a different destination channel for transfer.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            ledgerViewModel.saveEntry(
              editingId = editingId,
              dateEpoch = dateEpoch,
              type = entryType,
              amount = amt,
              mode = modeText.trim().take(40),
              toMode = toModeText.trim().take(40),
              party = partyText.trim().take(80),
              refNo = refText.trim().take(40),
              note = noteText.trim().take(120),
              onSaved = { resetForm() },
            )
          },
        )
      }
    }

    // Payment Channels Card
    SarimCard {
      Text("Payment Channels", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      if (channelBalances.isEmpty()) {
        Text("No channel movements yet.", color = palette.textMuted, fontSize = 12.5.sp)
      } else {
        channelBalances.forEach { (channel, bal) ->
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(channel, color = palette.textPrimary, fontSize = 13.5.sp)
            Text(
              text = "${if (bal >= 0) "+" else "−"} ${SarimFormat.formatRs(kotlin.math.abs(bal), showSymbol = false, privacyHidden = state.hideMoneyFigures)}",
              color = if (bal >= 0) palette.green else palette.red,
              style = TabularNumberStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
            )
          }
        }
      }
    }

    // Category Summary Card
    SarimCard {
      Text("Category Summary", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      if (categorySummary.isEmpty()) {
        Text("No expense categories recorded yet.", color = palette.textMuted, fontSize = 12.5.sp)
      } else {
        categorySummary.forEach { (cat, sum) ->
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(cat, color = palette.textPrimary, fontSize = 13.sp)
            Text(
              SarimFormat.formatRs(sum, privacyHidden = state.hideMoneyFigures),
              color = palette.amber,
              style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
          }
        }
      }
    }

    // Cashbook Log & History
    SarimCard {
      Text("Cashbook Log & History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      SarimToggleGroup(
        options = listOf("ALL", "IN", "OUT", "TRANSFER"),
        selected = ledgerState.typeFilter,
        labelOf = { it },
        onSelect = { ledgerViewModel.setTypeFilter(it) },
        testTagPrefix = "dl_filter",
      )
      SarimTextField(
        value = ledgerState.searchQuery,
        onValueChange = { ledgerViewModel.setSearchQuery(it) },
        label = "",
        placeholder = "Search by party, category, mode, amount...",
        leadingIcon = Icons.Filled.Search,
        testTag = "dl_search",
      )

      val filtered = ledgerState.filteredEntries

      AccordionHistoryList(
        records = filtered.map { e ->
          val c = when (e.type) {
            "IN" -> palette.green
            "OUT" -> palette.red
            else -> palette.cyan
          }
          HistoryRecordDisplay(
            item = e,
            dateEpoch = e.dateEpoch,
            title = if (e.type == "TRANSFER") "${e.mode} → ${e.toMode}" else e.party.ifBlank { e.category },
            subtitle = "${e.mode}${if (e.note.isNotBlank()) " · ${e.note}" else ""}",
            amountText = SarimFormat.formatRs(e.amount, privacyHidden = state.hideMoneyFigures),
            amountColor = c,
            chipText = e.type,
            chipColor = c,
          )
        },
        yearSummary = { items -> "${items.size} entries" },
        monthSummary = { items ->
          val mIn = items.filter { it.item.type == "IN" }.sumOf { it.item.amount }
          val mOut = items.filter { it.item.type == "OUT" }.sumOf { it.item.amount }
          "In ${SarimFormat.formatRs(mIn, privacyHidden = state.hideMoneyFigures)} · Out ${SarimFormat.formatRs(mOut, privacyHidden = state.hideMoneyFigures)}"
        },
        daySummary = { items -> "${items.size} row(s)" },
        emptySubtitle = "No cashbook entries match your search.",
      ) { rec ->
        val entry = rec.item
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (entry.refNo.isNotBlank()) Text("Ref #: ${entry.refNo}", color = palette.textMuted, fontSize = 12.sp)
          if (entry.note.isNotBlank()) Text("Note: ${entry.note}", color = palette.textMuted, fontSize = 12.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimSecondaryButton(
              text = "Edit",
              onClick = {
                editingId = entry.id
                entryType = entry.type
                dateEpoch = entry.dateEpoch
                amountText = entry.amount.toString()
                modeText = entry.mode
                toModeText = entry.toMode
                partyText = entry.party
                refText = entry.refNo
                noteText = entry.note
              },
              modifier = Modifier.weight(1f),
            )
            SarimDangerButton(
              text = "Delete",
              onClick = {
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Delete Ledger Entry",
                    body = "Move this ${entry.type} record of ${SarimFormat.formatRs(entry.amount)} to Recycle Bin?",
                    tone = DialogTone.PERMANENT,
                    confirmLabel = "Delete",
                    onConfirm = {
                      ledgerViewModel.deleteEntry(entry.id)
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
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}
