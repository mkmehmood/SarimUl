package com.example.ui.tabs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.backup.YearCloseMerge
import com.example.data.local.SaleTransactionEntity
import com.example.data.model.StandaloneScreen
import com.example.data.model.TimePeriod
import com.example.data.repository.SarimLinkEngine
import com.example.data.repository.SarimStockCheck
import com.example.data.repository.StockTone
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.theme.avatarColorForName
import com.example.ui.theme.storeColorForKey
import com.example.util.AppDialogRequest
import com.example.viewmodel.ShareAfterSave
import com.example.util.StatementKind
import com.example.util.StatementData
import com.example.util.DialogHighlight
import com.example.util.DialogTone
import com.example.util.PdfExporter
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

private enum class SalesMode(val label: String) {
  SALE("Sale"),
  COLLECTION("Collection"),
  REP_RETURN("Rep Return"),
}

private enum class PaymentChoice(val label: String) {
  CREDIT("Credit"),
  CASH("Cash"),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SalesTab(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val context = LocalContext.current
  val palette = LocalSarimPalette.current
  val snap = state.snapshot
  val hideMoney = state.hideMoneyFigures

  var mode by remember { mutableStateOf(SalesMode.SALE) }
  var dateEpoch by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var customerName by remember { mutableStateOf("") }
  var newCustPhone by remember { mutableStateOf("") }
  var selectedRep by remember { mutableStateOf("Direct") }
  var quantityText by remember { mutableStateOf("") }
  var selectedStoreKey by remember(state.stores) { mutableStateOf(state.stores.firstOrNull()?.storeKey ?: "zubair") }
  var paymentChoice by remember { mutableStateOf(PaymentChoice.CREDIT) }
  var saleAddToLedger by remember { mutableStateOf(true) }
  var amountReceivedText by remember { mutableStateOf("") }
  var expiredQtyText by remember { mutableStateOf("") }
  var repExpiredQtyText by remember { mutableStateOf("") }
  var repReturnedQtyText by remember { mutableStateOf("") }
  var collAddToLedger by remember { mutableStateOf(true) }
  var showStockDetails by remember { mutableStateOf(false) }
  var editingSaleId by remember { mutableStateOf<String?>(null) }
  var sheetSale by remember { mutableStateOf<SaleTransactionEntity?>(null) }

  var custSearchFilter by remember { mutableStateOf("") }
  var analyticsPeriod by remember { mutableStateOf(TimePeriod.WEEKLY) }
  var summaryPeriod by remember { mutableStateOf(TimePeriod.DAILY) }

  val isRepSelected = !selectedRep.equals("Direct", ignoreCase = true)
  val availableModes = remember(isRepSelected) {
    if (isRepSelected) listOf(SalesMode.SALE, SalesMode.REP_RETURN)
    else listOf(SalesMode.SALE, SalesMode.COLLECTION)
  }
  LaunchedEffect(isRepSelected) {
    if (isRepSelected) {
      if (mode == SalesMode.COLLECTION) mode = SalesMode.SALE
      paymentChoice = PaymentChoice.CREDIT
      saleAddToLedger = false
    } else {
      if (mode == SalesMode.REP_RETURN) mode = SalesMode.SALE
    }
  }

  val directCustomers = remember(state.customers) { state.customers.filter { !it.isRepCustomer } }
  val activePartyName = if (isRepSelected) selectedRep else customerName.trim()
  val matchedCustomer = remember(activePartyName, directCustomers, isRepSelected) {
    if (isRepSelected) null
    else directCustomers.firstOrNull { it.name.equals(activePartyName, ignoreCase = true) }
  }
  val repPriceContact = remember(activePartyName, directCustomers, isRepSelected) {
    if (!isRepSelected) null
    else directCustomers.firstOrNull { it.name.equals(activePartyName, ignoreCase = true) }
  }
  val isNewCustomer = !isRepSelected && customerName.isNotBlank() && matchedCustomer == null

  val store = state.stores.firstOrNull { it.storeKey == selectedStoreKey } ?: state.stores.firstOrNull()
  val storeStock = store?.let { snap?.storeMetrics?.get(it.storeKey) }
  // Per-store, per-day stock exactly like the web app: what this store produced / got back on the
  // selected date, minus what it already sold that date (the sale being edited is excluded).
  val dateKey = SarimFormat.dateInput(dateEpoch)
  val daySnap = remember(store?.storeKey, dateKey, state.productionEntries, state.sales, state.settlements, state.stockTransfers, editingSaleId) {
    store?.let {
      SarimStockCheck.snapshot(it.storeKey, dateKey, state.productionEntries, state.sales, state.settlements, state.stockTransfers, editingSaleId)
    }
  }
  val inStockKg = daySnap?.availableKg ?: 0.0
  val unitCostKg = storeStock?.avgCostPerKg ?: 0.0
  val unitPriceKg = matchedCustomer?.customSalePrice ?: repPriceContact?.customSalePrice ?: (store?.salePricePerKg ?: 400.0)

  val qtyNum = SarimFormat.parseSafeDouble(quantityText) ?: 0.0
  val totalValue = qtyNum * unitPriceKg
  val totalCost = qtyNum * unitCostKg
  val profit = totalValue - totalCost
  val overStock = mode == SalesMode.SALE && qtyNum > 0.0 && qtyNum - inStockKg > 1e-6

  val repOptions = remember(snap?.repsList) { listOf("Direct") + (snap?.repsList ?: emptyList()) }
  val reconciledReturns = remember(state.settlements) { SarimLinkEngine.reconciledRepReturnIds(state.settlements) }

  val repOutstanding = remember(selectedRep, isRepSelected, state.sales, state.settlements, editingSaleId) {
    if (!isRepSelected) 0.0
    else SarimLinkEngine.partyOutstanding(
      name = selectedRep,
      sales = state.sales,
      isRepProfile = false,
      settlements = state.settlements,
      excludeSaleId = editingSaleId,
    )
  }
  val repPendingDeliveriesKg = remember(selectedRep, isRepSelected, state.sales, state.settlements) {
    if (!isRepSelected) 0.0
    else SarimLinkEngine.getPendingRepDeliveries(selectedRep, state.sales, state.settlements).sumOf { it.quantityKg }
  }
  val repPendingReturnsKg = remember(selectedRep, isRepSelected, state.sales, state.settlements, editingSaleId) {
    if (!isRepSelected) 0.0
    else SarimLinkEngine.getPendingRepReturns(selectedRep, state.sales, state.settlements)
      .filter { it.id != editingSaleId }
      .sumOf { it.expiredQtyKg + it.returnedQtyKg }
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item { ShimmerHeading("Sales", fontSizeSp = 26) }

    // 1. New Sale / Collection / Rep Return Card
    item {
      SarimCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = when {
              editingSaleId == null && mode == SalesMode.REP_RETURN -> "Rep Return ($selectedRep)"
              editingSaleId == null && isRepSelected -> "Deliver to Rep ($selectedRep)"
              editingSaleId == null -> "New Sale"
              mode == SalesMode.COLLECTION -> "Edit Collection"
              mode == SalesMode.REP_RETURN -> "Edit Rep Return"
              else -> "Edit Sale"
            },
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
          )
          if (editingSaleId != null) {
            val origSale = state.sales.firstOrNull { it.id == editingSaleId }
            val hasEdits = origSale == null ||
              customerName.trim() != origSale.customerName ||
              quantityText != origSale.quantityKg.toString() ||
              (SarimFormat.parseSafeDouble(amountReceivedText) ?: 0.0) != displaySaleAmount(origSale) ||
              (SarimFormat.parseSafeDouble(expiredQtyText) ?: 0.0) != origSale.expiredQtyKg ||
              (SarimFormat.parseSafeDouble(repExpiredQtyText) ?: 0.0) != origSale.expiredQtyKg ||
              (SarimFormat.parseSafeDouble(repReturnedQtyText) ?: 0.0) != origSale.returnedQtyKg
            TextButton(
              onClick = {
                viewModel.requestCancelEdit(hasEdits) {
                  editingSaleId = null; customerName = ""; newCustPhone = ""; quantityText = ""
                  amountReceivedText = ""; expiredQtyText = ""; repExpiredQtyText = ""; repReturnedQtyText = ""
                }
              },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("btn_cancel_edit_sale"),
            ) { Text("Cancel Edit", color = palette.amber) }
          }
        }
        if (editingSaleId != null) {
          SarimCallout("Editing entry · Saving updates the original record.", palette.amber)
        }

        val lockRepReturnIdentity = editingSaleId != null && mode == SalesMode.REP_RETURN
        SarimToggleGroup(
          options = availableModes,
          selected = if (mode in availableModes) mode else availableModes.first(),
          labelOf = { it.label },
          onSelect = { if (editingSaleId == null) mode = it },
          testTagPrefix = "sales_mode",
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
          Box(modifier = Modifier.weight(1f)) {
            SarimDatePickerField("Date", dateEpoch, onDateSelected = {
              if (lockRepReturnIdentity) {
                viewModel.showDialog(AppDialogRequest("Action Not Allowed", "The rep and date of a return cannot be changed. Delete it and enter it again.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
              } else {
                dateEpoch = it
              }
            }, testTag = "cust_date")
          }
          Box(modifier = Modifier.weight(1f)) {
            if (isRepSelected) {
            SarimTextField(
              value = selectedRep,
              onValueChange = {},
              label = "Customer Name",
              readOnly = true,
              dashedReadOnly = true,
              testTag = "cust_name",
            )
            } else {
            SarimSearchFieldWithDropdown(
              value = customerName,
              onValueChange = { customerName = it },
              label = "Customer Name",
              placeholder = "Search or type new...",
              suggestions = directCustomers.map { it.name },
              onSuggestionSelected = { customerName = it },
              testTag = "cust_name",
            )

            }
          }
        }

        SarimFieldLabel("Sales Representative (For Calculator Link)")
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          repOptions.forEach { rep ->
            val sel = rep.equals(selectedRep, ignoreCase = true)
            Surface(
              shape = RoundedCornerShape(50),
              color = if (sel) palette.green.copy(alpha = 0.16f) else palette.surfaceElevated,
              border = BorderStroke(1.dp, if (sel) palette.green else palette.border),
              modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(50))
                .testTag("sales_rep_chip_${rep.lowercase().replace(" ", "_")}")
                .clickable {
                  if (lockRepReturnIdentity && !sel) {
                    viewModel.showDialog(AppDialogRequest("Action Not Allowed", "The rep and date of a return cannot be changed. Delete it and enter it again.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                  } else {
                    selectedRep = rep
                  }
                },
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(rep, color = if (sel) palette.green else palette.textMuted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
              }
            }
          }
        }

        if (isRepSelected) {
          Surface(shape = RoundedCornerShape(16.dp), color = palette.surfaceElevated, border = BorderStroke(1.dp, palette.cyan.copy(alpha = 0.45f))) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
              Column {
                Text("Rep Outstanding", color = palette.textMuted, fontSize = 11.sp)
                Text(SarimFormat.formatRs(repOutstanding, true, hideMoney), color = palette.cyan, style = TabularNumberStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
              }
              Column(horizontalAlignment = Alignment.End) {
                Text("Awaiting Calc", color = palette.textMuted, fontSize = 11.sp)
                Text(SarimFormat.formatKg(repPendingDeliveriesKg), color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
              }
            }
          }
        } else {
          if (isNewCustomer) {
            SarimTextField(
              value = newCustPhone,
              onValueChange = { newCustPhone = it },
              label = "Mobile number",
              placeholder = "03XX-XXXXXXX",
              keyboardType = KeyboardType.Phone,
              testTag = "new_cust_phone",
            )
          }

          if (matchedCustomer != null) {
            Surface(shape = RoundedCornerShape(16.dp), color = palette.surfaceElevated, border = BorderStroke(1.dp, palette.amber.copy(alpha = 0.45f))) {
              Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                  Text("Current Credit", color = palette.textMuted, fontSize = 11.sp)
                  Text(SarimFormat.formatRs(matchedCustomer.currentCredit, true, hideMoney), color = palette.amber, style = TabularNumberStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
                }
                Column(horizontalAlignment = Alignment.End) {
                  Text("Total Quantity", color = palette.textMuted, fontSize = 11.sp)
                  Text(SarimFormat.formatKg(matchedCustomer.totalQuantityKg), color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
                }
              }
            }
          }
        }

        when (mode) {
          SalesMode.SALE -> {
            SarimTextField(
              value = quantityText,
              onValueChange = { quantityText = it },
              label = "Quantity (kg)",
              placeholder = "0.000",
              suffixText = "kg",
              keyboardType = KeyboardType.Decimal,
              testTag = "cust_quantity",
            )

            if (state.stores.isNotEmpty()) {
              SarimFieldLabel("Supply")
              SarimToggleGroup(
                options = state.stores,
                selected = store ?: state.stores.first(),
                labelOf = { it.name },
                onSelect = { selectedStoreKey = it.storeKey },
                colorOf = { storeColorForKey(it.storeKey, palette.isDark) },
                testTagPrefix = "sales_store",
              )
            }

            if (isRepSelected) {
              SarimCallout("Rep deliveries are saved on Credit and reconciled later in Calculator (excluded from Direct Customers & Daily Ledger).", palette.cyan)
            } else {
              SarimFieldLabel("Payment")
              SarimToggleGroup(
                options = PaymentChoice.entries,
                selected = paymentChoice,
                labelOf = { it.label },
                onSelect = { paymentChoice = it },
                colorOf = { if (it == PaymentChoice.CASH) palette.green else palette.amber },
                testTagPrefix = "sales_payment",
              )

              if (paymentChoice == PaymentChoice.CASH) {
                SarimCheckboxRow(
                  checked = saleAddToLedger,
                  onCheckedChange = { saleAddToLedger = it },
                  label = "Add cash sale to Daily Ledger (Cash In +)",
                )
              }
            }

            SarimCard {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total Cost:", color = palette.textMuted, fontSize = 12.5.sp)
                Text(SarimFormat.formatRs(totalCost, true, hideMoney), color = palette.red, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total Value:", color = palette.textMuted, fontSize = 12.5.sp)
                Text(SarimFormat.formatRs(totalValue, true, hideMoney), color = palette.green, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold))
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Profit:", color = palette.textMuted, fontSize = 12.5.sp)
                Text(SarimFormat.formatRs(profit, true, hideMoney), color = palette.emerald, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold))
              }
            }

            val tileTone = SarimStockCheck.tone(inStockKg, qtyNum)
            val stockTone = when (tileTone) {
              StockTone.SHORT -> palette.red
              StockTone.LOW -> palette.amber
              StockTone.OK -> palette.green
            }
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = stockTone.copy(alpha = 0.1f),
              border = BorderStroke(1.dp, stockTone.copy(alpha = 0.5f)),
              modifier = Modifier.fillMaxWidth().clickable { showStockDetails = !showStockDetails },
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                  Text("In Stock: ${SarimFormat.formatKg(inStockKg.coerceAtLeast(0.0))}", color = stockTone, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                  SarimChip(SarimStockCheck.note(inStockKg, qtyNum), stockTone)
                }
                LinearProgressIndicator(
                  progress = { SarimStockCheck.usedFraction(inStockKg, qtyNum) },
                  modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                  color = stockTone,
                  trackColor = palette.surfaceElevated,
                )
                if (showStockDetails && daySnap != null) {
                  Text(
                    "Produced: ${SarimFormat.formatKg(daySnap.producedKg)} · Returned: ${SarimFormat.formatKg(daySnap.returnsKg + daySnap.transferInKg)} · Sold: ${SarimFormat.formatKg(daySnap.soldKg + daySnap.transferOutKg)}",
                    color = palette.textMuted,
                    fontSize = 11.5.sp,
                  )
                }
              }
            }
          }

