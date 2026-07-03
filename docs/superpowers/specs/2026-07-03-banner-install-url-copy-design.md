# Copyable ntfy Install URL in the NoDistributor Banner — Design Spec

**Date:** 2026-07-03
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / push polish (extends the push-distributor status banner, v0.3.1)

## 1. Context & goal

The push-status banner (shipped v0.3.0, Dismiss removed in v0.3.1) tells the user to
install the ntfy app via Obtainium, but they must find the install URL themselves
(it lives in `docs/PUSH.md`). On-device that means retyping
`https://github.com/binwiederhier/ntfy` into Obtainium's *Add app* field.

**Goal:** the `NoDistributor` banner shows the ntfy Obtainium source URL and offers a
one-tap **Copy** that puts it on the clipboard, ready to paste into Obtainium.

## 2. Scope

**In scope (native, wwt-mobile only):**
- `NoDistributor` banner: guidance copy reworded to introduce the URL; a row below it
  shows the URL as visible text plus a **Copy** `TextButton`.
- Copy writes the full URL to the system clipboard and confirms with a Toast on
  Android < 13 (Android 13+ shows its own system clipboard confirmation).

**Out of scope (unchanged / not needed):**
- **`WrongServer` and `Ok` states** — untouched. `WrongServer` means ntfy is already
  installed; an install URL is irrelevant there.
- **Deep-linking into Obtainium or ntfy** — still deferred (the actionable-banner
  follow-up). Copy-to-clipboard only; no intents, no package assumptions.
- **Backend / web changes** — none.
- **The banner's live-status behavior** (entry/resume/30s-poll re-check, self-clearing,
  no Dismiss) — unchanged.

## 3. Decisions (fixed)

- **URL = `https://github.com/binwiederhier/ntfy`** — the Obtainium GitHub source
  documented in `docs/PUSH.md`. Held as `const val NTFY_INSTALL_URL` in
  `PushStatusBanner.kt`. It is a universal upstream repo, not an
  environment-specific value, so it does **not** go in `BuildConfig`
  (unlike `NTFY_HOST`).
- **Copy button + visible URL** — the URL is shown as text (user sees exactly what
  they'll paste) and the button copies it. No copy-only or long-press-only variants.
- **Displayed text == copied text** — the full `https://…` URL, no shortening.
- **Toast gated to Android < 13** — `Build.VERSION.SDK_INT <= 32` shows
  "Copied install link"; on 13+ the OS overlay already confirms, so no app Toast
  (avoids a double confirmation).
- **`PushStatusBanner` stays caller-driven** — it gains an optional
  `installUrl: String? = null` parameter; `ShellScreen` decides when to pass it
  (only for `NoDistributor`). The composable stays a dumb renderer.

## 4. Components & data flow

All in wwt-mobile.

- **`PushStatusBanner.kt`** (modify, package `tech.whitewolf.app.ui`):
  - `const val NTFY_INSTALL_URL = "https://github.com/binwiederhier/ntfy"` (top-level,
    public so ShellScreen and tests reference it).
  - `pushBannerText(NoDistributor)` copy becomes: *"Notifications are off. In
    Obtainium, add this app source, then set the ntfy server to
    `ntfy.whitewolf.tech`:"* (ends with a colon; the URL row follows visually).
    `Ok` (null) and `WrongServer` copy unchanged.
  - `PushStatusBanner(text: String, installUrl: String? = null, modifier: Modifier =
    Modifier)` — when `installUrl != null`, a `Row` under the guidance `Text` renders
    the URL (`Modifier.weight(1f)`, `testTag("pushBannerUrl")`) and a **Copy**
    `TextButton` (`testTag("pushBannerCopy")`). When null, renders exactly as today.
  - Copy `onClick`: `LocalContext`'s `ClipboardManager` →
    `setPrimaryClip(ClipData.newPlainText("ntfy install URL", installUrl))`; then
    `if (Build.VERSION.SDK_INT <= 32) Toast.makeText(context, "Copied install link",
    Toast.LENGTH_SHORT).show()`.
- **`ShellScreen.kt`** (modify): the banner call becomes
  `PushStatusBanner(text = bannerText, installUrl = if (pushStatus is
  PushStatus.NoDistributor) NTFY_INSTALL_URL else null)`. Nothing else changes.

No new dependencies, permissions, or network calls. Clipboard writes need no
permission on any supported API level (minSdk 29).

## 5. Error handling

- Clipboard write is a synchronous platform call that does not fail in practice; no
  error path is added. If a future platform restricted it, the button would simply
  no-op — the visible URL text remains the manual fallback.
- The visible URL doubles as the degradation path: even if the user misses the
  button, they can read/long-press the text.

## 6. Testing

- **JVM unit** (extend `PushStatusBannerTest`):
  - `pushBannerText(NoDistributor)` equals the new exact string (ends with `:`).
  - `pushBannerText(WrongServer("ntfy.sh"))` unchanged (still names both hosts).
  - `NTFY_INSTALL_URL == "https://github.com/binwiederhier/ntfy"` — guards the
    constant against typos (it is the whole feature).
- **Build gate:** `:app:assembleDebug` (clipboard/Toast/composable are Android-side;
  no emulator).
- **Manual/e2e (operator):** (a) no ntfy → banner shows URL + Copy; (b) tap Copy →
  paste into Obtainium's *Add app* → resolves to the ntfy repo; (c) on Android 13+
  only the system clipboard confirmation appears (no double toast); (d) install via
  Obtainium, set server → banner self-clears (existing behavior); (e) `WrongServer`
  banner shows no URL row.

## 7. Build sequencing (rough)

(1) Update `pushBannerText` copy + `NTFY_INSTALL_URL` + tests (JVM) → (2) banner URL
row + Copy action + `ShellScreen` call-site → (3) manual e2e. One PR (wwt-mobile),
release as **v0.3.2**.

## 8. Follow-ups (logged, not in this sub-project)

- Full actionable banner (deep-link to open Obtainium/ntfy directly) — still the
  existing deferred follow-up; copy-to-clipboard is the low-risk step toward it.
