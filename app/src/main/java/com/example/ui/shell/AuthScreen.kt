package com.example.ui.shell

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Patterns
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
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
import com.example.ui.theme.CinzelFontFamily
import com.example.ui.theme.LocalSarimPalette
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
  savedEmail: String,
  syncManager: FirebaseSyncManager,
  onGoogleAuthSuccess: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val palette = LocalSarimPalette.current
  val credentialManager = remember(context) { CredentialManager.create(context) }

  var email by remember(savedEmail) { mutableStateOf(savedEmail) }
  var password by remember { mutableStateOf("") }
  var passwordVisible by remember { mutableStateOf(false) }
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

  fun validateEmailAndPassword(): Boolean {
    val cleanEmail = email.trim()
    if (cleanEmail.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
      errorMessage = "Enter a valid email address."
      return false
    }
    if (password.length < 6) {
      errorMessage = "Password must be at least 6 characters."
      return false
    }
    return true
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(palette.background),
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      drawCircle(
        brush = Brush.radialGradient(
          listOf(palette.green.copy(alpha = 0.22f), Color.Transparent),
          center = Offset(size.width * (0.2f + orbShift * 0.25f), size.height * 0.16f),
          radius = size.width * 0.6f,
        ),
        center = Offset(size.width * (0.2f + orbShift * 0.25f), size.height * 0.16f),
        radius = size.width * 0.6f,
      )
      drawCircle(
        brush = Brush.radialGradient(
          listOf(palette.cyan.copy(alpha = 0.15f), Color.Transparent),
          center = Offset(size.width * (0.8f - orbShift * 0.2f), size.height * 0.84f),
          radius = size.width * 0.55f,
        ),
        center = Offset(size.width * (0.8f - orbShift * 0.2f), size.height * 0.84f),
        radius = size.width * 0.55f,
      )
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .systemBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp, vertical = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      // Brand Emblem & Header above login card (matches web auth layout)
      Surface(
        modifier = Modifier.size(84.dp),
        shape = RoundedCornerShape(24.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, palette.green.copy(alpha = 0.35f)),
      ) {
        Image(
          painter = painterResource(id = R.drawable.brand_logo),
          contentDescription = "Gull and Zubair logo",
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      }

      Spacer(Modifier.height(14.dp))

      Text(
        text = "GULL AND ZUBAIR",
        color = palette.textPrimary,
        fontFamily = CinzelFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        letterSpacing = 2.sp,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = "NASWAR DEALERS",
        color = palette.green,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.2.sp,
      )

      Spacer(Modifier.height(22.dp))

      Surface(
        modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = palette.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, palette.border),
      ) {
        Column(
          modifier = Modifier.padding(22.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = "Welcome back",
              color = palette.textPrimary,
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = "Sign in to unlock and sync your business data.",
              color = palette.textMuted,
              fontSize = 13.sp,
            )
          }

          SarimTextField(
            value = email,
            onValueChange = { email = it },
            label = "Email address",
            placeholder = "name@example.com",
            leadingIcon = Icons.Rounded.Email,
            keyboardType = KeyboardType.Email,
            testTag = "auth_email",
          )

          SarimTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            placeholder = "Enter your password",
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

          SarimPrimaryButton(
            text = if (isLoading) "Signing in..." else "Sign in",
            enabled = !isLoading,
            testTag = "email_auth_submit_btn",
            onClick = {
              errorMessage = ""
              infoMessage = ""
              if (!validateEmailAndPassword()) return@SarimPrimaryButton
              isLoading = true
              scope.launch {
                val res = syncManager.signInWithEmailPassword(email, password)
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
            text = "Continue with Google",
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
        }
      }

      Spacer(Modifier.height(18.dp))

      Text(
        text = "No account? Contact the administrator to have yours added.",
        color = palette.textMuted,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 12.dp),
      )
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
