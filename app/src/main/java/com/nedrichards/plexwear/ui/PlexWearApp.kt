package com.nedrichards.plexwear.ui

import android.app.RemoteInput
import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import com.nedrichards.plexwear.R
import com.nedrichards.plexwear.offline.OfflineQuality
import com.nedrichards.plexwear.playback.PlaybackStatus
import com.nedrichards.plexwear.data.PlexPlaylist
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.BrowseItem
import com.nedrichards.plexwear.data.PlexSession
import com.nedrichards.plexwear.data.PlexTrack
import kotlinx.coroutines.flow.collect

@Composable
fun PlexWearApp(viewModel: PlexWearViewModel) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val searchLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
  ) { activityResult ->
    val query = activityResult.data
      ?.let(RemoteInput::getResultsFromIntent)
      ?.getCharSequence(KEY_SEARCH_QUERY)
      ?.toString()
    if (query != null) {
      viewModel.setSearchQuery(query)
    }
  }

  MaterialTheme {
    AppScaffold {
      PlexWearScreen(
        state = state,
        onBack = viewModel::navigateBack,
        onSettings = viewModel::openSettings,
        onReset = viewModel::resetAuth,
        onStartPinAuth = viewModel::startPinAuth,
        onCancelPinAuth = viewModel::cancelPinAuth,
        onSearch = { searchLauncher.launch(searchInputIntent(context)) },
        onClearSearch = viewModel::clearSearchQuery,
        onLoadMore = viewModel::loadMore,
        onRetryPage = viewModel::retryPage,
        onItemClick = { item ->
          when (item) {
            is BrowseItem.LibraryItem -> viewModel.loadAlbums(item.library)
            is BrowseItem.AlbumItem -> viewModel.loadAlbumTracks(item.album)
            is BrowseItem.PlaylistItem -> viewModel.loadPlaylistTracks(item.playlist)
            is BrowseItem.TrackItem -> viewModel.openTrack(item.track)
          }
        },
        onSelectTrack = viewModel::openTrack,
        onPlay = { offerAudioOutput(context); viewModel.play(it) },
        onPlayAll = { offerAudioOutput(context); viewModel.playAll(it) },
        onDownloadTrack = viewModel::downloadTrack,
        onDownloadTracks = viewModel::downloadTracks,
        onCurrentPlayback = {
          viewModel.openCurrentPlayback()
          if (state.playbackPaused) { offerAudioOutput(context); viewModel.togglePlayback() }
        },
        onTogglePlayback = {
          if (state.playbackPaused || state.playbackStatus in listOf(PlaybackStatus.Ended, PlaybackStatus.Failed)) offerAudioOutput(context)
          viewModel.togglePlayback()
        },
        onPrevious = viewModel::skipToPrevious,
        onNext = viewModel::skipToNext,
        onPlaylists = viewModel::loadPlaylists,
        onSessions = viewModel::loadSessions,
        onToggleSessionPlayback = viewModel::toggleSessionPlayback,
        onCycleOfflineQuality = viewModel::cycleOfflineQuality,
        onClearOfflineCache = viewModel::clearOfflineCache,
        onDownloads = viewModel::openDownloads,
        onDownloadCollection = viewModel::openDownloadCollection,
        onRemoveDownload = viewModel::removeDownload,
        onCancelDownloads = viewModel::cancelDownloads,
        onRetryDownloads = viewModel::retryDownloads,
        onRefreshHome = viewModel::refreshHome,
        onQuality = viewModel::setOfflineQuality,
        onAutoCache = viewModel::toggleAutoCache,
        onServer = viewModel::selectServer,
        onPin = viewModel::togglePlaylistPin,
      )
    }
  }
}

