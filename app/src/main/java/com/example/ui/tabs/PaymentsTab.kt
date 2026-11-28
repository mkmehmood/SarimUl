package com.example.ui.tabs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.backup.YearCloseMerge
import com.example.data.local.PaymentTransactionEntity
import com.example.data.model.StandaloneScreen
import com.example.data.model.TimePeriod
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.viewmodel.ShareAfterSave
import com.example.util.StatementKind
import com.example.util.DialogHighlight
import com.example.util.DialogTone
import com.example.util.PdfExporter
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import java.util.Calendar

/** Web `#btn-category-operating / -in / -out` toggle group in the Transaction Manager. */
private enum class PayTxType(val label: String, val title: String, val sub: String, val code: String) {
  EXPENSE("Operating Expense", "Operating Expense", "", "EXPENSE"),
  PAYMENT_IN("Payment IN (Cash In +)", "Payment IN", "Cash In +", "PAYMENT_IN"),
  PAYMENT_OUT("Payment OUT (Cash Out −)", "Payment OUT", "Cash Out −", "PAYMENT_OUT"),
}

/** Web `selectExpense(name, type)`: picking from the dropdown locks the type buttons that don't fit. */
private enum class PickedKind { ENTITY, EXPENSE }

private enum class UnifiedViewMode(val label: String) {
  ENTITIES("Entities & Balances"),
  EXPENSES("Expenses"),
}

/** Web `#unifiedPeriodFilter`: today, last 7 days, last 30 days, or everything. */
private enum class UnifiedPeriod(val label: String, val daysBack: Int?) {
  TODAY("Today", 0),
  WEEK("This Week", 7),
  MONTH("This Month", 30),
  ALL("All Time", null),
}

private fun unifiedPeriodStart(period: UnifiedPeriod, now: Long = System.currentTimeMillis()): Long {
  val days = period.daysBack ?: return 0L
  val cal = Calendar.getInstance().apply { timeInMillis = SarimFormat.startOfDay(now) }
  cal.add(Calendar.DAY_OF_YEAR, -days)
  return cal.timeInMillis
}

/**
 * Web `calculateCashTracker` window: anchored on the Transaction Manager date (`#expenseDate`).
 * Daily = that day, Weekly = 7 days ending that day, Monthly / Yearly = its calendar month / year.
 */
