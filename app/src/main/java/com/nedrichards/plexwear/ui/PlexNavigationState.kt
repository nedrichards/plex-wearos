package com.nedrichards.plexwear.ui

import com.nedrichards.plexwear.data.*

/** Only destination-owned state belongs on Back: never credentials, playback or downloads. */
internal data class NavigationSnapshot(
  val destinationId: String,
  val screen: Screen,
  val title: String,
  val items: List<BrowseItem>,
  val tracks: List<PlexTrack>,
  val sessions: List<PlexSession>,
  val trackListTitle: String?,
  val selectedTrack: PlexTrack?,
  val searchQuery: String,
  val canLoadMore: Boolean,
) {
  constructor(state: PlexWearUiState) : this(state.destinationId, state.screen, state.title, state.items,
    state.tracks, state.sessions, state.trackListTitle, state.selectedTrack, state.searchQuery, state.canLoadMore)

  fun restore(current: PlexWearUiState): PlexWearUiState = current.copy(
    destinationId = destinationId, screen = screen, title = title, items = items, tracks = tracks,
    sessions = sessions, trackListTitle = trackListTitle, selectedTrack = selectedTrack,
    searchQuery = searchQuery, canLoadMore = canLoadMore,
    loading = false, loadingMore = false, error = null, pageError = null,
  )
}

/** A response may publish only while its request generation is current. */
internal class BrowseRequestGate {
  private var generation = 0L
  fun advance(): Long = ++generation
  fun current(): Long = generation
  fun accepts(request: Long): Boolean = request == generation
}
