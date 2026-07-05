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

/** One wizard step of the push-setup banner: label, instruction, and an optional URL to copy. */
data class BannerContent(val stepLabel: String, val instruction: String, val copyUrl: String?)

/**
 * Project the current push status onto banner content. The live status machine IS the
 * wizard — no step state is stored: NoDistributor = step 1 (install ntfy),
 * WrongServer = step 2 (point it at the WWT server), Ok + notifications blocked = a
 * final unnumbered step, Ok + enabled = null (no banner). Transport problems win over
 * the permission state.
 */
fun pushBannerContent(status: PushStatus, notificationsEnabled: Boolean): BannerContent? =
    when (status) {
        is PushStatus.NoDistributor -> BannerContent(
            stepLabel = "Notifications — step 1 of 2",
            instruction = "Install and open ntfy: in Obtainium, add this source:",
            copyUrl = NTFY_INSTALL_URL,
        )
        is PushStatus.WrongServer -> BannerContent(
            stepLabel = "Notifications — step 2 of 2",
            instruction = "In ntfy, set Settings → Default server to:",
            copyUrl = "https://$EXPECTED_HOST",
        )
        is PushStatus.Ok -> if (notificationsEnabled) null else BannerContent(
            stepLabel = "Notifications — one last thing",
            instruction = "Allow notifications for WWT in Settings → Apps → WWT → Notifications.",
            copyUrl = null,
        )
    }

/**
 * A live status banner shown above the WebView, presenting push setup as a two-step
 * wizard: step label, one-line instruction, and a copyable URL. No actions, deep-links,
 * or dismiss — it appears only while push is misconfigured and clears itself the moment
 * status returns to [PushStatus.Ok].
 */
@Composable
fun PushStatusBanner(content: BannerContent, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("pushBanner"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = content.stepLabel, style = MaterialTheme.typography.labelSmall)
            Text(text = content.instruction, style = MaterialTheme.typography.bodyMedium)
            val url = content.copyUrl
            if (url != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = url,
                        modifier = Modifier.weight(1f).testTag("pushBannerUrl"),
                    )
                    TextButton(
                        onClick = { copyUrl(context, url) },
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
private fun copyUrl(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("ntfy URL", url))
    if (Build.VERSION.SDK_INT <= 32) {
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}
