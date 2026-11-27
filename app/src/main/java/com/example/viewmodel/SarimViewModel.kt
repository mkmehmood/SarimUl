package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.*
import com.example.data.model.*
import com.example.data.remote.*
import com.example.data.repository.*
import com.example.ui.navigation.SarimNavAction
import com.example.ui.navigation.SarimRoute
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.FeedbackManager
import com.example.util.SarimFormat
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SarimUiState(
  val showSplash: Boolean = true,
  val isAuthenticated: Boolean = false,
  val isOfflineSession: Boolean = false,
  val isLockedByFingerprint: Boolean = false,
  val currentUid: String = "",
  val workspaceOwnerUid: String? = null,
  val isLegacyAccount: Boolean = false,
  val sessionStartMillis: Long = 0L,
  val authErrorMessage: String? = null,
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
  val isAccountActionBusy: Boolean = false,
  val overviewRange: OverviewRange = OverviewRange.M6,
  val salesReps: List<String> = emptyList(),
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
  val dbStructureSnapshot: DatabaseStructureSnapshot? = null,
  val activeDialog: AppDialogRequest? = null,
  val photoViewerUris: List<String> = emptyList(),
  val photoViewerIndex: Int = 0,
  val statusMessage: String? = null,
  val lastUpdatedMillis: Long = System.currentTimeMillis(),
) {
  val isAdmin: Boolean get() = UserRole.ADMIN in activeRoles

  /** True when the account is a pure Rep session (mode "rep"): Rep screens stay as-is, admin view is hidden. */
  val isRepMode: Boolean
    get() = RolesHelper.deriveSession(RolesHelper.fromUserRoleSet(activeRoles)).mode == "rep"

  /** Admin View card on the Rep tab: never shown in rep mode. */
  val showRepAdminView: Boolean get() = isAdmin && !isRepMode

  /**
   * Locked-down account: non-admin and not a Rep. Only the entry card of the
   * assigned tab plus Settings/personalization remain visible.
   */
  val isLockedAccount: Boolean get() = !isAdmin && UserRole.REP !in activeRoles
  val isGatedSession: Boolean
    get() = !isOfflineSession && currentUid.isNotBlank() && !isAdmin && !workspaceOwnerUid.isNullOrBlank()
  val hideMoneyFigures: Boolean
    get() = RolesHelper.rolesRestrictMoney(RolesHelper.fromUserRoleSet(activeRoles))

  fun visibleTabs(): List<MainTab> {
    if (isAdmin) return MainTab.entries
    val tabs = RolesHelper.tabsForRoles(RolesHelper.fromUserRoleSet(activeRoles))
    return tabs.ifEmpty { listOf(MainTab.OVERVIEW) }
  }
}

class SarimViewModel(application: Application) : AndroidViewModel(application) {
  private val db = SarimDatabase.getInstance(application)
  private val dao = db.dao()
  private val safeReader = SafeSqliteCacheReader(dao)
  val prefsManager = PreferencesManager(application)
  val syncManager = FirebaseSyncManager(application)
  val uuidRegistry = UuidSyncRegistry(RoomKvStorePersistence(dao, safeReader))
  val repository = SarimRepository(dao, safeReader, uuidRegistry)
  val syncRepo = SarimSyncAndBackupRepository(dao, syncManager, prefsManager, safeReader, uuidRegistry)
  val feedbackManager = FeedbackManager(application)

  private val _uiState = MutableStateFlow(SarimUiState())
  val uiState: StateFlow<SarimUiState> = _uiState.asStateFlow()

  private val _navActions = MutableSharedFlow<SarimNavAction>(extraBufferCapacity = 32)
  val navActions: SharedFlow<SarimNavAction> = _navActions.asSharedFlow()

  @Volatile private var deviceId: String = ""
  @Volatile private var lastLiveDiagnostics: LiveFirestoreDiagnostics? = null
  private var currentFirebaseUser: FirebaseUser? = syncManager.currentUser()
  private var lastInitialSyncedUid: String? = null
  private var activeListenerKey: String? = null
  private var pingPullJob: Job? = null
  private var onSignOutCallback: (() -> Unit)? = null

