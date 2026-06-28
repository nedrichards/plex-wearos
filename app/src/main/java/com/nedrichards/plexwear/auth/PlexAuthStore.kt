package com.nedrichards.plexwear.auth

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nedrichards.plexwear.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class PlexCredentials(
  val serverUrl: String,
  val token: String,
) {
  val isConfigured: Boolean = serverUrl.isNotBlank() && token.isNotBlank()
}

private val Context.plexAuthDataStore by preferencesDataStore(name = "plex_auth")

class PlexAuthStore(context: Context) {
  private val dataStore = context.plexAuthDataStore

  val credentials: Flow<PlexCredentials> = dataStore.data.map { preferences ->
    PlexCredentials(
      serverUrl = preferences[SERVER_URL].orEmpty(),
      token = preferences[TOKEN].orEmpty(),
    )
  }

  val debugSeeded: Flow<Boolean> = dataStore.data.map { preferences ->
    preferences[DEBUG_SEEDED] == true
  }

  suspend fun seedDebugCredentialsIfNeeded() {
    if (!BuildConfig.DEBUG) return
    val debugServerUrl = BuildConfig.DEBUG_PLEX_SERVER_URL.trim()
    val debugToken = BuildConfig.DEBUG_PLEX_TOKEN.trim()
    if (debugServerUrl.isBlank() || debugToken.isBlank()) return

    dataStore.edit { preferences ->
      if (preferences[DEBUG_SEEDED] == true) return@edit
      if (preferences[SERVER_URL].isNullOrBlank() && preferences[TOKEN].isNullOrBlank()) {
        preferences[SERVER_URL] = debugServerUrl.trimEnd('/')
        preferences[TOKEN] = debugToken
      }
      preferences[DEBUG_SEEDED] = true
    }
  }

  suspend fun save(serverUrl: String, token: String) {
    dataStore.edit { preferences ->
      preferences[SERVER_URL] = serverUrl.trim().trimEnd('/')
      preferences[TOKEN] = token.trim()
      preferences[DEBUG_SEEDED] = false
    }
  }

  suspend fun clear() {
    dataStore.edit { preferences ->
      preferences.remove(SERVER_URL)
      preferences.remove(TOKEN)
      preferences.remove(DEBUG_SEEDED)
    }
  }

  companion object {
    private val SERVER_URL = stringPreferencesKey("server_url")
    private val TOKEN = stringPreferencesKey("token")
    private val DEBUG_SEEDED = booleanPreferencesKey("debug_seeded")
  }
}
