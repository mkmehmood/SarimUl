package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.ui.theme.LocalSarimPalette
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * Port of web `#photo-capture-modal` (`utilities-core.js`):
 * Custom CameraX capture dialog with 3x3 composition grid toggle, tap-to-focus reticle,
 * pinch + preset zoom stops (1x, 2x, 3x, 5x, 8x), torch toggle, front/back lens flip,
 * and quick gallery picker fallback.
 */
@Composable
fun InAppCameraOverlay(
  title: String = "Capture Photo",
  onDismiss: () -> Unit,
  onCaptured: (String) -> Unit,
  onOpenGallery: () -> Unit,
  onError: (String) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val palette = LocalSarimPalette.current

  var useFrontCamera by remember { mutableStateOf(false) }
  var gridEnabled by remember { mutableStateOf(true) }
  var torchEnabled by remember { mutableStateOf(false) }
  var hasFlashUnit by remember { mutableStateOf(false) }
  var minZoom by remember { mutableFloatStateOf(1f) }
  var maxZoom by remember { mutableFloatStateOf(4f) }
  var currentZoom by remember { mutableFloatStateOf(1f) }
  var focusOffset by remember { mutableStateOf<Offset?>(null) }
  var focusNonce by remember { mutableIntStateOf(0) }
  var showShutterFlash by remember { mutableStateOf(false) }
  var capturing by remember { mutableStateOf(false) }
  var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
  var boundCamera by remember { mutableStateOf<Camera?>(null) }
  var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }

  LaunchedEffect(focusNonce) {
    if (focusNonce > 0) {
      delay(900)
      focusOffset = null
    }
  }

  LaunchedEffect(showShutterFlash) {
    if (showShutterFlash) {
      delay(120)
      showShutterFlash = false
    }
  }

  DisposableEffect(lifecycleOwner, useFrontCamera, previewViewRef) {
    val pv = previewViewRef
    if (pv == null) {
      onDispose {}
    } else {
      val future = ProcessCameraProvider.getInstance(context)
      var providerRef: ProcessCameraProvider? = null
      val listener = Runnable {
        runCatching {
          val provider = future.get()
          providerRef = provider
          val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
          val preview = Preview.Builder().build().also {
            it.surfaceProvider = pv.surfaceProvider
          }
          val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
          provider.unbindAll()
          val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
          boundCamera = camera
          imageCaptureRef = capture
          hasFlashUnit = camera.cameraInfo.hasFlashUnit()
          torchEnabled = false
          val zs = camera.cameraInfo.zoomState.value
          minZoom = (zs?.minZoomRatio ?: 1f).coerceAtLeast(1f)
          maxZoom = (zs?.maxZoomRatio ?: 4f).coerceIn(minZoom, 10f)
          currentZoom = minZoom
          camera.cameraControl.setZoomRatio(currentZoom)
        }.onFailure { err ->
          onError(err.localizedMessage ?: "Camera unavailable on this device")
        }
      }
      future.addListener(listener, ContextCompat.getMainExecutor(context))
      onDispose {
        runCatching {
          boundCamera?.cameraControl?.enableTorch(false)
          providerRef?.unbindAll()
        }
        boundCamera = null
        imageCaptureRef = null
      }
    }
  }

  fun applyZoom(ratio: Float) {
    val clamped = ratio.coerceIn(minZoom, maxZoom)
    currentZoom = clamped
    boundCamera?.cameraControl?.setZoomRatio(clamped)
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black),
    ) {
      AndroidView(
        factory = { ctx ->
          PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            previewViewRef = this
          }
        },
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
              if (zoom != 1f) {
                applyZoom(currentZoom * zoom)
              }
            }
          }
          .pointerInput(boundCamera, previewViewRef) {
            detectTapGestures { offset ->
              val pv = previewViewRef ?: return@detectTapGestures
              val cam = boundCamera ?: return@detectTapGestures
              focusOffset = offset
              focusNonce++
              runCatching {
                val point = pv.meteringPointFactory.createPoint(offset.x, offset.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                  .setAutoCancelDuration(3, TimeUnit.SECONDS)
                  .build()
                cam.cameraControl.startFocusAndMetering(action)
              }
            }
          },
      )

      // 3x3 Rule-of-Thirds Grid Overlay
      if (gridEnabled) {
        Canvas(
          modifier = Modifier
            .fillMaxSize()
            .testTag("camera_grid_overlay"),
        ) {
          val lineColor = Color.White.copy(alpha = 0.28f)
          val stroke = 1.dp.toPx()
          for (i in 1..2) {
            val x = size.width * (i / 3f)
            val y = size.height * (i / 3f)
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
          }
        }
      }

      // Tap-to-Focus Reticle
      focusOffset?.let { pos ->
        Canvas(
          modifier = Modifier
            .fillMaxSize()
            .testTag("camera_focus_reticle"),
        ) {
          val radius = 30.dp.toPx()
          drawCircle(
            color = palette.amber,
            radius = radius,
            center = pos,
            style = Stroke(width = 2.dp.toPx()),
          )
          drawCircle(
            color = palette.amber.copy(alpha = 0.35f),
            radius = 5.dp.toPx(),
            center = pos,
          )
        }
      }

      // Shutter flash feedback
      AnimatedVisibility(
        visible = showShutterFlash,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
      ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.65f)))
      }

      // Top Toolbar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          IconButton(
            onClick = onDismiss,
            modifier = Modifier
              .size(48.dp)
              .background(Color.Black.copy(alpha = 0.55f), CircleShape)
              .testTag("camera_close_btn"),
          ) {
            Icon(Icons.Rounded.Close, contentDescription = "Close camera", tint = Color.White)
          }
          Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.55f)) {
            Text(
              title,
              color = Color.White,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = { gridEnabled = !gridEnabled },
            modifier = Modifier
              .size(48.dp)
              .background(
                if (gridEnabled) palette.green.copy(alpha = 0.80f) else Color.Black.copy(alpha = 0.55f),
                CircleShape,
              )
              .testTag("camera_grid_btn"),
          ) {
            Icon(
              if (gridEnabled) Icons.Rounded.GridOn else Icons.Rounded.GridOff,
              contentDescription = if (gridEnabled) "Hide 3x3 grid" else "Show 3x3 grid",
              tint = Color.White,
            )
          }
          if (hasFlashUnit && !useFrontCamera) {
            IconButton(
              onClick = {
                val next = !torchEnabled
                torchEnabled = next
                boundCamera?.cameraControl?.enableTorch(next)
              },
              modifier = Modifier
                .size(48.dp)
                .background(
                  if (torchEnabled) palette.amber.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
                  CircleShape,
                )
                .testTag("camera_torch_btn"),
            ) {
              Icon(
                if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                contentDescription = if (torchEnabled) "Turn flashlight off" else "Turn flashlight on",
                tint = Color.White,
              )
            }
          }
          IconButton(
            onClick = {
              torchEnabled = false
              useFrontCamera = !useFrontCamera
            },
            modifier = Modifier
              .size(48.dp)
              .background(Color.Black.copy(alpha = 0.55f), CircleShape)
              .testTag("camera_flip_btn"),
          ) {
            Icon(Icons.Rounded.Cameraswitch, contentDescription = "Switch front/back camera", tint = Color.White)
          }
        }
      }

      // Bottom Controls: Zoom Presets + Shutter + Gallery
      Column(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        val stops = remember(minZoom, maxZoom) {
          listOf(1f, 2f, 3f, 5f, 8f).filter { it in minZoom..maxZoom }.ifEmpty { listOf(minZoom) }
        }
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.65f)) {
            Text(
              String.format(Locale.US, "%.1f×", currentZoom),
              color = palette.amber,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
          }
          stops.forEach { stop ->
            val active = kotlin.math.abs(currentZoom - stop) < 0.25f
            Surface(
              shape = RoundedCornerShape(50),
              color = if (active) palette.green else Color.Black.copy(alpha = 0.55f),
              modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(50))
                .clickable { applyZoom(stop) }
                .testTag("camera_zoom_${stop.toInt()}"),
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                  "${stop.toInt()}×",
                  color = Color.White,
                  fontSize = 12.5.sp,
                  fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                )
              }
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(
            onClick = {
              onDismiss()
              onOpenGallery()
            },
            modifier = Modifier
              .size(52.dp)
              .background(Color.Black.copy(alpha = 0.55f), CircleShape)
              .testTag("camera_gallery_btn"),
          ) {
            Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Choose photo from gallery", tint = Color.White)
          }

          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
              .size(76.dp)
              .clip(CircleShape)
              .border(4.dp, Color.White, CircleShape)
              .padding(6.dp)
              .clip(CircleShape)
              .background(if (capturing) palette.amber else Color.White)
              .clickable(enabled = !capturing) {
                val ic = imageCaptureRef ?: return@clickable
                capturing = true
                showShutterFlash = true
                val outFile = createCameraTempFile(context)
                val outOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()
                ic.takePicture(
                  outOptions,
                  ContextCompat.getMainExecutor(context),
                  object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                      capturing = false
                      val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
                      onCaptured(uri.toString())
                    }

                    override fun onError(exception: ImageCaptureException) {
                      capturing = false
                      onError(exception.localizedMessage ?: "Failed to capture photo")
                    }
                  },
                )
              }
              .testTag("camera_shutter_btn"),
          ) {
            if (capturing) {
              CircularProgressIndicator(
                color = Color.Black,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(24.dp),
              )
            }
          }

          Spacer(modifier = Modifier.size(52.dp))
        }
      }
    }
  }
}

private fun createCameraTempFile(context: Context): File {
  val dir = File(context.cacheDir, "photos").apply { mkdirs() }
  return File(dir, "capture_${System.currentTimeMillis()}.jpg")
}
