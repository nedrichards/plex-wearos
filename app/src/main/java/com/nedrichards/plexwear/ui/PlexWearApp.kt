package com.nedrichards.plexwear.ui

import android.app.RemoteInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.wear.compose.material3.TimeText
import androidx.wear.input.RemoteInputIntentHelper
import com.nedrichards.plexwear.BuildConfig
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.BrowseItem
import com.nedrichards.plexwear.data.PlexSession
import com.nedrichards.plexwear.data.PlexTrack
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@Composable
fun PlexWearApp(viewModel: PlexWearViewModel) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
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
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(AppBackground),
    ) {
      TimeText()
      PlexWearScreen(
        state = state,
        onHome = viewModel::loadHome,
        onBack = viewModel::navigateBack,
        onSettings = viewModel::openSettings,
        onReset = viewModel::resetAuth,
        onStartPinAuth = viewModel::startPinAuth,
        onCancelPinAuth = viewModel::cancelPinAuth,
        onSearch = { searchLauncher.launch(searchInputIntent()) },
        onClearSearch = viewModel::clearSearchQuery,
        onLoadMore = viewModel::loadMore,
        onItemClick = { item ->
          when (item) {
            is BrowseItem.LibraryItem -> viewModel.loadAlbums(item.library)
            is BrowseItem.AlbumItem -> viewModel.loadAlbumTracks(item.album)
            is BrowseItem.PlaylistItem -> viewModel.loadPlaylistTracks(item.playlist)
            is BrowseItem.TrackItem -> viewModel.openTrack(item.track)
          }
        },
        onSelectTrack = viewModel::openTrack,
        onTrackList = viewModel::openTrackList,
        onPlay = { viewModel.play(it) },
        onPlayAll = { viewModel.playAll(it) },
        onDownloadTrack = viewModel::downloadTrack,
        onDownloadTracks = viewModel::downloadTracks,
        onCurrentPlayback = viewModel::openCurrentPlayback,
        onTogglePlayback = viewModel::togglePlayback,
        onPrevious = viewModel::skipToPrevious,
        onNext = viewModel::skipToNext,
        onPlaylists = viewModel::loadPlaylists,
        onSessions = viewModel::loadSessions,
        onToggleSessionPlayback = viewModel::toggleSessionPlayback,
        onCycleOfflineQuality = viewModel::cycleOfflineQuality,
        onClearOfflineCache = viewModel::clearOfflineCache,
      )
    }
  }
}

