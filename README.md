# Druware.MusicKit for Android

Apple Music playback in an Android application, by hosting Apple's MusicKit JS v3
in a WebView nobody ever sees and putting a Kotlin API over it.

Apple ships no native MusicKit for Android. What Apple does ship is MusicKit for
the Web, which needs a browser for sign-in, DRM and playback. This library runs
that page in a 1x1 WebView parked in your activity's content view and exposes the
surface a native MusicKit application would want — authorization, library
playlists and their tracks, catalog search, playlist creation, and a player —
as suspending Kotlin functions and `StateFlow`s.

## Requirements

- **minSdk 26**, compiled against SDK 37, Java/Kotlin JVM target 17.
- **An Apple Developer Program membership.** MusicKit needs a developer token
  signed with an Apple Media Services private key.
- **Your own developer-token source.** This library ships no Apple credential of
  any kind; see [Developer tokens](#developer-tokens).
- A device or emulator with a WebView capable of Widevine playback. Emulators are
  Widevine L3 only, so playback there proves little.

Users of your application also need their own Apple Music subscription.

## Consuming it

There is **no published Maven artifact today.** The library is consumed from
source as a git submodule plus a Gradle composite build.

Add the submodule:

```sh
git submodule add https://github.com/Druware/MusicKit.android.git MusicKit.android
```

In your `settings.gradle.kts`, include the build and substitute the module
coordinate for the included project:

```kotlin
includeBuild(settingsDir.resolve("MusicKit.android")) {
    // The included build publishes no coordinate of its own, so the module it
    // stands in for is named here rather than inferred.
    dependencySubstitution {
        substitute(module("com.druware:musickit")).using(project(":musickit"))
    }
}
```

Then depend on the coordinate as usual:

```kotlin
dependencies {
    implementation("com.druware:musickit")
}
```

Two things are worth knowing about the submodule. A plain `git clone` leaves it
as an empty directory and `includeBuild` then fails obscurely, so clone with
`--recurse-submodules` or run `git submodule update --init --recursive`. And AGP
resolves the Android SDK per build tree, from that tree's own `local.properties`
or from `ANDROID_HOME`; the submodule's `local.properties` is gitignored, so
either set `ANDROID_HOME` or have your outer `settings.gradle.kts` write one.

## Developer tokens

**No credential is compiled into this library.** You supply one by implementing
`DeveloperTokenProvider`:

```kotlin
interface DeveloperTokenProvider {
    suspend fun getToken(forceRefresh: Boolean = false): DeveloperToken
    fun invalidate()
}
```

A token lives about an hour, so `getToken` is called repeatedly rather than once.
Implementations are expected to cache and to be safe to call from several
coroutines at once. Sign the token on a server you control — an Apple Media
Services private key does not belong in an APK.

`CardServerDeveloperTokenProvider` is the one concrete implementation. It fits a
Druware Card Server, or anything that answers the same contract: one
unauthenticated `POST {baseUrl}/api/v1/shazam/token`, no body and no credential,
returning `200` with `{ "token": "<jwt>", "expiresAt": "<ISO-8601 UTC>" }`. The
base URL is a `() -> String` lambda evaluated once per token request, so a host
that lets a user edit the server address picks the new one up on the next fetch.

Hand the provider to `MusicKitOptions` and create a host:

```kotlin
val host = MusicKitHost.create(
    MusicKitOptions(
        developerTokenProvider = myProvider,
        activity = this,
        appName = "My App",
        appBuild = "1.0.0",
    )
)
```

Call `host.close()` from the activity's own teardown — the WebView is a child of
that activity's content view, so the host lives no longer than the activity.

## Architecture

```
your Kotlin code
      |
MusicKitHost / MusicKitPlayer      suspending calls, StateFlow state
      |
MusicKitBridge                     JSON envelopes, id-correlated calls
      |
HostedWebView                      1x1 WebView, WebViewAssetLoader
      |
assets/musickit/index.html         + musickit-host.js
      |
Apple's MusicKit JS v3             fetched from Apple's CDN at runtime
```

The page is served over `https://appassets.androidplatform.net/...` by
`WebViewAssetLoader`, never over `file://`. That origin is load-bearing three
times over: it is a secure context, which EME requires; it gives `localStorage`
somewhere to keep the Music User Token; and it puts a real `Origin` header on
requests to Apple's CDN and API.

Calls cross the bridge as JSON envelopes carrying a correlation id, using
`WebViewCompat.addWebMessageListener` scoped to that origin. Every member may be
called from any coroutine; WebView work is marshalled to the main thread
internally.

On credentials: the developer token is held in memory and sent to the page and
nowhere else. **The Music User Token never crosses the bridge at all** —
`authorize()` resolves to it inside the page, and only a boolean comes back.
Neither ever appears in the host's `diagnostic` flow.

## Building and testing

```sh
./gradlew :musickit:assemble        # build the library
./gradlew :musickit:test            # JVM unit tests (JUnit, Robolectric, MockWebServer)
```

Lint is set to `abortOnError`, so a lint error fails the build.

Instrumented tests need a connected device or emulator:

```sh
./gradlew :musickit:connectedDebugAndroidTest
```

`HostedPageInstrumentedTest` runs unconditionally. It covers what only a real
WebView can answer — that the page is served over the origin MusicKit's sign-in
is keyed to, that the bridge round-trips, and whether the device can do DRM at
all. None of it needs an Apple account.

`MusicKitHostInstrumentedTest` exercises the whole stack against a real developer
token, and is gated by `IntegrationGate`: without a token source the tests are
skipped with a reason rather than failed. Name one to run them:

```sh
./gradlew :musickit:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.cardServer=https://cards.example.com
```

Nothing in the instrumented suite signs in or asserts that audio comes out: the
sign-in popup needs a human, and playback needs a subscription and a device that
is not the emulator.

## Licence

Dual-licensed, at your option, under either:

- the **GNU Lesser General Public License, version 2.1 or (at your option) any
  later version** — the full text is in [`LICENSE.LGPL-2.1`](LICENSE.LGPL-2.1); or
- a **commercial licence** from Druware Software Designs.

If you do nothing, the LGPL applies, and it costs nothing. The commercial option
exists because LGPL section 6 requires that whoever receives your application be
able to relink it against a modified version of this library, which is difficult
in practice for a statically linked application distributed through an app store.
The commercial licence removes that obligation, for those who cannot meet the
LGPL's terms. For one, contact **support@druware.com**.

See [`LICENSE`](LICENSE) for the full notice.

## Third-party

The Gradle wrapper (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`) is Gradle Inc.'s, redistributed unmodified
under the Apache License, Version 2.0.

**Apple's MusicKit JS is not included in or distributed with this software.** The
hosted page loads it at runtime from Apple's CDN, and that reference is the only
copy in this repository. MusicKit JS remains subject to Apple's own terms, which
this project's licence does not alter and cannot grant rights to — you need your
own Apple Developer Program membership.

Apple, Apple Music and MusicKit are trademarks of Apple Inc. This project is not
affiliated with or endorsed by Apple Inc.
