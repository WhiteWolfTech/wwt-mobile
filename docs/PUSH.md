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
   `https://ntfy.whitewolf.tech`.
3. Install/open the **WWT app** (Obtainium) and **allow notifications** when asked.

## Verify
Send yourself an email → a **"New mail"** notification should appear within a few
seconds; tapping it opens the mailbox.

## Notes
- The push carries no email content — only a "new mail" signal; the app shows the
  actual mail when opened.
- If you didn't install a distributor, the app shows a hint and works fine without
  notifications until you do.
- No Google Play Services or foreground service is used.
