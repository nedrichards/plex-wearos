package com.nedrichards.plexwear.ui

import android.app.RemoteInput
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.nedrichards.plexwear.data.PlexTrack
import kotlinx.coroutines.launch

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
        .background(MaterialTheme.colorScheme.background),
    ) {
      TimeText()
      PlexWearScreen(
        state = state,
        onHome = viewModel::loadHome,
        onSettings = viewModel::openSettings,
        onReset = viewModel::resetAuth,
        onStartPinAuth = viewModel::startPinAuth,
        onCancelPinAuth = viewModel::cancelPinAuth,
        onSearch = { searchLauncher.launch(searchInputIntent()) },
        onClearSearch = viewModel::clearSearchQuery,
        onItemClick = { item ->
          when (item) {
            is BrowseItem.LibraryItem -> viewModel.loadAlbums(item.library)
            is BrowseItem.AlbumItem -> viewModel.loadAlbumTracks(item.album)
            is BrowseItem.PlaylistItem -> viewModel.loadPlaylistTracks(item.playlist)
            is BrowseItem.TrackItem -> viewModel.play(item.track)
          }
        },
        onPlay = { viewModel.play(it) },
        onPlayAll = { viewModel.playAll(it) },
        onCurrentPlayback = viewModel::openCurrentPlayback,
        onTogglePlayback = viewModel::togglePlayback,
        onPrevious = viewModel::skipToPrevious,
        onNext = viewModel::skipToNext,
        onPlaylists = viewModel::loadPlaylists,
      )
    }
  }
}

@Composable
private fun PlexWearScreen(
  state: PlexWearUiState,
  onHome: () -> Unit,
  onSettings: () -> Unit,
  onReset: () -> Unit,
  onStartPinAuth: () -> Unit,
  onCancelPinAuth: () -> Unit,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
  onItemClick: (BrowseItem) -> Unit,
  onPlay: (PlexTrack) -> Unit,
  onPlayAll: (List<PlexTrack>) -> Unit,
  onCurrentPlayback: () -> Unit,
  onTogglePlayback: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onPlaylists: () -> Unit,
) {
  val scrollState = rememberScrollState()
  val focusRequester = FocusRequester()
  val coroutineScope = rememberCoroutineScope()

  LaunchedEffect(state.screen, state.title) {
    focusRequester.requestFocus()
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .focusRequester(focusRequester)
      .onRotaryScrollEvent {
        coroutineScope.launch {
          scrollState.scrollBy(it.verticalScrollPixels)
        }
        true
      }
      .focusable()
      .verticalScroll(scrollState)
      .padding(horizontal = 14.dp, vertical = 26.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = state.title,
      style = MaterialTheme.typography.titleMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )

    if (state.loading) {
      Spacer(Modifier.height(18.dp))
      CircularProgressIndicator()
      return@Column
    }

    state.error?.let {
      StatusText(it)
    }

    if (!state.configured && state.screen != Screen.Settings) {
      OnboardingContent(state.auth, onStartPinAuth, onCancelPinAuth, onSettings)
      return@Column
    }

    if (state.canOpenCurrentPlayback) {
      AppButton(text = "Now playing", onClick = onCurrentPlayback)
    }

    when (state.screen) {
      Screen.Home -> HomeContent(state, onItemClick, onPlay, onPlaylists, onSettings)
      Screen.Albums, Screen.Playlists -> BrowseContent(state, onItemClick, onHome, onSearch, onClearSearch)
      Screen.Tracks -> TracksContent(state, onPlay, onPlayAll, onHome, onSearch, onClearSearch)
      Screen.NowPlaying -> NowPlayingContent(state, onTogglePlayback, onPrevious, onNext, onHome)
      Screen.Settings -> SettingsContent(state, onHome, onReset)
    }
  }
}

