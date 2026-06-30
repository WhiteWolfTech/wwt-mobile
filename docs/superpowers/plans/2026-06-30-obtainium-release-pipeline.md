# Obtainium Release Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On a `v*` tag, GitHub Actions builds a signed release APK and publishes a GitHub Release that Obtainium installs and auto-updates from.

**Architecture:** A single tag-triggered workflow (`release.yml`) sets up JDK 17 + the Android SDK, runs the JVM unit suite as a gate, decodes a release keystore from secrets, builds `assembleRelease` with tag-derived version, and publishes the APK as a GitHub Release. `build.gradle.kts` reads `versionName`/`versionCode` from `-P` properties (falling back to defaults) and applies release signing only when a keystore is present, so local/debug builds and tests are unaffected.

**Tech Stack:** GitHub Actions (`actions/checkout`, `actions/setup-java`, `android-actions/setup-android`, `gh` CLI), Android Gradle Plugin 8.6.1 / Gradle 8.10, `apksigner` (build-tools 35.0.0).

**Repo/branch:** `github.com/PeterRounce/wwt-mobile`, branch `feat/release-pipeline` (stacked on `feat/android-shell`, which carries the app). All paths relative to repo root `/home/dev/mobile-app`.

## Global Constraints

- Release tag format is `v<major>.<minor>.<patch>` (e.g. `v0.2.0`); the workflow trigger is restricted to `v*.*.*`. (spec §3, §8)
- `versionName` = tag minus leading `v`; `versionCode` = `major*10000 + minor*100 + patch`. (spec §3)
- `build.gradle.kts` reads `versionName`/`versionCode` from project properties, **falling back to `"0.1.0"` / `1`** when absent. (spec §3)
- Release signing config is **created and applied ONLY when `RELEASE_KEYSTORE_PATH` points at an existing file** — debug builds, `:app:testDebugUnitTest`, and `assembleDebug` must keep working with no secrets. (spec §4)
- `isMinifyEnabled = false` stays. (spec §4)
- Secrets (operator-set, never in the repo): `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. (spec §4)
- Toolchain to run Gradle locally (already installed this session; env in `~/.bashrc`): set and use `dangerouslyDisableSandbox: true` on Bash calls that run Gradle:
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  export ANDROID_HOME=/home/dev/android-sdk
  export PATH="$JAVA_HOME/bin:$PATH"
  cd /home/dev/mobile-app
  ```