          SalesMode.COLLECTION -> {
            val amtRec = SarimFormat.parseSafeDouble(amountReceivedText) ?: 0.0
            val expQty = SarimFormat.parseSafeDouble(expiredQtyText) ?: 0.0
            val currentDebt = matchedCustomer?.currentCredit ?: 0.0
            val expUnitPrice = SarimLinkEngine.goodsCreditUnitPrice(
              name = customerName.trim(),
              sales = state.sales,
              fallbackPrice = unitPriceKg,
              isRepProfile = false,
              excludeSaleId = editingSaleId,
            )
            val expPlan = SarimLinkEngine.planGoodsCredit(expQty, expUnitPrice, (currentDebt - amtRec).coerceAtLeast(0.0))
            val afterDebt = currentDebt - amtRec - expPlan.value

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
              SarimTextField(
                value = amountReceivedText,
                onValueChange = { amountReceivedText = it },
                label = "Amount Received (₨)",
                placeholder = "0.00",
                prefixText = "Rs",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
                testTag = "cust_amount_collected",
              )
              SarimTextField(
                value = expiredQtyText,
                onValueChange = { expiredQtyText = it },
                label = "Expired / Damage Qty (kg)",
                placeholder = "0",
                suffixText = "kg",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
                testTag = "cust_expired_qty",
              )
            }
            if (expQty > 0.0) {
              SarimCallout(
                "Expired goods credit: ${SarimFormat.formatRs(expPlan.value, true, hideMoney)} (@ ${SarimFormat.formatRs(expUnitPrice)}/kg) → moves to CHORA",
                palette.amber,
              )
            }
            SarimCheckboxRow(
              checked = collAddToLedger,
              onCheckedChange = { collAddToLedger = it },
              label = "Add to Daily Ledger",
            )
            SarimCard {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Balance After Collection:", color = palette.textMuted, fontSize = 12.5.sp)
                Text(SarimFormat.formatRs(afterDebt, true, hideMoney), color = if (afterDebt <= 0.01) palette.green else palette.amber, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold))
              }
            }
          }

          SalesMode.REP_RETURN -> {
            val repExp = SarimFormat.parseSafeDouble(repExpiredQtyText) ?: 0.0
            val repRet = SarimFormat.parseSafeDouble(repReturnedQtyText) ?: 0.0
            val allowanceKg = (repPendingDeliveriesKg - repPendingReturnsKg).coerceAtLeast(0.0)
            val repUnitPrice = SarimLinkEngine.goodsCreditUnitPrice(
              name = selectedRep,
              sales = state.sales,
              fallbackPrice = unitPriceKg,
              isRepProfile = false,
              excludeSaleId = editingSaleId,
            )
            val creditPlan = SarimLinkEngine.planGoodsCredit(repExp + repRet, repUnitPrice, repOutstanding)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SarimTextField(
                value = repExpiredQtyText,
                onValueChange = { repExpiredQtyText = it },
                label = "Expired Qty (kg)",
                placeholder = "0",
                suffixText = "kg",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
                testTag = "rep_return_expired_qty",
              )
              SarimTextField(
                value = repReturnedQtyText,
                onValueChange = { repReturnedQtyText = it },
                label = "Returned Qty (kg)",
                placeholder = "0",
                suffixText = "kg",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
                testTag = "rep_return_returned_qty",
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SarimStatTile("Return Allowance", SarimFormat.formatKg(allowanceKg), Modifier.weight(1f), palette.cyan)
              SarimStatTile("Goods Credit", SarimFormat.formatRs(creditPlan.value, false, hideMoney), Modifier.weight(1f), palette.green)
            }
            SarimCallout("Stock moves to CHORA / store inventory only when Calculator settlement is reconciled.", palette.amber)
          }
        }

        SarimPrimaryButton(
          text = if (editingSaleId == null) "Save Transaction" else "Update Transaction",
          testTag = "btn_save_cust_transaction",
          onClick = {
            if (SarimFormat.isFutureDate(dateEpoch)) {
              viewModel.showDialog(AppDialogRequest("Invalid Date", "Future dates are refused.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (!isRepSelected && (customerName.isBlank() || customerName.length > 80)) {
              viewModel.showDialog(AppDialogRequest("Customer Required", "Enter a valid customer name (max 80 characters).", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (isNewCustomer && newCustPhone.isNotBlank() && !SarimFormat.isValidPhone(newCustPhone)) {
              viewModel.showDialog(AppDialogRequest("Invalid Phone", "Phone number must have 7 to 15 digits.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            when (mode) {
              SalesMode.SALE -> {
                if (store == null || unitPriceKg <= 0.0) {
                  viewModel.showDialog(AppDialogRequest("Sale Price Missing", "Sale price not configured for this store. Set prices in Store Manager first.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
                if (qtyNum !in 0.001..1_000_000.0) {
                  viewModel.showDialog(AppDialogRequest("Invalid Quantity", "Quantity must be between 0.001 and 1,000,000 kg.", tone = DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
                val stockIssue = daySnap?.let {
                  SarimStockCheck.blockingIssue(store.name, SarimFormat.formatDate(dateEpoch), it, qtyNum)
                }
                if (stockIssue != null) {
                  viewModel.showDialog(AppDialogRequest(stockIssue.first, stockIssue.second, tone = DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
                val effPayment = if (isRepSelected) PaymentChoice.CREDIT else paymentChoice
                val existingCredit = if (!isRepSelected && effPayment == PaymentChoice.CREDIT && customerName.isNotBlank()) {
                  SarimLinkEngine.partyOutstanding(
                    name = customerName.trim(),
                    sales = state.sales,
                    isRepProfile = false,
                    settlements = state.settlements,
                    excludeSaleId = editingSaleId,
                  ).coerceAtLeast(0.0)
                } else 0.0
                val creditWarning = if (existingCredit > 5000.0) {
                  viewModel.redactMoney(
                    "Unpaid balance: ${SarimFormat.formatRs(existingCredit)}\nThis credit sale: ${SarimFormat.formatRs(totalValue)}\nNew total: ${SarimFormat.formatRs(existingCredit + totalValue)}\n\n${customerName.trim()} already has unpaid credit. Collect it before adding more.",
                    "Check this credit sale before saving.",
                  )
                } else null
                viewModel.confirmSave(
                  "Transaction",
                  listOf("Customer" to (if (isRepSelected) selectedRep else customerName), "Store" to store.name, "Quantity" to SarimFormat.formatKg(qtyNum), "Payment" to effPayment.name, "Date" to SarimFormat.formatDate(dateEpoch)),
                  isUpdate = editingSaleId != null,
                  warning = creditWarning,
                  shareAfterSave = if (isRepSelected) null else ShareAfterSave(StatementKind.CUSTOMER, customerName, dateEpoch),
                ) {
                  viewModel.launchSafely(
                    errorTitle = "Save Sale Failed",
                    successMessage = if (editingSaleId == null) "Sale saved" else "Sale updated",
                    onSuccess = { editingSaleId = null; quantityText = ""; customerName = ""; newCustPhone = "" },
                  ) {
                    viewModel.repository.saveSaleOrCollection(
                      editingId = editingSaleId,
                      dateEpoch = dateEpoch,
                      mode = "SALE",
                      customerName = if (isRepSelected) selectedRep else customerName,
                      newCustomerPhone = if (isRepSelected) "" else newCustPhone,
                      repName = selectedRep,
                      store = store,
                      quantityKg = qtyNum,
                      costPerKg = unitCostKg,
                      paymentType = effPayment.name,
                      amountReceived = if (effPayment == PaymentChoice.CASH) totalValue else 0.0,
                      expiredQtyKg = 0.0,
                      returnedQtyKg = 0.0,
                      addToLedger = !isRepSelected && effPayment == PaymentChoice.CASH && saleAddToLedger,
                      isRepSale = false,
                    )
                  }
                }
              }

              SalesMode.COLLECTION -> {
                val amtRec = SarimFormat.parseSafeDouble(amountReceivedText) ?: 0.0
                val expQty = SarimFormat.parseSafeDouble(expiredQtyText) ?: 0.0
                if (amtRec <= 0.0 && expQty <= 0.0) {
                  viewModel.showDialog(AppDialogRequest("Invalid Collection", "Enter amount received or expired qty.", confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
                val currentDebt = matchedCustomer?.currentCredit ?: 0.0
                if (editingSaleId == null && amtRec > 0.0 && currentDebt <= 0.01) {
                  viewModel.showDialog(AppDialogRequest("No Outstanding Balance", "${customerName.trim()} has no pending credit balance to collect.", confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
                val origColl = editingSaleId?.let { id -> state.sales.firstOrNull { it.id == id } }
                if (origColl != null && origColl.expiredQtyKg > expQty + 1e-4) {
                  val choraStock = state.rawMaterials.firstOrNull { !it.isDeleted && it.name.equals("CHORA", ignoreCase = true) }?.stockKg ?: 0.0
                  val choraDrop = origColl.expiredQtyKg - expQty
                  if (choraStock + 1e-6 < choraDrop) {
                    viewModel.showDialog(AppDialogRequest("CHORA Stock Used", "Cannot reduce expired quantity by ${SarimFormat.formatKg(choraDrop)}: only ${SarimFormat.formatKg(choraStock)} of CHORA remains unused.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                    return@SarimPrimaryButton
                  }
                }
                val expUnit = SarimLinkEngine.goodsCreditUnitPrice(customerName.trim(), state.sales, unitPriceKg, isRepProfile = false, excludeSaleId = editingSaleId)
                val expPlan = SarimLinkEngine.planGoodsCredit(expQty, expUnit, (currentDebt - amtRec).coerceAtLeast(0.0))
                val collNotes = buildList {
                  if (amtRec > currentDebt + 0.01) {
                    add("Amount received exceeds customer's current credit (balance after: ${SarimFormat.formatRs(currentDebt - amtRec, false, hideMoney)}). Proceed only if this is an advance payment.")
                  }
                  if (expQty > 0.0) {
                    add("Expired ${SarimFormat.formatKg(expQty)} will be added to CHORA raw material.")
                    if (expPlan.value > 0.0) {
                      add(viewModel.redactMoney(
                        "Expired goods credit: ${SarimFormat.formatRs(expPlan.value)} will be deducted from ${customerName.trim()}'s balance.",
                        "Expired goods credit will be deducted from customer's balance.",
                      ))
                    }
                  }
                }
                viewModel.confirmSave(
                  "Transaction",
                  listOf("Customer" to customerName, "Amount" to SarimFormat.formatRs(amtRec, false, hideMoney), "Expired (kg)" to expiredQtyText, "Date" to SarimFormat.formatDate(dateEpoch)),
                  isUpdate = editingSaleId != null,
                  warning = collNotes.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                  shareAfterSave = ShareAfterSave(StatementKind.CUSTOMER, customerName, dateEpoch),
                ) {
                  viewModel.launchSafely(
                    errorTitle = "Save Collection Failed",
                    successMessage = if (editingSaleId == null) "Collection saved" else "Collection updated",
                    onSuccess = { editingSaleId = null; amountReceivedText = ""; expiredQtyText = ""; customerName = ""; newCustPhone = "" },
                  ) {
                    viewModel.repository.saveSaleOrCollection(
                      editingId = editingSaleId,
                      dateEpoch = dateEpoch,
                      mode = "COLLECTION",
                      customerName = customerName,
                      newCustomerPhone = newCustPhone,
                      repName = "Direct",
                      store = store,
                      quantityKg = 0.0,
                      costPerKg = 0.0,
                      paymentType = "CASH",
                      amountReceived = amtRec,
                      expiredQtyKg = expQty,
                      returnedQtyKg = 0.0,
                      addToLedger = collAddToLedger,
                      isRepSale = false,
                    )
                  }
                }
              }

              SalesMode.REP_RETURN -> {
                if (selectedRep.isBlank() || selectedRep == "Direct") {
                  viewModel.showDialog(AppDialogRequest("Rep Required", "Please select a sales representative.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
                val origReturn = editingSaleId?.let { id -> state.sales.firstOrNull { it.id == id } }
                if (origReturn != null && (!selectedRep.equals(origReturn.repName, true) || SarimFormat.dateInput(dateEpoch) != SarimFormat.dateInput(origReturn.dateEpoch))) {
                  viewModel.showDialog(AppDialogRequest("Action Not Allowed", "The rep and date of a return cannot be changed. Delete it and enter it again.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
                val repExp = SarimFormat.parseSafeDouble(repExpiredQtyText) ?: 0.0
                val repRet = SarimFormat.parseSafeDouble(repReturnedQtyText) ?: 0.0
                if (repExp <= 0.0 && repRet <= 0.0) {
                  viewModel.showDialog(AppDialogRequest("Invalid Return", "Enter expired and/or returned quantity in kg.", confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
                val (deliveredKg, alreadyRetKg, allowanceKg) = SarimLinkEngine.getRepReturnAllowance(
                  repName = selectedRep,
                  excludeId = editingSaleId,
                  sales = state.sales,
                  settlements = state.settlements,
                )
                if (repExp + repRet > allowanceKg + 1e-4) {
                  viewModel.showDialog(
                    AppDialogRequest(
                      title = "Return Exceeds Pending Deliveries",
                      body = "$selectedRep has ${SarimFormat.formatKg(deliveredKg)} delivered awaiting calculation and ${SarimFormat.formatKg(alreadyRetKg)} already pending return.\nMax returnable now: ${SarimFormat.formatKg(allowanceKg)}\nRequested: ${SarimFormat.formatKg(repExp + repRet)}",
                      tone = DialogTone.WARNING,
                      confirmLabel = "OK",
                      cancelLabel = null,
                    )
                  )
                  return@SarimPrimaryButton
                }
                viewModel.confirmSave(
                  "Transaction",
                  listOf("Rep" to selectedRep, "Expired (kg)" to repExpiredQtyText, "Returned (kg)" to repReturnedQtyText, "Date" to SarimFormat.formatDate(dateEpoch)),
                  isUpdate = editingSaleId != null,
                ) {
                  viewModel.launchSafely(
                    errorTitle = "Save Rep Return Failed",
                    successMessage = if (editingSaleId == null) "Rep return saved" else "Rep return updated",
                    onSuccess = { editingSaleId = null; repExpiredQtyText = ""; repReturnedQtyText = "" },
                  ) {
                    viewModel.repository.saveRepReturn(
                      editingId = editingSaleId,
                      dateEpoch = dateEpoch,
                      repName = selectedRep,
                      expiredQtyKg = repExp,
                      returnedQtyKg = repRet,
                      fallbackUnitPrice = unitPriceKg,
                    )
                  }
                }
              }
            }
          },
        )
      }
    }

    if (state.isAdmin) {
    // 2. CUSTOMERS List Card
    item {
      val filteredCusts = directCustomers.filter {
        custSearchFilter.isBlank() || it.name.contains(custSearchFilter, ignoreCase = true) || it.phone.contains(custSearchFilter)
      }
      SarimCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Column {
            Text("CUSTOMERS", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.8.sp)
            Text("${directCustomers.size} active", color = palette.textMuted, fontSize = 11.5.sp)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
              onClick = {
                com.example.util.CustomerListPdf.create(
                  context,
                  fileName = "All_Customers_List.pdf",
                  title = "All Customers — Complete List",
                  rows = com.example.util.CustomerListPdf.rowsFor(directCustomers, state.sales),
                ).fold(
                  onSuccess = { f ->
                    if (com.example.util.DirectShare.shareViaChooser(context, f, "application/pdf", "All Customers — Complete List") == com.example.util.ShareOutcome.FAILED) {
                      viewModel.postStatus("Unable to share the customer list")
                    }
                  },
                  onFailure = { err -> viewModel.postStatus(err.localizedMessage ?: "Unable to export PDF") },
                )
              },
            ) { Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Export customers PDF", tint = palette.cyan) }
            IconButton(onClick = { viewModel.openStandalone(StandaloneScreen.CustomerEdit(null)) }) {
              Icon(Icons.Rounded.Add, contentDescription = "Add customer", tint = palette.green)
            }
          }
        }
        SarimTextField(
          value = custSearchFilter,
          onValueChange = { custSearchFilter = it },
          label = "",
          placeholder = "Search customers...",
          leadingIcon = Icons.Rounded.Search,
          testTag = "customer_filter",
        )
        SarimRecordTable(
          rows = filteredCusts.map { c ->
            val owes = c.currentCredit > 0.01
            RecordRow(
              key = c.id,
              name = c.name.uppercase(),
              avatarBadge = c.ledgerPage,
              details = if (c.phone.isNotBlank()) {
                listOf(
                  RecordDetail(
                    text = c.phone,
                    color = palette.green,
                    underline = true,
                    onClick = {
                      runCatching {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                      }.onFailure { viewModel.postStatus("No dialer app available") }
                    },
                    onLongClick = {
                      if (!com.example.util.DirectShare.openWhatsAppChat(context, c.phone)) viewModel.postStatus("WhatsApp or browser unavailable")
                    },
                  ),
                )
              } else {
                listOf(RecordDetail("No phone"))
              },
              amount = SarimFormat.formatRs(c.currentCredit, true, hideMoney),
              amountColor = if (owes) palette.red else palette.emerald,
              onClick = { viewModel.openStandalone(StandaloneScreen.CustomerDetails(c.id)) },
            )
          },
          stats = listOf(
            RecordStat("Outstanding credit", SarimFormat.formatRs(directCustomers.sumOf { it.currentCredit }, true, hideMoney), palette.red),
            RecordStat("Quantity sold", SarimFormat.formatKg(directCustomers.sumOf { it.totalQuantityKg })),
          ),
          emptyTitle = if (custSearchFilter.isBlank()) "No customers yet" else "No customers match your search",
          emptyHint = if (custSearchFilter.isBlank()) "Add a customer or save a sale to create one." else "Try a different name.",
        )
      }
    }

    // 3. Direct Analytics & Period Summary (excludes Rep-linked deliveries & Rep returns)
    if (state.isAdmin) {
      item {
        val cutoff = SarimMetricsEngine.periodStart(analyticsPeriod)
        val periodSales = state.sales.filter {
          !it.isRepSale && !it.isRepReturn && !it.isRepTransfer &&
            !SarimLinkEngine.isRepLinkedAdminSale(it) && it.mode == "SALE" && it.dateEpoch >= cutoff
        }
        SarimCard {
          Text("Direct Sales Analytics", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          SarimToggleGroup(
            listOf(TimePeriod.WEEKLY, TimePeriod.MONTHLY, TimePeriod.YEARLY, TimePeriod.ALL_TIME),
            analyticsPeriod,
            { it.label },
            { analyticsPeriod = it },
          )
          val cashVal = periodSales.filter { it.paymentType == "CASH" }.sumOf { it.totalValue }
          val credVal = periodSales.filter { it.paymentType == "CREDIT" }.sumOf { it.totalValue }
          DonutShareChart(
            slices = listOf(Triple("Cash", cashVal, palette.green), Triple("Credit", credVal, palette.amber)),
            centerLabel = "Split",
          )
        }
      }
    }

    item {
      val cutoff = SarimMetricsEngine.periodStart(summaryPeriod)
      val periodSales = state.sales.filter {
        !it.isRepSale && !it.isRepReturn && !it.isRepTransfer &&
          !SarimLinkEngine.isRepLinkedAdminSale(it) && it.mode == "SALE" && it.dateEpoch >= cutoff
      }
      val periodColls = state.sales.filter { !it.isRepSale && it.mode == "COLLECTION" && it.dateEpoch >= cutoff }
      val totalVal = periodSales.sumOf { it.totalValue }
      val totalProfit = periodSales.sumOf { it.profit }
      val collTotal = periodColls.sumOf { it.amountReceived.takeIf { a -> a > 0.0 } ?: it.totalValue }
      val cashIncl = periodSales.filter { it.paymentType == "CASH" }.sumOf { it.totalValue } + collTotal
      val pendingCredits = periodSales.filter { it.paymentType == "CREDIT" }.sumOf { it.totalValue }

      SarimCard {
        SarimToggleGroup(TimePeriod.entries, summaryPeriod, { it.label }, { summaryPeriod = it })
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("${summaryPeriod.label} Direct Sales", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          val margin = if (totalVal > 0.01) ((totalProfit / totalVal) * 100.0).toInt() else 0
          SarimChip("$margin% margin", palette.green)
        }
        Text(
          SarimFormat.formatRs(totalVal, true, hideMoney),
          color = palette.green,
          style = TabularNumberStyle.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
        )
        SplitRatioBar("Cash", cashIncl, palette.green, "Credit", pendingCredits, palette.amber, privacyHidden = hideMoney)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimStatTile("Quantity", SarimFormat.formatKg(periodSales.sumOf { it.quantityKg }), Modifier.weight(1f), palette.cyan)
          SarimStatTile("Net profit", SarimFormat.formatRs(totalProfit, false, hideMoney), Modifier.weight(1f), palette.emerald)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimStatTile("Cash (incl. rec)", SarimFormat.formatRs(cashIncl, false, hideMoney), Modifier.weight(1f), palette.green)
          SarimStatTile("Pending credits", SarimFormat.formatRs(pendingCredits, false, hideMoney), Modifier.weight(1f), palette.amber)
        }
      }
    }

    // 4. Sales History Accordion
    item {
      Text("Sales History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      Spacer(Modifier.height(6.dp))
      val directTx = state.sales.filter { !it.isRepSale }
      val records = directTx.map { s ->
        val isRepLink = SarimLinkEngine.isRepLinkedAdminSale(s)
        val isCash = s.paymentType == "CASH" || s.mode == "COLLECTION"
        val isReconciledRet = s.isRepReturn && s.id in reconciledReturns
        val dispAmt = displaySaleAmount(s)
        val chipLabel = when {
          s.isRepReturn -> if (isReconciledRet) "RECONCILED RET" else "REP RETURN"
          s.isRepTransfer -> "TRANSFER"
          s.mode == "COLLECTION" -> "COLLECTION"
          s.mode == "MONEY_OUT" -> "MONEY OUT"
          isRepLink -> if (s.creditReceived) "CALC SETTLED" else "REP LINKED"
          s.paymentType == "CASH" -> "PAID"
          s.creditReceived -> "SETTLED"
          else -> "PENDING"
        }
        val chipColor = when {
          s.isRepReturn -> if (isReconciledRet) palette.green else palette.cyan
          s.isRepTransfer -> palette.purple
          isCash || s.creditReceived -> palette.green
          isRepLink -> palette.cyan
          else -> palette.amber
        }
        val subtitle = when {
          s.isRepReturn -> "Exp ${SarimFormat.formatKg(s.expiredQtyKg)} · Ret ${SarimFormat.formatKg(s.returnedQtyKg)}"
          s.isRepTransfer -> "${SarimFormat.formatKg(s.quantityKg)} · From ${s.repTransferFrom ?: "Rep"}"
          s.mode == "SALE" -> "${SarimFormat.formatKg(s.quantityKg)} · ${s.storeName}${if (isRepLink) " · Rep" else ""}"
          else -> s.mode
        }
        HistoryRecordDisplay(
          item = s,
          dateEpoch = s.dateEpoch,
          title = s.customerName,
          subtitle = subtitle,
          amountText = SarimFormat.formatRs(dispAmt, true, hideMoney),
          amountColor = if (isCash || s.creditReceived || s.isRepReturn) palette.green else palette.amber,
          chipText = chipLabel,
          chipColor = chipColor,
          showStripe = !s.creditReceived && !s.isRepReturn && s.mode == "SALE" && s.paymentType == "CREDIT",
          detailMetrics = buildSaleMetrics(s, hideMoney),
        )
      }
      AccordionHistoryList(
        records = records,
        yearSummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.quantityKg }) },
        monthSummary = { items ->
          "${SarimFormat.formatKg(items.sumOf { it.item.quantityKg })} sold · ${SarimFormat.formatRs(items.sumOf { displaySaleAmount(it.item) }, false, hideMoney)}"
        },
        daySummary = { items ->
          "${SarimFormat.formatKg(items.sumOf { it.item.quantityKg })} sold · ${SarimFormat.formatRs(items.sumOf { displaySaleAmount(it.item) }, false, hideMoney)}"
        },
        onItemClick = { rec -> sheetSale = rec.item },
      )
    }
    }  // end admin-only block
  }

  sheetSale?.let { s ->
    val isReconciledRet = s.isRepReturn && s.id in reconciledReturns
    val isCash = s.paymentType == "CASH" || s.mode == "COLLECTION"
    val dispAmt = displaySaleAmount(s)
    SarimRecordBottomSheet(
      title = s.customerName,
      subtitle = "${SarimFormat.formatDate(s.dateEpoch)}${if (s.timeStr.isNotBlank()) " · ${s.timeStr}" else ""}",
      amountText = SarimFormat.formatRs(dispAmt, true, hideMoney),
      amountColor = if (isCash || s.isRepReturn) palette.green else palette.amber,
      badgeText = when {
        s.isRepReturn -> if (isReconciledRet) "RECONCILED" else "REP RETURN"
        s.isRepTransfer -> "TRANSFER"
        s.mode == "SALE" -> if (s.paymentType == "CASH") "PAID" else if (s.creditReceived) "SETTLED" else "PENDING"
        else -> s.mode
      },
      badgeColor = if (isCash || s.creditReceived || s.isRepReturn) palette.green else palette.amber,
      metrics = buildSaleMetrics(s, hideMoney),
      onDismiss = { sheetSale = null },
      extraActionLabel = if (!s.isRepReturn) "Invoice" else null,
      extraActionIcon = if (!s.isRepReturn) Icons.Rounded.Share else null,
      onExtraAction = if (!s.isRepReturn) {
        {
          val cust = state.customers.firstOrNull { !it.isDeleted && it.isRepCustomer == s.isRepSale && (it.id == s.customerId || it.name.equals(s.customerName, ignoreCase = true)) }
          val data = cust?.let {
            StatementData.salesDayInvoice(
              if (s.isRepSale) StatementKind.REP else StatementKind.CUSTOMER,
              it, customerSalesOf(state, it), SarimFormat.dateInput(s.dateEpoch), hideMoney,
            )
          }
          if (data == null) viewModel.postStatus("Nothing to invoice for this day.")
          else StatementData.shareDayInvoice(context, data)?.let { viewModel.postStatus(it) }
        }
      } else null,
      onEdit = {
        val editBlock = when {
          YearCloseMerge.isMergedId(s.id) -> "Merged Entry" to "Merged opening balance entries cannot be edited."
          s.isRepTransfer || state.settlements.any { !it.isDeleted && it.transferSaleId == s.id } ->
            "Transfer Locked" to "Rep transfers belong to a Calculator settlement. Delete or update that Calculator settlement first."
          !s.usedInCalcId.isNullOrBlank() ||
            (!s.isRepSale && s.id in SarimLinkEngine.reconciledAdminSalesIds(state.settlements)) ||
            isReconciledRet ->
            "Transaction Reconciled" to "This entry is already reconciled in the Calculator. Delete the Calculator settlement first to edit it."
          s.mode == "MONEY_OUT" || s.isMoneyOut ->
            "Money Out Locked" to "Customer Money Out payouts cannot be edited directly here. Delete and re-record if needed."
          else -> null
        }
        if (editBlock != null) {
          viewModel.showDialog(
            AppDialogRequest(
              title = editBlock.first,
              body = editBlock.second,
              tone = DialogTone.ERROR,
              confirmLabel = "OK",
              cancelLabel = null,
            )
          )
        } else {
          loadSaleIntoEditor(
            s = s,
            onSetEditing = { editingSaleId = it },
            onSetDate = { dateEpoch = it },
            onSetCustomer = { customerName = it },
            onSetRep = { selectedRep = it },
            onSetMode = { mode = it },
            onSetQty = { quantityText = it },
            onSetStore = { selectedStoreKey = it },
            onSetPay = { paymentChoice = it },
            onSetSaleLedger = { saleAddToLedger = it },
            onSetAmtRec = { amountReceivedText = it },
            onSetCollExp = { expiredQtyText = it },
            onSetCollLedger = { collAddToLedger = it },
            onSetRepExp = { repExpiredQtyText = it },
            onSetRepRet = { repReturnedQtyText = it },
          )
        }
      },
      onDelete = {
        val blockMsg = if (YearCloseMerge.isMergedId(s.id)) {
          "Merged opening balance entries cannot be deleted."
        } else {
          SarimLinkEngine.getSaleBlockReason(s, state.settlements)
            ?: SarimLinkEngine.getCollectionDeleteBlockReason(s, state.rawMaterials)
        }
        if (blockMsg != null) {
          viewModel.showDialog(
            AppDialogRequest(
              title = "Cannot Delete",
              body = blockMsg,
              tone = DialogTone.ERROR,
              confirmLabel = "OK",
              cancelLabel = null,
            )
          )
        } else {
          val typeLabel = when {
            s.isRepReturn -> "Rep Return"
            s.mode == "COLLECTION" -> "Collection"
            s.mode == "MONEY_OUT" -> "Money Out"
            s.paymentType == "CASH" -> "Cash Sale"
            else -> "Credit Sale"
          }
          val detailLine = when {
            s.isRepReturn -> "Rep: ${s.repName.ifBlank { s.customerName }}\nReturned: ${SarimFormat.formatKg(s.returnedQtyKg)} · Expired: ${SarimFormat.formatKg(s.expiredQtyKg)}\nGoods Credit: ${viewModel.redactMoney(SarimFormat.formatRs(dispAmt))}"
            s.mode == "COLLECTION" -> "Customer: ${s.customerName}\nCollected: ${viewModel.redactMoney(SarimFormat.formatRs(dispAmt))}${if (s.expiredQtyKg > 0.0) "\nExpired Chora reversed: ${SarimFormat.formatKg(s.expiredQtyKg)}" else ""}"
            s.mode == "MONEY_OUT" -> "Customer: ${s.customerName}\nPayout Reversed: ${viewModel.redactMoney(SarimFormat.formatRs(dispAmt))}"
            else -> "Customer: ${s.customerName}\nQty: ${SarimFormat.formatKg(s.quantityKg)} (${s.storeName})\nAmount: ${viewModel.redactMoney(SarimFormat.formatRs(dispAmt))}"
          }
          val ledgerLine = if (s.addToLedger && !s.ledgerId.isNullOrBlank()) "\nLinked Daily Ledger entry will also be removed." else ""
          viewModel.showDialog(
            AppDialogRequest(
              title = "Delete $typeLabel?",
              body = "$detailLine$ledgerLine\n\nMoves to Recycle Bin for 90 days.",
              tone = DialogTone.PERMANENT,
              confirmLabel = "Delete",
              onConfirm = {
                viewModel.launchSafely(errorTitle = "Delete Failed", successMessage = "Moved to Recycle Bin") {
                  viewModel.repository.deleteSale(s.id)
                }
              },
            )
          )
        }
      },
    )
  }
}

private fun displaySaleAmount(s: SaleTransactionEntity): Double = when {
  s.mode == "COLLECTION" || s.mode == "MONEY_OUT" -> s.amountReceived.takeIf { it > 0.0 } ?: s.totalValue
  s.isRepReturn -> s.returnValue
  else -> s.totalValue
}

private fun buildSaleMetrics(s: SaleTransactionEntity, hideMoney: Boolean): List<Pair<String, String>> = buildList {
  add("Mode" to s.mode)
  if (s.storeName.isNotBlank()) add("Store" to s.storeName)
  when {
    s.isRepReturn -> {
      add("Rep" to s.repName.ifBlank { s.customerName })
      add("Expired Qty" to SarimFormat.formatKg(s.expiredQtyKg))
      add("Returned Qty" to SarimFormat.formatKg(s.returnedQtyKg))
      add("Goods Credit" to SarimFormat.formatRs(s.returnValue, false, hideMoney))
      add("Balance After" to SarimFormat.formatRs(s.balanceAfter, false, hideMoney))
    }
    s.mode == "SALE" -> {
      add("Quantity" to SarimFormat.formatKg(s.quantityKg))
      add("Rate / kg" to SarimFormat.formatRs(s.pricePerKg, true, hideMoney))
      add("Total Cost" to SarimFormat.formatRs(s.totalCost, false, hideMoney))
      add("Total Value" to SarimFormat.formatRs(s.totalValue, false, hideMoney))
      add("Profit" to SarimFormat.formatRs(s.profit, false, hideMoney))
      add("Payment" to s.paymentType)
      add("Rep" to s.repName.ifBlank { "Direct" })
      if (!s.repTransferFrom.isNullOrBlank()) add("Transferred From" to s.repTransferFrom)
      add("Balance After" to SarimFormat.formatRs(s.balanceAfter, false, hideMoney))
    }
    else -> {
      add("Amount Received" to SarimFormat.formatRs(displaySaleAmount(s), false, hideMoney))
      if (s.expiredQtyKg > 0.0) {
        add("Expired Qty" to SarimFormat.formatKg(s.expiredQtyKg))
        if (s.expiredValue > 0.0) add("Expired Credit" to SarimFormat.formatRs(s.expiredValue, false, hideMoney))
      }
      add("Balance After" to SarimFormat.formatRs(s.balanceAfter, false, hideMoney))
    }
  }
}

private fun loadSaleIntoEditor(
  s: SaleTransactionEntity,
  onSetEditing: (String) -> Unit,
  onSetDate: (Long) -> Unit,
  onSetCustomer: (String) -> Unit,
  onSetRep: (String) -> Unit,
  onSetMode: (SalesMode) -> Unit,
  onSetQty: (String) -> Unit,
  onSetStore: (String) -> Unit,
  onSetPay: (PaymentChoice) -> Unit,
  onSetSaleLedger: (Boolean) -> Unit,
  onSetAmtRec: (String) -> Unit,
  onSetCollExp: (String) -> Unit,
  onSetCollLedger: (Boolean) -> Unit,
  onSetRepExp: (String) -> Unit,
  onSetRepRet: (String) -> Unit,
) {
  onSetEditing(s.id)
  onSetDate(s.dateEpoch)
  val rep = s.repName.takeIf { it.isNotBlank() && !it.equals("NONE", true) && !it.equals("ADMIN", true) } ?: "Direct"
  onSetRep(rep)
  onSetCustomer(s.customerName)
  when {
    s.isRepReturn -> {
      onSetMode(SalesMode.REP_RETURN)
      onSetRepExp(if (s.expiredQtyKg > 0.0) s.expiredQtyKg.toString() else "")
      onSetRepRet(if (s.returnedQtyKg > 0.0) s.returnedQtyKg.toString() else "")
    }
    s.mode == "COLLECTION" -> {
      onSetMode(SalesMode.COLLECTION)
      onSetAmtRec(displaySaleAmount(s).toString())
      onSetCollExp(if (s.expiredQtyKg > 0.0) s.expiredQtyKg.toString() else "")
      onSetCollLedger(s.addToLedger)
    }
    else -> {
      onSetMode(SalesMode.SALE)
      onSetQty(s.quantityKg.toString())
      if (s.storeKey.isNotBlank()) onSetStore(s.storeKey)
      onSetPay(if (s.paymentType == "CASH") PaymentChoice.CASH else PaymentChoice.CREDIT)
      onSetSaleLedger(s.addToLedger)
    }
  }
}

