package com.druware.musickit

import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri

/**
 * Hosts Apple's sign-in popup.
 *
 * MusicKit's `authorize()` calls `window.open`, and Apple's sign-in genuinely needs a surface the
 * user can see and type into. WebView2 opened one by itself, which is why the WPF host only had to
 * *not* handle the event; Android has no such default, so the popup is built here by hand:
 * `WebChromeClient.onCreateWindow` hands back a second, real [WebView] through a
 * [WebView.WebViewTransport], and that WebView is shown in a [Dialog] over the host activity.
 *
 * **Third-party cookies.** From the hidden page's `appassets.androidplatform.net` origin, every
 * cookie `apple.com` sets is a third-party one, and an app targeting Lollipop or later rejects
 * those by default. Without the per-WebView opt-in below the sign-in silently loops.
 *
 * Everything here runs on the main thread, because everything here is a `WebView` or a `View`.
 *
 * @param activity the activity to show the dialog over.
 * @param log takes a diagnostic line. Never given a full URI: the popup's query string carries the
 *   developer token, so only the scheme and host are ever reported.
 */
internal class MusicKitAuthPopup(
    private val activity: Activity,
    private val log: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    private var dialog: Dialog? = null

    /**
     * The WebView inside [dialog], held so it can be released even when the dialog's own dismiss
     * listener never runs — which is exactly what happens when the window it lived in is already
     * gone.
     */
    private var popupView: WebView? = null

    /**
     * Called with a sentence saying why, whenever [open] refuses a `window.open`.
     *
     * A refused window is a sign-in that will never happen, and MusicKit's `authorize()` is at that
     * moment already waiting on the bridge for a user who has no surface to type into. Without this
     * the call sits there until its own three-minute timeout. Wired to the same failure path the
     * renderer-gone signal uses, so the caller gets the same prompt, retryable error.
     */
    var onOpenFailed: ((String) -> Unit)? = null

    /**
     * Answers a `window.open` from the hosted page.
     *
     * @param resultMsg the message Chromium supplied, whose `obj` is the transport the new WebView
     *   must be handed back through.
     * @return true when the popup was opened and the transport answered; false when it was not, in
     *   which case the caller must let Chromium know the window was refused.
     */
    fun open(resultMsg: Message): Boolean {
        if (activity.isFinishing || activity.isDestroyed) {
            return refuse("sign-in popup refused: the host activity is going away")
        }

        // Only one at a time. A second window.open while a sign-in is already up is not something
        // MusicKit does, and stacking dialogs would strand the first.
        dismiss()

        val popup = WebView(activity)

        // Apple's sign-in is a JavaScript application; there is no version of this flow without it.
        // The popup is a throwaway view with no bridge object of its own attached, so nothing this
        // page runs can reach native code.
        @Suppress("SetJavaScriptEnabled")
        popup.settings.javaScriptEnabled = true

        // Apple's sign-in keeps state in web storage between its own steps.
        popup.settings.domStorageEnabled = true

        // The popup is a real browser surface the user drives, so it gets the affordances a
        // sign-in page expects and the hidden host page has no use for.
        popup.settings.setSupportZoom(false)
        popup.settings.loadWithOverviewMode = true
        popup.settings.useWideViewPort = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

        // The override lint asks for is the first one below; it does not find it on an anonymous
        // object declared this way.
        @Suppress("MissingOnRenderProcessGone")
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                log("sign-in popup navigating to ${originOf(request.url)}")

                // Never cancelled. Apple's flow ends by handing a result to the opener, and
                // redirecting or suppressing any part of it breaks sign-in.
                return false
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Handled, so Android kills this renderer rather than the whole host application.
                // The sign-in is lost either way; closing the dialog is what turns that into a
                // failed authorize call the caller can retry, rather than a dead white rectangle.
                log("the sign-in popup's renderer stopped; closing it")
                dismiss()
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // The fallback signal for "the popup is done", because onCloseWindow's firing is
                // not guaranteed: landing back on the host page's own origin means Apple has
                // handed the result to the opener and there is nothing left for the user to do.
                // Waiting for the page to *finish* rather than intercepting the navigation is what
                // lets that hand-off actually run before the WebView goes away.
                if ((url ?: "").toUri().host == HostedWebView.ASSET_HOST) {
                    log("sign-in popup returned to the host origin; closing it")
                    dismiss()
                }
            }
        }

        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                log("sign-in popup closed itself")
                dismiss()
            }
        }

        // Checked before there is a dialog to unwind: a WebView Chromium never took ownership of is
        // this method's to destroy, and it is easier to be sure of that here than in a teardown path.
        val transport = resultMsg.obj as? WebView.WebViewTransport
        if (transport == null) {
            popup.destroy()
            return refuse("sign-in popup refused: window.open carried no transport")
        }

        val shown = Dialog(activity).apply {
            setContentView(
                popup,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )

            // The user can always back out of a sign-in they did not mean to start. The bridge's
            // authorize call is left waiting in that case, and its own timeout ends it.
            setCancelable(true)
            setOnDismissListener { releasePopupView() }
        }

        dialog = shown
        popupView = popup

        // The window is asked for *before* the transport is answered, and that order is the whole
        // of what makes a failure here recoverable. `sendToTarget()` and `return false` are two
        // mutually exclusive answers to the same question: the first tells Chromium the window
        // exists and it is now the popup WebView's owner, the second tells Chromium the window was
        // refused so it can drop the pending contents itself. Doing both — sending the transport
        // and then reporting refusal because show() threw — leaves Chromium unwinding a popup this
        // class is also tearing down, which is a double free waiting to happen. Attaching the new
        // WebView to a hierarchy before answering the transport is also the order Android's own
        // onCreateWindow sample uses, so nothing about the successful path changes.
        try {
            shown.show()
        } catch (error: WindowManager.BadTokenException) {
            // The activity's window token died between the isFinishing check at the top of this
            // method and this line. There is no check that closes that race — the token can go
            // away on any turn of the loop in between — so it is caught rather than prevented.
            dialog = null

            // Cleaned up here by hand: show() throws out of WindowManager.addView before the
            // dialog ever records itself as showing, so its dismiss listener will never run and
            // nothing else is left holding this WebView.
            releasePopupView()

            return refuse("sign-in popup refused: ${error.message}")
        }

        transport.webView = popup
        resultMsg.sendToTarget()

        log("sign-in popup opened")
        return true
    }

    /** Closes the popup if one is open. Safe to call when none is, and safe to call twice. */
    fun dismiss() {
        val open = dialog ?: return
        dialog = null

        // `isShowing` is not a sufficient guard on its own. It stays true after the host activity's
        // window has been detached from the window manager — a rotation or a finish part way
        // through a sign-in — and dismissing then throws IllegalArgumentException out of
        // WindowManagerGlobal.removeView, which is a crash on the main thread. Asking the activity
        // whether it is still alive is what tells the two states apart.
        when {
            !open.isShowing ->
                // Already closed: the user backed out of it, or the page closed it itself.
                Unit

            activity.isFinishing || activity.isDestroyed ->
                log("the sign-in popup's host activity is gone; releasing it without dismissing")

            else -> try {
                open.dismiss()
            } catch (error: IllegalArgumentException) {
                // The window went away between the check above and this call. Nothing can close a
                // window that is already gone, and there is nothing left to repair, but it must
                // not take the process down — which is precisely what it did before this catch.
                log("the sign-in popup's window was already gone: ${error.message}")
            }
        }

        // Whichever of those paths was taken. The dialog's dismiss listener only runs when dismiss()
        // actually ran, so this is what keeps the popup WebView from outliving a window that had
        // already gone; it is a no-op when the listener got there first.
        releasePopupView()
    }

    /**
     * Detaches and destroys the popup WebView. Idempotent.
     *
     * The destroy is posted to the main looper rather than to the view: an unattached View defers
     * everything posted to it until it is next attached, and a popup WebView is detached — or was
     * never attached at all — on every path that reaches here, so `View.post` would silently never
     * run it.
     */
    private fun releasePopupView() {
        val popup = popupView ?: return
        popupView = null

        (popup.parent as? ViewGroup)?.removeView(popup)

        // Destroyed on a later turn of the loop: Chromium is still unwinding the window it just
        // closed, and tearing the WebView down inside that callback is what crashes.
        main.post { popup.destroy() }
    }

    /**
     * Reports that no window will appear.
     *
     * @param reason what happened, as a sentence.
     * @return false, the value that tells Chromium the window was refused.
     */
    private fun refuse(reason: String): Boolean {
        log(reason)
        onOpenFailed?.invoke(reason)
        return false
    }

    private fun originOf(uri: Uri): String = "${uri.scheme}://${uri.host}"
}
