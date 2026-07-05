package com.nedrichards.plexwear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nedrichards.plexwear.auth.PlexAuthClient
import com.nedrichards.plexwear.auth.PlexAuthStore
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.BrowseItem
import com.nedrichards.plexwear.data.PlexAlbum
import com.nedrichards.plexwear.data.PlexLibrary
import com.nedrichards.plexwear.data.PlexPlaylist
import com.nedrichards.plexwear.data.PlexRepository
import com.nedrichards.plexwear.data.PlexTrack
import com.nedrichards.plexwear.offline.OfflineCacheManager
import com.nedrichards.plexwear.offline.OfflineQuality
import com.nedrichards.plexwear.offline.OfflineSettingsStore
import com.nedrichards.plexwear.playback.PlaybackController
import com.nedrichards.plexwear.playback.PlexMediaItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlexAuthUiState(
  val pinCode: String? = null,
  val message: String? = null,
  val waiting: Boolean = false,
)

data class PlexWearUiState(
  val credentials: PlexCredentials = PlexCredentials("", ""),
  val title: String = "Plex Wear",
  val items: List<BrowseItem> = emptyList(),
  val tracks: List<PlexTrack> = emptyList(),
  val trackListTitle: String? = null,
  val selectedTrack: PlexTrack? = null,
  val nowPlaying: PlexTrack? = null,
  val nowPlayingContext: String? = null,
  val nowPlayingTrackCount: Int = 0,
  val nowPlayingQueue: List<PlexTrack> = emptyList(),
  val nowPlayingIndex: Int = -1,
  val playbackPaused: Boolean = false,
  val loading: Boolean = true,
  val error: String? = null,
  val auth: PlexAuthUiState = PlexAuthUiState(),
  val searchQuery: String = "",
  val offlineQuality: OfflineQuality = OfflineQuality.Default,
  val downloadedTrackQualities: Map<String, OfflineQuality> = emptyMap(),
  val downloadingTrackIds: Set<String> = emptySet(),
  val offlineCacheBytes: Long = 0,
  val screen: Screen = Screen.Home,
) {
  val configured: Boolean = credentials.isConfigured
  val searching: Boolean = searchQuery.isNotBlank()
  val canOpenCurrentPlayback: Boolean = nowPlaying != null && screen != Screen.NowPlaying
  val canPlayPrevious: Boolean = nowPlayingIndex > 0
  val canPlayNext: Boolean = nowPlayingIndex >= 0 && nowPlayingIndex < nowPlayingQueue.lastIndex
  fun downloadedQuality(track: PlexTrack): OfflineQuality? = downloadedTrackQualities[track.ratingKey]
  fun isDownloading(track: PlexTrack): Boolean = track.ratingKey in downloadingTrackIds
}

enum class Screen {
  Home,
  Albums,
  Playlists,
  Tracks,
  Track,
  NowPlaying,
  Settings,
}

