package com.example.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StandaloneScreen
import com.example.data.model.TimePeriod
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.theme.avatarColorForName
import com.example.util.AppDialogRequest
import com.example.util.PdfExporter
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.launch

private enum class RepTxMode(val label: String) { SALE("Sale"), COLLECTION("Collection") }
private enum class RepPayType(val label: String) { CREDIT("Credit"), CASH("Cash") }

@Composable
fun RepSalesTab(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val context = LocalContext.current
  val palette = LocalSarimPalette.current
  val snap = state.snapshot

  var mode by remember { mutableStateOf(RepTxMode.SALE) }
  var dateEpoch by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var customerName by remember { mutableStateOf("") }
  var newCustPhone by remember { mutableStateOf("") }
  var quantityText by remember { mutableStateOf("") }
  var payType by remember { mutableStateOf(RepPayType.CREDIT) }
  var amountReceivedText by remember { mutableStateOf("") }
  var expiredQtyText by remember { mutableStateOf("") }

  var adminViewRep by remember(snap?.repsList) { mutableStateOf(snap?.repsList?.firstOrNull() ?: "—") }
  var adminMapDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var analyticsPeriod by remember { mutableStateOf(TimePeriod.MONTHLY) }
  var custFilter by remember { mutableStateOf("") }

  val repCustomers = remember(state.customers) { state.customers.filter { it.isRepCustomer } }
  val matchedCust = remember(customerName, repCustomers) {
    repCustomers.firstOrNull { it.name.equals(customerName.trim(), ignoreCase = true) }
  }
  val isNewCust = customerName.isNotBlank() && matchedCust == null
  val defaultStore = state.stores.firstOrNull()
  val qtyNum = SarimFormat.parseSafeDouble(quantityText) ?: 0.0
  val txValue = if (mode == RepTxMode.SALE) qtyNum * (defaultStore?.salePricePerKg ?: 400.0) else (SarimFormat.parseSafeDouble(amountReceivedText) ?: 0.0)

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item { ShimmerHeading("Rep Sales", fontSizeSp = 26) }

    // 1. New Transaction Card
    item {
      SarimCard {
        Text("New Transaction", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        SarimToggleGroup(RepTxMode.entries, mode, { it.label }, { mode = it })
        SarimDatePickerField("Date", dateEpoch, onDateSelected = { dateEpoch = it }, testTag = "rep_date")
        SarimSearchFieldWithDropdown(
          value = customerName,
          onValueChange = { customerName = it },
          label = "Customer Name",
          placeholder = "Search customer...",
          suggestions = repCustomers.map { it.name },
          onSuggestionSelected = { customerName = it },
          testTag = "rep_cust_name",
        )
        if (isNewCust) {
          SarimTextField(newCustPhone, { newCustPhone = it }, "Mobile number", placeholder = "03XX-XXXXXXX", keyboardType = KeyboardType.Phone)
        }
        if (matchedCust != null) {
          SarimStatTile("Current Debt", SarimFormat.formatRs(matchedCust.currentCredit), Modifier.fillMaxWidth(), palette.amber)
        }
        if (mode == RepTxMode.SALE) {
          SarimTextField(quantityText, { quantityText = it }, "Quantity (kg)", placeholder = "0.000", suffixText = "kg", keyboardType = KeyboardType.Decimal)
          SarimFieldLabel("Payment Type")
          SarimToggleGroup(RepPayType.entries, payType, { it.label }, { payType = it }, colorOf = { if (it == RepPayType.CASH) palette.green else palette.amber })
        } else {
          SarimTextField(amountReceivedText, { amountReceivedText = it }, "Amount Received (₨)", placeholder = "Cash received from customer", prefixText = "Rs", keyboardType = KeyboardType.Decimal)
          SarimTextField(expiredQtyText, { expiredQtyText = it }, "Expired / Damage Qty (kg)", placeholder = "0", suffixText = "kg", keyboardType = KeyboardType.Decimal)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(if (mode == RepTxMode.SALE) "Transaction Value:" else "Amount Collected:", color = palette.textMuted, fontSize = 13.sp)
          Text(SarimFormat.formatRs(txValue), color = palette.green, style = TabularNumberStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
        SarimPrimaryButton(
          text = "Save Transaction",
          onClick = {
            if (SarimFormat.isFutureDate(dateEpoch) || customerName.isBlank()) {
              viewModel.showDialog(AppDialogRequest("Invalid Input", "Enter a valid non-future date and customer name.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            val amtRec = SarimFormat.parseSafeDouble(amountReceivedText) ?: 0.0
            val expQty = SarimFormat.parseSafeDouble(expiredQtyText) ?: 0.0
            if (mode == RepTxMode.SALE && qtyNum !in 0.001..1_000_000.0) {
              viewModel.showDialog(AppDialogRequest("Invalid Quantity", "Quantity must be between 0.001 and 1,000,000 kg.", confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            viewModel.launchSafely(
              errorTitle = "Save Rep Transaction Failed",
              successMessage = "Rep transaction saved",
              onSuccess = { customerName = ""; newCustPhone = ""; quantityText = ""; amountReceivedText = ""; expiredQtyText = "" },
            ) {
              viewModel.repository.saveSaleOrCollection(
                editingId = null,
                dateEpoch = dateEpoch,
                mode = mode.name,
                customerName = customerName,
                newCustomerPhone = newCustPhone,
                repName = state.signedInDisplayName,
                store = defaultStore,
                quantityKg = if (mode == RepTxMode.SALE) qtyNum else 0.0,
                costPerKg = defaultStore?.let { snap?.storeMetrics?.get(it.storeKey)?.avgCostPerKg } ?: 0.0,
                paymentType = payType.name,
                amountReceived = if (mode == RepTxMode.COLLECTION) amtRec else if (payType == RepPayType.CASH) txValue else 0.0,
                expiredQtyKg = expQty,
                returnedQtyKg = 0.0,
                addToLedger = false,
                isRepSale = true,
              )
            }
          },
        )
      }
    }

    // 2. Admin View Card (Map + Analytics)
    if (state.isAdmin) {
      item {
        val repOpts = listOf("—") + (snap?.repsList ?: emptyList())
        val mapStart = SarimFormat.startOfDay(adminMapDate)
        val mapEnd = SarimFormat.endOfDay(adminMapDate)
        val dayPins = state.sales.filter {
          it.isRepSale && it.dateEpoch in mapStart..mapEnd && (adminViewRep == "—" || it.repName.equals(adminViewRep, ignoreCase = true))
        }

        SarimCard {
          Text("Admin View", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimPopoverSelector("View For:", adminViewRep, repOpts, { _, o -> adminViewRep = o }, modifier = Modifier.weight(1f))
            SarimDatePickerField("Select Date", adminMapDate, onDateSelected = { adminMapDate = it }, modifier = Modifier.weight(1f))
          }

          // Map pin plot canvas (offline-safe surface showing saved location pins)
          Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF0A2218), border = BorderStroke(1.dp, palette.green.copy(alpha = 0.35f))) {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
              Canvas(modifier = Modifier.fillMaxSize()) {
                for (i in 1..4) {
                  val y = size.height * (i / 5f)
                  drawLine(palette.green.copy(alpha = 0.12f), Offset(0f, y), Offset(size.width, y))
                  val x = size.width * (i / 5f)
                  drawLine(palette.green.copy(alpha = 0.12f), Offset(x, 0f), Offset(x, size.height))
                }
                dayPins.forEachIndexed { idx, s ->
                  val px = ((idx + 1).toFloat() / (dayPins.size + 1)) * size.width
                  val py = size.height * (0.3f + ((idx * 37) % 50) / 100f)
                  val pinColor = when {
                    s.mode == "COLLECTION" -> palette.cyan
                    s.paymentType == "CASH" -> palette.green
                    else -> palette.amber
                  }
                  drawCircle(pinColor, radius = 7.dp.toPx(), center = Offset(px, py))
                }
              }
              if (dayPins.isEmpty()) {
                Text("No pinned field visits on ${SarimFormat.formatDate(adminMapDate)}", color = palette.green.copy(alpha = 0.7f), fontSize = 12.sp)
              }
            }
          }
          Text("Cash Sale (Green) | Collection (Cyan) | Credit Sale (Amber)", color = palette.textMuted, fontSize = 11.sp)
        }
      }

      item {
        val cutoff = SarimMetricsEngine.periodStart(analyticsPeriod)
        val repTxPeriod = state.sales.filter { it.isRepSale && it.dateEpoch >= cutoff }
        val cashSales = repTxPeriod.filter { it.mode == "SALE" && it.paymentType == "CASH" }.sumOf { it.totalValue }
        val credSales = repTxPeriod.filter { it.mode == "SALE" && it.paymentType == "CREDIT" }.sumOf { it.totalValue }
        val colls = repTxPeriod.filter { it.mode == "COLLECTION" }.sumOf { it.amountReceived }
        val moneyIn = cashSales + colls

        SarimCard {
          Text("Analytics", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          SarimToggleGroup(TimePeriod.entries, analyticsPeriod, { it.label }, { analyticsPeriod = it })
          Text("Money in: ${SarimFormat.formatRs(moneyIn)}", color = palette.green, style = TabularNumberStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold))
          SplitRatioBar("Cash + Coll", moneyIn, palette.green, "Credit Sales", credSales, palette.amber)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Collections", SarimFormat.formatRs(colls, false), Modifier.weight(1f), palette.cyan)
            SarimStatTile("Cash sales", SarimFormat.formatRs(cashSales, false), Modifier.weight(1f), palette.green)
            SarimStatTile("Credit sales", SarimFormat.formatRs(credSales, false), Modifier.weight(1f), palette.amber)
          }
        }
      }
    }

    // 3. Customer List
    item {
      val filtered = repCustomers.filter { custFilter.isBlank() || it.name.contains(custFilter, ignoreCase = true) }
      SarimCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Text("Customer List", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Row {
            IconButton(
              onClick = {
                val lines = filtered.map { "${it.name} | ${it.phone} | Debt: ${SarimFormat.formatRs(it.currentCredit)}" }
                PdfExporter.sharePdfReport(context, "rep_customers", "Rep Customers", "${filtered.size} customers", lines)
                  .onFailure { err -> viewModel.postStatus(err.localizedMessage ?: "Unable to export PDF") }
              },
            ) { Icon(Icons.Filled.PictureAsPdf, contentDescription = "Export rep customers PDF", tint = palette.cyan) }
            IconButton(onClick = { viewModel.openStandalone(StandaloneScreen.RepCustomerEdit(null)) }) {
              Icon(Icons.Filled.Add, contentDescription = "Add rep customer", tint = palette.green)
            }
          }
        }
        SarimTextField(value = custFilter, onValueChange = { custFilter = it }, label = "", placeholder = "Search customers...", leadingIcon = Icons.Filled.Search)
        if (filtered.isEmpty()) {
          SarimEmptyState("No customers yet", "Save a rep transaction or tap + to add a rep customer.")
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            filtered.forEach { c ->
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.surfaceElevated,
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                  viewModel.openStandalone(StandaloneScreen.RepCustomerDetails(c.id))
                },
              ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  InitialsAvatar(c.name, avatarColorForName(c.name), badgeText = c.ledgerPage)
                  Column(modifier = Modifier.weight(1f)) {
                    Text(c.name.uppercase(), color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Text(c.phone.ifBlank { "No phone" }, color = palette.textMuted, fontSize = 11.5.sp)
                  }
                  Text(SarimFormat.formatRs(c.currentCredit), color = if (c.currentCredit > 0) palette.amber else palette.green, style = TabularNumberStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold))
                }
              }
            }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimStatTile("Outstanding credit", SarimFormat.formatRs(repCustomers.sumOf { it.currentCredit }, false), Modifier.weight(1f), palette.amber)
          SarimStatTile("Credit sales", SarimFormat.formatRs(repCustomers.sumOf { it.totalSaleValue }, false), Modifier.weight(1f), palette.cyan)
          SarimStatTile("Collections", SarimFormat.formatRs(repCustomers.sumOf { it.totalReceived }, false), Modifier.weight(1f), palette.green)
        }
      }
    }

    // 4. Rep History Accordion
    item {
      Text("Rep Sales History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      Spacer(Modifier.height(6.dp))
      val repHistory = state.sales.filter { it.isRepSale }
      val records = repHistory.map { s ->
        HistoryRecordDisplay(
          item = s,
          dateEpoch = s.dateEpoch,
          title = s.customerName,
          subtitle = "${s.mode} · ${SarimFormat.formatKg(s.quantityKg)}",
          amountText = SarimFormat.formatRs(s.totalValue),
          amountColor = if (s.paymentType == "CASH" || s.mode == "COLLECTION") palette.green else palette.amber,
        )
      }
      AccordionHistoryList(
        records = records,
        yearSummary = { "${it.size} entries" },
        monthSummary = { "${it.size} entries" },
        daySummary = { "${it.size} entries" },
      ) { rec ->
        Text("Rep: ${rec.item.repName} · Balance after: ${SarimFormat.formatRs(rec.item.balanceAfter)}", color = palette.textMuted, fontSize = 12.sp)
      }
    }
  }
}
