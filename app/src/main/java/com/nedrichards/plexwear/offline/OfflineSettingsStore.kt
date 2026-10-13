package com.nedrichards.plexwear.offline

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.plexOfflineSettingsDataStore by preferencesDataStore(name = "plex_offline_settings")

class OfflineSettingsStore(context: Context) {
  private val dataStore = context.plexOfflineSettingsDataStore

  val quality: Flow<OfflineQuality> = dataStore.data.map { preferences ->
    OfflineQuality.fromBitrateKbps(preferences[QUALITY_BITRATE_KBPS] ?: OfflineQuality.Default.bitrateKbps)
  }

  val autoCacheEnabled: Flow<Boolean> = dataStore.data.map { it[AUTO_CACHE_ENABLED] ?: false }

  suspend fun setAutoCacheEnabled(enabled: Boolean) { dataStore.edit { it[AUTO_CACHE_ENABLED] = enabled } }

  suspend fun setQuality(quality: OfflineQuality) {
    dataStore.edit { preferences ->
      preferences[QUALITY_BITRATE_KBPS] = quality.bitrateKbps
    }
  }

  companion object {
    private val AUTO_CACHE_ENABLED = booleanPreferencesKey("auto_cache_enabled")
    private val QUALITY_BITRATE_KBPS = intPreferencesKey("quality_bitrate_kbps")
  }
}
