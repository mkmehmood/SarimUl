package com.example.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MainTab
import com.example.data.model.StandaloneScreen
import com.example.ui.components.SarimChip
import com.example.ui.navigation.SarimRoute
import com.example.ui.theme.CinzelFontFamily
import com.example.ui.theme.LocalSarimPalette
import com.example.viewmodel.SarimUiState

@Composable
fun SarimNavigationDrawerContent(
  state: SarimUiState,
  onSelectTab: (MainTab) -> Unit,
  onOpenRoute: (SarimRoute) -> Unit,
  onOpenStandalone: (StandaloneScreen) -> Unit,
  onLogout: () -> Unit,
) {
  val palette = LocalSarimPalette.current
  val visibleTabs = state.visibleTabs()
  val pendingApprovals = state.approvals.count { it.status == "PENDING" }

  val scheme = MaterialTheme.colorScheme
  ModalDrawerSheet(
    drawerContainerColor = scheme.surfaceContainerLow,
    drawerContentColor = palette.textPrimary,
    drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    modifier = Modifier.widthIn(max = 310.dp),
  ) {
    Column(
      modifier = Modifier
        .fillMaxHeight()
        .systemBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      // Header
      Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          "GULL AND ZUBAIR",
          color = palette.green,
          fontFamily = CinzelFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 18.sp,
          letterSpacing = 1.6.sp,
        )
        Text(
          state.signedInEmail.ifBlank { if (state.isOfflineSession) "Offline Local Workspace" else "Signed In" },
          color = palette.textMuted,
          fontSize = 11.5.sp,
        )
      }
      HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
      Spacer(Modifier.height(4.dp))

      DrawerGroupHeader("NAVIGATION")
      visibleTabs.forEach { tab ->
        val icon = when (tab) {
          MainTab.OVERVIEW -> Icons.Rounded.Dashboard
          MainTab.PRODUCTION -> Icons.Rounded.PrecisionManufacturing
          MainTab.SALES -> Icons.Rounded.PointOfSale
          MainTab.CALCULATOR -> Icons.Rounded.Calculate
          MainTab.FACTORY -> Icons.Rounded.Factory
          MainTab.PAYMENTS -> Icons.Rounded.AccountBalanceWallet
          MainTab.REP_SALES -> Icons.Rounded.Badge
        }
        val label = if (tab == MainTab.PRODUCTION) "Production" else tab.title
        val tabRoute = SarimRoute.routeForTab(tab)
        DrawerItemRow(
          label = label,
          icon = icon,
          selected = state.activeRoute == tabRoute,
          testTag = "drawer_tab_${tab.name.lowercase()}",
          onClick = { onSelectTab(tab) },
        )
      }

      if (state.isAdmin) {
        Spacer(Modifier.height(6.dp))
        DrawerGroupHeader("DAILY WORK")
        DrawerItemRow(
          label = "Approvals",
          icon = Icons.Rounded.Verified,
          selected = state.activeStandalone is StandaloneScreen.Approvals,
          badgeCount = pendingApprovals,
          testTag = "drawer_approvals",
          onClick = { onOpenStandalone(StandaloneScreen.Approvals) },
        )
        DrawerItemRow(
          label = "Daily Ledger",
          icon = Icons.AutoMirrored.Rounded.MenuBook,
          selected = state.activeStandalone is StandaloneScreen.DailyLedger,
          testTag = "drawer_daily_ledger",
          onClick = { onOpenStandalone(StandaloneScreen.DailyLedger) },
        )
        DrawerItemRow(
          label = "Stock Transfer",
          icon = Icons.Rounded.SwapHoriz,
          selected = state.activeStandalone is StandaloneScreen.StockTransfer,
          testTag = "drawer_stock_transfer",
          onClick = { onOpenStandalone(StandaloneScreen.StockTransfer) },
        )
        DrawerItemRow(
          label = "Payment Transfer",
          icon = Icons.Rounded.CurrencyExchange,
          selected = state.activeStandalone is StandaloneScreen.PaymentTransfer,
          testTag = "drawer_payment_transfer",
          onClick = { onOpenStandalone(StandaloneScreen.PaymentTransfer) },
        )

        Spacer(Modifier.height(6.dp))
        DrawerGroupHeader("RECORDS")
        DrawerItemRow(
          label = "Add Entity",
          icon = Icons.Rounded.PersonAdd,
          selected = state.activeStandalone is StandaloneScreen.AddEntity,
          testTag = "drawer_add_entity",
          onClick = { onOpenStandalone(StandaloneScreen.AddEntity()) },
        )
        DrawerItemRow(
          label = "Add Raw Materials",
          icon = Icons.Rounded.Inventory2,
          selected = state.activeStandalone is StandaloneScreen.AddRawMaterial,
          testTag = "drawer_add_raw_materials",
          onClick = { onOpenStandalone(StandaloneScreen.AddRawMaterial()) },
        )
        DrawerItemRow(
          label = "Formula Store",
          icon = Icons.Rounded.Science,
          selected = state.activeStandalone is StandaloneScreen.FormulaStore,
          testTag = "drawer_formula_store",
          onClick = { onOpenStandalone(StandaloneScreen.FormulaStore) },
        )
      }

      Spacer(Modifier.weight(1f))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
      Spacer(Modifier.height(4.dp))

      DrawerItemRow(
        label = "Settings",
        icon = Icons.Rounded.Settings,
        selected = state.activeStandalone is StandaloneScreen.Settings,
        testTag = "drawer_settings",
        onClick = { onOpenStandalone(StandaloneScreen.Settings) },
      )
      DrawerItemRow(
        label = "Logout",
        icon = Icons.AutoMirrored.Rounded.Logout,
        selected = false,
        tint = palette.red,
        testTag = "drawer_logout",
        onClick = onLogout,
      )
    }
  }
}

@Composable
private fun DrawerGroupHeader(title: String) {
  val palette = LocalSarimPalette.current
  Text(
    text = title,
    color = palette.textMuted,
    fontSize = 10.5.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.1.sp,
    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
  )
}

@Composable
private fun DrawerItemRow(
  label: String,
  icon: ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  tint: Color? = null,
  badgeCount: Int = 0,
  testTag: String = "drawer_item",
) {
  val palette = LocalSarimPalette.current
  val scheme = MaterialTheme.colorScheme
  val fg = tint ?: if (selected) palette.green else palette.textPrimary
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 48.dp)
      .clip(RoundedCornerShape(50))
      .testTag(testTag)
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(50),
    color = if (selected) lerp(scheme.surfaceContainerHigh, palette.green, 0.22f) else Color.Transparent,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(20.dp))
      Text(
        text = label,
        color = fg,
        fontSize = 13.5.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.weight(1f),
      )
      if (badgeCount > 0) {
        SarimChip(badgeCount.toString(), palette.amber)
      }
    }
  }
}
