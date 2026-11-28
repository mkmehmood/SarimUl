package com.example.ui.screens

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.model.HapticIntensity
import com.example.data.model.SoundIntensity
import com.example.data.model.StandaloneScreen
import com.example.data.remote.DbQuotaStats
import com.example.ui.components.*
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.example.util.DialogChoice
import com.example.data.backup.SarimBackupCrypto
import com.example.data.backup.SarimBackupService
import com.example.data.backup.RestoreSummary
import com.example.data.backup.BackupPreview
import com.example.data.backup.BackupCryptoException
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (!state.isLockedAccount) {
      // 1. Data & Sync
      SarimFieldLabel("Data & Sync")
      SarimCard(contentPadding = 4.dp) {
        SettingsRow("SYNC DATA", Icons.Rounded.Sync, palette.green) {
          viewModel.showDialog(
            AppDialogRequest(
              title = "Sync from Cloud",
              body = "Sync this device with the latest cloud data?\n\n• Local records are compared with cloud records\n• The newer version of each record wins\n• This device only — other devices are not changed",
              confirmLabel = "Update from Cloud",
              onConfirm = { viewModel.syncDataNow() },
            )
          )
        }
        HorizontalDivider(color = palette.border)
        SettingsRow("BACKUP & RESTORE", Icons.Rounded.Backup, palette.cyan) { viewModel.openStandalone(StandaloneScreen.BackupRestore) }
        HorizontalDivider(color = palette.border)
        SettingsRow("RECYCLE BIN", Icons.Rounded.DeleteOutline, palette.red) { viewModel.openStandalone(StandaloneScreen.RecycleBin) }
        HorizontalDivider(color = palette.border)
        SettingsRow("DATABASE STRUCTURE", Icons.Rounded.Storage, palette.purple) { viewModel.openStandalone(StandaloneScreen.DatabaseStructure) }
      }
    }

    // 2. Appearance & Device
    SarimFieldLabel("Appearance & Device")
    SarimCard(contentPadding = 4.dp) {
      SettingsRow(state.prefs.themeMode.label, Icons.Rounded.Palette, palette.amber) { viewModel.cycleTheme() }
      HorizontalDivider(color = palette.border)
      SettingsRow("SOUND & HAPTICS", Icons.Rounded.Vibration, palette.cyan) { viewModel.openStandalone(StandaloneScreen.SoundHaptics) }
    }

    // 3. Security
    SarimFieldLabel("Security")
    SarimCard(contentPadding = 4.dp) {
      SettingsRow(
        label = if (state.prefs.fingerprintLockEnabled) "DISABLE FINGERPRINT LOCK" else "ENABLE FINGERPRINT LOCK",
        icon = Icons.Rounded.Fingerprint,
        iconColor = palette.green,
      ) { viewModel.toggleFingerprintLock() }
      if (state.isAdmin) {
        HorizontalDivider(color = palette.border)
        SettingsRow("APP ACCOUNTS", Icons.Rounded.ManageAccounts, palette.purple) { viewModel.openStandalone(StandaloneScreen.AppAccounts) }
      }
    }

    // 4. Business
    if (state.isAdmin) {
      SarimFieldLabel("Business")
      SarimCard(contentPadding = 4.dp) {
        SettingsRow("STORE MANAGER", Icons.Rounded.Storefront, palette.green) { viewModel.openStandalone(StandaloneScreen.StoreManager) }
      }

      // 5. Danger Zone
      SarimFieldLabel("Danger Zone")
      SarimCard(borderColor = palette.red.copy(alpha = 0.4f), contentPadding = 4.dp) {
        SettingsRow("CLOSE FINANCIAL YEAR", Icons.Rounded.Warning, palette.red, textColor = palette.red) {
          viewModel.openStandalone(StandaloneScreen.CloseFinancialYear)
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun SettingsRow(
  label: String,
  icon: ImageVector,
  iconColor: Color,
  textColor: Color? = null,
  onClick: () -> Unit,
) {
  val palette = LocalSarimPalette.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 52.dp)
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
    Text(
      text = label,
      color = textColor ?: palette.textPrimary,
      fontSize = 13.5.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.5.sp,
      modifier = Modifier.weight(1f),
    )
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.textMuted)
  }
}

