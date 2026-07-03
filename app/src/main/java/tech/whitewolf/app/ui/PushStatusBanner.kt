package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tech.whitewolf.app.push.PushStatus

private const val EXPECTED_HOST = "ntfy.whitewolf.tech"

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
 * A dismissible status banner shown above the WebView. Guidance text + Dismiss only —
 * no actions or deep-links. Visibility and dismissal are decided by the caller.
 */
@Composable
fun PushStatusBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("pushBanner"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        ) {
            Text(text = text, modifier = Modifier.weight(1f))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.padding(start = 8.dp).testTag("pushBannerDismiss"),
            ) { Text("Dismiss") }
        }
    }
}
