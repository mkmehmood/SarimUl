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
import com.example.ui.shell.*
import com.example.ui.theme.SarimTheme
import com.example.viewmodel.SarimViewModel

class MainActivity : ComponentActivity() {
  /** Set when the user taps an approval alert; consumed once the admin session is ready. */
  private var approvalTap by mutableStateOf(false)

  private fun readPushTap(intent: Intent?) {
    if (intent?.getStringExtra(SarimPush.EXTRA_TYPE) == SarimPush.TYPE_APPROVAL) {
      approvalTap = true
      intent.removeExtra(SarimPush.EXTRA_TYPE)
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
        if (state.isAuthenticated && state.isAdmin && !state.isOfflineSession) {
          if (SarimPush.canPostNotifications(context)) viewModel.registerPush()
          else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
          }
        }
      }
      LaunchedEffect(approvalTap, state.isAuthenticated, state.isAdmin, state.isLockedByFingerprint, state.showSplash) {
        if (approvalTap && state.isAuthenticated && !state.showSplash && !state.isLockedByFingerprint) {
          viewModel.openApprovalsFromPush()
          approvalTap = false
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
