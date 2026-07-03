# WWT Launcher Icon Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app shows the WWT wolf as its launcher icon (adaptive + themed + legacy) and as the push-notification small icon, using the wwt-brand assets verbatim.

**Architecture:** Vendor the 8 generated files from `wwt-brand@main:android/res/` byte-identical into `app/src/main/res/` (verified by `diff -r` against a fresh shallow clone), add `android:icon="@mipmap/ic_launcher"` to the manifest, and swap the notification small icon to the vendored wolf VectorDrawable. No asset is edited in this repo, ever.

**Tech Stack:** Android resources (adaptive icon XML, VectorDrawable, mipmap PNGs), `gh` CLI for the private brand repo, AAPT resource linking as the functional gate.

## Global Constraints

- **Assets are used as-is** — do not redraw, recolour, re-export, scale, crop, reposition, or reformat any vendored file. Byte-identical to `wwt-brand@main:android/res/`, verified by recursive diff. A mismatch is a task failure, not something to fix locally.
- **Exactly these 8 files are vendored:** `mipmap-anydpi-v26/ic_launcher.xml`, `drawable/ic_launcher_foreground.xml`, `values/ic_launcher_background.xml`, `mipmap-mdpi/ic_launcher.png`, `mipmap-hdpi/ic_launcher.png`, `mipmap-xhdpi/ic_launcher.png`, `mipmap-xxhdpi/ic_launcher.png`, `mipmap-xxxhdpi/ic_launcher.png`.
- **No `android:roundIcon`** — the manifest doesn't set one and the brand package ships no round alias.
- **Notification small icon:** `R.drawable.ic_launcher_foreground` (replaces `android.R.drawable.ic_dialog_email`). No new notification-specific asset.
- **Single PR, wwt-mobile only.** No wwt-brand changes. Release: may share a tag with PR #9 or take its own — operator decides at merge time.
- **Toolchain:** env from `~/.bashrc`; run `./gradlew` from `/home/dev/mobile-app` with the Bash sandbox disabled. `gh` is authenticated for the private `PeterRounce/wwt-brand`. No emulator → `:app:assembleDebug` + `:app:testDebugUnitTest` are the gate.
- **Scratch space:** clone the brand repo under `/tmp/claude-1001/-home-dev-mobile-app/f2fdf32f-02c9-45db-b247-a5af72020573/scratchpad/`, never inside the app repo.

---

### Task 1: Vendor assets + manifest icon + notification icon

**Files:**
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/values/ic_launcher_background.xml`, `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` (all copied, never authored)
- Modify: `app/src/main/AndroidManifest.xml:7-12` (`<application>` element)
- Modify: `app/src/main/java/tech/whitewolf/app/push/Notifications.kt:47` (+ one import)

**Interfaces:**
- Consumes: `wwt-brand@main:android/res/` via authenticated `gh repo clone`.
- Produces: `@mipmap/ic_launcher` and `R.drawable.ic_launcher_foreground` resource IDs (nothing downstream consumes them beyond this task's own edits).

- [ ] **Step 1: Clone the brand repo and vendor the assets**

```bash
SCRATCH=/tmp/claude-1001/-home-dev-mobile-app/f2fdf32f-02c9-45db-b247-a5af72020573/scratchpad
rm -rf "$SCRATCH/wwt-brand"
gh repo clone PeterRounce/wwt-brand "$SCRATCH/wwt-brand" -- --depth 1
cp -r "$SCRATCH/wwt-brand/android/res/." /home/dev/mobile-app/app/src/main/res/
```

- [ ] **Step 2: Verify the copy is byte-identical (integrity gate)**

```bash
diff -r "$SCRATCH/wwt-brand/android/res" /home/dev/mobile-app/app/src/main/res && echo "BYTE-IDENTICAL"
find /home/dev/mobile-app/app/src/main/res -type f | sort | xargs sha256sum
```

Expected: `diff -r` prints nothing and `BYTE-IDENTICAL` is echoed; exactly 8 files listed with their hashes (record them in the report). Any diff output = STOP, report BLOCKED (do not "fix" the assets).

- [ ] **Step 3: Wire the manifest icon**

In `app/src/main/AndroidManifest.xml`, change the `<application>` element:

```xml
    <application
        android:name=".WwtApp"
        android:label="WWT"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="false"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
```

(Only the `android:icon` line is added; everything else is unchanged. Do NOT add `android:roundIcon`.)

- [ ] **Step 4: Swap the notification small icon**

In `app/src/main/java/tech/whitewolf/app/push/Notifications.kt`, add the import (after `import androidx.core.content.ContextCompat`):

```kotlin
import tech.whitewolf.app.MainActivity
import tech.whitewolf.app.R
```

and change the builder line:

```kotlin
            .setSmallIcon(R.drawable.ic_launcher_foreground)
```

(replacing `.setSmallIcon(android.R.drawable.ic_dialog_email)`; nothing else in the file changes.)

- [ ] **Step 5: Build gate (AAPT links the new resources)**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (A missing/broken `@mipmap/ic_launcher` or `R.drawable.ic_launcher_foreground` fails here.)

- [ ] **Step 6: Full unit suite (no regressions)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all existing tests; this task adds none — there is no unit-testable logic in a resource swap, and the spec's gate is AAPT + operator verification).

- [ ] **Step 7: Commit**

```bash
cd /home/dev/mobile-app
git add app/src/main/res app/src/main/AndroidManifest.xml app/src/main/java/tech/whitewolf/app/push/Notifications.kt
git commit -m "feat: adopt WWT wolf launcher icon + notification icon from wwt-brand"
```

---

### Task 2: Whole-branch review + PR + operator verification

**Files:** none (verification + PR only).

- [ ] **Step 1: Whole-branch review**

Use `superpowers:requesting-code-review`; fix blocking findings.

- [ ] **Step 2: Push + PR (standing preference — no options menu)**

Open one PR on wwt-mobile with this operator checklist in the body:

- [ ] (a) Launcher shows the wolf under the GrapheneOS circle mask (not the system default icon).
- [ ] (b) Android 13+ themed-icon mode shows the tinted monochrome wolf.
- [ ] (c) A "New mail" push shows the wolf glyph in the status bar (not the email envelope).
- [ ] (d) App-info page shows the icon.

- [ ] **Step 3: Release**

After merge, at the operator's word: tag from master (`vX.Y.Z` per their instruction — may bundle with PR #9's v0.3.2 or take its own). `release.yml` builds and publishes automatically.
