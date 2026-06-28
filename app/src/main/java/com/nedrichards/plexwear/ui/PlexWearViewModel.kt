package com.nedrichards.plexwear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nedrichards.plexwear.auth.PlexAuthStore
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.BrowseItem
import com.nedrichards.plexwear.data.PlexAlbum
import com.nedrichards.plexwear.data.PlexLibrary
import com.nedrichards.plexwear.data.PlexPlaylist
import com.nedrichards.plexwear.data.PlexRepository
import com.nedrichards.plexwear.data.PlexTrack
import com.nedrichards.plexwear.playback.PlaybackController
import com.nedrichards.plexwear.playback.PlexMediaItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlexWearUiState(
  val credentials: PlexCredentials = PlexCredentials("", ""),
  val title: String = "Plex Wear",
  val items: List<BrowseItem> = emptyList(),
  val tracks: List<PlexTrack> = emptyList(),
  val nowPlaying: PlexTrack? = null,
  val loading: Boolean = true,
  val error: String? = null,
  val screen: Screen = Screen.Home,
) {
  val configured: Boolean = credentials.isConfigured
}

enum class Screen {
  Home,
  Albums,
  Playlists,
  Tracks,
  NowPlaying,
  Settings,
}

class PlexWearViewModel(
  application: Application,
  private val authStore: PlexAuthStore,
  private val repository: PlexRepository,
) : AndroidViewModel(application) {
  private val playbackController = PlaybackController(application)
  private val _uiState = MutableStateFlow(PlexWearUiState())
  val uiState: StateFlow<PlexWearUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      authStore.seedDebugCredentialsIfNeeded()
      val credentials = authStore.credentials.first()
      _uiState.update { it.copy(credentials = credentials, loading = false) }
      if (credentials.isConfigured) loadHome()
    }
  }

  fun loadHome() {
    withCredentials { credentials ->
      _uiState.update { it.copy(screen = Screen.Home, title = "Plex Wear", loading = true, error = null) }
      val libraries = repository.musicLibraries(credentials).map(BrowseItem::LibraryItem)
      val recent = repository.recentMusic(credentials).take(6)
      _uiState.update {
        it.copy(
          items = libraries,
          tracks = recent,
          loading = false,
          error = null,
        )
      }
    }
  }

  fun loadAlbums(library: PlexLibrary) {
    withCredentials { credentials ->
      _uiState.update { it.copy(screen = Screen.Albums, title = library.title, loading = true, error = null) }
      val albums = repository.albums(credentials, library).map(BrowseItem::AlbumItem)
      _uiState.update { it.copy(items = albums, tracks = emptyList(), loading = false) }
    }
  }

  fun loadPlaylists() {
    withCredentials { credentials ->
      _uiState.update { it.copy(screen = Screen.Playlists, title = "Playlists", loading = true, error = null) }
      val playlists = repository.playlists(credentials).map(BrowseItem::PlaylistItem)
      _uiState.update { it.copy(items = playlists, tracks = emptyList(), loading = false) }
    }
  }

  fun loadAlbumTracks(album: PlexAlbum) {
    withCredentials { credentials ->
      _uiState.update { it.copy(screen = Screen.Tracks, title = album.title, loading = true, error = null) }
      val tracks = repository.tracksForAlbum(credentials, album)
      _uiState.update { it.copy(items = emptyList(), tracks = tracks, loading = false) }
    }
  }

  fun loadPlaylistTracks(playlist: PlexPlaylist) {
    withCredentials { credentials ->
      _uiState.update { it.copy(screen = Screen.Tracks, title = playlist.title, loading = true, error = null) }
      val tracks = repository.tracksForPlaylist(credentials, playlist)
      _uiState.update { it.copy(items = emptyList(), tracks = tracks, loading = false) }
    }
  }

  fun play(track: PlexTrack, preferTranscode: Boolean = false) {
    withCredentials { credentials ->
      val item = if (preferTranscode) {
        PlexMediaItems.transcode(credentials, track)
      } else {
        PlexMediaItems.direct(credentials, track)
      }
      playbackController.play(item)
      _uiState.update { it.copy(screen = Screen.NowPlaying, nowPlaying = track, error = null) }
    }
  }

  fun openSettings() {
    _uiState.update { it.copy(screen = Screen.Settings, title = "Settings", loading = false, error = null) }
  }

  fun resetAuth() {
    viewModelScope.launch {
      authStore.clear()
      authStore.seedDebugCredentialsIfNeeded()
      val credentials = authStore.credentials.first()
      _uiState.update {
        it.copy(
          credentials = credentials,
          screen = Screen.Home,
          title = "Plex Wear",
          items = emptyList(),
          tracks = emptyList(),
          nowPlaying = null,
          loading = false,
          error = null,
        )
      }
      if (credentials.isConfigured) loadHome()
    }
  }

  override fun onCleared() {
    playbackController.release()
  }

  private fun withCredentials(block: suspend (PlexCredentials) -> Unit) {
    viewModelScope.launch {
      val credentials = authStore.credentials.first()
      if (!credentials.isConfigured) {
        _uiState.update {
          it.copy(credentials = credentials, loading = false, error = "Add plex.serverUrl and plex.token to local.properties.")
        }
        return@launch
      }

      runCatching {
        _uiState.update { it.copy(credentials = credentials) }
        block(credentials)
      }.onFailure { throwable ->
        _uiState.update { it.copy(loading = false, error = throwable.message ?: "Plex request failed") }
      }
    }
  }
}
