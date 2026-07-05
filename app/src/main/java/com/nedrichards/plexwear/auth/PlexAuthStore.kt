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
  val alternateServerUrls: List<String> = emptyList(),
) {
  val serverUrls: List<String> =
    (listOf(serverUrl) + alternateServerUrls)
      .map { it.trim().trimEnd('/') }
      .filter { it.isNotBlank() }
      .distinct()
  val isConfigured: Boolean = serverUrls.isNotEmpty() && token.isNotBlank()

  fun forServerUrl(serverUrl: String): PlexCredentials =
    copy(
      serverUrl = serverUrl.trim().trimEnd('/'),
      alternateServerUrls = serverUrls.filterNot { it == serverUrl.trim().trimEnd('/') },
    )
}

private val Context.plexAuthDataStore by preferencesDataStore(name = "plex_auth")

class PlexAuthStore(context: Context) {
  private val dataStore = context.plexAuthDataStore

  val credentials: Flow<PlexCredentials> = dataStore.data.map { preferences ->
    PlexCredentials(
      serverUrl = preferences[SERVER_URL].orEmpty(),
      token = preferences[TOKEN].orEmpty(),
      alternateServerUrls = preferences[ALTERNATE_SERVER_URLS]
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList(),
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
    save(PlexCredentials(serverUrl, token))
  }

  suspend fun save(credentials: PlexCredentials) {
    dataStore.edit { preferences ->
      preferences[SERVER_URL] = credentials.serverUrls.firstOrNull().orEmpty()
      preferences[TOKEN] = credentials.token.trim()
      val alternateServerUrls = credentials.serverUrls.drop(1)
      if (alternateServerUrls.isEmpty()) {
        preferences.remove(ALTERNATE_SERVER_URLS)
      } else {
        preferences[ALTERNATE_SERVER_URLS] = alternateServerUrls.joinToString("\n")
      }
      preferences[DEBUG_SEEDED] = false
    }
  }

  suspend fun clear() {
    dataStore.edit { preferences ->
      preferences.remove(SERVER_URL)
      preferences.remove(TOKEN)
      preferences.remove(ALTERNATE_SERVER_URLS)
      preferences.remove(DEBUG_SEEDED)
    }
  }

  companion object {
    private val SERVER_URL = stringPreferencesKey("server_url")
    private val ALTERNATE_SERVER_URLS = stringPreferencesKey("alternate_server_urls")
    private val TOKEN = stringPreferencesKey("token")
    private val DEBUG_SEEDED = booleanPreferencesKey("debug_seeded")
  }
}
