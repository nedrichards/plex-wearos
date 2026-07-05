package com.nedrichards.plexwear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nedrichards.plexwear.ui.PlexWearApp
import com.nedrichards.plexwear.ui.PlexWearViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val app = application as PlexWearApplication
    setContent {
      val viewModel: PlexWearViewModel = viewModel(
        factory = viewModelFactory {
          initializer {
            PlexWearViewModel(
              app,
              app.authStore,
              app.offlineSettingsStore,
              app.offlineCacheManager,
              app.repository,
              app.authClient,
            )
          }
        },
      )
      PlexWearApp(viewModel = viewModel)
    }
  }
}
