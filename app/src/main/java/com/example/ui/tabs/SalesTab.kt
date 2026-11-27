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
import com.example.data.local.SaleTransactionEntity
import com.example.data.model.StandaloneScreen
import com.example.data.model.TimePeriod
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.theme.avatarColorForName
import com.example.ui.theme.storeColorForKey
import com.example.util.AppDialogRequest
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
  var analyticsPeriod by remember { mutableStateOf(TimePeriod.MONTHLY) }
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
  val inStockKg = storeStock?.remainingKg ?: 0.0
  val unitCostKg = storeStock?.avgCostPerKg ?: 0.0
  val unitPriceKg = matchedCustomer?.customSalePrice ?: repPriceContact?.customSalePrice ?: (store?.salePricePerKg ?: 400.0)

  val qtyNum = SarimFormat.parseSafeDouble(quantityText) ?: 0.0
  val totalValue = qtyNum * unitPriceKg
  val totalCost = qtyNum * unitCostKg
  val profit = totalValue - totalCost
  val overStock = mode == SalesMode.SALE && qtyNum > inStockKg && qtyNum > 0.0

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
            TextButton(
              onClick = {
                editingSaleId = null; customerName = ""; newCustPhone = ""; quantityText = ""
                amountReceivedText = ""; expiredQtyText = ""; repExpiredQtyText = ""; repReturnedQtyText = ""
              },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("btn_cancel_edit_sale"),
            ) { Text("Cancel Edit", color = palette.amber) }
          }
        }
        if (editingSaleId != null) {
          SarimCallout("Editing entry · Saving updates the original record.", palette.amber)
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
                .clickable { selectedRep = rep },
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(rep, color = if (sel) palette.green else palette.textMuted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
              }
            }
          }
        }

        SarimToggleGroup(
          options = availableModes,
          selected = if (mode in availableModes) mode else availableModes.first(),
          labelOf = { it.label },
          onSelect = { mode = it },
          testTagPrefix = "sales_mode",
        )
        SarimDatePickerField("Date", dateEpoch, onDateSelected = { dateEpoch = it }, testTag = "cust_date")

        if (isRepSelected) {
          SarimTextField(
            value = selectedRep,
            onValueChange = {},
            label = "Customer Name (Locked to Rep)",
            readOnly = true,
            dashedReadOnly = true,
            testTag = "cust_name",
          )
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
          SarimSearchFieldWithDropdown(
            value = customerName,
            onValueChange = { customerName = it },
            label = "Customer Name",
            placeholder = "Search or type new...",
            suggestions = directCustomers.map { it.name },
            onSuggestionSelected = { customerName = it },
            testTag = "cust_name",
          )

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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SarimStatTile("Total Cost", SarimFormat.formatRs(totalCost, false, hideMoney), Modifier.weight(1f), palette.red)
              SarimStatTile("Total Value", SarimFormat.formatRs(totalValue, false, hideMoney), Modifier.weight(1f), palette.green)
              SarimStatTile("Profit", SarimFormat.formatRs(profit, false, hideMoney), Modifier.weight(1f), palette.emerald)
            }

            val stockTone = if (overStock || inStockKg <= 0.0) palette.red else palette.green
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = stockTone.copy(alpha = 0.1f),
              border = BorderStroke(1.dp, stockTone.copy(alpha = 0.5f)),
              modifier = Modifier.fillMaxWidth().clickable { showStockDetails = !showStockDetails },
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                  Text("In Stock: ${SarimFormat.formatKg(inStockKg)}", color = stockTone, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                  SarimChip(if (overStock) "Exceeds stock" else if (inStockKg > 0) "Ready to sell" else "Out of stock", stockTone)
                }
                LinearProgressIndicator(
                  progress = { if (inStockKg <= 0.0) 0f else ((inStockKg - qtyNum) / inStockKg).toFloat().coerceIn(0f, 1f) },
                  modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                  color = stockTone,
                  trackColor = palette.surfaceElevated,
                )
                if (showStockDetails && storeStock != null) {
                  Text(
                    "Produced: ${SarimFormat.formatKg(storeStock.producedKg)} · Sold: ${SarimFormat.formatKg(storeStock.soldKg)}",
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

            SarimTextField(
              value = amountReceivedText,
              onValueChange = { amountReceivedText = it },
              label = "Amount Received (₨)",
              placeholder = "Cash received from customer",
              prefixText = "Rs",
              keyboardType = KeyboardType.Decimal,
              testTag = "cust_amount_collected",
            )
            SarimTextField(
              value = expiredQtyText,
              onValueChange = { expiredQtyText = it },
              label = "Expired / Damage Qty (kg)",
              placeholder = "0",
              suffixText = "kg",
              keyboardType = KeyboardType.Decimal,
              testTag = "cust_expired_qty",
            )
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
            SarimStatTile(
              label = "Balance After Collection",
              value = SarimFormat.formatRs(afterDebt, true, hideMoney),
              accentColor = if (afterDebt <= 0.01) palette.green else palette.amber,
              modifier = Modifier.fillMaxWidth(),
            )
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
                val effPayment = if (isRepSelected) PaymentChoice.CREDIT else paymentChoice
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
                val commitCollection: () -> Unit = {
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
                if (amtRec > currentDebt + 0.01) {
                  viewModel.showDialog(
                    AppDialogRequest(
                      title = "Over-Collection Warning",
                      body = "Amount received exceeds customer's current credit.\nProceed only if this is an advance payment.",
                      tone = DialogTone.WARNING,
                      highlight = DialogHighlight("Balance After", SarimFormat.formatRs(currentDebt - amtRec)),
                      confirmLabel = "Proceed",
                      onConfirm = { commitCollection() },
                    )
                  )
                } else {
                  commitCollection()
                }
              }

              SalesMode.REP_RETURN -> {
                if (selectedRep.isBlank() || selectedRep == "Direct") {
                  viewModel.showDialog(AppDialogRequest("Rep Required", "Please select a sales representative.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
                val repExp = SarimFormat.parseSafeDouble(repExpiredQtyText) ?: 0.0
                val repRet = SarimFormat.parseSafeDouble(repReturnedQtyText) ?: 0.0
                if (repExp <= 0.0 && repRet <= 0.0) {
                  viewModel.showDialog(AppDialogRequest("Invalid Return", "Enter expired and/or returned quantity in kg.", confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
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
          },
        )
      }
    }

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
                val lines = filteredCusts.map { "${it.name.uppercase()} | ${it.phone} | Balance: ${SarimFormat.formatRs(it.currentCredit)}" }
                PdfExporter.sharePdfReport(context, "customers", "Direct Customers", "${filteredCusts.size} customers", lines)
                  .onFailure { err -> viewModel.postStatus(err.localizedMessage ?: "Unable to export PDF") }
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
        if (filteredCusts.isEmpty()) {
          SarimEmptyState("No customers yet", "Add a customer or save a sale to create one.")
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            filteredCusts.forEach { c ->
              val avColor = avatarColorForName(c.name)
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = palette.surfaceElevated,
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(16.dp))
                  .clickable { viewModel.openStandalone(StandaloneScreen.CustomerDetails(c.id)) },
              ) {
                Row(
                  modifier = Modifier.padding(10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  InitialsAvatar(c.name, avColor, sizeDp = 40.dp, badgeText = c.ledgerPage)
                  Column(modifier = Modifier.weight(1f)) {
                    Text(c.name.uppercase(), color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    if (c.phone.isNotBlank()) {
                      Text(
                        text = c.phone,
                        color = palette.green,
                        fontSize = 12.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.combinedClickable(
                          onClick = {
                            runCatching {
                              val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                              context.startActivity(dial)
                            }.onFailure {
                              viewModel.postStatus("No dialer app available")
                            }
                          },
                          onLongClick = {
                            runCatching {
                              val wa = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${c.phone.filter { it.isDigit() || it == '+' }}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                              context.startActivity(wa)
                            }.onFailure {
                              viewModel.postStatus("WhatsApp or browser unavailable")
                            }
                          },
                        ),
                      )
                    }
                  }
                  Text(
                    SarimFormat.formatRs(c.currentCredit, true, hideMoney),
                    color = if (c.currentCredit > 0.01) palette.amber else palette.green,
                    style = TabularNumberStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                  )
                }
              }
            }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimStatTile("Outstanding credit", SarimFormat.formatRs(directCustomers.sumOf { it.currentCredit }, true, hideMoney), Modifier.weight(1f), palette.amber)
          SarimStatTile("Quantity sold", SarimFormat.formatKg(directCustomers.sumOf { it.totalQuantityKg }), Modifier.weight(1f), palette.cyan)
        }
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
        daySummary = { items -> "${items.size} entries" },
        hasDropdownDetails = true,
        onItemClick = { rec -> sheetSale = rec.item },
      ) { rec ->
        val s = rec.item
        val isReconciledRet = s.isRepReturn && s.id in reconciledReturns
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (!s.isRepReturn) {
            SarimSecondaryButton(
              text = "Invoice PDF",
              icon = Icons.Rounded.Share,
              onClick = {
                PdfExporter.sharePdfReport(
                  context,
                  "invoice_${s.id.take(6)}",
                  "Sales Invoice · ${s.customerName}",
                  SarimFormat.formatDate(s.dateEpoch),
                  listOf(
                    "Customer: ${s.customerName}",
                    "Store: ${s.storeName}",
                    "Quantity: ${SarimFormat.formatKg(s.quantityKg)}",
                    "Rate: ${SarimFormat.formatRs(s.pricePerKg)}/kg",
                    "Payment: ${s.paymentType}",
                    "Total Value: ${SarimFormat.formatRs(displaySaleAmount(s))}",
                  ),
                ).onFailure { err ->
                  viewModel.postStatus(err.localizedMessage ?: "Unable to share invoice")
                }
              },
              modifier = Modifier.fillMaxWidth(),
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimSecondaryButton(
              text = "Edit",
              onClick = {
                if (isReconciledRet) {
                  viewModel.showDialog(
                    AppDialogRequest(
                      title = "Return Reconciled",
                      body = "This return is already reconciled in the Calculator. Delete the Calculator settlement first to edit it.",
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
              modifier = Modifier.weight(1f),
            )
            SarimDangerButton(
              text = "Delete",
              icon = Icons.Rounded.Delete,
              onClick = {
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Delete Transaction?",
                    body = "Customer: ${s.customerName}\nAmount: ${SarimFormat.formatRs(displaySaleAmount(s))}\nMoves to Recycle Bin for 90 days.",
                    confirmLabel = "Delete",
                    onConfirm = {
                      viewModel.launchSafely(errorTitle = "Delete Failed", successMessage = "Moved to Recycle Bin") {
                        viewModel.repository.deleteSale(s.id)
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
      extraActionLabel = if (!s.isRepReturn) "Invoice PDF" else null,
      extraActionIcon = if (!s.isRepReturn) Icons.Rounded.Share else null,
      onExtraAction = if (!s.isRepReturn) {
        {
          PdfExporter.sharePdfReport(
            context,
            "invoice_${s.id.take(6)}",
            "Sales Invoice · ${s.customerName}",
            SarimFormat.formatDate(s.dateEpoch),
            listOf(
              "Customer: ${s.customerName}",
              "Store: ${s.storeName}",
              "Quantity: ${SarimFormat.formatKg(s.quantityKg)}",
              "Rate: ${SarimFormat.formatRs(s.pricePerKg)}/kg",
              "Payment: ${s.paymentType}",
              "Total Value: ${SarimFormat.formatRs(dispAmt)}",
            ),
          ).onFailure { err ->
            viewModel.postStatus(err.localizedMessage ?: "Unable to share invoice")
          }
        }
      } else null,
      onEdit = {
        if (isReconciledRet) {
          viewModel.showDialog(
            AppDialogRequest(
              title = "Return Reconciled",
              body = "This return is already reconciled in the Calculator. Delete the Calculator settlement first to edit it.",
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
        viewModel.showDialog(
          AppDialogRequest(
            title = "Delete Transaction?",
            body = "Customer: ${s.customerName}\nAmount: ${SarimFormat.formatRs(dispAmt)}\nMoves to Recycle Bin for 90 days.",
            confirmLabel = "Delete",
            onConfirm = {
              viewModel.launchSafely(errorTitle = "Delete Failed", successMessage = "Moved to Recycle Bin") {
                viewModel.repository.deleteSale(s.id)
              }
            },
          )
        )
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

