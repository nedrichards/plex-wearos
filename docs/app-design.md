# App design

Plex Wear is a standalone Wear OS Plex music client. The app is intentionally
small: it should make common music playback and lightweight remote control
practical on a watch without cloning the full Plex app.

## Product shape

Primary goals:

- Sign in directly from the watch.
- Browse music libraries, albums, playlists, and recent tracks.
- Start playback on the watch with reliable direct/transcode fallback.
- Keep current playback reachable from other screens.
- Cache useful tracks locally for watch playback.
- Pause or resume active Plex sessions on other players.

Non-goals for the current version:

- A companion phone app.
- Full Plex library management.
- Video playback on the watch.
- Rich remote-control transport for other players beyond play/pause.
- Cross-device sync for settings or offline media.

## Architecture

The app is a single Android application module:

- `MainActivity` creates the repositories, auth store, offline stores, and
  `PlexWearViewModel`.
- `PlexWearApp` owns the Compose UI and screen routing.
- `PlexWearViewModel` owns app state and user actions.
- `PlexRepository` exposes Plex operations used by the UI.
- `PlexApi`, `PlexRequestBuilder`, and `PlexXmlParser` handle raw Plex HTTP and
  XML parsing.
- `PlaybackService` and `PlaybackController` wrap Media3 playback.
- `OfflineCacheManager` owns the durable, scoped download catalogue and WorkManager queue.
- `PlexConnectionResolver` shares working routes across browsing, artwork, playback and downloads.
- `HomeSnapshotStore` retains a small last-used Home snapshot and pinned playlists.
- `OfflineSettingsStore` owns the preferred download/transcode quality.

The app uses DataStore for local auth and offline settings. There is no server
component and no phone-side dependency.

## Auth and server selection

Production sign-in uses Plex PIN auth. The watch shows a code, the user enters
it at `https://plex.tv/link`, and the app exchanges the resulting account token
for a selected server token and connection URLs.

Server connections are stored with the preferred URL first and alternate URLs
after it. Local/private URLs should be preferred so in-home playback avoids
relay or remote routes. Remote Plex URLs remain as fallbacks for LTE and
away-from-home use.

Debug builds can seed credentials from ignored `local.properties` using:

```properties
plex.serverUrl=https://your-plex-server.example
plex.token=your-plex-token
```

That path is intentionally preserved for local development. Release builds keep
the generated debug credential fields empty.

## Screen model

The home screen is the hub. Resume and Downloads take priority, followed by pinned playlists and browsing:

- `Playlists`
- `Active streams`
- music library rows
- recent tracks
- `Settings`

Screen routing is intentionally shallow:

- Library row -> albums
- Album row -> tracks
- Playlist row -> tracks
- Track row -> track actions
- Play or Play all -> Now Playing
- Active streams -> session list
- Settings -> auth and offline controls

The system back gesture returns through the loaded app screens. When playback
is active, a compact Now Playing action appears above relevant content instead
of a persistent full-width navigation row.

## List actions

Search is a compact control for filtering loaded pages; Search more pages explicitly adds another page. The search action
uses Android remote input so the system can offer voice or keyboard input. An
active query is shown inline with compact search and clear controls.

List-wide actions stay before list content:

- Track lists expose `Play all` and `Download`.
- Active streams expose `Refresh`.

Track-specific actions live on the track detail screen:

- `Play`
- `Download <quality>`
- `Tracks`

This keeps repeated rows mostly navigational and reserves command buttons for
the scope they affect.

## Playback policy

Playback is through Media3 and the app's `PlaybackService`. The view model
creates a playback plan for each track and asks `PlaybackController` to prepare
and play it.

Current stream preference:

- MP3 and AAC direct play.
- FLAC direct plays on local/private Plex URLs.
- FLAC and unknown codecs prefer Plex's AAC transcode endpoint on remote URLs.
- Alternate server URLs are appended as fallbacks.
- Cached local files are tried before streaming.

The service retries the failing track occurrence through its remaining source
chain, preserving queue and position. The controller reconnects on UI startup
and reconstructs the service queue and transport state.

Now Playing tracks the current queue in UI state. It exposes pause/resume,
previous, and next controls directly rather than hiding transport behind another
status screen.

## Offline cache

Manual downloads and optional automatic caching both go through
`OfflineCacheManager`, with WorkManager constraints and persistent jobs.

Media identity includes server machine/account scope, track rating key and
quality. The catalogue retains title, artist, album, collection membership and
ordered playlist occurrences; the Downloads destination works after restart
without contacting the server. Clear, removal and cancellation coordinate with
active writers, while incomplete files are excluded from the playable index.

A collection download discovers all pages durably. Filtered Download results
shares the Play results scope. Quality choices are explicit, and existing media
can be replaced at a new quality from track actions.

Automatic caching defaults off. If enabled it caches one upcoming track while
charging on an unmetered connection, with a 256 MB eviction budget. Pinned
manual downloads have a separate 1 GB budget per server/account scope. Space
checks reserve room for normal app operation.

## Remote control

Remote control uses Plex `/status/sessions` and
`/player/playback/{play|pause}`. The app sends commands to the target player's
machine identifier and maps music/video session types to Plex control types.

The current UI exposes only play/pause for active sessions. That keeps remote
control useful without turning the watch app into a full Plex remote.

## Browse performance

Album, playlist, and track views request Plex in 50-row server pages. The app
shows the initial rows promptly and requests another page near the end of the
list. Pages participate in a bounded, expiring in-memory browse cache with shared
in-flight requests, keeping
back-and-forth navigation responsive without loading an entire library into
memory on first visit.

## Launcher icon

The app uses adaptive launcher resources for current Android versions:

- adaptive XML in `mipmap-anydpi-v26`
- background vector in `drawable/ic_launcher_background.xml`
- foreground raster in
  `drawable-nodpi/ic_launcher_foreground_watch_play_waves.png`
- themed monochrome vector in `drawable/ic_launcher_monochrome.xml`

The WebP assets under `mipmap-*` are density-specific fallbacks generated from
the selected watch/play/waves raster direction. Keep them aligned with the
adaptive foreground instead of leaving Android template placeholders behind.

When changing the icon, check the adaptive foreground safe zone, the round icon,
and the monochrome/themed layer separately. A launcher icon can be technically
centered while still feeling visually off-balance, so judge the masked result,
not only the raw 108dp canvas.

## Validation

[Implementation evidence](app-review-resolution.md) records the current changes
and their validation limits. Connected journeys exercise the production media
service, offline manager, view model and Wear Compose UI.


Standard local validation:

```sh
export GRADLE_USER_HOME="$PWD/.gradle-local"
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
```

Check the packaged watch gate and launcher resource:

```sh
"${ANDROID_HOME}/build-tools/36.0.0/aapt" dump badging app/build/outputs/apk/debug/app-debug.apk
```

Release-signing validation is covered in
`docs/android-release-signing.md`.
