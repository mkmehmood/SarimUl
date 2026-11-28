package com.example.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.SaleTransactionEntity
import com.example.ui.theme.LocalSarimPalette
import com.example.util.SarimFormat
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object OsmTileMemoryCache {
  val cache = object : LruCache<String, ImageBitmap>(72) {}
}

/**
 * Interactive OpenStreetMap slippy-tile street map for Rep Admin View (port of web Leaflet map
 * in `rep-sales.js`): pan/pinch zoom, +/-/Fit controls, dashed route polyline sorted by time,
 * color-coded pins with tap popups, © OpenStreetMap attribution, and automatic coordinate-grid
 * fallback if offline.
 */
@Composable
fun RepOsmMapCard(
  mappedPins: List<SaleTransactionEntity>,
  dayPinsCount: Int,
  selectedDateEpoch: Long,
  selectedPinId: String?,
  hideMoney: Boolean,
  onSelectPin: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val palette = LocalSarimPalette.current
  val density = LocalDensity.current
  val tileSizePx = remember(density) { (256f * density.density.coerceIn(1.35f, 2.25f)) }

  var zoomLevel by remember { mutableIntStateOf(13) }
  var centerLat by remember { mutableDoubleStateOf(32.9910) }
  var centerLng by remember { mutableDoubleStateOf(70.6055) }
  var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
  var showPopup by remember { mutableStateOf(false) }

  var loadedTileRevision by remember { mutableIntStateOf(0) }
  var tileLoadSuccessCount by remember { mutableIntStateOf(0) }
  var tileLoadErrorCount by remember { mutableIntStateOf(0) }
  val inFlightTiles = remember { mutableStateMapOf<String, Boolean>() }

  fun fitToPins() {
    if (mappedPins.isEmpty()) {
      centerLat = 32.9910
      centerLng = 70.6055
      zoomLevel = 13
      return
    }
    val lats = mappedPins.mapNotNull { it.latitude }
    val lngs = mappedPins.mapNotNull { it.longitude }
    val minLat = lats.minOrNull() ?: 32.9910
    val maxLat = lats.maxOrNull() ?: 32.9910
    val minLng = lngs.minOrNull() ?: 70.6055
    val maxLng = lngs.maxOrNull() ?: 70.6055
    centerLat = (minLat + maxLat) / 2.0
    centerLng = (minLng + maxLng) / 2.0
    val span = maxOf(maxLat - minLat, maxLng - minLng)
    zoomLevel = when {
      mappedPins.size == 1 -> 15
      span < 0.005 -> 16
      span < 0.015 -> 15
      span < 0.04 -> 14
      span < 0.10 -> 13
      span < 0.25 -> 12
      span < 0.60 -> 10
      else -> 8
    }
  }

  LaunchedEffect(mappedPins) {
    fitToPins()
  }

  val nTiles = remember(zoomLevel) { 2.0.pow(zoomLevel) }
  val centerTileX = remember(centerLng, nTiles) { lngToTileX(centerLng, nTiles) }
  val centerTileY = remember(centerLat, nTiles) { latToTileY(centerLat, nTiles) }

  // Determine visible tile coordinates & fetch missing ones
  val visibleTiles = remember(zoomLevel, centerTileX, centerTileY, canvasSize, tileSizePx) {
    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
    val h = canvasSize.height.toFloat().coerceAtLeast(1f)
    val halfTilesX = (w / 2f) / tileSizePx
    val halfTilesY = (h / 2f) / tileSizePx
    val minTx = floor(centerTileX - halfTilesX).toInt()
    val maxTx = ceil(centerTileX + halfTilesX).toInt()
    val minTy = floor(centerTileY - halfTilesY).toInt()
    val maxTy = ceil(centerTileY + halfTilesY).toInt()
    val maxIdx = nTiles.toInt()
    buildList {
      for (ty in minTy..maxTy) {
        if (ty !in 0 until maxIdx) continue
        for (tx in minTx..maxTx) {
          val wrappedX = ((tx % maxIdx) + maxIdx) % maxIdx
          add(Triple(zoomLevel, tx, wrappedX to ty))
        }
      }
    }
  }

  LaunchedEffect(visibleTiles) {
    for ((z, _, wrappedAndY) in visibleTiles) {
      val (txWrapped, ty) = wrappedAndY
      val key = "$z/$txWrapped/$ty"
      if (OsmTileMemoryCache.cache.get(key) == null && inFlightTiles[key] != true) {
        inFlightTiles[key] = true
        val loaded = fetchOsmTile(z, txWrapped, ty)
        inFlightTiles.remove(key)
        if (loaded != null) {
          OsmTileMemoryCache.cache.put(key, loaded)
          tileLoadSuccessCount++
          loadedTileRevision++
        } else {
          tileLoadErrorCount++
        }
      }
    }
  }

  fun projectToCanvas(lat: Double, lng: Double, width: Float, height: Float): Offset {
    val n = 2.0.pow(zoomLevel)
    val tx = lngToTileX(lng, n)
    val ty = latToTileY(lat, n)
    val px = (width / 2f) + ((tx - centerTileX) * tileSizePx).toFloat()
    val py = (height / 2f) + ((ty - centerTileY) * tileSizePx).toFloat()
    return Offset(px, py)
  }

  val selectedPin = remember(mappedPins, selectedPinId) {
    mappedPins.firstOrNull { it.id == selectedPinId } ?: mappedPins.lastOrNull()
  }
  val tilesOffline = tileLoadSuccessCount == 0 && tileLoadErrorCount >= 2

  Surface(
    shape = RoundedCornerShape(24.dp),
    color = lerp(MaterialTheme.colorScheme.surfaceContainerLowest, Color(0xFF0A2218), 0.90f),
    modifier = modifier.fillMaxWidth(),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(248.dp)
        .clip(RoundedCornerShape(24.dp))
        .clipToBounds()
        .onSizeChanged { canvasSize = it },
    ) {
      @Suppress("UNUSED_EXPRESSION")
      loadedTileRevision

      Canvas(
        modifier = Modifier
          .fillMaxSize()
          .testTag("rep_route_map_canvas")
          .pointerInput(zoomLevel, centerTileX, centerTileY, tileSizePx) {
            detectTransformGestures { _, pan, zoom, _ ->
              if (pan != Offset.Zero) {
                val nextCx = (centerTileX - pan.x / tileSizePx).coerceIn(0.0, nTiles)
                val nextCy = (centerTileY - pan.y / tileSizePx).coerceIn(0.0, nTiles)
                centerLng = tileXToLng(nextCx, nTiles)
                centerLat = tileYToLat(nextCy, nTiles).coerceIn(-85.0, 85.0)
              }
              if (zoom > 1.18f && zoomLevel < 18) {
                zoomLevel = (zoomLevel + 1).coerceAtMost(18)
              } else if (zoom < 0.84f && zoomLevel > 4) {
                zoomLevel = (zoomLevel - 1).coerceAtLeast(4)
              }
            }
          }
          .pointerInput(mappedPins, zoomLevel, centerTileX, centerTileY) {
            detectTapGestures { tap ->
              if (mappedPins.isEmpty()) return@detectTapGestures
              val hit = mappedPins.minByOrNull { pin ->
                val lat = pin.latitude ?: return@minByOrNull Double.MAX_VALUE
                val lng = pin.longitude ?: return@minByOrNull Double.MAX_VALUE
                val p = projectToCanvas(lat, lng, size.width.toFloat(), size.height.toFloat())
                hypot((tap.x - p.x).toDouble(), (tap.y - p.y).toDouble())
              }
              if (hit != null && hit.latitude != null && hit.longitude != null) {
                val p = projectToCanvas(hit.latitude, hit.longitude, size.width.toFloat(), size.height.toFloat())
                val dist = hypot((tap.x - p.x).toDouble(), (tap.y - p.y).toDouble())
                if (dist <= 44.dp.toPx()) {
                  onSelectPin(hit.id)
                  showPopup = true
                } else {
                  showPopup = false
                }
              }
            }
          },
      ) {
        val w = size.width
        val h = size.height

        // Draw street map tiles or coordinate grid fallback
        var drewAnyTile = false
        for ((z, txRaw, wrappedAndY) in visibleTiles) {
          val (txWrapped, ty) = wrappedAndY
          val img = OsmTileMemoryCache.cache.get("$z/$txWrapped/$ty") ?: continue
          drewAnyTile = true
          val left = (w / 2f + ((txRaw - centerTileX) * tileSizePx)).roundToInt()
          val top = (h / 2f + ((ty - centerTileY) * tileSizePx)).roundToInt()
          val tileDrawSize = ceil(tileSizePx).toInt()
          drawImage(
            image = img,
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(tileDrawSize, tileDrawSize),
          )
        }

        if (!drewAnyTile) {
          for (i in 1..4) {
            val y = h * (i / 5f)
            drawLine(palette.green.copy(alpha = 0.14f), Offset(0f, y), Offset(w, y))
            val x = w * (i / 5f)
            drawLine(palette.green.copy(alpha = 0.14f), Offset(x, 0f), Offset(x, h))
          }
        }

        // Dashed chronological route polyline
        if (mappedPins.size >= 2) {
          val pts = mappedPins.mapNotNull { s ->
            val lat = s.latitude ?: return@mapNotNull null
            val lng = s.longitude ?: return@mapNotNull null
            projectToCanvas(lat, lng, w, h)
          }
          for (i in 0 until pts.lastIndex) {
            drawLine(
              color = Color(0xFF0284C7).copy(alpha = 0.78f),
              start = pts[i],
              end = pts[i + 1],
              strokeWidth = 3.dp.toPx(),
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
          }
        }

        // Circle markers with white stroke
        mappedPins.forEach { s ->
          val lat = s.latitude ?: return@forEach
          val lng = s.longitude ?: return@forEach
          val pos = projectToCanvas(lat, lng, w, h)
          val pinColor = when {
            s.mode == "COLLECTION" -> palette.cyan
            s.paymentType == "CASH" -> palette.green
            else -> palette.amber
          }
          if (selectedPin?.id == s.id) {
            drawCircle(Color(0xFF0F172A).copy(alpha = 0.45f), radius = 14.dp.toPx(), center = pos)
          }
          drawCircle(Color.White, radius = 10.dp.toPx(), center = pos)
          drawCircle(pinColor, radius = 8.dp.toPx(), center = pos)
        }
      }

      if (mappedPins.isEmpty()) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = Color.Black.copy(alpha = 0.65f),
          modifier = Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
        ) {
          Text(
            if (dayPinsCount == 0) "No field visits recorded on ${SarimFormat.formatDate(selectedDateEpoch)}"
            else "$dayPinsCount visit(s) on ${SarimFormat.formatDate(selectedDateEpoch)}, none with GPS coordinates",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
          )
        }
      }

      if (tilesOffline) {
        Surface(
          shape = RoundedCornerShape(50),
          color = Color.Black.copy(alpha = 0.70f),
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(10.dp),
        ) {
          Text(
            "Offline coordinate view",
            color = palette.amber,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      // Interactive Zoom +/- & Fit Controls
      Column(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        IconButton(
          onClick = { zoomLevel = (zoomLevel + 1).coerceAtMost(18) },
          modifier = Modifier
            .size(48.dp)
            .background(Color(0xFF0F172A).copy(alpha = 0.82f), CircleShape)
            .testTag("osm_zoom_in_btn"),
        ) {
          Icon(Icons.Rounded.Add, contentDescription = "Zoom in map", tint = Color.White)
        }
        IconButton(
          onClick = { zoomLevel = (zoomLevel - 1).coerceAtLeast(4) },
          modifier = Modifier
            .size(48.dp)
            .background(Color(0xFF0F172A).copy(alpha = 0.82f), CircleShape)
            .testTag("osm_zoom_out_btn"),
        ) {
          Icon(Icons.Rounded.Remove, contentDescription = "Zoom out map", tint = Color.White)
        }
        IconButton(
          onClick = { fitToPins() },
          modifier = Modifier
            .size(48.dp)
            .background(Color(0xFF0F172A).copy(alpha = 0.82f), CircleShape)
            .testTag("osm_fit_btn"),
        ) {
          Icon(Icons.Rounded.FitScreen, contentDescription = "Fit route bounds", tint = palette.cyan)
        }
      }

      // Tapped Pin Popup Overlay
      if (showPopup && selectedPin != null) {
        val dispVal = if (selectedPin.mode == "COLLECTION") {
          selectedPin.amountReceived.takeIf { it > 0.0 } ?: selectedPin.totalValue
        } else {
          selectedPin.totalValue
        }
        val typeText = when {
          selectedPin.mode == "COLLECTION" -> "Collection"
          selectedPin.paymentType == "CASH" -> "Cash Sale"
          else -> "Credit Sale"
        }
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = Color(0xFF0F172A).copy(alpha = 0.94f),
          tonalElevation = 4.dp,
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 10.dp, bottom = 10.dp, end = 72.dp)
            .testTag("osm_pin_popup"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(selectedPin.customerName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
              Text(
                "$typeText · ${if (selectedPin.mode == "SALE") SarimFormat.formatKg(selectedPin.quantityKg) else ""} ${SarimFormat.formatRs(dispVal, false, hideMoney)}".trim(),
                color = palette.green,
                fontSize = 11.5.sp,
              )
              if (selectedPin.timeStr.isNotBlank()) {
                Text(selectedPin.timeStr, color = Color(0xFF94A3B8), fontSize = 10.5.sp)
              }
            }
            IconButton(
              onClick = { showPopup = false },
              modifier = Modifier.size(48.dp),
            ) {
              Icon(Icons.Rounded.Close, contentDescription = "Close map popup", tint = Color.White, modifier = Modifier.size(16.dp))
            }
          }
        }
      }

      // OpenStreetMap Attribution
      Surface(
        shape = RoundedCornerShape(topStart = 10.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.72f),
        modifier = Modifier.align(Alignment.BottomEnd),
      ) {
        Text(
          "© OpenStreetMap contributors",
          color = Color(0xFFE2E8F0),
          fontSize = 9.5.sp,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
      }
    }
  }
}

private fun lngToTileX(lng: Double, n: Double): Double = ((lng + 180.0) / 360.0) * n

private fun tileXToLng(x: Double, n: Double): Double = (x / n) * 360.0 - 180.0

private fun latToTileY(lat: Double, n: Double): Double {
  val rad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
  return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
}

private fun tileYToLat(y: Double, n: Double): Double {
  val k = PI * (1.0 - 2.0 * y / n)
  return Math.toDegrees(kotlin.math.atan(sinh(k)))
}

private suspend fun fetchOsmTile(z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
  runCatching {
    val conn = (URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection).apply {
      connectTimeout = 5_000
      readTimeout = 6_000
      setRequestProperty("User-Agent", "SarimDealersApp/1.0 (https://sarim.app)")
    }
    try {
      if (conn.responseCode in 200..299) {
        conn.inputStream.use { stream ->
          BitmapFactory.decodeStream(stream)?.asImageBitmap()
        }
      } else null
    } finally {
      conn.disconnect()
    }
  }.getOrNull()
}
