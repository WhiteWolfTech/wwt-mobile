package tech.whitewolf.app.subapp.mail

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
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
import tech.whitewolf.app.web.NavPolicy
import tech.whitewolf.app.web.ShellBridge

private const val WAKE_JS = "window.wwtWake && window.wwtWake()"

// Spinner runtime for the wake-refresh path: the SPA gives no completion
// signal, and its refresh fetch is fast — a fixed short spin reads as "done".
private const val REFRESH_SPINNER_MS = 800L

// Offline-aware auto-retry cadence (docs/superpowers/specs/2026-07-07-offline-error-
// handling-design.md): how often to retry while online and the error screen is up.
private const val ERROR_RETRY_MS = 30_000L

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

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest,
            ): Boolean {
                val reqUrl = request.url.toString()
                return if (NavPolicy.isInApp(reqUrl, allowedHost)) {
                    false // let the WebView load it
                } else {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reqUrl)))
                    } catch (e: ActivityNotFoundException) {
                        Log.w("MailContent", "No app to open external link: $reqUrl")
                    }
                    true // handled externally
                }
            }

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

/** Copy for the main-frame load-error screen: offline vs server-unreachable. */
internal fun errorMessageFor(online: Boolean, title: String): String =
    if (online) "Couldn't reach $title."
    else "You're offline. Waiting for a connection…"
