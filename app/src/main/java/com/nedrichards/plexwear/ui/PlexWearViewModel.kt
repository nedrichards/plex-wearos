package com.nedrichards.plexwear.ui

import android.app.Application
import com.nedrichards.plexwear.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nedrichards.plexwear.auth.*
import com.nedrichards.plexwear.data.*
import com.nedrichards.plexwear.offline.*
import com.nedrichards.plexwear.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*


data class PlexAuthUiState(val pinCode: String? = null, val message: String? = null, val waiting: Boolean = false)

data class PlexWearUiState(
  val credentials: PlexCredentials = PlexCredentials("", ""),
  val title: String = "Plex Wear",
  val destinationId: String = "home",
  val items: List<BrowseItem> = emptyList(),
  val tracks: List<PlexTrack> = emptyList(),
  val sessions: List<PlexSession> = emptyList(),
  val trackListTitle: String? = null,
  val selectedTrack: PlexTrack? = null,
  val nowPlaying: PlexTrack? = null,
  val nowPlayingContext: String? = null,
  val nowPlayingTrackCount: Int = 0,
  val nowPlayingQueue: List<PlexTrack> = emptyList(),
  val nowPlayingIndex: Int = -1,
  val playbackPaused: Boolean = false,
  val playbackStatus: PlaybackStatus = PlaybackStatus.Idle,
  val playbackPositionMs: Long = 0,
  val playbackDurationMs: Long = 0,
  val loading: Boolean = false,
  val refreshingHome: Boolean = false,
  val error: String? = null,
  val pageError: String? = null,
  val auth: PlexAuthUiState = PlexAuthUiState(),
  val searchQuery: String = "",
  val offlineQuality: OfflineQuality = OfflineQuality.Default,
  val downloadedTrackQualities: Map<String, OfflineQuality> = emptyMap(),
  val downloadingTrackIds: Set<String> = emptySet(),
  val offlineCacheBytes: Long = 0,
  val downloadedTracks: List<PlexTrack> = emptyList(),
  val downloadedCollections: Map<String, List<PlexTrack>> = emptyMap(),
  val downloadProgress: String? = null,
  val downloadError: String? = null,
  val downloadBusy: Boolean = false,
  val autoCacheEnabled: Boolean = false,
  val availableServers: List<PlexServerChoice> = emptyList(),
  val pinnedPlaylists: List<PlexPlaylist> = emptyList(),
  val loadingMore: Boolean = false,
  val canLoadMore: Boolean = false,
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

enum class Screen { Home, Albums, Playlists, Tracks, Track, NowPlaying, Sessions, Settings, Downloads }

class PlexWearViewModel(
  application: Application,
  private val authStore: PlexAuthStore,
  private val offlineSettingsStore: OfflineSettingsStore,
  private val offlineCacheManager: OfflineCacheManager,
  private val repository: PlexRepository,
  private val authClient: PlexAuthClient,
) : AndroidViewModel(application) {
  private val playbackController = PlaybackController(application)
  private val homeStore = HomeSnapshotStore(application)
  private val appScope = (application as com.nedrichards.plexwear.PlexWearApplication).appScope
  private var authJob: Job? = null
  private var browseJob: Job? = null
  private var playJob: Job? = null
  private var collectionJob: Job? = null
  private val accountJobs = mutableSetOf<Job>()
  private var accountEpoch = 0L
  private var destinationCounter = 0L
  private var pageSource: PageSource? = null
  private val requestGate = BrowseRequestGate()
  private val backStack = ArrayDeque<NavigationDestination>()
  private val _uiState = MutableStateFlow(PlexWearUiState())
  val uiState: StateFlow<PlexWearUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch { offlineSettingsStore.quality.collect { quality -> _uiState.update { it.copy(offlineQuality = quality) } } }
    viewModelScope.launch { offlineSettingsStore.autoCacheEnabled.collect { enabled -> _uiState.update { it.copy(autoCacheEnabled = enabled) } } }
    viewModelScope.launch { authStore.servers.collect { servers -> _uiState.update { it.copy(availableServers = servers) } } }
    viewModelScope.launch {
      offlineCacheManager.snapshot.collect { snapshot ->
        if (!snapshot.ready || snapshot.scope != _uiState.value.credentials.cacheScope) return@collect
        _uiState.update { current ->
          val tracks = snapshot.entries.mapNotNull { it.track }.distinctBy { it.ratingKey }
          current.copy(
            downloadedTrackQualities = snapshot.entries.groupBy { it.ratingKey }
              .mapValues { (_, entries) -> entries.maxBy { it.quality.bitrateKbps }.quality },
            downloadingTrackIds = snapshot.downloadingTrackIds,
            offlineCacheBytes = snapshot.totalBytes,
            downloadedTracks = tracks,
            downloadedCollections = snapshot.collections,
            downloadProgress = snapshot.progressSummary,
            downloadError = snapshot.jobs.firstOrNull { it.error != null }?.error,
            downloadBusy = snapshot.jobs.any { it.state == "Queued" || it.state == "Downloading" },
            tracks = when {
              current.screen == Screen.Downloads -> tracks
              current.screen == Screen.Tracks && pageSource == null && current.trackListTitle != null -> snapshot.collections[current.trackListTitle].orEmpty()
              else -> current.tracks
            },
          )
        }
      }
    }
    viewModelScope.launch {
      playbackController.state.collect { playback ->
        _uiState.update { current -> current.copy(
          nowPlayingQueue = playback.queue,
          nowPlayingTrackCount = playback.queue.size,
          nowPlaying = playback.queue.getOrNull(playback.mediaItemIndex),
          nowPlayingIndex = playback.mediaItemIndex,
          playbackPaused = !playback.playWhenReady || playback.playbackStatus in listOf(PlaybackStatus.Idle, PlaybackStatus.Ended, PlaybackStatus.Failed),
          playbackStatus = playback.playbackStatus,
          playbackPositionMs = playback.positionMs,
          playbackDurationMs = playback.durationMs,
          error = if (current.screen == Screen.NowPlaying) playback.errorMessage else current.error,
        ) }
      }
    }
    viewModelScope.launch {
      authStore.seedDebugCredentialsIfNeeded()
      authStore.credentials.collect { credentials ->
        _uiState.update { current ->
          if (current.credentials.cacheScope == credentials.cacheScope) current.copy(credentials = credentials)
          else current.copy(credentials = credentials, items = emptyList(), tracks = emptyList(), pinnedPlaylists = emptyList(),
            downloadedTracks = emptyList(), downloadedCollections = emptyMap(), downloadedTrackQualities = emptyMap(),
            downloadingTrackIds = emptySet(), offlineCacheBytes = 0, downloadProgress = null, downloadError = null)
        }
        offlineCacheManager.activate(credentials)
        if (credentials.isConfigured && _uiState.value.screen == Screen.Home) loadHome()
      }
    }
    viewModelScope.launch { safely { playbackController.reconnect() } }
  }

  private fun text(id: Int): String = getApplication<Application>().getString(id)

  private fun invalidateBrowse() { browseJob?.cancel(); browseJob = null; requestGate.advance() }

  fun loadHome() {
    invalidateBrowse()
    backStack.clear()
    pageSource = null
    _uiState.update { it.copy(screen = Screen.Home, destinationId = "home", title = "Plex Wear",
      loading = false, refreshingHome = it.credentials.isConfigured, selectedTrack = null,
      trackListTitle = null, searchQuery = "", loadingMore = false, canLoadMore = false, error = null, pageError = null) }
    if (!_uiState.value.configured) return
    val generation = requestGate.current()
    val credentials = _uiState.value.credentials
    browseJob = viewModelScope.launch {
      val saved = homeStore.read(credentials)
      if (!requestGate.accepts(generation)) return@launch
      _uiState.update { it.copy(items = saved.libraries.map(BrowseItem::LibraryItem), tracks = saved.recent, pinnedPlaylists = saved.pinnedPlaylists) }
      supervisorScope {
        launch { homeSection(generation) {
          val libraries = repository.musicLibraries(credentials)
          if (requestGate.accepts(generation)) _uiState.update { it.copy(items = libraries.map(BrowseItem::LibraryItem)) }
          homeStore.update(credentials) { it.copy(libraries = libraries) }
        } }
        launch { homeSection(generation) {
          val recent = repository.recentMusic(credentials).take(6)
          if (requestGate.accepts(generation)) _uiState.update { it.copy(tracks = recent) }
          homeStore.update(credentials) { it.copy(recent = recent) }
        } }
      }
      if (requestGate.accepts(generation)) _uiState.update { it.copy(refreshingHome = false) }
    }
  }

  private suspend fun homeSection(generation: Long, block: suspend () -> Unit) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (requestGate.accepts(generation)) _uiState.update { it.copy(error = text(R.string.workflow_refresh_failed)) } }
  }

  fun refreshHome() = accountAction { repository.clearCache(); refreshServerConnections(); loadHome() }

  private suspend fun refreshServerConnections() {
    val token = authStore.accountToken.first()
    if (token.isBlank()) return
    try {
      val choices = authClient.serversForToken(token)
      val selected = choices.firstOrNull { it.credentials.serverMachineIdentifier == _uiState.value.credentials.serverMachineIdentifier }
      authStore.saveServers(choices, token)
      if (selected != null) authStore.save(selected.credentials)
      PlexConnectionResolver.invalidate()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* Keep the last known routes when discovery is unavailable. */ }
  }

  fun loadAlbums(library: PlexLibrary) = loadFirstPage(PageSource.Albums(library), Screen.Albums, library.title)
  fun loadPlaylists() = loadFirstPage(PageSource.Playlists, Screen.Playlists, text(R.string.playlists))
  fun loadAlbumTracks(album: PlexAlbum) = loadFirstPage(PageSource.AlbumTracks(album), Screen.Tracks, album.title)
  fun loadPlaylistTracks(playlist: PlexPlaylist) = loadFirstPage(PageSource.PlaylistTracks(playlist), Screen.Tracks, playlist.title)

  fun loadSessions() {
    val refreshing = _uiState.value.screen == Screen.Sessions
    if (!refreshing) { pushCurrentDestination(); moveTo(Screen.Sessions, text(R.string.active_streams)) }
    else invalidateBrowse()
    _uiState.update { it.copy(loading = it.sessions.isEmpty(), error = null) }
    val generation = requestGate.current()
    val credentials = _uiState.value.credentials
    browseJob = viewModelScope.launch { browseRequest(generation) {
      val sessions = repository.activeSessions(credentials)
      if (requestGate.accepts(generation)) _uiState.update { it.copy(sessions = sessions, loading = false) }
    } }
  }

  fun toggleSessionPlayback(session: PlexSession) = accountAction {
    repository.toggleSessionPlayback(_uiState.value.credentials, session)
    if (_uiState.value.screen == Screen.Sessions) loadSessions()
  }

  fun loadMore() {
    val source = pageSource ?: return
    val state = _uiState.value
    if (state.loading || state.loadingMore || !state.canLoadMore) return
    // Set the guard before launching or reading DataStore, so repeated rotary events cannot overlap.
    _uiState.update { it.copy(loadingMore = true, pageError = null) }
    val generation = requestGate.current()
    val start = if (source.isTrackSource) state.tracks.size else state.items.size
    browseJob = viewModelScope.launch { browseRequest(generation, page = true) {
      val page = loadPage(state.credentials, source, start)
      if (requestGate.accepts(generation)) _uiState.update { current -> current.copy(
        items = current.items + page.items, tracks = current.tracks + page.tracks,
        loadingMore = false, canLoadMore = page.isFull, pageError = null,
      ) }
    } }
  }

  fun retryPage() {
    if (_uiState.value.tracks.isEmpty() && _uiState.value.items.isEmpty()) {
      pageSource?.let { source -> loadFirstPage(source, _uiState.value.screen, _uiState.value.title, push = false) }
    } else loadMore()
  }

  private suspend fun browseRequest(generation: Long, page: Boolean = false, block: suspend () -> Unit) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (requestGate.accepts(generation)) _uiState.update { it.copy(
      loading = false, loadingMore = false,
      pageError = text(R.string.workflow_page_failed), error = if (page) it.error else text(R.string.workflow_page_failed),
    ) } }
  }

  fun openTrack(track: PlexTrack) {
    pushCurrentDestination(); moveTo(Screen.Track, track.title)
    _uiState.update { it.copy(selectedTrack = track) }
  }

  fun play(track: PlexTrack) = startPlayback(listOf(track), null, null)

  fun playAll(tracks: List<PlexTrack>) {
    val state = _uiState.value
    val source = pageSource.takeIf { state.screen == Screen.Tracks && !state.searching && it?.isTrackSource == true }
    startPlayback(tracks, source, state.trackListTitle)
  }

  private fun plan(credentials: PlexCredentials, track: PlexTrack): PlexPlaybackPlan = PlexMediaItems.playbackPlan(
    credentials, track, _uiState.value.offlineQuality,
    offlineCacheManager.cachedUri(credentials, track, _uiState.value.offlineQuality),
  )

  private fun startPlayback(tracks: List<PlexTrack>, source: PageSource?, title: String?) {
    if (tracks.isEmpty()) return
    collectionJob?.cancel(); playJob?.cancel()
    val credentials = _uiState.value.credentials
    val epoch = accountEpoch
    val queueGeneration = getApplication<com.nedrichards.plexwear.PlexWearApplication>().playbackGeneration.incrementAndGet()
    val hasMore = source != null && _uiState.value.canLoadMore
    pushCurrentDestination(); moveTo(Screen.NowPlaying, text(R.string.now_playing))
    _uiState.update { it.copy(nowPlayingContext = title, playbackStatus = PlaybackStatus.Starting) }
    playJob = viewModelScope.launch { safely {
      offlineCacheManager.activate(credentials)
      playbackController.play(tracks.map { plan(credentials, it) })
      if (_uiState.value.autoCacheEnabled) offlineCacheManager.maybeAutoCache(credentials, tracks.getOrNull(1) ?: tracks.first(), _uiState.value.offlineQuality)
      if (hasMore) {
        // Queue extension survives activity/view-model recreation; the session is authoritative.
        collectionJob = appScope.launch {
          try {
            var offset = tracks.size
            while (epoch == accountEpoch && queueGeneration == getApplication<com.nedrichards.plexwear.PlexWearApplication>().playbackGeneration.get()) {
              val page = loadPage(credentials, source, offset)
              if (epoch != accountEpoch || queueGeneration != getApplication<com.nedrichards.plexwear.PlexWearApplication>().playbackGeneration.get()) break
              if (page.tracks.isNotEmpty()) playbackController.append(page.tracks.map { plan(credentials, it) })
              offset += page.tracks.size
              if (!page.isFull) break
            }
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { _uiState.update { it.copy(error = text(R.string.workflow_queue_failed)) } }
        }
      }
    } }
  }

  fun openCurrentPlayback() {
    if (!_uiState.value.canOpenCurrentPlayback) return
    pushCurrentDestination(); moveTo(Screen.NowPlaying, text(R.string.now_playing))
  }

  fun navigateBack() {
    invalidateBrowse()
    if (backStack.isEmpty()) { loadHome(); return }
    val destination = backStack.removeLast()
    pageSource = destination.source
    _uiState.update(destination.snapshot::restore)
    if (_uiState.value.screen == Screen.Downloads) _uiState.update { it.copy(tracks = it.downloadedTracks) }
    if (_uiState.value.tracks.isEmpty() && _uiState.value.items.isEmpty() && pageSource != null) retryPage()
  }

  fun togglePlayback() = accountAction {
    if (_uiState.value.playbackPaused || _uiState.value.playbackStatus == PlaybackStatus.Ended || _uiState.value.playbackStatus == PlaybackStatus.Failed) playbackController.resume()
    else playbackController.pause()
  }
  fun skipToPrevious() = accountAction { playbackController.skipToPrevious() }
  fun skipToNext() = accountAction { playbackController.skipToNext() }

  fun downloadTrack(track: PlexTrack) = enqueueDownloads(listOf(track), null)
  fun downloadTracks(tracks: List<PlexTrack>) {
    val state = _uiState.value
    val source = pageSource.takeIf { state.screen == Screen.Tracks && !state.searching && it?.isTrackSource == true }
    if (source != null) {
      accountAction { offlineCacheManager.enqueueCollection(state.credentials, source.path, state.title, state.offlineQuality) }
    } else enqueueDownloads(tracks, state.trackListTitle)
  }
  private fun enqueueDownloads(tracks: List<PlexTrack>, title: String?) = accountAction {
    offlineCacheManager.enqueueTracks(_uiState.value.credentials, tracks, _uiState.value.offlineQuality, title)
  }
  fun cancelDownloads() = accountAction { offlineCacheManager.cancelDownloads() }
  fun retryDownloads() = accountAction { offlineCacheManager.retryDownloads() }
  fun removeDownload(track: PlexTrack) = accountAction { offlineCacheManager.removeTrack(track.ratingKey) }
  fun clearOfflineCache() = accountAction { offlineCacheManager.clear() }
  fun setOfflineQuality(quality: OfflineQuality) = accountAction { offlineSettingsStore.setQuality(quality) }
  fun cycleOfflineQuality() = setOfflineQuality(_uiState.value.offlineQuality.next())
  fun toggleAutoCache() = accountAction { offlineSettingsStore.setAutoCacheEnabled(!_uiState.value.autoCacheEnabled) }

  fun openDownloads() {
    pushCurrentDestination(); moveTo(Screen.Downloads, text(R.string.downloads))
    _uiState.update { it.copy(tracks = it.downloadedTracks) }
  }
  fun openDownloadCollection(title: String) {
    pushCurrentDestination(); moveTo(Screen.Tracks, title)
    _uiState.update { it.copy(tracks = it.downloadedCollections[title].orEmpty(), trackListTitle = title) }
  }
  fun openSettings() { pushCurrentDestination(); moveTo(Screen.Settings, text(R.string.settings)) }
  fun setSearchQuery(query: String) { _uiState.update { it.copy(searchQuery = query.trim(), error = null) } }
  fun clearSearchQuery() { _uiState.update { it.copy(searchQuery = "") } }

  fun togglePlaylistPin(playlist: PlexPlaylist) = accountAction {
    val credentials = _uiState.value.credentials
    val saved = homeStore.update(credentials) { snapshot -> snapshot.copy(pinnedPlaylists =
      if (snapshot.pinnedPlaylists.any { it.key == playlist.key }) snapshot.pinnedPlaylists.filterNot { it.key == playlist.key }
      else (snapshot.pinnedPlaylists + playlist).takeLast(6)) }
    _uiState.update { it.copy(pinnedPlaylists = saved.pinnedPlaylists) }
  }

  fun selectServer(id: String) {
    val choice = _uiState.value.availableServers.firstOrNull { it.id == id } ?: return
    accountAction {
      val selectingJob = currentCoroutineContext()[Job]
      accountJobs.toList().filterNot { it === selectingJob }.forEach { it.cancel() }
      stopAccountWork()
      playbackController.stop()
      offlineCacheManager.cancelDownloads()
      repository.clearCache()
      authStore.save(choice.credentials)
      offlineCacheManager.activate(choice.credentials)
      _uiState.update { it.copy(credentials = choice.credentials, nowPlayingContext = null) }
      loadHome()
    }
  }

  fun startPinAuth() {
    if (authJob?.isActive == true) return
    authJob = viewModelScope.launch {
      _uiState.update { it.copy(title = text(R.string.sign_in), error = null, auth = PlexAuthUiState(message = text(R.string.workflow_request_pin), waiting = true)) }
      try {
        val pin = authClient.createPin()
        _uiState.update { it.copy(auth = PlexAuthUiState(pin.code, text(R.string.workflow_enter_pin), true)) }
        val token = withTimeoutOrNull(10 * 60 * 1000L) {
          var result: String? = null
          while (result == null) { delay(3_000); result = authClient.pollPin(pin.id) }
          result
        }
        if (token == null) { _uiState.update { it.copy(auth = PlexAuthUiState(message = text(R.string.workflow_pin_timeout))) }; return@launch }
        val choices = authClient.serversForToken(token)
        authStore.saveServers(choices, token)
        authStore.save(choices.first().credentials)
        _uiState.update { it.copy(credentials = choices.first().credentials, auth = PlexAuthUiState()) }
        loadHome()
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { _uiState.update { it.copy(auth = PlexAuthUiState(message = text(R.string.workflow_pin_failed))) } }
    }
  }
  fun cancelPinAuth() { authJob?.cancel(); authJob = null; _uiState.update { it.copy(auth = PlexAuthUiState(), error = null) } }

  private fun stopAccountWork() {
    accountEpoch++
    getApplication<com.nedrichards.plexwear.PlexWearApplication>().playbackGeneration.incrementAndGet()
    invalidateBrowse(); collectionJob?.cancel(); playJob?.cancel()
    authJob?.cancel(); authJob = null
    backStack.clear(); pageSource = null
  }
  fun resetAuth(): Job {
    stopAccountWork()
    accountJobs.toList().forEach { it.cancel() }
    return viewModelScope.launch {
      playbackController.stop()
      offlineCacheManager.cancelDownloads()
      repository.clearCache()
      authStore.clear()
      offlineCacheManager.activate(PlexCredentials("", ""))
      _uiState.value = PlexWearUiState()
    }
  }

  override fun onCleared() {
    authJob?.cancel(); browseJob?.cancel()
    // The playback queue extension is application scoped; release only this UI connection.
    if (collectionJob?.isActive == true) collectionJob?.invokeOnCompletion { playbackController.release() }
    else playbackController.release()
    super.onCleared()
  }

  private fun accountAction(block: suspend () -> Unit) {
    val epoch = accountEpoch
    val job = viewModelScope.launch { safely { if (epoch == accountEpoch) block() } }
    accountJobs += job
    job.invokeOnCompletion { accountJobs -= job }
  }
  private suspend fun safely(block: suspend () -> Unit) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { _uiState.update { it.copy(error = text(R.string.workflow_action_failed)) } }
  }

  private fun moveTo(screen: Screen, title: String) {
    invalidateBrowse(); pageSource = null
    val destinationId = "${screen.name}-${++destinationCounter}"
    _uiState.update { it.copy(screen = screen, title = title, destinationId = destinationId,
      items = emptyList(), tracks = emptyList(), sessions = emptyList(), selectedTrack = null,
      trackListTitle = null, searchQuery = "", error = null, pageError = null,
      loading = false, loadingMore = false, canLoadMore = false) }
  }
  private fun loadFirstPage(source: PageSource, screen: Screen, title: String, push: Boolean = true) {
    if (push) { pushCurrentDestination(); moveTo(screen, title) } else invalidateBrowse()
    pageSource = source
    _uiState.update { it.copy(loading = true, pageError = null, error = null, trackListTitle = title.takeIf { screen == Screen.Tracks }) }
    val generation = requestGate.current()
    val credentials = _uiState.value.credentials
    browseJob = viewModelScope.launch { browseRequest(generation) {
      val page = loadPage(credentials, source, 0)
      if (requestGate.accepts(generation)) _uiState.update { it.copy(items = page.items, tracks = page.tracks,
        loading = false, canLoadMore = page.isFull) }
    } }
  }

  private suspend fun loadPage(credentials: PlexCredentials, source: PageSource, start: Int): BrowsePage = when (source) {
    is PageSource.Albums -> BrowsePage(items = repository.albums(credentials, source.library, start).map(BrowseItem::AlbumItem))
    PageSource.Playlists -> BrowsePage(items = repository.playlists(credentials, start).map(BrowseItem::PlaylistItem))
    is PageSource.AlbumTracks -> BrowsePage(tracks = repository.tracksForAlbum(credentials, source.album, start))
    is PageSource.PlaylistTracks -> BrowsePage(tracks = repository.tracksForPlaylist(credentials, source.playlist, start))
  }
  private sealed interface PageSource {
    data class Albums(val library: PlexLibrary) : PageSource
    data object Playlists : PageSource
    data class AlbumTracks(val album: PlexAlbum) : PageSource
    data class PlaylistTracks(val playlist: PlexPlaylist) : PageSource
    val isTrackSource: Boolean get() = this is AlbumTracks || this is PlaylistTracks
    val path: String get() = when (this) {
      is AlbumTracks -> album.key
      is PlaylistTracks -> playlist.key
      is Albums -> "/library/sections/${library.key}/albums"
      Playlists -> "/playlists"
    }
  }
  private data class BrowsePage(val items: List<BrowseItem> = emptyList(), val tracks: List<PlexTrack> = emptyList()) {
    val isFull: Boolean get() = items.size + tracks.size == BROWSE_PAGE_SIZE
  }
  private fun pushCurrentDestination() { backStack.addLast(NavigationDestination(NavigationSnapshot(_uiState.value), pageSource)) }
  private data class NavigationDestination(val snapshot: NavigationSnapshot, val source: PageSource?)
}
