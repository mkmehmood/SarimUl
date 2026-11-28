package com.example.ui.navigation

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.data.model.MainTab
import com.example.data.model.StandaloneScreen
import com.example.ui.screens.*
import com.example.ui.tabs.*
import com.example.viewmodel.BaseLedgerViewModel
import com.example.viewmodel.SarimUiState
import com.example.viewmodel.SarimViewModel

/**
 * Sealed class hierarchy defining all navigable screens in Sarim and their typed route helpers.
 */
sealed class SarimRoute(
  val route: String,
  val title: String,
  val isPrimaryDestination: Boolean = false,
) {
  // Primary Hub Views
  data object Dashboard : SarimRoute("dashboard", "Dashboard", isPrimaryDestination = true)
  data object Ledger : SarimRoute("ledger", "Daily Ledger", isPrimaryDestination = true)

  // Operational Tabs
  data object Production : SarimRoute("production", "Production")
  data object Sales : SarimRoute("sales", "Sales")
  data object Calculator : SarimRoute("calculator", "Calculator")
  data object Factory : SarimRoute("factory", "Factory")
  data object Payments : SarimRoute("payments", "Payments")
  data object RepSales : SarimRoute("rep_sales", "Rep Sales")

  // Admin & Detail Sub-Screens
  data object Settings : SarimRoute("settings", "SETTINGS")
  data object Approvals : SarimRoute("approvals", "APPROVALS")
  data object StockTransfer : SarimRoute("stock_transfer", "STOCK TRANSFER")
  data object PaymentTransfer : SarimRoute("payment_transfer", "PAYMENT TRANSFER")
  data object FormulaStore : SarimRoute("formula_store", "FORMULA STORE")
  data object BackupRestore : SarimRoute("backup_restore", "BACKUP & RESTORE")
  data object RecycleBin : SarimRoute("recycle_bin", "RECYCLE BIN")
  data object DatabaseStructure : SarimRoute("database_structure", "DATABASE STRUCTURE")
  data object SoundHaptics : SarimRoute("sound_haptics", "SOUND & HAPTICS")
  data object AppAccounts : SarimRoute("app_accounts", "APP ACCOUNTS")
  data object StoreManager : SarimRoute("store_manager", "STORE MANAGER")
  data object CloseFinancialYear : SarimRoute("close_financial_year", "CLOSE FINANCIAL YEAR")

  data class AddEntity(val entityId: String? = null) :
    SarimRoute(ROUTE, if (entityId == null) "ADD ENTITY" else "EDIT ENTITY") {
    companion object {
      const val ARG = "entityId"
      const val ROUTE = "add_entity?$ARG={$ARG}"
      fun createRoute(entityId: String? = null): String =
        if (entityId.isNullOrBlank()) "add_entity" else "add_entity?$ARG=${Uri.encode(entityId)}"
    }
  }

  data class AddRawMaterial(val materialId: String? = null) :
    SarimRoute(ROUTE, "PURCHASE ENTRY") {
    companion object {
      const val ARG = "materialId"
      const val ROUTE = "add_raw_material?$ARG={$ARG}"
      fun createRoute(materialId: String? = null): String =
        if (materialId.isNullOrBlank()) "add_raw_material" else "add_raw_material?$ARG=${Uri.encode(materialId)}"
    }
  }

  data class FormulaEdit(val formulaId: String? = null) :
    SarimRoute(ROUTE, if (formulaId == null) "NEW FORMULA" else "EDIT FORMULA") {
    companion object {
      const val ARG = "formulaId"
      const val ROUTE = "formula_edit?$ARG={$ARG}"
      fun createRoute(formulaId: String? = null): String =
        if (formulaId.isNullOrBlank()) "formula_edit" else "formula_edit?$ARG=${Uri.encode(formulaId)}"
    }
  }

  data class Batching(val savedId: String? = null) :
    SarimRoute(ROUTE, "BATCHING") {
    companion object {
      const val ARG = "savedId"
      const val ROUTE = "batching?$ARG={$ARG}"
      fun createRoute(savedId: String? = null): String =
        if (savedId.isNullOrBlank()) "batching" else "batching?$ARG=${Uri.encode(savedId)}"
    }
  }

  data class BatchCorrection(val savedId: String? = null) :
    SarimRoute(ROUTE, "BATCH CORRECTION") {
    companion object {
      const val ARG = "savedId"
      const val ROUTE = "batch_correction?$ARG={$ARG}"
      fun createRoute(savedId: String? = null): String =
        if (savedId.isNullOrBlank()) "batch_correction" else "batch_correction?$ARG=${Uri.encode(savedId)}"
    }
  }

  data class CustomerDetails(val customerId: String) : SarimRoute(ROUTE, "") {
    companion object {
      const val ARG = "customerId"
      const val ROUTE = "customer_details/{$ARG}"
      fun createRoute(customerId: String): String = "customer_details/${Uri.encode(customerId.ifBlank { "_" })}"
    }
  }

  data class CustomerEdit(val customerId: String? = null) :
    SarimRoute(ROUTE, if (customerId == null) "ADD CUSTOMER" else "EDIT CUSTOMER") {
    companion object {
      const val ARG = "customerId"
      const val ROUTE = "customer_edit?$ARG={$ARG}"
      fun createRoute(customerId: String? = null): String =
        if (customerId.isNullOrBlank()) "customer_edit" else "customer_edit?$ARG=${Uri.encode(customerId)}"
    }
  }

  data class RepCustomerDetails(val customerId: String) : SarimRoute(ROUTE, "") {
    companion object {
      const val ARG = "customerId"
      const val ROUTE = "rep_customer_details/{$ARG}"
      fun createRoute(customerId: String): String = "rep_customer_details/${Uri.encode(customerId.ifBlank { "_" })}"
    }
  }

  data class RepCustomerEdit(val customerId: String? = null) :
    SarimRoute(ROUTE, if (customerId == null) "ADD REP CUSTOMER" else "EDIT REP CUSTOMER") {
    companion object {
      const val ARG = "customerId"
      const val ROUTE = "rep_customer_edit?$ARG={$ARG}"
      fun createRoute(customerId: String? = null): String =
        if (customerId.isNullOrBlank()) "rep_customer_edit" else "rep_customer_edit?$ARG=${Uri.encode(customerId)}"
    }
  }

  data class EntityDetails(val entityId: String) : SarimRoute(ROUTE, "") {
    companion object {
      const val ARG = "entityId"
      const val ROUTE = "entity_details/{$ARG}"
      fun createRoute(entityId: String): String = "entity_details/${Uri.encode(entityId.ifBlank { "_" })}"
    }
  }

  data class ExpenseDetails(val expenseName: String) : SarimRoute(ROUTE, "EXPENSE DETAILS") {
    companion object {
      const val ARG = "expenseName"
      const val ROUTE = "expense_details/{$ARG}"
      fun createRoute(expenseName: String): String = "expense_details/${Uri.encode(expenseName.ifBlank { "_" })}"
    }
  }

  companion object {
    fun routeForTab(tab: MainTab): String = when (tab) {
      MainTab.OVERVIEW -> Dashboard.route
      MainTab.PRODUCTION -> Production.route
      MainTab.SALES -> Sales.route
      MainTab.CALCULATOR -> Calculator.route
      MainTab.FACTORY -> Factory.route
      MainTab.PAYMENTS -> Payments.route
      MainTab.REP_SALES -> RepSales.route
    }

    fun tabForRoute(route: String?): MainTab? = when (route) {
      Dashboard.route -> MainTab.OVERVIEW
      Production.route -> MainTab.PRODUCTION
      Sales.route -> MainTab.SALES
      Calculator.route -> MainTab.CALCULATOR
      Factory.route -> MainTab.FACTORY
      Payments.route -> MainTab.PAYMENTS
      RepSales.route -> MainTab.REP_SALES
      else -> null
    }

    fun routeForStandalone(screen: StandaloneScreen): String = when (screen) {
      StandaloneScreen.Settings -> Settings.route
      StandaloneScreen.Approvals -> Approvals.route
      StandaloneScreen.DailyLedger -> Ledger.route
      StandaloneScreen.StockTransfer -> StockTransfer.route
      StandaloneScreen.PaymentTransfer -> PaymentTransfer.route
      is StandaloneScreen.AddEntity -> AddEntity.createRoute(screen.entityId)
      is StandaloneScreen.AddRawMaterial -> AddRawMaterial.createRoute(screen.materialId)
      StandaloneScreen.FormulaStore -> FormulaStore.route
      is StandaloneScreen.FormulaEdit -> FormulaEdit.createRoute(screen.formulaId)
      is StandaloneScreen.Batching -> Batching.createRoute(screen.savedId)
      is StandaloneScreen.BatchCorrection -> BatchCorrection.createRoute(screen.savedId)
      StandaloneScreen.BackupRestore -> BackupRestore.route
      StandaloneScreen.RecycleBin -> RecycleBin.route
      StandaloneScreen.DatabaseStructure -> DatabaseStructure.route
      StandaloneScreen.SoundHaptics -> SoundHaptics.route
      StandaloneScreen.AppAccounts -> AppAccounts.route
      StandaloneScreen.StoreManager -> StoreManager.route
      StandaloneScreen.CloseFinancialYear -> CloseFinancialYear.route
      is StandaloneScreen.CustomerDetails -> CustomerDetails.createRoute(screen.customerId)
      is StandaloneScreen.CustomerEdit -> CustomerEdit.createRoute(screen.customerId)
      is StandaloneScreen.RepCustomerDetails -> RepCustomerDetails.createRoute(screen.customerId)
      is StandaloneScreen.RepCustomerEdit -> RepCustomerEdit.createRoute(screen.customerId)
      is StandaloneScreen.EntityDetails -> EntityDetails.createRoute(screen.entityId)
      is StandaloneScreen.ExpenseDetails -> ExpenseDetails.createRoute(screen.expenseName)
    }

    fun isTopBarPrimaryRoute(route: String?): Boolean {
      return route == Dashboard.route ||
        route == Ledger.route ||
        tabForRoute(route) != null
    }

    /**
     * Detail/record screens that render their own content header and therefore
     * show no title text in the shell top bar (Customer, Entity and Rep Customer screens).
     */
    fun hidesHeaderTitle(route: String?): Boolean =
      route?.startsWith("customer_details") == true ||
        route?.startsWith("add_entity") == true ||
        route?.startsWith("rep_customer_details") == true ||
        route?.startsWith("rep_customer_edit") == true

    fun titleForRoute(route: String?, isCloseFinYear: Boolean = false): String? = when {
      route == Dashboard.route || tabForRoute(route) != null -> null
      route == Ledger.route -> "DAILY LEDGER"
      route == Settings.route -> "SETTINGS"
      route == Approvals.route -> "APPROVALS"
      route == StockTransfer.route -> "STOCK TRANSFER"
      route == PaymentTransfer.route -> "PAYMENT TRANSFER"
      route?.startsWith("add_raw_material") == true -> "PURCHASE ENTRY"
      route == FormulaStore.route -> "FORMULA STORE"
      route?.startsWith("formula_edit") == true -> "FORMULA EDITOR"
      route?.startsWith("batching") == true -> "BATCHING"
      route?.startsWith("batch_correction") == true -> "BATCH CORRECTION"
      route == BackupRestore.route -> "BACKUP & RESTORE"
      route == RecycleBin.route -> "RECYCLE BIN"
      route == DatabaseStructure.route -> "DATABASE STRUCTURE"
      route == SoundHaptics.route -> "SOUND & HAPTICS"
      route == AppAccounts.route -> "APP ACCOUNTS"
      route == StoreManager.route -> "STORE MANAGER"
      route == CloseFinancialYear.route || isCloseFinYear -> "CLOSE FINANCIAL YEAR"
      route?.startsWith("customer_edit") == true -> "CUSTOMER DETAILS"
      route?.startsWith("expense_details") == true -> "EXPENSE DETAILS"
      else -> null
    }

    fun fromRoute(route: String?): SarimRoute? = when {
      route == Dashboard.route -> Dashboard
      route == Ledger.route -> Ledger
      route == Production.route -> Production
      route == Sales.route -> Sales
      route == Calculator.route -> Calculator
      route == Factory.route -> Factory
      route == Payments.route -> Payments
      route == RepSales.route -> RepSales
      route == Settings.route -> Settings
      route == Approvals.route -> Approvals
      route == StockTransfer.route -> StockTransfer
      route == PaymentTransfer.route -> PaymentTransfer
      route == FormulaStore.route -> FormulaStore
      route == BackupRestore.route -> BackupRestore
      route == RecycleBin.route -> RecycleBin
      route == DatabaseStructure.route -> DatabaseStructure
      route == SoundHaptics.route -> SoundHaptics
      route == AppAccounts.route -> AppAccounts
      route == StoreManager.route -> StoreManager
      route == CloseFinancialYear.route -> CloseFinancialYear
      route?.startsWith("add_entity") == true -> AddEntity()
      route?.startsWith("add_raw_material") == true -> AddRawMaterial()
      route?.startsWith("formula_edit") == true -> FormulaEdit()
      route?.startsWith("batching") == true -> Batching()
      route?.startsWith("batch_correction") == true -> BatchCorrection()
      route?.startsWith("customer_details") == true -> CustomerDetails("")
      route?.startsWith("customer_edit") == true -> CustomerEdit()
      route?.startsWith("rep_customer_details") == true -> RepCustomerDetails("")
      route?.startsWith("rep_customer_edit") == true -> RepCustomerEdit()
      route?.startsWith("entity_details") == true -> EntityDetails("")
      route?.startsWith("expense_details") == true -> ExpenseDetails("")
      else -> null
    }
  }
}

