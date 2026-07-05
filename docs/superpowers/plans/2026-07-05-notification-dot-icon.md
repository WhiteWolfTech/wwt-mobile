# Notification Dot Icon (v0.3.7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The "New mail" notification shows a clean white dot in the status bar and the full-colour WWT logo in the expanded card.

**Architecture:** Android's two notification image slots map directly: `setSmallIcon` gets a new 10dp filled-circle VectorDrawable (system-tinted, all sizes); `setLargeIcon` gets the already-vendored legacy launcher PNG decoded at build time. Two-line builder change plus one 9-line drawable.

**Tech Stack:** Android VectorDrawable, `NotificationCompat`, `BitmapFactory`. Build-gated (no unit surface). No emulator.

## Global Constraints

- **Single PR, wwt-mobile only.** Release as **v0.3.7** on the operator's word after merge.
- **Dot geometry (binding):** filled circle, radius 5 in a 24×24 viewport, fill `#FFFFFFFF`.
- **Large icon:** `BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)` — the vendored legacy PNG; decoded per notification (no caching, YAGNI).
- **`ic_launcher_foreground` is NOT removed** — still used by the adaptive launcher icon.
- **No new wolf artwork, no wwt-brand changes, no new deps/permissions.** Launcher icon, wizard, push machinery untouched.
- **Toolchain:** env from `~/.bashrc`; `./gradlew` from `/home/dev/mobile-app` with the Bash sandbox disabled. Gates: `:app:assembleDebug` + `:app:testDebugUnitTest`.

---

### Task 1: Dot drawable + notification builder change

**Files:**
- Create: `app/src/main/res/drawable/ic_notification_dot.xml`
- Modify: `app/src/main/java/tech/whitewolf/app/push/Notifications.kt`

**Interfaces:**
- Consumes: existing `R.mipmap.ic_launcher` (vendored), `NotificationCompat.Builder` chain in `showNewMail`.
- Produces: `R.drawable.ic_notification_dot`. No later tasks.

- [ ] **Step 1: Create the dot drawable**

Create `app/src/main/res/drawable/ic_notification_dot.xml` with exactly:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,7a5,5 0 1,0 0,10a5,5 0 1,0 0,-10z" />
</vector>
```

- [ ] **Step 2: Update `Notifications.kt`**

Two edits:

Edit 2a — add the import (after `import android.content.pm.PackageManager`):

```kotlin
import android.graphics.BitmapFactory
```

Edit 2b — in `showNewMail`, change the builder chain from:

```kotlin
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("New mail")
```

to:

```kotlin
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_dot)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle("New mail")
```

(The rest of the chain — `setContentText`, `setAutoCancel`, `setContentIntent`, `build()` — is unchanged. Nothing else in the file changes.)

- [ ] **Step 3: Run all gates**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. (AAPT verifies both resource references.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/drawable/ic_notification_dot.xml app/src/main/java/tech/whitewolf/app/push/Notifications.kt
git commit -m "feat(push): dot status-bar icon + logo large icon for New mail notification"
```

---

### Task 2: Review + PR + release

**Files:** none.

- [ ] **Step 1: Review** the branch diff (one new drawable + two builder edits; verify no other hunks and that `ic_launcher_foreground` is still referenced by `mipmap-anydpi-v26/ic_launcher.xml`).

- [ ] **Step 2: Push + PR** (standing preference) with this operator checklist:

- [ ] (a) Trigger a `new_mail` push → status bar shows a clean dot (not the fuzzy wolf).
- [ ] (b) Pull down the shade → the card shows the wolf logo large, dot as the small badge.
- [ ] (c) Tapping the notification opens the app.
- [ ] (d) Launcher icon unaffected (still the wolf, adaptive + themed).

- [ ] **Step 3: Release v0.3.7** after merge, on the operator's word.
