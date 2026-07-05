# Notification Dot Icon + Logo Large Icon — Design Spec

**Date:** 2026-07-05
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / branding polish (follows the v0.3.2 launcher-icon adoption)

## 1. Context & goal

Since v0.3.2 the "New mail" notification's small icon is the wolf vector
(`R.drawable.ic_launcher_foreground`). At status-bar size (~24dp, monochrome,
system-tinted) the wolf is unrecognisable — it reads as noise until the shade is
opened. Operator feedback: reduce the tiny representation to a simple white dot,
but still show the real logo where there is room.

**Goal:** status bar shows a clean dot; the expanded notification card shows the
full-colour WWT logo.

## 2. Scope

**In scope (wwt-mobile only):**
- New `drawable/ic_notification_dot.xml` — a plain filled-circle VectorDrawable.
- `Notifications.showNewMail`: small icon → the dot; add
  `setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))`.

**Out of scope:**
- **New wolf artwork** — none. Android's `setSmallIcon` is a single drawable for
  all sizes (no size-adaptive selection exists), so "wolf that becomes a dot" is
  not implementable in one slot; the two-slot split is the platform-idiomatic
  answer. A simplified small wolf mark was considered and rejected (new brand
  artwork through wwt-brand's generator, and still fuzzy at 24dp).
- **wwt-brand changes** — the dot is not brand artwork; the vendored wolf assets
  stay byte-identical.
- Launcher icon, wizard, push machinery — untouched.

## 3. Decisions (fixed)

- **Small icon = dot:** filled circle, radius 5 in a 24×24 viewport (10dp visual
  diameter — deliberate dot, not a blob), fill `#FFFFFFFF`. The system tints
  status-bar icons; only the alpha shape matters.
- **Large icon = the vendored legacy launcher PNG** (`R.mipmap.ic_launcher`,
  wolf on Near-Black `#131210`), decoded at notification-build time with
  `BitmapFactory.decodeResource`. Full-colour slot — no tinting, always
  recognisable. Decode cost is negligible for a single, rare notification and
  avoids caching complexity (YAGNI).
- **Shade appearance accepted:** the collapsed/expanded card shows the tinted dot
  as the small badge AND the wolf as the large image; the status bar shows only
  the dot. This is standard Android notification anatomy.

## 4. Components & changes

- **Create `app/src/main/res/drawable/ic_notification_dot.xml`:**

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

- **Modify `app/src/main/java/tech/whitewolf/app/push/Notifications.kt`:**
  - builder line `setSmallIcon(R.drawable.ic_launcher_foreground)` →
    `setSmallIcon(R.drawable.ic_notification_dot)`;
  - add `.setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))`
    to the builder chain;
  - add `import android.graphics.BitmapFactory` (the `tech.whitewolf.app.R`
    import already exists).

`ic_launcher_foreground` remains in use by the adaptive launcher icon
(mipmap-anydpi-v26) — it is NOT removed.

## 5. Error handling

`decodeResource` on a bundled mipmap cannot fail in practice; if it ever returned
null, `setLargeIcon(null)` is valid (notification simply has no large image).
No new failure paths.

## 6. Testing

- **Build gate:** `:app:assembleDebug` (AAPT links the new drawable + mipmap
  reference) + full JVM suite (no unit surface — resource/builder wiring).
- **Manual/e2e (operator):** trigger a `new_mail` push → (a) status bar shows a
  clean dot; (b) shade card shows the wolf logo large + dot badge; (c) tap opens
  the app; (d) launcher icon unaffected.

## 7. Build sequencing (rough)

Single task (drawable + `Notifications.kt` + gates) → review → PR → release
**v0.3.7** on the operator's word.
