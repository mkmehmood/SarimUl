package com.example.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.StandaloneScreen
import com.example.ui.components.*
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.viewmodel.BaseInventoryViewModel
import com.example.viewmodel.InventorySortMode
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

@Composable
fun InventoryScreen(
  appState: SarimUiState,
  inventoryViewModel: BaseInventoryViewModel,
  sarimViewModel: SarimViewModel,
) {
  val palette = LocalSarimPalette.current
  val invState by inventoryViewModel.uiState.collectAsStateWithLifecycle()
  val hideMoney = appState.hideMoneyFigures

  LaunchedEffect(invState.statusMessage, invState.errorMessage) {
    val msg = invState.errorMessage ?: invState.statusMessage
    if (!msg.isNullOrBlank()) {
      sarimViewModel.postStatus(msg)
      inventoryViewModel.clearMessages()
    }
  }

  val showSkeleton = appState.isSyncing || invState.isLoading

  Crossfade(
    targetState = showSkeleton,
    animationSpec = tween(220),
    label = "inventory_skeleton_crossfade",
  ) { loading ->
    if (loading) {
      InventorySkeletonContent(isSyncing = appState.isSyncing)
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        item {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              ShimmerHeading("Inventory", fontSizeSp = 26)
              Text(
                "${invState.materials.size} active materials · ${invState.purchases.size} purchase lots",
                color = palette.textMuted,
                fontSize = 11.5.sp,
              )
            }
            Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (!appState.isOfflineSession) {
                IconButton(
                  onClick = { sarimViewModel.syncDataNow() },
                  modifier = Modifier.size(48.dp).testTag("inv_sync_btn"),
                ) {
                  Icon(Icons.Rounded.Sync, contentDescription = "Sync inventory with Firestore", tint = palette.cyan)
                }
              }
              SarimSecondaryButton(
                text = "Transfer",
                icon = Icons.Rounded.SwapHoriz,
                onClick = { sarimViewModel.openStandalone(StandaloneScreen.StockTransfer) },
                testTag = "inv_stock_transfer_btn",
              )
            }
          }
        }

    // KPI Summary
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimStatTile(
            label = "Total Stock Value",
            value = SarimFormat.formatRs(invState.totalStockValue, true, hideMoney),
            accentColor = palette.green,
            modifier = Modifier.weight(1f),
          )
          SarimStatTile(
            label = "Total Collected",
            value = SarimFormat.formatKg(invState.totalCollectedKg),
            accentColor = palette.cyan,
            modifier = Modifier.weight(1f),
          )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimStatTile(
            label = "Low Stock (<25 kg)",
            value = invState.lowStockCount.toString(),
            accentColor = if (invState.lowStockCount > 0) palette.amber else palette.green,
            modifier = Modifier.weight(1f),
          )
          SarimStatTile(
            label = "Out of Stock",
            value = invState.outOfStockCount.toString(),
            accentColor = if (invState.outOfStockCount > 0) palette.red else palette.green,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }

    // Quick Add Raw Material Purchase CTA
    item {
      SarimPrimaryButton(
        text = "+ Add Raw Material Purchase",
        testTag = "inv_add_material_btn",
        onClick = { sarimViewModel.openStandalone(StandaloneScreen.AddRawMaterial()) },
      )
    }

    // Search + Sort
    item {
      SarimCard {
        SarimTextField(
          value = invState.searchQuery,
          onValueChange = { inventoryViewModel.setSearchQuery(it) },
          label = "",
          placeholder = "Search raw materials, supplier, or unit...",
          leadingIcon = Icons.Rounded.Search,
          testTag = "inv_search",
        )
        SarimToggleGroup(
          options = InventorySortMode.entries,
          selected = invState.sortMode,
          labelOf = { mode ->
            if (mode == invState.sortMode) {
              "${mode.label} ${if (invState.sortAscending) "▲" else "▼"}"
            } else {
              "${mode.label} ⇅"
            }
          },
          onSelect = { inventoryViewModel.toggleSort(it) },
          testTagPrefix = "inv_sort",
        )
      }
    }

    // Material List
    if (invState.filteredMaterials.isEmpty()) {
      item {
        SarimCard {
          SarimEmptyState(
            title = "No Raw Materials Found",
            subtitle = if (invState.searchQuery.isBlank()) {
              "Tap '+ Add Raw Material Purchase' above to stock your first material."
            } else {
              "No raw materials match '${invState.searchQuery}'."
            },
          )
        }
      }
    } else {
      item {
        SarimCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Raw Materials", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
            SarimChip("${invState.filteredMaterials.size} items", palette.cyan)
          }
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            invState.filteredMaterials.forEach { mat ->
              val totalVal = mat.stockKg * mat.avgCostPerKg
              val unitsCount = if (mat.conversionFactorKg > 0.001) mat.stockKg / mat.conversionFactorKg else mat.stockKg
              val stockColor = when {
                mat.stockKg <= 0.001 && !mat.name.equals("CHORA", ignoreCase = true) -> palette.red
                mat.stockKg < 25.0 && !mat.name.equals("CHORA", ignoreCase = true) -> palette.amber
                else -> palette.green
              }
              Surface(
                shape = RoundedCornerShape(20.dp),
                color = palette.surfaceElevated,
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(20.dp))
                  .testTag("inv_item_${mat.id}")
                  .clickable { sarimViewModel.openStandalone(StandaloneScreen.AddRawMaterial(mat.id)) },
              ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = stockColor, modifier = Modifier.size(18.dp))
                      Text(mat.name.uppercase(), color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text(
                      SarimFormat.formatRs(totalVal, true, hideMoney),
                      color = palette.green,
                      style = TabularNumberStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    )
                  }
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                      "${SarimFormat.formatQty(unitsCount, 2)} ${mat.unitName} · ${SarimFormat.formatKg(mat.stockKg)}",
                      color = stockColor,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Medium,
                    )
                    Text(
                      "Avg ${SarimFormat.formatRs(mat.avgCostPerKg, true, hideMoney)}/kg",
                      color = palette.textMuted,
                      fontSize = 11.5.sp,
                    )
                  }
                  if (mat.pendingSupplierAmount > 0.01 || !mat.supplierName.isNullOrBlank()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                      Text("Supplier: ${mat.supplierName ?: "Unspecified"}", color = palette.textMuted, fontSize = 11.sp)
                      if (mat.pendingSupplierAmount > 0.01) {
                        SarimChip("Payable ${SarimFormat.formatRs(mat.pendingSupplierAmount, false, hideMoney)}", palette.amber)
                      }
                    }
                  }
                  if (!mat.name.equals("CHORA", ignoreCase = true)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      SarimSecondaryButton(
                        text = "Restock",
                        icon = Icons.Rounded.Add,
                        onClick = { sarimViewModel.openStandalone(StandaloneScreen.AddRawMaterial(mat.id)) },
                        modifier = Modifier.weight(1f),
                      )
                      SarimDangerButton(
                        text = "Delete",
                        onClick = {
                          sarimViewModel.showDialog(
                            AppDialogRequest(
                              title = "Delete Material",
                              body = "Move ${mat.name} from active inventory to deleted records?",
                              tone = DialogTone.PERMANENT,
                              confirmLabel = "Delete",
                              onConfirm = { inventoryViewModel.deleteMaterial(mat.id, mat.name) },
                            )
                          )
                        },
                        modifier = Modifier.weight(0.8f),
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    // Recent Purchase Lots Accordion
    item {
      SarimCard {
        Text("Purchase Lots History", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
        AccordionHistoryList(
          records = invState.purchases.sortedByDescending { it.dateEpoch }.map { p ->
            HistoryRecordDisplay(
              item = p,
              dateEpoch = p.dateEpoch,
              title = p.materialName,
              subtitle = "${SarimFormat.formatQty(p.unitsBought, 2)} ${p.unitName} · ${SarimFormat.formatKg(p.totalKg)}",
              amountText = SarimFormat.formatRs(p.totalAmount, true, hideMoney),
              amountColor = palette.amber,
              chipText = p.supplierName ?: p.supplierMode,
            )
          },
          yearSummary = { items -> "${items.size} lots" },
          monthSummary = { items ->
            "${items.size} purchases · ${SarimFormat.formatRs(items.sumOf { it.item.totalAmount }, false, hideMoney)}"
          },
          daySummary = { items -> SarimFormat.formatKg(items.sumOf { it.item.totalKg }) },
          emptySubtitle = "No purchase lots recorded yet.",
        ) { rec ->
          val p = rec.item
          Text(
            "Unit cost: ${SarimFormat.formatRs(p.costPerUnit, true, hideMoney)} / ${p.unitName} · Status: ${p.paymentStatus}",
            color = palette.textMuted,
            fontSize = 12.sp,
          )
        }
      }
    }

        item { Spacer(Modifier.height(16.dp)) }
      }
    }
  }
}
