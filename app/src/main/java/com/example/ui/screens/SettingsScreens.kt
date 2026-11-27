package com.example.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.model.HapticIntensity
import com.example.data.model.SoundIntensity
import com.example.data.model.StandaloneScreen
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
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
    // 1. Data & Sync
    SarimFieldLabel("Data & Sync")
    SarimCard(contentPadding = 4.dp) {
      SettingsRow("SYNC DATA", Icons.Filled.Sync, palette.green) { viewModel.syncDataNow() }
      HorizontalDivider(color = palette.border)
      SettingsRow("BACKUP & RESTORE", Icons.Filled.Backup, palette.cyan) { viewModel.openStandalone(StandaloneScreen.BackupRestore) }
      HorizontalDivider(color = palette.border)
      SettingsRow("RECYCLE BIN", Icons.Filled.DeleteOutline, palette.red) { viewModel.openStandalone(StandaloneScreen.RecycleBin) }
      HorizontalDivider(color = palette.border)
      SettingsRow("DATABASE STRUCTURE", Icons.Filled.Storage, palette.purple) { viewModel.openStandalone(StandaloneScreen.DatabaseStructure) }
    }

    // 2. Appearance & Device
    SarimFieldLabel("Appearance & Device")
    SarimCard(contentPadding = 4.dp) {
      SettingsRow(state.prefs.themeMode.label, Icons.Filled.Palette, palette.amber) { viewModel.cycleTheme() }
      HorizontalDivider(color = palette.border)
      SettingsRow("SOUND & HAPTICS", Icons.Filled.Vibration, palette.cyan) { viewModel.openStandalone(StandaloneScreen.SoundHaptics) }
    }

    // 3. Security
    SarimFieldLabel("Security")
    SarimCard(contentPadding = 4.dp) {
      SettingsRow(
        label = if (state.prefs.fingerprintLockEnabled) "DISABLE FINGERPRINT LOCK" else "ENABLE FINGERPRINT LOCK",
        icon = Icons.Filled.Fingerprint,
        iconColor = palette.green,
      ) { viewModel.toggleFingerprintLock() }
      if (state.isAdmin) {
        HorizontalDivider(color = palette.border)
        SettingsRow("APP ACCOUNTS", Icons.Filled.ManageAccounts, palette.purple) { viewModel.openStandalone(StandaloneScreen.AppAccounts) }
      }
    }

    // 4. Business
    if (state.isAdmin) {
      SarimFieldLabel("Business")
      SarimCard(contentPadding = 4.dp) {
        SettingsRow("STORE MANAGER", Icons.Filled.Storefront, palette.green) { viewModel.openStandalone(StandaloneScreen.StoreManager) }
      }

      // 5. Danger Zone
      SarimFieldLabel("Danger Zone")
      SarimCard(borderColor = palette.red.copy(alpha = 0.4f), contentPadding = 4.dp) {
        SettingsRow("CLOSE FINANCIAL YEAR", Icons.Filled.Warning, palette.red, textColor = palette.red) {
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
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = palette.textMuted)
  }
}

