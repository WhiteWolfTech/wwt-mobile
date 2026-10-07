package tech.whitewolf.app.subapp.mail

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.net.URI
import kotlinx.coroutines.delay
import tech.whitewolf.app.auth.sessionCookieLine
import tech.whitewolf.app.subapp.SubAppHost
import tech.whitewolf.app.web.AttachmentDownloads
import tech.whitewolf.app.web.DownloadPolicy
import tech.whitewolf.app.web.NavPolicy
import tech.whitewolf.app.web.ShellBridge

private const val WAKE_JS = "window.wwtWake && window.wwtWake()"

// Spinner runtime for the wake-refresh path: the SPA gives no completion
// signal, and its refresh fetch is fast — a fixed short spin reads as "done".
private const val REFRESH_SPINNER_MS = 800L

// Offline-aware auto-retry cadence (docs/superpowers/specs/2026-07-07-offline-error-
// handling-design.md): how often to retry while online and the error screen is up.
private const val ERROR_RETRY_MS = 30_000L

// Leak guard for the throwaway popup WebView (see popupClient): a popup that never
// navigates — window.open() with no URL — is destroyed after this long regardless.
private const val POPUP_LEAK_GUARD_MS = 10_000L

/**
 * Whether mail's own back handler should be armed. History back is suppressed while the
 * load-error screen is up: canGoBack can still be true there, and walking history behind
 * an error screen instead of returning to the launcher reads as the app being stuck.
 */
internal fun mailBackEnabled(canGoBack: Boolean, errored: Boolean): Boolean =
    canGoBack && !errored

