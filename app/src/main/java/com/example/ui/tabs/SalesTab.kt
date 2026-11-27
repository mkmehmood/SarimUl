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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StandaloneScreen
import com.example.data.model.TimePeriod
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.theme.avatarColorForName
import com.example.ui.theme.storeColorForKey
import com.example.util.AppDialogRequest
import com.example.util.DialogHighlight
import com.example.util.PdfExporter
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.launch

private enum class SalesMode(val label: String) {
  SALE("Sale"),
  COLLECTION("Collection"),
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
  var collAddToLedger by remember { mutableStateOf(true) }
  var showStockDetails by remember { mutableStateOf(false) }

  var custSearchFilter by remember { mutableStateOf("") }
  var analyticsPeriod by remember { mutableStateOf(TimePeriod.MONTHLY) }
  var summaryPeriod by remember { mutableStateOf(TimePeriod.DAILY) }

  val directCustomers = remember(state.customers) { state.customers.filter { !it.isRepCustomer } }
  val matchedCustomer = remember(customerName, directCustomers) {
    directCustomers.firstOrNull { it.name.equals(customerName.trim(), ignoreCase = true) }
  }
  val isNewCustomer = customerName.isNotBlank() && matchedCustomer == null

  val store = state.stores.firstOrNull { it.storeKey == selectedStoreKey } ?: state.stores.firstOrNull()
  val storeStock = store?.let { snap?.storeMetrics?.get(it.storeKey) }
  val inStockKg = storeStock?.remainingKg ?: 0.0
  val unitCostKg = storeStock?.avgCostPerKg ?: 0.0
  val unitPriceKg = matchedCustomer?.customSalePrice ?: (store?.salePricePerKg ?: 400.0)

  val qtyNum = SarimFormat.parseSafeDouble(quantityText) ?: 0.0
  val totalValue = qtyNum * unitPriceKg
  val totalCost = qtyNum * unitCostKg
  val profit = totalValue - totalCost
  val overStock = mode == SalesMode.SALE && qtyNum > inStockKg && qtyNum > 0.0

