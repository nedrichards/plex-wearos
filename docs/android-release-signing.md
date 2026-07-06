# Android release signing

Release signing is optional in local checkouts. If no signing values are
present, release builds remain unsigned so development and smoke tests can run
without private key material.

## Versioning

Set release version values with Gradle properties or ignored `local.properties`:

```properties
plexWear.versionCode=1
plexWear.versionName=1.0
```

If absent, the app defaults to version code `1` and version name `1.0`.

## Signing inputs

The app module reads release signing values from these sources, in order:

1. Gradle properties named `plexWear.storeFile`, `plexWear.storePassword`,
   `plexWear.keyAlias`, and `plexWear.keyPassword`.
2. Ignored `local.properties` entries with the same `plexWear.*` names.
3. Ignored `keystore.properties` using the names in
   `keystore.properties.example`.
4. Environment variables: `PLEX_WEAR_KEYSTORE_FILE`,
   `PLEX_WEAR_KEYSTORE_PASSWORD`, `PLEX_WEAR_KEY_ALIAS`, and
   `PLEX_WEAR_KEY_PASSWORD`.

All four signing values must be present before the release signing config is
attached.

## Debug-signed release testing

For local performance testing, a release build can be signed with the debug
certificate by adding this ignored `local.properties` entry:

```properties
plexWear.debugSignRelease=true
```

Release signing values still take precedence when present. Do not use a
debug-signed release artifact for Play upload.

If you want a stable debug certificate shared with other local Wear OS repos,
you can also set these optional ignored values:

```properties
androidDebugSigning.storeFile=/absolute/path/to/debug.keystore
androidDebugSigning.storePassword=android
androidDebugSigning.keyAlias=androiddebugkey
androidDebugSigning.keyPassword=android
```

Without `androidDebugSigning.storeFile`, the Android Gradle plugin's generated
debug keystore is used for debug-signed release builds.

## Verification

Build a release APK:

```sh
./gradlew assembleRelease
```

If release signing values are configured, verify the APK certificate:

```sh
"${ANDROID_HOME}/build-tools/36.0.0/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
```
