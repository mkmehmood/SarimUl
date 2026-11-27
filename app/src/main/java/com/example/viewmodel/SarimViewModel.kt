package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.*
import com.example.data.model.*
import com.example.data.remote.FirebaseSyncManager
import com.example.data.repository.*
import com.example.ui.navigation.SarimNavAction
import com.example.ui.navigation.SarimRoute
import com.example.util.AppDialogRequest
import com.example.util.FeedbackManager
import com.example.util.SarimFormat
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SarimUiState(
  val showSplash: Boolean = true,
  val isAuthenticated: Boolean = false,
  val isOfflineSession: Boolean = false,
  val isLockedByFingerprint: Boolean = false,
  val signedInDisplayName: String = "GULL AND ZUBAIR",
  val signedInEmail: String = "",
  val activeRoles: Set<UserRole> = setOf(UserRole.ADMIN),
  val activeTab: MainTab = MainTab.OVERVIEW,
  val activeRoute: String = SarimRoute.Dashboard.route,
  val activeStandalone: StandaloneScreen? = null,
  val prefs: UserPrefs = UserPrefs(),
  val isOnline: Boolean = true,
  val pendingSyncCount: Int = 0,
  val isSyncing: Boolean = false,
  val overviewRange: OverviewRange = OverviewRange.M6,
  val snapshot: BusinessSnapshot? = null,
  val stores: List<StoreEntity> = emptyList(),
  val rawMaterials: List<RawMaterialEntity> = emptyList(),
  val purchases: List<RawMaterialPurchaseEntity> = emptyList(),
  val formulas: List<FormulaEntity> = emptyList(),
  val factoryBatches: List<FactoryBatchEntity> = emptyList(),
  val productionEntries: List<ProductionEntryEntity> = emptyList(),
  val customers: List<CustomerEntity> = emptyList(),
  val sales: List<SaleTransactionEntity> = emptyList(),
  val paymentEntities: List<PaymentEntityRecord> = emptyList(),
  val paymentTransactions: List<PaymentTransactionEntity> = emptyList(),
  val dailyLedger: List<DailyLedgerEntity> = emptyList(),
  val settlements: List<CalculatorSettlementEntity> = emptyList(),
  val stockTransfers: List<StockTransferEntity> = emptyList(),
  val paymentTransfers: List<PaymentTransferEntity> = emptyList(),
  val mixCalcs: List<SavedMixCalcEntity> = emptyList(),
  val approvals: List<ApprovalRequestEntity> = emptyList(),
  val accounts: List<AppAccountEntity> = emptyList(),
  val recycleBinItems: List<RecycleBinRecord> = emptyList(),
  val dbStructure: List<CollectionStats> = emptyList(),
  val activeDialog: AppDialogRequest? = null,
  val photoViewerUris: List<String> = emptyList(),
  val photoViewerIndex: Int = 0,
  val statusMessage: String? = null,
  val lastUpdatedMillis: Long = System.currentTimeMillis(),
) {
  val isAdmin: Boolean get() = UserRole.ADMIN in activeRoles
  val hideMoneyFigures: Boolean
    get() = !isAdmin && (UserRole.SALES in activeRoles || UserRole.PAYMENTS in activeRoles) &&
      UserRole.PRODUCTION !in activeRoles && UserRole.FACTORY !in activeRoles

  fun visibleTabs(): List<MainTab> {
    if (isAdmin) return MainTab.entries
    val tabs = mutableListOf<MainTab>()
    if (UserRole.PRODUCTION in activeRoles) tabs += MainTab.PRODUCTION
    if (UserRole.FACTORY in activeRoles) tabs += MainTab.FACTORY
    if (UserRole.SALES in activeRoles) tabs += MainTab.SALES
    if (UserRole.PAYMENTS in activeRoles) tabs += MainTab.PAYMENTS
    if (UserRole.REP in activeRoles) tabs += MainTab.REP_SALES
    return tabs.ifEmpty { listOf(MainTab.OVERVIEW) }
  }
}