  private val realtimeCallbacks = object : RealtimeSyncCallbacks {
    override fun getSessionStartMillis(): Long = _uiState.value.sessionStartMillis
    override fun isCurrentlyOnline(): Boolean = isNetworkConnected()

    override fun onForceSignOut(reason: String) {
      viewModelScope.launch {
        performForcedSignOut(reason)
      }
    }

    override fun onSessionDocChanged(info: AccountSessionInfo) {
      viewModelScope.launch {
        applyLiveSessionUpdate(info)
      }
    }

    override fun onRemotePing(collections: List<String>) {
      pingPullJob?.cancel()
      pingPullJob = viewModelScope.launch {
        delay(450L)
        if (!isNetworkConnected()) return@launch
        val st = _uiState.value
        if (!st.isAuthenticated || st.isOfflineSession) return@launch
        syncRepo.syncWithFirestore(isGated = st.isGatedSession, session = currentSessionInfo())
        runCatching { repository.syncLedgerFromTabs() }
        refreshDiagnostics()
      }
    }

    override fun onAdminPendingSnapshot(items: List<ApprovalRequestEntity>) {
      viewModelScope.launch {
        runCatching {
          syncRepo.replaceAdminPendingFromSnapshot(items)
          refreshDiagnostics()
        }
      }
    }

    override fun onOwnPendingResolved(item: ApprovalRequestEntity) {
      viewModelScope.launch {
        runCatching {
          syncRepo.handleOwnPendingResolved(item)
          val verb = if (item.status == "APPROVED") "approved" else "discarded"
          val detail = item.targetSummary.ifBlank { item.category }
          postStatus("Admin $verb your $detail")
          refreshDiagnostics()
        }
      }
    }

    override fun onSettingsConfigDoc(doc: Map<String, Any>) {
      viewModelScope.launch {
        runCatching {
          syncRepo.applyRemoteSettingsConfigDoc(doc)
          refreshDiagnostics()
        }
      }
    }

    override fun onTeamDoc(doc: Map<String, Any>) {
      viewModelScope.launch {
        runCatching {
          syncRepo.applyRemoteTeamDoc(doc)
          refreshDiagnostics()
        }
      }
    }

    override fun onYearCloseSignal(doc: Map<String, Any>) {
      viewModelScope.launch {
        val sigType = doc["type"]?.toString()?.trim().orEmpty()
        if (sigType.isNotEmpty() && sigType !in setOf("close", "restore", "fyClose")) return@launch
        val triggeredAt = if (doc["triggeredAt"] != null) SarimWebSchema.parseEpoch(doc["triggeredAt"]) else 0L
        val triggeredBy = doc["triggeredBy"]?.toString().orEmpty()
        val lastHandled = runCatching { prefsManager.getLastHandledYearCloseSignal() }.getOrDefault(0L)
        if (triggeredAt > lastHandled && triggeredBy != deviceId) {
          runCatching { prefsManager.setLastHandledYearCloseSignal(triggeredAt) }
          val msg = if (sigType == "restore") {
            "Backup was restored on another device. Syncing fresh cloud state..."
          } else {
            "Financial year was closed on another device. Syncing fresh opening balances..."
          }
          postStatus(msg)
          val st = _uiState.value
          uuidRegistry.setNewDeviceRestore(true)
          try {
            runCatching {
              syncRepo.wipeAllLocalWorkspaceData()
              deviceId = uuidRegistry.loadOrInitDeviceId(st.sessionStartMillis.takeIf { it > 0L })
              repository.initDefaults()
              if (isNetworkConnected() && st.isAuthenticated && !st.isOfflineSession) {
                syncRepo.syncWithFirestore(isGated = st.isGatedSession, session = currentSessionInfo())
                runCatching { repository.syncLedgerFromTabs() }
              }
              refreshDiagnostics()
            }
          } finally {
            uuidRegistry.setNewDeviceRestore(false)
          }
        }
      }
    }

    override fun onStoresDoc(doc: Map<String, Any>) {
      viewModelScope.launch {
        runCatching {
          syncRepo.applyRemoteStoresDoc(doc)
          refreshDiagnostics()
        }
      }
    }

    override fun onFormulasDoc(doc: Map<String, Any>) {
      viewModelScope.launch {
        runCatching {
          syncRepo.applyRemoteFormulasDoc(doc)
          refreshDiagnostics()
        }
      }
    }

    override fun onDailyLedgerDoc(doc: Map<String, Any>) {
      viewModelScope.launch {
        runCatching {
          syncRepo.applyRemoteDailyLedgerDoc(doc)
          repository.syncLedgerFromTabs()
          refreshDiagnostics()
        }
      }
    }

    override fun onCategoryDoc(colKey: String, doc: Map<String, Any>) {
      viewModelScope.launch {
        runCatching {
          syncRepo.applyRemoteCategoryDoc(colKey, doc)
        }
      }
    }

    override fun onDeletionsSnapshot(upserts: List<Pair<String, Map<String, Any>>>, removedIds: List<String>) {
      viewModelScope.launch {
        runCatching {
          syncRepo.applyRemoteDeletionsSnapshot(upserts, removedIds)
          refreshDiagnostics()
        }
      }
    }

    override fun onPhotosSnapshot(upserts: List<Pair<String, Map<String, Any>>>) {
      viewModelScope.launch {
        runCatching {
          syncRepo.applyRemotePhotosSnapshot(upserts)
          refreshDiagnostics()
        }
      }
    }

    override fun onRecordCollectionSnapshot(
      collection: String,
      docs: List<Pair<String, Map<String, Any>>>,
      removedIds: List<String>,
    ) {
      viewModelScope.launch {
        runCatching {
          syncRepo.applyRemoteRecordSnapshot(collection, docs, removedIds, isFullPull = false)
          if (collection in setOf(
              SarimWebSchema.COL_SALES,
              SarimWebSchema.COL_REP,
              SarimWebSchema.COL_TRANSACTIONS,
              SarimWebSchema.COL_CALCULATOR,
            )
          ) {
            repository.syncLedgerFromTabs()
          }
          refreshDiagnostics()
        }
      }
    }
  }

  init {
    syncManager.onQuotaEvent = { reads, writes ->
      viewModelScope.launch {
        if (reads > 0) uuidRegistry.trackFirestoreRead(reads)
        if (writes > 0) uuidRegistry.trackFirestoreWrite(writes)
      }
    }
    viewModelScope.launch {
      try {
        uuidRegistry.loadAll()
        deviceId = uuidRegistry.loadOrInitDeviceId()
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
    pingPullJob?.cancel()
    syncManager.stopRealtimeListeners()
    feedbackManager.release()
  }

  fun registerSignOutHandler(handler: () -> Unit) {
    onSignOutCallback = handler
  }

  fun clearAuthError() {
    _uiState.update { it.copy(authErrorMessage = null) }
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

  private fun currentSessionInfo(): AccountSessionInfo? {
    val st = _uiState.value
    if (st.currentUid.isBlank()) return null
    return AccountSessionInfo(
      uid = st.currentUid,
      email = st.signedInEmail,
      name = st.signedInDisplayName,
      roles = RolesHelper.fromUserRoleSet(st.activeRoles),
      ownerUid = st.workspaceOwnerUid,
      legacy = st.isLegacyAccount,
      approved = true,
      ts = st.sessionStartMillis,
    )
  }

  private fun workspaceRootUid(): String {
    val st = _uiState.value
    return st.workspaceOwnerUid?.takeIf { it.isNotBlank() }
      ?: st.currentUid.takeIf { it.isNotBlank() }
      ?: syncManager.currentUser()?.uid.orEmpty()
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
      repository.allKvKeys,
    ) { approvals, accounts, prefs, range, _ ->
      Tuple4(approvals, accounts, prefs, range)
    }

    viewModelScope.launch {
      combine(group1, group2, group3, group4) { g1, g2, g3, g4 ->
        val prefs = g4.c
        feedbackManager.updateSettings(prefs.hapticIntensity, prefs.soundIntensity)
        val storedReps = runCatching { repository.getSalesReps() }.getOrDefault(emptyList())
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
          storedReps = storedReps,
        )
        val pending = runCatching { syncRepo.countPendingSync() }.getOrDefault(0)
        val bin = runCatching { syncRepo.getRecycleBinItems() }.getOrDefault(emptyList())
        val structSnap = runCatching { syncRepo.getDatabaseStructureSnapshot(lastLiveDiagnostics) }.getOrNull()
        val struct = structSnap?.collections ?: runCatching { syncRepo.getDatabaseStructure(lastLiveDiagnostics) }.getOrDefault(emptyList())

        ObservedDataBundle(
          prefs = prefs,
          isOnline = isNetworkConnected(),
          pendingSyncCount = pending,
          salesReps = snap.repsList,
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
          dbStructureSnapshot = structSnap,
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
              salesReps = bundle.salesReps,
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
              dbStructureSnapshot = bundle.dbStructureSnapshot,
              lastUpdatedMillis = bundle.lastUpdatedMillis,
            )
          }
        }
    }
  }