@Composable
private fun PlexWearScreen(
  state: PlexWearUiState,
  onHome: () -> Unit,
  onBack: () -> Unit,
  onSettings: () -> Unit,
  onReset: () -> Unit,
  onStartPinAuth: () -> Unit,
  onCancelPinAuth: () -> Unit,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
  onLoadMore: () -> Unit,
  onItemClick: (BrowseItem) -> Unit,
  onSelectTrack: (PlexTrack) -> Unit,
  onTrackList: () -> Unit,
  onPlay: (PlexTrack) -> Unit,
  onPlayAll: (List<PlexTrack>) -> Unit,
  onDownloadTrack: (PlexTrack) -> Unit,
  onDownloadTracks: (List<PlexTrack>) -> Unit,
  onCurrentPlayback: () -> Unit,
  onTogglePlayback: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onPlaylists: () -> Unit,
  onSessions: () -> Unit,
  onToggleSessionPlayback: (PlexSession) -> Unit,
  onCycleOfflineQuality: () -> Unit,
  onClearOfflineCache: () -> Unit,
) {
  val scrollState = rememberLazyListState()
  val focusRequester = FocusRequester()
  val coroutineScope = rememberCoroutineScope()
  val filteredBrowseItems = remember(state.items, state.searchQuery) {
    filterBrowseItems(state.items, state.searchQuery)
  }
  val filteredTracks = remember(state.tracks, state.searchQuery) {
    filterTracks(state.tracks, state.searchQuery)
  }
  val showTitle = state.showScreenTitle()

  BackHandler(enabled = state.screen != Screen.Home) { onBack() }

  LaunchedEffect(state.screen, state.title) {
    focusRequester.requestFocus()
  }

  LaunchedEffect(state.screen, state.canLoadMore, state.loadingMore, state.searchQuery) {
    snapshotFlow {
      val layout = scrollState.layoutInfo
      layout.visibleItemsInfo.lastOrNull()?.index to layout.totalItemsCount
    }.collect { (lastVisibleIndex, totalItems) ->
      if (
        state.canLoadMore &&
        !state.loadingMore &&
        totalItems > 0 &&
        (lastVisibleIndex ?: -1) >= totalItems - AUTO_LOAD_THRESHOLD_ITEMS
      ) {
        onLoadMore()
      }
    }
  }

  LazyColumn(
    state = scrollState,
    modifier = Modifier
      .fillMaxSize()
      .focusRequester(focusRequester)
      .onRotaryScrollEvent {
        coroutineScope.launch {
          scrollState.scrollBy(it.verticalScrollPixels)
        }
        true
      }
      .focusable(),
    contentPadding = PaddingValues(start = 18.dp, top = 34.dp, end = 18.dp, bottom = 24.dp),
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

      if (!state.configured && state.screen != Screen.Settings) {
        item(key = "onboarding") {
          ListItemGroup {
            OnboardingContent(state.auth, onStartPinAuth, onCancelPinAuth, onSettings)
          }
        }
      } else {
        if (state.topLevelActions().isNotEmpty()) {
          item(key = "top-level-actions") {
            TopLevelActions(state, onHome, onCurrentPlayback)
          }
        }

        when (state.screen) {
          Screen.Home -> homeContent(state, onItemClick, onPlay, onPlaylists, onSessions, onSettings)
          Screen.Albums, Screen.Playlists -> browseContent(
            state,
            filteredBrowseItems,
            onItemClick,
            onSearch,
            onClearSearch,
          )
          Screen.Tracks -> tracksContent(
            state,
            filteredTracks,
            onSelectTrack,
            onPlayAll,
            onDownloadTracks,
            onSearch,
            onClearSearch,
          )
          Screen.Track -> item(key = "track-content") {
            ListItemGroup {
              TrackContent(state, onPlay, onDownloadTrack, onTrackList)
            }
          }
          Screen.NowPlaying -> item(key = "now-playing-content") {
            ListItemGroup {
              NowPlayingContent(state, onTogglePlayback, onPrevious, onNext)
            }
          }
          Screen.Sessions -> sessionsContent(state, onToggleSessionPlayback, onSessions)
          Screen.Settings -> item(key = "settings-content") {
            ListItemGroup {
              SettingsContent(state, onReset, onCycleOfflineQuality, onClearOfflineCache)
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
  onHome: () -> Unit,
  onCurrentPlayback: () -> Unit,
) {
  val actions = state.topLevelActions()
  when (actions.size) {
    0 -> Unit
    1 -> AppButton(
      text = actions.single().label,
      onClick = {
        when (actions.single()) {
          TopLevelAction.Home -> onHome()
          TopLevelAction.NowPlaying -> onCurrentPlayback()
        }
      },
    )
    else -> Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      actions.forEach { action ->
        AppButton(
          text = action.label,
          onClick = {
            when (action) {
              TopLevelAction.Home -> onHome()
              TopLevelAction.NowPlaying -> onCurrentPlayback()
            }
          },
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

private fun LazyListScope.homeContent(
  state: PlexWearUiState,
  onItemClick: (BrowseItem) -> Unit,
  onPlay: (PlexTrack) -> Unit,
  onPlaylists: () -> Unit,
  onSessions: () -> Unit,
  onSettings: () -> Unit,
) {
  item(key = "home-playlists") {
    NavigationRow(
      title = "Playlists",
      subtitle = "Saved playlists",
      icon = RowIcon.Playlist,
      onClick = onPlaylists,
    )
  }
  item(key = "home-sessions") {
    NavigationRow(
      title = "Active streams",
      subtitle = "Pause other players",
      icon = RowIcon.Track,
      onClick = onSessions,
    )
  }
  items(state.items, key = { it.browseStableKey() }) { item ->
    BrowseRow(state.credentials, item, onItemClick)
  }
  if (state.tracks.isNotEmpty()) {
    item(key = "recent-label") { SectionLabel("Recent") }
    items(state.tracks, key = { "recent-${it.ratingKey}" }) { track ->
      TrackRow(state.credentials, track, onPlay)
    }
  }
  item(key = "home-settings") {
    NavigationRow(
      title = "Settings",
      subtitle = "Account and server",
      icon = RowIcon.Settings,
      onClick = onSettings,
    )
  }
}

private fun LazyListScope.sessionsContent(
  state: PlexWearUiState,
  onToggleSessionPlayback: (PlexSession) -> Unit,
  onRefresh: () -> Unit,
) {
  item(key = "sessions-refresh") {
    IconActionButton(icon = ActionIcon.Refresh, label = "Refresh", onClick = onRefresh)
  }
  if (state.sessions.isEmpty()) {
    item(key = "sessions-empty") { StatusText("No active streams.") }
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
    AppButton(text = "Cancel", onClick = onCancelPinAuth)
  } else {
    AppButton(text = "Sign in with Plex", onClick = onStartPinAuth)
    AppButton(text = "Settings", onClick = onSettings)
  }
}

private fun LazyListScope.browseContent(
  state: PlexWearUiState,
  browseItems: List<BrowseItem>,
  onItemClick: (BrowseItem) -> Unit,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
) {
  item(key = "search-controls") {
    SearchControls(state.searchQuery, onSearch, onClearSearch)
  }
  if (browseItems.isEmpty()) {
    item(key = "browse-empty") { StatusText(if (state.searching) "No matches." else "Nothing found.") }
  }
  items(browseItems, key = { it.browseStableKey() }) { item ->
    BrowseRow(state.credentials, item, onItemClick)
  }
  loadMoreItem(state)
}

private fun LazyListScope.tracksContent(
  state: PlexWearUiState,
  tracks: List<PlexTrack>,
  onSelectTrack: (PlexTrack) -> Unit,
  onPlayAll: (List<PlexTrack>) -> Unit,
  onDownloadTracks: (List<PlexTrack>) -> Unit,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
) {
  item(key = "search-controls") {
    SearchControls(state.searchQuery, onSearch, onClearSearch)
  }
  if (tracks.isEmpty()) {
    item(key = "tracks-empty") { StatusText(if (state.searching) "No matches." else "No tracks found.") }
  }
  if (tracks.isNotEmpty() || state.tracks.isNotEmpty()) {
    item(key = "track-actions") {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      ) {
        IconActionButton(
          icon = ActionIcon.Play,
          label = "Play all",
          onClick = { onPlayAll(tracks) },
          enabled = tracks.isNotEmpty(),
        )
        IconActionButton(
          icon = ActionIcon.Download,
          label = "Download tracks",
          onClick = { onDownloadTracks(state.tracks) },
          enabled = state.tracks.isNotEmpty(),
        )
      }
    }
  }
  items(tracks, key = { it.ratingKey }) { track ->
    TrackRow(
      credentials = state.credentials,
      track = track,
      onPlay = onSelectTrack,
      offlineStatus = state.offlineStatus(track),
    )
  }
  loadMoreItem(state)
}

private fun LazyListScope.loadMoreItem(state: PlexWearUiState) {
  if (state.loadingMore) {
    item(key = "load-more-progress") { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
  }
}

@Composable
private fun TrackContent(
  state: PlexWearUiState,
  onPlay: (PlexTrack) -> Unit,
  onDownloadTrack: (PlexTrack) -> Unit,
  onTrackList: () -> Unit,
) {
  val track = state.selectedTrack
  if (track == null) {
    StatusText("No track selected.")
    AppButton(text = "Tracks", onClick = onTrackList)
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
    IconActionButton(icon = ActionIcon.Play, label = "Play", onClick = { onPlay(track) })
    if (state.downloadedQuality(track) == null && !state.isDownloading(track)) {
      IconActionButton(
        icon = ActionIcon.Download,
        label = "Download ${state.offlineQuality.bitrateKbps}k",
        onClick = { onDownloadTrack(track) },
      )
    }
  }
  AppButton(text = "Tracks", onClick = onTrackList)
}

@Composable
private fun SearchControls(
  query: String,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (query.isNotBlank()) {
      Text(
        text = "Search: $query",
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      IconActionButton(
        icon = ActionIcon.Clear,
        label = "Clear search",
        onClick = onClearSearch,
      )
    } else {
      Spacer(Modifier.weight(1f))
    }
    IconActionButton(
      icon = ActionIcon.Search,
      label = "Search",
      onClick = onSearch,
    )
  }
}

@Composable
private fun NowPlayingContent(
  state: PlexWearUiState,
  onTogglePlayback: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  state.nowPlayingContext?.let {
    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
  if (state.nowPlayingTrackCount > 1) {
    StatusText("${state.nowPlayingIndex + 1} of ${state.nowPlayingTrackCount}")
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
      label = "Previous",
      onClick = onPrevious,
      enabled = state.canPlayPrevious,
    )
    IconActionButton(
      icon = if (state.playbackPaused) ActionIcon.Play else ActionIcon.Pause,
      label = if (state.playbackPaused) "Resume" else "Pause",
      onClick = onTogglePlayback,
    )
    IconActionButton(
      icon = ActionIcon.Next,
      label = "Next",
      onClick = onNext,
      enabled = state.canPlayNext,
    )
  }
}

@Composable
private fun SettingsContent(
  state: PlexWearUiState,
  onReset: () -> Unit,
  onCycleOfflineQuality: () -> Unit,
  onClearOfflineCache: () -> Unit,
) {
  StatusText(if (state.configured) "Credentials are stored on this watch." else "No credentials stored.")
  StatusText("Downloads: ${state.offlineCacheBytes.formatBytes()}")
  AppButton(text = "Quality: ${state.offlineQuality.summary}", onClick = onCycleOfflineQuality)
  AppButton(text = "Clear downloads", onClick = onClearOfflineCache, enabled = state.offlineCacheBytes > 0)
  if (BuildConfig.DEBUG) {
    StatusText("Debug builds still seed plex.serverUrl and plex.token when local.properties is set.")
  }
  AppButton(text = "Reset auth", onClick = onReset)
}

@Composable
private fun BrowseRow(credentials: PlexCredentials, item: BrowseItem, onClick: (BrowseItem) -> Unit) {
  AppRow(
    credentials = credentials,
    title = item.browseRowTitle(),
    subtitle = item.browseRowSubtitle(),
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
    if (session.paused) "Resume" else "Pause",
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
      .size(48.dp)
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

private fun PlexWearUiState.offlineStatus(track: PlexTrack): String? = when {
  isDownloading(track) -> "Downloading"
  downloadedQuality(track) != null -> "Downloaded ${downloadedQuality(track)?.bitrateKbps}k"
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

internal enum class TopLevelAction(val label: String) {
  Home("Home"),
  NowPlaying("Now playing"),
}

internal fun PlexWearUiState.topLevelActions(): List<TopLevelAction> = buildList {
  if (screen != Screen.Home) add(TopLevelAction.Home)
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

private const val KEY_SEARCH_QUERY = "plex_search_query"
private const val AUTO_LOAD_THRESHOLD_ITEMS = 4
private val AppBackground = Color.Black

private fun searchInputIntent() = RemoteInputIntentHelper.createActionRemoteInputIntent().apply {
  val remoteInputs = listOf(
    RemoteInput.Builder(KEY_SEARCH_QUERY)
      .setLabel("Search Plex")
      .setAllowFreeFormInput(true)
      .build(),
  )
  RemoteInputIntentHelper.putRemoteInputsExtra(this, remoteInputs)
  RemoteInputIntentHelper.putTitleExtra(this, "Search")
  RemoteInputIntentHelper.putCancelLabelExtra(this, "Cancel")
  RemoteInputIntentHelper.putConfirmLabelExtra(this, "Search")
  RemoteInputIntentHelper.putInProgressLabelExtra(this, "Searching...")
}