@Composable
fun BackupRestoreScreen(
  viewModel: SarimViewModel,
) {
  val context = LocalContext.current
  val palette = LocalSarimPalette.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }

  fun fail(title: String, message: String) =
    viewModel.showDialog(AppDialogRequest(title, message, tone = DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))

  fun summaryText(s: RestoreSummary): String =
    if (s.isYearClose) "The financial year close was reversed. ${s.added} record(s) were restored from the pre-close backup."
    else "Added ${s.added} new record(s), updated ${s.updated}, and kept ${s.skipped} that were already up to date."

  fun runMergeRestore(preview: BackupPreview) {
    viewModel.launchSafely(errorTitle = "Restore Failed") {
      val summary = viewModel.restoreMergeSecure(preview)
      viewModel.showDialog(AppDialogRequest("Restore Complete", summaryText(summary), tone = DialogTone.SUCCESS, confirmLabel = "OK", cancelLabel = null))
    }
  }

  fun runYearCloseRestore(preview: BackupPreview, honourDeletions: Boolean) {
    viewModel.launchSafely(errorTitle = "Restore Failed") {
      val summary = viewModel.restoreYearCloseSecure(preview, honourDeletions)
      viewModel.showDialog(AppDialogRequest("Year Close Reversed", summaryText(summary), tone = DialogTone.SUCCESS, confirmLabel = "OK", cancelLabel = null))
    }
  }

  fun confirmRestore(preview: BackupPreview) {
    if (!preview.isYearClose) {
      viewModel.showDialog(
        AppDialogRequest(
          title = "Restore From Encrypted Backup",
          body = "Restore data from this backup file?\n\n• Records are merged, not overwritten — your current data stays\n• Duplicates are automatically removed\n• Only new or newer records are applied\n• Records you deleted stay deleted",
          tone = DialogTone.WARNING,
          confirmLabel = "Restore & Merge",
          onConfirm = { runMergeRestore(preview) },
        )
      )
      return
    }
    val madeOn = if (preview.createdAt > 0L) SarimFormat.formatDate(preview.createdAt) else "an earlier date"
    viewModel.showDialog(
      AppDialogRequest(
        title = "Reverse Financial Year Close",
        body = "This backup was created by Close Financial Year on $madeOn.\n\nRestoring it will:\n• REPLACE all current data with the pre-close snapshot\n• Remove all merged opening-balance records\n• Reverse the financial year close counter\n• Keep records created after the backup was taken",
        tone = DialogTone.PERMANENT,
        confirmLabel = "Reverse Year Close",
        onConfirm = {
          if (preview.postCloseDeletions > 0) {
            val n = preview.postCloseDeletions
            viewModel.showDialog(
              AppDialogRequest(
                title = "Post-Close Deletions Detected",
                body = "$n record${if (n != 1) "s" else ""} in this backup ${if (n != 1) "were" else "was"} deleted after the year-close backup was taken. Which behaviour do you want?",
                tone = DialogTone.WARNING,
                choices = listOf(
                  DialogChoice("keep", "Keep deletions", "Those records stay deleted (recommended if the deletions were intentional)"),
                  DialogChoice("restore", "Restore everything", "All $n deleted record${if (n != 1) "s" else ""} are brought back"),
                ),
                onChoiceSelected = { choice -> runYearCloseRestore(preview, honourDeletions = choice == "keep") },
              )
            )
          } else {
            runYearCloseRestore(preview, honourDeletions = true)
          }
        },
      )
    )
  }

  fun askPasswordAndOpen(bytes: ByteArray) {
    viewModel.showDialog(
      AppDialogRequest(
        title = "Enter Password to Decrypt",
        body = "This backup is encrypted with your account credentials.",
        tone = DialogTone.CONFIRM,
        requirePassword = true,
        confirmLabel = "Decrypt & Restore",
        onConfirm = { password ->
          scope.launch {
            try {
              val (email, uid) = viewModel.backupIdentity()
              confirmRestore(viewModel.backupService.openBackup(bytes, email, password, uid))
            } catch (e: BackupCryptoException) {
              fail("Restore Failed", SarimBackupCrypto.userMessage(e))
            } catch (e: Exception) {
              fail("Restore Failed", e.message ?: "Unable to read this backup.")
            }
          }
        },
      )
    )
  }

  val createDocLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
  ) { uri ->
    val bytes = pendingBackup
    pendingBackup = null
    if (uri != null && bytes != null) {
      scope.launch {
        try {
          withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
          viewModel.showDialog(AppDialogRequest("Backup Created", "Encrypted backup saved. The file needs your account password to restore.", tone = DialogTone.SUCCESS, confirmLabel = "OK", cancelLabel = null))
        } catch (e: Exception) {
          fail("Backup Failed", e.message ?: "Unable to write backup file.")
        }
      }
    }
  }

  val openDocLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      scope.launch {
        try {
          val displayName = withContext(Dispatchers.IO) {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
              val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: uri.lastPathSegment
          }
          if (!displayName.isNullOrBlank() && !displayName.trim().endsWith(".gznd", ignoreCase = true)) {
            fail("Unsupported file", "Only encrypted .gznd backup files can be restored.")
            return@launch
          }
          val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: ByteArray(0)
          if (SarimBackupService.isAllowedEncryptedUpload(displayName, bytes)) {
            askPasswordAndOpen(bytes)
          } else {
            fail("Unsupported file", "Only encrypted .gznd backup files can be restored.")
          }
        } catch (e: Exception) {
          fail("Restore Failed", e.message ?: "Unable to read file.")
        }
      }
    }
  }

  fun askPasswordAndBackup(share: Boolean) {
    viewModel.showDialog(
      AppDialogRequest(
        title = "Confirm Password",
        body = "Enter your account password to encrypt this backup file.",
        tone = DialogTone.CONFIRM,
        requirePassword = true,
        confirmLabel = if (share) "Encrypt & Share" else "Encrypt & Save",
        onConfirm = { password ->
          scope.launch {
            val check = viewModel.verifyPasswordForSensitiveAction(password)
            if (check.isFailure) {
              fail("Incorrect Password", check.exceptionOrNull()?.message ?: "Incorrect password. Please try again.")
              return@launch
            }
            try {
              val (email, uid) = viewModel.backupIdentity()
              val bytes = viewModel.backupService.createEncryptedBackup(email, password, uid)
              val name = "NaswarDealers_SecureBackup_${SarimFormat.dateInput(System.currentTimeMillis())}.gznd"
              if (share) {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, name).apply { writeBytes(bytes) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "application/octet-stream"
                  putExtra(Intent.EXTRA_STREAM, uri)
                  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share Backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              } else {
                pendingBackup = bytes
                createDocLauncher.launch(name)
              }
            } catch (e: Exception) {
              fail("Backup Failed", e.message ?: "Encryption failed.")
            }
          }
        },
      )
    )
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SarimCard {
      SarimFieldLabel("Backup")
      Text("Cloud Backup uploads your data to your account. Encrypted .gznd files use AES-256-GCM and need your account password to restore.", color = palette.textMuted, fontSize = 13.sp)
      SarimPrimaryButton(
        text = "Backup to Phone",
        onClick = {
          if (uiState.isOnline && !uiState.isOfflineSession) {
            viewModel.showDialog(
              AppDialogRequest(
                title = "Save Backup",
                body = "Choose how to back up your workspace data.\n\n• Cloud Backup saves to Firebase for all your devices.\n• Encrypted .gznd File saves an offline AES-256-GCM encrypted file protected with your account credentials.",
                tone = DialogTone.CONFIRM,
                choices = listOf(
                  DialogChoice("cloud", "Cloud Backup", "Upload latest changes to your cloud workspace"),
                  DialogChoice("file", "Download Encrypted File (.gznd)", "Save a password-encrypted backup file to phone"),
                ),
                cancelLabel = "Cancel",
                onChoiceSelected = { choice ->
                  when (choice) {
                    "cloud" -> viewModel.syncDataNow()
                    "file" -> runCatching { askPasswordAndBackup(share = false) }.onFailure { err ->
                      fail("Backup Unavailable", err.localizedMessage ?: "No file picker available.")
                    }
                  }
                },
              )
            )
          } else {
            runCatching { askPasswordAndBackup(share = false) }.onFailure { err ->
              fail("Backup Unavailable", err.localizedMessage ?: "No file picker available.")
            }
          }
        },
      )
      SarimSecondaryButton(
        text = "Quick Share Backup File",
        modifier = Modifier.fillMaxWidth(),
        onClick = { askPasswordAndBackup(share = true) },
      )
    }

    SarimCard {
      SarimFieldLabel("Restore")
      Text("Load an encrypted backup file (.gznd). Unencrypted JSON files are blocked. Records merge with your current data, or reverse a Financial Year close.", color = palette.textMuted, fontSize = 13.sp)
      SarimSecondaryButton(
        text = "Restore from Phone",
        icon = Icons.Rounded.FolderOpen,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
          runCatching {
            openDocLauncher.launch(arrayOf("application/octet-stream", "*/*"))
          }.onFailure { err ->
            fail("Restore Unavailable", err.localizedMessage ?: "No file picker available.")
          }
        },
      )
    }
  }
}

