# Push Wizard Robustness (v0.3.5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The push wizard verifies WWT's own notification permission (final unnumbered step), survives the stale-registration trap (force fresh registration on resume in `WrongServer`), and closes the stopped-state trap (step 1 says "install **and open**").

**Architecture:** `pushBannerContent` gains a `notificationsEnabled: Boolean` parameter and a third (permission) step; `BannerContent.copyUrl` becomes nullable and the URL row conditional. The permission state lives in `ShellScreen` (NOT `PushStatusBus`, which `onNewEndpoint` overwrites), refreshed by `recheck`, which becomes `(forceFresh: Boolean) -> Unit`: resume passes `true`, and in `WrongServer` that triggers `PushManager.reregister()` (unregister + register) so ntfy creates a fresh registration against its *current* default server.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `NotificationManagerCompat`, UnifiedPush connector. JVM unit tests (JUnit4). No emulator.

## Global Constraints

- **Single PR, wwt-mobile only.** Release as **v0.3.5** on the operator's word after merge.
- **Exact content (binding):**
  - `NoDistributor` → stepLabel `"Notifications — step 1 of 2"`, instruction `"Install and open ntfy: in Obtainium, add this source:"` (NEW — "and open"), copyUrl `NTFY_INSTALL_URL`.
  - `WrongServer` → unchanged: `"Notifications — step 2 of 2"` / `"In ntfy, set Settings → Default server to:"` / `"https://" + EXPECTED_HOST`.
  - `Ok` + notifications blocked → stepLabel `"Notifications — one last thing"`, instruction `"Allow notifications for WWT in Settings → Apps → WWT → Notifications."`, copyUrl `null`.
  - `Ok` + notifications enabled → `null`.
- **Transport wins:** steps 1–2 render whenever the distributor state is a problem, regardless of the permission flag.
- **Permission state lives OUTSIDE `PushStatusBus`** — a `notificationsEnabled` boolean in `ShellScreen`, starting `true` (no flicker), refreshed inside `recheck`.
- **Detection:** `NotificationManagerCompat.areNotificationsEnabled()` AND the `Notifications.CHANNEL_ID` channel importance != `IMPORTANCE_NONE`; a missing channel counts as enabled.
- **Force-fresh re-registration ONLY on resume** (`recheck(true)`), only when a distributor is present and status is `WrongServer`. Entry and the 30s poll use `recheck(false)`.
- **Poll gate extends:** `isProblem = pushStatus !is PushStatus.Ok || !notificationsEnabled`.
- **Unchanged:** `PushStatus`, `PushStatusBus`, `PushReceiver`, `NTFY_INSTALL_URL`, `EXPECTED_HOST`, clipboard/toast mechanics, testTags, no deep-links/intents, no new deps/permissions. Em-dash (—) and arrow (→) in copy are the real characters.
- **Toolchain:** env from `~/.bashrc`; `./gradlew` from `/home/dev/mobile-app` with the Bash sandbox disabled. Gates: `:app:testDebugUnitTest` + `:app:assembleDebug`.

---

### Task 1: Wizard robustness — permission step, "install and open" copy, `reregister()`, ShellScreen wiring

**One task by necessity:** changing `pushBannerContent`'s signature breaks `ShellScreen`'s call site, and `:app:testDebugUnitTest` compiles the main source set — so the content change and the wiring cannot be built (or committed) separately.

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/push/PushManager.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt`
- Test: `app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt` (whole-file replacement)

**Interfaces:**
- Consumes: existing `PushStatus`, `PushStatusBus`, `NTFY_INSTALL_URL`, `EXPECTED_HOST`, `Notifications.CHANNEL_ID`, `UnifiedPush.unregisterApp`.
- Produces: `data class BannerContent(val stepLabel: String, val instruction: String, val copyUrl: String?)`, `fun pushBannerContent(status: PushStatus, notificationsEnabled: Boolean): BannerContent?`, `PushManager.reregister()`. No later code tasks.

- [ ] **Step 1: Write the failing tests**

Replace the entire contents of `app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt` with:

```kotlin
package tech.whitewolf.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.whitewolf.app.push.PushStatus

class PushStatusBannerTest {
    @Test fun okWithNotificationsEnabledHasNoBanner() {
        assertNull(pushBannerContent(PushStatus.Ok, notificationsEnabled = true))
    }

    @Test fun okWithNotificationsBlockedIsTheFinalStep() {
        assertEquals(
            BannerContent(
                stepLabel = "Notifications — one last thing",
                instruction = "Allow notifications for WWT in Settings → Apps → WWT → Notifications.",
                copyUrl = null,
            ),
            pushBannerContent(PushStatus.Ok, notificationsEnabled = false),
        )
    }

    @Test fun noDistributorIsStepOneAndWinsOverBlockedNotifications() {
        assertEquals(
            BannerContent(
                stepLabel = "Notifications — step 1 of 2",
                instruction = "Install and open ntfy: in Obtainium, add this source:",
                copyUrl = NTFY_INSTALL_URL,
            ),
            pushBannerContent(PushStatus.NoDistributor, notificationsEnabled = false),
        )
    }

    @Test fun wrongServerIsStepTwoAndWinsOverBlockedNotifications() {
        assertEquals(
            BannerContent(
                stepLabel = "Notifications — step 2 of 2",
                instruction = "In ntfy, set Settings → Default server to:",
                copyUrl = "https://ntfy.whitewolf.tech",
            ),
            pushBannerContent(PushStatus.WrongServer("ntfy.sh"), notificationsEnabled = false),
        )
    }

