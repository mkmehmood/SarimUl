package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AppThemeMode
import com.example.data.model.HapticIntensity
import com.example.data.model.OverviewPanel
import com.example.data.model.SoundIntensity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "sarim_prefs")

data class UserPrefs(
  val themeMode: AppThemeMode = AppThemeMode.DARK,
  val hapticIntensity: HapticIntensity = HapticIntensity.MEDIUM,
  val soundIntensity: SoundIntensity = SoundIntensity.STANDARD,
  val fingerprintLockEnabled: Boolean = false,
  val savedEmail: String = "",
  val activeOverviewPanel: OverviewPanel = OverviewPanel.SALES,
  val lastSyncMillis: Long = 0L,
  val offlineModeActive: Boolean = false,
  val snippets: List<String> = emptyList(),
)

class PreferencesManager(private val context: Context) {
  private object Keys {
    val THEME = stringPreferencesKey("theme_mode")
    val HAPTIC = stringPreferencesKey("haptic_intensity")
    val SOUND = stringPreferencesKey("sound_intensity")
    val FINGERPRINT = booleanPreferencesKey("fingerprint_lock")
    val SAVED_EMAIL = stringPreferencesKey("saved_email")
    val OVERVIEW_PANEL = stringPreferencesKey("overview_panel")
    val LAST_SYNC = longPreferencesKey("last_sync_millis")
    val OFFLINE_MODE = booleanPreferencesKey("offline_mode_active")
    val SNIPPETS = stringPreferencesKey("snippets_json")
  }

  val prefsFlow: Flow<UserPrefs> = context.dataStore.data
    .catch { exception ->
      if (exception is IOException) {
        emit(emptyPreferences())
      } else {
        throw exception
      }
    }
    .map { prefs ->
      UserPrefs(
        themeMode = enumValues<AppThemeMode>().firstOrNull { it.name == prefs[Keys.THEME] } ?: AppThemeMode.DARK,
        hapticIntensity = enumValues<HapticIntensity>().firstOrNull { it.name == prefs[Keys.HAPTIC] } ?: HapticIntensity.MEDIUM,
        soundIntensity = enumValues<SoundIntensity>().firstOrNull { it.name == prefs[Keys.SOUND] } ?: SoundIntensity.STANDARD,
        fingerprintLockEnabled = prefs[Keys.FINGERPRINT] ?: false,
        savedEmail = prefs[Keys.SAVED_EMAIL] ?: "",
        activeOverviewPanel = enumValues<OverviewPanel>().firstOrNull { it.name == prefs[Keys.OVERVIEW_PANEL] } ?: OverviewPanel.SALES,
        lastSyncMillis = prefs[Keys.LAST_SYNC] ?: 0L,
        offlineModeActive = prefs[Keys.OFFLINE_MODE] ?: false,
        snippets = JsonCodecs.decodeStrings(prefs[Keys.SNIPPETS] ?: "[]"),
      )
    }

  suspend fun cycleTheme(current: AppThemeMode) {
    val next = when (current) {
      AppThemeMode.DARK -> AppThemeMode.AUTO
      AppThemeMode.AUTO -> AppThemeMode.LIGHT
      AppThemeMode.LIGHT -> AppThemeMode.DARK
    }
    context.dataStore.edit { it[Keys.THEME] = next.name }
  }

  suspend fun setHaptic(intensity: HapticIntensity) {
    context.dataStore.edit { it[Keys.HAPTIC] = intensity.name }
  }

  suspend fun setSound(intensity: SoundIntensity) {
    context.dataStore.edit { it[Keys.SOUND] = intensity.name }
  }

  suspend fun setFingerprintLock(enabled: Boolean) {
    context.dataStore.edit { it[Keys.FINGERPRINT] = enabled }
  }

  suspend fun setSavedEmail(email: String) {
    context.dataStore.edit { it[Keys.SAVED_EMAIL] = email }
  }

  suspend fun setOverviewPanel(panel: OverviewPanel) {
    context.dataStore.edit { it[Keys.OVERVIEW_PANEL] = panel.name }
  }

  suspend fun setLastSync(millis: Long) {
    context.dataStore.edit { it[Keys.LAST_SYNC] = millis }
  }

  suspend fun setOfflineMode(active: Boolean) {
    context.dataStore.edit { it[Keys.OFFLINE_MODE] = active }
  }

  suspend fun saveSnippets(list: List<String>) {
    val bounded = list.take(30).map { it.take(500) }
    context.dataStore.edit { it[Keys.SNIPPETS] = JsonCodecs.encodeStrings(bounded) }
  }
}
