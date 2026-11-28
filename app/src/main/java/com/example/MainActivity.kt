package com.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.remote.SarimPush
import com.example.data.remote.authStateFlow
import com.example.ui.components.FullScreenPhotoViewer
import com.example.ui.components.GlassAlertDialog
import com.example.ui.components.FailedOpsDialog
import com.example.ui.components.InvoiceShareHost
import com.example.ui.shell.*
import com.example.ui.theme.SarimTheme
import com.example.viewmodel.SarimViewModel

class MainActivity : ComponentActivity() {
  /** Set when the user taps an approval or activity alert; consumed once the session is ready. */
  private var pendingPushTarget by mutableStateOf<SarimPush.NotificationTarget?>(null)

  private fun readPushTap(intent: Intent?) {
    if (intent == null) return
    val parsedTarget = SarimPush.parseTarget(
      raw = intent.getStringExtra(SarimPush.EXTRA_TARGET),
      explicitKey = intent.getStringExtra(SarimPush.EXTRA_TARGET_KEY),
      explicitId = intent.getStringExtra(SarimPush.EXTRA_TARGET_ID),
    )
    val type = intent.getStringExtra(SarimPush.EXTRA_TYPE)
    when {
      parsedTarget != null -> {
        pendingPushTarget = parsedTarget
        intent.removeExtra(SarimPush.EXTRA_TARGET)
        intent.removeExtra(SarimPush.EXTRA_TARGET_KEY)
        intent.removeExtra(SarimPush.EXTRA_TARGET_ID)
        intent.removeExtra(SarimPush.EXTRA_TYPE)
      }
      type == SarimPush.TYPE_APPROVAL -> {
        pendingPushTarget = SarimPush.NotificationTarget("view", "approvals")
        intent.removeExtra(SarimPush.EXTRA_TYPE)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    readPushTap(intent)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    SarimPush.ensureChannel(applicationContext)
    if (savedInstanceState == null) readPushTap(intent)
    setContent {
      val viewModel: SarimViewModel = viewModel()
      val state by viewModel.uiState.collectAsStateWithLifecycle()
      val context = LocalContext.current
      val scope = rememberCoroutineScope()
      val credentialManager = remember(context) { CredentialManager.create(context) }

      val firebaseUser by remember { viewModel.syncManager.authStateFlow() }.collectAsStateWithLifecycle(
        initialValue = viewModel.syncManager.currentUser(),
      )

      LaunchedEffect(credentialManager) {
        viewModel.registerSignOutHandler {
          viewModel.syncManager.signOut(credentialManager, scope)
        }
      }

      LaunchedEffect(firebaseUser?.uid) {
        viewModel.onFirebaseAuthChanged(firebaseUser)
      }

      val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.registerPush()
      }
      LaunchedEffect(state.isAuthenticated, state.isAdmin, state.isOfflineSession) {
        if (state.isAuthenticated && !state.isOfflineSession) {
          if (SarimPush.canPostNotifications(context)) {
            if (state.isAdmin) viewModel.registerPush()
          } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
          }
        }
      }
      LaunchedEffect(pendingPushTarget, state.isAuthenticated, state.isAdmin, state.isLockedByFingerprint, state.showSplash) {
        val target = pendingPushTarget
        if (target != null && state.isAuthenticated && !state.showSplash && !state.isLockedByFingerprint) {
          viewModel.openNotificationTarget(target)
          pendingPushTarget = null
        }
      }

      val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
      DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
          when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_STOP ->
              viewModel.onAppBackgrounded(this@MainActivity.isChangingConfigurations)
            androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
              viewModel.onAppResumed()
            else -> Unit
          }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
      }
      LaunchedEffect(Unit) {
        while (true) {
          kotlinx.coroutines.delay(60_000L)
          viewModel.checkDateRollover()
        }
      }

      SarimTheme(themeMode = state.prefs.themeMode) {
        when {
          state.showSplash -> {
            SplashScreen(onFinished = { viewModel.dismissSplash() })
          }
          !state.isAuthenticated -> {
            AuthScreen(
              savedEmail = state.prefs.savedEmail,
              syncManager = viewModel.syncManager,
              authErrorMessage = state.authErrorMessage,
              onClearAuthError = { viewModel.clearAuthError() },
              onGoogleAuthSuccess = { viewModel.onFirebaseAuthChanged(viewModel.syncManager.currentUser()) },
              onEnterOfflineMode = { emailHint -> viewModel.enterOfflineSession(emailHint) },
            )
          }
          state.isLockedByFingerprint -> {
            FingerprintLockScreen(
              displayName = state.signedInDisplayName,
              onUnlocked = { viewModel.unlockFingerprint() },
            )
          }
          else -> {
            key(firebaseUser?.uid ?: "offline") {
              AppShell(
                state = state,
                viewModel = viewModel,
                onLogout = {
                  viewModel.logout(
                    onPerformFirebaseSignOut = {
                      viewModel.syncManager.signOut(credentialManager, scope)
                    },
                  )
                },
              )
            }
          }
        }

        InvoiceShareHost(state = state, viewModel = viewModel)
        if (state.showFailedOps && state.failedOps.isNotEmpty()) {
          FailedOpsDialog(ops = state.failedOps, viewModel = viewModel)
        }

        state.activeDialog?.let { req ->
          GlassAlertDialog(
            request = req,
            onDismiss = { viewModel.dismissDialog() },
          )
        }

        if (state.photoViewerUris.isNotEmpty()) {
          FullScreenPhotoViewer(
            uris = state.photoViewerUris,
            initialIndex = state.photoViewerIndex,
            onClose = { viewModel.closePhotoViewer() },
          )
        }
      }
    }
  }
}