    @Test fun installUrlIsTheNtfyAndroidAppRepo() {
        assertEquals("https://github.com/binwiederhier/ntfy-android", NTFY_INSTALL_URL)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.ui.PushStatusBannerTest'`
Expected: FAIL — test-source compilation error (`pushBannerContent` has no `notificationsEnabled` parameter; `copyUrl = null` not allowed).

- [ ] **Step 3: Update `PushStatusBanner.kt`**

Three edits (the file otherwise stays as-is — imports, `EXPECTED_HOST`, `NTFY_INSTALL_URL`, clipboard helper unchanged):

Edit 3a — make `copyUrl` nullable in the data class:

```kotlin
/** One wizard step of the push-setup banner: label, instruction, and an optional URL to copy. */
data class BannerContent(val stepLabel: String, val instruction: String, val copyUrl: String?)
```

Edit 3b — replace the whole `pushBannerContent` function (new signature, new step-1 copy, new final step):

```kotlin
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
```

Edit 3c — in the `PushStatusBanner` composable, make the URL row conditional. Replace the current unconditional `Row(...)` block inside the `Column` with:

```kotlin
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
```

(The two `Text` lines for `stepLabel`/`instruction` above it are unchanged.)

- [ ] **Step 4: Add `reregister()` to `PushManager`**

In `app/src/main/java/tech/whitewolf/app/push/PushManager.kt`, add after `disable()`:

```kotlin
    /**
     * Drop the current registration and register anew. Needed after the distributor's
     * server changes: ntfy pins a registration to the server that was its default when
     * the registration was created, so enable() alone returns the stale endpoint forever.
     */
    fun reregister() {
        UnifiedPush.unregisterApp(context)
        enable()
    }
```

- [ ] **Step 5: Update `ShellScreen.kt`**

Five edits:

Edit 5a — add three imports (alongside the existing ones):

```kotlin
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import tech.whitewolf.app.push.Notifications
```

Edit 5b — add the permission state and replace the `recheck` closure. Change:

```kotlin
    val pushStatus by pushStatusBus.status.collectAsState()
```

to:

```kotlin
    val pushStatus by pushStatusBus.status.collectAsState()
    var notificationsEnabled by remember { mutableStateOf(true) }
```

and replace the existing `recheck` closure (and its comment) with:

```kotlin
    // Re-drive push status from the current distributor state; also refresh whether WWT
    // can actually show notifications. Used on entry, resume, and the periodic poll.
    // forceFresh (resume only): in WrongServer, re-register from scratch — ntfy pins a
    // registration to the server that was its default when the registration was created,
    // so a plain register returns the stale endpoint forever after the user fixes the
    // server. unregister+register makes ntfy issue a fresh one against its current server.
    val recheck: (Boolean) -> Unit = { forceFresh ->
        notificationsEnabled = areWwtNotificationsEnabled(context)
        when {
            !pushManager.hasDistributor() -> pushStatusBus.set(PushStatus.NoDistributor)
            forceFresh && pushStatusBus.status.value is PushStatus.WrongServer ->
                pushManager.reregister()
            else -> pushManager.enable()
        }
    }
```

Edit 5c — the three call sites:

```kotlin
    LaunchedEffect(Unit) { recheck(false) }
```

in the resume observer: `if (event == Lifecycle.Event.ON_RESUME) recheck(true)`

in the poll loop body: `recheck(false)`

Edit 5d — extend the poll gate. Change:

```kotlin
    val isProblem = pushStatus !is PushStatus.Ok
```

to:

```kotlin
    val isProblem = pushStatus !is PushStatus.Ok || !notificationsEnabled
```

Edit 5e — the banner call:

```kotlin
            val bannerContent = pushBannerContent(pushStatus, notificationsEnabled)
```

(the `if (bannerContent != null) { PushStatusBanner(content = bannerContent) }` lines are unchanged), and append this top-level private function at the very end of `ShellScreen.kt`:

```kotlin
/**
 * True when WWT can actually show notifications: app-level enabled AND the Mail channel
 * not blocked. A channel that doesn't exist yet counts as enabled (it is created on the
 * first notification).
 */
private fun areWwtNotificationsEnabled(context: Context): Boolean {
    val nm = NotificationManagerCompat.from(context)
    if (!nm.areNotificationsEnabled()) return false
    val channel = nm.getNotificationChannel(Notifications.CHANNEL_ID)
    return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
}
```

- [ ] **Step 6: Run all gates**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass (including this task's 5 banner tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt app/src/main/java/tech/whitewolf/app/push/PushManager.kt app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt
git commit -m "feat(push): permission step, install-and-open copy, force-fresh re-register on resume"
```

---

### Task 2: Whole-branch review + PR + release

**Files:** none.

- [ ] **Step 1: Whole-branch review** (both tasks' combined diff); fix blocking findings.

- [ ] **Step 2: Push + PR** (standing preference) with this operator checklist:

- [ ] (a) Stale-registration replay: ntfy registered on `ntfy.sh`, set default server to `https://ntfy.whitewolf.tech`, return to WWT → banner clears on resume WITHOUT deleting the `up…` topic; server-side ntfy shows `subscribers=1`.
- [ ] (b) With transport healthy, disable notifications for WWT in system settings → "one last thing" banner on resume; re-enable → clears on resume.
- [ ] (c) Block only the Mail channel → same banner; unblock → clears.
- [ ] (d) Step 1 shows "Install and open ntfy…" (fresh-install path).
- [ ] (e) Push delivers end-to-end after (a).

- [ ] **Step 3: Release v0.3.5** after merge, on the operator's word.
