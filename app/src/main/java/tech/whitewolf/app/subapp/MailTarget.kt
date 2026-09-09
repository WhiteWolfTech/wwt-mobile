package tech.whitewolf.app.subapp

import java.net.URI
import tech.whitewolf.app.BuildConfig

/** Mail's origin, hosted in the shell. `host` is derived from [url].
 *
 *  No `id`/`title` here: those live on `MailSubApp` (see its `ID` companion constant),
 *  the one place that also hands them to `MailPush`. This type used to carry its own
 *  `id`/`title` fields, but only `.url` was ever read — a second, unread `SubAppId("mail")`
 *  literal that could drift from the real one without anything using it to notice. */
data class MailTarget(val url: String) {
    val host: String get() = URI(url).host ?: ""
}

/** Mail's URL, read once by `AppContainer` to build both the auth base URL and the real
 *  `MailSubApp`. */
internal fun mailTarget(): MailTarget = MailTarget(url = BuildConfig.MAIL_BASE_URL)
