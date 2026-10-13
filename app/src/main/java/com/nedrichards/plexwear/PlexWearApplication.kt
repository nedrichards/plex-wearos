package com.nedrichards.plexwear

import android.app.Application
import com.nedrichards.plexwear.ui.PlexArtworkStore
import com.nedrichards.plexwear.auth.PlexAuthClient
import com.nedrichards.plexwear.auth.PlexAuthStore
import com.nedrichards.plexwear.data.PlexConnectionResolver
import com.nedrichards.plexwear.data.PlexApi
import com.nedrichards.plexwear.data.PlexRepository
import com.nedrichards.plexwear.offline.OfflineCacheManager
import com.nedrichards.plexwear.offline.OfflineSettingsStore

class PlexWearApplication : Application() {
  val playbackGeneration = java.util.concurrent.atomic.AtomicLong()
  val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
  lateinit var authStore: PlexAuthStore
    private set

  lateinit var offlineSettingsStore: OfflineSettingsStore
    private set

  lateinit var offlineCacheManager: OfflineCacheManager
    private set

  lateinit var repository: PlexRepository
    private set

  lateinit var authClient: PlexAuthClient
    private set

  lateinit var artworkStore: PlexArtworkStore
    private set

  override fun onCreate() {
    super.onCreate()
    val clientPreferences = getSharedPreferences("plex-client", MODE_PRIVATE)
    val identifier = clientPreferences.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
      clientPreferences.edit().putString("id", it).apply()
    }
    com.nedrichards.plexwear.data.PlexRequestBuilder.configureClientIdentifier(identifier)
    PlexConnectionResolver.initialize(this)
    artworkStore = PlexArtworkStore(this)
    authStore = PlexAuthStore(this)
    offlineSettingsStore = OfflineSettingsStore(this)
    offlineCacheManager = OfflineCacheManager(this)
    repository = PlexRepository(PlexApi())
    authClient = PlexAuthClient()
  }
}
