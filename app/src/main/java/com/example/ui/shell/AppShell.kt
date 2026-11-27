package com.example.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.model.StandaloneScreen
import com.example.ui.components.SarimChip
import com.example.ui.components.ShimmerHeading
import com.example.ui.navigation.SarimNavAction
import com.example.ui.navigation.SarimNavHost
import com.example.ui.navigation.SarimRoute
import com.example.ui.theme.CinzelFontFamily
import com.example.ui.theme.LocalSarimPalette
import com.example.viewmodel.BaseInventoryViewModel
import com.example.viewmodel.BaseLedgerViewModel
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel
import kotlinx.coroutines.launch

@Composable
fun AppShell(
  state: SarimUiState,
  viewModel: SarimViewModel,
  onLogout: () -> Unit,
) {
  val palette = LocalSarimPalette.current
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val navController = rememberNavController()

  val inventoryViewModel: BaseInventoryViewModel = viewModel(
    factory = remember(viewModel.repository) { BaseInventoryViewModel.Factory(viewModel.repository) },
  )
  val ledgerViewModel: BaseLedgerViewModel = viewModel(
    factory = remember(viewModel.repository) { BaseLedgerViewModel.Factory(viewModel.repository) },
  )

  val initialRoute = remember { SarimRoute.routeForTab(state.activeTab) }
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route ?: state.activeRoute

  LaunchedEffect(currentRoute) {
    viewModel.onNavRouteChanged(currentRoute)
  }

  LaunchedEffect(viewModel) {
    viewModel.navActions.collect { action ->
      when (action) {
        is SarimNavAction.Navigate -> {
          runCatching {
            val currentResolved = navController.currentBackStackEntry?.destination?.route
            if (currentResolved != action.route) {
              navController.navigate(action.route) {
                if (action.popUpToStart) {
                  popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                  }
                  restoreState = true
                }
                launchSingleTop = true
              }
            }
          }.onFailure { err ->
            viewModel.postStatus(err.localizedMessage ?: "Unable to open screen")
          }
        }
        SarimNavAction.PopBack -> {
          if (!navController.popBackStack()) {
            val fallbackRoute = SarimRoute.fromRoute(SarimRoute.routeForTab(state.visibleTabs().first()))
              ?: SarimRoute.Dashboard
            viewModel.openRoute(fallbackRoute)
          }
        }
      }
    }
  }

  LaunchedEffect(state.statusMessage) {
    val msg = state.statusMessage
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearStatusMessage()
    }
  }

  val isPrimaryHeader = SarimRoute.isTopBarPrimaryRoute(currentRoute)
  val subScreenTitle = SarimRoute.titleForRoute(
    route = currentRoute,
    isCloseFinYear = state.activeStandalone is StandaloneScreen.CloseFinancialYear,
  )

  ModalNavigationDrawer(
    drawerState = drawerState,
    gesturesEnabled = isPrimaryHeader,
    drawerContent = {
      SarimNavigationDrawerContent(
        state = state,
        onSelectTab = { tab ->
          scope.launch { drawerState.close() }
          viewModel.selectTab(tab)
        },
        onOpenRoute = { route ->
          scope.launch { drawerState.close() }
          viewModel.openRoute(route)
        },
        onOpenStandalone = { screen ->
          scope.launch { drawerState.close() }
          viewModel.openStandalone(screen)
        },
        onLogout = {
          scope.launch { drawerState.close() }
          onLogout()
        },
      )
    },
  ) {
    Scaffold(
      containerColor = palette.background,
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(snackbarHostState) },
      topBar = {
        Column(modifier = Modifier.fillMaxWidth().background(palette.background)) {
          if (isPrimaryHeader && currentRoute != SarimRoute.Ledger.route) {
            // sys-header: hamburger left + shimmering signed-in name center
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            ) {
              IconButton(
                onClick = { scope.launch { drawerState.open() } },
                modifier = Modifier
                  .align(Alignment.CenterStart)
                  .size(48.dp)
                  .testTag("hamburger_btn"),
              ) {
                Icon(Icons.Filled.Menu, contentDescription = "Open navigation menu", tint = palette.textPrimary)
              }

              ShimmerHeading(
                text = state.signedInDisplayName,
                fontSizeSp = 17,
                modifier = Modifier
                  .align(Alignment.Center)
                  .padding(horizontal = 52.dp),
              )

              if (state.pendingSyncCount > 0 && !state.isOfflineSession) {
                IconButton(
                  onClick = { viewModel.syncDataNow() },
                  modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(48.dp)
                    .testTag("header_sync_btn"),
                ) {
                  Icon(Icons.Filled.Sync, contentDescription = "Sync pending data", tint = palette.green)
                }
              }
            }
          } else {
            // Standalone / Ledger screen top bar with Back Arrow and Cinzel title
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            ) {
              IconButton(
                onClick = {
                  if (!navController.popBackStack()) {
                    val fallbackRoute = SarimRoute.fromRoute(SarimRoute.routeForTab(state.visibleTabs().first()))
                      ?: SarimRoute.Dashboard
                    viewModel.openRoute(fallbackRoute)
                  }
                },
                modifier = Modifier
                  .align(Alignment.CenterStart)
                  .size(48.dp)
                  .testTag("standalone_back_btn"),
              ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
              }
              Text(
                text = subScreenTitle ?: "DAILY LEDGER",
                color = if (currentRoute == SarimRoute.CloseFinancialYear.route) palette.red else palette.textPrimary,
                fontFamily = CinzelFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 1.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 52.dp),
              )
            }
          }

          // Offline / Pending Sync slim banner
          if (!state.isOnline || state.isOfflineSession || state.pendingSyncCount > 0) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .clickable { if (!state.isOfflineSession) viewModel.syncDataNow() }
                .padding(horizontal = 16.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                  Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (!state.isOnline || state.isOfflineSession) palette.amber else palette.green),
                )
                Text(
                  text = if (!state.isOnline || state.isOfflineSession) {
                    "Offline — changes saved locally"
                  } else {
                    "Cloud connected — tap to sync"
                  },
                  color = palette.textMuted,
                  fontSize = 11.5.sp,
                )
              }
              SarimChip(
                text = "${state.pendingSyncCount} pending",
                color = if (state.pendingSyncCount > 0) palette.amber else palette.green,
              )
            }
            HorizontalDivider(color = palette.border)
          }
        }
      },
      bottomBar = {
        if (isPrimaryHeader) {
          NavigationBar(
            containerColor = palette.surface,
            contentColor = palette.textPrimary,
            windowInsets = WindowInsets.navigationBars,
            tonalElevation = 2.dp,
          ) {
            SarimRoute.primaryDestinations.forEach { (dest, icon) ->
              val selected = currentRoute == dest.route
              NavigationBarItem(
                selected = selected,
                onClick = { viewModel.openRoute(dest) },
                icon = { Icon(icon, contentDescription = dest.title) },
                label = { Text(dest.title, fontSize = 11.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                colors = NavigationBarItemDefaults.colors(
                  selectedIconColor = palette.green,
                  selectedTextColor = palette.green,
                  indicatorColor = palette.green.copy(alpha = 0.16f),
                  unselectedIconColor = palette.textMuted,
                  unselectedTextColor = palette.textMuted,
                ),
                modifier = Modifier.testTag("bottom_nav_${dest.route}"),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
        contentAlignment = Alignment.TopCenter,
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 760.dp),
        ) {
          SarimNavHost(
            navController = navController,
            startDestination = initialRoute,
            state = state,
            viewModel = viewModel,
            inventoryViewModel = inventoryViewModel,
            ledgerViewModel = ledgerViewModel,
          )
        }
      }
    }
  }
}