- `apksigner` lives at `$ANDROID_HOME/build-tools/35.0.0/apksigner`.
- Company name in any text: "White Wolf Technology" or "WWT" — never "White Wolf" alone (`whitewolf.tech` domain literals fine).
- Every commit message ends with these two trailer lines:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg
  ```

## File Structure

- `app/build.gradle.kts` — **modify**: version from `-P` props (fallback to defaults); conditional `release` signing config from env.
- `.github/workflows/release.yml` — **create**: the tag-triggered release workflow.
- `docs/OBTAINIUM.md` — **create**: operator runbook (keystore, secrets, cutting a release, adding the app in Obtainium).

---

### Task 1: Gradle — tag-driven versioning + conditional release signing

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: existing `app/build.gradle.kts` (`defaultConfig` with `versionCode = 1`, `versionName = "0.1.0"`; `release` build type with `isMinifyEnabled = false` + the `MAIL_BASE_URL` `buildConfigField`).
- Produces: a build that (a) honors `-PversionName` / `-PversionCode` with fallback to `0.1.0`/`1`; (b) signs `assembleRelease` with the keystore at `RELEASE_KEYSTORE_PATH` (+ `RELEASE_KEYSTORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD`) when that file exists, and is unsigned otherwise.

- [ ] **Step 1: Add version + keystore resolution at the top of the file**

In `app/build.gradle.kts`, immediately AFTER the `plugins { … }` block and BEFORE `android {`, add:

```kotlin
// Release version comes from -PversionName / -PversionCode (set by CI from the git
// tag); falls back to the dev defaults for local/debug builds.
val appVersionName: String = (findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Release signing is configured only when CI provides a keystore via env; absent it,
// release builds are unsigned and debug/tests are unaffected.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
```

- [ ] **Step 2: Use the version values in `defaultConfig`**

Replace these two lines in `defaultConfig`:

```kotlin
        versionCode = 1
        versionName = "0.1.0"
```

with:

```kotlin
        versionCode = appVersionCode
        versionName = appVersionName
```

- [ ] **Step 3: Add the conditional release signing config**

Inside `android { … }`, add this block (place it just before `buildTypes {`):

```kotlin
    if (releaseKeystorePath != null && file(releaseKeystorePath).exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
```

- [ ] **Step 4: Apply the signing config to the release build type**

Change the `release { … }` block to apply the signing config when present:

```kotlin
        release {
            isMinifyEnabled = false
            buildConfigField("String", "MAIL_BASE_URL", "\"https://mail.whitewolf.tech\"")
            if (releaseKeystorePath != null && file(releaseKeystorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
```

- [ ] **Step 5: Verify debug/tests + unsigned release still build (no keystore)**

Run (no `RELEASE_KEYSTORE_PATH` set, so the signing branch is skipped):

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/dev/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
cd /home/dev/mobile-app
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```
Expected: BUILD SUCCESSFUL. An (unsigned) `app/build/outputs/apk/release/app-release-unsigned.apk` is produced; unit tests pass. (When unsigned, AGP names the file `app-release-unsigned.apk`.)

- [ ] **Step 6: Verify the signed release path with a throwaway keystore**

Generate a temp keystore (NEVER commit it; it lives in `$RUNNER_TEMP`-equivalent `/tmp`), build with the env vars, and verify the signature:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/dev/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
cd /home/dev/mobile-app
keytool -genkeypair -v -keystore /tmp/throwaway.jks -storepass test123 -keypass test123 \
  -alias test -keyalg RSA -keysize 2048 -validity 30 -dname "CN=WWT Test, O=WWT"
RELEASE_KEYSTORE_PATH=/tmp/throwaway.jks RELEASE_KEYSTORE_PASSWORD=test123 \
  RELEASE_KEY_ALIAS=test RELEASE_KEY_PASSWORD=test123 \
  ./gradlew :app:assembleRelease -PversionName=0.9.9 -PversionCode=909 --rerun-tasks
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
rm -f /tmp/throwaway.jks
```
Expected: BUILD SUCCESSFUL; the output is now `app-release.apk` (no `-unsigned`); `apksigner verify` prints `Verified using v2 scheme: true` (and v1/v3) and a certificate (`CN=WWT Test`). Confirm `aapt`/build logs reflect versionName 0.9.9 if shown (optional). The temp keystore is deleted.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts
git commit   # subject: "build(android): tag-driven version + conditional release signing"  + standard trailer
```

---

### Task 2: Release workflow (`.github/workflows/release.yml`)

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: the Task 1 Gradle wiring (`-PversionName`/`-PversionCode`, `RELEASE_KEYSTORE_PATH` + password/alias env); the four repo secrets; the committed Gradle wrapper.
- Produces: on `v*.*.*` tag push, a GitHub Release named after the tag with `wwt-<version>.apk` attached.

- [ ] **Step 1: Create the workflow file**

Create `.github/workflows/release.yml`:

```yaml
name: Release

on:
  push:
    tags:
      - 'v*.*.*'

permissions:
  contents: write   # create the GitHub Release

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: android-actions/setup-android@v3

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties') }}
          restore-keys: gradle-${{ runner.os }}-

      - name: Derive version from tag
        id: ver
        run: |
          TAG="${GITHUB_REF_NAME#v}"
          IFS='.' read -r MAJ MIN PAT <<< "$TAG"
          CODE=$(( MAJ * 10000 + MIN * 100 + PAT ))
          echo "name=$TAG" >> "$GITHUB_OUTPUT"
          echo "code=$CODE" >> "$GITHUB_OUTPUT"

      - name: Unit tests (gate)
        run: ./gradlew :app:testDebugUnitTest

      - name: Decode release keystore
        env:
          KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
        run: |
          if [ -z "$KEYSTORE_BASE64" ]; then
            echo "::error::RELEASE_KEYSTORE_BASE64 secret is not set — cannot sign release." >&2
            exit 1
          fi
          echo "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.jks"

      - name: Build signed release APK
        env:
          RELEASE_KEYSTORE_PATH: ${{ runner.temp }}/release.jks
          RELEASE_KEYSTORE_PASSWORD: ${{ secrets.RELEASE_KEYSTORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
        run: ./gradlew :app:assembleRelease -PversionName=${{ steps.ver.outputs.name }} -PversionCode=${{ steps.ver.outputs.code }}

      - name: Stage APK
        run: cp app/build/outputs/apk/release/app-release.apk "wwt-${{ steps.ver.outputs.name }}.apk"

      - name: Publish GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release create "$GITHUB_REF_NAME" "wwt-${{ steps.ver.outputs.name }}.apk" \
            --title "WWT ${{ steps.ver.outputs.name }}" \
            --notes "Automated release of White Wolf Technology app ${{ steps.ver.outputs.name }}. Install/update via Obtainium (see docs/OBTAINIUM.md)."
```

- [ ] **Step 2: Validate the workflow**

If `actionlint` is available, run it:
```bash
command -v actionlint >/dev/null && actionlint .github/workflows/release.yml || echo "actionlint not installed — validating by YAML parse"
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml')); print('YAML OK')"
```
Expected: `YAML OK` (and no actionlint errors if installed). Confirm by inspection: trigger is `v*.*.*`; `permissions: contents: write`; the keystore step fails loudly when the secret is empty; the build step passes both `-P` props and the signing env; the release step uses `github.token`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit   # subject: "ci(android): tag-triggered signed-APK release workflow"  + standard trailer
```

---

### Task 3: Operator runbook (`docs/OBTAINIUM.md`)

**Files:**
- Create: `docs/OBTAINIUM.md`

**Interfaces:**
- Consumes: the secret names + tag convention from Tasks 1–2.
- Produces: documentation only.

- [ ] **Step 1: Write the runbook**

Create `docs/OBTAINIUM.md`:

````markdown
# Releasing the WWT app & installing via Obtainium

The app is distributed as a **signed APK attached to a GitHub Release**, built
automatically by `.github/workflows/release.yml` when you push a `v*` tag. Obtainium
installs and auto-updates from those releases.

## One-time: create the release keystore

This key is the app's **permanent identity**. Generate it once and **back it up
securely** — if it is lost, no future build can update an installed copy (a new key
is a different app to Android).

```bash
keytool -genkeypair -v -keystore wwt-release.jks \
  -alias wwt -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=White Wolf Technology, O=White Wolf Technology"
# choose a strong store password and key password when prompted
```

## One-time: set the GitHub Actions secrets

From a checkout of `PeterRounce/wwt-mobile` (requires `gh auth login` with repo
admin):

```bash
base64 -w0 wwt-release.jks | gh secret set RELEASE_KEYSTORE_BASE64 -R PeterRounce/wwt-mobile
gh secret set RELEASE_KEYSTORE_PASSWORD -R PeterRounce/wwt-mobile   # paste the store password
gh secret set RELEASE_KEY_ALIAS -b "wwt" -R PeterRounce/wwt-mobile
gh secret set RELEASE_KEY_PASSWORD -R PeterRounce/wwt-mobile        # paste the key password
```

## Cutting a release

```bash
git tag v0.1.1        # format: v<major>.<minor>.<patch>
git push origin v0.1.1
```

The workflow runs the unit tests, builds the signed `wwt-0.1.1.apk`, and creates a
GitHub Release `v0.1.1` with the APK attached. `versionName` is `0.1.1`;
`versionCode` is `major*10000 + minor*100 + patch` (here `101`), so each higher
version is a valid Android update.

## Installing / updating via Obtainium

The repo is **private**, so Obtainium needs a GitHub token:

1. In Obtainium → **Settings → Source-specific → GitHub → Personal Access Token**,
   add a token with read access to `PeterRounce/wwt-mobile`.
2. **Add app** → paste `https://github.com/PeterRounce/wwt-mobile` → select the
   **GitHub** source.
3. Obtainium tracks the latest release and installs `wwt-<version>.apk`. Pushing a
   newer tag later surfaces an update automatically.

The same signed APK installs on GrapheneOS and stock-Android Pixels.
````

- [ ] **Step 2: Verify the doc**

```bash
python3 -c "open('docs/OBTAINIUM.md').read(); print('readable')"
```
Expected: `readable`. Confirm by inspection: the four secret names match `release.yml` exactly; the tag format matches the workflow trigger; no standalone "White Wolf".

- [ ] **Step 3: Commit**

```bash
git add docs/OBTAINIUM.md
git commit   # subject: "docs: Obtainium release runbook (keystore, secrets, tagging)"  + standard trailer
```

---

## Self-Review

**Spec coverage (against `2026-06-30-obtainium-release-pipeline-design.md`):**
- §3 trigger `v*.*.*`, versionName from tag, versionCode formula, `-P` props with fallback → Task 1 (props/fallback) + Task 2 (trigger + derivation). ✓
- §4 four secrets, decode-to-temp, conditional release signing, unaffected debug/tests, minify off → Task 1 (conditional signing, minify untouched) + Task 2 (decode + env). ✓
- §5 workflow steps (checkout, java17, android sdk, cache, derive, test gate, decode, assembleRelease, stage, gh release) → Task 2, in order. ✓
- §6 components (release.yml, build.gradle wiring, OBTAINIUM.md, operator secrets) → Tasks 1–3. ✓
- §7 Obtainium private-repo PAT + GitHub source → Task 3. ✓
- §8 error handling (empty secret fails loudly; failing tests fail before build; tag restricted to `v*.*.*`) → Task 2 (keystore step guard, test gate before build, trigger filter). ✓
- §9 local verification (debug+tests+unsigned release; signed via throwaway keystore + apksigner verify) → Task 1 Steps 5–6; workflow inspection → Task 2 Step 2; operator end-to-end → documented in Task 3 (push a tag). ✓

**Placeholder scan:** No TBD/TODO; every step has concrete code/commands. The throwaway keystore in Task 1 Step 6 is explicitly created and deleted, never committed.

**Type/identifier consistency:** Secret names (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) and env var names (`RELEASE_KEYSTORE_PATH` + the three above) are identical across Task 1 (build.gradle reads), Task 2 (workflow sets), and Task 3 (doc). The APK output path `app/build/outputs/apk/release/app-release.apk` (signed) vs `app-release-unsigned.apk` (unsigned) is correctly distinguished between Task 1 Step 5 (unsigned) and Step 6 / Task 2 (signed). `versionName`/`versionCode` property names match between the build file and the `-P` flags. The versionCode formula (`major*10000+minor*100+patch`) is identical in Task 2 and Task 3. ✓

**Execution note:** Task 1 is fully verifiable locally (incl. the signed path via a throwaway keystore). Task 2's workflow cannot run until it is pushed to GitHub AND the operator has set the four secrets; it is validated here by YAML/actionlint + inspection, then end-to-end by the operator pushing a real tag. Task 3 is docs.
