package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.backup.YearCloseMerge
import com.example.data.local.JsonCodecs
import com.example.data.local.SaleTransactionEntity
import com.example.data.model.StandaloneScreen
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogChoice
import com.example.util.DialogTone
import com.example.util.DirectShare
import com.example.util.InvoiceLine
import com.example.util.PdfExporter
import com.example.util.ShareOutcome
import com.example.util.StatementData
import com.example.util.StatementKind
import com.example.util.StatementShare
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CustomerDetailsScreen(
  customerId: String,
  isRep: Boolean,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val context = LocalContext.current
  val customer = remember(customerId, state.customers) { state.customers.firstOrNull { it.id == customerId } }

  if (customer == null) {
    SarimEmptyState("Customer not found", "Return to the list and pick a valid customer.")
    return
  }

  val custSales = remember(state.sales, customer.id, customer.name, customer.isRepCustomer) {
    state.sales.filter {
      !it.isDeleted && it.isRepSale == customer.isRepCustomer &&
        (it.customerId == customer.id || it.customerName.equals(customer.name, ignoreCase = true))
    }.sortedByDescending { it.dateEpoch }
  }

  var searchQuery by remember { mutableStateOf("") }
  var filterChip by remember { mutableStateOf("All") }
  var menuExpanded by remember { mutableStateOf(false) }
  var showMoneyOutDialog by remember { mutableStateOf(false) }

  val filtered = remember(custSales, searchQuery, filterChip) {
    val q = searchQuery.trim().lowercase()
    custSales.filter { tx ->
      val chipOk = when (filterChip) {
        "Credit" -> tx.mode == "SALE" && tx.paymentType == "CREDIT"
        "Cash" -> tx.mode == "SALE" && tx.paymentType == "CASH"
        "Payments" -> tx.mode == "COLLECTION" || tx.mode == "MONEY_OUT"
        "Unpaid" -> tx.balanceAfter > 0.01
        else -> true
      }
      val qOk = q.isEmpty() || tx.storeName.lowercase().contains(q) || tx.mode.lowercase().contains(q) || tx.totalValue.toString().contains(q)
      chipOk && qOk
    }
  }

  val totalBill = customer.oldDebit + customer.totalSaleValue
  val pctReceived = if (totalBill > 0.01) ((customer.totalReceived / totalBill) * 100).toInt().coerceIn(0, 100) else 0

  val stmtKind = if (customer.isRepCustomer) StatementKind.REP else StatementKind.CUSTOMER
  var showStatementFlow by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Customer Profile Header
    SarimCollapsibleHeaderCard(
      initiallyExpanded = false,
      collapseDescription = customer.name,
      headerContent = {
        InitialsAvatar(customer.name, palette.amber, sizeDp = 46.dp, badgeText = customer.ledgerPage)
        Column(modifier = Modifier.weight(1f)) {
          Text(customer.name, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
          if (customer.phone.isNotBlank()) {
            Text(customer.phone, color = palette.cyan, fontSize = 12.sp)
          }
        }
        Box {
          IconButton(
            onClick = { menuExpanded = !menuExpanded },
            modifier = Modifier.size(48.dp).testTag("customer_options_btn"),
          ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Customer menu", tint = palette.textPrimary)
          }
          DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier
              .clip(RoundedCornerShape(20.dp))
              .background(MaterialTheme.colorScheme.surfaceContainerHigh),
          ) {
            DropdownMenuItem(
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("customer_menu_edit"),
              leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, tint = palette.cyan, modifier = Modifier.size(18.dp)) },
              text = { Text("Edit customer", color = palette.textPrimary, fontWeight = FontWeight.Medium) },
              onClick = {
                menuExpanded = false
                viewModel.openStandalone(if (isRep) StandaloneScreen.RepCustomerEdit(customer.id) else StandaloneScreen.CustomerEdit(customer.id))
              },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            DropdownMenuItem(
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("customer_menu_pdf"),
              leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = palette.green, modifier = Modifier.size(18.dp)) },
              text = { Text("Share statement", color = palette.textPrimary, fontWeight = FontWeight.Medium) },
              onClick = {
                menuExpanded = false
                showStatementFlow = true
              },
            )
          }
        }
      },
    ) {
      if (customer.phone.isNotBlank()) {
        Text(
          "Tap to Call · Long-press for WhatsApp: ${customer.phone}",
          color = palette.cyan,
          fontSize = 13.sp,
          modifier = Modifier.combinedClickable(
            onClick = {
              runCatching {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${customer.phone}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }.onFailure {
                viewModel.postStatus("No dialer app available")
              }
            },
            onLongClick = {
              if (!DirectShare.openWhatsAppChat(context, customer.phone)) viewModel.postStatus("WhatsApp or browser unavailable")
            },
          ),
        )
      } else {
        Text("No phone number saved", color = palette.textMuted, fontSize = 12.sp)
      }
      if (customer.address.isNotBlank()) {
        Text("Address: ${customer.address}", color = palette.textMuted, fontSize = 12.sp)
      }
      if (customer.ledgerPage.isNotBlank()) {
        Text("Ledger Page: ${customer.ledgerPage}", color = palette.textMuted, fontSize = 12.sp)
      }
    }

    // Balance card
    SarimCollapsibleCard(
      title = "CURRENT DEBT",
      subtitle = "$pctReceived% received",
      badgeText = SarimFormat.formatRs(customer.currentCredit, privacyHidden = state.hideMoneyFigures),
      badgeColor = palette.amber,
      tonalAccent = palette.amber,
      initiallyExpanded = false,
    ) {
      Text(
        SarimFormat.formatRs(customer.currentCredit, privacyHidden = state.hideMoneyFigures),
        color = palette.amber,
        style = TabularNumberStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
      )
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SarimStatTile("TOTAL SALE", SarimFormat.formatRs(customer.totalSaleValue, privacyHidden = state.hideMoneyFigures), modifier = Modifier.weight(1f), accentColor = palette.cyan)
        SarimStatTile("RECEIVED", SarimFormat.formatRs(customer.totalReceived, privacyHidden = state.hideMoneyFigures), modifier = Modifier.weight(1f), accentColor = palette.green)
      }
      LinearProgressIndicator(
        progress = { pctReceived / 100f },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
        color = palette.green,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
      )
      Text("$pctReceived% of the amount owed has been received", color = palette.textMuted, fontSize = 12.sp)
    }

    // Search & Money Out button (Sales only)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(modifier = Modifier.weight(1f)) {
        SarimTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          label = "",
          placeholder = "Search history...",
          leadingIcon = Icons.Rounded.Search,
        )
      }
      if (!isRep) {
        SarimSecondaryButton(
          text = "Money Out −",
          onClick = { showMoneyOutDialog = true },
          testTag = "cust_money_out_btn",
        )
      }
    }

    // Filter toggle (web .cm-filters)
    SarimToggleGroup(
      options = listOf("All", "Credit", "Cash", "Payments", "Unpaid"),
      selected = filterChip,
      labelOf = { it },
      onSelect = { filterChip = it },
      testTagPrefix = "cust_filter",
    )

    // History: same year > month > day ledger + entry sheet as every other history list
    val hide = state.hideMoneyFigures
    val records = remember(filtered, hide, palette) {
      filtered.map { tx ->
        val isReturn = tx.isRepReturn || tx.mode == "REP_RETURN" || tx.paymentType == "REP_RETURN"
        val typeLabel = when {
          isReturn -> "Rep Return"
          tx.mode == "COLLECTION" -> "Collection"
          tx.mode == "MONEY_OUT" -> "Money out"
          tx.paymentType == "CASH" -> "Cash sale"
          else -> "Credit sale"
        }
        val toneColor = when {
          isReturn -> palette.cyan
          tx.mode == "COLLECTION" -> palette.green
          tx.mode == "MONEY_OUT" -> palette.red
          tx.paymentType == "CASH" -> palette.green
          else -> palette.amber
        }
        val displayAmount = when {
          isReturn -> tx.returnValue.takeIf { it > 0.0 } ?: tx.totalValue
          tx.mode == "COLLECTION" -> tx.amountReceived.takeIf { it > 0.0 } ?: tx.totalValue
          else -> tx.totalValue
        }
        val details = buildList {
          if (tx.storeName.isNotBlank()) add("Store" to tx.storeName)
          if (tx.repName.isNotBlank()) add("Rep" to tx.repName)
          if (isReturn) {
            if (tx.returnedQtyKg > 0.0) add("Returned Qty" to SarimFormat.formatKg(tx.returnedQtyKg))
            if (tx.expiredQtyKg > 0.0) add("Expired Qty" to SarimFormat.formatKg(tx.expiredQtyKg))
            add("Return Value" to SarimFormat.formatRs(displayAmount, privacyHidden = hide))
          } else {
            if (tx.quantityKg > 0.0) {
              add("Quantity" to SarimFormat.formatKg(tx.quantityKg))
              add("Rate / kg" to SarimFormat.formatRs(tx.pricePerKg, privacyHidden = hide))
            }
            if (tx.mode == "SALE") {
              add("Total Value" to SarimFormat.formatRs(tx.totalValue, privacyHidden = hide))
              add("Profit" to SarimFormat.formatRs(tx.profit, privacyHidden = hide))
            } else {
              add("Amount Received" to SarimFormat.formatRs(displayAmount, privacyHidden = hide))
              if (tx.expiredQtyKg > 0.0) add("Expired Qty" to SarimFormat.formatKg(tx.expiredQtyKg))
            }
          }
          add("Balance After" to SarimFormat.formatRs(tx.balanceAfter, privacyHidden = hide))
        }
        val sub = listOfNotNull(
          if (tx.quantityKg > 0.0) "${SarimFormat.formatKg(tx.quantityKg)} @ ${SarimFormat.formatRs(tx.pricePerKg, privacyHidden = hide)}" else null,
          tx.storeName.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        HistoryRecordDisplay(
          item = tx,
          dateEpoch = tx.dateEpoch,
          title = typeLabel,
          subtitle = sub,
          amountText = SarimFormat.formatRs(displayAmount, privacyHidden = hide),
          amountColor = toneColor,
          chipText = if (tx.mode == "SALE") tx.paymentType else tx.mode,
          chipColor = toneColor,
          detailMetrics = details,
        )
      }
    }
    SarimCollapsibleCard(
      title = "Transaction History",
      badgeText = "${records.size}",
      badgeColor = palette.cyan,
      tonalAccent = palette.cyan,
      initiallyExpanded = false,
    ) {
      AccordionHistoryList(
        records = records,
        yearSummary = { "" },
        monthSummary = { list ->
          val sold = list.sumOf { it.item.quantityKg }
          val soldVal = list.filter { it.item.mode == "SALE" }.sumOf { it.item.totalValue }
          val recVal = list.filter { it.item.mode == "COLLECTION" || (it.item.mode == "SALE" && it.item.paymentType == "CASH") }.sumOf {
            if (it.item.mode == "COLLECTION") (it.item.amountReceived.takeIf { a -> a > 0.0 } ?: it.item.totalValue) else it.item.totalValue
          }
          "${SarimFormat.formatKg(sold)} sold · Sold ${SarimFormat.formatRs(soldVal, privacyHidden = hide)} · Received ${SarimFormat.formatRs(recVal, privacyHidden = hide)}"
        },
        daySummary = { "" },
        emptyTitle = "No customer history",
        emptySubtitle = "Transactions with ${customer.name} appear here.",
        actions = HistoryRecordActions(
          onDelete = { tx ->
            val blockMsg = if (YearCloseMerge.isMergedId(tx.id)) {
              "Merged opening balance records cannot be deleted."
            } else {
              SarimLinkEngine.getSaleBlockReason(tx, state.settlements)
                ?: SarimLinkEngine.getCollectionDeleteBlockReason(tx, state.rawMaterials)
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
              val dispAmt = when {
                tx.isRepReturn -> tx.returnValue.takeIf { it > 0.0 } ?: tx.totalValue
                tx.mode == "COLLECTION" || tx.mode == "MONEY_OUT" -> tx.amountReceived.takeIf { it > 0.0 } ?: tx.totalValue
                else -> tx.totalValue
              }
              val ledgerNote = if (tx.addToLedger && !tx.ledgerId.isNullOrBlank()) "\nLinked Daily Ledger entry will also be removed." else ""
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Delete Transaction",
                  body = "Customer: ${customer.name}\nType: ${tx.mode.replace("_", " ")}\nAmount: ${viewModel.redactMoney(SarimFormat.formatRs(dispAmt))}$ledgerNote\n\nMoves to Recycle Bin for 90 days.",
                  tone = DialogTone.PERMANENT,
                  confirmLabel = "Delete",
                  onConfirm = {
                    viewModel.launchSafely(errorTitle = "Delete Failed", successMessage = "Moved to Recycle Bin") {
                      viewModel.repository.deleteSale(tx.id)
                    }
                  },
                )
              )
            }
          },
          dayActionLabel = "Invoice",
          onDayAction = { dayItems ->
            val dayKey = SarimFormat.dateInput(dayItems.first().dateEpoch)
            val data = StatementData.salesDayInvoice(stmtKind, customer, custSales, dayKey, hide)
            if (data == null) viewModel.postStatus("Nothing to invoice for this day.")
            else StatementData.shareDayInvoice(context, data)?.let { viewModel.postStatus(it) }
          },
        ),
      )
    }
    Spacer(Modifier.height(24.dp))
  }

  if (showStatementFlow) {
    StatementShareFlow(
      viewModel = viewModel,
      personName = customer.name,
      minEpoch = custSales.minOfOrNull { it.dateEpoch } ?: 0L,
      maxEpoch = custSales.maxOfOrNull { it.dateEpoch } ?: 0L,
      buildSpec = { from, to -> StatementData.salesStatement(stmtKind, customer, custSales, from, to, state.hideMoneyFigures) },
      onDismiss = { showStatementFlow = false },
    )
  }

  if (showMoneyOutDialog) {
    var moDateEpoch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var moAmountText by remember { mutableStateOf("") }
    var moNote by remember { mutableStateOf("") }
    var moAddToLedger by remember { mutableStateOf(true) }
    Dialog(
      onDismissRequest = { showMoneyOutDialog = false },
      properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 460.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 6.dp,
      ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Money Out — ${customer.name}", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
          Text(
            "Cash payout/refund to customer increases their owed balance and optionally logs to Daily Ledger.",
            color = palette.textMuted,
            fontSize = 12.sp,
          )
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            SarimDatePickerField(
              label = "Date",
              dateEpoch = moDateEpoch,
              onDateSelected = { moDateEpoch = it },
              modifier = Modifier.weight(1f),
              testTag = "mo_date",
            )
            SarimTextField(
              value = moAmountText,
              onValueChange = { moAmountText = it },
              label = "Amount",
              placeholder = "0.00",
              prefixText = "Rs",
              keyboardType = KeyboardType.Decimal,
              modifier = Modifier.weight(1f),
              testTag = "mo_amount",
            )
          }
          SarimTextField(
            value = moNote,
            onValueChange = { moNote = it },
            label = "Note / Remarks",
            placeholder = "Optional payout note...",
            maxLength = 120,
            testTag = "mo_note",
          )
          Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Add to Daily Ledger", color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Switch(checked = moAddToLedger, onCheckedChange = { moAddToLedger = it })
          }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SarimSecondaryButton("Cancel", onClick = { showMoneyOutDialog = false }, modifier = Modifier.weight(1f))
            SarimPrimaryButton(
              text = "Record Payout",
              onClick = {
                val amt = SarimFormat.parseSafeDouble(moAmountText)
                if (amt == null || amt <= 0.0) {
                  viewModel.showDialog(AppDialogRequest("Invalid Amount", "Enter a valid positive amount.", tone = DialogTone.ERROR, cancelLabel = null))
                  return@SarimPrimaryButton
                }
                if (SarimFormat.isFutureDate(moDateEpoch)) {
                  viewModel.showDialog(AppDialogRequest("Invalid Date", "Future dates are refused on customer payouts.", tone = DialogTone.ERROR, cancelLabel = null))
                  return@SarimPrimaryButton
                }
                val cashNow = SarimLinkEngine.computeCashInHandBreakdown(
                  sales = state.sales,
                  settlements = state.settlements,
                  payments = state.paymentTransactions,
                  factoryBatches = state.factoryBatches,
                ).netCash
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
                viewModel.confirmSave(
                  "Money Out",
                  listOf(
                    "Customer" to customer.name,
                    "Amount" to viewModel.redactMoney(SarimFormat.formatRs(amt)),
                    "Date" to SarimFormat.formatDate(moDateEpoch),
                    "Daily Ledger" to if (moAddToLedger) "Linked" else "No",
                  ),
                  isUpdate = false,
                ) {
                  viewModel.recordCustomerMoneyOut(
                    customer = customer,
                    dateEpoch = moDateEpoch,
                    amount = amt,
                    note = moNote.trim(),
                    addToLedger = moAddToLedger,
                  ) {
                    showMoneyOutDialog = false
                  }
                }
              },
              modifier = Modifier.weight(1f),
              testTag = "mo_save_btn",
            )
          }
        }
      }
    }
  }
}

