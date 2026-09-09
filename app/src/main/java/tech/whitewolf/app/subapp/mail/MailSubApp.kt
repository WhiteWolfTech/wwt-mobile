package tech.whitewolf.app.subapp.mail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import tech.whitewolf.app.subapp.Retained
import tech.whitewolf.app.subapp.SubApp
import tech.whitewolf.app.subapp.SubAppHost
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.SubAppScopes

/** The retained half of mail, held in the sub-app's own scope. */
class MailScope(val session: MailWebSession) : Retained {
    override fun onDiscard() = session.destroy()
}

/**
 * Mail as a sub-app. Constructed with its URL and a token supplier — the shell never
 * learns mail has an origin.
 *
 * [token] is called fresh on every [Content] recomposition (cheap; only actually used
 * once, at first attach — [MailContent]'s `AndroidView` factory runs once per retained
 * session) and again, once, inside the [scopes] `getOrPut` block that seeds the very
 * first session.
 *
 * [online] is a plain snapshot read, not a Compose-observed one: it is re-evaluated
 * whenever [Content] itself recomposes, but a StateFlow flip inside [MailContent] (e.g.
 * `session.errored`) does not by itself force this composable to recompose, so the
 * offline/online copy on the error screen can lag behind a real connectivity change until
 * something else (a route change, a push-status tick) causes [Content] to run again. Not
 * fixed here: matches [token]'s own "supplier now, `StateFlow` later" shape, which Task 19
 * is expected to widen for both.
 */
class MailSubApp(
    private val url: String,
    private val scopes: SubAppScopes,
    private val token: () -> String?,
    private val online: () -> Boolean,
) : SubApp {
    override val id = SubAppId("mail")
    override val title = "Mail"
    override val icon: ImageVector = Icons.Filled.Email

    @Composable
    override fun Content(host: SubAppHost, modifier: Modifier) {
        val ctx = LocalContext.current
        val scope = scopes.getOrPut(id) { MailScope(newSession(ctx, url, token())) }
        MailContent(
            session = scope.session,
            url = url,
            sessionToken = token(),
            host = host,
            online = online(),
            modifier = modifier.testTag("subapp.${id.value}"),
        )
    }
}
