package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tech.whitewolf.app.push.PushStatus

private val EXPECTED_HOST = tech.whitewolf.app.BuildConfig.NTFY_HOST

/** Guidance copy for a push-status banner, or null when there is nothing to show ([PushStatus.Ok]). */
fun pushBannerText(status: PushStatus): String? = when (status) {
    is PushStatus.Ok -> null
    is PushStatus.NoDistributor ->
        "Notifications are off. Install the ntfy app via Obtainium and set its " +
            "server to $EXPECTED_HOST."
    is PushStatus.WrongServer ->
        "ntfy is installed but pointed at ${status.endpointHost}. Open ntfy and set its " +
            "server to $EXPECTED_HOST for notifications."
}

/**
 * A live status banner shown above the WebView. Guidance text only — no actions,
 * deep-links, or dismiss. It appears only while push is misconfigured and clears itself
 * the moment status returns to [PushStatus.Ok].
 */
@Composable
fun PushStatusBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("pushBanner"),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
