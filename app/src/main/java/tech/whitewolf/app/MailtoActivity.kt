package tech.whitewolf.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import tech.whitewolf.app.mailto.MailtoIntent
import tech.whitewolf.app.push.DeepLink
import tech.whitewolf.app.subapp.WakePayload
import tech.whitewolf.app.subapp.mail.MailSubApp

/**
 * The app's mailto: handler (WWT-253): a no-UI trampoline that turns the link into a
 * `wwt://subapp/mail?compose=…` deep link and forwards it to [MainActivity]. Mail then
 * opens Compose filled in from the link; nothing is ever sent automatically.
 *
 * Why a trampoline rather than intent filters on MainActivity (verified against
 * AOSP/Chromium source):
 *
 * - Browsers (Chromium, so Vanadium too) add NEW_TASK|CLEAR_TOP to external intents. On
 *   the standard-launchMode MainActivity, CLEAR_TOP alone finishes the live instance and
 *   creates a fresh one — killing anything above it (an SSO tab, an attachment viewer)
 *   and leaving the retained WebView PAUSED, because the new activity's
 *   MailWebSession.onAttached() runs before the old one's onDetached().
 * - Callers that don't pass NEW_TASK (Contacts, or our own in-message mailto: going out
 *   through the chooser) would get a SECOND MainActivity in the caller's task, which
 *   steals the WebView (MailContent re-parents the retained container), leaving the first
 *   shell blank and then paused.
 *
 * So this activity forwards with NEW_TASK|SINGLE_TOP|CLEAR_TOP, and two settings are
 * load-bearing:
 *
 * - CLEAR_TOP clears this trampoline and anything above MainActivity in the app task, and
 *   together with SINGLE_TOP delivers onNewIntent to the EXISTING MainActivity instead of
 *   recreating it.
 * - `taskAffinity=""` (manifest) keeps the trampoline out of the app task: without it, on
 *   a cold launch the trampoline would become the task's root.
 *
 * `singleTask` on MainActivity would also avoid the duplicate, and was rejected: it
 * changes launcher-icon behaviour, clearing the SSO/viewer activities on an icon tap.
 *
 * Theme.NoDisplay requires finish() before onCreate returns, so it is unconditional —
 * a link this app can't use simply does nothing.
 */
class MailtoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // runCatching: this activity is exported, and a hostile caller can hand it extras
        // that throw on unparcel (getExtras deserialises lazily, on the first read).
        val mailto = runCatching {
            MailtoIntent.toMailto(
                intent.dataString,
                intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList(),
                intent.getStringArrayExtra(Intent.EXTRA_CC)?.toList(),
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            )
        }.getOrNull()
        if (mailto != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setData(DeepLink.build(WakePayload(MailSubApp.ID, compose = mailto)))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    ),
            )
        }
        finish()
    }
}
