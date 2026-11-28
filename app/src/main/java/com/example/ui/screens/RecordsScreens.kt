package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.JsonCodecs
import com.example.data.local.RawMaterialPurchaseEntity
import com.example.data.repository.SarimLinkEngine
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogChoice
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

@Composable
fun AddEntityScreen(
  entityId: String?,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val editingEntity = remember(entityId, state.paymentEntities) {
    entityId?.let { id -> state.paymentEntities.firstOrNull { it.id == id } }
  }

  var photoUris by remember(editingEntity) {
    mutableStateOf(listOfNotNull(editingEntity?.photoUri?.takeIf { it.isNotBlank() }))
  }
  var name by remember(editingEntity) { mutableStateOf(editingEntity?.name ?: "") }
  var phone by remember(editingEntity) { mutableStateOf(editingEntity?.phone ?: "") }
  var wallet by remember(editingEntity) { mutableStateOf(editingEntity?.wallet ?: "") }
  var ledgerPage by remember(editingEntity) { mutableStateOf(editingEntity?.ledgerPage ?: "") }
  val openingOptions = listOf(
    "Credit — we owe them (payable)",
    "Debit — they owe us (receivable)",
  )
  var openingIndex by remember(editingEntity) {
    mutableIntStateOf(if (editingEntity?.openingType == "DEBIT") 1 else 0)
  }
  var openingAmountText by remember(editingEntity) {
    mutableStateOf(editingEntity?.openingAmount?.takeIf { it > 0.0 }?.toString() ?: "")
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Identity card
    SarimCard {
      SarimFieldLabel("Identity")
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        val currentPhoto = photoUris.firstOrNull()
        Box(
          modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag("entity_photo_preview")
            .clickable {
              if (!currentPhoto.isNullOrBlank()) viewModel.openPhotoViewer(listOf(currentPhoto), 0)
            },
          contentAlignment = Alignment.Center,
        ) {
          if (!currentPhoto.isNullOrBlank()) {
            AsyncImage(model = currentPhoto, contentDescription = "Entity photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
          } else {
            Text(SarimFormat.initials(name.ifBlank { "EN" }), color = palette.green, fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
        label = "Entity Name",
        placeholder = "Name",
        maxLength = 80,
        testTag = "entityName",
      )
    }

    // Contact & payment details
    SarimCard {
      SarimFieldLabel("Contact and payment details")
      SarimTextField(
        value = phone,
        onValueChange = { phone = it },
        label = "Phone number",
        placeholder = "03XX-XXXXXXX",
        keyboardType = KeyboardType.Phone,
        testTag = "entityPhone",
      )
      SarimTextField(
        value = wallet,
        onValueChange = { wallet = it },
        label = "Wallet / account details",
        placeholder = "JazzCash, Easypaisa or bank account",
        maxLength = 120,
        testTag = "entityWallet",
      )
      SarimTextField(
        value = ledgerPage,
        onValueChange = { ledgerPage = it },
        label = "Manual ledger book page",
        placeholder = "e.g. 45",
        maxLength = 12,
        testTag = "entityLedgerPage",
      )
      Text("Shown as a small badge on the entity's photo.", color = palette.textMuted, fontSize = 11.5.sp)
    }

    // Account card
    SarimCard {
      SarimFieldLabel("Account")
      SarimPopoverSelector(
        label = "Old balance (already credit or debit)",
        selectedText = openingOptions[openingIndex],
        options = openingOptions,
        onSelect = { idx, _ -> openingIndex = idx },
        testTag = "entityOpeningType",
      )
      SarimTextField(
        value = openingAmountText,
        onValueChange = { openingAmountText = it },
        label = "Old balance amount",
        placeholder = "0.00",
        prefixText = "Rs",
        keyboardType = KeyboardType.Decimal,
        testTag = "entityOpeningAmount",
      )
      Text("Balance carried over from before this app. Leave 0 for none.", color = palette.textMuted, fontSize = 11.5.sp)
    }

    // Edit history (only when editing)
    if (editingEntity != null) {
      val history = remember(editingEntity.editHistoryJson) { JsonCodecs.decodeEditHistory(editingEntity.editHistoryJson) }
      com.example.ui.components.EditHistoryCard(
        entries = history,
        createdAt = editingEntity.createdAt,
        onDeleteEntry = { idx -> viewModel.deletePaymentEntityEditHistoryEntry(editingEntity.id, idx) },
      )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (editingEntity != null) {
        SarimDangerButton(
          text = "Delete",
          modifier = Modifier.weight(1f),
          onClick = {
            val blockReason = SarimLinkEngine.getEntityDeleteBlockReason(
              entity = editingEntity,
              allTxs = state.paymentTransactions,
              sales = state.sales,
              settlements = state.settlements,
              factoryBatches = state.factoryBatches,
            )
            if (blockReason != null) {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Cannot Delete Entity",
                  body = blockReason,
                  tone = DialogTone.ERROR,
                  confirmLabel = "OK",
                  cancelLabel = null,
                )
              )
              return@SarimDangerButton
            }
            val linkedCount = state.paymentTransactions.count { !it.isDeleted && it.entityId == editingEntity.id }
            val balNote = if (kotlin.math.abs(editingEntity.currentBalance) > 0.01) {
              val kind = if (editingEntity.currentBalance >= 0) "Payable" else "Receivable"
              "\nBalance ($kind): ${viewModel.redactMoney(SarimFormat.formatRs(kotlin.math.abs(editingEntity.currentBalance)))}"
            } else ""
            val txNote = if (linkedCount > 0) "\nLinked transactions: $linkedCount" else ""
            viewModel.showDialog(
              AppDialogRequest(
                title = "Delete Entity",
                body = "Move ${editingEntity.name} to Recycle Bin?$balNote$txNote",
                tone = DialogTone.PERMANENT,
                confirmLabel = "Delete",
                onConfirm = {
                  viewModel.launchSafely(
                    errorTitle = "Delete Failed",
                    successMessage = "Deleted ${editingEntity.name}",
                    onSuccess = { viewModel.closeStandalone() },
                  ) {
                    viewModel.repository.deletePaymentEntity(editingEntity.id)
                  }
                },
              )
            )
          },
        )
      }
      SarimPrimaryButton(
        text = "Save Entity",
        modifier = Modifier.weight(1f),
        testTag = "save_entity_btn",
        onClick = {
          val clean = name.trim()
          if (clean.isEmpty()) {
            viewModel.showDialog(AppDialogRequest("Name Required", "Enter an entity name.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          val normalizedNew = clean.replace(" ", "").lowercase()
          val duplicate = state.paymentEntities.any {
            !it.isDeleted && it.id != editingEntity?.id && it.name.replace(" ", "").lowercase() == normalizedNew
          }
          if (duplicate) {
            viewModel.showDialog(AppDialogRequest("Duplicate Entity", "An entity named '$clean' already exists.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (phone.isNotBlank() && !SarimFormat.isValidPhone(phone)) {
            viewModel.showDialog(AppDialogRequest("Invalid Phone", "Phone number must have 7 to 15 digits.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          val openAmt = if (openingAmountText.isBlank()) 0.0 else SarimFormat.parseSafeDouble(openingAmountText)
          if (openAmt == null || openAmt < 0.0) {
            viewModel.showDialog(AppDialogRequest("Invalid Amount", "Enter a valid non-negative opening amount.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          viewModel.showDialog(
            AppDialogRequest(
              title = if (editingEntity == null) "Save Entity?" else "Update Entity?",
              body = "Name: $clean\nPhone: ${phone.ifBlank { "—" }}\nWallet: ${wallet.ifBlank { "—" }}\nOld Balance: ${if (openingIndex == 0) "Credit (Payable)" else "Debit (Receivable)"} ${SarimFormat.formatRs(openAmt)}\nLedger Page: ${ledgerPage.ifBlank { "—" }}",
              confirmLabel = "Save",
              onConfirm = {
                viewModel.launchSafely(
                  errorTitle = "Save Entity Failed",
                  successMessage = "Entity saved",
                  onSuccess = { viewModel.closeStandalone() },
                ) {
                  viewModel.repository.savePaymentEntity(
                    editingId = editingEntity?.id,
                    name = clean,
                    phone = phone,
                    wallet = wallet,
                    ledgerPage = ledgerPage,
                    openingType = if (openingIndex == 0) "CREDIT" else "DEBIT",
                    openingAmount = openAmt,
                    photoUri = photoUris.firstOrNull().orEmpty(),
                  )
                }
              },
            )
          )
        },
      )
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
fun AddRawMaterialScreen(
  materialId: String?,
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val materials = remember(state.rawMaterials) { state.rawMaterials.filter { !it.isDeleted } }
  val initialMat = remember(materialId, materials) { materialId?.let { id -> materials.firstOrNull { it.id == id } } }

  var editingLotId by remember { mutableStateOf<String?>(null) }
  val editingLot = remember(editingLotId, state.purchases) {
    editingLotId?.let { id -> state.purchases.firstOrNull { it.id == id && !it.isDeleted } }
  }
  var materialName by remember(initialMat) { mutableStateOf(initialMat?.name ?: "") }
  var dateEpoch by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var unitName by remember(initialMat) { mutableStateOf(initialMat?.unitName ?: "bag") }
  var qtyText by remember { mutableStateOf("") }
  var weightPerUnitText by remember(initialMat) { mutableStateOf(initialMat?.conversionFactorKg?.toString() ?: "1") }
  var costPerUnitText by remember { mutableStateOf("") }
  var supplierMode by remember { mutableStateOf("NONE") } // NONE, EXISTING, NEW
  var existingSupplierName by remember { mutableStateOf("") }
  var newSupplierName by remember { mutableStateOf("") }
  var newSupplierPhone by remember { mutableStateOf("") }

  fun clearEditForm() {
    editingLotId = null
    qtyText = ""
    costPerUnitText = ""
    supplierMode = "NONE"
    existingSupplierName = ""
    newSupplierName = ""
    newSupplierPhone = ""
  }

  fun loadLotIntoEditor(p: RawMaterialPurchaseEntity) {
    editingLotId = p.id
    materialName = p.materialName
    dateEpoch = p.dateEpoch
    unitName = p.unitName
    qtyText = p.unitsBought.toString()
    weightPerUnitText = p.weightPerUnitKg.toString()
    costPerUnitText = p.costPerUnit.toString()
    supplierMode = p.supplierMode.ifBlank { "NONE" }
    existingSupplierName = p.supplierName.orEmpty()
    newSupplierName = ""
    newSupplierPhone = ""
  }

  val matchedMaterial = remember(materialName, materials) {
    materials.firstOrNull { it.name.equals(materialName.trim(), ignoreCase = true) }
  }
  val entities = remember(state.paymentEntities) { state.paymentEntities.filter { !it.isDeleted } }
  val selectedExistingSupplier = remember(existingSupplierName, entities) {
    entities.firstOrNull { it.name.equals(existingSupplierName.trim(), ignoreCase = true) }
  }

  val unitsBought = SarimFormat.parseSafeDouble(qtyText) ?: 0.0
  val wtPerUnit = SarimFormat.parseSafeDouble(weightPerUnitText) ?: 0.0
  val costPerUnit = SarimFormat.parseSafeDouble(costPerUnitText) ?: 0.0
  val stockKgAdd = unitsBought * wtPerUnit
  val totalAmt = unitsBought * costPerUnit
  val costPerKg = if (wtPerUnit > 0.0001) costPerUnit / wtPerUnit else 0.0

  Scaffold(
    containerColor = palette.background,
    // The shell Scaffold already applies status/nav-bar/keyboard insets; applying them again
    // here pushed the form down and left the fields stuck behind doubled padding.
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    bottomBar = {
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (editingLot != null) {
            SarimSecondaryButton(
              text = "Cancel Edit",
              modifier = Modifier.weight(1f),
              onClick = {
                viewModel.requestCancelEdit(hasChanges = true) {
                  clearEditForm()
                }
              },
            )
          } else if (matchedMaterial != null) {
            SarimDangerButton(
              text = "Delete Material",
              modifier = Modifier.weight(1f),
              onClick = {
                val usedFormulas = state.formulas.filter { f ->
                  !f.isDeleted && JsonCodecs.decodeIngredients(f.ingredientsJson).any {
                    it.refType.equals("MATERIAL", ignoreCase = true) && it.refId == matchedMaterial.id
                  }
                }
                if (usedFormulas.isNotEmpty()) {
                  viewModel.showDialog(
                    AppDialogRequest(
                      title = "Cannot Delete Material",
                      body = "${matchedMaterial.name} is still used in formula(s): ${usedFormulas.joinToString { it.name }}. Remove it from those formulas first.",
                      tone = DialogTone.ERROR,
                      confirmLabel = "OK",
                      cancelLabel = null,
                    )
                  )
                  return@SarimDangerButton
                }
                val matLots = state.purchases.filter { !it.isDeleted && it.materialId == matchedMaterial.id }
                val totalVal = matchedMaterial.stockKg * matchedMaterial.avgCostPerKg
                val supPayableNote = if (matchedMaterial.pendingSupplierAmount > 0.01) {
                  "\nPending supplier payables reversed: ${viewModel.redactMoney(SarimFormat.formatRs(matchedMaterial.pendingSupplierAmount))}"
                } else ""
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Delete Material",
                    body = "Delete ${matchedMaterial.name} from inventory?\nIn Stock: ${SarimFormat.formatKg(matchedMaterial.stockKg)} (${viewModel.redactMoney(SarimFormat.formatRs(totalVal))})\nPurchase lots removed: ${matLots.size}$supPayableNote\n\nMoves to Recycle Bin for 90 days.",
                    tone = DialogTone.PERMANENT,
                    confirmLabel = "Delete",
                    onConfirm = {
                      viewModel.launchSafely(
                        errorTitle = "Delete Material Failed",
                        successMessage = "Deleted ${matchedMaterial.name}",
                        onSuccess = { viewModel.closeStandalone() },
                      ) {
                        viewModel.repository.deleteRawMaterial(matchedMaterial.id)
                      }
                    },
                  )
                )
              },
            )
          }
          SarimPrimaryButton(
            text = if (editingLot != null) "Update Purchase" else "Save Purchase",
            modifier = Modifier.weight(1f),
            testTag = "save_purchase_btn",
            onClick = {
              val cleanMat = materialName.trim()
              if (cleanMat.isEmpty() || cleanMat.length > 80) {
                viewModel.showDialog(AppDialogRequest("Invalid Material", "Material name is required (max 80 characters).", tone = DialogTone.ERROR, cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (SarimFormat.isFutureDate(dateEpoch)) {
                viewModel.showDialog(AppDialogRequest("Invalid Date", "Future purchase dates are refused.", tone = DialogTone.ERROR, cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (unitsBought !in 0.001..1_000_000.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Quantity", "Units bought must be between 0.001 and 1,000,000.", tone = DialogTone.ERROR, cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (wtPerUnit !in 0.001..100_000.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Weight", "Weight per unit must be between 0.001 and 100,000 kg.", tone = DialogTone.ERROR, cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (wtPerUnit != 1.0 && unitName.trim().isEmpty()) {
                viewModel.showDialog(AppDialogRequest("Unit Name Required", "Unit name is required when weight per unit is not 1.", tone = DialogTone.ERROR, cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (unitName.trim().length > 30) {
                viewModel.showDialog(AppDialogRequest("Invalid Unit Name", "Unit name cannot exceed 30 characters.", tone = DialogTone.ERROR, cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (costPerUnit !in 0.01..100_000_000.0) {
                viewModel.showDialog(AppDialogRequest("Invalid Cost", "Cost per unit must be between 0.01 and 100,000,000.", tone = DialogTone.ERROR, cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (supplierMode == "EXISTING" && selectedExistingSupplier == null) {
                viewModel.showDialog(AppDialogRequest("Supplier Required", "Select an existing supplier from the dropdown.", tone = DialogTone.ERROR, cancelLabel = null))
                return@SarimPrimaryButton
              }
              if (supplierMode == "NEW") {
                val supClean = newSupplierName.trim()
                if (supClean.isEmpty()) {
                  viewModel.showDialog(AppDialogRequest("Supplier Name Required", "Enter the new supplier name.", tone = DialogTone.ERROR, cancelLabel = null))
                  return@SarimPrimaryButton
                }
                val dup = entities.any { it.name.replace(" ", "").equals(supClean.replace(" ", ""), ignoreCase = true) }
                if (dup) {
                  viewModel.showDialog(
                    AppDialogRequest(
                      title = "Supplier Already Exists",
                      body = "$supClean is already saved. Switch supplier mode to 'Existing' and select them.",
                      tone = DialogTone.ERROR,
                      cancelLabel = null,
                    )
                  )
                  return@SarimPrimaryButton
                }
                if (newSupplierPhone.isNotBlank() && !SarimFormat.isValidPhone(newSupplierPhone)) {
                  viewModel.showDialog(AppDialogRequest("Invalid Phone", "Supplier phone must be 7 to 15 digits.", tone = DialogTone.ERROR, cancelLabel = null))
                  return@SarimPrimaryButton
                }
              }

              fun executePurchaseSave(adjustPayable: Boolean) {
                viewModel.launchSafely(
                  errorTitle = "Purchase Save Failed",
                  successMessage = if (editingLot != null) "Updated purchase ($cleanMat)" else "Saved raw material purchase ($cleanMat)",
                  onSuccess = { clearEditForm() },
                ) {
                  viewModel.repository.saveRawMaterialPurchase(
                    materialName = cleanMat,
                    dateEpoch = dateEpoch,
                    unitName = unitName,
                    unitsBought = unitsBought,
                    weightPerUnitKg = wtPerUnit,
                    costPerUnit = costPerUnit,
                    supplierMode = supplierMode,
                    existingSupplier = selectedExistingSupplier,
                    newSupplierName = newSupplierName,
                    newSupplierPhone = newSupplierPhone,
                    editingLotId = editingLot?.id,
                    adjustSupplierPayable = adjustPayable,
                  )
                }
              }

              if (editingLot != null &&
                editingLot.supplierMode == "EXISTING" &&
                supplierMode == "EXISTING" &&
                !editingLot.supplierId.isNullOrBlank() &&
                selectedExistingSupplier?.id == editingLot.supplierId &&
                kotlin.math.abs(totalAmt - editingLot.totalAmount) > 0.01
              ) {
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Update Supplier Payable?",
                    body = "Purchase total changed from ${viewModel.redactMoney(SarimFormat.formatRs(editingLot.totalAmount))} to ${viewModel.redactMoney(SarimFormat.formatRs(totalAmt))} for ${selectedExistingSupplier.name}.\n\nUpdate their payable balance as well?",
                    tone = DialogTone.CONFIRM,
                    choices = listOf(
                      DialogChoice("adjust", "Update Payable", "Adjust supplier balance to match the new total"),
                      DialogChoice("keep", "Keep Old Payable", "Update stock and cost without changing supplier balance"),
                    ),
                    cancelLabel = "Cancel",
                    onChoiceSelected = { choice -> executePurchaseSave(adjustPayable = choice == "adjust") },
                  )
                )
              } else {
                viewModel.confirmSave(
                  "Inventory Item",
                  listOf(
                    "Material" to cleanMat,
                    "Quantity" to "${SarimFormat.formatQty(unitsBought, 2)} $unitName (${SarimFormat.formatKg(stockKgAdd)})",
                    "Cost / Unit" to viewModel.redactMoney(SarimFormat.formatRs(costPerUnit)),
                    "Total" to viewModel.redactMoney(SarimFormat.formatRs(totalAmt)),
                  ),
                  isUpdate = editingLot != null,
                ) {
                  executePurchaseSave(adjustPayable = true)
                }
              }
            },
          )
        }
      }
    },
  ) { pad ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(pad)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Material Card
      SarimCard {
        SarimFieldLabel("Material")
        // Web .pf-inline-row: material search grows, date sits beside it
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
          Box(modifier = Modifier.weight(1.4f)) {
            SarimSearchFieldWithDropdown(
              value = materialName,
              onValueChange = { materialName = it },
              label = "Search or add material",
              placeholder = "Search saved materials or type a new one",
              suggestions = materials.map { it.name },
              onSuggestionSelected = { picked ->
                materialName = picked
                materials.firstOrNull { it.name.equals(picked, ignoreCase = true) }?.let { m ->
                  unitName = m.unitName
                  weightPerUnitText = m.conversionFactorKg.toString()
                }
              },
              leadingIcon = Icons.Rounded.Inventory2,
              testTag = "factoryMaterialName",
            )
          }
          Box(modifier = Modifier.weight(1f)) {
            SarimDatePickerField(
              label = "Date",
              dateEpoch = dateEpoch,
              onDateSelected = { dateEpoch = it },
              testTag = "factoryPurchaseDate",
            )
          }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimStatTile("Collected", SarimFormat.formatKg(matchedMaterial?.collectedKg ?: 0.0), modifier = Modifier.weight(1f), accentColor = palette.cyan)
          SarimStatTile("In stock", SarimFormat.formatKg(matchedMaterial?.stockKg ?: 0.0), modifier = Modifier.weight(1f), accentColor = palette.green)
          SarimStatTile("Pending suppliers", SarimFormat.formatRs(matchedMaterial?.pendingSupplierAmount ?: 0.0, privacyHidden = state.hideMoneyFigures), modifier = Modifier.weight(1f), accentColor = palette.amber)
        }
      }

      // New purchase card
      SarimCard {
        SarimFieldLabel("New purchase")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimTextField(
            value = unitName,
            onValueChange = { unitName = it },
            label = "Unit name",
            placeholder = "bag, box, carton",
            maxLength = 30,
            modifier = Modifier.weight(1f),
            testTag = "factoryMaterialUnitName",
          )
          SarimTextField(
            value = qtyText,
            onValueChange = { qtyText = it },
            label = "Units bought",
            placeholder = "0",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
            testTag = "factoryMaterialQuantity",
          )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimTextField(
            value = weightPerUnitText,
            onValueChange = { weightPerUnitText = it },
            label = "Weight per unit",
            placeholder = "1",
            suffixText = "kg",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
            testTag = "factoryMaterialConversionFactor",
          )
          SarimTextField(
            value = costPerUnitText,
            onValueChange = { costPerUnitText = it },
            label = "Cost per unit",
            placeholder = "0.00",
            prefixText = "Rs",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
            testTag = "factoryMaterialCost",
          )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimStatTile("Stock (kg)", SarimFormat.formatKg(stockKgAdd), modifier = Modifier.weight(1f))
          SarimStatTile("Cost per kg", SarimFormat.formatRs(costPerKg, privacyHidden = state.hideMoneyFigures), modifier = Modifier.weight(1f))
          SarimStatTile("Total amount", SarimFormat.formatRs(totalAmt, privacyHidden = state.hideMoneyFigures), modifier = Modifier.weight(1f), accentColor = palette.green)
        }
      }

      // Supplier card
      SarimCard {
        SarimFieldLabel("Supplier")
        SarimToggleGroup(
          options = listOf("NONE", "EXISTING", "NEW"),
          selected = supplierMode,
          labelOf = { it.lowercase().replaceFirstChar { c -> c.uppercase() } },
          onSelect = { supplierMode = it },
          testTagPrefix = "factorySupplierSeg",
        )
        when (supplierMode) {
          "EXISTING" -> {
            SarimSearchFieldWithDropdown(
              value = existingSupplierName,
              onValueChange = { existingSupplierName = it },
              label = "Select supplier",
              placeholder = "Search or select supplier...",
              suggestions = entities.map { it.name },
              onSuggestionSelected = { existingSupplierName = it },
              testTag = "factoryExistingSupplier",
            )
          }
          "NEW" -> {
            SarimTextField(
              value = newSupplierName,
              onValueChange = { newSupplierName = it },
              label = "Supplier name",
              placeholder = "Company or person",
              testTag = "factorySupplierName",
            )
            SarimTextField(
              value = newSupplierPhone,
              onValueChange = { newSupplierPhone = it },
              label = "Phone",
              placeholder = "Phone number",
              keyboardType = KeyboardType.Phone,
              testTag = "factorySupplierPhone",
            )
            SarimCheckboxRow(
              checked = true,
              onCheckedChange = {},
              enabled = false,
              label = "Also add to Quick Entity Selection as a PAYEE",
              testTag = "factorySupplierAutoEntity",
            )
          }
        }
      }

      // Purchase history accordion
      SarimCard {
        SarimFieldLabel("Purchase history")
        val hist = remember(state.purchases, matchedMaterial) {
          state.purchases.filter { !it.isDeleted && (matchedMaterial == null || it.materialId == matchedMaterial.id) }
            .sortedByDescending { it.dateEpoch }
        }
        AccordionHistoryList(
          records = hist.map { p ->
            HistoryRecordDisplay(
              item = p,
              dateEpoch = p.dateEpoch,
              title = p.materialName,
              subtitle = "${SarimFormat.formatQty(p.unitsBought, 2)} ${p.unitName} · ${SarimFormat.formatKg(p.totalKg)}",
              amountText = SarimFormat.formatRs(p.totalAmount, privacyHidden = state.hideMoneyFigures),
              amountColor = palette.amber,
              chipText = p.supplierName ?: p.supplierMode,
              detailMetrics = listOf(
                "Units Bought" to "${SarimFormat.formatQty(p.unitsBought, 2)} ${p.unitName}",
                "Total Weight" to SarimFormat.formatKg(p.totalKg),
                "Cost / ${p.unitName}" to SarimFormat.formatRs(p.costPerUnit, privacyHidden = state.hideMoneyFigures),
                "Cost / kg" to SarimFormat.formatRs(if (p.totalKg > 0.0001) p.totalAmount / p.totalKg else 0.0, privacyHidden = state.hideMoneyFigures),
                "Supplier" to (p.supplierName ?: p.supplierMode),
                "Date" to SarimFormat.dateInput(p.dateEpoch),
              ),
            )
          },
          yearSummary = { items -> "Purchased ${SarimFormat.formatRs(items.sumOf { it.item.totalAmount }, showSymbol = false, privacyHidden = state.hideMoneyFigures)}" },
          monthSummary = { items -> "${SarimFormat.formatKg(items.sumOf { it.item.totalKg })} · ${SarimFormat.formatRs(items.sumOf { it.item.totalAmount }, showSymbol = false, privacyHidden = state.hideMoneyFigures)}" },
          daySummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.totalKg }) },
          emptySubtitle = "No purchases recorded yet.",
          actions = HistoryRecordActions<RawMaterialPurchaseEntity>(
            editLabel = "Edit",
            onEdit = { p -> loadLotIntoEditor(p) },
            deleteLabel = "Delete Purchase",
            onDelete = { p ->
              val lotMat = materials.firstOrNull { it.id == p.materialId } ?: matchedMaterial
              val blockMsg = SarimLinkEngine.getPurchaseLotDeleteBlockReason(p, lotMat)
              if (blockMsg != null) {
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Cannot Delete Purchase Lot",
                    body = blockMsg,
                    tone = DialogTone.ERROR,
                    confirmLabel = "OK",
                    cancelLabel = null,
                  )
                )
              } else {
                val payableNote = if (!p.supplierId.isNullOrBlank() && p.totalPayable > 0.01) {
                  "\nSupplier payable reduced by: ${viewModel.redactMoney(SarimFormat.formatRs(p.totalPayable))}"
                } else ""
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Delete Purchase Lot",
                    body = "Deduct ${SarimFormat.formatKg(p.totalKg)} from ${p.materialName}$payableNote and move this purchase to Recycle Bin?",
                    tone = DialogTone.PERMANENT,
                    confirmLabel = "Delete",
                    onConfirm = {
                      viewModel.launchSafely(
                        errorTitle = "Delete Purchase Failed",
                        successMessage = "Deleted purchase lot",
                        onSuccess = { if (editingLotId == p.id) clearEditForm() },
                      ) {
                        viewModel.repository.deletePurchaseLot(p.id)
                      }
                    },
                  )
                )
              }
            },
          ),
        )
      }
    }
  }
}
