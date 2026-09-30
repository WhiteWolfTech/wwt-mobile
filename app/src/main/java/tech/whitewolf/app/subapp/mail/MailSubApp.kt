package tech.whitewolf.app.subapp.mail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.StateFlow
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
 * [online] supplies the live connectivity `StateFlow` (not a snapshot `Boolean`):
 * [Content] collects it with `collectAsState()`, so a real connectivity change recomposes
 * this composable directly — which both keeps the offline/online error copy live and lets
 * [MailContent]'s own auto-retry effect (keyed on `errored`/`online`) actually observe an
 * offline->online transition, rather than only whatever value happened to be current the
 * last time [Content] recomposed for some unrelated reason.
 */
class MailSubApp(
    private val url: String,
    private val scopes: SubAppScopes,
    private val token: () -> String?,
    private val online: () -> StateFlow<Boolean>,
) : SubApp {
    override val id = ID
    override val title = "Mail"
    override val icon: ImageVector = Icons.Filled.Email

    companion object {
        /** Mail's id: the one definition, so nothing else declares its own `SubAppId("mail")`
         *  literal. `MailPush` takes it as a constructor argument (see `AppContainer`) rather
         *  than redeclaring it — a future rename here would otherwise leave routing and the
         *  notification's tap target pointed at two different ids with no compiler to catch it. */
        val ID = SubAppId("mail")
    }

    @Composable
    override fun Content(host: SubAppHost, modifier: Modifier) {
        val ctx = LocalContext.current
        val scope = scopes.getOrPut(id) { MailScope(newSession(ctx, url, token())) }
        val isOnline by online().collectAsState()
        MailContent(
            session = scope.session,
            url = url,
            sessionToken = token(),
            host = host,
            online = isOnline,
            modifier = modifier.testTag("subapp.${id.value}"),
        )
    }
}
