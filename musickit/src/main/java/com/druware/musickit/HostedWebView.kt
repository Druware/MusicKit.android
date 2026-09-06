package com.druware.musickit

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The WebView the MusicKit page lives in: created, configured, attached, and eventually torn down.
 *
 * Every member touches a `WebView` and so every member runs on the main thread; the suspending ones
 * marshal there themselves, so callers may be anywhere.
 *
 * @param webView the configured, attached WebView.
 * @param popup the sign-in dialog host.
 */
internal class HostedWebView private constructor(
    private val webView: WebView,
    private val popup: MusicKitAuthPopup,
) : PageChannel {

    private val main = Handler(Looper.getMainLooper())

    /**
     * The way back into the page, handed over with the first message it posts. Volatile because it
     * is written on the main thread and read by [post] from wherever a caller happens to be.
     */
    @Volatile
    private var replyProxy: JavaScriptReplyProxy? = null

    private var closed = false

    /** Called with each message the page posts, on the main thread. */
    var onMessage: ((String) -> Unit)? = null

    /** Called when the renderer dies, with a sentence saying so, on the main thread. */
    var onRendererGone: ((String) -> Unit)? = null

    /**
     * Called when Apple's sign-in popup could not be opened, with a sentence saying why, on the
     * main thread.
     *
     * The page asked for a window and did not get one, so the `authorize()` call already waiting on
     * the bridge has nothing left to wait for; this is what ends it promptly instead of at its own
     * three-minute timeout.
     */
    var onAuthPopupFailed: ((String) -> Unit)? = null

    /**
     * Hands one JSON envelope to the page.
     *
     * @param json the envelope.
     * @throws MusicKitException the page has not spoken yet, so there is no channel back into it.
     */
    // The feature check is in create(): a HostedWebView cannot exist on a WebView that does not
    // support the listener, so by the time there is a proxy to post through it is supported.
    @SuppressLint("RequiresFeature")
    override suspend fun post(json: String) {
        withContext(Dispatchers.Main.immediate) {
            val proxy = replyProxy
                ?: throw MusicKitException(
                    "The Apple Music page is not listening yet.",
                    "PAGE_NOT_READY",
                )

            proxy.postMessage(json)
        }
    }

    /**
     * Waits for the page to be ready to take calls.
     *
     * Ready means three things at once: `musickit-host.js` has run, Apple's CDN script has defined
     * `window.MusicKit`, and the page has posted at least once so there is a reply proxy to answer
     * through. Polled rather than awaited because two of the three are page-side facts.
     *
     * @param timeout how long to keep asking.
     * @return true when the page became ready, false when the timeout ran out first.
     */
    suspend fun waitForPage(timeout: Duration): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (deadline.hasNotPassedNow()) {
            if (replyProxy != null && evaluate("!!(window.musicKitHost && window.MusicKit)") == "true") {
                return true
            }

            delay(POLL_INTERVAL)
        }

        return false
    }

    /**
     * Runs one expression in the page and returns its result as JSON.
     *
     * @param script the expression to evaluate.
     * @return the JSON encoding of the value it produced, or `"null"`.
     */
    suspend fun evaluate(script: String): String = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { value ->
                continuation.resume(value ?: "null")
            }
        }
    }

    /**
     * Clears everything the hosting origin has stored.
     *
     * Origin-scoped on purpose: the host application is free to use WebViews of its own, and a
     * blanket clear would take their storage with it.
     */
    suspend fun clearOriginStorage() {
        withContext(Dispatchers.Main.immediate) {
            WebStorage.getInstance().deleteOrigin("https://$ASSET_HOST")
        }
    }

    /** Closes Apple's sign-in dialog if one is open. */
    fun dismissAuthPopup() {
        main.post { popup.dismiss() }
    }

    /** Tears the page down. Idempotent. */
    suspend fun close() {
        withContext(Dispatchers.Main.immediate) {
            if (closed) {
                return@withContext
            }

            closed = true
            popup.dismiss()

            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_OBJECT) }
            }

            replyProxy = null
            webView.stopLoading()
            webView.webChromeClient = null
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    companion object {

        /**
         * The host of the origin the page is served from.
         *
         * **This is part of the user's identity and may never change.** MusicKit keeps the Music
         * User Token in this origin's `localStorage`, so moving the page to any other host — a
         * custom domain, a different path authority, anything — is indistinguishable to MusicKit
         * from moving to a brand new website, and silently signs every user out. Google reserves
         * `appassets.androidplatform.net` for exactly this use and guarantees it collides with no
         * real site, which is why it is preferred over a domain this project does not own.
         */
        const val ASSET_HOST = "appassets.androidplatform.net"

        /** The page's address. Requests to it are intercepted; nothing is ever fetched over the network. */
        const val PAGE_URL = "https://$ASSET_HOST/assets/musickit/index.html"

        /** The name the bridge channel is injected into the page under. */
        const val BRIDGE_OBJECT = "musicKitBridge"

        private val ALLOWED_ORIGINS = setOf("https://$ASSET_HOST")

        private val POLL_INTERVAL = 250.milliseconds

        /**
         * Creates the WebView, configures it, attaches it, and starts the page loading.
         *
         * @param activity the activity to park the page in.
         * @param log takes a diagnostic line.
         * @return the hosted page, which is loading but not yet ready — see [waitForPage].
         * @throws MusicKitException there is no usable WebView on this device.
         */
        // Guarded a dozen lines below by an isFeatureSupported check that throws rather than
        // continuing; lint only recognises the check when it wraps the call in an if.
        @SuppressLint("RequiresFeature")
        suspend fun create(activity: Activity, log: (String) -> Unit): HostedWebView =
            withContext(Dispatchers.Main.immediate) {
                // Android has no "runtime not installed" failure the way WebView2 does, but the
                // system WebView can be disabled or missing on OEM and enterprise builds, and the
                // failure without this check is an opaque crash inside the WebView constructor.
                val provider = WebViewCompat.getCurrentWebViewPackage(activity)
                    ?: throw MusicKitException(
                        "This device has no Android System WebView, so Apple Music cannot be used.",
                        "WEBVIEW_MISSING",
                    )

                log("Android System WebView ${provider.versionName}")

                // The bridge is scoped to one origin by the platform, which is the whole reason it
                // is preferred over addJavascriptInterface — that one is reachable from every
                // origin the WebView ever loads. Feature-detected and failed loudly rather than
                // fallen back on, because the fallback would be the insecure one.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    throw MusicKitException(
                        "This device's Android System WebView is too old to talk to Apple Music " +
                            "safely. Updating Android System WebView from the Play Store fixes it.",
                        "WEBVIEW_UNSUPPORTED",
                    )
                }

                val webView = try {
                    WebView(activity)
                } catch (error: RuntimeException) {
                    throw MusicKitException(
                        "The Android System WebView could not be started: ${error.message}",
                        "WEBVIEW_FAILED",
                        error,
                    )
                }

                applySettings(webView.settings)

                val popup = MusicKitAuthPopup(activity, log)
                val hosted = HostedWebView(webView, popup)

                popup.onOpenFailed = { reason -> hosted.onAuthPopupFailed?.invoke(reason) }

                val assetLoader = WebViewAssetLoader.Builder()
                    // The default domain, deliberately: see ASSET_HOST.
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
                    .build()

                // The override lint asks for is declared below; it does not find it on an
                // anonymous object declared this way.
                @Suppress("MissingOnRenderProcessGone")
                webView.webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        val reason =
                            "The Apple Music page's renderer stopped" +
                                if (detail.didCrash()) " because it crashed." else " because Android reclaimed it."

                        log(reason)
                        hosted.onRendererGone?.invoke(reason)

                        // Handled, so Android kills this renderer rather than the whole app.
                        return true
                    }
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        // Without this, EME never starts: WebView has no permission UI of its own,
                        // so an ungranted PROTECTED_MEDIA_ID makes
                        // navigator.requestMediaKeySystemAccess reject silently and every Apple
                        // Music track fails to play, on a page that works in Chrome.
                        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                            log("granting protected media playback to the page")
                            request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                            return
                        }

                        // Nothing else is asked for, and nothing else is given.
                        log("denying an unexpected page permission request")
                        request.deny()
                    }

                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message,
                    ): Boolean = popup.open(resultMsg)

                    override fun onCloseWindow(window: WebView) {
                        popup.dismiss()
                    }
                }

                WebViewCompat.addWebMessageListener(
                    webView,
                    BRIDGE_OBJECT,
                    ALLOWED_ORIGINS,
                    object : WebViewCompat.WebMessageListener {
                        override fun onPostMessage(
                            view: WebView,
                            message: WebMessageCompat,
                            sourceOrigin: android.net.Uri,
                            isMainFrame: Boolean,
                            replyProxy: JavaScriptReplyProxy,
                        ) {
                            // Only the page itself. A frame Apple's script might create is not the
                            // bridge's counterpart, even though it shares the origin.
                            if (!isMainFrame) {
                                return
                            }

                            hosted.replyProxy = replyProxy

                            val data = message.data ?: return

                            // The listener makes no promise about which thread it arrives on, and
                            // everything downstream of it ends up back at the WebView. Marshalled
                            // once, here, so nothing after this has to think about it.
                            hosted.main.post { hosted.onMessage?.invoke(data) }
                        }
                    },
                )

                // 1x1 and attached, never 0x0 and never detached: Chromium tears a renderer's
                // surface down on View.onDetachedFromWindow, not on a view being small, and a
                // WebView with no window never reliably runs JS or starts a media pipeline at all.
                // Nothing here is ever shown; one pixel in the corner of the content view is the
                // price of a renderer that stays alive.
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                content.addView(webView, ViewGroup.LayoutParams(1, 1))

                webView.loadUrl(PAGE_URL)
                log("hosting the Apple Music page at https://$ASSET_HOST")

                hosted
            }

        /**
         * Applies every setting the hosted page depends on.
         *
         * Separated from [create] so the settings can be asserted without a device.
         *
         * @param settings the WebView's settings.
         */
        fun applySettings(settings: WebSettings) {
            // MusicKit JS is the entire page; without this there is nothing to host. The only
            // code that ever runs here is Apple's own SDK and the bundled host script, and the
            // bridge that reaches native is scoped to this one origin.
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true

            // Where MusicKit keeps the Music User Token. Without it the user signs in again on
            // every launch, because localStorage silently does nothing.
            settings.domStorageEnabled = true

            // play() is called from the bridge, and a page nobody can see never receives the tap
            // Chromium would otherwise insist on. Without this, play() resolves as though it
            // worked and no audio is ever produced — a silent, total failure.
            settings.mediaPlaybackRequiresUserGesture = false

            // MusicKit's authorize() calls window.open to show Apple's sign-in. Both of these are
            // needed before Chromium will even raise onCreateWindow for it.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true

            // The page is served over https by WebViewAssetLoader, so it needs no file access of
            // any kind; leaving these on would only widen what a compromised page could reach.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
        }
    }
}