private fun cashTrackerWindow(period: TimePeriod, anchor: Long): Pair<Long, Long> {
  val dayStart = SarimFormat.startOfDay(anchor)
  val dayEnd = SarimFormat.endOfDay(anchor)
  val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
  return when (period) {
    TimePeriod.DAILY -> dayStart to dayEnd
    TimePeriod.WEEKLY -> {
      cal.add(Calendar.DAY_OF_YEAR, -6)
      cal.timeInMillis to dayEnd
    }
    TimePeriod.MONTHLY -> {
      cal.set(Calendar.DAY_OF_MONTH, 1)
      val start = cal.timeInMillis
      cal.add(Calendar.MONTH, 1)
      cal.add(Calendar.MILLISECOND, -1)
      start to cal.timeInMillis
    }
    TimePeriod.YEARLY -> {
      cal.set(Calendar.DAY_OF_YEAR, 1)
      val start = cal.timeInMillis
      cal.add(Calendar.YEAR, 1)
      cal.add(Calendar.MILLISECOND, -1)
      start to cal.timeInMillis
    }
    TimePeriod.ALL_TIME -> 0L to Long.MAX_VALUE
  }
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
  // Web clears `#expenseAddToLedger` to unchecked on every fresh form.
  var addToLedger by remember { mutableStateOf(false) }
  var pickedKind by remember { mutableStateOf<PickedKind?>(null) }
  var pickedName by remember { mutableStateOf("") }

  // Web tracker opens on "Daily" for the selected date.
  var trackerPeriod by remember { mutableStateOf(TimePeriod.DAILY) }
  var unifiedSearch by remember { mutableStateOf("") }
  var unifiedMode by remember { mutableStateOf(UnifiedViewMode.ENTITIES) }
  var unifiedPeriod by remember { mutableStateOf(UnifiedPeriod.MONTH) }
  var editingTxId by remember { mutableStateOf<String?>(null) }
  var sheetPayment by remember { mutableStateOf<PaymentTransactionEntity?>(null) }
  var prevDayRef by remember { mutableLongStateOf(state.currentLocalDayEpoch) }

  LaunchedEffect(state.currentLocalDayEpoch) {
    val newDay = state.currentLocalDayEpoch
    if (newDay != prevDayRef) {
      dateEpoch = SarimLinkEngine.rolloverDateIfUnedited(dateEpoch, prevDayRef, newDay, editingTxId != null)
      prevDayRef = newDay
    }
  }

  fun resetForm() {
    editingTxId = null
    nameText = ""
    amountText = ""
    descriptionText = ""
    photoUris = emptyList()
    addToLedger = false
    pickedKind = null
    pickedName = ""
    txType = PayTxType.PAYMENT_OUT
    dateEpoch = System.currentTimeMillis()
  }

  val editingOriginal = editingTxId?.let { id -> state.paymentTransactions.firstOrNull { it.id == id } }

  // Web `handleExpenseSearch`: one dropdown with an EXPENSES section and an ENTITIES section.
  val expenseNames = remember(state.operatingCategories, state.paymentEntities, state.paymentTransactions) {
    (state.operatingCategories +
      state.paymentTransactions.filter { !it.isDeleted && it.type == "EXPENSE" }.map { it.entityOrExpenseName } +
      state.paymentEntities.filter { !it.isDeleted && it.isExpenseEntity }.map { it.name })
      .map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
  }
  val entityNames = remember(state.paymentEntities) {
    state.paymentEntities.filter { !it.isDeleted && !it.isExpenseEntity }.map { it.name.trim() }
      .filter { it.isNotBlank() }.distinctBy { it.lowercase() }
  }
  val suggestions = remember(expenseNames, entityNames) { (expenseNames + entityNames).distinctBy { it.lowercase() } }

  fun typeLocked(t: PayTxType): Boolean = when {
    editingOriginal != null -> editingOriginal.type != t.code
    pickedKind == PickedKind.ENTITY -> t == PayTxType.EXPENSE
    pickedKind == PickedKind.EXPENSE -> t != PayTxType.EXPENSE
    else -> false
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
            if (editingTxId != null) {
              val hasEdits = editingOriginal == null ||
                nameText.trim() != editingOriginal.entityOrExpenseName ||
                (SarimFormat.parseSafeDouble(amountText) ?: 0.0) != editingOriginal.amount ||
                descriptionText.trim() != editingOriginal.description
              viewModel.requestCancelEdit(hasEdits) { resetForm() }
            } else {
              resetForm()
            }
          }) { Text(if (editingTxId == null) "Clear" else "Cancel Edit", color = if (editingTxId == null) palette.cyan else palette.amber) }
        }
        if (editingTxId != null) {
          SarimCallout("Editing entry · Saving updates the original record.", palette.amber)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          SarimDatePickerField(
            "Date",
            dateEpoch,
            onDateSelected = { dateEpoch = it },
            modifier = Modifier.weight(1f),
            testTag = "expense_date",
          )
          SarimSearchFieldWithDropdown(
            value = nameText,
            onValueChange = { v ->
              nameText = v
              if (pickedKind != null && !v.trim().equals(pickedName, ignoreCase = true)) {
                pickedKind = null
                pickedName = ""
              }
            },
            label = "Name/Entity",
            placeholder = "Search expenses or entities...",
            suggestions = suggestions,
            onSuggestionSelected = { picked ->
              nameText = picked
              val isEntity = entityNames.any { it.equals(picked, ignoreCase = true) }
              val isExpense = expenseNames.any { it.equals(picked, ignoreCase = true) }
              val kind = when {
                isEntity && isExpense -> if (txType == PayTxType.EXPENSE) PickedKind.EXPENSE else PickedKind.ENTITY
                isEntity -> PickedKind.ENTITY
                isExpense -> PickedKind.EXPENSE
                else -> null
              }
              pickedKind = kind
              pickedName = picked.trim()
              if (editingOriginal == null) {
                if (kind == PickedKind.ENTITY && txType == PayTxType.EXPENSE) txType = PayTxType.PAYMENT_OUT
                if (kind == PickedKind.EXPENSE && txType != PayTxType.EXPENSE) txType = PayTxType.EXPENSE
              }
            },
            modifier = Modifier.weight(1.4f),
            testTag = "expense_name",
          )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          SarimTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = "Amount",
            placeholder = "0",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
            testTag = "expense_amount",
          )
          SarimTextField(
            value = descriptionText,
            onValueChange = { descriptionText = it },
            label = "Description (Optional)",
            placeholder = "Add details...",
            modifier = Modifier.weight(1.4f),
            testTag = "expense_description",
          )
        }
        PhotoAttachmentBar(
          photoUris = photoUris,
          onAddUris = { photoUris = photoUris + it },
          onClearAll = { photoUris = emptyList() },
          onViewPhotos = { idx -> viewModel.openPhotoViewer(photoUris, idx) },
          onError = { msg -> viewModel.showDialog(AppDialogRequest("Photo Error", msg, tone = DialogTone.ERROR, cancelLabel = null)) },
        )

        SarimFieldLabel(if (editingTxId != null) "Transaction Type (Locked while editing)" else "Transaction Type")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          val scheme = MaterialTheme.colorScheme
          PayTxType.entries.forEach { t ->
            val sel = t == txType
            val lockedOut = typeLocked(t)
            val c = when (t) {
              PayTxType.EXPENSE -> palette.red
              PayTxType.PAYMENT_IN -> palette.green
              PayTxType.PAYMENT_OUT -> palette.amber
            }
            val typeSource = remember { MutableInteractionSource() }
            val (scale, corner) = rememberExpressivePress(typeSource, restCorner = 18.dp, pressedCorner = 12.dp, pressedScale = 0.97f)
            val shape = RoundedCornerShape(corner)
            Surface(
              shape = shape,
              color = if (sel) lerp(scheme.surfaceContainerHigh, c, 0.22f) else scheme.surfaceContainerHigh,
              modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 56.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .alpha(if (lockedOut) 0.4f else 1f)
                .clip(shape)
                .clickable(interactionSource = typeSource, indication = LocalIndication.current, enabled = !lockedOut) { txType = t }
                .testTag("expense_type_${t.code.lowercase()}"),
            ) {
              Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
              ) {
                Text(
                  t.title,
                  color = if (sel) c else palette.textMuted,
                  fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                  fontSize = 12.5.sp,
                  textAlign = TextAlign.Center,
                  lineHeight = 15.sp,
                )
                if (t.sub.isNotBlank()) {
                  Text("(${t.sub})", color = if (sel) c.copy(alpha = 0.85f) else palette.textMuted, fontSize = 10.5.sp, textAlign = TextAlign.Center)
                }
              }
            }
          }
        }

        // Web hides `#expense-ledger-wrap` for operating expenses.
        if (txType != PayTxType.EXPENSE) {
          SarimCheckboxRow(checked = addToLedger, onCheckedChange = { addToLedger = it }, label = "Add to Daily Ledger")
        }

        SarimPrimaryButton(
          text = if (editingTxId == null) "Save Transaction" else "Update Transaction",
          onClick = {
            if (SarimFormat.isFutureDate(dateEpoch)) {
              viewModel.showDialog(AppDialogRequest("Invalid Date", "Future dates are not allowed.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            val amt = SarimFormat.parseSafeDouble(amountText)
            if (nameText.isBlank()) {
              viewModel.showDialog(AppDialogRequest("Invalid Entry", "Please enter name/entity", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (amt == null || amt <= 0.0) {
              viewModel.showDialog(AppDialogRequest("Invalid Entry", "Please enter valid amount", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            val trimmedName = nameText.trim()
            val knownEntity = entityNames.any { it.equals(trimmedName, ignoreCase = true) }
            if (txType != PayTxType.EXPENSE && !knownEntity && state.operatingCategories.any { it.equals(trimmedName, ignoreCase = true) }) {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Reserved Category Name",
                  body = "\"$trimmedName\" is an operating expense category, not an entity. Switch to Operating Expense mode or use a different name.",
                  tone = DialogTone.WARNING,
                  confirmLabel = "OK",
                  cancelLabel = null,
                )
              )
              return@SarimPrimaryButton
            }
            val currentEditId = editingTxId
            if (currentEditId != null && editingOriginal != null && editingOriginal.type != txType.code) {
              viewModel.showDialog(AppDialogRequest("Type Locked", "The type cannot be changed while editing. Delete and re-enter it instead.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (txType == PayTxType.EXPENSE || txType == PayTxType.PAYMENT_OUT) {
              val cashNow = SarimLinkEngine.computeCashInHandBreakdown(
                sales = state.sales,
                settlements = state.settlements,
                payments = state.paymentTransactions,
                factoryBatches = state.factoryBatches,
              ).netCash + if (editingOriginal != null && editingOriginal.type != "PAYMENT_IN") editingOriginal.amount else 0.0
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
            }
            val saveToLedger = txType != PayTxType.EXPENSE && addToLedger
            viewModel.confirmSave(
              if (txType == PayTxType.EXPENSE) "Expense" else "Transaction",
              listOf("Name" to nameText, "Amount" to SarimFormat.formatRs(amt, false, hideMoney), "Date" to SarimFormat.formatDate(dateEpoch), "Note" to descriptionText),
              isUpdate = currentEditId != null,
              shareAfterSave = if (txType == PayTxType.EXPENSE) null else ShareAfterSave(StatementKind.ENTITY, nameText, dateEpoch),
            ) {
              val savedType = txType
              viewModel.launchSafely(
                errorTitle = "Save Payment Failed",
                successMessage = when {
                  currentEditId != null -> "Transaction updated"
                  savedType == PayTxType.EXPENSE -> "Operating expense recorded: $trimmedName"
                  savedType == PayTxType.PAYMENT_IN -> "Payment IN recorded: $trimmedName"
                  else -> "Payment OUT recorded: $trimmedName"
                },
                onSuccess = { resetForm() },
              ) {
                if (savedType == PayTxType.EXPENSE) {
                  viewModel.repository.saveCategoryIfNew("categories", nameText)
                }
                viewModel.repository.savePaymentTransaction(
                  editingId = currentEditId,
                  dateEpoch = dateEpoch,
                  type = savedType.code,
                  name = nameText,
                  amount = amt,
                  description = descriptionText,
                  photoUri = photoUris.firstOrNull() ?: "",
                  addToLedger = saveToLedger,
                  actor = state.signedInDisplayName.ifBlank { "Admin" },
                )
              }
            }
          },
        )
      }
    }

    if (state.isAdmin && !hideMoney && snap != null) {
      // 2. ECONOMIC HEALTH DASHBOARD (web `calculateNetCash` / `updateEconomicDashboard`)
      item {
        val cash = SarimLinkEngine.computeCashInHandBreakdown(
          sales = state.sales,
          settlements = state.settlements,
          payments = state.paymentTransactions,
          factoryBatches = state.factoryBatches,
        )
        val directSalesCash = cash.salesCash + cash.collectionsCash - cash.moneyOutCash
        val operatingExpenses = cash.expensesCash + cash.factoryAdditionalCost
        val operatingCashFlow = directSalesCash + cash.calcCash
        val cashInHand = cash.netCash
        val totalReceivables = snap.directReceivables + snap.repReceivables
        val currentAssets = cashInHand + snap.rawInventoryValue + snap.formulaUnitsInventoryValue + totalReceivables
        val currentLiabilities = snap.supplierPayables + snap.entityPayables
        val netWorkingCapital = currentAssets - currentLiabilities
        val currentRatio = if (currentLiabilities > 0.01) String.format("%.2f", currentAssets / currentLiabilities) else "—"
        val quickRatio = if (currentLiabilities > 0.01) String.format("%.2f", (cashInHand + totalReceivables) / currentLiabilities) else "—"
        val cashRatio = if (currentLiabilities > 0.01) String.format("%.2f", cashInHand / currentLiabilities) else "—"
        val cashTone = when {
          cashInHand < 0 -> palette.red
          cashInHand < 10_000 -> palette.amber
          else -> palette.green
        }
        val wcTone = when {
          netWorkingCapital < 0 -> palette.red
          netWorkingCapital < 50_000 -> palette.amber
          else -> palette.green
        }

        SarimCard(tonalAccent = palette.green) {
          Text("ECONOMIC HEALTH DASHBOARD", color = palette.green, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
          Text(SarimFormat.formatRs(cashInHand), color = cashTone, style = TabularNumberStyle.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold))
          Surface(shape = RoundedCornerShape(14.dp), color = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, palette.green, 0.10f)) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
              HealthLine("Operating Cash Flow:", SarimFormat.formatRs(operatingCashFlow), palette.green, bold = true)
            }
          }

          HealthSection("CASH IN HAND DETAILS", palette.cyan)
          HealthLine("Net Sales Cash (Direct Customers):", SarimFormat.formatRs(directSalesCash), palette.textPrimary)
          HealthLine("Representative Collections:", SarimFormat.formatRs(cash.calcCash), palette.textPrimary)
          HealthLine("External Payments Received:", SarimFormat.formatRs(cash.paymentInCash), palette.textPrimary)
          HealthLine("External Payments Made:", SarimFormat.formatRs(cash.paymentOutCash), palette.red)
          HealthLine("Operating Expenses:", SarimFormat.formatRs(operatingExpenses), palette.red)
          HealthLine("NET CASH IN HAND:", SarimFormat.formatRs(cashInHand), palette.green, bold = true)

          HealthSection("CURRENT ASSETS", palette.green)
          HealthLine("Cash in Hand (Above):", SarimFormat.formatRs(cashInHand), palette.textPrimary)
          HealthLine("Raw Materials Inventory:", SarimFormat.formatRs(snap.rawInventoryValue), palette.cyan)
          HealthLine("Formula Units Inventory:", SarimFormat.formatRs(snap.formulaUnitsInventoryValue), palette.purple)

          HealthSection("ACCOUNTS RECEIVABLE", palette.amber)
          HealthLine("Direct Customer Credits:", SarimFormat.formatRs(snap.directReceivables), palette.textPrimary)
          HealthLine("Representative Credits:", SarimFormat.formatRs(snap.repReceivables), palette.textPrimary)
          HealthLine("TOTAL RECEIVABLES:", SarimFormat.formatRs(totalReceivables), palette.amber, bold = true)
          HealthLine("TOTAL CURRENT ASSETS:", SarimFormat.formatRs(currentAssets), palette.green, bold = true)

          HealthSection("CURRENT LIABILITIES", palette.red)
          HealthLine("ACCOUNTS PAYABLE:", "", palette.textPrimary, bold = true)
          HealthLine("   Supplier Payables (Raw Material Invoices):", SarimFormat.formatRs(snap.supplierPayables), palette.red)
          HealthLine("   Entity Payables (Payment Entities):", SarimFormat.formatRs(snap.entityPayables), palette.red)
          HealthLine("TOTAL ACCOUNTS PAYABLE:", SarimFormat.formatRs(currentLiabilities), palette.red, bold = true)
          HealthLine("TOTAL CURRENT LIABILITIES:", SarimFormat.formatRs(currentLiabilities), palette.red, bold = true)

          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
          ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HealthLine("Net Working Capital:", SarimFormat.formatRs(netWorkingCapital), wcTone, bold = true)
                HealthLine("Total Enterprise Value:", SarimFormat.formatRs(netWorkingCapital), palette.textPrimary, bold = true)
              }
            }
          }
          HealthSection("FINANCIAL RATIOS", palette.purple)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Current Ratio", currentRatio, Modifier.weight(1f), palette.cyan)
            SarimStatTile("Quick Ratio", quickRatio, Modifier.weight(1f), palette.purple)
            SarimStatTile("Cash Ratio", cashRatio, Modifier.weight(1f), palette.green)
          }
        }
      }

      // 3. CASH IN HAND TRACKER (web `calculateCashTracker`, anchored on the form date)
      item {
        val (from, to) = cashTrackerWindow(trackerPeriod, dateEpoch)
        val breakdown = SarimLinkEngine.computeCashInHandBreakdown(
          sales = state.sales,
          settlements = state.settlements,
          payments = state.paymentTransactions,
          factoryBatches = state.factoryBatches,
          cutoffEpoch = from,
          endEpoch = to,
        )
        val credits = SarimLinkEngine.computeOutstandingCredits(
          sales = state.sales,
          settlements = state.settlements,
          cutoffEpoch = from,
          endEpoch = to,
        )
        val salesTabCash = breakdown.salesCash + breakdown.collectionsCash - breakdown.moneyOutCash
        val expenses = breakdown.expensesCash + breakdown.factoryAdditionalCost

        SarimCard(tonalAccent = palette.cyan) {
          Text("CASH IN HAND TRACKER", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.8.sp)
          SarimToggleGroup(
            TimePeriod.entries,
            trackerPeriod,
            { if (it == TimePeriod.ALL_TIME) "All Times" else it.label },
            { trackerPeriod = it },
            testTagPrefix = "cash_tracker",
          )

          Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("Net Cash Flow", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              HealthLine("Sales Tab Cash:", SarimFormat.formatRs(salesTabCash), palette.green)
              HealthLine("Calculator Cash:", SarimFormat.formatRs(breakdown.calcCash), palette.green)
              HealthLine("Payments In:", SarimFormat.formatRs(breakdown.paymentInCash), palette.green)
              HealthLine("Payments Out:", SarimFormat.formatRs(breakdown.paymentOutCash), palette.red)
              HealthLine("Expenses:", SarimFormat.formatRs(expenses), palette.red)
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
              val netTone = if (breakdown.netCash >= 0) palette.green else palette.red
              HealthLine("NET CASH:", SarimFormat.formatRs(breakdown.netCash), netTone, bold = true)
            }
          }

          Surface(shape = RoundedCornerShape(18.dp), color = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, palette.amber, 0.12f)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("TOTAL OUTSTANDING CREDITS", color = palette.amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              HealthLine("Sales Tab Credits:", SarimFormat.formatRs(credits.salesTabCredits), palette.textPrimary)
              HealthLine("Calculator Credits:", SarimFormat.formatRs(credits.calculatorCredits), palette.textPrimary)
              HorizontalDivider(color = palette.amber.copy(alpha = 0.35f))
              HealthLine("TOTAL CREDITS:", SarimFormat.formatRs(credits.total), palette.red, bold = true)
              Text("Credits awaiting collection", color = palette.textMuted, fontSize = 11.sp)
            }
          }
        }
      }

      // 4. Unified Records (web `renderUnifiedTable`)
      item {
        val q = unifiedSearch.trim().lowercase()
        val periodStart = unifiedPeriodStart(unifiedPeriod)
        val liveEntities = state.paymentEntities.filter { !it.isDeleted && !it.isExpenseEntity }
        val totalReceivables = liveEntities.filter { it.currentBalance < -0.01 }.sumOf { -it.currentBalance }
        val totalPayables = snap.supplierPayables + snap.entityPayables
        val periodExpenses = state.paymentTransactions.filter { !it.isDeleted && it.type == "EXPENSE" && it.dateEpoch >= periodStart }
        val totalExpenses = periodExpenses.sumOf { it.amount }
        val entityRows = liveEntities
          .filter { q.isBlank() || it.name.lowercase().contains(q) || it.phone.contains(q) || it.wallet.lowercase().contains(q) }
          .sortedByDescending { kotlin.math.abs(it.currentBalance) }
        val expenseGroups = periodExpenses
          .filter { q.isBlank() || it.entityOrExpenseName.lowercase().contains(q) }
          .groupBy { it.entityOrExpenseName }
          .map { (name, list) -> Triple(name, list, list.maxOf { it.dateEpoch }) }
          .sortedByDescending { it.third }

        SarimCard {
          Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
              Text("UNIFIED RECORDS", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.8.sp)
              Text(
                if (unifiedMode == UnifiedViewMode.ENTITIES) "${entityRows.size} entities" else "${expenseGroups.size} expenses",
                color = palette.textMuted,
                fontSize = 11.5.sp,
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
              onClick = {
                val spec = if (unifiedMode == UnifiedViewMode.ENTITIES) {
                  val exportEntities = state.paymentEntities.filter {
                    q.isBlank() || it.name.lowercase().contains(q) || it.phone.contains(q) || it.wallet.lowercase().contains(q)
                  }
                  PdfExporter.unifiedEntitiesSpec(
                    periodLabel = unifiedPeriod.label,
                    entities = exportEntities,
                    materials = state.rawMaterials,
                    transactions = state.paymentTransactions,
                    privacyHidden = hideMoney,
                  )
                } else {
                  val exportExpenses = periodExpenses.filter { q.isBlank() || it.entityOrExpenseName.lowercase().contains(q) }
                  PdfExporter.unifiedExpensesSpec(
                    periodLabel = unifiedPeriod.label,
                    expenses = exportExpenses,
                    privacyHidden = hideMoney,
                  )
                }
                com.example.ui.components.shareReportChoices(viewModel, context, spec)
              },
              modifier = Modifier.size(48.dp).testTag("unified_records_pdf_btn"),
            ) { Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Download PDF", tint = palette.cyan) }
            IconButton(onClick = { viewModel.openStandalone(StandaloneScreen.AddEntity(null)) }, modifier = Modifier.testTag("unified_add_entity_btn")) {
              Icon(Icons.Rounded.Add, contentDescription = "Add entity", tint = palette.green)
            }
            }
          }
          SarimTextField(
            value = unifiedSearch,
            onValueChange = { unifiedSearch = it },
            label = "",
            placeholder = if (unifiedMode == UnifiedViewMode.ENTITIES) "Search entities..." else "Search expenses...",
            leadingIcon = Icons.Rounded.Search,
            testTag = "unified_search",
          )
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
              options = UnifiedPeriod.entries.map { it.label },
              onSelect = { idx, _ -> unifiedPeriod = UnifiedPeriod.entries[idx] },
              modifier = Modifier.weight(1f),
            )
          }

          fun phoneDetail(phone: String) = RecordDetail(
            text = phone,
            color = palette.green,
            underline = true,
            onClick = {
              runCatching {
                com.example.util.AppLockGuard.suppressFor()
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }.onFailure { viewModel.postStatus("No dialer app available") }
            },
            onLongClick = {
              if (!com.example.util.DirectShare.openWhatsAppChat(context, phone)) viewModel.postStatus("WhatsApp or browser unavailable")
            },
          )
          // Same table as Sales > CUSTOMERS: one bordered list, avatar + ledger-page badge, phone dial / WhatsApp, pinned totals.
          if (unifiedMode == UnifiedViewMode.ENTITIES) {
            val net = totalReceivables - totalPayables
            SarimRecordTable(
              rows = entityRows.map { ent ->
                val bal = ent.currentBalance
                RecordRow(
                  key = ent.id,
                  name = ent.name.uppercase(),
                  avatarBadge = ent.ledgerPage,
                  details = when {
                    ent.phone.isNotBlank() -> listOf(phoneDetail(ent.phone))
                    ent.wallet.isNotBlank() -> listOf(RecordDetail(ent.wallet))
                    else -> listOf(RecordDetail("No contact"))
                  },
                  amount = SarimFormat.formatRs(kotlin.math.abs(bal), true, hideMoney),
                  amountColor = if (bal > 0.01) palette.red else palette.emerald,
                  tag = when {
                    bal > 0.01 -> "PAYABLE"
                    bal < -0.01 -> "RECEIVABLE"
                    else -> "SETTLED"
                  },
                  onClick = { viewModel.openStandalone(StandaloneScreen.EntityDetails(ent.id)) },
                )
              },
              stats = listOf(
                RecordStat("Net Balance", SarimFormat.formatRs(kotlin.math.abs(net), true, hideMoney), if (net >= 0) palette.emerald else palette.red),
              ),
              emptyTitle = if (q.isBlank()) "No entities yet" else "No entities match your search",
              emptyHint = if (q.isBlank()) "Add an entity or record a payment to create one." else "Try a different name.",
            )
          } else {
            val netTotal = -expenseGroups.sumOf { g -> g.second.sumOf { it.amount } }
            SarimRecordTable(
              rows = expenseGroups.map { (expName, list, _) ->
                RecordRow(
                  key = expName,
                  name = expName.uppercase(),
                  showAvatar = false,
                  details = listOf(RecordDetail(if (list.size > 1) "Operating · ${list.size} transactions" else "Operating")),
                  amount = SarimFormat.formatRs(list.sumOf { it.amount }, true, hideMoney),
                  amountColor = palette.amber,
                  onClick = { viewModel.openStandalone(StandaloneScreen.ExpenseDetails(expName)) },
                )
              },
              stats = listOf(
                RecordStat("Net Total", SarimFormat.formatRs(netTotal, true, hideMoney), if (netTotal >= 0) palette.emerald else palette.red),
              ),
              emptyTitle = if (q.isBlank()) "No expenses in this period" else "No expenses match your search",
              emptyHint = if (q.isBlank()) "Change the period to see more." else "Try a different name.",
            )
          }

          // Web `#unified-summary` grid
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Total Receivable", SarimFormat.formatRs(totalReceivables, true, hideMoney), Modifier.weight(1f), palette.green)
            SarimStatTile("Expenses", SarimFormat.formatRs(totalExpenses, true, hideMoney), Modifier.weight(1f), palette.amber)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Supplies Payable", SarimFormat.formatRs(snap.supplierPayables, true, hideMoney), Modifier.weight(1f), palette.purple)
            SarimStatTile("Entity Payable", SarimFormat.formatRs(snap.entityPayables, true, hideMoney), Modifier.weight(1f), palette.red)
          }
        }
      }

      // 5. Payment History (web `refreshPaymentTab` cards, filtered by the Name/Entity box)
      item {
        Text("Payment History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        val typed = nameText.trim().lowercase()
        val exactPick = pickedKind != null
        val entityById = state.paymentEntities.associateBy { it.id }
        val historyTxs = state.paymentTransactions.filter { p ->
          if (p.isDeleted) return@filter false
          if (typed.isEmpty()) return@filter true
          val n = p.entityOrExpenseName.trim().lowercase()
          if (exactPick) n == typed else n.contains(typed)
        }
        val records = historyTxs.map { p ->
          val isIn = p.type == "PAYMENT_IN"
          val ent = p.entityId?.let { entityById[it] }
          val entityType = when {
            p.type == "EXPENSE" -> "Expense"
            ent != null && ent.type.isNotBlank() -> ent.type.replaceFirstChar { it.uppercase() }
            p.entityType.isNotBlank() -> p.entityType.replaceFirstChar { it.uppercase() }
            else -> ""
          }
          HistoryRecordDisplay(
            item = p,
            dateEpoch = p.dateEpoch,
            title = ent?.name ?: p.entityOrExpenseName,
            subtitle = p.description.ifBlank { "No description" },
            amountText = SarimFormat.formatRs(p.amount, false, hideMoney),
            amountColor = if (isIn) palette.green else palette.red,
            chipText = if (isIn) "In" else "Out",
            chipColor = if (isIn) palette.green else palette.red,
            detailMetrics = buildList {
              if (entityType.isNotBlank()) add("Type" to entityType)
              add("Date" to SarimFormat.dateInput(p.dateEpoch) + if (p.timeStr.isNotBlank()) " ${p.timeStr}" else "")
              add("Amount" to SarimFormat.formatRs(p.amount, true, hideMoney))
              add("Description" to p.description.ifBlank { "No description" })
              add("Daily Ledger" to if (p.addToLedger) "Linked" else "Not linked")
            },
          )
        }
        AccordionHistoryList(
          records = records,
          yearSummary = { "" },
          monthSummary = { items ->
            val inTot = items.filter { it.item.type == "PAYMENT_IN" }.sumOf { it.item.amount }
            val outTot = items.filter { it.item.type != "PAYMENT_IN" }.sumOf { it.item.amount }
            "In ${SarimFormat.formatRs(inTot, false, hideMoney)} · Out ${SarimFormat.formatRs(outTot, false, hideMoney)}"
          },
          daySummary = { items ->
            val inTot = items.filter { it.item.type == "PAYMENT_IN" }.sumOf { it.item.amount }
            val outTot = items.filter { it.item.type != "PAYMENT_IN" }.sumOf { it.item.amount }
            "In ${SarimFormat.formatRs(inTot, false, hideMoney)} · Out ${SarimFormat.formatRs(outTot, false, hideMoney)}"
          },
          emptyTitle = if (typed.isEmpty()) "No payment transactions found." else "No matching payments",
          emptySubtitle = if (typed.isEmpty()) "Saved payments and expenses will list here." else "No matching payments. Clear the name to see all.",
          onItemClick = { rec -> sheetPayment = rec.item },
        )
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
        val isTransfer = state.paymentTransfers.any { !it.isDeleted && (it.outDocId == p.id || it.inDocId == p.id) }
        val hasConsumedCredit = p.type == "PAYMENT_IN" && state.paymentTransactions.any { other ->
          !other.isDeleted && other.type == "PAYMENT_OUT" &&
            com.example.data.local.JsonCodecs.decodeConsumedCredits(other.consumedCreditsJson).any { it.id == p.id }
        }
        val blockMsg = when {
          YearCloseMerge.isMergedId(p.id) -> "Merged opening balance records cannot be edited."
          isTransfer -> "Transfers are edited from the Payment Transfer screen."
          hasConsumedCredit -> "This Payment IN has supplier credit already consumed by a Payment OUT. Delete that Payment OUT first."
          p.isPayable || !p.materialId.isNullOrBlank() || p.supplierCreditAmount > 0.001 ->
            "This payment settled a supplier payable or credit. Delete and re-enter it to change it."
          else -> null
        }
        if (blockMsg != null) {
          viewModel.showDialog(AppDialogRequest("Action Not Allowed", blockMsg, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
        } else {
          editingTxId = p.id
          pickedKind = null
          pickedName = ""
          dateEpoch = p.dateEpoch
          txType = PayTxType.entries.firstOrNull { it.code == p.type } ?: PayTxType.PAYMENT_OUT
          nameText = p.entityOrExpenseName
          amountText = p.amount.toString()
          descriptionText = p.description
          photoUris = if (p.photoUri.isNotBlank()) listOf(p.photoUri) else emptyList()
          addToLedger = p.addToLedger
        }
      },
      onDelete = {
        val blockMsg = SarimLinkEngine.getPaymentTxDeleteBlockReason(
          tx = p,
          allTxs = state.paymentTransactions,
          sales = state.sales,
          settlements = state.settlements,
          factoryBatches = state.factoryBatches,
        )
        if (blockMsg != null) {
          viewModel.showDialog(AppDialogRequest("Action Not Allowed", blockMsg, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
        } else {
          val impactNotes = buildString {
            if (p.isPayable && p.type == "PAYMENT_OUT") {
              append("\n\n↩ Supplier payable status will be restored — material purchase reverts to pending payment.")
            }
            if (p.isPayable && p.type == "PAYMENT_IN") {
              val matName = p.materialId?.let { id -> state.rawMaterials.firstOrNull { it.id == id }?.name } ?: "the material"
              append("\n\n↩ Credit purchase record removed — supplier will be unlinked from $matName.")
            }
            if (p.addToLedger) {
              append("\n↩ Linked Daily Ledger entry will also be removed.")
            }
          }
          viewModel.showDialog(
            AppDialogRequest(
              title = deleteTitle,
              body = "Delete this $deleteTypeDesc?\nEntity: ${p.entityOrExpenseName}\nDate: ${SarimFormat.dateInput(p.dateEpoch)}$impactNotes\n\nThis cannot be undone.",
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
        }
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

@Composable
private fun HealthSection(title: String, color: androidx.compose.ui.graphics.Color) {
  Spacer(Modifier.height(4.dp))
  Text(title, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
  HorizontalDivider(color = color.copy(alpha = 0.25f))
}