/**
 * Mail's hosted-web content: a retained WebView wrapped in pull-to-refresh, wired to
 * [session] rather than to composable-local state so re-attaching a retained view into a
 * fresh composition (leaving the launcher and coming back) does not lose history tracking
 * or replay a stale reload. Deliberately mail-shaped, not a generic web-content composable
 * — a second web-hosted sub-app can generalise this one.
 *
 * [sessionToken] is CONSTRUCTED in, not fetched: a sub-app must not reach into the app
 * singleton for its own dependencies (that reappears as a hidden shell coupling this
 * package exists to remove). The caller (Task 9's `MailSubApp`) reads it from
 * `AppContainer.auth`; a later task widens this to a `StateFlow<String?>` for refresh.
 *
 * [online] must be a live, Compose-observed value (the caller collects a connectivity
 * `StateFlow` before calling in), not a one-off snapshot — the offline-aware auto-retry
 * below is keyed on it and needs to see a real offline->online transition, not just
 * whatever value happened to be current the last time this composable's caller recomposed
 * for some unrelated reason.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MailContent(
    session: MailWebSession,
    url: String,
    sessionToken: String?,
    host: SubAppHost,
    online: Boolean,
    modifier: Modifier,
) {
    val pageLoaded by session.pageLoaded.collectAsState()
    val canGoBack by session.canGoBack.collectAsState()
    val errored by session.errored.collectAsState()

    // System back walks the WebView history (the SPA creates real entries for
    // thread/compose navigation and seeds a base entry under deep links). At
    // the history root the handler disables itself and default back applies.
    // Suppressed while the error screen is up (see mailBackEnabled).
    BackHandler(enabled = mailBackEnabled(canGoBack, errored)) { session.goBack() }

    // Re-entering composition (from the launcher, or another sub-app) primes state from
    // the live view and resumes JS timers; leaving pauses them. State is observed via
    // collectAsState() above, not a bound listener callback, so there is nothing to
    // unbind on the way out.
    DisposableEffect(session) {
        session.onAttached()
        onDispose {
            session.onDetached()
        }
    }

    // host.wake is a level-triggered counter, not an event stream: a tick covers both
    // arms (foreground refresh and a wake that also notified), applied once the page is
    // ready, whether the wake arrived while this composable was on screen or the user
    // only reaches it afterwards. session.lastWakeSeen is retained on the session (not a
    // composable-local var) so re-entering mail does not re-fire a wake already handled.
    val tick by host.wake.collectAsState()
    LaunchedEffect(tick, pageLoaded) {
        if (pageLoaded && tick > session.lastWakeSeen) {
            session.lastWakeSeen = tick
            session.evaluateJavascript(WAKE_JS)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Restored verbatim (including this comment) from ShellScreen.kt at 2254e96, where
    // this lived before the error screen moved into MailContent. A second, INDEPENDENT
    // retry trigger from the cadence effect below, not a replacement for it — the cadence
    // effect may be mid-delay when this fires, and that is fine: session.retry() is
    // idempotent (clears `errored`, reloads), so at most one of the two ends up reloading
    // a page the other is also mid-reloading, which is harmless. Matches the original's
    // shape rather than inventing coordination between the two triggers.
    //
    // DisposableEffect(lifecycleOwner) re-registers only when lifecycleOwner itself
    // changes (essentially never, for the hosting Activity), so the LifecycleEventObserver
    // lambda below is created ONCE and closes over whatever it captures BY VALUE at that
    // moment. `online` is a plain Boolean parameter — a raw capture would freeze it at
    // MailContent's first composition and never see a later connectivity change, silently
    // breaking exactly the resume-driven retry this effect exists to provide (or, the
    // other direction, retrying while genuinely offline). rememberUpdatedState is the
    // canonical fix: currentOnline always reads the LATEST `online` without forcing the
    // observer to be torn down and re-registered on every connectivity flap. errored does
    // NOT need this treatment — it is read via session.errored.value directly, the live
    // source, rather than through the collectAsState() delegate above.
    val currentOnline by rememberUpdatedState(online)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Reopening the app is the natural "try again" moment: if the
                // error screen is up and we're online, retry without waiting
                // for the 30s tick.
                if (session.errored.value && currentOnline) session.retry()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Offline-aware auto-retry (docs/superpowers/specs/2026-07-07-offline-error-handling-
    // design.md): a load failure no longer latches forever. Immediate retry when a usable
    // connection (re)appears; every ERROR_RETRY_MS while online (short server blips);
    // never while offline. Each retry waits for RESUMED so nothing reloads from the
    // background. session.retry() clears `errored` BEFORE reloading, so a repeat failure
    // is a false->true transition this effect's key sees — not true->true, which a plain
    // StateFlow write would dedupe away and this effect would never restart from.
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(errored, online) {
        val cameOnline = online && !wasOnline
        wasOnline = online
        if (!errored || !online) return@LaunchedEffect
        if (!cameOnline) delay(ERROR_RETRY_MS)
        while (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            delay(ERROR_RETRY_MS)
        }
        session.retry()
    }

    if (errored) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(errorMessageFor(online, "Mail"))
            Button(
                onClick = { session.retry() },
                modifier = Modifier.padding(top = 12.dp).testTag("retry"),
            ) { Text("Retry") }
        }
    } else {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                // Re-attaching the retained container: AndroidView parents the returned
                // view in its own holder, so handing back a view that still has a parent
                // from the previous composition throws "the specified child already has
                // a parent" — detach first.
                session.container?.also { existing ->
                    (existing.parent as? ViewGroup)?.removeView(existing)
                } ?: buildContainer(ctx, session, url, sessionToken).also { session.container = it }
            },
        )
    }
}

/**
 * Builds a brand-new [MailWebSession] around a brand-new [WebView], then immediately
 * runs it through [buildContainer] so the cookie is seeded and the first [WebView.loadUrl]
 * fires as soon as the sub-app's scope is created, without waiting for [MailContent]'s
 * `AndroidView` factory. The WebView built HERE is the one [MailWebSession.web] wraps, so
 * back/reload/wake (which all act through `session.web`) and what actually renders on
 * screen can never diverge — see the cast note in [buildContainer]. Called once per
 * sub-app scope by `MailSubApp` via `SubAppScopes.getOrPut`; re-attaching after a
 * launcher round-trip reuses the retained [MailWebSession.container] instead of calling
 * this again, so [buildContainer]'s own call site inside [MailContent] below only ever
 * runs when it is handed a session that did NOT come through here.
 */
internal fun newSession(ctx: Context, url: String, sessionToken: String?): MailWebSession {
    val session = MailWebSession(AndroidWebViewHandle(WebView(ctx)))
    session.container = buildContainer(ctx, session, url, sessionToken)
    return session
}

/** The existing WebView + SwipeRefreshLayout construction, moved verbatim from
 *  ui/SubAppWebView.kt. Runs once per session — on re-attach [MailContent] returns the
 *  retained [MailWebSession.container] instead of calling this again. */
