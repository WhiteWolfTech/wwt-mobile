package tech.whitewolf.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tech.whitewolf.app.push.PushStatus

private val EXPECTED_HOST = tech.whitewolf.app.BuildConfig.NTFY_HOST

/**
 * Obtainium "Add app" source for the ntfy distributor (documented in docs/PUSH.md).
 * NOTE: this is the ntfy-android APP repo — the plain `ntfy` repo is the server and
 * its releases carry no APK (Obtainium finds "no suitable release" there).
 */
const val NTFY_INSTALL_URL = "https://github.com/binwiederhier/ntfy-android"

/** Guidance copy for a push-status banner, or null when there is nothing to show ([PushStatus.Ok]). */
fun pushBannerText(status: PushStatus): String? = when (status) {
    is PushStatus.Ok -> null
    is PushStatus.NoDistributor ->
        "Notifications are off. In Obtainium, add this app source, then set the " +
            "ntfy server to $EXPECTED_HOST:"
    is PushStatus.WrongServer ->
        "ntfy is installed but pointed at ${status.endpointHost}. Open ntfy and set its " +
            "server to $EXPECTED_HOST for notifications."
}

/**
 * A live status banner shown above the WebView. Guidance text plus, when [installUrl]
 * is set, a copyable install-URL row — no deep-links, no dismiss. It appears only while
 * push is misconfigured and clears itself the moment status returns to [PushStatus.Ok].
 */
@Composable
fun PushStatusBanner(text: String, installUrl: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("pushBanner"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = text)
            if (installUrl != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = installUrl,
                        modifier = Modifier.weight(1f).testTag("pushBannerUrl"),
                    )
                    TextButton(
                        onClick = { copyInstallUrl(context, installUrl) },
                        modifier = Modifier.padding(start = 8.dp).testTag("pushBannerCopy"),
                    ) { Text("Copy") }
                }
            }
        }
    }
}

/**
 * Copy [url] to the clipboard. Confirm with a Toast only below Android 13 — on 13+
 * the system shows its own clipboard confirmation, and a second toast would double up.
 */
private fun copyInstallUrl(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("ntfy install URL", url))
    if (Build.VERSION.SDK_INT <= 32) {
        Toast.makeText(context, "Copied install link", Toast.LENGTH_SHORT).show()
    }
}
