package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.remote.authStateFlow
import com.example.ui.components.FullScreenPhotoViewer
import com.example.ui.components.GlassAlertDialog
import com.example.ui.shell.*
import com.example.ui.theme.SarimTheme
import com.example.viewmodel.SarimViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val viewModel: SarimViewModel = viewModel()
      val state by viewModel.uiState.collectAsStateWithLifecycle()
      val context = LocalContext.current
      val scope = rememberCoroutineScope()
      val credentialManager = remember(context) { CredentialManager.create(context) }

      val firebaseUser by remember { Firebase.auth.authStateFlow() }.collectAsStateWithLifecycle(
        initialValue = Firebase.auth.currentUser,
      )

      LaunchedEffect(firebaseUser?.uid) {
        viewModel.onFirebaseAuthChanged(firebaseUser)
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
              onGoogleAuthSuccess = { viewModel.onFirebaseAuthChanged(Firebase.auth.currentUser) },
              onContinueOffline = { email -> viewModel.enterOfflineSession(email) },
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
