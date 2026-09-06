package com.druware.musickit

import android.app.Activity
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What only a real WebView can answer: that the page is served over the origin MusicKit's sign-in is
 * keyed to, that the bridge round-trips, and whether this device can do DRM at all.
 *
 * None of these need an Apple account, and none of them assert that audio comes out — the emulator
 * is Widevine L3 only, so a playback failure there would say nothing about the code.
 */
@RunWith(AndroidJUnit4::class)
class HostedPageInstrumentedTest {

    private lateinit var scenario: ActivityScenario<MusicKitTestActivity>
    private lateinit var activity: Activity

    private val lines = CopyOnWriteArrayList<String>()
    private var page: HostedWebView? = null

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MusicKitTestActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After
    fun tearDown() {
        runBlocking { page?.close() }
        page = null
        scenario.close()
        Log.i(TAG, lines.joinToString("\n"))
    }

    private fun host(): HostedWebView = runBlocking {
        HostedWebView.create(activity) { lines += it }.also { page = it }
    }

    /**
     * Polls the page until an expression is true.
     *
     * Deliberately not [HostedWebView.waitForPage]: that one also waits for Apple's CDN script, and
     * these tests want to separate "our page loaded" from "Apple answered".
     */
    private suspend fun HostedWebView.waitFor(script: String, timeout: Duration): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (deadline.hasNotPassedNow()) {
            if (evaluate(script) == "true") {
                return true
            }

            delay(250.milliseconds)
        }

        return false
    }

    @Test
    fun theBundledPageIsServedOverTheOriginTheSignInIsKeyedTo(): Unit = runBlocking {
        val hosted = host()

        assertTrue(
            "musickit-host.js never ran; transcript:\n${lines.joinToString("\n")}",
            hosted.waitFor("!!window.musicKitHost", 30.seconds),
        )

        assertEquals(
            "the origin is part of the user's identity and must not drift",
            "\"https://${HostedWebView.ASSET_HOST}\"",
            hosted.evaluate("window.location.origin"),
        )

        assertEquals(
            "a page that is not a secure context cannot start EME at all",
            "true",
            hosted.evaluate("window.isSecureContext"),
        )

        // This is where MusicKit keeps the Music User Token; a file:// origin has none that works.
        assertEquals(
            "\"ok\"",
            hosted.evaluate(
                "(function () { try { localStorage.setItem('probe', 'ok'); " +
                    "return localStorage.getItem('probe'); } catch (e) { return 'FAILED ' + e; } })()",
            ),
        )
    }

    @Test
    fun theBridgeRoundTripsOneCall(): Unit = runBlocking {
        val hosted = host()
        val bridge = MusicKitBridge(hosted, callTimeout = 30.seconds, authorizeTimeout = 30.seconds)
        hosted.onMessage = bridge::onMessage
        bridge.onLog = { lines += it }

        assertTrue(hosted.waitFor("!!window.musicKitHost", 30.seconds))

        // status needs no MusicKit instance, so this is the bridge and nothing else.
        val status = bridge.status()

        assertNotNull(status)
        assertEquals("a call was left pending after it answered", 0, bridge.pendingCount)
        assertTrue(
            "the page did not report itself unconfigured before configure ran",
            !status.configured,
        )
        Log.i(TAG, "page status: musicKitLoaded=${status.musicKitLoaded} version=${status.version}")
    }

    @Test
    fun appleScriptLoadsFromTheCdn(): Unit = runBlocking {
        val hosted = host()

        // The one part of the page that is a real network fetch, and the one Apple's terms require
        // stays a fetch. A failure here is a connectivity or CDN problem, not a code one.
        assertTrue(
            "MusicKit JS never arrived from js-cdn.music.apple.com; transcript:\n" +
                lines.joinToString("\n"),
            hosted.waitFor("!!window.MusicKit", 60.seconds),
        )
    }

    /**
     * A capability probe, not an assertion about DRM.
     *
     * Whether Widevine is available is a property of the device: emulators are L3-only, and some OEM
     * WebView builds carry no CDM whatsoever. What is asserted is that asking produces an answer
     * rather than hanging — the result itself is recorded for a human to read.
     */
    @Test
    fun theDeviceReportsWhetherItCanDoWidevine(): Unit = runBlocking {
        val hosted = host()
        val bridge = MusicKitBridge(hosted, callTimeout = 30.seconds, authorizeTimeout = 30.seconds)
        hosted.onMessage = bridge::onMessage
        bridge.onLog = { lines += it }

        assertTrue(hosted.waitFor("!!window.musicKitHost", 30.seconds))

        val probe = bridge.probeWidevine()

        Log.w(
            TAG,
            "WIDEVINE CAPABILITY: supported=${probe.supported} " +
                "keySystem=${probe.keySystem} error=${probe.error}",
        )

        assertTrue(
            "the probe answered neither way, which means the question was never asked",
            probe.supported || probe.error != null,
        )
    }

    private companion object {
        const val TAG = "MusicKitInstrumented"
    }
}