@Composable
private fun HomeContent(
  state: PlexWearUiState,
  onItemClick: (BrowseItem) -> Unit,
  onPlay: (PlexTrack) -> Unit,
  onPlaylists: () -> Unit,
  onSettings: () -> Unit,
) {
  AppButton(text = "Playlists", onClick = onPlaylists)
  state.items.forEach { item -> BrowseRow(state.credentials, item, onItemClick) }
  if (state.tracks.isNotEmpty()) {
    SectionLabel("Recent")
    state.tracks.forEach { track -> TrackRow(state.credentials, track, onPlay) }
  }
  AppButton(text = "Settings", onClick = onSettings)
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

@Composable
private fun BrowseContent(
  state: PlexWearUiState,
  onItemClick: (BrowseItem) -> Unit,
  onHome: () -> Unit,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
) {
  val items = filterBrowseItems(state.items, state.searchQuery)
  SearchControls(state.searchQuery, onSearch, onClearSearch)
  if (items.isEmpty()) StatusText(if (state.searching) "No matches." else "Nothing found.")
  items.forEach { item -> BrowseRow(state.credentials, item, onItemClick) }
  AppButton(text = "Home", onClick = onHome)
}

@Composable
private fun TracksContent(
  state: PlexWearUiState,
  onPlay: (PlexTrack) -> Unit,
  onPlayAll: (List<PlexTrack>) -> Unit,
  onHome: () -> Unit,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
) {
  val tracks = filterTracks(state.tracks, state.searchQuery)
  SearchControls(state.searchQuery, onSearch, onClearSearch)
  if (tracks.isEmpty()) StatusText(if (state.searching) "No matches." else "No tracks found.")
  if (tracks.isNotEmpty()) AppButton(text = "Play all", onClick = { onPlayAll(tracks) })
  tracks.forEach { track -> TrackRow(state.credentials, track, onPlay) }
  AppButton(text = "Home", onClick = onHome)
}

@Composable
private fun SearchControls(
  query: String,
  onSearch: () -> Unit,
  onClearSearch: () -> Unit,
) {
  if (query.isNotBlank()) {
    StatusText("Search: $query")
    AppButton(text = "Clear search", onClick = onClearSearch)
  }
  AppButton(text = "Search", onClick = onSearch)
}

@Composable
private fun NowPlayingContent(
  state: PlexWearUiState,
  onTogglePlayback: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onHome: () -> Unit,
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
  AppButton(text = if (state.playbackPaused) "Resume" else "Pause", onClick = onTogglePlayback)
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    AppButton(
      text = "Previous",
      onClick = onPrevious,
      enabled = state.canPlayPrevious,
      modifier = Modifier.weight(1f),
    )
    AppButton(
      text = "Next",
      onClick = onNext,
      enabled = state.canPlayNext,
      modifier = Modifier.weight(1f),
    )
  }
  AppButton(text = "Home", onClick = onHome)
}

@Composable
private fun SettingsContent(
  state: PlexWearUiState,
  onHome: () -> Unit,
  onReset: () -> Unit,
) {
  StatusText(if (state.configured) "Credentials are stored on this watch." else "No credentials stored.")
  if (BuildConfig.DEBUG) {
    StatusText("Debug builds still seed plex.serverUrl and plex.token when local.properties is set.")
  }
  AppButton(text = "Reset auth", onClick = onReset)
  AppButton(text = "Home", onClick = onHome)
}

@Composable
private fun BrowseRow(credentials: PlexCredentials, item: BrowseItem, onClick: (BrowseItem) -> Unit) {
  val title = when (item) {
    is BrowseItem.LibraryItem -> item.library.title
    is BrowseItem.AlbumItem -> item.album.title
    is BrowseItem.PlaylistItem -> item.playlist.title
    is BrowseItem.TrackItem -> item.track.title
  }
  val subtitle = when (item) {
    is BrowseItem.LibraryItem -> item.library.type
    is BrowseItem.AlbumItem -> item.album.artist
    is BrowseItem.PlaylistItem -> item.playlist.durationMs?.formatDuration()
    is BrowseItem.TrackItem -> item.track.artist
  }
  val artworkPath = when (item) {
    is BrowseItem.LibraryItem -> null
    is BrowseItem.AlbumItem -> item.album.thumb
    is BrowseItem.PlaylistItem -> item.playlist.thumb
    is BrowseItem.TrackItem -> item.track.thumb
  }

  AppRow(
    credentials = credentials,
    title = title,
    subtitle = subtitle,
    artworkPath = artworkPath,
    onClick = { onClick(item) },
  )
}

@Composable
private fun TrackRow(credentials: PlexCredentials, track: PlexTrack, onPlay: (PlexTrack) -> Unit) {
  AppRow(
    credentials = credentials,
    title = track.title,
    subtitle = listOfNotNull(track.artist, track.durationMs?.formatDuration()).joinToString(" - ").ifBlank { null },
    artworkPath = track.thumb,
    onClick = { onPlay(track) },
  )
}

@Composable
private fun AppRow(
  credentials: PlexCredentials,
  title: String,
  subtitle: String?,
  artworkPath: String?,
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
      PlexArtwork(
        credentials = credentials,
        thumbPath = artworkPath,
        contentDescription = null,
      )
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
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

private fun Long.formatDuration(): String {
  val totalSeconds = this / 1000
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val KEY_SEARCH_QUERY = "plex_search_query"

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