class SarimViewModel(application: Application) : AndroidViewModel(application) {
  private val db = SarimDatabase.getInstance(application)
  private val dao = db.dao()
  val prefsManager = PreferencesManager(application)
  val syncManager = FirebaseSyncManager()
  val repository = SarimRepository(dao)
  val syncRepo = SarimSyncAndBackupRepository(dao, syncManager, prefsManager)
  val feedbackManager = FeedbackManager(application)

  private val _uiState = MutableStateFlow(SarimUiState())
  val uiState: StateFlow<SarimUiState> = _uiState.asStateFlow()

  private val _navActions = MutableSharedFlow<SarimNavAction>(extraBufferCapacity = 32)
  val navActions: SharedFlow<SarimNavAction> = _navActions.asSharedFlow()

  private var currentFirebaseUser: FirebaseUser? = syncManager.currentUser()
  private var lastInitialSyncedUid: String? = null

  init {
    viewModelScope.launch {
      try {
        repository.initDefaults()
        refreshDiagnostics()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        postStatus(e.localizedMessage ?: "Failed to initialize local workspace")
      }
    }
    observeAllData()
  }

  override fun onCleared() {
    super.onCleared()
    feedbackManager.release()
  }

  private fun isNetworkConnected(): Boolean {
    return try {
      val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      val net = cm?.activeNetwork ?: return false
      val caps = cm.getNetworkCapabilities(net) ?: return false
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
      false
    }
  }

  private fun observeAllData() {
    val group1 = combine(
      repository.stores,
      repository.rawMaterials,
      repository.purchases,
      repository.formulas,
      repository.factoryBatches,
    ) { stores, mats, purchases, formulas, batches ->
      Tuple5(stores, mats, purchases, formulas, batches)
    }

    val group2 = combine(
      repository.productionEntries,
      repository.customers,
      repository.sales,
      repository.paymentEntities,
      repository.paymentTransactions,
    ) { prod, custs, sales, entities, payTx ->
      Tuple5(prod, custs, sales, entities, payTx)
    }

    val group3 = combine(
      repository.dailyLedger,
      repository.settlements,
      repository.stockTransfers,
      repository.paymentTransfers,
      repository.mixCalcs,
    ) { ledger, settlements, stockTx, payTx, mix ->
      Tuple5(ledger, settlements, stockTx, payTx, mix)
    }

    val group4 = combine(
      repository.approvals,
      repository.accounts,
      prefsManager.prefsFlow,
      _uiState.map { it.overviewRange }.distinctUntilChanged(),
    ) { approvals, accounts, prefs, range ->
      Tuple4(approvals, accounts, prefs, range)
    }

    viewModelScope.launch {
      combine(group1, group2, group3, group4) { g1, g2, g3, g4 ->
        val prefs = g4.c
        feedbackManager.updateSettings(prefs.hapticIntensity, prefs.soundIntensity)
        val snap = SarimMetricsEngine.compute(
          stores = g1.a,
          materials = g1.b,
          formulas = g1.d,
          production = g2.a,
          sales = g2.c,
          customers = g2.b,
          entities = g2.d,
          payments = g2.e,
          ledger = g3.a,
          settlements = g3.b,
          stockTransfers = g3.c,
          accounts = g4.b,
          overviewMonths = g4.d.months,
          factoryBatches = g1.e,
        )
        val pending = runCatching { syncRepo.countPendingSync() }.getOrDefault(0)
        val bin = runCatching { syncRepo.getRecycleBinItems() }.getOrDefault(emptyList())
        val struct = runCatching { syncRepo.getDatabaseStructure() }.getOrDefault(emptyList())

        ObservedDataBundle(
          prefs = prefs,
          isOnline = isNetworkConnected(),
          pendingSyncCount = pending,
          snapshot = snap,
          stores = g1.a,
          rawMaterials = g1.b,
          purchases = g1.c,
          formulas = g1.d,
          factoryBatches = g1.e,
          productionEntries = g2.a,
          customers = g2.b,
          sales = g2.c,
          paymentEntities = g2.d,
          paymentTransactions = g2.e,
          dailyLedger = g3.a,
          settlements = g3.b,
          stockTransfers = g3.c,
          paymentTransfers = g3.d,
          mixCalcs = g3.e,
          approvals = g4.a,
          accounts = g4.b,
          recycleBinItems = bin,
          dbStructure = struct,
          lastUpdatedMillis = System.currentTimeMillis(),
        )
      }
        .flowOn(Dispatchers.Default)
        .catch { err ->
          if (err is CancellationException) throw err
          postStatus(err.localizedMessage ?: "Error refreshing business data")
        }
        .collect { bundle ->
          _uiState.update { current ->
            current.copy(
              prefs = bundle.prefs,
              isOnline = bundle.isOnline,
              pendingSyncCount = bundle.pendingSyncCount,
              snapshot = bundle.snapshot,
              stores = bundle.stores,
              rawMaterials = bundle.rawMaterials,
              purchases = bundle.purchases,
              formulas = bundle.formulas,
              factoryBatches = bundle.factoryBatches,
              productionEntries = bundle.productionEntries,
              customers = bundle.customers,
              sales = bundle.sales,
              paymentEntities = bundle.paymentEntities,
              paymentTransactions = bundle.paymentTransactions,
              dailyLedger = bundle.dailyLedger,
              settlements = bundle.settlements,
              stockTransfers = bundle.stockTransfers,
              paymentTransfers = bundle.paymentTransfers,
              mixCalcs = bundle.mixCalcs,
              approvals = bundle.approvals,
              accounts = bundle.accounts,
              recycleBinItems = bundle.recycleBinItems,
              dbStructure = bundle.dbStructure,
              lastUpdatedMillis = bundle.lastUpdatedMillis,
            )
          }
        }
    }
  }