@Composable
fun RecycleBinScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val filterOptions = listOf("All Tabs", "Sales Tab", "Rep Tab", "Manufacturing Tab", "Calculator Tab", "Factory Tab", "Payments Tab", "Daily Ledger", "Formula Store")
  var filterIndex by remember { mutableIntStateOf(0) }

  LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }

  val scope = rememberCoroutineScope()
  val items = remember(state.recycleBinItems, filterIndex) {
    val selected = filterOptions[filterIndex]
    if (selected == "All Tabs") state.recycleBinItems
    else state.recycleBinItems.filter { it.tabCategory == selected }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      "${state.recycleBinItems.size} deleted records (kept for 90 days)",
      color = palette.textMuted,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
      Box(modifier = Modifier.weight(1.3f)) {
        SarimPopoverSelector(
          label = "Filter",
          selectedText = filterOptions[filterIndex],
          options = filterOptions,
          onSelect = { idx, _ -> filterIndex = idx },
          testTag = "recycleBinFilterBtn",
        )
      }
      SarimDangerButton(
        text = "Empty Bin",
        icon = Icons.Rounded.DeleteForever,
        enabled = items.isNotEmpty(),
        modifier = Modifier.weight(1f),
        onClick = {
          val targetLabel = if (filterIndex == 0) "all ${items.size} deleted record(s)" else "${items.size} deleted record(s) in ${filterOptions[filterIndex]}"
          viewModel.showDialog(
            AppDialogRequest(
              title = "Empty Recycle Bin",
              body = "Permanently erase $targetLabel? Linked records deleted together will also be removed. This cannot be undone.",
              tone = DialogTone.PERMANENT,
              confirmLabel = "Erase ${items.size}",
              onConfirm = {
                viewModel.launchSafely(
                  errorTitle = "Empty Recycle Bin Failed",
                  successMessage = "Recycle Bin emptied",
                ) {
                  viewModel.syncRepo.emptyRecycleBinFiltered(items)
                  viewModel.refreshDiagnostics()
                }
              },
            )
          )
        },
      )
    }

    SarimCallout(
      text = "Restore puts the record back to its original collection. Records expire after 90 days. Use Delete Forever to erase a record immediately and permanently.",
      toneColor = palette.amber,
    )

    if (items.isEmpty()) {
      SarimEmptyState(
        title = "Recycle Bin is empty.",
        subtitle = "Deleted transactions will appear here and can be restored within 90 days.",
      )
    } else {
      items.forEach { rec ->
        SarimCard {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
              SarimChip(rec.tabCategory, palette.cyan)
              Spacer(Modifier.height(4.dp))
              Text(rec.title, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
              Text("${rec.subtitle} · Deleted ${SarimFormat.formatDate(rec.deletedAt)}", color = palette.textMuted, fontSize = 11.5.sp)
            }
            Text(rec.amountText, color = palette.red, style = TabularNumberStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimSecondaryButton(
              text = "Restore",
              modifier = Modifier.weight(1f),
              onClick = {
                scope.launch {
                  val preview = viewModel.syncRepo.previewRecycleRestore(rec)
                  if (preview.blockedReason != null) {
                    viewModel.showDialog(
                      AppDialogRequest(
                        title = "Warning",
                        body = preview.blockedReason,
                        tone = DialogTone.WARNING,
                        confirmLabel = "OK",
                        cancelLabel = null,
                      )
                    )
                  } else {
                    val body = buildString {
                      append("Restore ${rec.title}?")
                      if (rec.amountText.isNotBlank()) append("\nAmount: ${rec.amountText}")
                      if (preview.notes.isNotEmpty()) {
                        append("\n\n")
                        append(preview.notes.joinToString("\n") { "• $it" })
                      }
                    }
                    viewModel.showDialog(
                      AppDialogRequest(
                        title = "Restore Record",
                        body = body,
                        tone = if (preview.groupSize > 1) DialogTone.WARNING else DialogTone.CONFIRM,
                        confirmLabel = "Restore",
                        onConfirm = {
                          viewModel.launchSafely(
                            errorTitle = "Restore Failed",
                            successMessage = "Restored record",
                          ) {
                            viewModel.syncRepo.restoreRecycleItem(rec)
                            viewModel.refreshDiagnostics()
                          }
                        },
                      )
                    )
                  }
                }
              },
            )
            SarimDangerButton(
              text = "Delete Forever",
              modifier = Modifier.weight(1f),
              onClick = {
                viewModel.showDialog(
                  AppDialogRequest(
                    title = "Delete Forever",
                    body = "Erase ${rec.title} permanently?",
                    tone = DialogTone.PERMANENT,
                    confirmLabel = "Erase",
                    onConfirm = {
                      viewModel.launchSafely(errorTitle = "Permanent Delete Failed") {
                        viewModel.syncRepo.deleteForeverRecycleItem(rec)
                        viewModel.refreshDiagnostics()
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
    Spacer(Modifier.height(24.dp))
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DatabaseStructureScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val sections = listOf("Collections", "Config Docs", "Listeners", "Summary")
  var selectedSection by remember { mutableStateOf("Collections") }
  LaunchedEffect(Unit) { viewModel.refreshDiagnostics(includeCloudDiagnostics = true) }

  val snap = state.dbStructureSnapshot
  val collections = snap?.collections ?: state.dbStructure
  val quota = snap?.dbStats ?: DbQuotaStats()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Database Structure", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text("${collections.size} synced collections", color = palette.textMuted, fontSize = 12.sp)
          if (snap != null) {
            SarimChip("Shard: ${snap.myDeviceShard}", palette.purple)
            SarimChip("↑ ${snap.totalUploaded} / ↓ ${snap.totalDownloaded}", palette.cyan)
          }
        }
      }
      IconButton(
        onClick = { viewModel.refreshDiagnostics(includeCloudDiagnostics = true) },
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("db_struct_refresh_btn"),
      ) {
        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh structure and cloud diagnostics", tint = palette.cyan)
      }
    }

    SarimToggleGroup(
      options = sections,
      selected = selectedSection,
      labelOf = { it },
      onSelect = { selectedSection = it },
      testTagPrefix = "db_struct_tab",
    )

    when (selectedSection) {
      "Collections" -> {
        val readRatio = (quota.reads.toFloat() / 50_000f).coerceIn(0f, 1f)
        val writeRatio = (quota.writes.toFloat() / 20_000f).coerceIn(0f, 1f)
        val readTone = when {
          quota.reads >= 48_000 -> palette.red
          quota.reads >= 40_000 -> palette.amber
          else -> palette.green
        }
        val writeTone = when {
          quota.writes >= 19_000 -> palette.red
          quota.writes >= 16_000 -> palette.amber
          else -> palette.cyan
        }

        SarimCard(contentPadding = 12.dp, modifier = Modifier.testTag("db_quota_card")) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("24h Firestore Quota", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
              text = if (quota.lastReset > 0L) "Since ${SarimFormat.formatTime(quota.lastReset)}" else "Today",
              color = palette.textMuted,
              fontSize = 11.5.sp,
              fontFamily = JetBrainsMonoFontFamily,
            )
          }
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Reads", color = palette.textMuted, fontSize = 12.sp)
              Text(
                "${quota.reads} / 50,000",
                color = readTone,
                style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
              )
            }
            LinearProgressIndicator(
              progress = { readRatio },
              modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
              color = readTone,
              trackColor = palette.surfaceElevated,
            )
          }
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Writes", color = palette.textMuted, fontSize = 12.sp)
              Text(
                "${quota.writes} / 20,000",
                color = writeTone,
                style = TabularNumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
              )
            }
            LinearProgressIndicator(
              progress = { writeRatio },
              modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
              color = writeTone,
              trackColor = palette.surfaceElevated,
            )
          }
          if (snap != null && snap.deviceId.isNotBlank()) {
            Text(
              text = "Device ID: ${snap.deviceId} · Shard: ${snap.myDeviceShard}",
              color = palette.textMuted,
              fontSize = 11.sp,
              fontFamily = JetBrainsMonoFontFamily,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        if (collections.isEmpty()) {
          SarimEmptyState("Loading database structure…", "Inspecting Room tables and Firestore sync state.")
        } else {
          collections.forEach { col ->
            val hasMismatch = col.fsCount != null && col.fsCount != col.activeCount
            SarimCard(
              contentPadding = 12.dp,
              borderColor = if (hasMismatch) palette.amber.copy(alpha = 0.7f) else null,
              modifier = Modifier.testTag("db_col_${col.name}"),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  modifier = Modifier.weight(1f),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(col.name, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                  SarimChip(
                    text = if (col.hasLiveListener) "LIVE" else "SNAPSHOT",
                    color = if (col.hasLiveListener) palette.cyan else palette.textMuted,
                  )
                  if (col.lockOnClose) {
                    SarimChip("LOCKED ON CLOSE", palette.amber)
                  }
                }
                SarimChip(
                  text = "${col.pendingSyncCount} pending",
                  color = if (col.pendingSyncCount > 0) palette.amber else palette.green,
                )
              }

              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  "Active: ${col.activeCount} · Deleted: ${col.deletedCount}",
                  color = palette.textMuted,
                  fontSize = 12.sp,
                )
                if (col.fsCount != null) {
                  SarimChip(
                    text = if (hasMismatch) "FS: ${col.fsCount} vs Local: ${col.activeCount} (MISMATCH)" else "FS: ${col.fsCount} = Local",
                    color = if (hasMismatch) palette.amber else palette.green,
                  )
                }
              }

              Text(
                text = "↑ ${col.uploadedCount} / ↓ ${col.downloadedCount} · Dirty: ${col.dirtyUuidCount} · Syncs: ${col.syncCount} · Last: ${if (col.lastSyncMs > 0L) SarimFormat.formatTime(col.lastSyncMs) else "Never"}",
                color = palette.textMuted,
                fontSize = 11.5.sp,
                style = TabularNumberStyle.copy(fontSize = 11.5.sp),
              )

              Text(
                text = "${col.sqliteKey} → ${col.jsVar}",
                color = palette.cyan,
                fontSize = 11.sp,
                fontFamily = JetBrainsMonoFontFamily,
              )
              if (col.description.isNotBlank()) {
                Text(col.description, color = palette.textMuted, fontSize = 11.5.sp)
              }
            }
          }
        }
      }

      "Config Docs" -> {
        SarimCard(contentPadding = 12.dp) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            SarimChip(
              text = if (snap?.isCloudLive == true) "Live Cloud Data" else "Local Schema Only",
              color = if (snap?.isCloudLive == true) palette.green else palette.amber,
            )
            Text(
              text = snap?.lastCloudCheckAt?.let { "Checked ${SarimFormat.formatTime(it)}" } ?: "Tap refresh to probe cloud",
              color = palette.textMuted,
              fontSize = 11.5.sp,
            )
          }
        }

        val configDocs = snap?.configDocs.orEmpty()
        if (configDocs.isEmpty()) {
          SarimEmptyState("Loading config docs…", "Inspecting singleton configuration documents.")
        } else {
          configDocs.forEach { doc ->
            val statusLabel = when (doc.existsInCloud) {
              true -> "EXISTS"
              false -> "MISSING"
              null -> "LOCAL ONLY"
            }
            val statusColor = when (doc.existsInCloud) {
              true -> palette.green
              false -> palette.red
              null -> palette.textMuted
            }
            SarimCard(
              contentPadding = 12.dp,
              modifier = Modifier.testTag("db_cfg_${doc.path.replace('/', '_')}"),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = doc.path,
                  color = palette.cyan,
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.5.sp,
                  fontFamily = JetBrainsMonoFontFamily,
                )
                SarimChip(statusLabel, statusColor)
              }
              Text(doc.description, color = palette.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
              if (doc.listenerName.isNotBlank()) {
                Text(
                  text = "Listener: ${doc.listenerName}",
                  color = palette.textMuted,
                  fontSize = 11.sp,
                  fontFamily = JetBrainsMonoFontFamily,
                )
              }
              if (doc.mappings.isNotEmpty()) {
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  doc.mappings.forEach { (sqliteK, fsK) ->
                    SarimChip("$sqliteK → $fsK", palette.purple)
                  }
                }
              }
              if (doc.expectedFields.isNotEmpty()) {
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  doc.expectedFields.forEach { field ->
                    val present = field in doc.presentFields
                    val preview = doc.fieldPreviews[field]?.takeIf { it.isNotBlank() }
                    val chipText = if (preview != null) "$field ($preview)" else field
                    val chipColor = when {
                      doc.existsInCloud == null -> palette.textMuted
                      present -> palette.green
                      else -> palette.amber
                    }
                    SarimChip(chipText, chipColor)
                  }
                }
              }
            }
          }
        }
      }

      "Listeners" -> {
        val totalPending = collections.sumOf { it.pendingSyncCount }
        val listeners = snap?.listeners.orEmpty()
        SarimCard(contentPadding = 12.dp) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("${listeners.size} Active Realtime Listeners", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
            SarimChip(
              text = if (state.isOfflineSession) "Offline Cache" else "Live Sync",
              color = if (state.isOfflineSession) palette.amber else palette.green,
            )
          }
          Text(
            text = "Last Updated: ${SarimFormat.formatTime(state.lastUpdatedMillis)} · Pending Queue: $totalPending",
            color = palette.textMuted,
            fontSize = 12.sp,
          )
        }

        listeners.forEachIndexed { idx, listener ->
          SarimCard(
            contentPadding = 12.dp,
            modifier = Modifier.testTag("db_listener_$idx"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SarimChip(
                  text = listener.kind,
                  color = if (listener.kind == "DOC") palette.cyan else palette.purple,
                )
                Text(
                  text = listener.name,
                  color = palette.textPrimary,
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.5.sp,
                  fontFamily = JetBrainsMonoFontFamily,
                )
              }
              SarimChip(
                text = if (listener.isLive && !state.isOfflineSession) "ACTIVE" else "PAUSED",
                color = if (listener.isLive && !state.isOfflineSession) palette.green else palette.amber,
              )
            }
            Text(listener.purpose, color = palette.textPrimary, fontSize = 12.5.sp)
            Text(
              text = "Handler: ${listener.handler}",
              color = palette.cyan,
              fontSize = 11.sp,
              fontFamily = JetBrainsMonoFontFamily,
            )
            Text("Fires when: ${listener.triggerRule}", color = palette.textMuted, fontSize = 11.5.sp)
          }
        }
      }

      else -> {
        val activeSum = collections.sumOf { it.activeCount }
        val deletedSum = collections.sumOf { it.deletedCount }
        val pendingSum = collections.sumOf { it.pendingSyncCount }
        val totalUp = snap?.totalUploaded ?: collections.sumOf { it.uploadedCount }
        val totalDown = snap?.totalDownloaded ?: collections.sumOf { it.downloadedCount }

        SarimCard(contentPadding = 12.dp) {
          Text("Database Summary", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Collections", collections.size.toString(), Modifier.weight(1f), palette.cyan)
            SarimStatTile("Active Docs", activeSum.toString(), Modifier.weight(1f), palette.green)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Deleted", deletedSum.toString(), Modifier.weight(1f), palette.red)
            SarimStatTile("Pending Sync", pendingSum.toString(), Modifier.weight(1f), palette.amber)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("Total Uploaded", totalUp.toString(), Modifier.weight(1f), palette.purple)
            SarimStatTile("Total Downloaded", totalDown.toString(), Modifier.weight(1f), palette.cyan)
          }
        }

        SarimCard(contentPadding = 12.dp) {
          Text("Collection Mapping Table", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          Row(modifier = Modifier.fillMaxWidth()) {
            Text("Firestore", color = palette.textMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
            Text("SQLite", color = palette.textMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
            Text("JS Var", color = palette.textMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
            Text("FS / Loc", color = palette.textMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
          }
          HorizontalDivider(color = palette.border)
          collections.forEach { col ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(col.name, color = palette.cyan, fontSize = 10.5.sp, fontFamily = JetBrainsMonoFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.1f))
              Text(col.sqliteKey, color = palette.textPrimary, fontSize = 10.5.sp, fontFamily = JetBrainsMonoFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.1f))
              Text(col.jsVar, color = palette.textMuted, fontSize = 10.5.sp, fontFamily = JetBrainsMonoFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.9f))
              Text(
                text = "${col.fsCount?.toString() ?: "—"} / ${col.activeCount}",
                color = if (col.fsCount != null && col.fsCount != col.activeCount) palette.amber else palette.green,
                style = TabularNumberStyle.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(0.8f),
              )
            }
          }
        }

        SarimCard(contentPadding = 12.dp) {
          Text("Firestore Schema Tree", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          val schemaTree = remember {
            """
users/{uid} (root workspace doc: roles, approved, ownerUid, lastWrite)
├── settings/
│   ├── config (settings, fyCloseCount, reps init)
│   ├── team (reps directory)
│   └── yearCloseSignal (close / restore broadcast)
├── stores/list (stores + formulas + pricing)
├── formulas/config (formulas + savedMixCalcs)
├── categories/list (operating/field/commission categories)
├── daily_ledger/entries (cashbook singleton)
├── account/info (profile metadata)
├── subcollections: production, sales, rep, customers, clients,
│   transactions, entities, expenses, inventory, factory,
│   returns, calculator, pending, deletions, photos
└── app_accounts/ (top-level role & workspace ownership registry)
            """.trimIndent()
          }
          Text(
            text = schemaTree,
            color = palette.cyan,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = JetBrainsMonoFontFamily,
          )
        }

        if (state.isAdmin) {
          SarimDangerButton(
            text = "Deduplicate All Data",
            icon = Icons.Rounded.DeleteSweep,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
              viewModel.showDialog(
                AppDialogRequest(
                  title = "Deduplicate All Data",
                  body = "Run a full deduplication scan?\n• Scan all records across every collection\n• Remove exact duplicate entries (keeping the newest version)\n• Sync cleaned data to the cloud\nThis cannot be undone.",
                  tone = DialogTone.PERMANENT,
                  confirmLabel = "Run Cleanup",
                  onConfirm = { viewModel.runDeduplicationCleanup() },
                )
              )
            },
          )
        }

        SarimCard(contentPadding = 12.dp) {
          Text("Financial Year Status", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarimStatTile("FY Close Count", (snap?.fyCloseCount ?: 0).toString(), Modifier.weight(1f), palette.purple)
            SarimStatTile("Last Closed", snap?.lastYearClosedDate ?: "Never", Modifier.weight(1.4f), palette.amber)
          }
          Text(
            text = "Year-Close Broadcast Signal: settings/yearCloseSignal",
            color = palette.cyan,
            fontSize = 11.5.sp,
            fontFamily = JetBrainsMonoFontFamily,
          )
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
fun SoundHapticsScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  var hapticMenu by remember { mutableStateOf(false) }
  var soundMenu by remember { mutableStateOf(false) }
  var hapticStatus by remember { mutableStateOf("") }
  var soundStatus by remember { mutableStateOf("") }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SoundHapticsDropdownCard(
      title = "Haptic Feedback Intensity",
      subtitle = "Tactile vibration for taps and toggles.",
      buttonText = "Test",
      onButtonClick = { hapticStatus = viewModel.feedbackManager.triggerHaptic(state.prefs.hapticIntensity) },
      selectedLabel = state.prefs.hapticIntensity.label,
      selectedDescription = state.prefs.hapticIntensity.description,
      accentColor = palette.green,
      expanded = hapticMenu,
      onExpandedChange = { hapticMenu = it },
      options = HapticIntensity.entries.map { it.label to it.description },
      onSelect = { idx ->
        val level = HapticIntensity.entries[idx]
        viewModel.setHapticIntensity(level)
        hapticStatus = viewModel.feedbackManager.triggerHaptic(level)
      },
      statusText = hapticStatus,
      statusColor = palette.cyan,
      testTag = "haptic_intensity_dropdown",
    )

    SoundHapticsDropdownCard(
      title = "Sound Feedback Intensity",
      subtitle = "Crisp confirmation tone on UI interactions.",
      buttonText = "Play",
      onButtonClick = { soundStatus = viewModel.feedbackManager.triggerSound(state.prefs.soundIntensity) },
      selectedLabel = state.prefs.soundIntensity.label,
      selectedDescription = state.prefs.soundIntensity.description,
      accentColor = palette.cyan,
      expanded = soundMenu,
      onExpandedChange = { soundMenu = it },
      options = SoundIntensity.entries.map { it.label to it.description },
      onSelect = { idx ->
        val level = SoundIntensity.entries[idx]
        viewModel.setSoundIntensity(level)
        soundStatus = viewModel.feedbackManager.triggerSound(level)
      },
      statusText = soundStatus,
      statusColor = palette.green,
      testTag = "sound_intensity_dropdown",
    )

    Text(
      "These settings apply to this device only and cover dialogs, switches and toggle buttons.",
      color = palette.textMuted,
      fontSize = 12.sp,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundHapticsDropdownCard(
  title: String,
  subtitle: String,
  buttonText: String,
  onButtonClick: () -> Unit,
  selectedLabel: String,
  selectedDescription: String,
  accentColor: Color,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  options: List<Pair<String, String>>,
  onSelect: (Int) -> Unit,
  statusText: String,
  statusColor: Color,
  testTag: String,
) {
  val palette = LocalSarimPalette.current
  SarimCard {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(title, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(subtitle, color = palette.textMuted, fontSize = 12.sp)
      }
      SarimSecondaryButton(text = buttonText, onClick = onButtonClick, testTag = "${testTag}_test")
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    Box(modifier = Modifier.fillMaxWidth()) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, if (expanded) accentColor else palette.border),
        modifier = Modifier
          .fillMaxWidth()
          .onSizeChanged { anchorWidthPx = it.width }
          .defaultMinSize(minHeight = SarimFieldDefaults.MinHeight)
          .clip(SarimFieldDefaults.Shape)
          .testTag(testTag)
          .clickable { onExpandedChange(!expanded) },
      ) {
        Row(modifier = Modifier.padding(horizontal = SarimFieldDefaults.HorizontalPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text(selectedLabel, color = accentColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(selectedDescription, color = palette.textMuted, fontSize = 12.sp)
          }
          Icon(
            Icons.Rounded.ArrowDropDown,
            contentDescription = if (expanded) "Hide options" else "Show options",
            tint = palette.textMuted,
            modifier = Modifier.size(24.dp).rotate(if (expanded) 180f else 0f),
          )
        }
      }
      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { onExpandedChange(false) },
        modifier = Modifier
          .width(with(density) { anchorWidthPx.toDp() })
          .background(palette.surfaceElevated)
          .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
      ) {
        options.forEachIndexed { idx, (optLabel, optDesc) ->
          val isSelected = optLabel == selectedLabel
          DropdownMenuItem(
            modifier = Modifier
              .fillMaxWidth()
              .defaultMinSize(minHeight = 50.dp)
              .testTag("${testTag}_item_$idx"),
            text = {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    optLabel,
                    color = if (isSelected) accentColor else palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                  )
                  Text(optDesc, color = palette.textMuted, fontSize = 11.5.sp)
                }
                if (isSelected) {
                  Icon(Icons.Rounded.Check, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
                }
              }
            },
            onClick = {
              onSelect(idx)
              onExpandedChange(false)
            },
          )
          if (idx < options.lastIndex) {
            HorizontalDivider(color = palette.border)
          }
        }
      }
    }
    if (statusText.isNotBlank()) Text(statusText, color = statusColor, fontSize = 12.sp)
  }
}
