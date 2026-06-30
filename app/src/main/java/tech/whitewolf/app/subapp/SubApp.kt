package tech.whitewolf.app.subapp

import java.net.URI

/** A WWT sub-app hosted in the shell. `host` is derived from [url]. */
data class SubApp(val id: String, val title: String, val url: String) {
    val host: String get() = URI(url).host ?: ""
}
