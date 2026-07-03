# Push Banner Step Wizard — Design Spec

**Date:** 2026-07-03
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / push polish (reworks the v0.3.2/v0.3.3 banner content)

## 1. Context & goal

The push-status banner is a live indicator: `NoDistributor` → install ntfy,
`WrongServer` → point it at `ntfy.whitewolf.tech`, `Ok` → gone. Since v0.3.2 the
`NoDistributor` state carries a copyable install URL; the `WrongServer` state has
guidance text only, and both states cram their whole instruction into one long
sentence.

**Goal:** present setup as a two-step wizard — short step label, one-line
instruction, copyable URL — with the existing live status machine acting as the
stepper: completing a step advances the banner automatically; completing both
clears it. Also adds the requested copyable server URL to `WrongServer`.

## 2. Scope

**In scope (wwt-mobile only):**
- Replace `pushBannerText` + the `installUrl` parameter with one pure
  `pushBannerContent(status): BannerContent?` (step label + instruction +
  copyable URL per problem state).
- Banner layout: step label line (`labelSmall`) → instruction line (`bodyMedium`)
  → URL + **Copy** row (mechanics unchanged from v0.3.2/v0.3.3).
- `WrongServer` gains its copyable URL: the full `https://ntfy.whitewolf.tech`
  (ntfy's *Default server* field expects a full URL), derived from the existing
  `EXPECTED_HOST` (`BuildConfig.NTFY_HOST`).

**Out of scope (unchanged):**
- The status machine itself — detection, entry/resume/30s-poll re-check,
  self-clearing, no dismiss, `PushStatusBus`, `PushReceiver`.
- Deep-links/intents (still the deferred actionable-banner follow-up), backend/web,
  `docs/PUSH.md` (its long-form steps remain the reference; the banner is the
  terse in-context mirror).

## 3. Decisions (fixed)

- **The state machine is the wizard.** No step state is stored anywhere; the step
  shown is a pure projection of `PushStatus`. `NoDistributor` = step 1,
  `WrongServer` = step 2. A user who lands directly in `WrongServer` simply sees
  step 2 — correct, since step 1 is demonstrably done.
- **Exact content:**
  - `NoDistributor` → label `"Notifications — step 1 of 2"`, instruction
    `"Install ntfy: in Obtainium, add this source:"`, copy URL
    `NTFY_INSTALL_URL` (`https://github.com/binwiederhier/ntfy-android`).
  - `WrongServer` → label `"Notifications — step 2 of 2"`, instruction
    `"In ntfy, set Settings → Default server to:"`, copy URL
    `"https://" + EXPECTED_HOST` (= `https://ntfy.whitewolf.tech`).
  - `Ok` → `null` (no banner).
- **The wrong-host diagnostic is dropped from the copy** (was "pointed at
  {host}") — shorter and less intimidating; `WrongServer(endpointHost)` keeps the
  host in the type for logs/tests, it is just no longer rendered.
- **`copyUrl` is non-nullable inside `BannerContent`** — both problem states
  always have a copy target now; absence of a banner is expressed by the
  function returning `null`, not by empty fields.
- **Copy mechanics unchanged:** same clipboard write, clip label `"ntfy URL"`,
  Toast `"Copied install link"` → generalise to `"Copied"` (it now also copies a
  server URL), still gated to `SDK_INT <= 32`.

## 4. Components & changes

All in `app/src/main/java/tech/whitewolf/app/ui/` unless noted.

- **`PushStatusBanner.kt`** (modify):
  - Add `data class BannerContent(val stepLabel: String, val instruction: String,
    val copyUrl: String)` and the pure
    `fun pushBannerContent(status: PushStatus): BannerContent?` (per §3).
  - Remove `fun pushBannerText(...)` (fully superseded — no other callers).
  - Composable becomes `PushStatusBanner(content: BannerContent, modifier)`:
    `Column` → `Text(stepLabel, style = MaterialTheme.typography.labelSmall)`,
    `Text(instruction, style = MaterialTheme.typography.bodyMedium)`, then the
    existing URL row (`Text(copyUrl, weight(1f), testTag("pushBannerUrl"))` +
    **Copy** `TextButton(testTag("pushBannerCopy"))`). Keep
    `testTag("pushBanner")` on the `Surface`.
  - `copyInstallUrl` → renamed `copyUrl(context, url)`; Toast text `"Copied"`.
  - `NTFY_INSTALL_URL` and `EXPECTED_HOST` stay as-is.
- **`ShellScreen.kt`** (modify): banner block becomes
  ```kotlin
  val bannerContent = pushBannerContent(pushStatus)
  if (bannerContent != null) {
      PushStatusBanner(content = bannerContent)
  }
  ```
  Nothing else changes.

## 5. Error handling

Unchanged from the shipped banner: clipboard write is a synchronous foreground
call with no practical failure path; the visible URL is the manual fallback; a
state change mid-interaction simply recomposes to the new step.

## 6. Testing

- **JVM unit** (rewrite `PushStatusBannerTest`):
  - `pushBannerContent(Ok)` → `null`.
  - `NoDistributor` → exact `stepLabel`/`instruction` strings and
    `copyUrl == NTFY_INSTALL_URL`.
  - `WrongServer("ntfy.sh")` → exact strings and
    `copyUrl == "https://ntfy.whitewolf.tech"` (host not rendered in the copy).
  - `NTFY_INSTALL_URL == "https://github.com/binwiederhier/ntfy-android"`
    (kept — guards the constant).
- **Build gate:** `:app:assembleDebug`. Full JVM suite for regressions.
- **Manual/e2e (operator):** on a device with ntfy on the wrong server: banner
  shows "step 2 of 2" + `https://ntfy.whitewolf.tech` + Copy → paste into ntfy →
  banner self-clears ≤30s. Fresh state (ntfy removed): "step 1 of 2" → install →
  banner advances to step 2 without app relaunch → complete → gone.

## 7. Build sequencing (rough)

Single code task (`BannerContent` + `pushBannerContent` + composable rework +
`ShellScreen` call-site, JVM-tested) → whole-branch review → PR → release
**v0.3.4** on the operator's word.

## 8. Follow-ups (unchanged)

Actionable banner (deep-link to Obtainium/ntfy) remains the logged follow-up;
this wizard formatting is another low-risk step toward it.