  fun dismissSplash() {
    viewModelScope.launch {
      val user = syncManager.currentUser()
      val cached = prefsManager.readCachedAccountSession()
      val prefs = _uiState.value.prefs
      val lockNeeded = prefs.fingerprintLockEnabled && (user != null || prefs.offlineModeActive)
      val cachedRoles = cached?.let { RolesHelper.toUserRoleSet(it.roles, it.legacy) } ?: setOf(UserRole.ADMIN)
      SarimUuid.currentAppMode = SarimUuid.deriveAppMode(RolesHelper.fromUserRoleSet(cachedRoles))
      if (deviceId.isBlank()) {
        deviceId = uuidRegistry.loadOrInitDeviceId(cached?.ts)
      }
      val display = (cached?.name?.takeIf { it.isNotBlank() }
        ?: user?.displayName
        ?: user?.email?.substringBefore("@")
        ?: "GULL AND ZUBAIR").uppercase()
      _uiState.update { current ->
        val allowedTabs = current.copy(activeRoles = cachedRoles).visibleTabs()
        val nextTab = if (current.activeTab in allowedTabs) current.activeTab else allowedTabs.first()
        current.copy(
          showSplash = false,
          isAuthenticated = user != null || prefs.offlineModeActive,
          isOfflineSession = user == null && prefs.offlineModeActive,
          isLockedByFingerprint = lockNeeded,
          currentUid = user?.uid ?: cached?.uid.orEmpty(),
          workspaceOwnerUid = cached?.ownerUid,
          isLegacyAccount = cached?.legacy ?: false,
          activeRoles = cachedRoles,
          activeTab = nextTab,
          activeRoute = if (current.activeStandalone == null) SarimRoute.routeForTab(nextTab) else current.activeRoute,
          signedInDisplayName = display,
          signedInEmail = user?.email ?: cached?.email ?: prefs.savedEmail,
        )
      }
    }
  }

  fun onFirebaseAuthChanged(user: FirebaseUser?) {
    currentFirebaseUser = user
    if (user == null) {
      lastInitialSyncedUid = null
      activeListenerKey = null
      syncManager.stopRealtimeListeners()
      syncManager.clearCachedWorkspace()
      if (!_uiState.value.isOfflineSession && _uiState.value.isAuthenticated) {
        _uiState.update {
          it.copy(
            isAuthenticated = false,
            currentUid = "",
            workspaceOwnerUid = null,
            activeStandalone = null,
          )
        }
      }
      return
    }

    viewModelScope.launch {
      val result = syncManager.checkUserApprovedAndLoadSession(user, isNetworkConnected())
      when (result) {
        is SessionCheckResult.Allowed -> {
          applyApprovedSession(user, result.session)
        }
        is SessionCheckResult.Denied -> {
          syncManager.stopRealtimeListeners()
          syncManager.clearCachedWorkspace()
          activeListenerKey = null
          lastInitialSyncedUid = null
          runCatching { prefsManager.clearAccountSession() }
          _uiState.update {
            it.copy(
              isAuthenticated = false,
              isOfflineSession = false,
              currentUid = "",
              workspaceOwnerUid = null,
              activeStandalone = null,
              authErrorMessage = result.reason,
            )
          }
          runCatching { onSignOutCallback?.invoke() }
        }
        SessionCheckResult.OfflineFallback -> {
          restoreSessionOfflineFallback(user)
        }
      }
    }
  }

  private suspend fun applyApprovedSession(user: FirebaseUser, info: AccountSessionInfo) {
    val existingStart = runCatching { prefsManager.getSessionStartMillis() }.getOrDefault(0L)
    val startMs = if (existingStart > 0L) existingStart else System.currentTimeMillis()
    runCatching {
      prefsManager.saveAccountSession(info)
      prefsManager.setOfflineMode(false)
      if (existingStart <= 0L) prefsManager.setSessionStartMillis(startMs)
    }

    val roles = RolesHelper.toUserRoleSet(info.roles, info.legacy)
    SarimUuid.currentAppMode = SarimUuid.deriveAppMode(RolesHelper.fromUserRoleSet(roles))
    deviceId = uuidRegistry.loadOrInitDeviceId(startMs)
    val display = info.name.ifBlank { user.email?.substringBefore("@") ?: "GULL AND ZUBAIR" }.uppercase()
    _uiState.update { current ->
      val allowedTabs = current.copy(activeRoles = roles).visibleTabs()
      val nextTab = if (current.activeTab in allowedTabs) current.activeTab else allowedTabs.first()
      current.copy(
        isAuthenticated = true,
        isOfflineSession = false,
        currentUid = info.uid,
        workspaceOwnerUid = info.ownerUid,
        isLegacyAccount = info.legacy,
        sessionStartMillis = startMs,
        authErrorMessage = null,
        signedInDisplayName = display,
        signedInEmail = info.email.ifBlank { user.email.orEmpty() },
        activeRoles = roles,
        activeTab = nextTab,
        activeRoute = if (current.activeStandalone == null) SarimRoute.routeForTab(nextTab) else current.activeRoute,
      )
    }

    ensureRealtimeListenersForSession(info)
    if (UserRole.ADMIN in roles && isNetworkConnected()) {
      refreshAccounts()
    }
    if (lastInitialSyncedUid != user.uid && isNetworkConnected()) {
      lastInitialSyncedUid = user.uid
      syncDataSilently()
    }
  }