  fun dismissSplash() {
    val user = syncManager.currentUser()
    val lockNeeded = _uiState.value.prefs.fingerprintLockEnabled && (user != null || _uiState.value.prefs.offlineModeActive)
    _uiState.update {
      it.copy(
        showSplash = false,
        isAuthenticated = user != null || it.prefs.offlineModeActive,
        isOfflineSession = user == null && it.prefs.offlineModeActive,
        isLockedByFingerprint = lockNeeded,
        signedInDisplayName = (user?.displayName ?: user?.email?.substringBefore("@") ?: "GULL AND ZUBAIR").uppercase(),
        signedInEmail = user?.email ?: it.prefs.savedEmail,
      )
    }
  }

  fun onFirebaseAuthChanged(user: FirebaseUser?) {
    currentFirebaseUser = user
    if (user != null) {
      val email = user.email ?: ""
      val matchedAccount = _uiState.value.accounts.firstOrNull { it.email.equals(email, ignoreCase = true) }
      val roles = matchedAccount?.rolesCsv?.split(",")
        ?.mapNotNull { r -> enumValues<UserRole>().firstOrNull { it.name.equals(r.trim(), ignoreCase = true) } }
        ?.toSet()
        ?.takeIf { it.isNotEmpty() } ?: setOf(UserRole.ADMIN)
      val display = (user.displayName ?: matchedAccount?.name ?: email.substringBefore("@").ifBlank { "GULL AND ZUBAIR" }).uppercase()
      viewModelScope.launch {
        if (email.isNotBlank()) runCatching { prefsManager.setSavedEmail(email) }
      }
      _uiState.update {
        val allowedTabs = it.copy(activeRoles = roles).visibleTabs()
        val nextTab = if (it.activeTab in allowedTabs) it.activeTab else allowedTabs.first()
        it.copy(
          isAuthenticated = true,
          isOfflineSession = false,
          signedInDisplayName = display,
          signedInEmail = email,
          activeRoles = roles,
          activeTab = nextTab,
          activeRoute = if (it.activeStandalone == null) SarimRoute.routeForTab(nextTab) else it.activeRoute,
        )
      }
      if (lastInitialSyncedUid != user.uid && isNetworkConnected()) {
        lastInitialSyncedUid = user.uid
        syncDataSilently()
      }
    } else {
      lastInitialSyncedUid = null
    }
  }

