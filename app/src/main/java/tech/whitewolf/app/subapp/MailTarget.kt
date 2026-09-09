package tech.whitewolf.app.subapp

import java.net.URI
import tech.whitewolf.app.BuildConfig

/** A WWT sub-app hosted in the shell. `host` is derived from [url]. */
data class MailTarget(val id: String, val title: String, val url: String) {
    val host: String get() = URI(url).host ?: ""
}

/** The one mail placeholder, until Task 9 replaces it with a real MailSubApp.
 *  Defined once so the two interim call sites cannot drift apart. */
internal fun mailTarget(): MailTarget =
    MailTarget(id = "mail", title = "Mail", url = BuildConfig.MAIL_BASE_URL)
