# WrongServer State-Entry Trigger (v0.3.6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The force-fresh re-registration fires when status *becomes* `WrongServer` (state-entry trigger), closing the cold-start gap where the stale endpoint arrives after the resume replay has already run.

**Architecture:** One `LaunchedEffect(pushStatus)` in `ShellScreen` calling `pushManager.reregister()` when the status is `WrongServer`. Bounded by value equality: a still-wrong server yields an equal `WrongServer(host)`, `StateFlow` dedupes it, and an unchanged key does not restart the effect — exactly one attempt per distinct wrong-host entry. The resume trigger (`recheck(true)`) stays for "fixed while away", where the value never changes.

**Tech Stack:** Kotlin, Jetpack Compose. No new tests possible (pure effect wiring, no emulator) — build gate + full JVM suite + operator e2e.

## Global Constraints

- **Single PR, wwt-mobile only.** Release as **v0.3.6** on the operator's word after merge.
- **Everything else in `ShellScreen` is untouched:** `recheck` (`(Boolean) -> Unit`), the entry `recheck(false)`, the resume observer's `recheck(true)`, the 30s poll (`recheck(false)`), `isProblem`, `notificationsEnabled`, banner call, `signOut`. `PushManager`, `PushStatus`, `PushStatusBus`, `PushReceiver`, `PushStatusBanner` — all untouched.
- **Toolchain:** env from `~/.bashrc`; `./gradlew` from `/home/dev/mobile-app` with the Bash sandbox disabled. Gates: `:app:testDebugUnitTest` + `:app:assembleDebug`.

---

### Task 1: State-entry trigger in `ShellScreen`

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt` (one insertion)

**Interfaces:**
- Consumes: existing `pushStatus` (collected state), `PushStatus.WrongServer`, `pushManager.reregister()`.
- Produces: nothing new.

- [ ] **Step 1: Insert the effect**

In `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt`, directly AFTER the resume `DisposableEffect(lifecycleOwner) { ... }` block (the one whose observer calls `recheck(true)`) and BEFORE the periodic-poll comment, insert:

```kotlin
    // State-entry trigger for the fresh re-registration: on a cold start the stale
    // endpoint arrives AFTER the resume replay has already run (the process-fresh bus
    // still held Ok at that instant), so the resume path alone never re-registers.
    // Fires once whenever status BECOMES WrongServer; bounded because a still-wrong
    // server returns an equal WrongServer(host) — StateFlow dedupes it and an unchanged
    // key does not restart this effect. The resume trigger still covers "fixed while
    // away", where the value never changes.
    LaunchedEffect(pushStatus) {
        if (pushStatus is PushStatus.WrongServer) pushManager.reregister()
    }
```

(No import changes — `LaunchedEffect`, `PushStatus`, and `pushManager` are already in scope.)

- [ ] **Step 2: Run all gates**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt
git commit -m "fix(push): re-register on WrongServer state entry, not just resume (cold-start gap)"
```

---

### Task 2: Review + PR + release

**Files:** none.

- [ ] **Step 1: Review** the branch diff (single insertion; verify no other hunks).

- [ ] **Step 2: Push + PR** (standing preference) with this operator checklist:

- [ ] (a) **Cold-start replay:** point ntfy back at `ntfy.sh`, cold-start WWT → step 2 shows (one fresh-registration attempt, still wrong); set ntfy's server to `https://ntfy.whitewolf.tech`, kill WWT, cold-start it → banner appears briefly (or not at all) and self-clears within seconds, with NO app switch.
- [ ] (b) Push delivers end-to-end afterwards.

- [ ] **Step 3: Release v0.3.6** after merge, on the operator's word.
