package com.example.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.DailyLedgerEntity
import com.example.data.local.JsonCodecs
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.*
import com.example.viewmodel.BaseLedgerViewModel
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.launch

@Composable
fun DailyLedgerScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
  ledgerViewModel: BaseLedgerViewModel,
) {
  val palette = LocalSarimPalette.current
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current
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
  var prevDayRef by remember { mutableLongStateOf(state.currentLocalDayEpoch) }

  LaunchedEffect(state.currentLocalDayEpoch) {
    val newDay = state.currentLocalDayEpoch
    if (newDay != prevDayRef) {
      dateEpoch = SarimLinkEngine.rolloverDateIfUnedited(dateEpoch, prevDayRef, newDay, editingId != null)
      prevDayRef = newDay
    }
  }
  var amountText by remember { mutableStateOf("") }
  var modeText by remember { mutableStateOf("Cash") }
  var toModeText by remember { mutableStateOf("Bank Transfer") }
  var partyText by remember { mutableStateOf("") }
  var refText by remember { mutableStateOf("") }
  var noteText by remember { mutableStateOf("") }
  var showCategoryDialog by remember { mutableStateOf(false) }
  var pendingAmount by remember { mutableDoubleStateOf(0.0) }
  var selectedCategory by remember { mutableStateOf("") }
  var selectedScope by remember { mutableStateOf("Business") }
  var sheetEntry by remember { mutableStateOf<DailyLedgerEntity?>(null) }
  var ambiguousLinkState by remember { mutableStateOf<SarimLinkEngine.LedgerLinkTarget.AmbiguousTarget?>(null) }
  val scope = rememberCoroutineScope()

  fun resetForm() {
    editingId = null
    amountText = ""
    modeText = "Cash"
    toModeText = "Bank Transfer"
    partyText = ""
    refText = ""
    noteText = ""
    selectedCategory = ""
    selectedScope = "Business"
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
        SarimCard(tonalAccent = palette.green) {
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
                  Icon(Icons.Rounded.Sync, contentDescription = "Sync ledger with Firestore", tint = palette.cyan)
                }
              }
              Box(
                modifier = Modifier
                  .size(44.dp)
                  .clip(RoundedCornerShape(16.dp))
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
                Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Export Daily Ledger PDF", tint = Color(0xFF022112), modifier = Modifier.size(20.dp))
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

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        SarimDatePickerField(
          label = "Date",
          dateEpoch = dateEpoch,
          onDateSelected = { dateEpoch = it },
          modifier = Modifier.weight(1f),
          testTag = "dl_date",
        )
        SarimTextField(
          value = amountText,
          onValueChange = { amountText = it },
          label = "Amount",
          placeholder = "0.00",
          prefixText = "Rs",
          keyboardType = KeyboardType.Decimal,
          modifier = Modifier.weight(1f),
          testTag = "dl_amount",
        )
      }

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
          val origEntry = activeEntries.firstOrNull { it.id == editingId }
          val hasEdits = origEntry == null ||
            (SarimFormat.parseSafeDouble(amountText) ?: 0.0) != origEntry.amount ||
            modeText.trim() != origEntry.mode ||
            partyText.trim() != origEntry.party ||
            noteText.trim() != origEntry.note
          SarimSecondaryButton(
            text = "Cancel Edit",
            onClick = { viewModel.requestCancelEdit(hasEdits) { resetForm() } },
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
          modifier = Modifier.weight(1f),
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
            if (entryType == "TRANSFER") {
              var chBal = 0.0
              channelBalances.forEach { (ch, b) -> if (ch.equals(modeText.trim(), ignoreCase = true)) chBal = b.toDouble() }
              val after = chBal - amt
              val insufficient = after < 0.0
              val fromCh = modeText.trim().take(40)
              val toCh = toModeText.trim().take(40)
              val bodyText = buildString {
                append("Transfer ${SarimFormat.formatRs(amt)} from $fromCh ➔ $toCh?\n\nThis only moves money between channels. Cash in hand does not change.")
                if (insufficient) append("\n\nWarning: $fromCh balance is ${if (chBal < 0) "-" else ""}${SarimFormat.formatRs(kotlin.math.abs(chBal))}. This transfer will leave it at -${SarimFormat.formatRs(kotlin.math.abs(after))}.")
              }
              viewModel.showDialog(
                AppDialogRequest(
                  title = if (insufficient) "Insufficient Channel Balance" else "Confirm Transfer",
                  body = bodyText,
                  tone = DialogTone.WARNING,
                  confirmLabel = if (editingId != null) "Update Transfer" else "Transfer",
                  onConfirm = {
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
              )
            } else {
              pendingAmount = amt
              if (selectedCategory.isBlank()) {
                selectedCategory = if (entryType == "IN") "General Income" else "General Expense"
              }
              showCategoryDialog = true
            }
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          channelBalances.forEach { (channel, bal) ->
            val balTone = if (bal >= 0) palette.green else palette.red
            Surface(
              shape = RoundedCornerShape(18.dp),
              color = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, balTone, 0.06f),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(channel, color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                  text = "${if (bal >= 0) "+" else "−"} ${SarimFormat.formatRs(kotlin.math.abs(bal), showSymbol = false, privacyHidden = state.hideMoneyFigures)}",
                  color = balTone,
                  style = TabularNumberStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                )
              }
            }
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          categorySummary.forEach { (cat, sum) ->
            Surface(
              shape = RoundedCornerShape(18.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(cat, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                  SarimFormat.formatRs(sum, privacyHidden = state.hideMoneyFigures),
                  color = palette.amber,
                  style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
              }
            }
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
        leadingIcon = Icons.Rounded.Search,
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
            showDots = e.type == "TRANSFER",
            detailMetrics = buildList {
              add("Channel" to e.mode)
              if (e.type == "TRANSFER" && e.toMode.isNotBlank()) add("To Channel" to e.toMode)
              if (e.category.isNotBlank()) add("Category" to e.category)
              if (e.party.isNotBlank()) add("Party" to e.party)
              if (e.scope.isNotBlank()) add("Scope" to e.scope)
              if (e.refNo.isNotBlank()) add("Ref #" to e.refNo)
            },
            detailNote = if (e.note.isNotBlank()) "Note: ${e.note}" else "",
          )
        },
        yearSummary = { "" },
        monthSummary = { items ->
          val mIn = items.filter { it.item.type == "IN" }.sumOf { it.item.amount }
          val mOut = items.filter { it.item.type == "OUT" }.sumOf { it.item.amount }
          "In ${SarimFormat.formatRs(mIn, privacyHidden = state.hideMoneyFigures)} · Out ${SarimFormat.formatRs(mOut, privacyHidden = state.hideMoneyFigures)}"
        },
        daySummary = { items ->
          val dIn = items.filter { it.item.type == "IN" }.sumOf { it.item.amount }
          val dOut = items.filter { it.item.type == "OUT" }.sumOf { it.item.amount }
          "In ${SarimFormat.formatRs(dIn, privacyHidden = state.hideMoneyFigures)} · Out ${SarimFormat.formatRs(dOut, privacyHidden = state.hideMoneyFigures)}"
        },
        emptySubtitle = "No cashbook entries match your search.",
        onItemClick = { rec -> sheetEntry = rec.item },
      )
    }
        Spacer(Modifier.height(24.dp))
      }
    }
  }

  if (showCategoryDialog) {
    val defaultCats = if (entryType == "IN") {
      listOf("Sales Revenue", "Debt Collection", "Partner Investment", "General Income", "Other Income")
    } else {
      listOf("Raw Materials", "Factory Expense", "Salary / Wages", "Fuel & Transport", "Rent & Utilities", "Supplier Payment", "Home / Personal", "General Expense")
    }
    val allSuggestions = (defaultCats + categorySummary.map { it.first }).distinct()
    val toneColor = if (entryType == "IN") palette.green else palette.red
    Dialog(
      onDismissRequest = { showCategoryDialog = false },
      properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false),
    ) {
      Surface(
        modifier = Modifier
          .fillMaxWidth(0.92f)
          .widthIn(max = 460.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
      ) {
        Column(
          modifier = Modifier
            .padding(22.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Select Category", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            SarimChip(if (entryType == "IN") "CASH IN" else "CASH OUT", toneColor)
          }
          Surface(
            shape = RoundedCornerShape(20.dp),
            color = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, toneColor, 0.12f),
          ) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Text(modeText.ifBlank { "Cash" }, color = palette.textMuted, fontSize = 13.sp)
              Text(
                SarimFormat.formatRs(pendingAmount, true, state.hideMoneyFigures),
                color = toneColor,
                style = TabularNumberStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
              )
            }
          }
          SarimSearchFieldWithDropdown(
            value = selectedCategory,
            onValueChange = { selectedCategory = it },
            label = "Category",
            placeholder = "Search or type category...",
            suggestions = allSuggestions,
            onSuggestionSelected = { selectedCategory = it },
            testTag = "dl_category_input",
          )
          if (entryType == "OUT") {
            SarimFieldLabel("Expense Scope")
            SarimToggleGroup(
              options = listOf("Business", "Home"),
              selected = selectedScope,
              labelOf = { it },
              onSelect = { selectedScope = it },
              testTagPrefix = "dl_scope",
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SarimSecondaryButton("Cancel", onClick = { showCategoryDialog = false }, modifier = Modifier.weight(1f), testTag = "dl_cat_cancel")
            SarimPrimaryButton(
              text = "Save Entry",
              onClick = {
                val chosenCat = selectedCategory.trim().ifBlank { if (entryType == "IN") "General Income" else "General Expense" }
                val chosenScope = if (entryType == "OUT") selectedScope else ""
                val cleanParty = partyText.trim().take(80)
                scope.launch {
                  when (val target = viewModel.resolveLedgerTarget(entryType, chosenScope, cleanParty, chosenCat)) {
                    is SarimLinkEngine.LedgerLinkTarget.AmbiguousTarget -> {
                      ambiguousLinkState = target
                    }
                    else -> {
                      ledgerViewModel.saveEntry(
                        editingId = editingId,
                        dateEpoch = dateEpoch,
                        type = entryType,
                        amount = pendingAmount,
                        mode = modeText.trim().take(40),
                        toMode = toModeText.trim().take(40),
                        party = cleanParty,
                        refNo = refText.trim().take(40),
                        note = noteText.trim().take(120),
                        category = chosenCat,
                        scope = chosenScope,
                        linkedTargetChoice = null,
                        onSaved = {
                          showCategoryDialog = false
                          resetForm()
                        },
                      )
                    }
                  }
                }
              },
              modifier = Modifier.weight(1f),
              testTag = "dl_cat_save",
            )
          }
        }
      }
    }
  }

  ambiguousLinkState?.let { ambig ->
    val chosenCat = selectedCategory.trim().ifBlank { if (entryType == "IN") "General Income" else "General Expense" }
    val chosenScope = if (entryType == "OUT") selectedScope else ""
    fun commitWithChoice(choice: String) {
      ambiguousLinkState = null
      ledgerViewModel.saveEntry(
        editingId = editingId,
        dateEpoch = dateEpoch,
        type = entryType,
        amount = pendingAmount,
        mode = modeText.trim().take(40),
        toMode = toModeText.trim().take(40),
        party = partyText.trim().take(80),
        refNo = refText.trim().take(40),
        note = noteText.trim().take(120),
        category = chosenCat,
        scope = chosenScope,
        linkedTargetChoice = choice,
        onSaved = {
          showCategoryDialog = false
          resetForm()
        },
      )
    }
    Dialog(onDismissRequest = { ambiguousLinkState = null }) {
      Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
      ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Link Target Match", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
          Text(
            "\"${ambig.customerName}\" exists as both a Payment Entity and a Direct Customer. Choose where to post this entry:",
            color = palette.textMuted,
            fontSize = 12.5.sp,
          )
          SarimPrimaryButton(
            text = "Post to Entity (${ambig.entity.name})",
            onClick = { commitWithChoice("ENTITY") },
            modifier = Modifier.fillMaxWidth(),
            testTag = "dl_ambig_entity",
          )
          SarimSecondaryButton(
            text = "Post to Customer (${ambig.customerName})",
            onClick = { commitWithChoice("CUSTOMER") },
            modifier = Modifier.fillMaxWidth(),
            testTag = "dl_ambig_customer",
          )
          SarimSecondaryButton(
            text = "Cashbook Only (No Link)",
            onClick = { commitWithChoice("NONE") },
            modifier = Modifier.fillMaxWidth(),
            testTag = "dl_ambig_none",
          )
        }
      }
    }
  }

  sheetEntry?.let { entry ->
    val tone = when (entry.type) {
      "IN" -> palette.green
      "OUT" -> palette.red
      else -> palette.cyan
    }
    val isSheetTabOwned = SarimLinkEngine.isTabOwnedLedgerRow(entry)
    SarimRecordBottomSheet(
      title = if (entry.type == "TRANSFER") "${entry.mode} → ${entry.toMode}" else entry.party.ifBlank { entry.category },
      subtitle = "${SarimFormat.formatDate(entry.dateEpoch)} · ${entry.type}",
      amountText = SarimFormat.formatRs(entry.amount, true, state.hideMoneyFigures),
      amountColor = tone,
      badgeText = entry.type,
      badgeColor = tone,
      metrics = buildList {
        add("Channel" to entry.mode)
        if (entry.type == "TRANSFER" && entry.toMode.isNotBlank()) add("To Channel" to entry.toMode)
        if (entry.category.isNotBlank()) add("Category" to entry.category)
        if (entry.party.isNotBlank()) add("Party" to entry.party)
        if (entry.scope.isNotBlank()) add("Scope" to entry.scope)
        if (entry.refNo.isNotBlank()) add("Ref #" to entry.refNo)
        if (entry.note.isNotBlank()) add("Note" to entry.note)
      },
      onDismiss = { sheetEntry = null },
      extraContent = if (isSheetTabOwned) {
        { SarimCallout(SarimLinkEngine.tabEntryNotice(entry), palette.cyan) }
      } else null,
      extraActionLabel = "Copy Voucher",
      extraActionIcon = Icons.Rounded.ContentCopy,
      onExtraAction = {
        val voucher = buildString {
          appendLine("=== CASHBOOK TRANSACTION STATEMENT VOUCHER ===")
          appendLine("Date: ${SarimFormat.dateInput(entry.dateEpoch)}")
          appendLine("Type: ${entry.type}")
          appendLine("Amount: ${SarimFormat.formatRs(entry.amount)}")
          appendLine("Channel: ${entry.mode}${if (entry.type == "TRANSFER" && entry.toMode.isNotBlank()) " -> ${entry.toMode}" else ""}")
          if (entry.category.isNotBlank()) appendLine("Category: ${entry.category}")
          if (entry.party.isNotBlank()) appendLine("Party: ${entry.party}")
          if (entry.refNo.isNotBlank()) appendLine("Ref #: ${entry.refNo}")
          if (entry.note.isNotBlank()) appendLine("Note: ${entry.note}")
          append("Business: GULL AND ZUBAIR")
        }
        clipboardManager.setText(AnnotatedString(voucher))
        viewModel.postStatus("Voucher copied")
      },
      onEdit = if (isSheetTabOwned) null else {
        {
          editingId = entry.id
          entryType = entry.type
          dateEpoch = entry.dateEpoch
          amountText = entry.amount.toString()
          modeText = entry.mode
          toModeText = entry.toMode
          partyText = entry.party
          refText = entry.refNo
          noteText = entry.note
          selectedCategory = entry.category
          selectedScope = entry.scope.ifBlank { "Business" }
        }
      },
      onDelete = if (isSheetTabOwned) null else {
        {
          val linkMap = JsonCodecs.decodeLedgerLink(entry.linkJson).orEmpty()
          val linkedTxId = linkMap["txId"]?.toString()?.takeIf { it.isNotBlank() }
          val linkedSaleId = linkMap["saleId"]?.toString()?.takeIf { it.isNotBlank() }
          val linkedPayTx = linkedTxId?.let { id -> state.paymentTransactions.firstOrNull { !it.isDeleted && it.id == id } }
          val linkedSale = linkedSaleId?.let { id -> state.sales.firstOrNull { !it.isDeleted && it.id == id } }
          val blockMsg = when {
            linkedPayTx != null -> SarimLinkEngine.getPaymentTxDeleteBlockReason(
              tx = linkedPayTx,
              allTxs = state.paymentTransactions,
              sales = state.sales,
              settlements = state.settlements,
              factoryBatches = state.factoryBatches,
            )
            linkedSale != null -> SarimLinkEngine.getSaleBlockReason(linkedSale, state.settlements)
              ?: SarimLinkEngine.getCollectionDeleteBlockReason(linkedSale, state.rawMaterials)
            else -> null
          }
          if (blockMsg != null) {
            viewModel.showDialog(AppDialogRequest("Cannot Delete Entry", blockMsg, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
          } else {
            val cascadeNote = when {
              linkedPayTx != null -> "\n\n↩ The linked payment/expense on Payments tab will also be removed."
              linkedSale != null -> "\n\n↩ The linked customer record on Sales tab will also be reversed."
              else -> ""
            }
            viewModel.showDialog(
              AppDialogRequest(
                title = "Delete Ledger Entry",
                body = "Delete this ${entry.type} record of ${SarimFormat.formatRs(entry.amount, privacyHidden = state.hideMoneyFigures)}?$cascadeNote\n\nMoves to Recycle Bin.",
                tone = DialogTone.PERMANENT,
                confirmLabel = "Delete",
                onConfirm = {
                  ledgerViewModel.deleteEntry(entry.id)
                },
              )
            )
          }
        }
      },
    )
  }
}
