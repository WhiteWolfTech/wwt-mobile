# Push Banner Step Wizard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Present push setup as a two-step wizard in the status banner — short step label, one-line instruction, copyable URL per state — with the live status machine acting as the stepper, and add the copyable server URL to `WrongServer`.

**Architecture:** A pure `pushBannerContent(status): BannerContent?` projects `PushStatus` onto `(stepLabel, instruction, copyUrl)` — `NoDistributor` = step 1 (install URL), `WrongServer` = step 2 (server URL), `Ok` = null. It fully replaces `pushBannerText` and the `installUrl` parameter. The banner composable renders label (`labelSmall`) → instruction (`bodyMedium`) → the existing URL+Copy row. No step state is stored anywhere; the entry/resume/30s-poll re-check advances and clears the banner as before.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), existing clipboard/Toast mechanics. JVM unit tests (JUnit4). No emulator.

## Global Constraints

- **Single PR, wwt-mobile only.** Release as **v0.3.4** on the operator's word after merge.
- **Exact content (copy strings are binding):**
  - `NoDistributor` → stepLabel `"Notifications — step 1 of 2"`, instruction `"Install ntfy: in Obtainium, add this source:"`, copyUrl = `NTFY_INSTALL_URL`.
  - `WrongServer` → stepLabel `"Notifications — step 2 of 2"`, instruction `"In ntfy, set Settings → Default server to:"`, copyUrl = `"https://" + EXPECTED_HOST` (= `https://ntfy.whitewolf.tech`).
  - `Ok` → `null`.
- **The wrong-host diagnostic is no longer rendered** (`WrongServer.endpointHost` stays in the type, not in the copy).
- **`copyUrl` is non-nullable in `BannerContent`**; "no banner" is expressed by `pushBannerContent` returning `null`.
- **`pushBannerText` is removed** (its only caller, `ShellScreen`, moves to `pushBannerContent`).
- **Copy mechanics unchanged except:** clip label `"ntfy URL"`, Toast text becomes `"Copied"`, still gated to `Build.VERSION.SDK_INT <= 32`.
- **Unchanged:** `NTFY_INSTALL_URL` (`https://github.com/binwiederhier/ntfy-android`), `EXPECTED_HOST`, the status machine (`recheck`/resume/30s poll/self-clearing/no dismiss), testTags `pushBanner`/`pushBannerUrl`/`pushBannerCopy`, no deep-links/intents, no new deps/permissions.
- **Toolchain:** env from `~/.bashrc`; run `./gradlew` from `/home/dev/mobile-app` with the Bash sandbox disabled. No emulator → `:app:testDebugUnitTest` + `:app:assembleDebug` are the gate.

---

### Task 1: `BannerContent` + `pushBannerContent` + wizard banner + `ShellScreen` call-site

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt` (whole-file replacement below)
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt` (one call-site edit)
- Test: `app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt` (whole-file replacement)

**Interfaces:**
- Consumes: existing `PushStatus` (`tech.whitewolf.app.push`), existing `NTFY_INSTALL_URL`/`EXPECTED_HOST` (same file), existing `pushStatus` local in `ShellScreen`.
- Produces: `data class BannerContent(val stepLabel: String, val instruction: String, val copyUrl: String)`, `fun pushBannerContent(status: PushStatus): BannerContent?`, `@Composable fun PushStatusBanner(content: BannerContent, modifier: Modifier = Modifier)`. `pushBannerText` and the `(text, installUrl)` overload cease to exist.

- [ ] **Step 1: Write the failing tests**

Replace the entire contents of `app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt` with:

```kotlin
package tech.whitewolf.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.whitewolf.app.push.PushStatus

class PushStatusBannerTest {
    @Test fun okHasNoBannerContent() {
        assertNull(pushBannerContent(PushStatus.Ok))
    }

    @Test fun noDistributorIsStepOneWithInstallUrl() {
        assertEquals(
            BannerContent(
                stepLabel = "Notifications — step 1 of 2",
                instruction = "Install ntfy: in Obtainium, add this source:",
                copyUrl = NTFY_INSTALL_URL,
            ),
            pushBannerContent(PushStatus.NoDistributor),
        )
    }

    @Test fun wrongServerIsStepTwoWithServerUrlAndNoHostInCopy() {
        assertEquals(
            BannerContent(
                stepLabel = "Notifications — step 2 of 2",
                instruction = "In ntfy, set Settings → Default server to:",
                copyUrl = "https://ntfy.whitewolf.tech",
            ),
            pushBannerContent(PushStatus.WrongServer("ntfy.sh")),
        )
    }

    @Test fun installUrlIsTheNtfyAndroidAppRepo() {
        assertEquals("https://github.com/binwiederhier/ntfy-android", NTFY_INSTALL_URL)
    }
}
```

