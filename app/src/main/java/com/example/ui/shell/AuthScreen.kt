package com.example.ui.shell

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Patterns
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.example.R
import com.example.data.remote.FirebaseSyncManager
import com.example.ui.components.SarimCallout
import com.example.ui.components.SarimPrimaryButton
import com.example.ui.components.SarimSecondaryButton
import com.example.ui.components.SarimTextField
import com.example.ui.components.SarimToggleGroup
import com.example.ui.theme.CinzelFontFamily
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.PlayfairDisplayFontFamily
import kotlinx.coroutines.launch

private enum class AuthScreenMode(val label: String) {
  SIGN_IN("Sign In"),
  REGISTER("Register"),
}

@Composable
fun AuthScreen(
  savedEmail: String,
  syncManager: FirebaseSyncManager,
  onGoogleAuthSuccess: () -> Unit,
  onContinueOffline: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val palette = LocalSarimPalette.current
  val credentialManager = remember(context) { CredentialManager.create(context) }

  var mode by remember { mutableStateOf(AuthScreenMode.SIGN_IN) }
  var fullName by remember { mutableStateOf("") }
  var email by remember(savedEmail) { mutableStateOf(savedEmail) }
  var password by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var passwordVisible by remember { mutableStateOf(false) }
  var confirmPasswordVisible by remember { mutableStateOf(false) }
  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf("") }
  var infoMessage by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    syncManager.attemptAutoSignIn(
      context = context,
      credentialManager = credentialManager,
      scope = scope,
      onAuthSuccess = onGoogleAuthSuccess,
      onUnauthenticated = {},
    )
  }

  val infinite = rememberInfiniteTransition(label = "orbs")
  val orbShift by infinite.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "orb_shift",
  )

  fun validateEmailAndPassword(isRegister: Boolean): Boolean {
    val cleanEmail = email.trim()
    if (isRegister && fullName.trim().length < 2) {
      errorMessage = "Enter your full name or dealership name."
      return false
    }
    if (cleanEmail.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
      errorMessage = "Enter a valid email address."
      return false
    }
    if (password.length < 6) {
      errorMessage = "Password must be at least 6 characters."
      return false
    }
    if (isRegister && password != confirmPassword) {
      errorMessage = "Passwords do not match."
      return false
    }
    return true
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(palette.background),
  ) {
    // Soft animated colour orbs
    Canvas(modifier = Modifier.fillMaxSize()) {
      drawCircle(
        brush = Brush.radialGradient(
          listOf(palette.green.copy(alpha = 0.24f), Color.Transparent),
          center = Offset(size.width * (0.2f + orbShift * 0.25f), size.height * 0.18f),
          radius = size.width * 0.6f,
        ),
        center = Offset(size.width * (0.2f + orbShift * 0.25f), size.height * 0.18f),
        radius = size.width * 0.6f,
      )
      drawCircle(
        brush = Brush.radialGradient(
          listOf(palette.cyan.copy(alpha = 0.18f), Color.Transparent),
          center = Offset(size.width * (0.8f - orbShift * 0.2f), size.height * 0.82f),
          radius = size.width * 0.55f,
        ),
        center = Offset(size.width * (0.8f - orbShift * 0.2f), size.height * 0.82f),
        radius = size.width * 0.55f,
      )
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .systemBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Surface(
        modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = palette.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, palette.border),
      ) {
        Column(
          modifier = Modifier.padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Box(
            modifier = Modifier
              .size(68.dp)
              .clip(CircleShape)
              .background(palette.green.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              painter = painterResource(id = R.drawable.ic_launcher_foreground),
              contentDescription = "Gull and Zubair logo",
              tint = Color.Unspecified,
              modifier = Modifier.size(62.dp),
            )
          }

          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              "GULL AND ZUBAIR",
              color = palette.textPrimary,
              fontFamily = CinzelFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 20.sp,
              letterSpacing = 1.8.sp,
            )
            Text(
              "Naswar Dealers",
              color = palette.green,
              fontFamily = PlayfairDisplayFontFamily,
              fontSize = 14.sp,
            )
          }

          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = if (mode == AuthScreenMode.SIGN_IN) "Dealer Sign In" else "Register Dealer Account",
              color = palette.textPrimary,
              fontSize = 19.sp,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = if (mode == AuthScreenMode.SIGN_IN) {
                "Sign in with email & password to secure and sync your ledger and inventory."
              } else {
                "Create your secured Firebase dealer account."
              },
              color = palette.textMuted,
              fontSize = 12.5.sp,
              textAlign = TextAlign.Center,
            )
          }

          SarimToggleGroup(
            options = AuthScreenMode.entries,
            selected = mode,
            labelOf = { it.label },
            onSelect = {
              mode = it
              errorMessage = ""
              infoMessage = ""
            },
            testTagPrefix = "auth_mode",
          )

          AnimatedVisibility(visible = mode == AuthScreenMode.REGISTER) {
            SarimTextField(
              value = fullName,
              onValueChange = { fullName = it },
              label = "Dealer / Full Name",
              placeholder = "e.g. Gull & Zubair Dealer",
              leadingIcon = Icons.Rounded.Person,
              maxLength = 60,
              testTag = "auth_full_name",
            )
          }

          SarimTextField(
            value = email,
            onValueChange = { email = it },
            label = "Email address",
            placeholder = "dealer@example.com",
            leadingIcon = Icons.Rounded.Email,
            keyboardType = KeyboardType.Email,
            testTag = "auth_email",
          )

          SarimTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            placeholder = "At least 6 characters",
            leadingIcon = Icons.Rounded.Lock,
            keyboardType = KeyboardType.Password,
            isPassword = true,
            passwordVisible = passwordVisible,
            trailingContent = {
              IconButton(
                onClick = { passwordVisible = !passwordVisible },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("auth_toggle_password"),
              ) {
                Icon(
                  imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                  contentDescription = if (passwordVisible) "Hide password" else "Show password",
                  tint = palette.textMuted,
                )
              }
            },
            testTag = "auth_password",
          )

          AnimatedVisibility(visible = mode == AuthScreenMode.REGISTER) {
            SarimTextField(
              value = confirmPassword,
              onValueChange = { confirmPassword = it },
              label = "Confirm Password",
              placeholder = "Re-enter password",
              leadingIcon = Icons.Rounded.Lock,
              keyboardType = KeyboardType.Password,
              isPassword = true,
              passwordVisible = confirmPasswordVisible,
              trailingContent = {
                IconButton(
                  onClick = { confirmPasswordVisible = !confirmPasswordVisible },
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("auth_toggle_confirm_password"),
                ) {
                  Icon(
                    imageVector = if (confirmPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (confirmPasswordVisible) "Hide confirm password" else "Show confirm password",
                    tint = palette.textMuted,
                  )
                }
              },
              testTag = "auth_confirm_password",
            )
          }

          if (mode == AuthScreenMode.SIGN_IN) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
              TextButton(
                enabled = !isLoading,
                onClick = {
                  val cleanEmail = email.trim()
                  if (cleanEmail.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
                    errorMessage = "Enter your email address first to reset your password."
                    return@TextButton
                  }
                  isLoading = true
                  errorMessage = ""
                  infoMessage = ""
                  scope.launch {
                    val res = syncManager.sendPasswordReset(cleanEmail)
                    isLoading = false
                    res.fold(
                      onSuccess = { infoMessage = "Password reset link sent to $cleanEmail." },
                      onFailure = { err -> errorMessage = err.message ?: "Unable to send reset email." },
                    )
                  }
                },
                modifier = Modifier.testTag("auth_forgot_password"),
              ) {
                Text("Forgot password?", color = palette.cyan, fontSize = 12.5.sp)
              }
            }
          }

          SarimPrimaryButton(
            text = when {
              isLoading && mode == AuthScreenMode.SIGN_IN -> "Signing in..."
              isLoading && mode == AuthScreenMode.REGISTER -> "Creating account..."
              mode == AuthScreenMode.SIGN_IN -> "Sign In with Email"
              else -> "Create Dealer Account"
            },
            enabled = !isLoading,
            testTag = "email_auth_submit_btn",
            onClick = {
              errorMessage = ""
              infoMessage = ""
              val isRegister = mode == AuthScreenMode.REGISTER
              if (!validateEmailAndPassword(isRegister)) return@SarimPrimaryButton
              isLoading = true
              scope.launch {
                val res = if (isRegister) {
                  syncManager.registerWithEmailPassword(fullName, email, password)
                } else {
                  syncManager.signInWithEmailPassword(email, password)
                }
                isLoading = false
                res.fold(
                  onSuccess = { onGoogleAuthSuccess() },
                  onFailure = { err -> errorMessage = err.message ?: "Authentication failed." },
                )
              }
            },
          )

          if (errorMessage.isNotBlank()) {
            SarimCallout(text = errorMessage, toneColor = palette.red)
          }
          if (infoMessage.isNotBlank()) {
            SarimCallout(text = infoMessage, toneColor = palette.green)
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = palette.border)
            Text("or", color = palette.textMuted, fontSize = 12.sp)
            HorizontalDivider(modifier = Modifier.weight(1f), color = palette.border)
          }

          SarimSecondaryButton(
            text = "Sign in with Google",
            modifier = Modifier.fillMaxWidth(),
            testTag = "google_sign_in_btn",
            onClick = {
              if (isLoading) return@SarimSecondaryButton
              isLoading = true
              errorMessage = ""
              infoMessage = ""
              syncManager.onGoogleSignInClicked(
                context = context,
                credentialManager = credentialManager,
                scope = scope,
                onAuthSuccess = {
                  isLoading = false
                  onGoogleAuthSuccess()
                },
                onAuthError = { err ->
                  isLoading = false
                  errorMessage = err
                },
                onAuthCancelled = {
                  isLoading = false
                  infoMessage = "Google sign-in cancelled."
                },
              )
            },
          )

          SarimSecondaryButton(
            text = "Continue Offline (Local Vault)",
            onClick = { onContinueOffline(email) },
            icon = Icons.Rounded.Lock,
            modifier = Modifier.fillMaxWidth(),
            testTag = "offline_continue_btn",
          )

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = palette.green, modifier = Modifier.size(14.dp))
            Text(
              "Secured by Firebase Auth · Local Room Vault",
              color = palette.textMuted,
              fontSize = 11.sp,
            )
          }
        }
      }
    }
  }
}

