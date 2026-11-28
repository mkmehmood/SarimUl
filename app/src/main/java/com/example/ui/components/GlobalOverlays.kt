package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.util.AppLockGuard
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
  var showInAppCamera by remember { mutableStateOf(false) }
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

  val launchGalleryRef = remember { mutableStateOf<() -> Unit>({}) }
  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      showInAppCamera = true
    } else {
      reportError("Camera permission denied. Opening your photo library instead.")
      launchGalleryRef.value()
    }
  }

  val launchGallery: () -> Unit = {
    runCatching {
      AppLockGuard.suppressFor()
      galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }.onFailure { err ->
      reportError(err.localizedMessage ?: "Photo picker unavailable on this device")
    }
  }

  launchGalleryRef.value = launchGallery
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = if (photoUris.isNotEmpty()) androidx.compose.ui.graphics.lerp(scheme.surfaceContainerHigh, palette.green, 0.16f) else scheme.surfaceContainerHigh,
      modifier = Modifier
        .defaultMinSize(minHeight = 48.dp)
        .clip(RoundedCornerShape(20.dp))
        .testTag("photo_camera_btn")
        .clickable {
          val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
          if (granted) {
            showInAppCamera = true
          } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
          }
        },
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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

    if (photoUris.isNotEmpty()) {
      TextButton(
        onClick = { onViewPhotos(0) },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("photo_view_btn"),
      ) {
        Text("View", color = palette.green, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
      }
      IconButton(onClick = onClearAll, modifier = Modifier.size(48.dp).testTag("photo_clear_btn")) {
        Icon(Icons.Rounded.Close, contentDescription = "Remove attached photos", tint = palette.red, modifier = Modifier.size(18.dp))
      }
    }
  }

  if (showInAppCamera) {
    InAppCameraOverlay(
      onDismiss = { showInAppCamera = false },
      onCaptured = { uriStr ->
        showInAppCamera = false
        onAddUris(listOf(uriStr))
      },
      onOpenGallery = launchGallery,
      onError = { msg ->
        showInAppCamera = false
        reportError(msg)
      },
    )
  }
}
