# Push notifications (UnifiedPush) — setup

WWT uses UnifiedPush with a self-hosted **ntfy** server (`ntfy.whitewolf.tech`) —
no Google/Firebase. You install one small "distributor" app once; it wakes the WWT
app when new mail arrives.

## One-time device setup
1. Install the **ntfy app via Obtainium** — add source
   `https://github.com/binwiederhier/ntfy-android` (GitHub). When Obtainium asks
   which APK, pick **`ntfy-<version>-fdroid-release.apk`** (the `play` flavor
   expects Firebase, which GrapheneOS doesn't have). Note: the plain
   `binwiederhier/ntfy` repo is the *server* — it has no APK.
2. Open the ntfy app → **Settings → Default server** → set to
   `https://ntfy.whitewolf.tech`. (Opening ntfy once matters — a
   never-opened app can't receive the WWT app's registration.)
3. Install/open the **WWT app** (Obtainium) and **allow notifications** when asked.

### ntfy will ask you about…

On first open (and in its settings), ntfy prompts for a few things. Answer yes to
all three — each is needed for reliable delivery on GrapheneOS:

- **Notification permission** → **Allow.** This is for ntfy's own delivery/status
  notifications; the "New mail" alert itself comes from the WWT app, which asks
  separately (step 3).
- **WebSockets / instant delivery** → **Accept.** With no Google services, ntfy
  keeps its own connection to `ntfy.whitewolf.tech` — this is what makes wake-ups
  instant.
- **Battery optimization exemption** → **Accept.** Stops Android from killing that
  connection in the background; without it, notifications arrive late or not at
  all once the phone dozes.

## Verify
Send yourself an email → a **"New mail"** notification should appear within a few
seconds; tapping it opens the mailbox.

## Notes
- The push carries no email content — only a "new mail" signal; the app shows the
  actual mail when opened.
- If you didn't install a distributor, the app shows a hint and works fine without
  notifications until you do.
- No Google Play Services are used, and the WWT app itself runs no foreground
  service — the persistent delivery connection is ntfy's own.
