package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.DialogTone
import com.example.util.inferDialogTone
import java.io.File

@Composable
fun GlassAlertDialog(
  request: AppDialogRequest,
  onDismiss: () -> Unit,
) {
  val palette = LocalSarimPalette.current
  val tone = request.tone ?: inferDialogTone(request.title, request.body)
  val toneColor = when (tone) {
    DialogTone.PERMANENT, DialogTone.ERROR -> palette.red
    DialogTone.WARNING -> palette.amber
    DialogTone.SUCCESS, DialogTone.CONFIRM -> palette.green
  }
  val toneIcon = when (tone) {
    DialogTone.PERMANENT -> Icons.Rounded.DeleteForever
    DialogTone.WARNING -> Icons.Rounded.Warning
    DialogTone.ERROR -> Icons.Rounded.ErrorOutline
    DialogTone.SUCCESS -> Icons.Rounded.CheckCircle
    DialogTone.CONFIRM -> Icons.AutoMirrored.Rounded.HelpOutline
  }

  var passwordText by remember { mutableStateOf("") }
  var showPassword by remember { mutableStateOf(false) }

  Dialog(
    onDismissRequest = {
      request.onCancel()
      onDismiss()
    },
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(0.9f)
        .widthIn(max = 460.dp),
      shape = RoundedCornerShape(32.dp),
      color = palette.surface.copy(alpha = 0.96f),
      border = BorderStroke(1.dp, toneColor.copy(alpha = 0.45f)),
      tonalElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Tone chip
        Surface(
          shape = RoundedCornerShape(50),
          color = toneColor.copy(alpha = 0.16f),
          border = BorderStroke(1.dp, toneColor.copy(alpha = 0.4f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Icon(toneIcon, contentDescription = null, tint = toneColor, modifier = Modifier.size(14.dp))
            Text(tone.label, color = toneColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
        }

        // Large round icon
        Box(
          modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(toneColor.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(toneIcon, contentDescription = null, tint = toneColor, modifier = Modifier.size(28.dp))
        }

        Text(
          text = request.title,
          color = palette.textPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
        )

        // Body lines parsed as facts (Label: value), bullets (• ...), or paragraphs
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          request.body.lines().filter { it.isNotBlank() }.forEach { line ->
            val trimmed = line.trim()
            when {
              trimmed.startsWith("•") || trimmed.startsWith("- ") -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("•", color = toneColor, fontWeight = FontWeight.Bold)
                  Text(trimmed.removePrefix("•").removePrefix("-").trim(), color = palette.textMuted, fontSize = 13.sp)
                }
              }
              trimmed.contains(": ") && trimmed.substringBefore(": ").length <= 28 -> {
                val k = trimmed.substringBefore(": ")
                val v = trimmed.substringAfter(": ")
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(k, color = palette.textMuted, fontSize = 12.5.sp)
                  Text(v, color = palette.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
              }
              else -> {
                Text(trimmed, color = palette.textMuted, fontSize = 13.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
              }
            }
          }
        }

        request.highlight?.let { hl ->
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = toneColor.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, toneColor.copy(alpha = 0.35f)),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(hl.label.uppercase(), color = palette.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
              Text(
                hl.value,
                color = toneColor,
                style = TabularNumberStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
              )
            }
          }
        }

        if (request.choices.isNotEmpty()) {
          Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            request.choices.forEach { choice ->
              Surface(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(16.dp))
                  .clickable {
                    request.onChoiceSelected(choice.id)
                    onDismiss()
                  },
                shape = RoundedCornerShape(16.dp),
                color = palette.surfaceElevated,
                border = BorderStroke(1.dp, palette.border),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(choice.label, color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (!choice.subtitle.isNullOrBlank()) {
                      Text(choice.subtitle, color = palette.textMuted, fontSize = 11.5.sp)
                    }
                  }
                  Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(18.dp))
                }
              }
            }
          }
        }

        if (request.requirePassword) {
          SarimTextField(
            value = passwordText,
            onValueChange = { passwordText = it },
            label = "Password",
            placeholder = request.passwordHint,
            isPassword = true,
            passwordVisible = showPassword,
            trailingContent = {
              IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                  if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                  contentDescription = "Toggle password visibility",
                  tint = palette.textMuted,
                )
              }
            },
            testTag = "dialog_password_input",
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (request.cancelLabel != null) {
            SarimSecondaryButton(
              text = request.cancelLabel,
              onClick = {
                request.onCancel()
                onDismiss()
              },
              modifier = Modifier.weight(1f),
              testTag = "dialog_cancel_btn",
            )
          }
          if (request.choices.isEmpty()) {
            Surface(
              modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(50))
                .testTag("dialog_confirm_btn")
                .clickable {
                  request.onConfirm(passwordText)
                  onDismiss()
                },
              shape = RoundedCornerShape(50),
              color = toneColor,
            ) {
              Box(contentAlignment = Alignment.Center) {
                Text(
                  request.confirmLabel,
                  color = if (toneColor == palette.amber || toneColor == palette.green) Color(0xFF041A0E) else Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 14.sp,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun FullScreenPhotoViewer(
  uris: List<String>,
  initialIndex: Int,
  onClose: () -> Unit,
) {
  var currentIndex by remember(uris, initialIndex) { mutableIntStateOf(initialIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0))) }
  var scale by remember { mutableFloatStateOf(1f) }
  var offsetX by remember { mutableFloatStateOf(0f) }
  var offsetY by remember { mutableFloatStateOf(0f) }

  Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black),
    ) {
      val currentUri = uris.getOrNull(currentIndex).orEmpty()
      AsyncImage(
        model = currentUri,
        contentDescription = "Full screen photo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = offsetX,
            translationY = offsetY,
          )
          .pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
              scale = (scale * zoom).coerceIn(1f, 5f)
              if (scale > 1f) {
                offsetX += pan.x
                offsetY += pan.y
              } else {
                offsetX = 0f
                offsetY = 0f
              }
            }
          }
          .pointerInput(Unit) {
            detectTapGestures(
              onDoubleTap = {
                if (scale > 1.2f) {
                  scale = 1f; offsetX = 0f; offsetY = 0f
                } else {
                  scale = 2.5f
                }
              },
            )
          },
      )

      // Top Controls
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.65f)) {
          Text(
            "${String.format("%.1f", scale)}×",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          )
        }
        IconButton(
          onClick = onClose,
          modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), CircleShape),
        ) {
          Icon(Icons.Rounded.Close, contentDescription = "Close viewer", tint = Color.White)
        }
      }

      // Bottom Controls
      Column(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          FilledTonalButton(onClick = { scale = (scale - 0.5f).coerceAtLeast(1f) }) { Text("−") }
          FilledTonalButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) { Text("Fit") }
          FilledTonalButton(onClick = { scale = (scale + 0.5f).coerceAtMost(5f) }) { Text("+") }
        }
        if (uris.size > 1) {
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { if (currentIndex > 0) currentIndex-- }, enabled = currentIndex > 0) {
              Text("Prev", color = Color.White)
            }
            Text("${currentIndex + 1} / ${uris.size}", color = Color.White, modifier = Modifier.align(Alignment.CenterVertically))
            TextButton(onClick = { if (currentIndex < uris.lastIndex) currentIndex++ }, enabled = currentIndex < uris.lastIndex) {
              Text("Next", color = Color.White)
            }
          }
        }
        Text(
          "Pinch to zoom · double-tap to zoom in or out",
          color = Color.White.copy(alpha = 0.65f),
          fontSize = 11.5.sp,
        )
      }
    }
  }
}

