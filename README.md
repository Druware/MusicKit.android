# Druware.MusicKit for Android

Apple Music playback in an Android application, by hosting Apple's MusicKit JS v3
in a WebView nobody ever sees and putting a Kotlin API over it.

While Apple ships a native MusicKit for Android, it has not been updated since
2021/12/02. Current Android versions have made changes that break the existing
MusicKit that Apple provides. As a result it may as well not exist. What Apple
does ship is MusicKit for the Web, which needs a browser for sign-in, DRM and
playback. This library runs that page in a 1x1 WebView parked in your activity's
content view and exposes the surface a native MusicKit application would want —
authorization, library playlists and their tracks, catalog search, playlist
creation, and a player — as suspending Kotlin functions and `StateFlow`s.

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

**No credential is compiled into this library**, and no token endpoint is
provided with it. You supply a token by implementing `DeveloperTokenProvider`:

```kotlin
interface DeveloperTokenProvider {
    suspend fun getToken(forceRefresh: Boolean = false): DeveloperToken
    fun invalidate()
}

data class DeveloperToken(val token: String, val expiresAt: Instant)
```

That interface is the real contract. `getToken` is called repeatedly rather than
once, so an implementation is expected to cache, to mint a fresh token when the
held one is near expiry, and to be safe to call from several coroutines at once.
`invalidate()` drops the held token so the next `getToken` fetches again.
`CardServerDeveloperTokenProvider` is the one implementation shipped here; it
speaks HTTP, and the specification below is what it expects at the other end.

### What the token is

An Apple MusicKit **developer token**: a JSON Web Token signed **ES256** with an
Apple Media Services private key (a `.p8` downloaded once from the Apple
Developer portal), carrying your Team ID as the `iss` claim, `iat` and `exp`, and
your Key ID as the `kid` header. Apple caps its lifetime at six months; shorter
is better, and the provider here is built for tokens that live about an hour,
which is the right order of magnitude for an endpoint that takes no credential.

**Sign it on a server you control.** A `.p8` in an APK is extractable, and
whoever extracts it can mint tokens against your Apple Developer Program
membership until you revoke the key. There is no configuration of this library
that takes a private key, deliberately.

Every development team implementing this must obtain their own token from Apple
as a part of the Apple Developer Program.

### You run your own endpoint

Druware does not operate a developer-token endpoint for third-party use. There is
no shared address to point this at, and none is compiled in:
`CardServerDeveloperTokenProvider` takes `baseUrl: () -> String` as a required
constructor parameter with no default, so it cannot be built without one you
supply.

### The wire contract

`CardServerDeveloperTokenProvider` makes exactly one call per token:

- **`POST {baseUrl}/api/v1/shazam/token`.** The base URL's own path is kept and
  the suffix appended; a trailing slash is trimmed, and any query or fragment on
  the base is dropped. The base must be an absolute `http` or `https` URL —
  anything else is refused before a request is made.

  The `shazam` in that path is historical rather than a mistake: ShazamKit was
  the endpoint's first consumer, and what it mints is a plain Apple Media
  Services developer token with no audience or other service-specific claim, so
  the same endpoint serves MusicKit unchanged. `CardServerDeveloperTokenProvider`
  appends this exact path, so an endpoint that cannot serve it wants a
  `DeveloperTokenProvider` of your own rather than a renamed route here.
- **No body and no credential.** The request carries an empty body typed
  `application/json`, and sends no `Authorization` header, cookie or key. If your
  endpoint requires authentication, write your own `DeveloperTokenProvider`
  rather than bending this one; that is a supported path, not a workaround.
- **Success is `200`** with a JSON object body:

  | Field | Type | Meaning |
  | --- | --- | --- |
  | `token` | string | The signed JWT. Must be present and non-blank. |
  | `expiresAt` | string | ISO-8601 timestamp for when it stops being usable. |

  Field names are read case-insensitively, so `Token` and `ExpiresAt` are
  accepted. An explicit offset on `expiresAt` is honoured; a timestamp carrying
  none is taken as UTC. A missing or unreadable `expiresAt` is not an error — the
  token is trusted for five minutes and then re-fetched.
