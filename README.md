# Plex Wear

Standalone Wear OS music playback and Plex remote-control app.

Plex Wear is built for a watch that can operate on its own network connection.
It signs in directly with Plex, discovers music libraries and playlists, plays
music through a Media3 playback service, can cache tracks locally, and can pause
or resume other active Plex players.

## Current scope

- Plex PIN sign-in from the watch with `https://plex.tv/link`.
- Debug-only credential seeding from ignored `local.properties` for local
  development.
- Music libraries, albums, playlists, recent music, and track detail screens.
- Wear-native search using Android remote input, so voice and keyboard input are
  provided by the platform.
- Direct watch playback with pause, resume, previous, next, and a route back to
  current playback from other screens.
- Local-first server access with remote Plex connection fallbacks.
- Automatic playback fallback between direct and transcoded stream URLs.
- Durable manual downloads of AAC files and an optional bounded automatic cache.
- Offline Downloads library, persistent collection order, and pinned playlists.
- Remote play/pause control for active Plex sessions.

This is intentionally watch-first. The app does not require a companion phone
app and does not try to reproduce the full Plex browse UI.

## App flow

The home screen is the main hub. Resume/Now Playing and Downloads are near the top,
followed by pinned playlists and browsing:

- `Playlists` opens audio playlists.
- `Active streams` lists current Plex sessions that can be paused or resumed.
- Music library rows open albums.
- `Recent` shows recent tracks for quick playback.
- `Settings` contains account, offline cache, and quality controls.

List screens use the system back gesture to return through the loaded app
screens. When music is playing, a compact Now Playing action remains available
above the current screen content.

Album, playlist, and browse screens expose a compact search icon rather than a
full-width search row. Search explicitly filters loaded pages and matches album, playlist, track,
artist, and album text where available. Search more pages requests another page
only when chosen; filtering does not automatically scan a whole collection. Browse views load 50
rows at a time and automatically request the next page near the end of a list.

## Plex sign-in

On first launch, choose `Sign in with Plex` and enter the code shown on the watch
at:

```text
https://plex.tv/link
```

The app stores the selected Plex server token and server connection URLs in
DataStore on the watch. Local/private server URLs are tried first, with the
server's remote connection retained as a fallback for LTE or away-from-home use.

## Debug Plex credentials

For personal debug installs, you can still seed a debug build from uncommitted
`local.properties`:

```properties
plex.serverUrl=https://your-plex-server.example
plex.token=your-plex-token
```

The debug build copies those values into DataStore on first launch through
`PlexAuthStore`. Release builds set the generated Plex `BuildConfig` fields to
empty strings, so secrets are not compiled into release artifacts.

## Playback

Selecting a track from an album or playlist opens track actions for playback and
download. `Play collection` starts the loaded tracks promptly and appends remaining
collection pages. `Play results` queues the filtered loaded tracks. Both open Now
Playing. Queue metadata and transport state come from the media service when the
app reopens.

Playback uses these rules:

- MP3 and AAC direct play.
- FLAC direct plays when the active Plex server URL looks local/private.
- FLAC and unknown codecs prefer Plex's HTTP AAC transcode endpoint when the
  active connection looks remote.
- If a track fails, the service retries that occurrence using its remaining
  direct/transcode/server alternatives without replacing the entire queue.
- If a local cached file exists, playback prefers it and keeps a streaming
  fallback available.

The Now Playing screen shows queue position when more than one track is queued,
then exposes pause/resume, previous, and next controls.

## Offline playback

`Download collection` schedules the entire album or playlist, including pages not
loaded on screen. Filtered `Download results` saves the visible matching tracks.
Track actions support individual downloads, removal and replacement at a new
quality. A durable WorkManager queue exposes progress, cancellation and retry.

Downloads are indexed by server and account, with persistent track metadata and
ordered collections. Open `Downloads` after restarting without server access to
find saved music. Repeated playlist tracks retain their order while sharing a
single stored audio file per track and quality. Pinned downloads have a 1 GB
budget per server/account scope, and every transfer respects remaining disk space.

Automatic caching defaults to off. When enabled in Settings it caches one
upcoming track on an unmetered connection while charging, using a separate
256 MB expendable cache. It does not download the entire playback queue.

Settings offers explicit quality choices: Data saver (96k AAC), Balanced
(192k AAC), and High (320k AAC). A new quality affects new downloads and
transcodes; use a track's replacement action to save it at that quality.
Settings also allows selecting a discovered server and signing out. Both stop
current playback and cancel active download work before changing the account or
server.

## Remote control

`Active streams` lists current Plex sessions from `/status/sessions`. Selecting
a controllable session sends a Plex play or pause command to that player, so the
watch can stop and resume another Plex or Plexamp stream without becoming the
playback device.

Remote control is deliberately narrow at the moment: play/pause only, with
session refresh available from the top of the screen.

## Launcher icon

The app uses adaptive launcher resources on supported Android versions:

- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `app/src/main/res/drawable/ic_launcher_background.xml`
- `app/src/main/res/drawable-nodpi/ic_launcher_foreground_watch_play_waves.png`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`

The WebP fallback assets under `mipmap-*` are generated from the same
watch/play/waves raster direction. When changing the icon, keep adaptive
foreground, background, monochrome, and fallback assets as separate concerns.

## Development

From the repository root, use a repo-local Gradle cache. Set `JAVA_HOME` first
if your shell does not already select an Android-compatible JDK.

```sh
export GRADLE_USER_HOME="$PWD/.gradle-local"
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Check the watch-only manifest gate and launcher icon resource:

```sh
"${ANDROID_HOME}/build-tools/36.0.0/aapt" dump badging app/build/outputs/apk/debug/app-debug.apk
```

Expected badging includes `android.hardware.type.watch` and the adaptive
launcher resource `res/mipmap-anydpi-v26/ic_launcher.xml`.

## Documentation

- [Review implementation and validation](docs/app-review-resolution.md) records the current changes.
- [App design](docs/app-design.md) describes the screen structure, playback
  policy, offline cache, and icon-resource model.
- [Android release signing](docs/android-release-signing.md) describes optional
  real release signing and local debug-signed release builds.
