package com.druware.musickit

import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The WebView settings the hosted page depends on.
 *
 * Robolectric runs no JS engine and paints nothing, so this is the whole of what can be asserted
 * without a device: that each load-bearing switch is in the position the page needs. Page load,
 * bridge round trips, EME and audio all belong to the instrumented tier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostedWebViewSettingsTest {

    private val settings =
        WebView(RuntimeEnvironment.getApplication()).settings.also(HostedWebView::applySettings)

    @Test
    fun `javascript is on, because MusicKit JS is the entire page`() {
        assertTrue(settings.javaScriptEnabled)
    }

    @Test
    fun `DOM storage is on, because that is where the Music User Token lives`() {
        assertTrue(
            "without localStorage the user signs in again on every launch",
            settings.domStorageEnabled,
        )
    }

    @Test
    fun `playback needs no user gesture, because a hidden page never receives one`() {
        assertFalse(
            "play() would resolve as though it worked and produce no audio",
            settings.mediaPlaybackRequiresUserGesture,
        )
    }

    @Test
    fun `the page may open windows, because that is how Apple's sign-in appears`() {
        assertTrue("window.open would never reach onCreateWindow", settings.supportMultipleWindows())
        assertTrue(settings.javaScriptCanOpenWindowsAutomatically)
    }

    @Test
    fun `the page gets no file or content access, because it is served over https`() {
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
    }

    @Test
    fun `the origin the page is served from is the one the token is keyed to`() {
        // Changing either of these signs every existing user out, so they are pinned by a test
        // rather than left to a careless edit.
        assertEquals("appassets.androidplatform.net", HostedWebView.ASSET_HOST)
        assertEquals(
            "https://appassets.androidplatform.net/assets/musickit/index.html",
            HostedWebView.PAGE_URL,
        )
    }

    @Test
    fun `the bridge object is named the same on both sides`() {
        assertEquals("musicKitBridge", HostedWebView.BRIDGE_OBJECT)
    }
}
