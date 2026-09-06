package com.druware.musickit

import android.app.Activity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Everything [MusicKitHost.create] needs to stand a host up.
 *
 * @param developerTokenProvider where developer tokens come from.
 * @param activity the activity the hidden 1x1 WebView is parked in and Apple's sign-in dialog is
 *   shown over. The host lives no longer than this activity: the WebView is a child of its content
 *   view, so [MusicKitHost.close] belongs in the activity's own teardown. There is no
 *   `UserDataFolder` equivalent to set — Android gives every app one WebView data directory, under
 *   the app's private storage, and that is where the sign-in persists.
 * @param appName the application name MusicKit shows on Apple's sign-in popup.
 * @param appBuild the application build MusicKit reports alongside [appName].
 * @param rotationLeadTime how far ahead of expiry the developer token is replaced. Rotation stops
 *   playback and clears the queue, so it is done between tracks rather than under one.
 * @param callTimeout how long one bridge call may take before it is a
 *   [java.util.concurrent.TimeoutException].
 * @param authorizeTimeout how long Apple's sign-in popup may stay open before the wait gives up.
 */
class MusicKitOptions(
    val developerTokenProvider: DeveloperTokenProvider,
    val activity: Activity,
    val appName: String = "Druware.MusicKit",
    val appBuild: String = "0.1.0",
    val rotationLeadTime: Duration = 10.minutes,
    val callTimeout: Duration = 30.seconds,
    val authorizeTimeout: Duration = 3.minutes,
)