fun createTempCameraUri(context: Context): Uri {
  val dir = File(context.cacheDir, "photos").apply { mkdirs() }
  val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
  return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
fun PhotoAttachmentBar(
  photoUris: List<String>,
  onAddUris: (List<String>) -> Unit,
  onClearAll: () -> Unit,
  onViewPhotos: (Int) -> Unit,
  modifier: Modifier = Modifier,
  onError: ((String) -> Unit)? = null,
) {
  val context = LocalContext.current
  val palette = LocalSarimPalette.current
  var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
  fun reportError(msg: String) {
    if (onError != null) {
      onError(msg)
    } else {
      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
  }

  val galleryLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 6),
  ) { uris ->
    if (uris.isNotEmpty()) {
      onAddUris(uris.map { it.toString() })
    }
  }

  val cameraLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture(),
  ) { success ->
    if (success) {
      pendingCameraUri?.let { onAddUris(listOf(it.toString())) }
    }
  }

  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = palette.surfaceElevated,
      border = BorderStroke(1.dp, if (photoUris.isNotEmpty()) palette.green else palette.border),
      modifier = Modifier
        .defaultMinSize(minHeight = 46.dp)
        .clip(RoundedCornerShape(16.dp))
        .clickable {
          runCatching {
            val uri = createTempCameraUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
          }.onFailure { err ->
            reportError(err.localizedMessage ?: "Camera unavailable on this device")
          }
        },
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Box {
          Icon(Icons.Rounded.PhotoCamera, contentDescription = "Take photo", tint = palette.textPrimary, modifier = Modifier.size(20.dp))
          if (photoUris.isNotEmpty()) {
            Box(
              Modifier
                .size(8.dp)
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .background(palette.green),
            )
          }
        }
        Text(
          if (photoUris.isEmpty()) "Photo" else "${photoUris.size} Photo(s)",
          color = palette.textPrimary,
          fontSize = 12.5.sp,
          fontWeight = FontWeight.Medium,
        )
      }
    }

    Surface(
      shape = RoundedCornerShape(16.dp),
      color = palette.surfaceElevated,
      border = BorderStroke(1.dp, palette.border),
      modifier = Modifier
        .defaultMinSize(minHeight = 46.dp)
        .clip(RoundedCornerShape(16.dp))
        .clickable {
          runCatching {
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
          }.onFailure { err ->
            reportError(err.localizedMessage ?: "Photo picker unavailable on this device")
          }
        },
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Pick from gallery", tint = palette.cyan, modifier = Modifier.size(18.dp))
        Text("Gallery", color = palette.textPrimary, fontSize = 12.5.sp)
      }
    }

    if (photoUris.isNotEmpty()) {
      TextButton(onClick = { onViewPhotos(0) }) {
        Text("View", color = palette.green, fontSize = 12.5.sp)
      }
      IconButton(onClick = onClearAll, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Rounded.Close, contentDescription = "Remove attached photos", tint = palette.red, modifier = Modifier.size(18.dp))
      }
    }
  }
}