private fun buildContainer(
    ctx: Context,
    session: MailWebSession,
    url: String,
    sessionToken: String?,
): SwipeRefreshLayout {
    val bridge = ShellBridge()
    var refreshLayout: SwipeRefreshLayout? = null
    // session.web already wraps the WebView this session drives, and the cast below is
    // safe rather than defensive: MailSubApp constructs the AndroidWebViewHandle and the
    // MailWebSession together (Task 9), so the two can never disagree here — a mismatch
    // fails loudly at that construction site, not silently at this cast. WebViewHandle
    // itself stays narrow (canGoBack/goBack/reload/loadUrl/evaluateJavascript/pause/
    // resume/destroy) so MailWebSession is JVM-testable with a fake; MailContent is
    // Compose UI and isn't JVM-testable regardless, so it — not the shared interface —
    // is where the concrete View type belongs.
    val wv = (session.web as AndroidWebViewHandle).view
    val allowedHost = URI(url).host ?: ""

    wv.apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        run {
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
        }
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true

        // Honor system dark mode: when the host DayNight theme is dark, let the
        // WebView report `prefers-color-scheme: dark` so the (dark-aware) SPA
        // themes itself rather than staying light. Feature-guarded — without
        // support the page simply renders light. (WWT-91)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }

        // One-way SPA → shell signal gating pull-to-refresh (see ShellBridge).
        // Main-frame navigation is pinned to allowedHost by NavPolicy, and the
        // interface carries a single boolean — no data is exposed.
        addJavascriptInterface(bridge, "WwtShell")

        // Attachment links (WWT-238). The server sends them as downloads, and a
        // WebView silently drops a download no one handles — so every attachment
        // link did nothing in the app. DownloadPolicy refuses anything but the mail
        // host (the fetch carries the session cookie); the file is fetched into
        // app-private storage and opened in a viewer the user picks.
        setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            val plan = DownloadPolicy.plan(dlUrl, contentDisposition, mimeType, allowedHost)
            if (plan == null) {
                Log.w("MailContent", "Refused download outside the mail host: $dlUrl")
                return@setDownloadListener
            }
            AttachmentDownloads.start(ctx, plan, userAgent)
        }

        // Links inside an email (WWT-219). The SPA renders the message body in a sandboxed
        // iframe (allow-same-origin allow-popups allow-popups-to-escape-sandbox, no
        // allow-top-navigation) and the server gives every absolute link target="_blank".
        // With multiple windows unsupported, Chromium retargets a _blank click at the top
        // frame — which the sandbox forbids navigating — so the click was silently dropped
        // and shouldOverrideUrlLoading below never fired: tapping a link did nothing.
        // Supporting multiple windows turns that click into onCreateWindow instead, where
        // popupClient routes it exactly like any other link (verified on Pixel 8/A14,
        // Galaxy S25/A15 and S20/A10).
        settings.setSupportMultipleWindows(true)
        webChromeClient = popupClient(ctx, allowedHost)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest,
            ): Boolean = openExternally(ctx, request.url.toString(), allowedHost)

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError,
            ) {
                if (request.isForMainFrame) session.notifyMainFrameError()
            }

            override fun onPageFinished(view: WebView, url: String) {
                session.notifyPageFinished()
                refreshLayout?.isRefreshing = false
            }

            override fun doUpdateVisitedHistory(
                view: WebView, url: String?, isReload: Boolean,
            ) {
                // Fires for full loads AND the SPA's pushState/hash entries,
                // keeping the BackHandler's enablement in sync.
                session.notifyHistoryChanged()
            }
        }

        // Seed the session cookie from the stored token NOW (the WebView's cookie
        // store is live at this point) so the SPA loads already authenticated.
        // The cookie is committed before loadUrl via the setCookie callback to
        // avoid a race between seeding and the first request.
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        session.seededToken = sessionToken
        if (sessionToken != null) {
            cm.setCookie(url, sessionCookieLine(sessionToken)) {
                cm.flush()
                loadUrl(url)
            }
        } else {
            loadUrl(url)
        }
    }

    return SwipeRefreshLayout(ctx).apply {
        refreshLayout = this
        // Explicit MATCH_PARENT params: without them the WebView is added
        // with default WRAP_CONTENT layout params, and Chromium then sizes
        // the page's CSS layout viewport to 0px tall (vh/dvh/100% all
        // collapse) even though the view itself is drawn full-size — the
        // 2026-07-07 blank-app incident.
        addView(
            wv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // Arm the gesture ONLY when the SPA says its list is visible and at
        // the top ("child can scroll up" everywhere else, so drags scroll).
        setOnChildScrollUpCallback { _, _ -> !bridge.atTop }
        setOnRefreshListener {
            // Reads the session's live value, not a composition-local: this listener
            // outlives whichever composition installed it.
            if (session.pageLoaded.value) {
                wv.evaluateJavascript(WAKE_JS, null)
                postDelayed({ isRefreshing = false }, REFRESH_SPINNER_MS)
            } else {
                // Page never finished loading — a real reload both refreshes
                // and recovers; onPageFinished stops the spinner.
                wv.reload()
            }
        }
    }
}

