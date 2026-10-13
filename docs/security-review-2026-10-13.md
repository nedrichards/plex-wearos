# Security review — 13 October 2026

## XML responses

Code-scanning alert [1](https://github.com/nedrichards/plex-wearos/security/code-scanning/1) reports `java/xxe` in the shared authentication/music XML parser. The old implementation rejected the literal DOCTYPE string and used a throwing entity resolver, but left DOM parser features at their defaults.

The replacement parses through SAX with external general and parameter entities disabled. A mandatory lexical handler throws on every DTD before its declarations are processed. A throwing resolver remains as a second guard. External entity controls and lexical-handler registration are mandatory and fail closed. External DTD loading is additionally disabled where supported; Android Expat lacks that Xerces feature, so the mandatory DTD-rejection callback enforces rejection there. DOM is constructed from SAX events; the DOM builder never parses network data. Android's DOM factory does not support the Xerces security features, so configuring those unconditionally would break authentication and browsing on Wear OS.

Regression tests exercise local-file entities, HTTP DTDs, parameter entities, internal expansion, authentication, malformed XML, predefined entities, Unicode and nested media metadata. Android instrumentation covers the platform parser as well as JVM unit tests.

## Dependabot

Live open alerts are all transitive dependencies of the build tooling, reported against `settings.gradle.kts`. Neither AGP 9.4.1 nor Foojay resolver 1.0.0 has a newer stable parent release in upstream Maven metadata. The newest AGP preview, 9.5.0-alpha09, also retains bundletool 1.18.3, Jetifier 1.0.0-beta10, Commons Compress 1.27.1 and Bouncy Castle 1.80.2. No leaf constraints, forced versions or exclusions were added.

| Alerts | Library | Current resolved version | First patched version | Parent path | Disposition |
| --- | --- | --- | --- | --- | --- |
| [51](https://github.com/nedrichards/plex-wearos/security/dependabot/51), [50](https://github.com/nedrichards/plex-wearos/security/dependabot/50), [18](https://github.com/nedrichards/plex-wearos/security/dependabot/18) | bcprov-jdk18on | 1.80.2 | 1.85 for all three | AGP → builder/sdk-common/apkzlib | Keep open; awaiting parent update |
| [17](https://github.com/nedrichards/plex-wearos/security/dependabot/17) | bcpkix-jdk18on | 1.80.2 | 1.84 | AGP → builder/sdk-common/apkzlib | Keep open; awaiting parent update |
| [14](https://github.com/nedrichards/plex-wearos/security/dependabot/14) | jose4j | 0.9.5 | 0.9.6 | AGP → bundletool | Keep open; awaiting parent update |
| [12](https://github.com/nedrichards/plex-wearos/security/dependabot/12) | jdom2 | 2.0.6 | 2.0.6.1 | AGP → Jetifier processor | Keep open; awaiting parent update |
| [8](https://github.com/nedrichards/plex-wearos/security/dependabot/8) | commons-lang3 | 3.16.0 | 3.18.0 | AGP → sdk-common/repository/sdklib → Commons Compress | Keep open; awaiting parent update |
| [1](https://github.com/nedrichards/plex-wearos/security/dependabot/1) | httpclient | 4.5.14 | 4.5.13 | AGP → httpmime and analytics crash | Submitted graph also contains requested 4.5.6; Gradle buildEnvironment resolves 4.5.6 → 4.5.14. Dismissed as inaccurate after checking the resolved classpath; no dependency override required. |

Evidence: GitHub Dependabot API and submitted SPDX SBOM; local `./gradlew --offline buildEnvironment`; Google Maven and Gradle Plugin Portal release metadata and upstream AGP POMs. These dependencies run in build tooling; this is not a claim that every advisory is unexploitable in a build environment.

## Publication prerequisite

Main commit dadb33b omitted six supporting refactor source/resource files. Its hosted Android and CodeQL jobs fail with unresolved references. Those existing files are restored separately from the XML security change so a clean checkout can build and CodeQL can scan the fix. Other local untracked work remains outside this change.

## Validation

- Local unit suite: 66 tests passed, including existing untracked refactor tests.
- Debug and release lint passed; debug, Android test and optimized release APKs built successfully.
- Both Android regression methods passed on the attached Pixel Watch 2 through an `app_process` probe using temporary APK classpaths. No APK was installed and application data was unchanged. This is platform-parser validation, not a full instrumentation-runner journey.
- Two temporary emulator launches crashed during startup; the watch probe supplied the Android runtime evidence instead.
- Hosted clean-checkout and CodeQL results must be checked before integrating and closing the alert.