class PlexWearViewModel(
  application: Application,
  private val authStore: PlexAuthStore,
  private val offlineSettingsStore: OfflineSettingsStore,
  private val offlineCacheManager: OfflineCacheManager,
  private val repository: PlexRepository,
  private val authClient: PlexAuthClient,
) : AndroidViewModel(application) {
  private val playbackController = PlaybackController(application)
  private var authJob: Job? = null
  private val _uiState = MutableStateFlow(PlexWearUiState())
  val uiState: StateFlow<PlexWearUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      offlineSettingsStore.quality.collect { quality ->
        _uiState.update { it.copy(offlineQuality = quality) }
      }
    }
    viewModelScope.launch {
      offlineCacheManager.snapshot.collect { snapshot ->
        _uiState.update {
          it.copy(
            downloadedTrackQualities = snapshot.entries
              .groupBy { entry -> entry.ratingKey }
              .mapValues { (_, entries) -> entries.maxBy { entry -> entry.quality.bitrateKbps }.quality },
            downloadingTrackIds = snapshot.downloadingTrackIds,
            offlineCacheBytes = snapshot.totalBytes,
          )
        }
      }
    }
    viewModelScope.launch {
      authStore.seedDebugCredentialsIfNeeded()
      val credentials = authStore.credentials.first()
      _uiState.update { it.copy(credentials = credentials, loading = false) }
      if (credentials.isConfigured) loadHome()
    }
  }

  fun loadHome() {
    withCredentials { credentials ->
      _uiState.update {
        it.copy(
          screen = Screen.Home,
          title = "Plex Wear",
          loading = true,
          error = null,
          searchQuery = "",
          trackListTitle = null,
          selectedTrack = null,
        )
      }
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
      _uiState.update {
        it.copy(
          screen = Screen.Albums,
          title = library.title,
          loading = true,
          error = null,
          searchQuery = "",
          trackListTitle = null,
          selectedTrack = null,
        )
      }
      val albums = repository.albums(credentials, library).map(BrowseItem::AlbumItem)
      _uiState.update { it.copy(items = albums, tracks = emptyList(), loading = false) }
    }
  }

  fun loadPlaylists() {
    withCredentials { credentials ->
      _uiState.update {
        it.copy(
          screen = Screen.Playlists,
          title = "Playlists",
          loading = true,
          error = null,
          searchQuery = "",
          trackListTitle = null,
          selectedTrack = null,
        )
      }
      val playlists = repository.playlists(credentials).map(BrowseItem::PlaylistItem)
      _uiState.update { it.copy(items = playlists, tracks = emptyList(), loading = false) }
    }
  }

  fun loadAlbumTracks(album: PlexAlbum) {
    withCredentials { credentials ->
      _uiState.update {
        it.copy(
          screen = Screen.Tracks,
          title = album.title,
          loading = true,
          error = null,
          searchQuery = "",
          trackListTitle = album.title,
          selectedTrack = null,
        )
      }
      val tracks = repository.tracksForAlbum(credentials, album)
      _uiState.update { it.copy(items = emptyList(), tracks = tracks, loading = false) }
    }
  }

  fun loadPlaylistTracks(playlist: PlexPlaylist) {
    withCredentials { credentials ->
      _uiState.update {
        it.copy(
          screen = Screen.Tracks,
          title = playlist.title,
          loading = true,
          error = null,
          searchQuery = "",
          trackListTitle = playlist.title,
          selectedTrack = null,
        )
      }
      val tracks = repository.tracksForPlaylist(credentials, playlist)
      _uiState.update { it.copy(items = emptyList(), tracks = tracks, loading = false) }
    }
  }

  fun openTrack(track: PlexTrack) {
    _uiState.update {
      it.copy(
        screen = Screen.Track,
        title = track.title,
        selectedTrack = track,
        error = null,
        searchQuery = "",
      )
    }
  }

  fun openTrackList() {
    _uiState.update {
      if (it.tracks.isEmpty()) {
        it
      } else {
        it.copy(
          screen = Screen.Tracks,
          title = it.trackListTitle ?: it.title,
          selectedTrack = null,
          error = null,
        )
      }
    }
  }

  fun play(track: PlexTrack) {
    withCredentials { credentials ->
      val quality = _uiState.value.offlineQuality
      playbackController.play(
        PlexMediaItems.playbackPlan(
          credentials = credentials,
          track = track,
          quality = quality,
          cachedUri = offlineCacheManager.cachedUri(track, quality),
        ),
      )
      autoCacheTracks(credentials, listOf(track), quality)
      _uiState.update {
        it.copy(
          screen = Screen.NowPlaying,
          title = "Now playing",
          nowPlaying = track,
          nowPlayingContext = null,
          nowPlayingTrackCount = 1,
          nowPlayingQueue = listOf(track),
          nowPlayingIndex = 0,
          selectedTrack = null,
          playbackPaused = false,
          error = null,
          searchQuery = "",
        )
      }
    }
  }

  fun playAll(tracks: List<PlexTrack>) {
    if (tracks.isEmpty()) return
    withCredentials { credentials ->
      val quality = _uiState.value.offlineQuality
      playbackController.play(
        tracks.map { track ->
          PlexMediaItems.playbackPlan(
            credentials = credentials,
            track = track,
            quality = quality,
            cachedUri = offlineCacheManager.cachedUri(track, quality),
          )
        },
      )
      autoCacheTracks(credentials, tracks, quality)
      _uiState.update {
        it.copy(
          screen = Screen.NowPlaying,
          title = "Now playing",
          nowPlaying = tracks.first(),
          nowPlayingContext = it.title,
          nowPlayingTrackCount = tracks.size,
          nowPlayingQueue = tracks,
          nowPlayingIndex = 0,
          selectedTrack = null,
          playbackPaused = false,
          error = null,
          searchQuery = "",
        )
      }
    }
  }

  fun openCurrentPlayback() {
    _uiState.update {
      if (it.nowPlaying == null) {
        it
      } else {
        it.copy(screen = Screen.NowPlaying, title = "Now playing", error = null, searchQuery = "")
      }
    }
  }

  fun togglePlayback() {
    viewModelScope.launch {
      val paused = _uiState.value.playbackPaused
      if (paused) {
        playbackController.resume()
      } else {
        playbackController.pause()
      }
      _uiState.update {
        if (it.nowPlaying == null) it else it.copy(playbackPaused = !paused, error = null)
      }
    }
  }

  fun skipToPrevious() {
    viewModelScope.launch {
      val state = _uiState.value
      val previousIndex = state.nowPlayingIndex - 1
      if (previousIndex !in state.nowPlayingQueue.indices) return@launch

      playbackController.skipToPrevious()
      _uiState.update {
        if (it.nowPlayingQueue.indices.contains(previousIndex)) {
          it.copy(
            nowPlaying = it.nowPlayingQueue[previousIndex],
            nowPlayingIndex = previousIndex,
            playbackPaused = false,
            error = null,
          )
        } else {
          it
        }
      }
    }
  }

  fun skipToNext() {
    viewModelScope.launch {
      val state = _uiState.value
      val nextIndex = state.nowPlayingIndex + 1
      if (nextIndex !in state.nowPlayingQueue.indices) return@launch

      playbackController.skipToNext()
      _uiState.update {
        if (it.nowPlayingQueue.indices.contains(nextIndex)) {
          it.copy(
            nowPlaying = it.nowPlayingQueue[nextIndex],
            nowPlayingIndex = nextIndex,
            playbackPaused = false,
            error = null,
          )
        } else {
          it
        }
      }
    }
  }

  fun downloadTrack(track: PlexTrack) {
    downloadTracks(listOf(track))
  }

  fun downloadTracks(tracks: List<PlexTrack>) {
    if (tracks.isEmpty()) return
    withCredentials { credentials ->
      val quality = _uiState.value.offlineQuality
      runCatching {
        tracks.forEach { track -> offlineCacheManager.downloadTrack(credentials, track, quality) }
      }.onSuccess {
        _uiState.update { it.copy(error = null) }
      }.onFailure { throwable ->
        _uiState.update { it.copy(error = throwable.message ?: "Download failed") }
      }
    }
  }

  fun cycleOfflineQuality() {
    viewModelScope.launch {
      offlineSettingsStore.setQuality(_uiState.value.offlineQuality.next())
    }
  }

  fun clearOfflineCache() {
    viewModelScope.launch {
      runCatching {
        offlineCacheManager.clear()
      }.onSuccess {
        _uiState.update { it.copy(error = null) }
      }.onFailure { throwable ->
        _uiState.update { it.copy(error = throwable.message ?: "Could not clear downloads") }
      }
    }
  }

  fun openSettings() {
    _uiState.update {
      it.copy(
        screen = Screen.Settings,
        title = "Settings",
        loading = false,
        error = null,
        searchQuery = "",
        selectedTrack = null,
      )
    }
  }

  fun setSearchQuery(query: String) {
    _uiState.update { it.copy(searchQuery = query.trim(), error = null) }
  }

  fun clearSearchQuery() {
    _uiState.update { it.copy(searchQuery = "") }
  }

  fun startPinAuth() {
    if (authJob?.isActive == true) return
    authJob = viewModelScope.launch {
      _uiState.update {
        it.copy(
          title = "Sign in",
          loading = false,
          error = null,
          auth = PlexAuthUiState(message = "Requesting sign-in code...", waiting = true),
        )
      }

      try {
        val pin = authClient.createPin()
        _uiState.update {
          it.copy(
            auth = PlexAuthUiState(
              pinCode = pin.code,
              message = "Enter this code at plex.tv/link.",
              waiting = true,
            ),
          )
        }

        val deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS
        var token: String? = null
        while (isActive && token == null && System.currentTimeMillis() < deadline) {
          delay(PIN_POLL_INTERVAL_MS)
          token = authClient.pollPin(pin.id)
        }

        if (token == null) {
          _uiState.update {
            it.copy(
              auth = PlexAuthUiState(message = "Sign-in timed out."),
              error = "Try sign in again.",
            )
          }
          return@launch
        }

        _uiState.update {
          it.copy(auth = it.auth.copy(message = "Finding your Plex server...", waiting = true))
        }
        val credentials = authClient.credentialsForToken(token)
        authStore.save(credentials.serverUrl, credentials.token)
        _uiState.update {
          it.copy(
            credentials = credentials,
            title = "Plex Wear",
            screen = Screen.Home,
            auth = PlexAuthUiState(),
            error = null,
            loading = false,
          )
        }
        loadHome()
      } catch (exception: CancellationException) {
        throw exception
      } catch (throwable: Throwable) {
        _uiState.update {
          it.copy(
            auth = PlexAuthUiState(message = "Sign-in failed."),
            error = throwable.message ?: "Plex sign-in failed",
            loading = false,
          )
        }
      }
    }
  }

  fun cancelPinAuth() {
    authJob?.cancel()
    authJob = null
    _uiState.update { it.copy(auth = PlexAuthUiState(), error = null, loading = false) }
  }

  fun resetAuth() {
    viewModelScope.launch {
      authJob?.cancel()
      authJob = null
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
          trackListTitle = null,
          selectedTrack = null,
          nowPlaying = null,
          nowPlayingContext = null,
          nowPlayingTrackCount = 0,
          nowPlayingQueue = emptyList(),
          nowPlayingIndex = -1,
          playbackPaused = false,
          loading = false,
          error = null,
          auth = PlexAuthUiState(),
          searchQuery = "",
        )
      }
      if (credentials.isConfigured) loadHome()
    }
  }

  override fun onCleared() {
    authJob?.cancel()
    playbackController.release()
  }

  private fun withCredentials(block: suspend (PlexCredentials) -> Unit) {
    viewModelScope.launch {
      val credentials = authStore.credentials.first()
      if (!credentials.isConfigured) {
        _uiState.update {
          it.copy(
            credentials = credentials,
            screen = Screen.Home,
            title = "Plex Wear",
            loading = false,
            error = "Sign in with Plex or use debug credentials.",
          )
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

  private fun autoCacheTracks(
    credentials: PlexCredentials,
    tracks: List<PlexTrack>,
    quality: OfflineQuality,
  ) {
    viewModelScope.launch {
      runCatching {
        tracks.forEach { track -> offlineCacheManager.downloadTrack(credentials, track, quality) }
      }
    }
  }

  private companion object {
    const val PIN_POLL_INTERVAL_MS = 3_000L
    const val AUTH_TIMEOUT_MS = 10 * 60 * 1000L
  }
}