(The `WrongServer` test uses exact data-class equality, which also proves the wrong host `ntfy.sh` appears nowhere in the rendered content.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.ui.PushStatusBannerTest'`
Expected: FAIL — compilation error, `BannerContent` / `pushBannerContent` unresolved.

- [ ] **Step 3: Replace `PushStatusBanner.kt` in full**

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

/**
 * Obtainium "Add app" source for the ntfy distributor (documented in docs/PUSH.md).
 * NOTE: this is the ntfy-android APP repo — the plain `ntfy` repo is the server and
 * its releases carry no APK (Obtainium finds "no suitable release" there).
 */
const val NTFY_INSTALL_URL = "https://github.com/binwiederhier/ntfy-android"

/** One wizard step of the push-setup banner: label, instruction, and the URL to copy. */
data class BannerContent(val stepLabel: String, val instruction: String, val copyUrl: String)

/**
 * Project the current push status onto banner content. The live status machine IS the
 * wizard — no step state is stored: NoDistributor = step 1 (install ntfy),
 * WrongServer = step 2 (point it at the WWT server), Ok = null (no banner). Completing
 * a step advances the banner via the normal status re-check.
 */
fun pushBannerContent(status: PushStatus): BannerContent? = when (status) {
    is PushStatus.Ok -> null
    is PushStatus.NoDistributor -> BannerContent(
        stepLabel = "Notifications — step 1 of 2",
        instruction = "Install ntfy: in Obtainium, add this source:",
        copyUrl = NTFY_INSTALL_URL,
    )
    is PushStatus.WrongServer -> BannerContent(
        stepLabel = "Notifications — step 2 of 2",
        instruction = "In ntfy, set Settings → Default server to:",
        copyUrl = "https://$EXPECTED_HOST",
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = content.copyUrl,
                    modifier = Modifier.weight(1f).testTag("pushBannerUrl"),
                )
                TextButton(
                    onClick = { copyUrl(context, content.copyUrl) },
                    modifier = Modifier.padding(start = 8.dp).testTag("pushBannerCopy"),
                ) { Text("Copy") }
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
```

- [ ] **Step 4: Update the `ShellScreen` call-site**

In `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt`, change:

```kotlin
            val bannerText = pushBannerText(pushStatus)
            if (bannerText != null) {
                PushStatusBanner(
                    text = bannerText,
                    installUrl = if (pushStatus is PushStatus.NoDistributor) NTFY_INSTALL_URL else null,
                )
            }
```

to:

```kotlin
            val bannerContent = pushBannerContent(pushStatus)
            if (bannerContent != null) {
                PushStatusBanner(content = bannerContent)
            }
```

(Nothing else in `ShellScreen.kt` changes. `PushStatus` is still imported and used by `recheck`/`isProblem`; `pushBannerContent`/`BannerContent` are same-package — no import changes.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'tech.whitewolf.app.ui.PushStatusBannerTest'`
Expected: PASS (4 tests).

- [ ] **Step 6: Build gate + full suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (no regressions).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/PushStatusBanner.kt app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt app/src/test/java/tech/whitewolf/app/ui/PushStatusBannerTest.kt
git commit -m "feat(push): banner presents setup as a two-step wizard with copyable URLs"
```

---

### Task 2: Whole-branch review + PR + release

**Files:** none (verification + PR only).

- [ ] **Step 1: Whole-branch review** — single-task branch: an independent task review over the full branch diff satisfies it; fix blocking findings.

- [ ] **Step 2: Push + PR** (standing preference — no options menu) with this operator checklist:

- [ ] (a) ntfy removed → banner shows "Notifications — step 1 of 2" + install URL + Copy.
- [ ] (b) Install ntfy (default `ntfy.sh`) while the app stays open → banner advances to "step 2 of 2" + `https://ntfy.whitewolf.tech` + Copy, without relaunch.
- [ ] (c) Copy → paste into ntfy *Settings → Default server* → banner self-clears ≤30s.
- [ ] (d) Push still delivers end-to-end.

- [ ] **Step 3: Release v0.3.4** after merge, on the operator's word: tag master, `release.yml` publishes.
