package com.example.ui.tabs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Search
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
import com.example.data.backup.YearCloseMerge
import com.example.data.local.SaleTransactionEntity
import com.example.data.model.StandaloneScreen
import com.example.data.model.TimePeriod
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.theme.avatarColorForName
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
  val hideMoney = state.hideMoneyFigures

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
  var editingRepTxId by remember { mutableStateOf<String?>(null) }
  var sheetRepSale by remember { mutableStateOf<SaleTransactionEntity?>(null) }

  val repCustomers = remember(state.customers) { state.customers.filter { it.isRepCustomer } }
  val matchedCust = remember(customerName, repCustomers) {
    repCustomers.firstOrNull { it.name.equals(customerName.trim(), ignoreCase = true) }
  }
  val isNewCust = customerName.isNotBlank() && matchedCust == null
  val defaultStore = state.stores.firstOrNull()
  val unitPrice = matchedCust?.customSalePrice ?: (defaultStore?.salePricePerKg ?: 0.0)
  val costPerKg = defaultStore?.let { snap?.storeMetrics?.get(it.storeKey)?.avgCostPerKg } ?: 0.0
  val qtyNum = SarimFormat.parseSafeDouble(quantityText) ?: 0.0
  val txValue = if (mode == RepTxMode.SALE) qtyNum * unitPrice else (SarimFormat.parseSafeDouble(amountReceivedText) ?: 0.0)

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item { ShimmerHeading("Rep Sales", fontSizeSp = 26) }

    // 1. New Transaction Card
    item {
      SarimCard {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column {
            Text(
              if (editingRepTxId == null) "New Transaction" else "Edit Transaction",
              color = palette.textPrimary,
              fontWeight = FontWeight.Bold,
              fontSize = 16.sp,
            )
            if (editingRepTxId != null) {
              val origRep = state.sales.firstOrNull { it.id == editingRepTxId }
              val hasEdits = origRep == null ||
                customerName.trim() != origRep.customerName ||
                quantityText != origRep.quantityKg.toString() ||
                (SarimFormat.parseSafeDouble(amountReceivedText) ?: 0.0) != origRep.amountReceived ||
                (SarimFormat.parseSafeDouble(expiredQtyText) ?: 0.0) != origRep.expiredQtyKg
              TextButton(
                onClick = {
                  viewModel.requestCancelEdit(hasEdits) {
                    editingRepTxId = null; customerName = ""; newCustPhone = ""; quantityText = ""; amountReceivedText = ""; expiredQtyText = ""
                  }
                },
                contentPadding = PaddingValues(0.dp),
              ) { Text("Cancel Edit", color = palette.amber, fontSize = 12.sp) }
            }
          }
          Box(modifier = Modifier.width(184.dp)) {
            SarimToggleGroup(RepTxMode.entries, mode, { it.label }, { mode = it })
          }
        }
        if (editingRepTxId != null) {
          SarimCallout("Editing entry · Saving updates the original record.", palette.amber)
        }
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
          SarimStatTile("Current Debt", SarimFormat.formatRs(matchedCust.currentCredit, true, hideMoney), Modifier.fillMaxWidth(), palette.amber)
        }
        if (mode == RepTxMode.SALE) {
          SarimTextField(quantityText, { quantityText = it }, "Quantity (kg)", placeholder = "0.000", suffixText = "kg", keyboardType = KeyboardType.Decimal)
          SarimFieldLabel("Payment Type")
          SarimToggleGroup(RepPayType.entries, payType, { it.label }, { payType = it }, colorOf = { if (it == RepPayType.CASH) palette.green else palette.amber })
        } else {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            SarimTextField(amountReceivedText, { amountReceivedText = it }, "Amount Received (₨)", modifier = Modifier.weight(1f), placeholder = "0.00", prefixText = "Rs", keyboardType = KeyboardType.Decimal)
            SarimTextField(expiredQtyText, { expiredQtyText = it }, "Expired / Damage Qty (kg)", modifier = Modifier.weight(1f), placeholder = "0", suffixText = "kg", keyboardType = KeyboardType.Decimal)
          }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(if (mode == RepTxMode.SALE) "Transaction Value:" else "Amount Collected:", color = palette.textMuted, fontSize = 13.sp)
          Text(SarimFormat.formatRs(txValue, true, hideMoney), color = palette.green, style = TabularNumberStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
        SarimPrimaryButton(
          text = if (editingRepTxId == null) "Save Transaction" else "Update Transaction",
          onClick = {
            if (SarimFormat.isFutureDate(dateEpoch) || customerName.isBlank()) {
              viewModel.showDialog(AppDialogRequest("Invalid Input", "Enter a valid non-future date and customer name.", tone = DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            if (isNewCust && newCustPhone.isNotBlank() && !SarimFormat.isValidPhone(newCustPhone)) {
              viewModel.showDialog(AppDialogRequest("Invalid Phone", "Phone number must have 7 to 15 digits.", tone = DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))
              return@SarimPrimaryButton
            }
            val amtRec = SarimFormat.parseSafeDouble(amountReceivedText) ?: 0.0
            val expQty = SarimFormat.parseSafeDouble(expiredQtyText) ?: 0.0
            if (mode == RepTxMode.SALE) {
              if (qtyNum !in 0.001..1_000_000.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Quantity", "Quantity must be between 0.001 and 1,000,000 kg.", tone = DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (defaultStore == null || unitPrice <= 0.0) {
                viewModel.showDialog(AppDialogRequest("Sale Price Missing", "Sale price not configured. Set prices in Store Manager / Factory Formulas before recording rep sales.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (costPerKg < 0.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Cost Price", "Invalid cost price detected. Please check Factory Formulas.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
            }
            val currentEditId = editingRepTxId
            val origRep = currentEditId?.let { id -> state.sales.firstOrNull { it.id == id } }
            val effectiveUnitPrice = if (origRep != null && origRep.pricePerKg > 0.0) origRep.pricePerKg else unitPrice
            val effectiveCostPerKg = if (origRep != null && origRep.quantityKg > 0.0) (origRep.totalCost / origRep.quantityKg) else costPerKg
            val effectiveTotalVal = SarimLinkEngine.round2(qtyNum * effectiveUnitPrice)
            val computedProfit = SarimLinkEngine.round2(effectiveTotalVal - (qtyNum * effectiveCostPerKg))
            if (mode == RepTxMode.SALE && computedProfit < -0.01) {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Loss Sale Blocked",
                  body = viewModel.redactMoney(
                    "This sale would result in a loss of ${SarimFormat.formatRs(kotlin.math.abs(computedProfit))}. Check sale price vs cost price in Factory Formulas.",
                    "This sale cannot be saved at the current price. Please contact the admin.",
                  ),
                  tone = DialogTone.WARNING,
                  confirmLabel = "OK",
                  cancelLabel = null,
                )
              )
              return@SarimPrimaryButton
            }
            val commitRepTx: (String?) -> Unit = { repWarn ->
              viewModel.confirmSave(
                "Rep Sale",
                listOf("Date" to SarimFormat.formatDate(dateEpoch), "Customer" to customerName, "Quantity" to quantityText, "Collected" to amountReceivedText, "Expired (kg)" to expiredQtyText),
                isUpdate = currentEditId != null,
                warning = repWarn,
                shareAfterSave = ShareAfterSave(StatementKind.REP, customerName, dateEpoch),
              ) {
                viewModel.launchSafely(
                  errorTitle = "Save Rep Transaction Failed",
                  successMessage = if (currentEditId == null) "Rep transaction saved" else "Rep transaction updated",
                  onSuccess = { editingRepTxId = null; customerName = ""; newCustPhone = ""; quantityText = ""; amountReceivedText = ""; expiredQtyText = "" },
                ) {
                  viewModel.repository.saveSaleOrCollection(
                    editingId = currentEditId,
                    dateEpoch = dateEpoch,
                    mode = mode.name,
                    customerName = customerName,
                    newCustomerPhone = newCustPhone,
                    repName = state.signedInDisplayName,
                    store = defaultStore,
                    quantityKg = if (mode == RepTxMode.SALE) qtyNum else 0.0,
                    costPerKg = effectiveCostPerKg,
                    paymentType = payType.name,
                    amountReceived = if (mode == RepTxMode.COLLECTION) amtRec else if (payType == RepPayType.CASH) effectiveTotalVal else 0.0,
                    expiredQtyKg = expQty,
                    returnedQtyKg = 0.0,
                    addToLedger = false,
                    isRepSale = true,
                  )
                }
              }
            }
            if (mode == RepTxMode.COLLECTION) {
              if (amtRec <= 0.0 && expQty <= 0.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Collection", "Enter amount received or expired qty.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              val currentDebt = SarimLinkEngine.partyOutstanding(
                name = customerName.trim(),
                sales = state.sales,
                isRepProfile = true,
                settlements = state.settlements,
                excludeSaleId = currentEditId,
              )
              if (currentEditId == null && amtRec > 0.0 && currentDebt <= 0.01) {
                viewModel.showDialog(AppDialogRequest("No Outstanding Balance", "${customerName.trim()} has no pending credit balance to collect.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (origRep != null && origRep.expiredQtyKg > expQty + 1e-4) {
                val choraStock = state.rawMaterials.firstOrNull { !it.isDeleted && it.name.equals("CHORA", ignoreCase = true) }?.stockKg ?: 0.0
                val choraDrop = origRep.expiredQtyKg - expQty
                if (choraStock + 1e-6 < choraDrop) {
                  viewModel.showDialog(AppDialogRequest("CHORA Stock Used", "Cannot reduce expired quantity by ${SarimFormat.formatKg(choraDrop)}: only ${SarimFormat.formatKg(choraStock)} of CHORA remains unused.", tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
                  return@SarimPrimaryButton
                }
              }
              val expUnit = SarimLinkEngine.goodsCreditUnitPrice(customerName.trim(), state.sales, unitPrice, isRepProfile = true, excludeSaleId = currentEditId)
              val expPlan = SarimLinkEngine.planGoodsCredit(expQty, expUnit, (currentDebt - amtRec).coerceAtLeast(0.0))
              val notes = buildList {
                if (amtRec > currentDebt + 0.01) {
                  add(viewModel.redactMoney(
                    "Owes: ${SarimFormat.formatRs(currentDebt)}\nCollecting: ${SarimFormat.formatRs(amtRec)}\nOverpayment: ${SarimFormat.formatRs(amtRec - currentDebt)}\n\nProceed only if this is an advance payment.",
                    "Collection exceeds outstanding balance. Proceed only if this is an advance payment.",
                  ))
                }
                if (expQty > 0.0) {
                  add("Expired ${SarimFormat.formatKg(expQty)} will be added to CHORA raw material.")
                  if (expPlan.value > 0.0) {
                    add(viewModel.redactMoney(
                      "Expired goods credited: ${SarimFormat.formatRs(expPlan.value)} will be deducted from ${customerName.trim()}'s balance.",
                      "Expired goods credit will be deducted from customer's balance.",
                    ))
                  }
                }
              }
              commitRepTx(notes.takeIf { it.isNotEmpty() }?.joinToString("\n"))
            } else {
              val existingDebt = if (payType == RepPayType.CREDIT) {
                SarimLinkEngine.partyOutstanding(customerName.trim(), state.sales, isRepProfile = true, settlements = state.settlements, excludeSaleId = currentEditId)
              } else 0.0
              val creditWarn = if (existingDebt > 5000.0) {
                viewModel.redactMoney(
                  "Unpaid balance: ${SarimFormat.formatRs(existingDebt)}\nThis credit sale: ${SarimFormat.formatRs(effectiveTotalVal)}\nNew total: ${SarimFormat.formatRs(existingDebt + effectiveTotalVal)}\n\n${customerName.trim()} already has unpaid credit. Collect it before adding more.",
                  "Check this credit sale before saving.",
                )
              } else null
              commitRepTx(creditWarn)
            }
          },
        )
      }
    }

    // 2. Admin View Card (Map + Analytics)
    if (state.showRepAdminView) {
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
          Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF0A2218), border = BorderStroke(1.dp, palette.green.copy(alpha = 0.35f))) {
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
        val colls = repTxPeriod.filter { it.mode == "COLLECTION" }.sumOf { it.amountReceived.takeIf { a -> a > 0.0 } ?: it.totalValue }
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
                val repLabel = state.signedInDisplayName.ifBlank { "Rep" }
                com.example.util.CustomerListPdf.create(
                  context,
                  fileName = "My_Customers_${repLabel.replace(Regex("[^A-Za-z0-9]"), "_")}.pdf",
                  title = "My Customers — $repLabel",
                  rows = com.example.util.CustomerListPdf.rowsFor(repCustomers, state.sales),
                ).fold(
                  onSuccess = { f ->
                    if (com.example.util.DirectShare.shareViaChooser(context, f, "application/pdf", "My Customers — $repLabel") == com.example.util.ShareOutcome.FAILED) {
                      viewModel.postStatus("Unable to share the customer list")
                    }
                  },
                  onFailure = { err -> viewModel.postStatus(err.localizedMessage ?: "Unable to export PDF") },
                )
              },
            ) { Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Export rep customers PDF", tint = palette.cyan) }
            IconButton(onClick = { viewModel.openStandalone(StandaloneScreen.RepCustomerEdit(null)) }) {
              Icon(Icons.Rounded.Add, contentDescription = "Add rep customer", tint = palette.green)
            }
          }
        }
        SarimTextField(value = custFilter, onValueChange = { custFilter = it }, label = "", placeholder = "Search customers...", leadingIcon = Icons.Rounded.Search)
        SarimRecordTable(
          rows = filtered.map { c ->
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
              amount = SarimFormat.formatRs(c.currentCredit),
              amountColor = if (c.currentCredit > 1.0) palette.amber else palette.emerald,
              onClick = { viewModel.openStandalone(StandaloneScreen.RepCustomerDetails(c.id)) },
            )
          },
          stats = listOf(
            RecordStat("Outstanding credit", SarimFormat.formatRs(repCustomers.sumOf { it.currentCredit }, false), palette.red),
            RecordStat("Credit sales", SarimFormat.formatRs(repCustomers.sumOf { it.totalSaleValue }, false)),
            RecordStat("Collections", SarimFormat.formatRs(repCustomers.sumOf { it.totalReceived }, false), palette.emerald),
          ),
          emptyTitle = if (custFilter.isBlank()) "No customers yet" else "No customers match your search",
          emptyHint = if (custFilter.isBlank()) "Save a rep transaction or tap + to add a rep customer." else "Try a different name.",
        )
      }
    }

    // 4. Rep History Accordion
    item {
      Text("Rep Sales History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      Spacer(Modifier.height(6.dp))
      val repHistory = state.sales.filter { it.isRepSale }
      val records = repHistory.map { s ->
        val isCash = s.paymentType == "CASH" || s.mode == "COLLECTION"
        val dispAmt = if (s.mode == "COLLECTION" || s.mode == "MONEY_OUT") (s.amountReceived.takeIf { it > 0.0 } ?: s.totalValue) else s.totalValue
        HistoryRecordDisplay(
          item = s,
          dateEpoch = s.dateEpoch,
          title = s.customerName,
          subtitle = "${s.mode} · ${SarimFormat.formatKg(s.quantityKg)}",
          amountText = SarimFormat.formatRs(dispAmt),
          amountColor = if (isCash) palette.green else palette.amber,
          chipText = if (s.mode == "SALE") (if (s.paymentType == "CASH") "PAID" else "PENDING") else s.mode,
          chipColor = if (isCash || s.creditReceived) palette.green else palette.amber,
          showStripe = !s.creditReceived && s.mode == "SALE" && s.paymentType == "CREDIT",
          detailMetrics = buildList {
            add("Mode" to s.mode)
            add("Rep" to s.repName.ifBlank { "Rep" })
            if (s.mode == "SALE") {
              add("Quantity" to SarimFormat.formatKg(s.quantityKg))
              add("Rate / kg" to SarimFormat.formatRs(s.pricePerKg))
              add("Total Value" to SarimFormat.formatRs(s.totalValue))
              add("Payment" to s.paymentType)
            } else {
              add("Received" to SarimFormat.formatRs(s.amountReceived))
              if (s.expiredQtyKg > 0.0) add("Expired Qty" to SarimFormat.formatKg(s.expiredQtyKg))
            }
            add("Balance After" to SarimFormat.formatRs(s.balanceAfter))
          },
        )
      }
      AccordionHistoryList(
        records = records,
        yearSummary = { "" },
        monthSummary = { "" },
        daySummary = { "" },
        onItemClick = { rec -> sheetRepSale = rec.item },
      )
    }
  }

  sheetRepSale?.let { s ->
    val isCash = s.paymentType == "CASH" || s.mode == "COLLECTION"
    val sheetDispAmt = if (s.mode == "COLLECTION" || s.mode == "MONEY_OUT") (s.amountReceived.takeIf { it > 0.0 } ?: s.totalValue) else s.totalValue
    SarimRecordBottomSheet(
      title = s.customerName,
      subtitle = "${SarimFormat.formatDate(s.dateEpoch)}${if (s.timeStr.isNotBlank()) " · ${s.timeStr}" else ""}",
      amountText = SarimFormat.formatRs(sheetDispAmt),
      amountColor = if (isCash) palette.green else palette.amber,
      badgeText = if (s.mode == "SALE") (if (s.paymentType == "CASH") "PAID" else if (s.creditReceived) "SETTLED" else "PENDING") else s.mode,
      badgeColor = if (isCash || s.creditReceived) palette.green else palette.amber,
      metrics = buildList {
        add("Mode" to s.mode)
        add("Rep" to s.repName.ifBlank { "Rep" })
        if (s.mode == "SALE") {
          add("Quantity" to SarimFormat.formatKg(s.quantityKg))
          add("Rate / kg" to SarimFormat.formatRs(s.pricePerKg))
          add("Total Value" to SarimFormat.formatRs(s.totalValue))
          add("Payment" to s.paymentType)
        } else {
          add("Amount Received" to SarimFormat.formatRs(sheetDispAmt))
          if (s.expiredQtyKg > 0.0) add("Expired Qty" to SarimFormat.formatKg(s.expiredQtyKg))
        }
        add("Balance After" to SarimFormat.formatRs(s.balanceAfter))
      },
      onDismiss = { sheetRepSale = null },
      extraActionLabel = "Invoice",
      extraActionIcon = Icons.Rounded.Share,
      onExtraAction = {
        val cust = state.customers.firstOrNull { !it.isDeleted && it.isRepCustomer && (it.id == s.customerId || it.name.equals(s.customerName, ignoreCase = true)) }
        val data = cust?.let { StatementData.salesDayInvoice(StatementKind.REP, it, customerSalesOf(state, it), SarimFormat.dateInput(s.dateEpoch), state.hideMoneyFigures) }
        if (data == null) viewModel.postStatus("Nothing to invoice for this day.")
        else StatementData.shareDayInvoice(context, data)?.let { viewModel.postStatus(it) }
      },
      onEdit = {
        val editBlock = when {
          YearCloseMerge.isMergedId(s.id) -> "Merged opening balance records cannot be edited directly."
          !s.usedInCalcId.isNullOrBlank() || s.id in SarimLinkEngine.reconciledRepSalesIds(state.settlements) ->
            "Reconciled Calculator transactions cannot be edited directly. Delete the Calculator settlement first."
          else -> null
        }
        if (editBlock != null) {
          viewModel.showDialog(AppDialogRequest("Action Not Allowed", editBlock, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
        } else {
          editingRepTxId = s.id
          dateEpoch = s.dateEpoch
          customerName = s.customerName
          if (s.mode == "COLLECTION") {
            mode = RepTxMode.COLLECTION
            amountReceivedText = sheetDispAmt.toString()
            expiredQtyText = if (s.expiredQtyKg > 0.0) s.expiredQtyKg.toString() else ""
          } else {
            mode = RepTxMode.SALE
            quantityText = s.quantityKg.toString()
            payType = if (s.paymentType == "CASH") RepPayType.CASH else RepPayType.CREDIT
          }
        }
      },
      onDelete = {
        val blockMsg = when {
          YearCloseMerge.isMergedId(s.id) -> "Merged opening balance records cannot be deleted."
          else -> SarimLinkEngine.getSaleBlockReason(s, state.settlements)
            ?: SarimLinkEngine.getCollectionDeleteBlockReason(s, state.rawMaterials)
        }
        if (blockMsg != null) {
          viewModel.showDialog(AppDialogRequest("Action Not Allowed", blockMsg, tone = DialogTone.WARNING, confirmLabel = "OK", cancelLabel = null))
        } else {
          val isColl = s.mode == "COLLECTION" || s.isCollection
          val payLabel = when {
            isColl -> "Collection"
            s.paymentType == "CREDIT" -> "Credit Sale"
            else -> "Cash Sale"
          }
          val msg = buildString {
            append("Permanently delete this $payLabel?")
            append("\nCustomer: ${s.customerName}")
            append("\nDate: ${SarimFormat.dateInput(s.dateEpoch)}")
            if (isColl) {
              if (sheetDispAmt > 0.0) append("\nAmount: ${SarimFormat.formatRs(sheetDispAmt, false, hideMoney)}")
              if (s.expiredQtyKg > 0.0) append("\nExpired / Damage: ${SarimFormat.formatKg(s.expiredQtyKg)}")
              append("\n\n↩ Deleting this collection will restore the credit balance to this customer.")
              if (s.expiredQtyKg > 0.0 && !s.isRepReturn) append("\n↩ ${SarimFormat.formatKg(s.expiredQtyKg)} will be removed from CHORA raw material.")
            } else {
              append("\nQty: ${SarimFormat.formatKg(s.quantityKg)}")
              append("\nValue: ${SarimFormat.formatRs(sheetDispAmt, false, hideMoney)}")
              if (s.paymentType == "CREDIT") {
                append("\n\n⚠ Deleting removes this credit balance from ${s.customerName}'s ledger.")
              }
            }
            append("\n\nThis cannot be undone.")
          }
          viewModel.showDialog(
            AppDialogRequest(
              title = "Delete $payLabel",
              body = msg,
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
