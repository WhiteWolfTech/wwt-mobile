# WWT Launcher Icon Adoption — Design Spec

**Date:** 2026-07-03
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / branding (closes the long-standing "no launcher icon" follow-up)

## 1. Context & goal

The app has no launcher icon at all — no `res/` directory, no `android:icon`
attribute — so it ships with the system default. The push "New mail" notification
likewise uses the placeholder `android.R.drawable.ic_dialog_email`.

The brand repo (`github.com/PeterRounce/wwt-brand`, private) now publishes
generated, verified, drop-in Android launcher assets under `android/res/`:
the white wolf emblem on Near-Black `#131210`, adaptive-icon safe-zone sized,
with a `<monochrome>` layer for Android 13+ themed icons and legacy raster
fallbacks. Its README mandates: **use the files as-is; do not redraw, recolour,
re-export, scale, crop, or reposition.**

**Goal:** the app shows the WWT wolf as its launcher icon (adaptive + themed +
legacy) and as the notification small icon.

## 2. Scope

**In scope (wwt-mobile only):**
- Vendor the 8 asset files from `wwt-brand/android/res/` into `app/src/main/res/`,
  byte-identical.
- Wire `android:icon="@mipmap/ic_launcher"` in the manifest.
- Switch the notification small icon from `android.R.drawable.ic_dialog_email` to
  the vendored `R.drawable.ic_launcher_foreground`.

**Out of scope:**
- **Any modification of the assets** — brand-repo rule; changes happen upstream
  via its generator, then re-vendor.
- **`android:roundIcon`** — the manifest doesn't set one and the brand repo ships
  no round alias; launchers mask the adaptive icon themselves.
- **In-app logo usage** (login screen, about) — separate branding work if ever
  wanted.
- **wwt-brand repo changes** — none.

## 3. Decisions (fixed)

- **Vendor (copy + commit), not fetch-at-build.** The repo is private; build-time
  fetching would need token plumbing in CI and makes builds network-dependent.
  Vendoring is the brand README's own documented consumption path.
- **Byte-identical integrity is verified at copy time:** each vendored file's
  SHA-256 must equal that of the blob fetched from the wwt-brand GitHub API
  (`main` branch). Any mismatch fails the task.
- **Notification small icon = `R.drawable.ic_launcher_foreground`.** It is a
  white-on-transparent VectorDrawable — exactly the notification-icon contract
  (the system applies its own tint). No separate notification asset is created.
- **No test resources or golden-image tests.** AAPT resource linking makes a
  broken `@mipmap/ic_launcher` or drawable reference a **build failure** —
  `assembleDebug` is the functional gate. Icon appearance is operator-verified.

## 4. Components & changes

- **Vendored files (create, byte-identical from `wwt-brand@main:android/res/`):**
  - `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` — adaptive icon
    (background + foreground + monochrome), API 26+.
  - `app/src/main/res/drawable/ic_launcher_foreground.xml` — white wolf
    VectorDrawable; doubles as the monochrome layer and the notification icon.
  - `app/src/main/res/values/ic_launcher_background.xml` — colour resource
    `#131210`.
  - `app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` — legacy
    rasters (48/72/96/144/192 px), pre-API-26 fallback (minSdk 29 devices ignore
    them, but they ship per the brand package — vendor verbatim, don't prune).
- **`app/src/main/AndroidManifest.xml` (modify):** add
  `android:icon="@mipmap/ic_launcher"` to the `<application>` element.
- **`app/src/main/java/tech/whitewolf/app/push/Notifications.kt` (modify):**
  the builder's `setSmallIcon(android.R.drawable.ic_dialog_email)` becomes
  `setSmallIcon(R.drawable.ic_launcher_foreground)` (import
  `tech.whitewolf.app.R`).

The app currently has **no** `res/` directory, so there are no collisions.

## 5. Error handling / integrity

- **Copy integrity:** SHA-256 of every vendored file compared against the
  wwt-brand API blob at vendor time; mismatch = task failure (no silent
  corruption or truncated PNGs).
- **Broken references:** caught at build time by AAPT (resource linking), not at
  runtime.
- **Future brand drift:** upstream regenerates and we re-vendor; this repo never
  edits the files.

## 6. Testing

- **Build gate:** `:app:assembleDebug` (AAPT links `@mipmap/ic_launcher` +
  `R.drawable.ic_launcher_foreground`). Full JVM suite for regressions
  (`Notifications` has no JVM test; the change is a resource-ID swap).
- **Manual/e2e (operator, GrapheneOS device):** (a) launcher shows the wolf under
  the circle mask, not the system default; (b) Android 13+ themed-icon mode shows
  the tinted monochrome wolf; (c) a "New mail" push shows the wolf glyph in the
  status bar (not the email envelope); (d) app-info page shows the icon.

## 7. Build sequencing (rough)

Single task: vendor 8 files (with SHA verification) + manifest attribute +
`Notifications.kt` swap + build/test gates → whole-branch review → PR. Can share
a release tag with PR #9 (banner URL copy) or take its own; operator decides at
merge time.

## 8. Follow-ups (logged, not in this sub-project)

- In-app brand usage (login screen wordmark, about screen) — only if wanted later.
