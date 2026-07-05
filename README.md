# Plex Wear

Standalone Wear OS music playback spike for a Plex server.

## Plex sign-in

On first launch, sign in with the code shown on the watch at:

```text
https://plex.tv/link
```

The app stores the selected Plex server connections and server token locally on
the watch. Local connections are tried first, with the server's remote
connection used as a fallback when the watch is away from the home network.

## Debug Plex credentials

For personal debug installs, you can still seed a debug build from uncommitted
`local.properties`:

```properties
plex.serverUrl=https://your-plex-server.example
plex.token=your-plex-token
```

The debug build copies those values into DataStore on first launch through `PlexAuthStore`.
Release builds set the generated Plex `BuildConfig` fields to empty strings.

## Playback

Selecting a track from an album or playlist opens track actions for playback and download. The app direct plays MP3 and AAC, direct plays FLAC when the configured Plex server URL looks local/private and otherwise uses Plex's HTTP AAC transcode endpoint for FLAC to avoid high-bandwidth remote streaming. If the first playback path fails during startup, the app silently falls back to the other path.

## Remote control

`Active streams` on the home screen lists current Plex sessions from
`/status/sessions`. Selecting a session sends a Plex play or pause command to
that player, so the watch can stop and resume another Plex or Plexamp stream
without becoming the playback device.

## Offline playback

`Download all` stores the whole loaded album or playlist. Individual tracks can
be downloaded from the selected track action screen.

The app also automatically caches tracks after playback starts. Downloaded
tracks are stored in the app's private storage on the watch, playback prefers a
local copy when one exists, and Settings shows the total download size with a
`Clear downloads` action.

Settings also cycles the download/transcode quality between 96k, 192k, and
320k AAC. The selected quality applies to new downloads, automatic caching, and
Plex transcode streams. Existing downloads keep the quality they were cached at
until they are cleared or replaced.

## Validation

From the repository root, use a repo-local Gradle cache. Set `JAVA_HOME`
first if your shell does not already select an Android-compatible JDK.

```sh
export GRADLE_USER_HOME="$PWD/.gradle-local"
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Check the watch-only manifest gate:

```sh
"${ANDROID_HOME}/build-tools/36.0.0/aapt" dump badging app/build/outputs/apk/debug/app-debug.apk
```
