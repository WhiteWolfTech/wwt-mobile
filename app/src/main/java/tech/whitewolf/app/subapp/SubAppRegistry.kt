package tech.whitewolf.app.subapp

import tech.whitewolf.app.BuildConfig

/**
 * The ordered registry of WWT sub-apps. Mail is the only entry today; adding a
 * future sub-app is one new entry here. The shell auto-opens [default] until a
 * launcher UI exists (2+ sub-apps).
 */
object SubAppRegistry {
    private val mail = MailTarget(id = "mail", title = "Mail", url = BuildConfig.MAIL_BASE_URL)

    fun all(): List<MailTarget> = listOf(mail)
    fun default(): MailTarget = all().first()
}