@Composable
fun FingerprintLockScreen(
  displayName: String,
  onUnlocked: () -> Unit,
) {
  val context = LocalContext.current
  val palette = LocalSarimPalette.current
  var statusText by remember { mutableStateOf("Tap fingerprint icon to unlock") }

  fun triggerBiometric(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      try {
        val prompt = BiometricPrompt.Builder(ctx)
          .setTitle("Unlock Gull & Zubair")
          .setSubtitle("Verify your fingerprint to continue")
          .setNegativeButton("Cancel", ctx.mainExecutor) { _, _ ->
            statusText = "Fingerprint cancelled. Tap to retry."
          }
          .build()
        prompt.authenticate(
          CancellationSignal(),
          ctx.mainExecutor,
          object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
              onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
              statusText = errString?.toString() ?: "Biometric unavailable; tap fallback unlock."
            }
          },
        )
      } catch (_: Exception) {
        onUnlocked()
      }
    } else {
      onUnlocked()
    }
  }

  LaunchedEffect(Unit) { triggerBiometric(context) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(palette.background)
      .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = palette.green.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, palette.green),
        onClick = { triggerBiometric(context) },
        modifier = Modifier.size(86.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(Icons.Rounded.Fingerprint, contentDescription = "Unlock with fingerprint", tint = palette.green, modifier = Modifier.size(44.dp))
        }
      }
      Text(displayName, color = palette.textPrimary, fontFamily = CinzelFontFamily, fontSize = 18.sp, fontWeight = FontWeight.Bold)
      Text(statusText, color = palette.textMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
      SarimSecondaryButton(text = "Unlock Session", onClick = onUnlocked, testTag = "fingerprint_fallback_unlock")
    }
  }
}