  private suspend fun restoreSessionOfflineFallback(user: FirebaseUser) {
    val email = (user.email ?: "").trim().lowercase()
    val cached = prefsManager.readCachedAccountSession()
    if (cached != null && !cached.approved && (cached.uid == user.uid || cached.email.equals(email, ignoreCase = true))) {
      runCatching { prefsManager.clearAccountSession() }
      _uiState.update {
        it.copy(
          isAuthenticated = false,
          isOfflineSession = false,
          currentUid = "",
          authErrorMessage = "Access denied. Your account has been suspended. Contact the administrator.",
        )
      }
      runCatching { onSignOutCallback?.invoke() }
      return
    }

    val info = if (cached != null && (cached.uid == user.uid || cached.email.equals(email, ignoreCase = true))) {
      cached
    } else {
      val localAcct = _uiState.value.accounts.firstOrNull {
        it.uid == user.uid || (email.isNotBlank() && it.email.equals(email, ignoreCase = true))
      }
      if (localAcct != null && !localAcct.approved) {
        _uiState.update {
          it.copy(
            isAuthenticated = false,
            authErrorMessage = "Access denied. Your account has been suspended. Contact the administrator.",
          )
        }
        runCatching { onSignOutCallback?.invoke() }
        return
      }
      val parsedRoles = localAcct?.let { RolesHelper.parseRolesCsv(it.rolesCsv) } ?: listOf("admin")
      AccountSessionInfo(
        uid = user.uid,
        email = email,
        name = localAcct?.name ?: user.displayName ?: email.substringBefore("@").ifBlank { "GULL AND ZUBAIR" },
        roles = parsedRoles,
        ownerUid = localAcct?.ownerUid?.takeIf { it.isNotBlank() },
        legacy = localAcct == null,
        approved = true,
      )
    }

    val roles = RolesHelper.toUserRoleSet(info.roles, info.legacy)
    val startMs = runCatching { prefsManager.getSessionStartMillis() }.getOrDefault(0L).takeIf { it > 0L }
      ?: System.currentTimeMillis()
    SarimUuid.currentAppMode = SarimUuid.deriveAppMode(RolesHelper.fromUserRoleSet(roles))
    deviceId = uuidRegistry.loadOrInitDeviceId(startMs)
    _uiState.update { current ->
      val allowedTabs = current.copy(activeRoles = roles).visibleTabs()
      val nextTab = if (current.activeTab in allowedTabs) current.activeTab else allowedTabs.first()
      current.copy(
        isAuthenticated = true,
        isOfflineSession = false,
        currentUid = info.uid,
        workspaceOwnerUid = info.ownerUid,
        isLegacyAccount = info.legacy,
        sessionStartMillis = startMs,
        authErrorMessage = null,
        signedInDisplayName = info.name.uppercase(),
        signedInEmail = email,
        activeRoles = roles,
        activeTab = nextTab,
        activeRoute = if (current.activeStandalone == null) SarimRoute.routeForTab(nextTab) else current.activeRoute,
      )
    }
  }

  private fun ensureRealtimeListenersForSession(info: AccountSessionInfo) {
    val wsUid = info.ownerUid?.takeIf { it.isNotBlank() } ?: info.uid
    val derived = RolesHelper.deriveSession(if (info.legacy && info.roles.isEmpty()) listOf("admin") else info.roles)
    val isAdmin = derived.mode == "admin"
    val isGated = !isAdmin && !info.ownerUid.isNullOrBlank()
    val key = "${info.uid}|$wsUid|$isAdmin|$isGated"
    if (activeListenerKey == key) return
    activeListenerKey = key
    syncManager.startRealtimeListeners(
      selfUid = info.uid,
      wsUid = wsUid,
      selfEmail = info.email,
      isAdmin = isAdmin,
      isGated = isGated,
      callbacks = realtimeCallbacks,
    )
  }

  private suspend fun applyLiveSessionUpdate(info: AccountSessionInfo) {
    val prev = _uiState.value
    if (prev.currentUid != info.uid) return
    val nextRoles = RolesHelper.toUserRoleSet(info.roles, info.legacy)
    val rolesChanged = nextRoles != prev.activeRoles
    SarimUuid.currentAppMode = SarimUuid.deriveAppMode(RolesHelper.fromUserRoleSet(nextRoles))
    runCatching { prefsManager.saveAccountSession(info) }
    val nextDisplay = info.name.ifBlank { prev.signedInDisplayName }.uppercase()
    _uiState.update { current ->
      val allowedTabs = current.copy(activeRoles = nextRoles).visibleTabs()
      val nextTab = if (current.activeTab in allowedTabs) current.activeTab else allowedTabs.first()
      current.copy(
        activeRoles = nextRoles,
        workspaceOwnerUid = info.ownerUid,
        isLegacyAccount = info.legacy,
        signedInDisplayName = nextDisplay,
        activeTab = nextTab,
        activeRoute = if (current.activeStandalone == null) SarimRoute.routeForTab(nextTab) else current.activeRoute,
      )
    }
    ensureRealtimeListenersForSession(info)
    if (rolesChanged) {
      val label = RolesHelper.roleNames(if (info.legacy && info.roles.isEmpty()) listOf("admin") else info.roles)
      postStatus("Your roles were updated: $label")
      if (UserRole.ADMIN in nextRoles) {
        refreshAccounts()
      }
      syncDataSilently()
    }
  }

  private suspend fun performForcedSignOut(reason: String) {
    pingPullJob?.cancel()
    syncManager.stopRealtimeListeners()
    syncManager.clearCachedWorkspace()
    activeListenerKey = null
    lastInitialSyncedUid = null
    lastLiveDiagnostics = null
    SarimUuid.currentAppMode = SarimUuid.MODE_ADMIN
    runCatching { prefsManager.clearAccountSession() }
    runCatching { syncRepo.wipeAllLocalWorkspaceData() }
    runCatching { uuidRegistry.clearDeviceIdStorage() }
    deviceId = runCatching { uuidRegistry.loadOrInitDeviceId() }.getOrDefault("")
    runCatching { repository.initDefaults() }
    _uiState.update {
      SarimUiState(
        showSplash = false,
        isAuthenticated = false,
        authErrorMessage = reason,
        prefs = it.prefs.copy(offlineModeActive = false, cachedSessionJson = "", sessionStartMillis = 0L),
      )
    }
    runCatching { onSignOutCallback?.invoke() }
  }