@Composable
fun CustomerEditScreen(
  customerId: String?,
  isRep: Boolean,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val existing = remember(customerId, state.customers) { customerId?.let { id -> state.customers.firstOrNull { it.id == id } } }

  var photoUris by remember(existing) { mutableStateOf(listOfNotNull(existing?.photoUri?.takeIf { it.isNotBlank() })) }
  var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
  var phone by remember(existing) { mutableStateOf(existing?.phone ?: "") }
  var address by remember(existing) { mutableStateOf(existing?.address ?: "") }
  var locationStatus by remember { mutableStateOf("") }
  var oldDebitText by remember(existing) { mutableStateOf(existing?.oldDebit?.takeIf { it != 0.0 }?.toString() ?: "") }
  var customPriceText by remember(existing) { mutableStateOf(existing?.customSalePrice?.toString() ?: "") }
  var ledgerPage by remember(existing) { mutableStateOf(existing?.ledgerPage ?: "") }

  @SuppressLint("MissingPermission")
  fun fetchDeviceLocation() {
    scope.launch {
      locationStatus = "Locating GPS..."
      try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
          ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc == null) {
          locationStatus = "No cached GPS fix available right now."
        } else {
          val resolved = withContext(Dispatchers.IO) {
            try {
              @Suppress("DEPRECATION")
              val list = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
              list?.firstOrNull()?.let { addr ->
                listOfNotNull(addr.subLocality, addr.locality, addr.adminArea).joinToString(", ")
              }
            } catch (_: Exception) {
              null
            }
          }
          address = resolved?.takeIf { it.isNotBlank() } ?: "${String.format(Locale.US, "%.4f", loc.latitude)}, ${String.format(Locale.US, "%.4f", loc.longitude)}"
          locationStatus = "Location updated from GPS."
        }
      } catch (e: Exception) {
        locationStatus = "Location unavailable: ${e.message ?: "error"}"
      }
    }
  }

  val locationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) fetchDeviceLocation() else locationStatus = "Location permission denied."
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Identity
    SarimCard {
      SarimFieldLabel("Identity")
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val curPhoto = photoUris.firstOrNull()
        Box(
          modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag("cust_photo_preview")
            .clickable { if (!curPhoto.isNullOrBlank()) viewModel.openPhotoViewer(listOf(curPhoto), 0) },
          contentAlignment = Alignment.Center,
        ) {
          if (!curPhoto.isNullOrBlank()) {
            AsyncImage(model = curPhoto, contentDescription = "Customer photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
          } else {
            Text(SarimFormat.initials(name.ifBlank { "CU" }), color = palette.green, fontWeight = FontWeight.Bold, fontSize = 20.sp)
          }
        }
        Column(modifier = Modifier.weight(1f)) {
          PhotoAttachmentBar(
            photoUris = photoUris,
            onAddUris = { if (it.isNotEmpty()) photoUris = listOf(it.last()) },
            onClearAll = { photoUris = emptyList() },
            onViewPhotos = { idx -> viewModel.openPhotoViewer(photoUris, idx) },
            onError = { msg -> viewModel.postStatus(msg) },
          )
        }
      }

      SarimTextField(
        value = name,
        onValueChange = { name = it },
        label = "Customer Name",
        placeholder = if (existing == null) "Type name to search or add..." else "Customer name",
        maxLength = 80,
        testTag = "edit_cust_name",
      )
      Text("Editing the name will update all records for this customer.", color = palette.textMuted, fontSize = 11.5.sp)
    }

    // Contact
    SarimCard {
      SarimFieldLabel("Contact")
      SarimTextField(
        value = phone,
        onValueChange = { phone = it },
        label = "Phone number",
        placeholder = "03XX-XXXXXXX",
        keyboardType = KeyboardType.Phone,
        testTag = "edit_cust_phone",
      )
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        Box(modifier = Modifier.weight(1f)) {
          SarimTextField(
            value = address,
            onValueChange = { address = it },
            label = "Address / location",
            placeholder = "Area, shop no., etc.",
            testTag = "edit_cust_address",
          )
        }
        SarimSecondaryButton(
          text = "Locate",
          icon = Icons.Rounded.MyLocation,
          onClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (granted) fetchDeviceLocation() else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
          },
        )
      }
      if (locationStatus.isNotBlank()) {
        Text(locationStatus, color = palette.cyan, fontSize = 12.sp, modifier = Modifier.testTag("location_status"))
      }
    }

    // Account
    SarimCard {
      SarimFieldLabel("Account")
      SarimTextField(
        value = oldDebitText,
        onValueChange = { oldDebitText = it },
        label = "Previous balance (old debt)",
        placeholder = "0.00",
        prefixText = "Rs",
        keyboardType = KeyboardType.Decimal,
        testTag = "edit_cust_old_debit",
      )
      if (!isRep) {
        SarimTextField(
          value = customPriceText,
          onValueChange = { customPriceText = it },
          label = "Custom sale price",
          placeholder = "Factory default",
          prefixText = "Rs",
          suffixText = "/kg",
          keyboardType = KeyboardType.Decimal,
          testTag = "edit_cust_custom_price",
        )
      }
      SarimTextField(
        value = ledgerPage,
        onValueChange = { ledgerPage = it },
        label = "Manual ledger book page",
        placeholder = "e.g. 45",
        maxLength = 12,
        testTag = "edit_cust_ledger_page",
      )
      Text(
        if (isRep) "Shown as a small badge on the customer's photo." else "Shown as a small badge on the customer avatar.",
        color = palette.textMuted,
        fontSize = 11.5.sp,
      )
    }

    if (existing != null) {
      val hist = remember(existing.editHistoryJson) { JsonCodecs.decodeEditHistory(existing.editHistoryJson) }
      com.example.ui.components.EditHistoryCard(
        entries = hist,
        createdAt = existing.createdAt,
        onDeleteEntry = { idx -> viewModel.deleteCustomerEditHistoryEntry(existing.id, idx) },
      )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (existing != null) {
        SarimDangerButton(
          text = "Delete",
          modifier = Modifier.weight(1f),
          onClick = {
            val custTxs = state.sales.filter {
              !it.isDeleted && it.isRepSale == existing.isRepCustomer &&
                (it.customerId == existing.id || it.customerName.equals(existing.name, ignoreCase = true))
            }
            val blockMsg = custTxs.firstNotNullOfOrNull { tx ->
              when {
                YearCloseMerge.isMergedId(tx.id) -> "Contains merged opening balance records that cannot be deleted."
                !tx.usedInCalcId.isNullOrBlank() ||
                  (!tx.isRepSale && tx.id in SarimLinkEngine.reconciledAdminSalesIds(state.settlements)) ||
                  (tx.isRepSale && tx.id in SarimLinkEngine.reconciledRepSalesIds(state.settlements)) ||
                  (tx.isRepReturn && tx.id in SarimLinkEngine.reconciledRepReturnIds(state.settlements)) ->
                  "This customer has transactions reconciled in the Calculator. Delete those settlements first."
                else -> SarimLinkEngine.getCollectionDeleteBlockReason(tx, state.rawMaterials)
              }
            }
            if (blockMsg != null) {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Cannot Delete Customer",
                  body = blockMsg,
                  tone = DialogTone.ERROR,
                  confirmLabel = "OK",
                  cancelLabel = null,
                )
              )
              return@SarimDangerButton
            }
            val debtLine = if (kotlin.math.abs(existing.currentCredit) > 0.01) {
              "\nCurrent debt: ${viewModel.redactMoney(SarimFormat.formatRs(existing.currentCredit))}"
            } else ""
            val txLine = if (custTxs.isNotEmpty()) "\nLinked transactions removed: ${custTxs.size}" else ""
            viewModel.showDialog(
              AppDialogRequest(
                title = "Delete Customer",
                body = "Move ${existing.name} to Recycle Bin?$debtLine$txLine\n\nMoves to Recycle Bin for 90 days.",
                tone = DialogTone.PERMANENT,
                confirmLabel = "Delete",
                onConfirm = {
                  viewModel.launchSafely(
                    errorTitle = "Delete Customer Failed",
                    successMessage = "Moved ${existing.name} to Recycle Bin",
                    onSuccess = { viewModel.closeStandalone() },
                  ) {
                    viewModel.repository.deleteCustomer(existing.id)
                  }
                },
              )
            )
          },
        )
      }
      SarimPrimaryButton(
        text = if (existing == null) "Add Customer" else "Update Customer",
        modifier = Modifier.weight(1f),
        onClick = {
          val clean = name.trim()
          if (clean.isEmpty() || clean.length > 80) {
            viewModel.showDialog(AppDialogRequest("Invalid Name", "Customer name is required (max 80 characters).", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (phone.isNotBlank() && !SarimFormat.isValidPhone(phone)) {
            viewModel.showDialog(AppDialogRequest("Invalid Phone", "Phone number must have 7 to 15 digits.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          val oldD = if (oldDebitText.isBlank()) 0.0 else SarimFormat.parseSafeDouble(oldDebitText)
          if (oldD == null) {
            viewModel.showDialog(AppDialogRequest("Invalid Old Debt", "Enter a plain valid number for previous balance.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          val customP = if (customPriceText.isBlank()) null else SarimFormat.parseSafeDouble(customPriceText)
          if (customPriceText.isNotBlank() && (customP == null || customP <= 0.0)) {
            viewModel.showDialog(AppDialogRequest("Invalid Price", "Enter a positive custom price or leave blank.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          viewModel.confirmSave(
            "Customer",
            listOf("Name" to clean, "Phone" to phone, "Address" to address, "Old Debit" to oldDebitText, "Custom Price" to customPriceText, "Ledger Page" to ledgerPage),
            isUpdate = existing != null,
          ) {
            viewModel.launchSafely(
              errorTitle = "Save Customer Failed",
              successMessage = "Saved customer $clean",
              onSuccess = { viewModel.closeStandalone() },
            ) {
              viewModel.repository.saveCustomer(
                editingId = existing?.id,
                name = clean,
                phone = phone,
                address = address,
                oldDebit = oldD,
                customSalePrice = if (isRep) null else customP,
                ledgerPage = ledgerPage,
                photoUri = photoUris.firstOrNull().orEmpty(),
                isRepCustomer = existing?.isRepCustomer ?: isRep,
                repName = existing?.repName ?: if (isRep) state.signedInDisplayName else "",
                actor = state.signedInDisplayName.ifBlank { "Admin" },
              )
            }
          }
        },
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}