@Composable
fun BackupRestoreScreen(
  viewModel: SarimViewModel,
) {
  val context = LocalContext.current
  val palette = LocalSarimPalette.current
  val scope = rememberCoroutineScope()

  val createDocLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/json"),
  ) { uri ->
    if (uri != null) {
      scope.launch {
        try {
          val json = viewModel.syncRepo.exportBackupJson()
          context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
          }
          viewModel.showDialog(AppDialogRequest("Backup Saved", "Encrypted backup exported successfully.", tone = DialogTone.SUCCESS, cancelLabel = null))
        } catch (e: Exception) {
          viewModel.showDialog(AppDialogRequest("Backup Failed", e.message ?: "Unable to write backup file.", tone = DialogTone.ERROR, cancelLabel = null))
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
          val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
          val res = viewModel.syncRepo.restoreBackupJson(raw)
          res.fold(
            onSuccess = { count ->
              viewModel.refreshDiagnostics()
              viewModel.showDialog(AppDialogRequest("Restore Complete", "Restored $count record(s) from backup.", tone = DialogTone.SUCCESS, cancelLabel = null))
            },
            onFailure = { err ->
              viewModel.showDialog(AppDialogRequest("Restore Failed", err.message ?: "Invalid backup file.", tone = DialogTone.ERROR, cancelLabel = null))
            }
          )
        } catch (e: Exception) {
          viewModel.showDialog(AppDialogRequest("Restore Failed", e.message ?: "Unable to read file.", tone = DialogTone.ERROR, cancelLabel = null))
        }
      }
    }
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
      Text("Save a local copy of all data to your device", color = palette.textMuted, fontSize = 13.sp)
      SarimPrimaryButton(
        text = "Backup to Phone",
        onClick = {
          runCatching {
            createDocLauncher.launch("gull_zubair_backup_${System.currentTimeMillis()}.gznd")
          }.onFailure { err ->
            viewModel.showDialog(AppDialogRequest("Backup Unavailable", err.localizedMessage ?: "No file picker available.", tone = DialogTone.ERROR, cancelLabel = null))
          }
        },
      )
      SarimSecondaryButton(
        text = "Quick Share Backup File",
        modifier = Modifier.fillMaxWidth(),
        onClick = {
          viewModel.launchSafely(errorTitle = "Quick Share Failed") {
            val json = viewModel.syncRepo.exportBackupJson()
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "gull_zubair_backup.gznd").apply { writeText(json) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
              type = "application/json"
              putExtra(Intent.EXTRA_STREAM, uri)
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }
        },
      )
    }

    SarimCard {
      SarimFieldLabel("Restore")
      Text("Load a previously saved backup file (.gznd or .json)", color = palette.textMuted, fontSize = 13.sp)
      SarimSecondaryButton(
        text = "Restore from Phone",
        icon = Icons.Filled.FolderOpen,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
          runCatching {
            openDocLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
          }.onFailure { err ->
            viewModel.showDialog(AppDialogRequest("Restore Unavailable", err.localizedMessage ?: "No file picker available.", tone = DialogTone.ERROR, cancelLabel = null))
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
        icon = Icons.Filled.DeleteForever,
        enabled = state.recycleBinItems.isNotEmpty(),
        modifier = Modifier.weight(1f),
        onClick = {
          viewModel.showDialog(
            AppDialogRequest(
              title = "Empty Recycle Bin",
              body = "Permanently erase all ${state.recycleBinItems.size} deleted records? This cannot be undone.",
              tone = DialogTone.PERMANENT,
              confirmLabel = "Erase All",
              onConfirm = {
                viewModel.launchSafely(
                  errorTitle = "Empty Recycle Bin Failed",
                  successMessage = "Recycle Bin emptied",
                ) {
                  viewModel.syncRepo.emptyRecycleBin()
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
                viewModel.launchSafely(
                  errorTitle = "Restore Failed",
                  successMessage = "Restored record",
                ) {
                  viewModel.syncRepo.restoreRecycleItem(rec)
                  viewModel.refreshDiagnostics()
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

@Composable
fun DatabaseStructureScreen(
  state: SarimUiState,
  viewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("Collections & Sync Queue", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      SarimSecondaryButton(text = "Refresh", icon = Icons.Filled.Refresh, onClick = { viewModel.refreshDiagnostics() })
    }

    if (state.dbStructure.isEmpty()) {
      SarimEmptyState("Loading database structure…", "Inspecting Room tables and Firestore sync state.")
    } else {
      state.dbStructure.forEach { col ->
        SarimCard(contentPadding = 12.dp) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
              Text(col.name, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
              Text("Active: ${col.activeCount} · Deleted: ${col.deletedCount}", color = palette.textMuted, fontSize = 12.sp)
            }
            SarimChip(
              text = "${col.pendingSyncCount} pending",
              color = if (col.pendingSyncCount > 0) palette.amber else palette.green,
            )
          }
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
    ExposedDropdownMenuBox(
      expanded = expanded,
      onExpandedChange = onExpandedChange,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, if (expanded) accentColor else palette.border),
        modifier = Modifier
          .fillMaxWidth()
          .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
          .defaultMinSize(minHeight = 52.dp)
          .clip(RoundedCornerShape(12.dp))
          .testTag(testTag)
          .clickable { onExpandedChange(!expanded) },
      ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text(selectedLabel, color = accentColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(selectedDescription, color = palette.textMuted, fontSize = 12.sp)
          }
          ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
      }
      ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = { onExpandedChange(false) },
        modifier = Modifier
          .exposedDropdownSize(matchTextFieldWidth = true)
          .background(palette.surfaceElevated)
          .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
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
                  Icon(Icons.Filled.Check, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
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