  fun enterOfflineSession(emailHint: String) {
    val matchedAccount = _uiState.value.accounts.firstOrNull { it.email.equals(emailHint.trim(), ignoreCase = true) }
    val roles = matchedAccount?.rolesCsv?.split(",")
      ?.mapNotNull { r -> enumValues<UserRole>().firstOrNull { it.name.equals(r.trim(), ignoreCase = true) } }
      ?.toSet()
      ?.takeIf { it.isNotEmpty() } ?: setOf(UserRole.ADMIN)
    SarimUuid.currentAppMode = SarimUuid.deriveAppMode(RolesHelper.fromUserRoleSet(roles))
    viewModelScope.launch {
      runCatching {
        prefsManager.setOfflineMode(true)
        if (emailHint.isNotBlank()) prefsManager.setSavedEmail(emailHint.trim())
      }
      if (deviceId.isBlank()) {
        deviceId = runCatching { uuidRegistry.loadOrInitDeviceId() }.getOrDefault("")
      }
      repository.initDefaults()
    }
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
    viewModelScope.launch {
      val st = _uiState.value
      var syncIncompleteOffline = false
      if (!st.isOfflineSession && st.isAuthenticated) {
        if (isNetworkConnected()) {
          val synced = withTimeoutOrNull(12_000L) {
            syncRepo.syncWithFirestore(isGated = st.isGatedSession, session = currentSessionInfo())
          }
          if (synced == null || synced.isFailure) {
            syncIncompleteOffline = true
          }
        } else {
          syncIncompleteOffline = true
        }
      }
      val pending = runCatching { syncRepo.countPendingSync() }.getOrDefault(0)
      val body = when {
        pending > 0 -> "$pending local change(s) have not been synced to the cloud yet. Signing out will clear local workspace data on this device. Sign out anyway?"
        syncIncompleteOffline -> "You are currently offline so cloud sync could not verify pending changes. Signing out will clear local workspace data. Sign out anyway?"
        else -> "Are you sure you want to sign out of ${_uiState.value.signedInDisplayName}?"
      }
      showDialog(
        AppDialogRequest(
          title = "Logout",
          body = body,
          tone = if (pending > 0 || syncIncompleteOffline) DialogTone.WARNING else DialogTone.CONFIRM,
          confirmLabel = "Logout",
          onConfirm = {
            viewModelScope.launch {
              pingPullJob?.cancel()
              syncManager.stopRealtimeListeners()
              syncManager.clearCachedWorkspace()
              activeListenerKey = null
              lastInitialSyncedUid = null
              lastLiveDiagnostics = null
              SarimUuid.currentAppMode = SarimUuid.MODE_ADMIN
              runCatching { prefsManager.clearAccountSession() }
              runCatching { syncRepo.wipeAllLocalWorkspaceData() }
              runCatching { uuidRegistry.clearDeviceIdStorage() }
              deviceId = runCatching { uuidRegistry.loadOrInitDeviceId() }.getOrDefault("")
              runCatching { repository.initDefaults() }
              runCatching { onPerformFirebaseSignOut() }
              _uiState.update { current ->
                SarimUiState(
                  showSplash = false,
                  isAuthenticated = false,
                  prefs = current.prefs.copy(offlineModeActive = false, cachedSessionJson = "", sessionStartMillis = 0L),
                )
              }
            }
          },
        )
      )
    }
  }

  // =====================================================================
  // App Accounts Management (mirrors app-accounts.screen.html)
  // =====================================================================

  fun refreshAccounts() {
    val wsUid = workspaceRootUid()
    if (wsUid.isBlank() || !_uiState.value.isAdmin || !isNetworkConnected()) return
    viewModelScope.launch {
      syncManager.loadWorkspaceAccounts(wsUid).getOrNull()?.let { list ->
        syncRepo.replaceAccountsFromCloud(list)
        refreshDiagnostics()
      }
    }
  }

