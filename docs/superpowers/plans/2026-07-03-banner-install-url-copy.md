# Banner Install-URL Copy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The `NoDistributor` push-status banner shows the ntfy Obtainium source URL with a one-tap **Copy** button that puts it on the clipboard.

**Architecture:** `PushStatusBanner.kt` gains a public `NTFY_INSTALL_URL` constant, reworded `NoDistributor` copy (ends with a colon, introducing the URL), and an optional `installUrl: String?` parameter that renders a URL + Copy row below the guidance text. `ShellScreen` passes the URL only for `NoDistributor`. Copy writes to the system clipboard and toasts a confirmation only below Android 13 (13+ shows its own overlay).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Android `ClipboardManager`/`Toast`. JVM unit tests (JUnit4). No emulator.

## Global Constraints

- **Single PR, wwt-mobile only.** No backend/web changes. Release as **v0.3.2** after merge.
- **URL:** `NTFY_INSTALL_URL = "https://github.com/binwiederhier/ntfy"` — a top-level `const val` in `PushStatusBanner.kt`, NOT in `BuildConfig` (universal upstream repo, not environment-specific).
- **`NoDistributor` copy (exact):** `"Notifications are off. In Obtainium, add this app source, then set the ntfy server to ntfy.whitewolf.tech:"` (host interpolated from the existing `EXPECTED_HOST`; ends with a colon). `WrongServer` and `Ok` copy unchanged.
- **Displayed text == copied text** — the full `https://…` URL, no shortening.
- **Copy affordance only for `NoDistributor`** (`WrongServer` means ntfy is already installed — no URL row there).
- **Toast gated:** `Build.VERSION.SDK_INT <= 32` → `"Copied install link"`; no Toast on 13+ (avoids double confirmation).
- **No deep-links, no intents, no dismiss, no new dependencies/permissions/network calls.** The banner's live-status behavior (entry/resume/30s-poll re-check, self-clearing) is untouched.
- **Toolchain:** env from `~/.bashrc`; run `./gradlew` from `/home/dev/mobile-app` with the Bash sandbox disabled. No emulator → `:app:testDebugUnitTest` + `:app:assembleDebug` are the gate.

---

### Task 1: `NTFY_INSTALL_URL` constant + new `NoDistributor` copy (JVM-tested)

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt` (constant + one `when` branch; composable untouched in this task)
- Test: `app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt` (full replacement)

**Interfaces:**
- Consumes: existing `PushStatus` (`tech.whitewolf.app.push`), existing private `EXPECTED_HOST` in the same file.
- Produces: `const val NTFY_INSTALL_URL: String` (top-level, public, package `tech.whitewolf.app.ui`) and the new `pushBannerText(NoDistributor)` string — Task 2 relies on both.

- [ ] **Step 1: Write the failing tests**

Replace the entire contents of `app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt` with:

```kotlin
package tech.whitewolf.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.whitewolf.app.push.PushStatus

class PushStatusBannerTest {
    @Test fun okHasNoBannerText() {
        assertNull(pushBannerText(PushStatus.Ok))
    }

    @Test fun noDistributorTextIntroducesTheInstallUrl() {
        assertEquals(
            "Notifications are off. In Obtainium, add this app source, then set the " +
                "ntfy server to ntfy.whitewolf.tech:",
            pushBannerText(PushStatus.NoDistributor),
        )
    }

    @Test fun wrongServerTextNamesTheWrongHost() {
        val text = pushBannerText(PushStatus.WrongServer("ntfy.sh"))
        assertTrue(text!!.contains("ntfy.sh"))
        assertTrue(text.contains("ntfy.whitewolf.tech"))
    }