  fun enterOfflineSession(emailHint: String) {
    viewModelScope.launch {
      runCatching {
        prefsManager.setOfflineMode(true)
        if (emailHint.isNotBlank()) prefsManager.setSavedEmail(emailHint.trim())
      }
    }
    val matchedAccount = _uiState.value.accounts.firstOrNull { it.email.equals(emailHint.trim(), ignoreCase = true) }
    val roles = matchedAccount?.rolesCsv?.split(",")
      ?.mapNotNull { r -> enumValues<UserRole>().firstOrNull { it.name.equals(r.trim(), ignoreCase = true) } }
      ?.toSet()
      ?.takeIf { it.isNotEmpty() } ?: setOf(UserRole.ADMIN)
    val display = (matchedAccount?.name ?: emailHint.trim().substringBefore("@").ifBlank { "GULL AND ZUBAIR" }).uppercase()
    _uiState.update {
      val allowedTabs = it.copy(activeRoles = roles).visibleTabs()
      val nextTab = if (it.activeTab in allowedTabs) it.activeTab else allowedTabs.first()
      it.copy(
        isAuthenticated = true,
        isOfflineSession = true,
        isLockedByFingerprint = false,
        signedInDisplayName = display,
        signedInEmail = emailHint.trim(),
        activeRoles = roles,
        activeTab = nextTab,
        activeRoute = if (it.activeStandalone == null) SarimRoute.routeForTab(nextTab) else it.activeRoute,
      )
    }
  }

  fun unlockFingerprint() {
    _uiState.update { it.copy(isLockedByFingerprint = false) }
  }

  fun logout(onPerformFirebaseSignOut: () -> Unit) {
    showDialog(
      AppDialogRequest(
        title = "Logout",
        body = "Are you sure you want to sign out of ${uiState.value.signedInDisplayName}?",
        confirmLabel = "Logout",
        onConfirm = {
          viewModelScope.launch {
            runCatching { prefsManager.setOfflineMode(false) }
            runCatching { onPerformFirebaseSignOut() }
            _uiState.update {
              it.copy(
                isAuthenticated = false,
                isOfflineSession = false,
                activeStandalone = null,
                activeDialog = null,
              )
            }
          }
        },
      )
    )
  }

  fun selectTab(tab: MainTab) {
    feedbackManager.performTapFeedback()
    val targetRoute = SarimRoute.routeForTab(tab)
    _uiState.update { it.copy(activeTab = tab, activeRoute = targetRoute, activeStandalone = null) }
    _navActions.tryEmit(SarimNavAction.Navigate(targetRoute, popUpToStart = true))
  }

  fun openRoute(route: SarimRoute) {
    feedbackManager.performTapFeedback()
    val matchedTab = SarimRoute.tabForRoute(route.route)
    _uiState.update {
      it.copy(
        activeTab = matchedTab ?: it.activeTab,
        activeRoute = route.route,
        activeStandalone = if (route == SarimRoute.Ledger) StandaloneScreen.DailyLedger else null,
      )
    }
    _navActions.tryEmit(SarimNavAction.Navigate(route.route, popUpToStart = route.isPrimaryDestination))
  }

  fun openStandalone(screen: StandaloneScreen) {
    val missingId = when (screen) {
      is StandaloneScreen.CustomerDetails -> screen.customerId.isBlank()
      is StandaloneScreen.RepCustomerDetails -> screen.customerId.isBlank()
      is StandaloneScreen.EntityDetails -> screen.entityId.isBlank()
      is StandaloneScreen.ExpenseDetails -> screen.expenseName.isBlank()
      else -> false
    }
    if (missingId) {
      postStatus("Record details are unavailable")
      return
    }
    feedbackManager.performTapFeedback()
    val targetRoute = SarimRoute.routeForStandalone(screen)
    _uiState.update { it.copy(activeStandalone = screen, activeRoute = targetRoute) }
    _navActions.tryEmit(SarimNavAction.Navigate(targetRoute, popUpToStart = false))
  }

  fun closeStandalone() {
    feedbackManager.performTapFeedback()
    _uiState.update { it.copy(activeStandalone = null) }
    _navActions.tryEmit(SarimNavAction.PopBack)
  }