  fun submitNewAccount(
    email: String,
    password: String,
    name: String,
    roles: Set<UserRole>,
    onClearForm: () -> Unit,
  ) {
    if (!_uiState.value.isAdmin) {
      postStatus("Only admins can manage accounts")
      return
    }
    if (!isNetworkConnected()) {
      showDialog(AppDialogRequest("Offline", "Internet connection is required to add or link accounts.", tone = DialogTone.WARNING, cancelLabel = null))
      return
    }
    val wsUid = workspaceRootUid()
    if (wsUid.isBlank()) return
    val roleKeys = RolesHelper.fromUserRoleSet(roles)
    if (roleKeys.isEmpty()) {
      showDialog(AppDialogRequest("Role Required", "Pick at least one role for this account.", tone = DialogTone.ERROR, cancelLabel = null))
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isAccountActionBusy = true) }
      try {
        val prepRes = syncManager.prepareAdminAccount(email, password, name, roleKeys, wsUid)
        prepRes.fold(
          onSuccess = { prep ->
            when (prep) {
              is AdminAccountPrepareResult.Blocked -> {
                showDialog(AppDialogRequest(prep.title, prep.message, tone = DialogTone.ERROR, cancelLabel = null))
              }
              is AdminAccountPrepareResult.NeedsAdoptConfirm -> {
                showDialog(
                  AppDialogRequest(
                    title = "Existing Login Found",
                    body = "The email ${prep.email} already has a login. Add it to this workspace with roles: ${RolesHelper.roleNames(prep.roles)}?",
                    tone = DialogTone.CONFIRM,
                    confirmLabel = "Add to Workspace",
                    onConfirm = {
                      viewModelScope.launch {
                        finalizeAccountAndSync(prep.uid, prep.email, prep.name, prep.roles, wsUid, onClearForm)
                      }
                    },
                  )
                )
              }
              is AdminAccountPrepareResult.Ready -> {
                finalizeAccountAndSync(prep.uid, prep.email, prep.name, prep.roles, wsUid, onClearForm)
              }
            }
          },
          onFailure = { err ->
            showDialog(AppDialogRequest("Add Account Failed", err.message ?: "Unable to add account.", tone = DialogTone.ERROR, cancelLabel = null))
          },
        )
      } finally {
        _uiState.update { it.copy(isAccountActionBusy = false) }
      }
    }
  }

  private suspend fun finalizeAccountAndSync(
    uid: String,
    email: String,
    name: String,
    roles: List<String>,
    wsUid: String,
    onClearForm: () -> Unit,
  ) {
    _uiState.update { it.copy(isAccountActionBusy = true) }
    try {
      val res = syncManager.finalizeAdminAccount(uid, email, name, roles, wsUid)
      res.fold(
        onSuccess = { acct ->
          repository.upsertLocalAppAccount(acct)
          if ("rep" in roles) {
            repository.ensureRepInList(acct.name)
            syncRepo.syncWithFirestore(isGated = false, session = currentSessionInfo())
          }
          refreshAccounts()
          refreshDiagnostics()
          onClearForm()
          postStatus("Added account (${acct.email})")
        },
        onFailure = { err ->
          showDialog(AppDialogRequest("Save Account Failed", err.message ?: "Unable to save account.", tone = DialogTone.ERROR, cancelLabel = null))
        },
      )
    } finally {
      _uiState.update { it.copy(isAccountActionBusy = false) }
    }
  }

  fun toggleAccountRole(acct: AppAccountEntity, roleKey: String) {
    if (!_uiState.value.isAdmin || !isNetworkConnected()) {
      if (!isNetworkConnected()) postStatus("Internet connection required to change roles")
      return
    }
    val targetUid = acct.uid.ifBlank { acct.id }
    val wsUid = workspaceRootUid()
    val currentRoles = RolesHelper.parseRolesCsv(acct.rolesCsv)
    val afterRoles = RolesHelper.toggleRole(currentRoles, roleKey)
    val isSelf = targetUid == _uiState.value.currentUid

    if ((isSelf || targetUid == wsUid) && "admin" !in afterRoles) {
      showDialog(
        AppDialogRequest(
          title = "Admin Role Required",
          body = if (targetUid == wsUid && !isSelf) {
            "The workspace owner's Admin role cannot be removed."
          } else {
            "You cannot remove your own Admin role while signed in."
          },
          tone = DialogTone.WARNING,
          cancelLabel = null,
        )
      )
      return
    }

    viewModelScope.launch {
      val removingRep = roleKey == "rep" && "rep" !in afterRoles
      val plan = if (removingRep) {
        val st = _uiState.value
        RolesHelper.repRemovalPlan(
          name = acct.name,
          repsList = st.salesReps,
          sales = st.sales,
          customers = st.customers,
          transactions = st.paymentTransactions,
          expenses = dao.getAllExpenses(),
          returns = dao.getAllReturns(),
        )
      } else null

      val copy = RolesHelper.describeRoleChange(acct.name, isSelf, roleKey, afterRoles)
      val extraNote = if (plan != null && plan.blocked) {
        "\n\nNote: ${acct.name} has ${plan.count} existing sales/client record(s), so their name will remain in historical dropdowns."
      } else ""

      showDialog(
        AppDialogRequest(
          title = copy.title,
          body = copy.message + extraNote,
          tone = if (copy.danger) DialogTone.PERMANENT else DialogTone.CONFIRM,
          confirmLabel = copy.confirmText,
          onConfirm = {
          viewModelScope.launch {
            _uiState.update { it.copy(isAccountActionBusy = true) }
            try {
              val res = syncManager.adminUpdateAccountRoles(acct, afterRoles, wsUid)
              res.fold(
                onSuccess = {
                  val updated = acct.copy(
                    rolesCsv = afterRoles.joinToString(",") { it.uppercase() },
                    syncPending = false,
                  )
                  repository.upsertLocalAppAccount(updated)
                  if ("rep" in afterRoles) {
                    repository.ensureRepInList(acct.name)
                    syncRepo.syncWithFirestore(isGated = false, session = currentSessionInfo())
                  } else if (plan != null && plan.remove) {
                    repository.dropRepFromList(acct.name)
                    syncRepo.syncWithFirestore(isGated = false, session = currentSessionInfo())
                  }
                  if (isSelf) {
                    val updatedSession = currentSessionInfo()?.copy(roles = afterRoles, legacy = false)
                    if (updatedSession != null) applyLiveSessionUpdate(updatedSession)
                  }
                  refreshAccounts()
                  postStatus("Updated roles for ${acct.name}")
                },
                onFailure = { err ->
                  showDialog(AppDialogRequest("Role Update Failed", err.message ?: "Unable to update roles.", tone = DialogTone.ERROR, cancelLabel = null))
                },
              )
            } finally {
              _uiState.update { it.copy(isAccountActionBusy = false) }
            }
          }
          },
        )
      )
    }
  }

  fun toggleAccountSuspension(acct: AppAccountEntity) {
    if (!_uiState.value.isAdmin || !isNetworkConnected()) {
      if (!isNetworkConnected()) postStatus("Internet connection required")
      return
    }
    val wsUid = workspaceRootUid()
    val targetUid = acct.uid.ifBlank { acct.id }
    if (targetUid == wsUid || targetUid == _uiState.value.currentUid) {
      showDialog(AppDialogRequest("Action Blocked", "You cannot suspend the workspace owner or your active account.", tone = DialogTone.WARNING, cancelLabel = null))
      return
    }
    val nextApproved = !acct.approved
    showDialog(
      AppDialogRequest(
        title = if (nextApproved) "Reinstate Account" else "Suspend Account",
        body = if (nextApproved) {
          "Restore sign-in access for ${acct.name} (${acct.email})?"
        } else {
          "Suspend ${acct.name} (${acct.email})? They will be signed out immediately and blocked until reinstated."
        },
        tone = if (nextApproved) DialogTone.CONFIRM else DialogTone.PERMANENT,
        confirmLabel = if (nextApproved) "Reinstate" else "Suspend",
        onConfirm = {
          viewModelScope.launch {
            _uiState.update { it.copy(isAccountActionBusy = true) }
            try {
              syncManager.adminToggleAccountApproval(acct, nextApproved, wsUid).fold(
                onSuccess = {
                  repository.upsertLocalAppAccount(acct.copy(approved = nextApproved, syncPending = false))
                  refreshAccounts()
                  postStatus(if (nextApproved) "Reinstated ${acct.name}" else "Suspended ${acct.name}")
                },
                onFailure = { err ->
                  showDialog(AppDialogRequest("Update Failed", err.message ?: "Unable to update status.", tone = DialogTone.ERROR, cancelLabel = null))
                },
              )
            } finally {
              _uiState.update { it.copy(isAccountActionBusy = false) }
            }
          }
        },
      )
    )
  }

  fun removeAccount(acct: AppAccountEntity) {
    if (!_uiState.value.isAdmin || !isNetworkConnected()) {
      if (!isNetworkConnected()) postStatus("Internet connection required")
      return
    }
    val wsUid = workspaceRootUid()
    val targetUid = acct.uid.ifBlank { acct.id }
    if (targetUid == wsUid || targetUid == _uiState.value.currentUid) {
      showDialog(AppDialogRequest("Action Blocked", "You cannot remove the workspace owner or your active account.", tone = DialogTone.WARNING, cancelLabel = null))
      return
    }
    viewModelScope.launch {
      val st = _uiState.value
      val plan = RolesHelper.repRemovalPlan(
        name = acct.name,
        repsList = st.salesReps,
        sales = st.sales,
        customers = st.customers,
        transactions = st.paymentTransactions,
        expenses = dao.getAllExpenses(),
        returns = dao.getAllReturns(),
      )
      val note = if (plan.blocked) {
        "\n\nExisting historical records (${plan.count}) for ${acct.name} remain preserved."
      } else ""

      showDialog(
        AppDialogRequest(
          title = "Remove Account",
          body = "Permanently remove ${acct.name} (${acct.email}) from this workspace?$note",
          tone = DialogTone.PERMANENT,
          confirmLabel = "Remove",
          onConfirm = {
          viewModelScope.launch {
            _uiState.update { it.copy(isAccountActionBusy = true) }
            try {
              syncManager.adminRemoveAccount(acct, wsUid).fold(
                onSuccess = {
                  repository.deleteLocalAppAccount(acct.id)
                  if (plan.remove) {
                    repository.dropRepFromList(acct.name)
                    syncRepo.syncWithFirestore(isGated = false, session = currentSessionInfo())
                  }
                  refreshAccounts()
                  postStatus("Removed ${acct.name}")
                },
                onFailure = { err ->
                  showDialog(AppDialogRequest("Remove Account Failed", err.message ?: "Unable to remove account.", tone = DialogTone.ERROR, cancelLabel = null))
                },
              )
            } finally {
              _uiState.update { it.copy(isAccountActionBusy = false) }
            }
          }
          },
        )
      )
    }
  }

  // =====================================================================
  // Pending Approvals & Financial Year Close Broadcast
  // =====================================================================

  suspend fun resolveApproval(req: ApprovalRequestEntity, approved: Boolean) {
    resolveApprovals(listOf(req), approved)
  }

  suspend fun resolveApprovals(reqs: List<ApprovalRequestEntity>, approved: Boolean) {
    val st = _uiState.value
    val res = syncRepo.resolveApprovals(
      items = reqs,
      approved = approved,
      deciderUid = st.currentUid,
      deciderName = st.signedInDisplayName,
    )
    if (res.isFailure) {
      throw res.exceptionOrNull() ?: IllegalStateException("Failed to resolve approval request(s).")
    }
    refreshDiagnostics()
  }

  suspend fun closeFinancialYearAndBroadcast(): Int {
    val compacted = repository.closeFinancialYear()
    val now = System.currentTimeMillis()
    val st = _uiState.value
    val activeDevId = deviceId.ifBlank { uuidRegistry.loadOrInitDeviceId() }.also { deviceId = it }
    if (!st.isOfflineSession && isNetworkConnected()) {
      val (fyCount, _, _) = repository.readYearCloseMeta()
      runCatching { prefsManager.setLastHandledYearCloseSignal(now) }
      syncManager.pushSingletonDoc(
        SarimWebSchema.COL_SETTINGS,
        "yearCloseSignal",
        SarimWebSchema.yearCloseSignalPayload(
          type = "close",
          triggeredAt = now,
          triggeredBy = activeDevId,
          fyCloseCount = fyCount,
        ),
      )
      syncRepo.syncWithFirestore(isGated = st.isGatedSession, session = currentSessionInfo())
    }
    refreshDiagnostics()
    return compacted
  }

  // =====================================================================
  // Navigation, Preferences & Diagnostics
  // =====================================================================

  fun selectTab(tab: MainTab) {
    feedbackManager.performTapFeedback()
    val targetRoute = SarimRoute.routeForTab(tab)
    _uiState.update { it.copy(activeTab = tab, activeRoute = targetRoute, activeStandalone = null) }
    _navActions.tryEmit(SarimNavAction.Navigate(targetRoute, popUpToStart = true))
  }

  fun openRoute(route: SarimRoute) {
    if (_uiState.value.isLockedAccount && route != SarimRoute.Settings && route != SarimRoute.SoundHaptics &&
      SarimRoute.tabForRoute(route.route) !in _uiState.value.visibleTabs()
    ) {
      postStatus("This section is locked for your account")
      return
    }
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
    if (_uiState.value.isLockedAccount && screen !is StandaloneScreen.Settings && screen !is StandaloneScreen.SoundHaptics) {
      postStatus("This section is locked for your account")
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
    val currentlyEnabled = _uiState.value.prefs.fingerprintLockEnabled
    if (currentlyEnabled) {
      showDialog(
        AppDialogRequest(
          title = "Remove Biometric Lock",
          body = "Remove the fingerprint lock from this app?\n• Anyone with access to this device can open the app without biometric verification\n• To re-enable, tap Enable Fingerprint Lock in Settings again\nYour data will not be affected.",
          tone = DialogTone.PERMANENT,
          confirmLabel = "Remove Lock",
          onConfirm = {
            viewModelScope.launch { runCatching { prefsManager.setFingerprintLock(false) } }
          },
        )
      )
    } else {
      viewModelScope.launch {
        runCatching { prefsManager.setFingerprintLock(true) }
        postStatus("Biometric Lock Enabled")
      }
    }
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
        val st = _uiState.value
        syncRepo.syncWithFirestore(isGated = st.isGatedSession, session = currentSessionInfo())
        runCatching { repository.syncLedgerFromTabs() }
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
    if (!isNetworkConnected() && !_uiState.value.isOfflineSession) {
      showDialog(
        AppDialogRequest(
          title = "No Internet",
          body = "Internet connection is required to sync with cloud. Your changes remain saved locally.",
          tone = DialogTone.WARNING,
          confirmLabel = "OK",
          cancelLabel = null,
        )
      )
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSyncing = true) }
      try {
        val st = _uiState.value
        val result = syncRepo.syncWithFirestore(isGated = st.isGatedSession, session = currentSessionInfo())
        result.fold(
          onSuccess = { count ->
            runCatching { repository.syncLedgerFromTabs() }
            refreshDiagnostics()
            showDialog(
              AppDialogRequest(
                title = "Sync Complete",
                body = when {
                  st.isGatedSession && count > 0 -> "Submitted $count request(s) for admin approval and refreshed cloud data."
                  count > 0 -> "Synced $count pending record(s) to cloud."
                  else -> "All records are up to date."
                },
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

  fun refreshDiagnostics(includeCloudDiagnostics: Boolean = false) {
    viewModelScope.launch {
      try {
        if (includeCloudDiagnostics && isNetworkConnected() && !_uiState.value.isOfflineSession) {
          val wsUid = workspaceRootUid()
          if (wsUid.isNotBlank()) {
            syncManager.fetchLiveDatabaseDiagnostics(wsUid).getOrNull()?.let { live ->
              lastLiveDiagnostics = live
            }
          }
        }
        val pending = syncRepo.countPendingSync()
        val bin = syncRepo.getRecycleBinItems()
        val structSnap = syncRepo.getDatabaseStructureSnapshot(lastLiveDiagnostics)
        _uiState.update {
          it.copy(
            isOnline = isNetworkConnected(),
            pendingSyncCount = pending,
            recycleBinItems = bin,
            dbStructure = structSnap.collections,
            dbStructureSnapshot = structSnap,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
      }
    }
  }

  suspend fun resolveLedgerTarget(
    type: String,
    scope: String,
    party: String,
    category: String,
    overrideChoice: String? = null,
  ): SarimLinkEngine.LedgerLinkTarget = repository.resolveLedgerTargetForInput(
    type = type,
    scope = scope,
    party = party,
    category = category,
    overrideChoice = overrideChoice,
  )

  suspend fun verifyPasswordForSensitiveAction(password: String): Result<Unit> =
    syncRepo.run { syncManager.verifyCurrentUserPassword(password) }

  fun recordCustomerMoneyOut(
    customer: CustomerEntity,
    dateEpoch: Long,
    amount: Double,
    note: String,
    addToLedger: Boolean,
    onDone: () -> Unit = {},
  ) {
    if (amount <= 0.0) {
      showDialog(AppDialogRequest("Invalid Amount", "Enter a positive amount.", tone = DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))
      return
    }
    if (SarimFormat.isFutureDate(dateEpoch)) {
      showDialog(AppDialogRequest("Invalid Date", "Future dates are refused on customer payouts.", tone = DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))
      return
    }
    launchSafely(
      errorTitle = "Money Out Failed",
      successMessage = "Recorded Money Out for ${customer.name}",
      onSuccess = onDone,
    ) {
      repository.saveSaleOrCollection(
        editingId = null,
        dateEpoch = dateEpoch,
        mode = "MONEY_OUT",
        customerName = customer.name,
        newCustomerPhone = customer.phone,
        repName = if (customer.isRepCustomer) customer.repName else "Direct",
        store = null,
        quantityKg = 0.0,
        costPerKg = 0.0,
        paymentType = "MONEY_OUT",
        amountReceived = amount,
        expiredQtyKg = 0.0,
        returnedQtyKg = 0.0,
        addToLedger = addToLedger,
        isRepSale = customer.isRepCustomer,
        notes = note,
      )
    }
  }

  fun repairSaleLinks() {
    launchSafely(errorTitle = "Link Repair Failed") {
      val repaired = repository.repairSaleLinks()
      postStatus(
        if (repaired > 0) "Repaired $repaired linked sale/collection record(s)" else "All sale/collection links are clean"
      )
    }
  }

  fun runDeduplicationCleanup() {
    launchSafely(errorTitle = "Cleanup Failed") {
      val removed = syncRepo.deduplicateAllData()
      refreshDiagnostics()
      if (removed > 0 && isNetworkConnected() && !_uiState.value.isOfflineSession) {
        runCatching { syncRepo.syncWithFirestore(isGated = _uiState.value.isGatedSession, session = currentSessionInfo()) }
      }
      showDialog(
        AppDialogRequest(
          title = "Cleanup Complete",
          body = if (removed > 0) "Done! $removed duplicate record(s) removed and synced." else "No duplicate records found. All collections are clean.",
          tone = DialogTone.SUCCESS,
          confirmLabel = "Done",
          cancelLabel = null,
        )
      )
    }
  }

  fun applyAuditFix(finding: AuditFinding, newValueRaw: String) {
    if (finding.recordType == "LINK_REPAIR") {
      launchSafely(errorTitle = "Link Repair Failed", successMessage = "Repaired sale & collection links") {
        repository.applyAuditFix("LINK_REPAIR", finding.recordId, 0.0)
      }
      return
    }
    val valNum = SarimFormat.parseSafeDouble(newValueRaw)
    if (valNum == null || valNum < 0.0) {
      showDialog(AppDialogRequest("Invalid Value", "Enter a valid non-negative number.", tone = DialogTone.ERROR, confirmLabel = "OK", cancelLabel = null))
      return
    }
    launchSafely(errorTitle = "Audit Fix Failed", successMessage = "Updated record successfully") {
      repository.applyAuditFix(finding.recordType, finding.recordId, valNum)
    }
  }
}

private data class ObservedDataBundle(
  val prefs: UserPrefs,
  val isOnline: Boolean,
  val pendingSyncCount: Int,
  val salesReps: List<String>,
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
  val dbStructureSnapshot: DatabaseStructureSnapshot?,
  val lastUpdatedMillis: Long,
)

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
