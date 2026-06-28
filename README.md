# Plex Wear

Standalone Wear OS music playback spike for a Plex server.

## Debug Plex credentials

For the first playback spike, seed a debug build from uncommitted `local.properties`:

```properties
plex.serverUrl=https://your-plex-server.example
plex.token=your-plex-token
```

The debug build copies those values into DataStore on first launch through `PlexAuthStore`.
Release builds set the generated Plex `BuildConfig` fields to empty strings.

## Validation

Use a repo-local Gradle cache:

```sh
JAVA_HOME=/var/home/nedr/.jdks/jbr-17.0.14 \
GRADLE_USER_HOME=/var/home/nedr/Projects/plex-wearos/.gradle-local \
./gradlew testDebugUnitTest assembleDebug
```

Check the watch-only manifest gate:

```sh
${ANDROID_HOME}/build-tools/36.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk
```
