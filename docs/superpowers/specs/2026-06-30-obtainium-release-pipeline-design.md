# Obtainium Release Pipeline (Design Spec)

**Date:** 2026-06-30
**Status:** Approved design — ready for implementation planning
**Sub-project:** Foundation / cross-cutting (CI release distribution)

## 1. Context & goal

The WWT Android app (repo `github.com/PeterRounce/wwt-mobile`, private; Kotlin/
Compose, application id `tech.whitewolf.app`) currently builds locally only. This
adds an automated GitHub Actions pipeline that, on a version tag, builds a **signed
release APK** and publishes it as a **GitHub Release** — the artifact Obtainium
installs and auto-updates from. This realizes the foundation's "Obtainium +
private releases" distribution decision.

This is a focused cross-cutting addition, not a new app module.

## 2. Scope

**In scope:** a tag-triggered GitHub Actions workflow (setup → unit tests → build
signed release APK → publish GitHub Release with the APK); the `build.gradle.kts`
wiring for tag-driven versioning and conditional release signing; an Obtainium
setup doc; and the keystore/secret setup instructions for the operator.

**Out of scope (acceptable follow-ups):** a separate PR build-test/lint CI (the
release job runs the unit tests, so a red build is never released, but per-PR CI is
deferred); running instrumented tests in CI (needs an emulator job); a self-hosted
F-Droid repo; ProGuard/R8 minification (stays off for now).

## 3. Trigger & versioning

- **Trigger:** push of a tag matching `v<major>.<minor>.<patch>` (e.g. `v0.2.0`).
- **versionName:** the tag with the leading `v` stripped (`0.2.0`).
- **versionCode:** derived monotonically from the tag as `major*10000 + minor*100 +
  patch` (so `v0.2.0` → `200`, `v1.0.0` → `10000`). This guarantees a strictly
  increasing integer as long as versions increase, satisfying Android's installer
  (a higher `versionCode` is required to update) and giving Obtainium a clear latest.
- Both are passed to Gradle as `-PversionName=… -PversionCode=…`.
  `build.gradle.kts` reads these project properties, falling back to the current
  `versionName "0.1.0"` / `versionCode 1` for local/debug builds (so nothing breaks
  when the properties are absent).

## 4. Signing

- Four GitHub Actions repository secrets: `RELEASE_KEYSTORE_BASE64` (the keystore,
  base64-encoded), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD`.
- The workflow base64-decodes the keystore to a temp file and exposes its path +
  the passwords/alias to Gradle via environment variables
  (`RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD`).
- `build.gradle.kts` gains a `release` signing config that is **created and applied
  only when the keystore file is present**. Without it (local builds, JVM tests,
  `assembleDebug`), the release build type is simply unsigned and everything else is
  unaffected — no secrets are required for day-to-day development.
- `assembleRelease` then produces a signed `app-release.apk`.
- Minification (`isMinifyEnabled`) stays `false`; the app is reflection-light and
  this avoids R8 surprises for the first releases.

**Operator responsibility:** the release keystore is the app's permanent identity.
It must be generated once and **backed up safely** — losing it means no future
build can update an installed copy via Obtainium (a new key = a different app to
Android). The operator generates it (`keytool`) and sets the four secrets
(`gh secret set`); exact commands live in the Obtainium doc (§7) and the plan.

## 5. Workflow steps (`.github/workflows/release.yml`)

1. `actions/checkout`.
2. `actions/setup-java` (Temurin 17).
3. `android-actions/setup-android` (SDK).
4. Gradle dependency cache.
5. Derive `versionName`/`versionCode` from the tag (`$GITHUB_REF_NAME`).
6. `./gradlew :app:testDebugUnitTest` — the gate; a failing unit suite fails the
   release.
7. Decode `RELEASE_KEYSTORE_BASE64` → `$RUNNER_TEMP/release.jks`.
8. `./gradlew :app:assembleRelease -PversionName=… -PversionCode=…` with the signing
   env vars set.
9. Copy the output to `wwt-<versionName>.apk`.
10. `gh release create <tag> wwt-<versionName>.apk` (using the built-in
    `GITHUB_TOKEN`; the job has `contents: write` permission) with a short release
    note.

## 6. Components & boundaries

- **`release.yml`** — the only workflow; owns trigger, version derivation, test gate,
  signed build, and release publishing. Depends on: the four secrets, the committed
  Gradle wrapper, the SDK setup action.
- **`build.gradle.kts` (signing + version wiring)** — reads `versionName`/`versionCode`
  project properties (fallback to defaults); conditionally configures release signing
  from env. Independently verifiable locally: `assembleRelease` with no keystore →
  unsigned APK builds; with a throwaway keystore + the env vars → a signed APK
  (`apksigner verify` passes). Must not change debug/test behavior.
- **`docs/OBTAINIUM.md`** — operator runbook: generate keystore, set the four
  secrets, cut a release (push a tag), and add the app in Obtainium.
- **Secrets (operator-provided)** — not in the repo; the seam between CI and signing.

## 7. Obtainium setup (private repo)

Because the repo is private, Obtainium needs a GitHub **personal access token**
(scope: read access to the repo) configured in Obtainium's settings. The app is
added as a **GitHub** source with URL `https://github.com/PeterRounce/wwt-mobile`;
Obtainium tracks the latest release and installs the `wwt-<version>.apk` asset.
Updates flow automatically once a newer tag is released (higher `versionCode`).
This is documented in `docs/OBTAINIUM.md`.

## 8. Error handling

- Missing/invalid secrets → the decode/sign step fails the workflow loudly (no silent
  unsigned release); the operator must set the four secrets before the first tag.
- Failing unit tests → step 6 fails the job before any APK is built or published.
- A malformed tag (not `v<int>.<int>.<int>`) → the version-derivation step produces a
  bad `versionCode`; the trigger is therefore restricted to `v*.*.*` and the runbook
  states the required tag format.

## 9. Testing & verification

- **Local (in the plan):** verify the `build.gradle.kts` changes — `assembleDebug` +
  `:app:testDebugUnitTest` still pass; `assembleRelease` with no keystore builds an
  (unsigned) APK; `assembleRelease` with a *throwaway* generated keystore + env vars
  produces a signed APK that `apksigner verify` accepts. (The throwaway keystore is
  never committed.)
- **Workflow:** validated by inspection (and `actionlint` if available); it cannot
  fully run until the real secrets exist.
- **End-to-end (operator, after secrets set):** push `v0.1.1` → confirm the GitHub
  Release appears with the signed `wwt-0.1.1.apk` → install + update via Obtainium on
  a device.