/**
 * The single link-routing decision, shared by the main WebViewClient and the popup path
 * (WWT-219) so the two cannot drift. Returns false for an in-app URL (NavPolicy) — the
 * caller lets the WebView load it — and true when the URL was handed to [launch] (an
 * ACTION_VIEW in production). A missing handler is logged and still counts as handled:
 * the WebView must not fall back to loading a foreign page itself.
 *
 * [launch] is a parameter rather than a Context so this stays JVM-testable.
 */
internal fun openExternally(url: String, allowedHost: String, launch: (String) -> Unit): Boolean {
    if (NavPolicy.isInApp(url, allowedHost)) return false // let the WebView load it
    try {
        launch(url)
    } catch (e: ActivityNotFoundException) {
        Log.w("MailContent", "No app to open external link: $url")
    }
    return true // handled externally
}

private fun openExternally(ctx: Context, url: String, allowedHost: String): Boolean =
    openExternally(url, allowedHost) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }

/**
 * Mail's WebChromeClient (WWT-219). Until WWT-219 the WebView had NO WebChromeClient, and
 * this client is deliberately shaped to change nothing about that except popups:
 *
 * - onCreateWindow: target="_blank" / window.open land here once multiple windows are
 *   supported (see buildContainer). A throwaway, never-attached WebView receives the
 *   popup; its first navigation is routed through [openExternally] like any other link
 *   (an in-app URL loads in the main WebView instead) and the throwaway is destroyed.
 *   Only user gestures are honoured — script-opened popups are refused, as before.
 * - onJsAlert/onJsConfirm/onJsPrompt/onJsBeforeUnload: with no client at all, WebView
 *   cancels every JS dialog (and beforeunload = stay). A client that does NOT override
 *   these makes WebView show system dialogs instead, so they are required, not optional:
 *   the SPA is deliberately native-dialog-free and must stay that way.
 * - Nothing else is overridden. In particular onShowCustomView/onHideCustomView must stay
 *   undeclared so fullscreen, the file chooser, geolocation and permission requests
 *   behave exactly as they did with no client.
 */
private fun popupClient(ctx: Context, allowedHost: String) = object : WebChromeClient() {
    override fun onCreateWindow(
        view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message,
    ): Boolean {
        if (!isUserGesture) return false
        val temp = WebView(view.context)
        // temp is never attached to a window, so temp.post{} would NEVER run (View.post
        // queues until attach) — that leaked every popup in testing. Destroy through the
        // main looper instead, outside the WebViewClient callback. Idempotent: routing,
        // a renderer crash and the leak guard can each ask.
        val ui = Handler(Looper.getMainLooper())
        var destroyed = false
        fun destroyTemp() {
            if (destroyed) return
            destroyed = true
            ui.post { temp.destroy() }
        }
        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                v: WebView, request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                if (!openExternally(ctx, url, allowedHost)) view.loadUrl(url)
                destroyTemp()
                return true
            }

            // A throwaway popup's renderer dying must not take the app down (the default
            // returns false, which makes WebView kill the host process).
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                destroyTemp()
                return true
            }
        }
        ui.postDelayed({ destroyTemp() }, POPUP_LEAK_GUARD_MS)
        (resultMsg.obj as WebView.WebViewTransport).webView = temp
        resultMsg.sendToTarget()
        return true
    }

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel(); return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel(); return true
    }

    override fun onJsPrompt(
        view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult,
    ): Boolean {
        result.cancel(); return true
    }

    // cancel() = stay on the page, matching the no-client default.
    override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel(); return true
    }
}

/** Copy for the main-frame load-error screen: offline vs server-unreachable. */
internal fun errorMessageFor(online: Boolean, title: String): String =
    if (online) "Couldn't reach $title."
    else "You're offline. Waiting for a connection…"