  val repOptions = remember(snap?.repsList) { listOf("Direct") + (snap?.repsList ?: emptyList()) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item { ShimmerHeading("Sales", fontSizeSp = 26) }

    // 1. New Sale / Collection Card
    item {
      SarimCard {
        Text("New Sale", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        SarimToggleGroup(
          options = SalesMode.entries,
          selected = mode,
          labelOf = { it.label },
          onSelect = { mode = it },
          testTagPrefix = "sales_mode",
        )
        SarimDatePickerField("Date", dateEpoch, onDateSelected = { dateEpoch = it }, testTag = "cust_date")

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
          Surface(shape = RoundedCornerShape(12.dp), color = palette.surfaceElevated, border = BorderStroke(1.dp, palette.amber.copy(alpha = 0.45f))) {
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

        if (mode == SalesMode.SALE) {
          SarimFieldLabel("Sales Representative (For Calculator Link)")
          Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repOptions.forEach { rep ->
              val sel = rep == selectedRep
              Surface(
                shape = RoundedCornerShape(50),
                color = if (sel) palette.green.copy(alpha = 0.16f) else palette.surfaceElevated,
                border = BorderStroke(1.dp, if (sel) palette.green else palette.border),
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { selectedRep = rep },
              ) {
                Text(rep, color = if (sel) palette.green else palette.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
              }
            }
          }

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

          // Sale Result Card
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Total Cost", SarimFormat.formatRs(totalCost, false, hideMoney), Modifier.weight(1f), palette.red)
            SarimStatTile("Total Value", SarimFormat.formatRs(totalValue, false, hideMoney), Modifier.weight(1f), palette.green)
            SarimStatTile("Profit", SarimFormat.formatRs(profit, false, hideMoney), Modifier.weight(1f), palette.emerald)
          }

          // Stock availability tile
          val stockTone = if (overStock || inStockKg <= 0.0) palette.red else palette.green
          Surface(
            shape = RoundedCornerShape(12.dp),
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
        } else {
          // Collection mode
          val amtRec = SarimFormat.parseSafeDouble(amountReceivedText) ?: 0.0
          val currentDebt = matchedCustomer?.currentCredit ?: 0.0
          val afterDebt = currentDebt - amtRec
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

        SarimPrimaryButton(
          text = "Save Transaction",
          testTag = "btn_save_cust_transaction",
          onClick = {
            if (SarimFormat.isFutureDate(dateEpoch)) {
              viewModel.showDialog(AppDialogRequest("Invalid Date", "Future dates are refused.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (customerName.isBlank() || customerName.length > 80) {
              viewModel.showDialog(AppDialogRequest("Customer Required", "Enter a valid customer name (max 80 characters).", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (isNewCustomer && newCustPhone.isNotBlank() && !SarimFormat.isValidPhone(newCustPhone)) {
              viewModel.showDialog(AppDialogRequest("Invalid Phone", "Phone number must have 7 to 15 digits.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (mode == SalesMode.SALE) {
              if (qtyNum !in 0.001..1_000_000.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Quantity", "Quantity must be between 0.001 and 1,000,000 kg.", confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              viewModel.launchSafely(
                errorTitle = "Save Sale Failed",
                successMessage = "Sale saved",
                onSuccess = { quantityText = ""; customerName = ""; newCustPhone = "" },
              ) {
                viewModel.repository.saveSaleOrCollection(
                  editingId = null,
                  dateEpoch = dateEpoch,
                  mode = "SALE",
                  customerName = customerName,
                  newCustomerPhone = newCustPhone,
                  repName = selectedRep,
                  store = store,
                  quantityKg = qtyNum,
                  costPerKg = unitCostKg,
                  paymentType = paymentChoice.name,
                  amountReceived = if (paymentChoice == PaymentChoice.CASH) totalValue else 0.0,
                  expiredQtyKg = 0.0,
                  returnedQtyKg = 0.0,
                  addToLedger = paymentChoice == PaymentChoice.CASH && saleAddToLedger,
                  isRepSale = false,
                )
              }
            } else {
              val amtRec = SarimFormat.parseSafeDouble(amountReceivedText) ?: 0.0
              val expQty = SarimFormat.parseSafeDouble(expiredQtyText) ?: 0.0
              if (amtRec <= 0.0 && expQty <= 0.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Collection", "Enter amount received or expired qty.", confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              val commitCollection: () -> Unit = {
                viewModel.launchSafely(
                  errorTitle = "Save Collection Failed",
                  successMessage = "Collection saved",
                  onSuccess = { amountReceivedText = ""; expiredQtyText = ""; customerName = ""; newCustPhone = "" },
                ) {
                  viewModel.repository.saveSaleOrCollection(
                    editingId = null,
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
              val currentDebt = matchedCustomer?.currentCredit ?: 0.0
              if (amtRec > currentDebt + 0.01) {
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Over-Collection Warning",
                    body = "Amount received exceeds customer's current credit.\nProceed only if this is an advance payment.",
                    highlight = DialogHighlight("Balance After", SarimFormat.formatRs(currentDebt - amtRec)),
                    confirmLabel = "Proceed",
                    onConfirm = { commitCollection() },
                  )
                )
              } else {
                commitCollection()
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
            ) { Icon(Icons.Filled.PictureAsPdf, contentDescription = "Export customers PDF", tint = palette.cyan) }
            IconButton(onClick = { viewModel.openStandalone(StandaloneScreen.CustomerEdit(null)) }) {
              Icon(Icons.Filled.Add, contentDescription = "Add customer", tint = palette.green)
            }
          }
        }
        SarimTextField(
          value = custSearchFilter,
          onValueChange = { custSearchFilter = it },
          label = "",
          placeholder = "Search customers...",
          leadingIcon = Icons.Filled.Search,
          testTag = "customer_filter",
        )
        if (filteredCusts.isEmpty()) {
          SarimEmptyState("No customers yet", "Add a customer or save a sale to create one.")
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            filteredCusts.forEach { c ->
              val avColor = avatarColorForName(c.name)
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.surfaceElevated,
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
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

    // 3. Analytics & Period Summary
    if (state.isAdmin) {
      item {
        val cutoff = SarimMetricsEngine.periodStart(analyticsPeriod)
        val periodSales = state.sales.filter { !it.isRepSale && it.mode == "SALE" && it.dateEpoch >= cutoff }
        SarimCard {
          Text("Analytics", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
      val periodSales = state.sales.filter { !it.isRepSale && it.mode == "SALE" && it.dateEpoch >= cutoff }
      val periodColls = state.sales.filter { !it.isRepSale && it.mode == "COLLECTION" && it.dateEpoch >= cutoff }
      val totalVal = periodSales.sumOf { it.totalValue }
      val totalProfit = periodSales.sumOf { it.profit }
      val cashIncl = periodSales.filter { it.paymentType == "CASH" }.sumOf { it.totalValue } + periodColls.sumOf { it.amountReceived }
      val pendingCredits = periodSales.filter { it.paymentType == "CREDIT" }.sumOf { it.totalValue }

      SarimCard {
        SarimToggleGroup(TimePeriod.entries, summaryPeriod, { it.label }, { summaryPeriod = it })
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("${summaryPeriod.label} Sales", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
        val isCash = s.paymentType == "CASH" || s.mode == "COLLECTION"
        HistoryRecordDisplay(
          item = s,
          dateEpoch = s.dateEpoch,
          title = s.customerName,
          subtitle = if (s.mode == "SALE") "${SarimFormat.formatKg(s.quantityKg)} · ${s.storeName}" else s.mode,
          amountText = SarimFormat.formatRs(s.totalValue, true, hideMoney),
          amountColor = if (isCash) palette.green else palette.amber,
          chipText = if (s.mode == "SALE") (if (s.paymentType == "CASH") "PAID" else "PENDING") else s.mode,
          chipColor = if (isCash) palette.green else palette.amber,
        )
      }
      AccordionHistoryList(
        records = records,
        yearSummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.quantityKg }) },
        monthSummary = { items -> "${SarimFormat.formatKg(items.sumOf { it.item.quantityKg })} sold · ${SarimFormat.formatRs(items.sumOf { it.item.totalValue }, false, hideMoney)}" },
        daySummary = { items -> "${items.size} entries" },
      ) { rec ->
        val s = rec.item
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimSecondaryButton(
            text = "Invoice",
            icon = Icons.Filled.Share,
            modifier = Modifier.weight(1f),
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
                  "Total Value: ${SarimFormat.formatRs(s.totalValue)}",
                ),
              ).onFailure { err ->
                viewModel.postStatus(err.localizedMessage ?: "Unable to share invoice")
              }
            },
          )
          SarimDangerButton(
            text = "Delete",
            icon = Icons.Filled.Delete,
            modifier = Modifier.weight(1f),
            onClick = {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Delete Transaction?",
                  body = "Customer: ${s.customerName}\nAmount: ${SarimFormat.formatRs(s.totalValue)}\nMoves to Recycle Bin for 90 days.",
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
    }
  }
}
