# Wake-to-Sync Bridge (native → JS) — Design Spec

**Date:** 2026-07-02
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / sub-project 4 (the first JS↔native bridge, consuming push)

## 1. Context & goal

Push notifications now deliver a data-light `{"type":"new_mail"}` wake-up to the
device (shipped: wwt-mobile PR #5). Today `PushReceiver.onMessage` only posts a
generic "New mail" notification; tapping it brings the **already-loaded** WebView
to the front, where the mail SPA stays stale until its next 45-second poll.

**Goal:** when a wake-up arrives, make the mail SPA **refresh now** rather than only
notifying/opening. When the app is in the foreground, refresh silently (no
notification); when backgrounded, notify and refresh on return. This is the first
JS↔native bridge and is intentionally minimal: **native → JS only**.

The SPA already has the refresh machinery — `Inbox` has a `refresh()` callback, a
45s poll, and a `refreshSignal` prop (`sentTick`) that forces an immediate refetch
when its value changes. The bridge reuses that path: native bumps the signal.

## 2. Scope

**In scope:**
- **Native (wwt-mobile):** a process-scoped wake signal; foreground-aware handling
  in `PushReceiver.onMessage` (foreground → refresh + suppress notification;
  background → notification + pending wake); deliver the wake to the live WebView
  via `evaluateJavascript`; refresh on resume when a wake is pending. Introduce a
  `WwtApp : Application` to host a single app-scoped `AppContainer`, track
  foreground state, and own the wake signal.
- **Web (email-client-maileroo):** expose `window.wwtWake()` that bumps a tick fed
  into `Inbox`'s existing `refreshSignal`. No new fetch logic.

**Out of scope (deferred / not needed):**
- **JS → native** direction (`@JavascriptInterface`) — no feature needs it yet
  (badge counts, mark-read, native compose stay deferred). YAGNI.
- **Rich/deep-link notifications** — push is generic "New mail" with no thread id,
  so tapping opens the inbox, not a specific thread.
- **Native fetching mail content** — content never traverses native (push design
  §6); the SPA fetches over its own API.
- **Whole-WebView reload on wake** — would discard SPA state (open thread, scroll,
  drafts) and re-auth; the SPA's `refreshSignal` is far lighter.
- **Startup-speed optimizations** (WebView pre-warm; SPA persisting its last list to
  localStorage for instant paint) — real but separate from wake-to-sync; logged as a
  follow-up (§10), not built here.

## 3. Decisions (fixed)

- **Foreground push → silent live refresh, no notification.** A notification for
  mail that just appeared on-screen is noise.
- **Background push → notification (as today) + pending wake**; the refresh happens
  when the app next comes to the foreground.
- **Bridge is native → JS only** for v1, via
  `WebView.evaluateJavascript("window.wwtWake && window.wwtWake()")`.
- **Wake is level-triggered** ("something changed, refetch"), carries no data, and
  coalesces multiple pushes into one refresh.
- **`pendingWake` lives in memory only** and is **gated to background pushes** — it
  is never persisted across process death, and a foreground push never sets it.
  This is deliberate (see §5 cold start): persisting it or setting it on every push
  would cause a redundant double-fetch on the next launch/resume.

## 4. Architecture & components

### Native (wwt-mobile) — units with clear boundaries

- **`WwtApp : Application`** — the process root. Builds one `AppContainer` and
  exposes it (`(context.applicationContext as WwtApp).container`), replacing the
  per-call `AppContainer(...)` construction in `MainActivity` and
  `PushReceiver` (resolves the push-review follow-up). Registers
  `ActivityLifecycleCallbacks` to maintain a `foreground: Boolean` (started-activity
  count > 0). Owns the `WakeBus`.
- **`WakeBus`** — the process-scoped signal. Holds:
  - `tick: StateFlow<Long>` — bumped by `signalWake()` for live foreground refresh;
  - `pendingWake: Boolean` — set when a wake arrives while backgrounded, consumed
    (and cleared) on the next foreground resume.
  - Methods: `signalWakeForeground()` (bump tick only), `signalWakeBackground()`
    (set `pendingWake` only), `consumePending(): Boolean` (returns and clears).
  JVM-testable (no Android types in its core logic).
- **`PushReceiver.onMessage`** (modified) — reads `WwtApp.foreground`:
  - foreground → `wakeBus.signalWakeForeground()`, **post no notification**;
  - background → `Notifications.showNewMail(context)` **and**
    `wakeBus.signalWakeBackground()`.
  The notify-vs-wake choice is a pure function `wakeAction(foreground): WakeAction`
  (`{Foreground, Background}`) so it is unit-testable without a receiver.
- **`SubAppWebView`** (modified) — retains a reference to the created `WebView`;
  collects `wakeBus.tick`; when the tick changes **and** the page has finished
  loading, calls `evaluateJavascript("window.wwtWake && window.wwtWake()", null)`.
  A tick that arrives before `onPageFinished` is applied once loaded (StateFlow
  holds the latest value → no missed wake).
- **`MainActivity` / `ShellScreen`** (modified) — on resume (foreground), if
  `wakeBus.consumePending()` is true, poke the wake once the page is loaded. Cold
  start needs no poke (the SPA fetches on mount — §5).
- **`AndroidManifest.xml`** (modified) — `android:name=".WwtApp"` on `<application>`.

### Web (email-client-maileroo)

- **`App.tsx`** (modified) — add `const [wakeTick, setWakeTick] = useState(0)`;
  in a `useEffect`, set `window.wwtWake = () => setWakeTick((n) => n + 1)` and
  remove it on cleanup; pass `refreshSignal={sentTick + wakeTick}` to `Inbox`.
  (`Inbox` already refetches when `refreshSignal` changes.) A small typed global
  declaration (`declare global { interface Window { wwtWake?: () => void } }`)
  keeps TypeScript happy. In a plain browser (no native shell) nothing calls
  `wwtWake`, so behavior is unchanged.

## 5. Data flow

**Foreground push (app open on the inbox):**
new mail → backend fan-out → ntfy → `PushReceiver.onMessage` → `foreground == true`
→ `wakeBus.signalWakeForeground()` bumps `tick`, **no notification** →
`SubAppWebView` collector sees the new tick (page already loaded) →
`evaluateJavascript("window.wwtWake && window.wwtWake()")` → SPA `setWakeTick` →
`Inbox.refreshSignal` changes → `refresh()` refetches → inbox updates silently.

**Background push (app not foreground):**
`…onMessage` → `foreground == false` → post "New mail" notification **and**
`wakeBus.signalWakeBackground()` (`pendingWake = true`) → user taps (or reopens) →
`MainActivity` (SINGLE_TOP) resumes → `wakeBus.consumePending()` == true → after
`onPageFinished`, poke `wwtWake` once → `pendingWake` cleared.

**Cold start (process was dead):**
tap launches the app → WebView loads the SPA → `Inbox` mount effect fetches the
latest mail → **already fresh** (the backend stored the mail before sending the
push). No poke: `pendingWake` did not survive process death (by design), and the
mount fetch already retrieved the new mail — an extra poke would be a redundant
second fetch. This is the freshness optimum for cold start.

## 6. Error handling

- **WebView not ready when a tick arrives** — the collector pokes only after
  `onPageFinished`; the StateFlow holds the latest tick, so the wake is applied when
  the page is ready. No missed wake.
- **`window.wwtWake` absent** (older SPA, or JS not yet initialized) — the `&&`
  guard makes `evaluateJavascript` a no-op; the 45s poll is the backstop, so mail
  still arrives, just not instantly.
- **Coalesced / rapid pushes** — level-triggered wake collapses multiple pushes into
  one refresh; no data rides the signal, so nothing is lost.
- **App killed before tap** — cold start's mount fetch covers it; `pendingWake`
  legitimately does not survive process death.
- **Foreground/background race** (push lands exactly as the app changes state) —
  worst case is a redundant notification *or* a redundant refresh, never a lost
  update; the poll backstops either way.
- **Multiple WebView instances / recomposition** — the wake targets the live
  `WebView` held by the current `SubAppWebView`; on dispose the reference is cleared
  so a stale instance is never poked.

## 7. Security & privacy

- The bridge is **native → JS only**; no `@JavascriptInterface` is added, so no new
  JS-reachable native surface. `evaluateJavascript` runs a fixed, literal string
  (`"window.wwtWake && window.wwtWake()"`) — no interpolation, no data from the push
  is ever placed into JS.
- The wake carries no mail content (consistent with push design §6). Worst case if
  spuriously triggered: an extra harmless refetch over the already-authenticated
  session.
- WebView hardening is unchanged (JS enabled as today for the SPA; no file/content
  access; `MIXED_CONTENT_NEVER_ALLOW`; nav policy intact).

## 8. Permissions after this sub-project

Unchanged: `INTERNET`, `POST_NOTIFICATIONS`. No new permissions, no foreground
service.

## 9. Testing

- **Web (Vitest):** `window.wwtWake()` increments the tick so the combined
  `refreshSignal` handed to `Inbox` increases; with no native shell present,
  behavior is unchanged. Optionally assert `Inbox` refetches when `refreshSignal`
  changes (existing behavior).
- **Native JVM unit:** `WakeBus` — `signalWakeForeground()` bumps the tick and does
  **not** set `pendingWake`; `signalWakeBackground()` sets `pendingWake` and does
  **not** bump the tick; `consumePending()` returns true once then false. The pure
  `wakeAction(foreground)` returns `Foreground`/`Background` correctly.
- **Instrumented (written, deferred — no emulator):** `onMessage` while foregrounded
  pokes the WebView and posts no notification; while backgrounded posts one
  notification and sets pending; resume with a pending wake triggers exactly one
  `evaluateJavascript`.
- **Manual/e2e (operator):** app open on the inbox → send a test mail → inbox
  updates within ~1s with no notification. Background the app → send a test mail →
  "New mail" notification → tap → inbox is fresh on open.

## 10. Follow-ups (logged, not in this sub-project)

- **Startup speed on cold start** (freshness is already optimal; speed is the only
  remaining lever): WebView pre-warm, and/or the SPA persisting its last inbox page
  to localStorage for an instant paint-then-refetch. Optional performance work.
- **JS → native channel** when a feature needs it (badge/unread count, mark-read
  propagation, native compose/share) — would add a minimal `@JavascriptInterface`
  with its own security review.

## 11. Build sequencing (rough)

(1) Web: add `window.wwtWake()` + `wakeTick` in `App.tsx` (+ Vitest); ship/deploy so
the hook exists → (2) Native: `WakeBus` (JVM-tested) → (3) `WwtApp` + manifest +
route `AppContainer` through it → (4) foreground-aware `PushReceiver.onMessage`
(pure `wakeAction` tested) → (5) `SubAppWebView` tick delivery + resume/pending
poke in the shell → (6) manual e2e.

## 12. Repos & PRs

Two cross-repo PRs, sequenced **web-first** so the `wwtWake` hook is deployed before
the native side calls it (the `&&` guard means order is not strictly required, but
web-first avoids a window where native pokes a missing function):
- **email-client-maileroo:** the `App.tsx` hook + Vitest (small).
- **wwt-mobile:** `WakeBus`, `WwtApp`, manifest, `PushReceiver`, `SubAppWebView`,
  shell wiring, JVM tests.