  fun onNavRouteChanged(route: String?) {
    if (route.isNullOrBlank()) return
    val tab = SarimRoute.tabForRoute(route)
    _uiState.update { current ->
      current.copy(
        activeRoute = route,
        activeTab = tab ?: current.activeTab,
        activeStandalone = when {
          tab != null -> null
          route == SarimRoute.Ledger.route -> StandaloneScreen.DailyLedger
          else -> current.activeStandalone
        },
      )
    }
  }

  fun setOverviewRange(range: OverviewRange) {
    feedbackManager.performTapFeedback()
    _uiState.update { it.copy(overviewRange = range) }
  }

  fun setOverviewPanel(panel: OverviewPanel) {
    feedbackManager.performTapFeedback()
    viewModelScope.launch { runCatching { prefsManager.setOverviewPanel(panel) } }
  }

  fun cycleTheme() {
    feedbackManager.performTapFeedback()
    viewModelScope.launch { runCatching { prefsManager.cycleTheme(_uiState.value.prefs.themeMode) } }
  }

  fun toggleFingerprintLock() {
    feedbackManager.performTapFeedback()
    viewModelScope.launch { runCatching { prefsManager.setFingerprintLock(!_uiState.value.prefs.fingerprintLockEnabled) } }
  }

  fun setHapticIntensity(intensity: HapticIntensity) {
    viewModelScope.launch { runCatching { prefsManager.setHaptic(intensity) } }
  }

  fun setSoundIntensity(intensity: SoundIntensity) {
    viewModelScope.launch { runCatching { prefsManager.setSound(intensity) } }
  }

  fun addSnippet(text: String) {
    val clean = text.trim().take(500)
    if (clean.isBlank()) return
    viewModelScope.launch {
      runCatching {
        val updated = (listOf(clean) + _uiState.value.prefs.snippets).distinct().take(30)
        prefsManager.saveSnippets(updated)
      }
    }
  }

  fun showDialog(req: AppDialogRequest) {
    _uiState.update { it.copy(activeDialog = req) }
  }

  fun dismissDialog() {
    _uiState.update { it.copy(activeDialog = null) }
  }

  fun openPhotoViewer(uris: List<String>, startIndex: Int = 0) {
    if (uris.isEmpty()) return
    _uiState.update { it.copy(photoViewerUris = uris, photoViewerIndex = startIndex.coerceIn(0, uris.lastIndex)) }
  }

  fun closePhotoViewer() {
    _uiState.update { it.copy(photoViewerUris = emptyList(), photoViewerIndex = 0) }
  }

  fun clearStatusMessage() {
    _uiState.update { it.copy(statusMessage = null) }
  }

  fun postStatus(msg: String) {
    _uiState.update { it.copy(statusMessage = msg) }
  }

  fun launchSafely(
    errorTitle: String = "Operation Failed",
    successMessage: String? = null,
    onSuccess: (() -> Unit)? = null,
    block: suspend () -> Unit,
  ) {
    viewModelScope.launch {
      try {
        block()
        if (!successMessage.isNullOrBlank()) {
          postStatus(successMessage)
        }
        onSuccess?.invoke()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        showDialog(
          AppDialogRequest(
            title = errorTitle,
            body = e.localizedMessage ?: "An unexpected error occurred. Your changes were not saved.",
            confirmLabel = "OK",
            cancelLabel = null,
          )
        )
      }
    }
  }

  private fun syncDataSilently() {
    viewModelScope.launch {
      _uiState.update { it.copy(isSyncing = true) }
      try {
        syncRepo.syncWithFirestore()
        refreshDiagnostics()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Silent initial background sync keeps local workspace active without blocking UI.
      } finally {
        _uiState.update { it.copy(isSyncing = false) }
      }
    }
  }