sealed interface SarimNavAction {
  data class Navigate(val route: String, val popUpToStart: Boolean = false) : SarimNavAction
  data object PopBack : SarimNavAction
}

/**
 * Central NavHost managing transitions between Dashboard, Ledger,
 * and all operational tabs and detail screens.
 */
@Composable
fun SarimNavHost(
  navController: NavHostController,
  startDestination: String,
  state: SarimUiState,
  viewModel: SarimViewModel,
  ledgerViewModel: BaseLedgerViewModel,
  modifier: Modifier = Modifier,
) {
  NavHost(
    navController = navController,
    startDestination = startDestination,
    modifier = modifier,
    enterTransition = {
      val destRoute = targetState.destination.route
      if (SarimRoute.isTopBarPrimaryRoute(destRoute)) {
        fadeIn(animationSpec = tween(180))
      } else {
        slideInHorizontally(animationSpec = tween(220)) { it / 5 } + fadeIn(animationSpec = tween(180))
      }
    },
    exitTransition = {
      fadeOut(animationSpec = tween(140))
    },
    popEnterTransition = {
      fadeIn(animationSpec = tween(180))
    },
    popExitTransition = {
      val srcRoute = initialState.destination.route
      if (SarimRoute.isTopBarPrimaryRoute(srcRoute)) {
        fadeOut(animationSpec = tween(140))
      } else {
        slideOutHorizontally(animationSpec = tween(200)) { it / 5 } + fadeOut(animationSpec = tween(140))
      }
    },
  ) {
    composable(SarimRoute.Dashboard.route) {
      if (state.isAdmin) {
        OverviewTab(state, viewModel)
      } else {
        androidx.compose.foundation.layout.Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
          androidx.compose.material3.Text(
            "No access assigned yet. Ask an admin to assign a role.",
            color = com.example.ui.theme.LocalSarimPalette.current.textMuted,
          )
        }
      }
    }
    composable(SarimRoute.Ledger.route) {
      DailyLedgerScreen(
        state = state,
        viewModel = viewModel,
        ledgerViewModel = ledgerViewModel,
      )
    }
    composable(SarimRoute.Production.route) { ProductionTab(state, viewModel) }
    composable(SarimRoute.Sales.route) { SalesTab(state, viewModel) }
    composable(SarimRoute.Calculator.route) { CalculatorTab(state, viewModel) }
    composable(SarimRoute.Factory.route) { FactoryTab(state, viewModel) }
    composable(SarimRoute.Payments.route) { PaymentsTab(state, viewModel) }
    composable(SarimRoute.RepSales.route) { RepSalesTab(state, viewModel) }

    composable(SarimRoute.Settings.route) { SettingsScreen(state, viewModel) }
    composable(SarimRoute.Approvals.route) { ApprovalsScreen(state, viewModel) }
    composable(SarimRoute.StockTransfer.route) { StockTransferScreen(state, viewModel) }
    composable(SarimRoute.PaymentTransfer.route) { PaymentTransferScreen(state, viewModel) }
    composable(
      route = SarimRoute.AddEntity.ROUTE,
      arguments = listOf(navArgument(SarimRoute.AddEntity.ARG) { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
      AddEntityScreen(entry.arguments?.getString(SarimRoute.AddEntity.ARG), state, viewModel)
    }
    composable(
      route = SarimRoute.AddRawMaterial.ROUTE,
      arguments = listOf(navArgument(SarimRoute.AddRawMaterial.ARG) { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
      AddRawMaterialScreen(entry.arguments?.getString(SarimRoute.AddRawMaterial.ARG), state, viewModel)
    }
    composable(SarimRoute.FormulaStore.route) { FormulaStoreScreen(state, viewModel) }
    composable(
      route = SarimRoute.FormulaEdit.ROUTE,
      arguments = listOf(navArgument(SarimRoute.FormulaEdit.ARG) { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
      FormulaEditScreen(entry.arguments?.getString(SarimRoute.FormulaEdit.ARG), state, viewModel)
    }
    composable(
      route = SarimRoute.Batching.ROUTE,
      arguments = listOf(navArgument(SarimRoute.Batching.ARG) { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
      BatchingScreen(entry.arguments?.getString(SarimRoute.Batching.ARG), state, viewModel)
    }
    composable(
      route = SarimRoute.BatchCorrection.ROUTE,
      arguments = listOf(navArgument(SarimRoute.BatchCorrection.ARG) { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
      BatchCorrectionScreen(entry.arguments?.getString(SarimRoute.BatchCorrection.ARG), state, viewModel)
    }
    composable(SarimRoute.BackupRestore.route) { BackupRestoreScreen(viewModel) }
    composable(SarimRoute.RecycleBin.route) { RecycleBinScreen(state, viewModel) }
    composable(SarimRoute.DatabaseStructure.route) { DatabaseStructureScreen(state, viewModel) }
    composable(SarimRoute.SoundHaptics.route) { SoundHapticsScreen(state, viewModel) }
    composable(SarimRoute.AppAccounts.route) { AppAccountsScreen(state, viewModel) }
    composable(SarimRoute.StoreManager.route) { StoreManagerScreen(state, viewModel) }
    composable(SarimRoute.CloseFinancialYear.route) { CloseFinancialYearScreen(state, viewModel) }

    composable(
      route = SarimRoute.CustomerDetails.ROUTE,
      arguments = listOf(navArgument(SarimRoute.CustomerDetails.ARG) { type = NavType.StringType }),
    ) { entry ->
      val id = entry.arguments?.getString(SarimRoute.CustomerDetails.ARG).orEmpty()
      CustomerDetailsScreen(id, isRep = false, state = state, viewModel = viewModel)
    }
    composable(
      route = SarimRoute.CustomerEdit.ROUTE,
      arguments = listOf(navArgument(SarimRoute.CustomerEdit.ARG) { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
      CustomerEditScreen(entry.arguments?.getString(SarimRoute.CustomerEdit.ARG), isRep = false, state = state, viewModel = viewModel)
    }
    composable(
      route = SarimRoute.RepCustomerDetails.ROUTE,
      arguments = listOf(navArgument(SarimRoute.RepCustomerDetails.ARG) { type = NavType.StringType }),
    ) { entry ->
      val id = entry.arguments?.getString(SarimRoute.RepCustomerDetails.ARG).orEmpty()
      CustomerDetailsScreen(id, isRep = true, state = state, viewModel = viewModel)
    }
    composable(
      route = SarimRoute.RepCustomerEdit.ROUTE,
      arguments = listOf(navArgument(SarimRoute.RepCustomerEdit.ARG) { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
      CustomerEditScreen(entry.arguments?.getString(SarimRoute.RepCustomerEdit.ARG), isRep = true, state = state, viewModel = viewModel)
    }
    composable(
      route = SarimRoute.EntityDetails.ROUTE,
      arguments = listOf(navArgument(SarimRoute.EntityDetails.ARG) { type = NavType.StringType }),
    ) { entry ->
      val id = entry.arguments?.getString(SarimRoute.EntityDetails.ARG).orEmpty()
      EntityDetailsScreen(id, state, viewModel)
    }
    composable(
      route = SarimRoute.ExpenseDetails.ROUTE,
      arguments = listOf(navArgument(SarimRoute.ExpenseDetails.ARG) { type = NavType.StringType }),
    ) { entry ->
      val name = entry.arguments?.getString(SarimRoute.ExpenseDetails.ARG).orEmpty()
      ExpenseDetailsScreen(name, state, viewModel)
    }
  }
}