@Composable
internal fun PlexWearScreen(
  state: PlexWearUiState,
  onBack: () -> Unit = {},
  onSettings: () -> Unit = {},
  onReset: () -> Unit = {},
  onStartPinAuth: () -> Unit = {},
  onCancelPinAuth: () -> Unit = {},
  onSearch: () -> Unit = {},
  onClearSearch: () -> Unit = {},
  onLoadMore: () -> Unit = {},
  onRetryPage: () -> Unit = onLoadMore,
  onItemClick: (BrowseItem) -> Unit = {},
  onSelectTrack: (PlexTrack) -> Unit = {},
  onPlay: (PlexTrack) -> Unit = {},
  onPlayAll: (List<PlexTrack>) -> Unit = {},
  onDownloadTrack: (PlexTrack) -> Unit = {},
  onDownloadTracks: (List<PlexTrack>) -> Unit = {},
  onCurrentPlayback: () -> Unit = {},
  onTogglePlayback: () -> Unit = {},
  onPrevious: () -> Unit = {},
  onNext: () -> Unit = {},
  onPlaylists: () -> Unit = {},
  onSessions: () -> Unit = {},
  onToggleSessionPlayback: (PlexSession) -> Unit = {},
  onCycleOfflineQuality: () -> Unit = {},
  onClearOfflineCache: () -> Unit = {},
  onDownloads: () -> Unit = {},
  onDownloadCollection: (String) -> Unit = {},
  onRemoveDownload: (PlexTrack) -> Unit = {},
  onCancelDownloads: () -> Unit = {},
  onRetryDownloads: () -> Unit = {},
  onRefreshHome: () -> Unit = {},
  onQuality: (OfflineQuality) -> Unit = {},
  onAutoCache: () -> Unit = {},
  onServer: (String) -> Unit = {},
  onPin: (PlexPlaylist) -> Unit = {},
) {
  val stateHolder = rememberSaveableStateHolder()
  // Authentication changes the Home structure; its old onboarding anchor must not carry into music.
  val accountScrollIdentity = remember(state.credentials) {
    "${state.credentials.cacheScope}:${state.configured}"
  }
  stateHolder.SaveableStateProvider("$accountScrollIdentity:${state.destinationId}") {

  val scrollState = rememberTransformingLazyColumnState()
  val filteredBrowseItems = remember(state.items, state.searchQuery) {
    filterBrowseItems(state.items, state.searchQuery)
  }
  val filteredTracks = remember(state.tracks, state.searchQuery) {
    filterTracks(state.tracks, state.searchQuery)
  }
  val occurrences = remember(state.tracks) { trackOccurrences(state.tracks) }
  val visibleOccurrences = remember(occurrences, filteredTracks, state.searchQuery) {
    if (state.searchQuery.isBlank()) occurrences else {
      val matches = filteredTracks.toHashSet()
      occurrences.filter { it.second in matches }
    }
  }
  val showTitle = state.showScreenTitle()

  BackHandler(enabled = state.screen != Screen.Home) { onBack() }

  LaunchedEffect(state.screen, state.canLoadMore, state.loadingMore, state.searchQuery, state.pageError) {
    snapshotFlow {
      val layout = scrollState.layoutInfo
      layout.visibleItems.lastOrNull()?.index to layout.totalItemsCount
    }.collect { (lastVisibleIndex, totalItems) ->
      if (
        !state.searching && state.pageError == null && state.canLoadMore &&
        !state.loadingMore &&
        totalItems > 0 &&
        (lastVisibleIndex ?: -1) >= totalItems - AUTO_LOAD_THRESHOLD_ITEMS
      ) {
        onLoadMore()
      }
    }
  }

  // Match the pinned Wear library's 10% vertical inset using public configuration APIs.
  val verticalContentPadding = (LocalConfiguration.current.screenHeightDp * 0.1f).dp
  ScreenScaffold(
    scrollState = scrollState,
    contentPadding = PaddingValues(horizontal = 18.dp, vertical = verticalContentPadding),
  ) { scaffoldPadding ->
  TransformingLazyColumn(
    state = scrollState,
    modifier = Modifier.fillMaxSize().testTag("destination-list"),
    contentPadding = scaffoldPadding,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (showTitle) {
      item(key = "title") {
        Text(
          text = state.title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    if (state.loading) {
      item(key = "loading-spacer") { Spacer(Modifier.height(18.dp)) }
      item(key = "loading") { CircularProgressIndicator() }
    } else {
      state.error?.let { error ->
        item(key = "error") { StatusText(error) }
      }

      if (!state.configured && state.screen !in listOf(Screen.Settings, Screen.Downloads, Screen.Track, Screen.NowPlaying, Screen.Tracks)) {
        item(key = "onboarding") {
          ListItemGroup {
            OnboardingContent(state.auth, onStartPinAuth, onCancelPinAuth, onSettings)
            AppButton(stringResource(R.string.downloads), onDownloads)
          }
        }
      } else {
        if (state.topLevelActions().isNotEmpty()) {
          item(key = "top-level-actions") {
            TopLevelActions(state, onCurrentPlayback)
          }
        }

        when (state.screen) {
          Screen.Home -> homeContent(state, onItemClick, onSelectTrack, onPlaylists, onSessions, onSettings, onDownloads, onRefreshHome)
          Screen.Albums, Screen.Playlists -> browseContent(
            state,
            filteredBrowseItems,
            onItemClick,
            onSearch,
            onClearSearch,
            onLoadMore,
            onRetryPage,
            onPin,
          )
          Screen.Tracks -> tracksContent(
            state,
            filteredTracks,
            visibleOccurrences,
            onSelectTrack,
            onPlayAll,
            onDownloadTracks,
            onSearch,
            onClearSearch,
            onLoadMore,
            onRetryPage,
          )
          Screen.Track -> item(key = "track-content") {
            ListItemGroup {
              TrackContent(state, onPlay, onDownloadTrack, onRemoveDownload)
            }
          }
          Screen.NowPlaying -> item(key = "now-playing-content") {
            ListItemGroup {
              NowPlayingContent(state, onTogglePlayback, onPrevious, onNext)
            }
          }
          Screen.Downloads -> downloadsContent(state, onDownloadCollection, onSelectTrack, onCancelDownloads, onRetryDownloads)
          Screen.Sessions -> sessionsContent(state, onToggleSessionPlayback, onSessions)
          Screen.Settings -> settingsContent(state, onReset, onClearOfflineCache, onQuality, onAutoCache, onServer)
        }
      }
    }
  }
  }
  }
}

@Composable
private fun ListItemGroup(content: @Composable () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    content()
  }
}

@Composable
private fun TopLevelActions(
  state: PlexWearUiState,
  onCurrentPlayback: () -> Unit,
) {
  val actions = state.topLevelActions()
  if (actions.isEmpty()) return
  AppButton(
    text = stringResource(if (state.playbackPaused) R.string.resume else R.string.now_playing),
    onClick = onCurrentPlayback,
  )
}

private fun TransformingLazyColumnScope.homeContent(
  state: PlexWearUiState,
  onItemClick: (BrowseItem) -> Unit,
  onPlay: (PlexTrack) -> Unit,
  onPlaylists: () -> Unit,
  onSessions: () -> Unit,
  onSettings: () -> Unit,
  onDownloads: () -> Unit,
  onRefreshHome: () -> Unit,
) {
  item(key = "home-downloads") { NavigationRow(stringResource(R.string.downloads), stringResource(R.string.listen_offline), RowIcon.Track, onDownloads) }
  items(state.pinnedPlaylists, key = { "pin-${it.key}" }) { playlist ->
    BrowseRow(state.credentials, BrowseItem.PlaylistItem(playlist), onItemClick)
  }
  item(key = "home-refresh") { AppButton(stringResource(R.string.refresh), onRefreshHome, enabled = !state.refreshingHome) }
  item(key = "home-playlists") {
    NavigationRow(
      title = stringResource(R.string.playlists),
      subtitle = stringResource(R.string.saved_playlists),
      icon = RowIcon.Playlist,
      onClick = onPlaylists,
    )
  }
  items(state.items, key = { it.browseStableKey() }) { item ->
    BrowseRow(state.credentials, item, onItemClick)
  }
  if (state.tracks.isNotEmpty()) {
    item(key = "recent-label") { SectionLabel(stringResource(R.string.recent)) }
    items(state.tracks, key = { "recent-${it.ratingKey}" }) { track ->
      TrackRow(state.credentials, track, onPlay)
    }
  }
  item(key = "home-sessions") {
    NavigationRow(
      title = stringResource(R.string.active_streams),
      subtitle = stringResource(R.string.pause_other_players),
      icon = RowIcon.Track,
      onClick = onSessions,
    )
  }
  item(key = "home-settings") {
    NavigationRow(
      title = stringResource(R.string.settings),
      subtitle = stringResource(R.string.account_server),
      icon = RowIcon.Settings,
      onClick = onSettings,
    )
  }
}

private fun TransformingLazyColumnScope.sessionsContent(
  state: PlexWearUiState,
  onToggleSessionPlayback: (PlexSession) -> Unit,
  onRefresh: () -> Unit,
) {
  item(key = "sessions-refresh") {
    IconActionButton(icon = ActionIcon.Refresh, label = stringResource(R.string.refresh), onClick = onRefresh)
  }
  if (state.sessions.isEmpty()) {
    item(key = "sessions-empty") { StatusText(stringResource(R.string.no_active_streams)) }
  }
  items(state.sessions, key = { it.sessionKey }) { session ->
    SessionRow(session = session, onTogglePlayback = { onToggleSessionPlayback(session) })
  }
}

@Composable
private fun OnboardingContent(
  auth: PlexAuthUiState,
  onStartPinAuth: () -> Unit,
  onCancelPinAuth: () -> Unit,
  onSettings: () -> Unit,
) {
  auth.message?.let { StatusText(it) }
  auth.pinCode?.let { code ->
    Text(
      text = code,
      style = MaterialTheme.typography.titleLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  if (auth.waiting) {
    CircularProgressIndicator(modifier = Modifier.size(24.dp))
    AppButton(text = stringResource(R.string.cancel), onClick = onCancelPinAuth)
  } else {
    AppButton(text = stringResource(R.string.sign_in), onClick = onStartPinAuth)
    AppButton(text = stringResource(R.string.settings), onClick = onSettings)
  }
}

private fun TransformingLazyColumnScope.browseContent(
  state: PlexWearUiState,
  browseItems: List<BrowseItem>,
  onItemClick: (BrowseItem) -> Unit,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
  onLoadMore: () -> Unit,
  onRetryPage: () -> Unit,
  onPin: (PlexPlaylist) -> Unit,
) {
  item(key = "search-controls") {
    SearchControls(state.searchQuery, onSearch, onClearSearch)
  }
  if (browseItems.isEmpty()) {
    item(key = "browse-empty") { StatusText(if (state.searching) stringResource(R.string.no_matches) else stringResource(R.string.nothing_found)) }
  }
  items(browseItems, key = { it.browseStableKey() }) { item ->
    ListItemGroup {
      BrowseRow(state.credentials, item, onItemClick)
      if (item is BrowseItem.PlaylistItem) AppButton(stringResource(if (state.pinnedPlaylists.any { it.key == item.playlist.key }) R.string.unpin else R.string.pin), { onPin(item.playlist) })
    }
  }
  loadMoreItem(state, onLoadMore, onRetryPage)
}

private fun TransformingLazyColumnScope.tracksContent(
  state: PlexWearUiState,
  tracks: List<PlexTrack>,
  occurrences: List<Pair<String, PlexTrack>>,
  onSelectTrack: (PlexTrack) -> Unit,
  onPlayAll: (List<PlexTrack>) -> Unit,
  onDownloadTracks: (List<PlexTrack>) -> Unit,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
  onLoadMore: () -> Unit,
  onRetryPage: () -> Unit,
) {
  state.searchQuery.takeIf { it.isNotBlank() }?.let { query ->
    item(key = "search-query") { StatusText(stringResource(R.string.search_query, query)) }
  }
  if (tracks.isEmpty()) {
    item(key = "tracks-empty") { StatusText(if (state.searching) stringResource(R.string.no_matches) else stringResource(R.string.no_tracks)) }
  }
  item(key = "track-scope") {
    StatusText(stringResource(R.string.loaded_track_count, state.tracks.size))
  }
  if (tracks.isNotEmpty() || state.tracks.isNotEmpty()) {
    item(key = "track-actions") {
      TrackListActions(
        searching = state.searching,
        onSearch = onSearch,
        onClearSearch = onClearSearch,
        onPlayAll = { onPlayAll(tracks) },
        onDownloadTracks = { onDownloadTracks(tracks) },
        canPlay = tracks.isNotEmpty(),
        canDownload = tracks.isNotEmpty(),
      )
    }
  }
  items(occurrences, key = { it.first }) { (_, track) ->
    TrackRow(
      credentials = state.credentials,
      track = track,
      onPlay = onSelectTrack,
      offlineStatus = state.offlineStatus(track),
    )
  }
  loadMoreItem(state, onLoadMore, onRetryPage)
}

private fun TransformingLazyColumnScope.loadMoreItem(state: PlexWearUiState, onLoadMore: () -> Unit, onRetryPage: () -> Unit) {
  state.pageError?.let { error -> item(key = "page-error") { ListItemGroup { StatusText(error); AppButton(stringResource(R.string.retry), onRetryPage) } } }
  if (state.canLoadMore && !state.loadingMore && state.pageError == null) item(key = "load-more") {
    AppButton(stringResource(if (state.searching) R.string.search_more else R.string.load_more), onLoadMore)
  }
  if (state.loadingMore) {
    item(key = "load-more-progress") { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
  }
}

@Composable
private fun TrackContent(
  state: PlexWearUiState,
  onPlay: (PlexTrack) -> Unit,
  onDownloadTrack: (PlexTrack) -> Unit,
  onRemoveDownload: (PlexTrack) -> Unit,
) {
  val track = state.selectedTrack
  if (track == null) {
    StatusText(stringResource(R.string.no_track_selected))
    return
  }

  track.artist?.let { StatusText(it) }
  track.album?.let { StatusText(it) }
  track.durationMs?.let { StatusText(it.formatDuration()) }
  state.offlineStatus(track)?.let { StatusText(it) }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
  ) {
    IconActionButton(
      icon = ActionIcon.Play,
      label = stringResource(R.string.play),
      onClick = { onPlay(track) },
      size = 48.dp,
    )
    if (!state.isDownloading(track)) {
      IconActionButton(
        icon = ActionIcon.Download,
        label = stringResource(R.string.download_quality_action, state.offlineQuality.bitrateKbps),
        onClick = { onDownloadTrack(track) },
        size = 48.dp,
      )
    }
  }
  if (state.downloadedQuality(track) != null) AppButton(stringResource(R.string.remove_download), { onRemoveDownload(track) })
}

@Composable
private fun TrackListActions(
  searching: Boolean,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
  onPlayAll: () -> Unit,
  onDownloadTracks: () -> Unit,
  canPlay: Boolean,
  canDownload: Boolean,
) {
  ListItemGroup {
    IconActionButton(
      icon = if (searching) ActionIcon.Clear else ActionIcon.Search,
      label = stringResource(if (searching) R.string.clear_search else R.string.search),
      onClick = if (searching) onClearSearch else onSearch,
    )
    AppButton(
      text = stringResource(if (searching) R.string.play_results else R.string.play_collection),
      onClick = onPlayAll,
      enabled = canPlay,
    )
    AppButton(
      text = stringResource(if (searching) R.string.download_results else R.string.download_collection),
      onClick = onDownloadTracks,
      enabled = canDownload,
    )
  }
}

@Composable
private fun SearchControls(
  query: String,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    query.takeIf { it.isNotBlank() }?.let {
      Text(
        text = stringResource(R.string.search_query, it),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (query.isNotBlank()) {
        IconActionButton(
          icon = ActionIcon.Clear,
          label = stringResource(R.string.clear_search),
          onClick = onClearSearch,
        )
      }
      IconActionButton(
        icon = ActionIcon.Search,
        label = stringResource(R.string.search),
        onClick = onSearch,
      )
    }
  }
}

@Composable
private fun NowPlayingContent(
  state: PlexWearUiState,
  onTogglePlayback: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  val context = LocalContext.current
  state.nowPlaying?.thumb?.let { PlexArtwork(state.credentials, it, null, size = 64.dp) }
  StatusText(stringResource(when (state.playbackStatus) {
    PlaybackStatus.Idle -> R.string.playback_idle
    PlaybackStatus.Starting -> R.string.playback_starting
    PlaybackStatus.Buffering -> R.string.playback_buffering
    PlaybackStatus.Playing -> R.string.playback_playing
    PlaybackStatus.Paused -> R.string.playback_paused
    PlaybackStatus.Ended -> R.string.playback_ended
    PlaybackStatus.Failed -> R.string.playback_failed
  }))
  StatusText("${state.playbackPositionMs.formatDuration()} / ${state.playbackDurationMs.formatDuration()}")
  state.nowPlayingContext?.let {
    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
  if (state.nowPlayingTrackCount > 1) {
    StatusText(stringResource(R.string.queue_position, state.nowPlayingIndex + 1, state.nowPlayingTrackCount))
  }
  state.nowPlaying?.let {
    Text(it.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    it.artist?.let { artist -> StatusText(artist) }
  }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
  ) {
    IconActionButton(
      icon = ActionIcon.Previous,
      label = stringResource(R.string.previous),
      onClick = onPrevious,
      enabled = state.canPlayPrevious,
    )
    IconActionButton(
      icon = if (state.playbackPaused) ActionIcon.Play else ActionIcon.Pause,
      label = if (state.playbackPaused) stringResource(R.string.resume) else stringResource(R.string.pause),
      onClick = onTogglePlayback,
      enabled = state.playbackStatus != PlaybackStatus.Starting,
      size = 56.dp,
    )
    IconActionButton(
      icon = ActionIcon.Next,
      label = stringResource(R.string.next),
      onClick = onNext,
      enabled = state.canPlayNext,
    )
  }
  AppButton(stringResource(R.string.audio_output), { launchAudioOutput(context) })
}

private fun TransformingLazyColumnScope.downloadsContent(state: PlexWearUiState, onCollection: (String) -> Unit, onTrack: (PlexTrack) -> Unit, onCancel: () -> Unit, onRetry: () -> Unit) {
  state.downloadProgress?.let { progress -> item(key = "download-progress") { StatusText(progress) } }
  if (state.downloadBusy) item(key = "download-cancel") { AppButton(stringResource(R.string.cancel), onCancel) }
  state.downloadError?.let { error -> item(key = "download-error") { ListItemGroup { StatusText(error); AppButton(stringResource(R.string.retry), onRetry) } } }
  if (state.downloadedTracks.isEmpty()) item(key = "downloads-empty") { StatusText(stringResource(R.string.no_downloads)) }
  items(state.downloadedCollections.keys.toList(), key = { "collection-$it" }) { title ->
    NavigationRow(title, stringResource(R.string.track_count, state.downloadedCollections[title].orEmpty().size), RowIcon.Playlist, { onCollection(title) })
  }
  items(state.downloadedTracks, key = { "download-${it.ratingKey}" }) { track -> TrackRow(state.credentials, track, onTrack, state.offlineStatus(track)) }
}

internal fun trackOccurrences(tracks: List<PlexTrack>): List<Pair<String, PlexTrack>> = tracks.mapIndexed { index, track ->
  "${track.ratingKey}:${track.playlistEntryId.orEmpty()}:$index" to track
}

private fun TransformingLazyColumnScope.settingsContent(
  state: PlexWearUiState,
  onReset: () -> Unit,
  onClearOfflineCache: () -> Unit,
  onQuality: (OfflineQuality) -> Unit,
  onAutoCache: () -> Unit,
  onServer: (String) -> Unit,
) {
  item(key = "account-status") { StatusText(stringResource(if (state.configured) R.string.credentials_saved else R.string.credentials_missing)) }
  item(key = "download-storage") { StatusText(stringResource(R.string.download_storage, state.offlineCacheBytes.formatBytes())) }
  item(key = "quality-label") { SectionLabel(stringResource(R.string.download_quality)) }
  items(OfflineQuality.entries.toList(), key = { "quality-${it.name}" }) { quality ->
    AppButton(stringResource(R.string.quality_choice, if (quality == state.offlineQuality) "✓ " else "", quality.bitrateKbps), { onQuality(quality) })
  }
  item(key = "automatic-cache") { AppButton(stringResource(if (state.autoCacheEnabled) R.string.auto_cache_on else R.string.auto_cache_off), onAutoCache) }
  item(key = "automatic-cache-help") { StatusText(stringResource(R.string.auto_cache_help)) }
  if (state.availableServers.isNotEmpty()) item(key = "server-label") { SectionLabel(stringResource(R.string.server)) }
  items(state.availableServers, key = { "server-${it.id}" }) { server ->
    AppButton(server.name, { onServer(server.id) }, enabled = server.credentials.cacheScope != state.credentials.cacheScope)
  }
  item(key = "clear-downloads") { AppButton(stringResource(R.string.clear_downloads), onClearOfflineCache, enabled = state.offlineCacheBytes > 0 || state.downloadBusy) }
  item(key = "sign-out") { AppButton(stringResource(R.string.sign_out), onReset) }
}

@Composable
private fun BrowseRow(credentials: PlexCredentials, item: BrowseItem, onClick: (BrowseItem) -> Unit) {
  AppRow(
    credentials = credentials,
    title = item.browseRowTitle(),
    subtitle = if (item is BrowseItem.LibraryItem) stringResource(R.string.browse_albums) else item.browseRowSubtitle(),
    artworkPath = item.browseRowArtworkPath(),
    fallbackIcon = item.browseRowIcon(),
    onClick = { onClick(item) },
  )
}

@Composable
private fun TrackRow(
  credentials: PlexCredentials,
  track: PlexTrack,
  onPlay: (PlexTrack) -> Unit,
  offlineStatus: String? = null,
) {
  AppRow(
    credentials = credentials,
    title = track.title,
    subtitle = listOfNotNull(track.artist, track.durationMs?.formatDuration(), offlineStatus)
      .joinToString(" - ")
      .ifBlank { null },
    artworkPath = track.thumb,
    fallbackIcon = RowIcon.Track,
    onClick = { onPlay(track) },
  )
}

@Composable
private fun SessionRow(
  session: PlexSession,
  onTogglePlayback: () -> Unit,
) {
  val subtitle = listOfNotNull(
    if (session.paused) stringResource(R.string.resume) else stringResource(R.string.pause),
    session.playerTitle,
    session.subtitle,
    session.state,
  ).joinToString(" - ").ifBlank { null }
  Button(
    onClick = onTogglePlayback,
    enabled = session.canTogglePlayback,
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.filledTonalButtonColors(),
  ) {
    Column(Modifier.fillMaxWidth()) {
      Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
      subtitle?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun AppRow(
  credentials: PlexCredentials,
  title: String,
  subtitle: String?,
  artworkPath: String?,
  fallbackIcon: RowIcon,
  onClick: () -> Unit,
) {
  Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.filledTonalButtonColors(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (artworkPath == null) {
        IconBadge(icon = fallbackIcon)
      } else {
        PlexArtwork(
          credentials = credentials,
          thumbPath = artworkPath,
          contentDescription = null,
        )
      }
      Column(Modifier.weight(1f)) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun NavigationRow(
  title: String,
  subtitle: String,
  icon: RowIcon,
  onClick: () -> Unit,
) {
  AppRow(
    credentials = PlexCredentials("", ""),
    title = title,
    subtitle = subtitle,
    artworkPath = null,
    fallbackIcon = icon,
    onClick = onClick,
  )
}

@Composable
private fun IconBadge(
  icon: RowIcon,
  modifier: Modifier = Modifier,
) {
  val background = MaterialTheme.colorScheme.secondaryContainer
  val foreground = MaterialTheme.colorScheme.onSecondaryContainer

  Box(
    modifier = modifier
      .size(24.dp)
      .clip(CircleShape)
      .background(background),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(Modifier.size(16.dp)) {
      when (icon) {
        RowIcon.Album -> drawAlbumIcon(foreground)
        RowIcon.Library -> drawLibraryIcon(foreground)
        RowIcon.Playlist -> drawPlaylistIcon(foreground)
        RowIcon.Settings -> drawSettingsIcon(foreground)
        RowIcon.Track -> drawTrackIcon(foreground)
      }
    }
  }
}

@Composable
private fun IconActionButton(
  icon: ActionIcon,
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  size: Dp = 48.dp,
) {
  val foreground = if (enabled) {
    MaterialTheme.colorScheme.onSecondaryContainer
  } else {
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
  }
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier
      .size(size)
      .semantics { contentDescription = label },
    colors = ButtonDefaults.filledTonalButtonColors(),
  ) {
    Canvas(Modifier.size(18.dp)) {
      when (icon) {
        ActionIcon.Clear -> drawClearIcon(foreground)
        ActionIcon.Download -> drawDownloadIcon(foreground)
        ActionIcon.Next -> drawNextIcon(foreground)
        ActionIcon.Pause -> drawPauseIcon(foreground)
        ActionIcon.Play -> drawPlayIcon(foreground)
        ActionIcon.Previous -> drawPreviousIcon(foreground)
        ActionIcon.Refresh -> drawRefreshIcon(foreground)
        ActionIcon.Search -> drawSearchIcon(foreground)
      }
    }
  }
}

@Composable
private fun AppButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun StatusText(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    maxLines = 3,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun PlexWearUiState.offlineStatus(track: PlexTrack): String? = when {
  isDownloading(track) -> stringResource(R.string.downloading)
  downloadedQuality(track) != null -> stringResource(R.string.downloaded_quality, downloadedQuality(track)?.bitrateKbps ?: 0)
  else -> null
}

private fun Long.formatBytes(): String = when {
  this <= 0L -> "0 MB"
  this < 1024L * 1024L -> "${(this / 1024L).coerceAtLeast(1L)} KB"
  else -> "${this / (1024L * 1024L)} MB"
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

internal fun BrowseItem.browseRowTitle(): String = when (this) {
  is BrowseItem.LibraryItem -> library.title
  is BrowseItem.AlbumItem -> album.title
  is BrowseItem.PlaylistItem -> playlist.title
  is BrowseItem.TrackItem -> track.title
}

internal fun BrowseItem.browseRowSubtitle(): String? = when (this) {
  is BrowseItem.LibraryItem -> "Browse albums"
  is BrowseItem.AlbumItem -> album.artist
  is BrowseItem.PlaylistItem -> playlist.durationMs?.formatDuration()
  is BrowseItem.TrackItem -> track.artist
}

private fun BrowseItem.browseRowArtworkPath(): String? = when (this) {
  is BrowseItem.LibraryItem -> null
  is BrowseItem.AlbumItem -> album.thumb
  is BrowseItem.PlaylistItem -> playlist.thumb
  is BrowseItem.TrackItem -> track.thumb
}

private fun BrowseItem.browseRowIcon(): RowIcon = when (this) {
  is BrowseItem.LibraryItem -> RowIcon.Library
  is BrowseItem.AlbumItem -> RowIcon.Album
  is BrowseItem.PlaylistItem -> RowIcon.Playlist
  is BrowseItem.TrackItem -> RowIcon.Track
}

private fun BrowseItem.browseStableKey(): String = when (this) {
  is BrowseItem.LibraryItem -> "library-${library.key}"
  is BrowseItem.AlbumItem -> "album-${album.key}"
  is BrowseItem.PlaylistItem -> "playlist-${playlist.key}"
  is BrowseItem.TrackItem -> "track-${track.ratingKey}"
}

private enum class RowIcon {
  Album,
  Library,
  Playlist,
  Settings,
  Track,
}

private enum class ActionIcon {
  Clear,
  Download,
  Next,
  Pause,
  Play,
  Previous,
  Refresh,
  Search,
}

internal enum class TopLevelAction {
  NowPlaying;

  val label: String
    get() = when (this) {
      NowPlaying -> "Now playing"
    }
}

internal fun PlexWearUiState.topLevelActions(): List<TopLevelAction> = buildList {
  if (canOpenCurrentPlayback) add(TopLevelAction.NowPlaying)
}

private fun PlexWearUiState.showScreenTitle(): Boolean =
  loading || !configured || screen != Screen.Home

private fun DrawScope.drawAlbumIcon(color: Color) {
  val stroke = Stroke(width = size.minDimension * 0.1f, cap = StrokeCap.Round)
  drawCircle(
    color = color,
    radius = size.minDimension * 0.34f,
    center = center,
    style = stroke,
  )
  drawCircle(
    color = color,
    radius = size.minDimension * 0.08f,
    center = center,
  )
}

private fun DrawScope.drawLibraryIcon(color: Color) {
  val stroke = Stroke(width = size.minDimension * 0.1f, cap = StrokeCap.Round)
  drawRoundRect(
    color = color,
    topLeft = Offset(size.width * 0.2f, size.height * 0.17f),
    size = Size(size.width * 0.6f, size.height * 0.66f),
    cornerRadius = CornerRadius(size.minDimension * 0.08f),
    style = stroke,
  )
  drawCircle(
    color = color,
    radius = size.minDimension * 0.13f,
    center = Offset(size.width * 0.5f, size.height * 0.5f),
    style = stroke,
  )
}

private fun DrawScope.drawPlaylistIcon(color: Color) {
  val stroke = Stroke(width = size.minDimension * 0.1f, cap = StrokeCap.Round)
  listOf(0.3f, 0.5f, 0.7f).forEach { y ->
    drawCircle(
      color = color,
      radius = size.minDimension * 0.04f,
      center = Offset(size.width * 0.23f, size.height * y),
    )
    drawLine(
      color = color,
      start = Offset(size.width * 0.38f, size.height * y),
      end = Offset(size.width * 0.78f, size.height * y),
      strokeWidth = stroke.width,
      cap = StrokeCap.Round,
    )
  }
}

private fun DrawScope.drawSettingsIcon(color: Color) {
  val stroke = Stroke(width = size.minDimension * 0.1f, cap = StrokeCap.Round)
  drawCircle(
    color = color,
    radius = size.minDimension * 0.22f,
    center = center,
    style = stroke,
  )
  for (i in 0 until 8) {
    val angle = Math.toRadians((i * 45).toDouble())
    val inner = size.minDimension * 0.33f
    val outer = size.minDimension * 0.43f
    drawLine(
      color = color,
      start = Offset(
        x = center.x + kotlin.math.cos(angle).toFloat() * inner,
        y = center.y + kotlin.math.sin(angle).toFloat() * inner,
      ),
      end = Offset(
        x = center.x + kotlin.math.cos(angle).toFloat() * outer,
        y = center.y + kotlin.math.sin(angle).toFloat() * outer,
      ),
      strokeWidth = stroke.width,
      cap = StrokeCap.Round,
    )
  }
}

private fun DrawScope.drawTrackIcon(color: Color) {
  val strokeWidth = size.minDimension * 0.1f
  drawLine(
    color = color,
    start = Offset(size.width * 0.62f, size.height * 0.2f),
    end = Offset(size.width * 0.62f, size.height * 0.68f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
  drawLine(
    color = color,
    start = Offset(size.width * 0.62f, size.height * 0.2f),
    end = Offset(size.width * 0.8f, size.height * 0.28f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
  drawCircle(
    color = color,
    radius = size.minDimension * 0.14f,
    center = Offset(size.width * 0.42f, size.height * 0.7f),
    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
  )
}

private fun DrawScope.drawSearchIcon(color: Color) {
  val strokeWidth = size.minDimension * 0.12f
  drawCircle(
    color = color,
    radius = size.minDimension * 0.28f,
    center = Offset(size.width * 0.43f, size.height * 0.43f),
    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
  )
  drawLine(
    color = color,
    start = Offset(size.width * 0.62f, size.height * 0.62f),
    end = Offset(size.width * 0.82f, size.height * 0.82f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
}

private fun DrawScope.drawClearIcon(color: Color) {
  val strokeWidth = size.minDimension * 0.12f
  drawLine(
    color = color,
    start = Offset(size.width * 0.28f, size.height * 0.28f),
    end = Offset(size.width * 0.72f, size.height * 0.72f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
  drawLine(
    color = color,
    start = Offset(size.width * 0.72f, size.height * 0.28f),
    end = Offset(size.width * 0.28f, size.height * 0.72f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
}

private fun DrawScope.drawPlayIcon(color: Color) {
  drawPath(
    path = Path().apply {
      moveTo(size.width * 0.35f, size.height * 0.22f)
      lineTo(size.width * 0.35f, size.height * 0.78f)
      lineTo(size.width * 0.78f, size.height * 0.5f)
      close()
    },
    color = color,
  )
}

private fun DrawScope.drawPauseIcon(color: Color) {
  val corner = CornerRadius(size.minDimension * 0.04f)
  drawRoundRect(
    color = color,
    topLeft = Offset(size.width * 0.3f, size.height * 0.22f),
    size = Size(size.width * 0.14f, size.height * 0.56f),
    cornerRadius = corner,
  )
  drawRoundRect(
    color = color,
    topLeft = Offset(size.width * 0.56f, size.height * 0.22f),
    size = Size(size.width * 0.14f, size.height * 0.56f),
    cornerRadius = corner,
  )
}

private fun DrawScope.drawPreviousIcon(color: Color) {
  val strokeWidth = size.minDimension * 0.1f
  drawLine(
    color = color,
    start = Offset(size.width * 0.25f, size.height * 0.24f),
    end = Offset(size.width * 0.25f, size.height * 0.76f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
  drawPath(
    path = Path().apply {
      moveTo(size.width * 0.74f, size.height * 0.24f)
      lineTo(size.width * 0.34f, size.height * 0.5f)
      lineTo(size.width * 0.74f, size.height * 0.76f)
      close()
    },
    color = color,
  )
}

private fun DrawScope.drawNextIcon(color: Color) {
  val strokeWidth = size.minDimension * 0.1f
  drawPath(
    path = Path().apply {
      moveTo(size.width * 0.26f, size.height * 0.24f)
      lineTo(size.width * 0.66f, size.height * 0.5f)
      lineTo(size.width * 0.26f, size.height * 0.76f)
      close()
    },
    color = color,
  )
  drawLine(
    color = color,
    start = Offset(size.width * 0.75f, size.height * 0.24f),
    end = Offset(size.width * 0.75f, size.height * 0.76f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
}

private fun DrawScope.drawDownloadIcon(color: Color) {
  val strokeWidth = size.minDimension * 0.11f
  drawLine(
    color = color,
    start = Offset(size.width * 0.5f, size.height * 0.22f),
    end = Offset(size.width * 0.5f, size.height * 0.58f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
  drawLine(
    color = color,
    start = Offset(size.width * 0.32f, size.height * 0.43f),
    end = Offset(size.width * 0.5f, size.height * 0.61f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
  drawLine(
    color = color,
    start = Offset(size.width * 0.68f, size.height * 0.43f),
    end = Offset(size.width * 0.5f, size.height * 0.61f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
  drawLine(
    color = color,
    start = Offset(size.width * 0.28f, size.height * 0.78f),
    end = Offset(size.width * 0.72f, size.height * 0.78f),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
  )
}

private fun DrawScope.drawRefreshIcon(color: Color) {
  val strokeWidth = size.minDimension * 0.1f
  drawArc(
    color = color,
    startAngle = 35f,
    sweepAngle = 285f,
    useCenter = false,
    topLeft = Offset(size.width * 0.22f, size.height * 0.22f),
    size = Size(size.width * 0.56f, size.height * 0.56f),
    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
  )
  drawPath(
    path = Path().apply {
      moveTo(size.width * 0.74f, size.height * 0.25f)
      lineTo(size.width * 0.78f, size.height * 0.48f)
      lineTo(size.width * 0.57f, size.height * 0.39f)
      close()
    },
    color = color,
  )
}

private fun Long.formatDuration(): String {
  val totalSeconds = this / 1000
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** Mirror the system output chooser with the documented Wear Bluetooth fallback. */
private fun launchAudioOutput(context: Context) {
  if (Build.VERSION.SDK_INT >= 34 && MediaRouter2.getInstance(context).showSystemOutputSwitcher()) return
  // Horologist OutputSwitcher uses the Settings media panel and selects its system handler.
  val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
    .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
  val handler = context.packageManager.queryIntentActivities(panel, 0).firstOrNull { info ->
    ((info.activityInfo?.applicationInfo?.flags ?: 0) and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
  }?.activityInfo
  if (handler != null) {
    panel.component = ComponentName(handler.packageName, handler.name)
    context.startActivity(panel)
  } else {
    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
      .putExtra("EXTRA_CONNECTION_ONLY", true)
      .putExtra("EXTRA_CLOSE_ON_CONNECT", true)
      .putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1))
  }
}

private fun offerAudioOutput(context: Context) {
  val outputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
  val external = outputs.any { it.type in setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
  ) }
  if (!external) launchAudioOutput(context)
}

private const val KEY_SEARCH_QUERY = "plex_search_query"
private const val AUTO_LOAD_THRESHOLD_ITEMS = 4

private fun searchInputIntent(context: android.content.Context) = RemoteInputIntentHelper.createActionRemoteInputIntent().apply {
  val remoteInputs = listOf(
    RemoteInput.Builder(KEY_SEARCH_QUERY)
      .setLabel(context.getString(R.string.search_plex))
      .setAllowFreeFormInput(true)
      .build(),
  )
  RemoteInputIntentHelper.putRemoteInputsExtra(this, remoteInputs)
  RemoteInputIntentHelper.putTitleExtra(this, context.getString(R.string.search))
  RemoteInputIntentHelper.putCancelLabelExtra(this, context.getString(R.string.cancel))
  RemoteInputIntentHelper.putConfirmLabelExtra(this, context.getString(R.string.search))
  RemoteInputIntentHelper.putInProgressLabelExtra(this, context.getString(R.string.searching))
}
