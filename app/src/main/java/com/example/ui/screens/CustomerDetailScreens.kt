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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.local.JsonCodecs
import com.example.data.local.SaleTransactionEntity
import com.example.data.model.StandaloneScreen
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.PdfExporter
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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

  val custSales = remember(state.sales, customer.id) {
    state.sales.filter { !it.isDeleted && it.customerId == customer.id }.sortedByDescending { it.dateEpoch }
  }

  var searchQuery by remember { mutableStateOf("") }
  var filterChip by remember { mutableStateOf("All") }
  var selectedTxForSheet by remember { mutableStateOf<SaleTransactionEntity?>(null) }
  var menuExpanded by remember { mutableStateOf(false) }

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

  val months = remember(filtered) { filtered.map { SarimFormat.formatMonthYear(it.dateEpoch) }.distinct() }
  val totalBill = customer.oldDebit + customer.totalSaleValue
  val pctReceived = if (totalBill > 0.01) ((customer.totalReceived / totalBill) * 100).toInt().coerceIn(0, 100) else 0

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Customer Profile Header
    SarimCard {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InitialsAvatar(customer.name, palette.amber, sizeDp = 50.dp, badgeText = customer.ledgerPage)
        Column(modifier = Modifier.weight(1f)) {
          Text(customer.name, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
          if (customer.phone.isNotBlank()) {
            Text(
              customer.phone,
              color = palette.cyan,
              fontSize = 13.sp,
              modifier = Modifier.clickable {
                runCatching {
                  context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${customer.phone}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                  viewModel.postStatus("No dialer app available")
                }
              },
            )
          }
          if (customer.address.isNotBlank()) {
            Text(customer.address, color = palette.textMuted, fontSize = 11.5.sp)
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
              .background(palette.surfaceElevated)
              .border(1.dp, palette.green.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
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
            HorizontalDivider(color = palette.border)
            DropdownMenuItem(
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("customer_menu_pdf"),
              leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = palette.green, modifier = Modifier.size(18.dp)) },
              text = { Text("Statement PDF", color = palette.textPrimary, fontWeight = FontWeight.Medium) },
              onClick = {
                menuExpanded = false
                val lines = custSales.take(80).map {
                  "${SarimFormat.formatDate(it.dateEpoch)} | ${it.mode} ${it.paymentType} | ${SarimFormat.formatKg(it.quantityKg)} | ${SarimFormat.formatRs(it.totalValue, privacyHidden = state.hideMoneyFigures)} | Bal ${SarimFormat.formatRs(it.balanceAfter, privacyHidden = state.hideMoneyFigures)}"
                }
                PdfExporter.sharePdfReport(
                  context = context,
                  fileNamePrefix = "statement_${customer.name.replace(" ", "_")}",
                  title = "Customer Statement — ${customer.name}",
                  subtitle = "Current Debt: ${SarimFormat.formatRs(customer.currentCredit, privacyHidden = state.hideMoneyFigures)}",
                  lines = lines.ifEmpty { listOf("No transactions recorded.") },
                ).onFailure { err ->
                  viewModel.postStatus(err.localizedMessage ?: "Unable to share PDF")
                }
              },
            )
          }
        }
      }
    }

    // Balance card
    SarimCard(borderColor = palette.amber.copy(alpha = 0.45f)) {
      Text("CURRENT DEBT", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
        trackColor = palette.border,
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
          onClick = {
            viewModel.openStandalone(StandaloneScreen.DailyLedger)
          },
        )
      }
    }

    // Filter chips
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf("All", "Credit", "Cash", "Payments", "Unpaid").forEach { chip ->
        FilterChip(selected = filterChip == chip, onClick = { filterChip = chip }, label = { Text(chip) })
      }
    }

    // Month jump chips
    if (months.size > 1) {
      Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        months.forEach { m ->
          SarimChip(m, palette.cyan)
        }
      }
    }

    // History grouped by month
    val byMonth = remember(filtered) { filtered.groupBy { SarimFormat.formatMonthYear(it.dateEpoch) } }
    if (byMonth.isEmpty()) {
      SarimEmptyState("No customer history", "Transactions with ${customer.name} appear here.")
    } else {
      byMonth.forEach { (monthLabel, mList) ->
        val mSold = mList.sumOf { it.quantityKg }
        val mSoldVal = mList.filter { it.mode == "SALE" }.sumOf { it.totalValue }
        val mRecVal = mList.filter { it.mode == "COLLECTION" || (it.mode == "SALE" && it.paymentType == "CASH") }.sumOf { if (it.mode == "COLLECTION") it.amountReceived else it.totalValue }
        Text(
          "$monthLabel · ${SarimFormat.formatKg(mSold)} sold · Sold ${SarimFormat.formatRs(mSoldVal, privacyHidden = state.hideMoneyFigures)} · Received ${SarimFormat.formatRs(mRecVal, privacyHidden = state.hideMoneyFigures)}",
          color = palette.textMuted,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Bold,
        )

        mList.forEach { tx ->
          val typeLabel = when {
            tx.mode == "COLLECTION" -> "Collection"
            tx.mode == "MONEY_OUT" -> "Money out"
            tx.paymentType == "CASH" -> "Cash sale"
            else -> "Credit sale"
          }
          val toneColor = when (tx.mode) {
            "COLLECTION" -> palette.green
            "MONEY_OUT" -> palette.red
            else -> if (tx.paymentType == "CASH") palette.green else palette.amber
          }
          Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { selectedTxForSheet = tx },
            shape = RoundedCornerShape(20.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.border),
          ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(38.dp)) {
                Text(SarimFormat.formatDayNumber(tx.dateEpoch), color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(SarimFormat.formatWeekday(tx.dateEpoch), color = palette.textMuted, fontSize = 10.sp)
              }
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                  Text(typeLabel, color = palette.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                  SarimChip(if (tx.mode == "SALE") tx.paymentType else tx.mode, toneColor)
                }
                if (tx.quantityKg > 0.0) {
                  Text("${SarimFormat.formatKg(tx.quantityKg)} @ ${SarimFormat.formatRs(tx.pricePerKg, privacyHidden = state.hideMoneyFigures)}/kg", color = palette.textMuted, fontSize = 11.5.sp)
                }
              }
              Column(horizontalAlignment = Alignment.End) {
                Text(
                  SarimFormat.formatRs(if (tx.mode == "SALE") tx.totalValue else tx.amountReceived, privacyHidden = state.hideMoneyFigures),
                  color = toneColor,
                  style = TabularNumberStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                )
                Text("Bal ${SarimFormat.formatRs(tx.balanceAfter, privacyHidden = state.hideMoneyFigures)}", color = palette.textMuted, fontSize = 10.5.sp)
                if (tx.mode == "SALE") {
                  TextButton(
                    contentPadding = PaddingValues(0.dp),
                    onClick = {
                      PdfExporter.sharePdfReport(
                        context = context,
                        fileNamePrefix = "invoice_${customer.name.replace(" ", "_")}",
                        title = "Sale Invoice — ${customer.name}",
                        subtitle = SarimFormat.formatDate(tx.dateEpoch),
                        lines = listOf(
                          "Store: ${tx.storeName}",
                          "Type: ${tx.paymentType}",
                          "Quantity: ${SarimFormat.formatKg(tx.quantityKg)}",
                          "Price/kg: ${SarimFormat.formatRs(tx.pricePerKg, privacyHidden = state.hideMoneyFigures)}",
                          "Total: ${SarimFormat.formatRs(tx.totalValue, privacyHidden = state.hideMoneyFigures)}",
                          "Running Balance: ${SarimFormat.formatRs(tx.balanceAfter, privacyHidden = state.hideMoneyFigures)}",
                        ),
                      ).onFailure { err ->
                        viewModel.postStatus(err.localizedMessage ?: "Unable to share invoice")
                      }
                    },
                  ) {
                    Text("Invoice", color = palette.cyan, fontSize = 11.5.sp)
                  }
                }
              }
            }
          }
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }

  selectedTxForSheet?.let { tx ->
    ModalBottomSheet(
      onDismissRequest = { selectedTxForSheet = null },
      containerColor = palette.surface,
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${tx.mode} · ${SarimFormat.formatDate(tx.dateEpoch)}", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("Customer: ${tx.customerName}", color = palette.textMuted, fontSize = 13.sp)
        if (tx.quantityKg > 0.0) Text("Quantity: ${SarimFormat.formatKg(tx.quantityKg)}", color = palette.textPrimary, fontSize = 13.sp)
        Text("Value: ${SarimFormat.formatRs(if (tx.mode == "SALE") tx.totalValue else tx.amountReceived, privacyHidden = state.hideMoneyFigures)}", color = palette.green, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Balance After: ${SarimFormat.formatRs(tx.balanceAfter, privacyHidden = state.hideMoneyFigures)}", color = palette.amber, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        SarimDangerButton(
          text = "Delete Transaction",
          modifier = Modifier.fillMaxWidth(),
          onClick = {
            selectedTxForSheet = null
            viewModel.showDialog(
              AppDialogRequest(
                title = "Delete Transaction",
                body = "Move this transaction to Recycle Bin?",
                tone = DialogTone.PERMANENT,
                confirmLabel = "Delete",
                onConfirm = {
                  viewModel.launchSafely(errorTitle = "Delete Failed", successMessage = "Moved to Recycle Bin") {
                    viewModel.repository.deleteSale(tx.id)
                  }
                },
              )
            )
          },
        )
        Spacer(Modifier.height(16.dp))
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
            .background(palette.surfaceElevated)
            .border(1.5.dp, palette.green.copy(alpha = 0.6f), CircleShape)
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
      val hist = remember(existing) { JsonCodecs.decodeEditHistory(existing.editHistoryJson) }
      if (hist.isNotEmpty()) {
        SarimCard {
          SarimFieldLabel("Edit history")
          hist.forEach { h ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(h.summary, color = palette.textPrimary, fontSize = 12.sp)
              Text(SarimFormat.formatDate(h.timestamp), color = palette.textMuted, fontSize = 11.sp)
            }
          }
        }
      }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (existing != null) {
        SarimDangerButton(
          text = "Delete",
          modifier = Modifier.weight(1f),
          onClick = {
            viewModel.showDialog(
              AppDialogRequest(
                title = "Delete Customer",
                body = "Move ${existing.name} to Recycle Bin?",
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
        modifier = Modifier.weight(1.5f),
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
            )
          }
        },
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}
