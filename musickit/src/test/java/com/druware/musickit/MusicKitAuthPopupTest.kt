package com.druware.musickit

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.WindowManager
import android.webkit.WebView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog

/**
 * What the sign-in popup does when the window it lives in has gone away underneath it.
 *
 * A user signing in on a real device rotated the screen part way through, which destroyed the host
 * activity; the queued dismiss then threw `IllegalArgumentException: View not attached to window
 * manager` out of `Dialog.dismiss` and killed the process. Every test here is about that: the
 * activity dying between a popup being opened and something getting round to closing it.
 *
 * **What Robolectric can and cannot show.** Its window manager records views rather than owning
 * them, so a destroyed activity's dialog is still dismissable there and the production
 * `IllegalArgumentException` cannot be provoked from a unit test. What is asserted instead is the
 * decision that prevents it — that a dismiss against a dead activity takes the release-without
 * -dismissing path — plus the leak that path has to avoid. Both fail against the old code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicKitAuthPopupTest {

    private val controller: ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).setup()

    private val activity: Activity = controller.get()

    private val log = mutableListOf<String>()

    private val refusals = mutableListOf<String>()

    private val popup = MusicKitAuthPopup(activity) { log += it }.apply {
        onOpenFailed = { refusals += it }
    }

    @After
    fun drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `dismissing when nothing is open does nothing at all`() {
        popup.dismiss()

        assertTrue("a dismiss with no popup open should not have anything to say", log.isEmpty())
    }

    @Test
    fun `dismissing twice does not throw`() {
        openPopup()

        popup.dismiss()
        popup.dismiss()
    }

    @Test
    fun `dismissing after the host activity is destroyed does not throw`() {
        openPopup()
        controller.pause().stop().destroy()

        // The crash on the device. Nothing may escape this call.
        popup.dismiss()

        assertTrue(
            "a dismiss against a destroyed activity must take the release-without-dismissing " +
                "path, not call Dialog.dismiss and hope: $log",
            log.any { it.contains("host activity is gone") },
        )
    }

    @Test
    fun `dismissing after the host activity is finishing does not throw`() {
        openPopup()
        activity.finish()

        popup.dismiss()

        assertTrue(
            "isFinishing is the same detached window as isDestroyed, one loop turn earlier: $log",
            log.any { it.contains("host activity is gone") },
        )
    }

    @Test
    fun `the popup WebView is released even when the window it lived in is already gone`() {
        val opened = openPopup()
        assertNotNull("the popup should be in the dialog's content view", opened.parent)

        controller.pause().stop().destroy()
        popup.dismiss()
        shadowOf(Looper.getMainLooper()).idle()

        // The dialog's own dismiss listener is what normally releases this, and it never runs when
        // the dialog is never dismissed. Without the unconditional release in dismiss(), the popup
        // WebView outlives the activity that owned it.
        assertNull("the popup WebView outlived a window that was already gone", opened.parent)

        // Detaching is not releasing. The destroy has to be posted to the main looper rather than
        // to the view, because a dialog hands its dismiss listener a view that has *already* been
        // detached, and an unattached View holds everything posted to it until it is next attached
        // — which, for a popup on its way out, is never.
        assertTrue(
            "the popup WebView was detached but never destroyed, which is the leak",
            shadowOf(opened).wasDestroyCalled(),
        )
    }

    @Test
    fun `opening is refused, and reported, when the host activity is already gone`() {
        controller.pause().stop().destroy()

        val message = transportMessage()
        assertFalse("Chromium must be told the window was refused", popup.open(message))
        assertTrue("an authorize() is waiting on a window that will never appear", refusals.isNotEmpty())
    }

    @Test
    fun `a window open carrying no transport is refused, and reported`() {
        val message = Message.obtain(Handler(Looper.getMainLooper()))

        assertFalse(popup.open(message))
        assertTrue(refusals.any { it.contains("no transport") })
    }

    /**
     * Opens a popup the way `onCreateWindow` does.
     *
     * @return the WebView handed back through the transport, which is the popup's own.
     */
    private fun openPopup(): WebView {
        val message = transportMessage()
        val transport = message.obj as WebView.WebViewTransport

        assertTrue("the popup should open over a live activity", popup.open(message))

        return requireNotNull(transport.webView) { "open() answered the transport with nothing" }
    }

    private fun transportMessage(): Message {
        val message = Message.obtain(Handler(Looper.getMainLooper()))
        message.obj = WebView(activity).WebViewTransport()
        return message
    }
}

/**
 * The one case a dialog can fail to appear at all: the activity's window token dies between the
 * liveness check at the top of `open` and the `show` at the bottom of it.
 *
 * Split into its own class because the shadow below makes every dialog in it unshowable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowDialogWithDeadToken::class])
class MusicKitAuthPopupShowFailureTest {

    private val controller: ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).setup()

    private val log = mutableListOf<String>()

    private val refusals = mutableListOf<String>()

    private val popup = MusicKitAuthPopup(controller.get()) { log += it }.apply {
        onOpenFailed = { refusals += it }
    }

    @Test
    fun `a show that fails cleans up and reports the window as refused`() {
        val message = Message.obtain(Handler(Looper.getMainLooper()))
        val webView = WebView(controller.get())
        val transport = webView.WebViewTransport()
        message.obj = transport

        assertFalse("Chromium must be told the window was refused", popup.open(message))

        // The transport was never answered, which is the half of the protocol that matters: having
        // both sent it and reported refusal would leave Chromium unwinding a popup this class is
        // also tearing down.
        assertNull("a refused window must not also have been handed a WebView", transport.webView)

        assertTrue("the waiting authorize() call has to be told: $log", refusals.isNotEmpty())

        // Nothing is left holding the popup, so a later dismiss finds nothing and does nothing.
        popup.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
    }
}

/** A window manager that has already forgotten the activity's token, as a rotation leaves it. */
@Implements(Dialog::class)
class ShadowDialogWithDeadToken : ShadowDialog() {

    @Implementation
    override fun show() {
        throw WindowManager.BadTokenException("Unable to add window -- token null is not valid")
    }
}
