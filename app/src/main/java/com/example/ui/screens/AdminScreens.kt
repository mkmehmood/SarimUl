package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RolesHelper
import com.example.data.model.UserRole
import com.example.data.repository.SarimSeedHelper
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppAccountsScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var showPassword by remember { mutableStateOf(false) }
  var fullName by remember { mutableStateOf("") }
  var selectedRoles by remember { mutableStateOf(setOf(UserRole.REP)) }

  LaunchedEffect(state.currentUid, state.isOnline) {
    viewModel.refreshAccounts()
  }

  val wsRootUid = remember(state.workspaceOwnerUid, state.currentUid) {
    state.workspaceOwnerUid?.takeIf { it.isNotBlank() } ?: state.currentUid
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SarimCard {
      SarimFieldLabel("Add Account")
      SarimTextField(
        value = email,
        onValueChange = { email = it },
        label = "Email",
        placeholder = "user@example.com",
        keyboardType = KeyboardType.Email,
        testTag = "acct_new_email",
      )
      SarimTextField(
        value = password,
        onValueChange = { password = it },
        label = "Password",
        placeholder = "min 8 characters",
        isPassword = true,
        passwordVisible = showPassword,
        trailingContent = {
          IconButton(
            onClick = { showPassword = !showPassword },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("acct_toggle_pwd_btn"),
          ) {
            Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = "Toggle password", tint = palette.textMuted)
          }
        },
        testTag = "acct_new_password",
      )
      SarimTextField(
        value = fullName,
        onValueChange = { fullName = it },
        label = "Name",
        placeholder = "full name",
        testTag = "acct_new_name",
      )

      SarimFieldLabel("Roles (Combinable)")
      @OptIn(ExperimentalLayoutApi::class)
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        UserRole.entries.forEach { role ->
          val active = role in selectedRoles
          FilterChip(
            selected = active,
            onClick = {
              selectedRoles = if (active && selectedRoles.size > 1) selectedRoles - role else selectedRoles + role
            },
            label = { Text(role.label) },
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("acct_role_${role.name.lowercase()}"),
          )
        }
      }

      SarimPrimaryButton(
        text = if (state.isAccountActionBusy) "Working..." else "Add Account",
        enabled = !state.isAccountActionBusy,
        testTag = "acct_add_btn",
        onClick = {
          val eClean = email.trim()
          if (!eClean.contains("@") || !eClean.contains(".")) {
            viewModel.showDialog(AppDialogRequest("Invalid Email", "Enter a valid email address.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (password.length < 8) {
            viewModel.showDialog(AppDialogRequest("Weak Password", "Password must be at least 8 characters.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          if (fullName.trim().isEmpty()) {
            viewModel.showDialog(AppDialogRequest("Name Required", "Enter the person's full name.", tone = DialogTone.ERROR, cancelLabel = null))
            return@SarimPrimaryButton
          }
          viewModel.submitNewAccount(
            email = eClean,
            password = password,
            name = fullName.trim(),
            roles = selectedRoles,
            onClearForm = {
              email = ""
              password = ""
              fullName = ""
            },
          )
        },
      )
    }

    SarimCard {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Registered Accounts (${state.accounts.size})", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        SarimSecondaryButton(
          text = "Refresh",
          icon = Icons.Rounded.Refresh,
          testTag = "acct_refresh_btn",
          onClick = { viewModel.refreshAccounts() },
        )
      }

      if (state.accounts.isEmpty()) {
        SarimEmptyState("No accounts configured", "Add an account above to assign role permissions.")
      } else {
        state.accounts.forEach { acct ->
          val targetUid = acct.uid.ifBlank { acct.id }
          val isOwner = targetUid == wsRootUid
          val isSelf = targetUid == state.currentUid
          val acctRoles = RolesHelper.parseRolesCsv(acct.rolesCsv)
          Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Text(acct.name.ifBlank { acct.email }, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                  if (isOwner) SarimChip("Owner", palette.green)
                  if (isSelf) SarimChip("You", palette.cyan)
                  if (!acct.approved) SarimChip("Suspended", palette.red)
                }
                Text(acct.email, color = palette.textMuted, fontSize = 12.sp)
                Text(
                  text = "Roles: ${RolesHelper.roleNames(acctRoles)}",
                  color = palette.cyan,
                  fontSize = 11.5.sp,
                  fontWeight = FontWeight.Medium,
                )
              }
              if (!isOwner && !isSelf) {
                IconButton(
                  enabled = !state.isAccountActionBusy,
                  onClick = { viewModel.toggleAccountSuspension(acct) },
                  modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("acct_suspend_$targetUid"),
                ) {
                  Icon(
                    imageVector = if (acct.approved) Icons.Rounded.Block else Icons.Rounded.CheckCircle,
                    contentDescription = if (acct.approved) "Suspend account" else "Reinstate account",
                    tint = if (acct.approved) palette.amber else palette.green,
                  )
                }
                IconButton(
                  enabled = !state.isAccountActionBusy,
                  onClick = { viewModel.removeAccount(acct) },
                  modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("acct_remove_$targetUid"),
                ) {
                  Icon(Icons.Rounded.Delete, contentDescription = "Remove account", tint = palette.red)
                }
              }
            }

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              RolesHelper.ROLE_KEYS.forEach { roleKey ->
                val active = roleKey in acctRoles
                FilterChip(
                  selected = active,
                  enabled = !state.isAccountActionBusy,
                  onClick = { viewModel.toggleAccountRole(acct, roleKey) },
                  label = { Text(RolesHelper.ROLE_LABELS[roleKey] ?: roleKey, fontSize = 11.5.sp) },
                  modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("acct_chip_${targetUid}_$roleKey"),
                )
              }
            }
          }
          HorizontalDivider(color = palette.border)
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
fun StoreManagerScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val stores = remember(state.stores) { state.stores.filter { !it.isDeleted } }
  val formulas = remember(state.formulas) { state.formulas.filter { !it.isDeleted } }
  val formulaOptions = remember(formulas) { listOf("Select formula") + formulas.map { it.name } }

  var editingStoreKey by remember { mutableStateOf<String?>(null) }
  var storeName by remember { mutableStateOf("") }
  var formulaIndex by remember { mutableIntStateOf(0) }
  var salePriceText by remember { mutableStateOf("0") }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SarimCard {
      Text(
        text = if (editingStoreKey == null) "New Store" else storeName.uppercase(),
        color = palette.green,
        fontWeight = FontWeight.Bold,
        fontSize = 15.5.sp,
        modifier = Modifier.testTag("store_form_heading"),
      )
      SarimTextField(
        value = storeName,
        onValueChange = { storeName = it },
        label = "Store Name",
        placeholder = "e.g. KARACHI",
        maxLength = 32,
        readOnly = editingStoreKey != null,
        testTag = "store_name_input",
      )
      if (editingStoreKey == null && storeName.isNotBlank()) {
        Text(
          "Generated key: ${SarimSeedHelper.storeKeyFromName(storeName)}",
          color = palette.textMuted,
          fontSize = 11.5.sp,
        )
      }
      SarimPopoverSelector(
        label = "Formula",
        selectedText = formulaOptions.getOrElse(formulaIndex) { "Select formula" },
        options = formulaOptions,
        onSelect = { idx, _ -> formulaIndex = idx },
        testTag = "store_formula_btn",
      )
      SarimTextField(
        value = salePriceText,
        onValueChange = { salePriceText = it },
        label = "Sale Price (per kg)",
        placeholder = "0.00",
        prefixText = "Rs",
        keyboardType = KeyboardType.Decimal,
        testTag = "store_sale_price_input",
      )

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editingStoreKey != null) {
          SarimSecondaryButton(
            text = "Cancel",
            modifier = Modifier.weight(1f),
            onClick = { editingStoreKey = null; storeName = ""; formulaIndex = 0; salePriceText = "0" },
          )
        }
        SarimPrimaryButton(
          text = if (editingStoreKey == null) "Add Store" else "Update",
          modifier = Modifier.weight(1.4f),
          onClick = {
            val clean = storeName.trim()
            if (clean.isEmpty()) {
              viewModel.showDialog(AppDialogRequest("Name Required", "Enter a store name.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val selectedFormula = formulas.getOrNull(formulaIndex - 1)
            if (selectedFormula == null) {
              viewModel.showDialog(AppDialogRequest("Formula Required", "Choose a formula for this store.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val price = SarimFormat.parseSafeDouble(salePriceText)
            if (price == null || price !in 0.01..100_000_000.0) {
              viewModel.showDialog(AppDialogRequest("Invalid Price", "Sale price must be between 0.01 and 100,000,000.", tone = DialogTone.ERROR, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val dupStore = stores.any { it.storeKey != editingStoreKey && it.name.equals(clean, ignoreCase = true) }
            if (dupStore) {
              viewModel.showDialog(AppDialogRequest("Duplicate Store", "A store named \"$clean\" already exists.", tone = DialogTone.WARNING, cancelLabel = null))
              return@SarimPrimaryButton
            }
            val isEditing = editingStoreKey != null
            viewModel.showDialog(
              AppDialogRequest(
                title = if (isEditing) "Update Store?" else "Save Store?",
                body = "${if (isEditing) "Update" else "Add"} store \"$clean\"?\nFormula: ${selectedFormula.name}\nSale Price: ${SarimFormat.formatRs(price)}/kg",
                tone = if (isEditing) DialogTone.WARNING else DialogTone.CONFIRM,
                confirmLabel = if (isEditing) "Update" else "Save",
                onConfirm = {
                  viewModel.launchSafely(
                    errorTitle = "Save Store Failed",
                    successMessage = "Store saved",
                    onSuccess = {
                      editingStoreKey = null
                      storeName = ""
                      formulaIndex = 0
                      salePriceText = "0"
                    },
                  ) {
                    viewModel.repository.saveStore(clean, selectedFormula.id, price, editingStoreKey)
                  }
                },
              )
            )
          },
        )
      }
      Text(
        "A store's key is generated from its name when created (ZUBAIR becomes zubair) and stays locked.",
        color = palette.textMuted,
        fontSize = 11.5.sp,
      )
    }

    SarimCard {
      Text("Existing Stores (${stores.size})", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      stores.forEach { st ->
        val formulaName = formulas.firstOrNull { it.id == st.formulaId }?.name ?: "Unlinked"
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("${st.name} (${st.storeKey})", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Formula: $formulaName · Price: ${SarimFormat.formatRs(st.salePricePerKg)}/kg", color = palette.textMuted, fontSize = 12.sp)
          }
          IconButton(
            onClick = {
              editingStoreKey = st.storeKey
              storeName = st.name
              val fIdx = formulas.indexOfFirst { it.id == st.formulaId }
              formulaIndex = if (fIdx >= 0) fIdx + 1 else 0
              salePriceText = st.salePricePerKg.toString()
            },
          ) {
            Icon(Icons.Rounded.Edit, contentDescription = "Edit store", tint = palette.cyan)
          }
          IconButton(
            onClick = {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Delete Store",
                  body = "Delete store ${st.name}?",
                  tone = DialogTone.PERMANENT,
                  confirmLabel = "Delete",
                  onConfirm = {
                    viewModel.launchSafely(errorTitle = "Delete Store Failed") {
                      viewModel.repository.deleteStore(st.storeKey)
                    }
                  },
                )
              )
            },
          ) {
            Icon(Icons.Rounded.Delete, contentDescription = "Delete store", tint = palette.red)
          }
        }
        HorizontalDivider(color = palette.border)
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
fun CloseFinancialYearScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val scope = rememberCoroutineScope()
  var password by remember { mutableStateOf("") }
  var showPassword by remember { mutableStateOf(false) }
  var isProcessing by remember { mutableStateOf(false) }
  var progress by remember { mutableFloatStateOf(0f) }
  var stageText by remember { mutableStateOf("") }
  var compactedCount by remember { mutableIntStateOf(0) }
  var isComplete by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Header band
    SarimCard(borderColor = palette.red.copy(alpha = 0.6f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.Warning, contentDescription = null, tint = palette.red, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CLOSE FINANCIAL YEAR", color = palette.red, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            SarimChip(if (isComplete) "DONE" else "PREVIEW", if (isComplete) palette.green else palette.red)
          }
          Text("Compact all records into opening balances", color = palette.textMuted, fontSize = 12.5.sp)
        }
      }
    }

    // Preview Grid
    SarimCard {
      SarimFieldLabel("Compaction Preview")
      PreviewRow("Sales & Rep Records", "${state.sales.count { !it.isDeleted }} entries")
      PreviewRow("Production Entries", "${state.productionEntries.count { !it.isDeleted }} entries")
      PreviewRow("Daily Ledger Entries", "${state.dailyLedger.count { !it.isDeleted }} entries")
      PreviewRow("Payment Transactions", "${state.paymentTransactions.count { !it.isDeleted }} entries")
      PreviewRow("Customer Debt Rolled Forward", SarimFormat.formatRs(state.customers.filter { !it.isDeleted }.sumOf { it.currentCredit }))
    }

    SarimCallout(
      text = "Irreversible — original records will be compacted into opening balances. Enter your account password to encrypt the year-end backup and confirm this action.",
      toneColor = palette.red,
    )

    if (!isProcessing && !isComplete) {
      SarimCard {
        SarimTextField(
          value = password,
          onValueChange = { password = it },
          label = "Account password",
          placeholder = "Account password",
          isPassword = true,
          passwordVisible = showPassword,
          trailingContent = {
            IconButton(onClick = { showPassword = !showPassword }) {
              Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = "Toggle password", tint = palette.textMuted)
            }
          },
          testTag = "close_year_confirm_input",
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          SarimSecondaryButton(
            text = "Cancel",
            modifier = Modifier.weight(1f),
            onClick = { viewModel.closeStandalone() },
          )
          SarimDangerButton(
            text = "Close Financial Year",
            enabled = password.isNotBlank(),
            modifier = Modifier.weight(1.4f),
            onClick = {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Close Financial Year?",
                  body = "This will verify your password, compact historical transactions into opening balances, and broadcast the year close to all devices.\nThis action cannot be undone.",
                  tone = DialogTone.PERMANENT,
                  confirmLabel = "Close Year",
                  onConfirm = {
                    isProcessing = true
                    scope.launch {
                      try {
                        stageText = "Verifying password…"; progress = 0.1f
                        val pwdCheck = viewModel.verifyPasswordForSensitiveAction(password)
                        if (pwdCheck.isFailure) {
                          isProcessing = false
                          viewModel.showDialog(
                            AppDialogRequest(
                              title = "Incorrect Password",
                              body = pwdCheck.exceptionOrNull()?.message ?: "Incorrect password. Please try again.",
                              tone = DialogTone.ERROR,
                              confirmLabel = "OK",
                              cancelLabel = null,
                            )
                          )
                          return@launch
                        }
                        stageText = "Initializing…"; progress = 0.25f; delay(250)
                        stageText = "Exporting year-end snapshot…"; progress = 0.5f; delay(250)
                        stageText = "Compacting opening balances…"; progress = 0.8f
                        compactedCount = viewModel.closeFinancialYearAndBroadcast()
                        progress = 1f
                        isProcessing = false
                        isComplete = true
                      } catch (e: CancellationException) {
                        throw e
                      } catch (e: Exception) {
                        isProcessing = false
                        viewModel.showDialog(
                          AppDialogRequest(
                            title = "Close Year Failed",
                            body = e.localizedMessage ?: "Unable to compact financial year.",
                            tone = DialogTone.ERROR,
                            confirmLabel = "OK",
                            cancelLabel = null,
                          )
                        )
                      }
                    }
                  },
                )
              )
            },
          )
        }
      }
    }

    if (isProcessing) {
      SarimCard {
        Text("Processing: $stageText (${(progress * 100).toInt()}%)", color = palette.amber, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = palette.red)
      }
    }

    if (isComplete) {
      SarimCard(borderColor = palette.green.copy(alpha = 0.6f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = palette.green, modifier = Modifier.size(28.dp))
          Column {
            Text("Financial Year Closed", color = palette.green, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Compacted $compactedCount historical records cleanly into opening balances.", color = palette.textMuted, fontSize = 12.5.sp)
          }
        }
        SarimPrimaryButton(text = "Done", onClick = { viewModel.closeStandalone() })
      }
    }
  }
}

@Composable
private fun PreviewRow(label: String, value: String) {
  val palette = LocalSarimPalette.current
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = palette.textMuted, fontSize = 13.sp)
    Text(value, color = palette.textPrimary, style = TabularNumberStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
  }
}
