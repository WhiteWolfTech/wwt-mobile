package tech.whitewolf.app.subapp

import tech.whitewolf.app.BuildConfig
import java.net.URI

/**
 * The WWT intranet — the shell's way *out* to the rest of WhiteWolf, deliberately
 * NOT a [SubApp]. Sub-apps are hosted in the WebView; this one is handed to the
 * phone's browser, because NavPolicy pins the WebView to the mail host and the
 * intranet is a different one. Mirrors the mail entry's contract: configured
 * through BuildConfig, never a literal.
 */
object Intranet {
    val url: String = BuildConfig.INTRANET_URL
    val host: String get() = URI(url).host ?: ""
}