- **Errors use one envelope**, `{"error":{"code":"...","message":"..."}}`. The
  `message` is surfaced to the caller as the `MusicKitException` message and the
  `code` as its `code`, so write messages fit to show a user. Nothing else from
  the response body is ever echoed.

Every failure arrives as a `MusicKitException`:

| Condition | `code` |
| --- | --- |
| `baseUrl` is not an absolute HTTP(S) URL | `TOKEN_PROVIDER_FAILED` |
| The host could not be reached | `TOKEN_PROVIDER_FAILED` |
| A `200` whose body is not a JSON object | `TOKEN_PROVIDER_FAILED` |
| A `200` with a missing or blank `token` | `TOKEN_PROVIDER_FAILED` |
| `429`, after the retries below | `429` |
| An envelope whose code is `SHAZAM_NOT_CONFIGURED` | `SHAZAM_NOT_CONFIGURED` |
| Any other envelope carrying a code | that code, verbatim |
| Any other status, with no readable envelope | the HTTP status, as a string |

`SHAZAM_NOT_CONFIGURED` is the one code with a message of its own — it means the
endpoint is running but has no Apple key configured, which is a deployment
mistake rather than a transient fault, and worth saying so plainly. Answer it
with `503`.

### Behaviour to honour

- **Expiry.** The provider refreshes **60 seconds ahead** of `expiresAt` rather
  than at it, so a token minted with less than a minute of life left causes a
  fetch on every single call. Mint with real headroom.
- **`429`.** A `429` is retried up to **three** times after the first attempt.
  The backoff starts at 500 ms, doubles per attempt, and adds up to another whole
  step of jitter, landing uniformly in `[step, 2 × step)`. A fourth consecutive
  `429` surfaces as a failure. Rate-limiting per IP is reasonable, but note that
  a venue full of devices is one public IP — the jitter exists for exactly that.
  No other status is retried.
- **The address is re-read per request.** `baseUrl` is a lambda evaluated once
  per token fetch, so a host that lets a user edit the server address picks up
  the new one on the next fetch without rebuilding the provider.

### Worked examples

A successful `200`:

```json
{
  "token": "eyJhbGciOiJFUzI1NiIsImtpZCI6IkFCQzEyM0RFRjQifQ.eyJpc3MiOiIxMjM0NTZBQkNEIiwiaWF0IjoxNzU3MzAwMDAwLCJleHAiOjE3NTczMDM2MDB9.Ll5s1Rb3-signature",
  "expiresAt": "2030-01-02T03:04:05Z"
}
```

A `503` from an endpoint with no Apple key installed:

```json
{
  "error": {
    "code": "SHAZAM_NOT_CONFIGURED",
    "message": "No Apple Media Services key is configured."
  }
}
```

Which reaches the caller as a `MusicKitException` with code
`SHAZAM_NOT_CONFIGURED`.

### Wiring it up

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

## License

Dual-licensed, at your option, under either:

- the **GNU Lesser General Public License, version 2.1 or (at your option) any
  later version** — the full text is in [`LICENSE.LGPL-2.1`](LICENSE.LGPL-2.1); or
- a **commercial license** from Druware Software Designs.

If you do nothing, the LGPL applies, and it costs nothing. The commercial option
exists because LGPL section 6 requires that whoever receives your application be
able to relink it against a modified version of this library, which is difficult
in practice for a statically linked application distributed through an app store.
The commercial license removes that obligation, for those who cannot meet the
LGPL's terms. For one, contact **support@druware.com**.

See [`LICENSE`](LICENSE) for the full notice.

## Third-party

The Gradle wrapper (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`) is Gradle Inc.'s, redistributed unmodified
under the Apache License, Version 2.0.

**Apple's MusicKit JS is not included in or distributed with this software.** The
hosted page loads it at runtime from Apple's CDN, and that reference is the only
copy in this repository. MusicKit JS remains subject to Apple's own terms, which
this project's license does not alter and cannot grant rights to — you need your
own Apple Developer Program membership.

Apple, Apple Music and MusicKit are trademarks of Apple Inc. This project is not
affiliated with or endorsed by Apple Inc.
