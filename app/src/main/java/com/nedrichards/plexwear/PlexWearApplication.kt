package com.nedrichards.plexwear

import android.app.Application
import com.nedrichards.plexwear.auth.PlexAuthStore
import com.nedrichards.plexwear.data.PlexApi
import com.nedrichards.plexwear.data.PlexRepository

class PlexWearApplication : Application() {
  lateinit var authStore: PlexAuthStore
    private set

  lateinit var repository: PlexRepository
    private set

  override fun onCreate() {
    super.onCreate()
    authStore = PlexAuthStore(this)
    repository = PlexRepository(PlexApi())
  }
}
