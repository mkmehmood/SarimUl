package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object LocationHelper {
  const val GPS_CLUSTER_RADIUS_METERS = 150.0
  private const val EARTH_RADIUS_METERS = 6_371_000.0
  private const val MAX_CACHED_FIX_AGE_MS = 5 * 60 * 1000L

  /** Exact port of web `getDistanceFromLatLonInMeters` using Earth radius 6,371,000 m. */
  fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
      cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
      sin(dLon / 2.0) * sin(dLon / 2.0)
    val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    return EARTH_RADIUS_METERS * c
  }

  fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  @SuppressLint("MissingPermission")
  suspend fun getCurrentLocationOrNull(context: Context, timeoutMs: Long = 10_000L): Pair<Double, Double>? {
    if (!hasLocationPermission(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

    val now = System.currentTimeMillis()
    val providers = buildList {
      if (runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
        add(LocationManager.GPS_PROVIDER)
      }
      if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
        add(LocationManager.NETWORK_PROVIDER)
      }
      if (isEmpty()) add(LocationManager.PASSIVE_PROVIDER)
    }

    val recentKnown = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
      .filter { now - it.time <= MAX_CACHED_FIX_AGE_MS }
      .maxByOrNull { it.time }

    val fresh = withTimeoutOrNull(timeoutMs) {
      val primary = providers.firstOrNull() ?: return@withTimeoutOrNull null
      requestSingleFix(lm, primary)
        ?: providers.drop(1).firstNotNullOfOrNull { p -> requestSingleFix(lm, p) }
    }

    val chosen = fresh ?: recentKnown ?: providers.mapNotNull { p ->
      runCatching { lm.getLastKnownLocation(p) }.getOrNull()
    }.maxByOrNull { it.time }

    return chosen?.let { it.latitude to it.longitude }
  }

  @SuppressLint("MissingPermission")
  @Suppress("DEPRECATION")
  private suspend fun requestSingleFix(lm: LocationManager, provider: String): Location? =
    suspendCancellableCoroutine { cont ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val cancelSignal = android.os.CancellationSignal()
        cont.invokeOnCancellation { cancelSignal.cancel() }
        runCatching {
          lm.getCurrentLocation(
            provider,
            cancelSignal,
            { runnable -> Looper.getMainLooper().let { android.os.Handler(it).post(runnable) } },
          ) { loc ->
            if (cont.isActive) cont.resume(loc)
          }
        }.onFailure {
          if (cont.isActive) cont.resume(null)
        }
      } else {
        val listener = object : LocationListener {
          override fun onLocationChanged(location: Location) {
            runCatching { lm.removeUpdates(this) }
            if (cont.isActive) cont.resume(location)
          }
          override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
          override fun onProviderEnabled(provider: String) {}
          override fun onProviderDisabled(provider: String) {}
        }
        cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        runCatching {
          lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.onFailure {
          if (cont.isActive) cont.resume(null)
        }
      }
    }
}