    @Test fun installUrlIsTheNtfyRepo() {
        assertEquals("https://github.com/binwiederhier/ntfy", NTFY_INSTALL_URL)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.ui.PushStatusBannerTest'`
Expected: FAIL — compilation error, `NTFY_INSTALL_URL` unresolved (and the `NoDistributor` string assertion would not yet match).

- [ ] **Step 3: Implement — constant + copy change**

In `app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt`, add the constant directly below the existing `EXPECTED_HOST` line:

```kotlin
private val EXPECTED_HOST = tech.whitewolf.app.BuildConfig.NTFY_HOST

/** Obtainium "Add app" source for the ntfy distributor (documented in docs/PUSH.md). */
const val NTFY_INSTALL_URL = "https://github.com/binwiederhier/ntfy"
```

and replace the `NoDistributor` branch of `pushBannerText`:

```kotlin
    is PushStatus.NoDistributor ->
        "Notifications are off. In Obtainium, add this app source, then set the " +
            "ntfy server to $EXPECTED_HOST:"
```

(`Ok` and `WrongServer` branches unchanged. The `PushStatusBanner` composable is unchanged in this task.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.ui.PushStatusBannerTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt
git commit -m "feat(push): NTFY_INSTALL_URL constant + NoDistributor copy introduces it"
```

---

### Task 2: Banner URL row + Copy action + `ShellScreen` call-site

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt` (whole-file replacement below — includes Task 1's changes)
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt` (one call-site edit)

**Interfaces:**
- Consumes: `NTFY_INSTALL_URL` and the copy string from Task 1; existing `PushStatus` sealed type; existing `bannerText`/`pushStatus` locals in `ShellScreen`.
- Produces: `@Composable fun PushStatusBanner(text: String, installUrl: String? = null, modifier: Modifier = Modifier)` — the old two-arg call sites remain source-compatible via the default.

- [ ] **Step 1: Replace `PushStatusBanner.kt` in full**

Replace the entire contents of `app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt` with:

```kotlin
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

/** Obtainium "Add app" source for the ntfy distributor (documented in docs/PUSH.md). */
const val NTFY_INSTALL_URL = "https://github.com/binwiederhier/ntfy"

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
```

- [ ] **Step 2: Update the `ShellScreen` call-site**

In `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt`, change:

```kotlin
            if (bannerText != null) {
                PushStatusBanner(text = bannerText)
            }
```

to:

```kotlin
            if (bannerText != null) {
                PushStatusBanner(
                    text = bannerText,
                    installUrl = if (pushStatus is PushStatus.NoDistributor) NTFY_INSTALL_URL else null,
                )
            }
```

(Nothing else in `ShellScreen.kt` changes. `PushStatus` is already imported; `NTFY_INSTALL_URL` is same-package — no new imports.)

- [ ] **Step 3: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full unit suite (no regressions)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all tests, including Task 1's 4).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt
git commit -m "feat(push): copyable ntfy install URL in the NoDistributor banner"
```

---

### Task 3: Manual e2e (operator) + PR + v0.3.2 release

**Files:** none (verification + release only).

- [ ] **Step 1: On-device verification (operator, GrapheneOS device, debug or release build)**

- [ ] (a) No ntfy installed → banner shows the guidance text ending in `:`, the URL `https://github.com/binwiederhier/ntfy`, and a **Copy** button.
- [ ] (b) Tap **Copy** → paste into Obtainium's *Add app* field → resolves to the ntfy repo.
- [ ] (c) On Android 13+ only the system clipboard confirmation appears (no app toast).
- [ ] (d) Install ntfy via Obtainium, set server to `ntfy.whitewolf.tech` → banner self-clears (existing behavior, no regression).
- [ ] (e) `WrongServer` banner (ntfy on `ntfy.sh`) shows NO URL row — text only.

- [ ] **Step 2: Whole-branch review + PR**

Use `superpowers:requesting-code-review` for a whole-branch review, fix blocking findings, open one PR on wwt-mobile with the checklist above in the body.

- [ ] **Step 3: Release v0.3.2 after merge**

```bash
git checkout master && git pull --ff-only
git tag -a v0.3.2 -m "WWT app v0.3.2 — copyable ntfy install URL in the NoDistributor banner" master
git push origin v0.3.2
```

Expected: `release.yml` builds the signed `wwt-0.3.2.apk` and publishes GitHub Release "WWT 0.3.2".