  fun syncDataNow() {
    viewModelScope.launch {
      _uiState.update { it.copy(isSyncing = true) }
      try {
        val result = syncRepo.syncWithFirestore()
        result.fold(
          onSuccess = { count ->
            refreshDiagnostics()
            showDialog(
              AppDialogRequest(
                title = "Sync Complete",
                body = if (count > 0) "Synced $count pending record(s) to cloud." else "All records are up to date.",
                confirmLabel = "Done",
                cancelLabel = null,
              )
            )
          },
          onFailure = { err ->
            showDialog(
              AppDialogRequest(
                title = "Sync Failed",
                body = err.message ?: "Unable to sync right now. Changes remain saved locally.",
                confirmLabel = "OK",
                cancelLabel = null,
              )
            )
          }
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        showDialog(
          AppDialogRequest(
            title = "Sync Failed",
            body = e.localizedMessage ?: "Unable to sync right now.",
            confirmLabel = "OK",
            cancelLabel = null,
          )
        )
      } finally {
        _uiState.update { it.copy(isSyncing = false) }
      }
    }
  }

  fun refreshDiagnostics() {
    viewModelScope.launch {
      try {
        val pending = syncRepo.countPendingSync()
        val bin = syncRepo.getRecycleBinItems()
        val struct = syncRepo.getDatabaseStructure()
        _uiState.update {
          it.copy(
            isOnline = isNetworkConnected(),
            pendingSyncCount = pending,
            recycleBinItems = bin,
            dbStructure = struct,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
      }
    }
  }

  fun applyAuditFix(finding: AuditFinding, newValueRaw: String) {
    val valNum = SarimFormat.parseSafeDouble(newValueRaw)
    if (valNum == null || valNum < 0.0) {
      postStatus("Invalid numeric value")
      return
    }
    launchSafely(errorTitle = "Audit Fix Failed", successMessage = "Updated record successfully") {
      when (finding.recordType) {
        "SALE" -> {
          val sale = _uiState.value.sales.firstOrNull { it.id == finding.recordId } ?: return@launchSafely
          val newTotal = sale.quantityKg * valNum
          val newProfit = newTotal - sale.totalCost
          dao.upsertSale(sale.copy(pricePerKg = valNum, totalValue = newTotal, profit = newProfit, syncPending = true, updatedAt = System.currentTimeMillis()))
        }
        "PRODUCTION" -> {
          val prod = _uiState.value.productionEntries.firstOrNull { it.id == finding.recordId } ?: return@launchSafely
          val net = (valNum - prod.containerWeightKg).coerceAtLeast(0.0)
          val costPerKg = if (net > 0.0001) prod.totalFormulaCost / net else 0.0
          val totalVal = net * prod.salePricePerKg
          dao.upsertProductionEntry(
            prod.copy(
              grossWeightKg = valNum,
              netWeightKg = net,
              costPerKg = costPerKg,
              profitPerKg = prod.salePricePerKg - costPerKg,
              totalValue = totalVal,
              totalProfit = totalVal - prod.totalFormulaCost,
              syncPending = true,
              updatedAt = System.currentTimeMillis(),
            )
          )
        }
        "MATERIAL" -> {
          val mat = _uiState.value.rawMaterials.firstOrNull { it.id == finding.recordId } ?: return@launchSafely
          dao.upsertRawMaterial(mat.copy(stockKg = valNum, syncPending = true, updatedAt = System.currentTimeMillis()))
        }
      }
    }
  }
}

private data class ObservedDataBundle(
  val prefs: UserPrefs,
  val isOnline: Boolean,
  val pendingSyncCount: Int,
  val snapshot: BusinessSnapshot,
  val stores: List<StoreEntity>,
  val rawMaterials: List<RawMaterialEntity>,
  val purchases: List<RawMaterialPurchaseEntity>,
  val formulas: List<FormulaEntity>,
  val factoryBatches: List<FactoryBatchEntity>,
  val productionEntries: List<ProductionEntryEntity>,
  val customers: List<CustomerEntity>,
  val sales: List<SaleTransactionEntity>,
  val paymentEntities: List<PaymentEntityRecord>,
  val paymentTransactions: List<PaymentTransactionEntity>,
  val dailyLedger: List<DailyLedgerEntity>,
  val settlements: List<CalculatorSettlementEntity>,
  val stockTransfers: List<StockTransferEntity>,
  val paymentTransfers: List<PaymentTransferEntity>,
  val mixCalcs: List<SavedMixCalcEntity>,
  val approvals: List<ApprovalRequestEntity>,
  val accounts: List<AppAccountEntity>,
  val recycleBinItems: List<RecycleBinRecord>,
  val dbStructure: List<CollectionStats>,
  val lastUpdatedMillis: Long,
)

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
