# WWT Android Shell + Auth + Email Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the WWT native Android app shell — native login → bearer token in Keystore + seeded WebView session cookie → hardened WebView hosting the mail sub-app — as the first sub-project of the mobile app.

**Architecture:** One Kotlin/Jetpack-Compose app (`tech.whitewolf.app`) hosting WWT sub-apps via a registry (mail = sub-app #1). Login is native (`POST /api/login`); the bearer token is stored in an Android-Keystore-backed store for native use, and the returned `session` cookie is seeded into the WebView so the SPA loads authenticated. Logic-bearing units sit behind interfaces (`SecureStore`, `WebCookies`) so they unit-test on the JVM with fakes/MockWebServer; only the framework wiring (Keystore prefs, WebView, Compose UI) needs instrumented tests.

**Tech Stack:** Kotlin 2.x, Android Gradle Plugin 8.x, Jetpack Compose (BOM), AndroidX (activity-compose, lifecycle-viewmodel-compose, security-crypto), OkHttp + MockWebServer, kotlinx-serialization-json, JUnit4, kotlinx-coroutines-test.

## Global Constraints

- Application ID **`tech.whitewolf.app`**; display name **"WWT"**. (spec §5) Never brand the app "Mail"; mail is sub-app #1.
- `minSdk 29`, `targetSdk 36`, `compileSdk 36`. Kotlin + Jetpack Compose. (spec §5)
- Mail sub-app URL = `BuildConfig.MAIL_BASE_URL`; release value **`https://mail.whitewolf.tech`**, debug overridable. Each sub-app's host derives from its own URL. (spec §5)
- Permissions: **`INTERNET` only** this sub-project. No `POST_NOTIFICATIONS`, no other permission. (spec §5, §8 foundation)
- Backend HTTP contract (already shipped, PR #24): `POST /api/login` with `{"email","password"}` → on success `200` `{"ok":true,"token":"<userID.exp.sig>","expires":<unixSeconds>}` and a `Set-Cookie: session=…` header; `401` on bad credentials. Token + cookie share a 7-day lifetime. (spec §3)
- The SPA authenticates via the **session cookie**; the native side never injects `Authorization` into the WebView. (spec §3)
- **No JS↔native bridge** (`@JavascriptInterface`) in this sub-project. (spec §2, §4)
- **No launcher/switcher UI** — the shell auto-opens the sole registry sub-app. (spec §2)
- Company name in any user-facing string/comment: "White Wolf Technology" or "WWT" — never "White Wolf" alone. (`whitewolf.tech` domain literals are fine.)
- Tests: JVM unit tests via `./gradlew testDebugUnitTest`; instrumented tests via `./gradlew connectedDebugAndroidTest` (need a device/emulator). Each task states which it uses.
- Every commit message ends with these two trailer lines (the "standard trailer"):
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg
  ```

## File Structure

App module `app/`, package root `tech.whitewolf.app`:

- `app/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts` — Gradle config + version catalog.
- `app/src/main/AndroidManifest.xml` — INTERNET, app label, single Activity.
- `subapp/SubApp.kt`, `subapp/SubAppRegistry.kt` — sub-app model + registry (mail entry). Pure Kotlin.
- `auth/SecureStore.kt` — storage interface. Pure Kotlin.
- `auth/EncryptedPrefsStore.kt` — `SecureStore` impl over EncryptedSharedPreferences (Keystore). Android.
- `auth/TokenStore.kt` — token + expiry persistence over `SecureStore`. Pure Kotlin (clock-injected).
- `auth/WebCookies.kt` — cookie-seeding interface + `setSessionCookieHeader` extractor. Pure Kotlin interface; helper pure.
- `auth/AndroidWebCookies.kt` — `WebCookies` impl over `CookieManager`. Android.
- `auth/AuthRepository.kt` — login orchestration over OkHttp + `TokenStore` + `WebCookies`. Pure-ish (JVM-testable with MockWebServer + fakes).
- `auth/LoginViewModel.kt` — login screen state machine. Pure-ish (JVM-testable).
- `ui/LoginScreen.kt` — Compose login UI.
- `ui/SubAppWebView.kt` — hardened WebView (Compose `AndroidView`) + `WebViewClient`.
- `web/NavPolicy.kt` — pure same-host-vs-external decision. Pure Kotlin.
- `ui/ShellScreen.kt` + `MainActivity.kt` — nav by auth state, loading/error/retry, sign-out.

Tests mirror under `app/src/test/...` (JVM) and `app/src/androidTest/...` (instrumented).

---

### Task 1: Gradle scaffold + app module + manifest

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/tech/whitewolf/app/Placeholder.kt`, `app/src/test/java/tech/whitewolf/app/ScaffoldTest.kt`, `.gitignore`
- Also: Gradle wrapper files (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`).

**Interfaces:**
- Produces: a buildable Compose app module with `BuildConfig.MAIL_BASE_URL`, application id `tech.whitewolf.app`, INTERNET permission. Later tasks add source files under `tech.whitewolf.app.*`.

- [ ] **Step 1: Generate the Gradle wrapper**

Run (requires a local Gradle ≥8.7; this writes the wrapper so later steps use `./gradlew`):
```bash
gradle wrapper --gradle-version 8.10
```
Expected: creates `gradlew`, `gradle/wrapper/gradle-wrapper.{jar,properties}`.

- [ ] **Step 2: Write `gradle/libs.versions.toml`** (version catalog — single source of versions; bump to current stable if newer)

```toml
[versions]
agp = "8.6.1"
kotlin = "2.0.20"
coreKtx = "1.13.1"
activityCompose = "1.9.2"
composeBom = "2024.09.03"
lifecycle = "2.8.6"
securityCrypto = "1.1.0-alpha06"
okhttp = "4.12.0"
serialization = "1.7.3"
coroutines = "1.9.0"
junit = "4.13.2"
androidxTestExt = "1.2.1"
espresso = "3.6.1"

[libraries]
core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExt" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 3: Write root `settings.gradle.kts` and `build.gradle.kts`**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "WWT"
include(":app")
```

`build.gradle.kts` (root):
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

- [ ] **Step 4: Write `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "tech.whitewolf.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.whitewolf.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "MAIL_BASE_URL", "\"https://mail.whitewolf.tech\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "MAIL_BASE_URL", "\"https://mail.whitewolf.tech\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isReturnDefaultValues = true } }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

- [ ] **Step 5: Write `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:label="WWT"
        android:allowBackup="false"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 6: Add a temporary `MainActivity` placeholder so the manifest resolves**

`app/src/main/java/tech/whitewolf/app/MainActivity.kt`:
```kotlin
package tech.whitewolf.app

import android.app.Activity

// Placeholder; replaced in Task 9 by the Compose shell.
class MainActivity : Activity()
```

- [ ] **Step 7: Write a trivial scaffold unit test**

`app/src/test/java/tech/whitewolf/app/ScaffoldTest.kt`:
```kotlin
package tech.whitewolf.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaffoldTest {
    @Test fun sanity() { assertEquals(4, 2 + 2) }
}
```

- [ ] **Step 8: Write `.gitignore`** (Android/Gradle)

```
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
/build
/app/build
/captures
.externalNativeBuild
.cxx
local.properties
```

- [ ] **Step 9: Build and run the unit test**

Run:
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL; `ScaffoldTest.sanity` passes. (If the Android SDK isn't installed, `assembleDebug` fails at SDK resolution — that's an environment prerequisite, not a code error; see the execution-prerequisite note in the handoff.)

- [ ] **Step 10: Commit**

```bash
git add -A
git commit   # subject: "chore(android): scaffold WWT app module (Compose, BuildConfig, INTERNET)"  + standard trailer
```

---

### Task 2: SubApp + SubAppRegistry

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/subapp/SubApp.kt`, `.../subapp/SubAppRegistry.kt`
- Test: `app/src/test/java/tech/whitewolf/app/subapp/SubAppRegistryTest.kt`

**Interfaces:**
- Produces:
  - `data class SubApp(val id: String, val title: String, val url: String)` with `val host: String` (the URL's host).
  - `object SubAppRegistry { fun all(): List<SubApp>; fun default(): SubApp }` — `all()` returns the ordered list (mail only today); `default()` returns the first entry.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.whitewolf.app.subapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAppRegistryTest {
    @Test fun mailIsTheSoleDefaultSubApp() {
        val all = SubAppRegistry.all()
        assertEquals(1, all.size)
        val mail = SubAppRegistry.default()
        assertEquals("mail", mail.id)
        assertEquals("Mail", mail.title)
        assertEquals(mail, all.first())
    }

    @Test fun subAppExposesHostFromUrl() {
        val s = SubApp(id = "x", title = "X", url = "https://mail.whitewolf.tech/inbox")
        assertEquals("mail.whitewolf.tech", s.host)
    }

    @Test fun defaultUrlComesFromBuildConfig() {
        // The mail entry's URL must be the configured MAIL_BASE_URL, not a literal.
        assertTrue(SubAppRegistry.default().url.startsWith("https://"))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SubAppRegistryTest"`
Expected: FAIL — unresolved `SubApp`/`SubAppRegistry`.

- [ ] **Step 3: Implement `SubApp.kt`**

```kotlin
package tech.whitewolf.app.subapp

import java.net.URI

/** A WWT sub-app hosted in the shell. `host` is derived from [url]. */
data class SubApp(val id: String, val title: String, val url: String) {
    val host: String get() = URI(url).host ?: ""
}
```

- [ ] **Step 4: Implement `SubAppRegistry.kt`**

```kotlin
package tech.whitewolf.app.subapp

import tech.whitewolf.app.BuildConfig

/**
 * The ordered registry of WWT sub-apps. Mail is the only entry today; adding a
 * future sub-app is one new entry here. The shell auto-opens [default] until a
 * launcher UI exists (2+ sub-apps).
 */
object SubAppRegistry {
    private val mail = SubApp(id = "mail", title = "Mail", url = BuildConfig.MAIL_BASE_URL)

    fun all(): List<SubApp> = listOf(mail)
    fun default(): SubApp = all().first()
}
```

- [ ] **Step 5: Run the test to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SubAppRegistryTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/subapp app/src/test/java/tech/whitewolf/app/subapp
git commit   # subject: "feat(subapp): sub-app registry with mail as sub-app #1"  + standard trailer
```

---

### Task 3: SecureStore + TokenStore (+ Keystore-backed impl)

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/auth/SecureStore.kt`, `.../auth/TokenStore.kt`, `.../auth/EncryptedPrefsStore.kt`
- Test: `app/src/test/java/tech/whitewolf/app/auth/TokenStoreTest.kt` (JVM), `app/src/androidTest/java/tech/whitewolf/app/auth/EncryptedPrefsStoreTest.kt` (instrumented)

**Interfaces:**
- Produces:
  - `interface SecureStore { fun getString(key: String): String?; fun putString(key: String, value: String); fun remove(key: String) }`
  - `class TokenStore(private val store: SecureStore, private val nowSeconds: () -> Long)` with:
    - `fun save(token: String, expiresUnix: Long)`
    - `fun token(): String?` — the token, or `null` if absent or `expiresUnix <= now`
    - `fun expiresAt(): Long?`
    - `fun clear()`
  - `class EncryptedPrefsStore(context: Context) : SecureStore` — backed by EncryptedSharedPreferences (Android Keystore).

- [ ] **Step 1: Write the failing JVM test (with an in-memory fake SecureStore)**

```kotlin
package tech.whitewolf.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeSecureStore : SecureStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

class TokenStoreTest {
    @Test fun savesAndReturnsTokenWhenNotExpired() {
        val s = FakeSecureStore()
        val ts = TokenStore(s, nowSeconds = { 1000L })
        ts.save("u.exp.sig", expiresUnix = 2000L)
        assertEquals("u.exp.sig", ts.token())
        assertEquals(2000L, ts.expiresAt())
    }

    @Test fun returnsNullWhenExpired() {
        val s = FakeSecureStore()
        val ts = TokenStore(s, nowSeconds = { 5000L })
        ts.save("t", expiresUnix = 4999L)
        assertNull(ts.token())
    }

    @Test fun returnsNullWhenAbsent() {
        val ts = TokenStore(FakeSecureStore(), nowSeconds = { 0L })
        assertNull(ts.token())
        assertNull(ts.expiresAt())
    }

    @Test fun clearRemovesToken() {
        val s = FakeSecureStore()
        val ts = TokenStore(s, nowSeconds = { 0L })
        ts.save("t", expiresUnix = 10L)
        ts.clear()
        assertNull(ts.token())
        assertNull(ts.expiresAt())
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*TokenStoreTest"`
Expected: FAIL — unresolved `SecureStore`/`TokenStore`.

- [ ] **Step 3: Implement `SecureStore.kt`**

```kotlin
package tech.whitewolf.app.auth

/** Minimal key/value string storage, so TokenStore is testable without Android. */
interface SecureStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}
```

- [ ] **Step 4: Implement `TokenStore.kt`**

```kotlin
package tech.whitewolf.app.auth

/**
 * Persists the native bearer token and its expiry over a [SecureStore].
 * token() returns null once the stored expiry has passed (local check; the token
 * and the session cookie share the backend's 7-day lifetime).
 */
class TokenStore(
    private val store: SecureStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val keyToken = "auth.token"
    private val keyExpires = "auth.expires"

    fun save(token: String, expiresUnix: Long) {
        store.putString(keyToken, token)
        store.putString(keyExpires, expiresUnix.toString())
    }

    fun token(): String? {
        val t = store.getString(keyToken) ?: return null
        val exp = store.getString(keyExpires)?.toLongOrNull() ?: return null
        return if (nowSeconds() < exp) t else null
    }

    fun expiresAt(): Long? = store.getString(keyExpires)?.toLongOrNull()

    fun clear() {
        store.remove(keyToken)
        store.remove(keyExpires)
    }
}
```

- [ ] **Step 5: Run the JVM test to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*TokenStoreTest"`
Expected: PASS.

- [ ] **Step 6: Implement `EncryptedPrefsStore.kt` (Keystore-backed SecureStore)**

```kotlin
package tech.whitewolf.app.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** SecureStore backed by EncryptedSharedPreferences (Android Keystore master key). */
class EncryptedPrefsStore(context: Context) : SecureStore {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "wwt_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
```

- [ ] **Step 7: Write the instrumented test for the real Keystore round-trip**

`app/src/androidTest/java/tech/whitewolf/app/auth/EncryptedPrefsStoreTest.kt`:
```kotlin
package tech.whitewolf.app.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedPrefsStoreTest {
    @Test fun roundTripsThroughKeystore() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = EncryptedPrefsStore(ctx)
        store.remove("k")
        assertNull(store.getString("k"))
        store.putString("k", "v")
        assertEquals("v", store.getString("k"))
        store.remove("k")
        assertNull(store.getString("k"))
    }
}
```

- [ ] **Step 8: Run the instrumented test (needs a device/emulator)**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*EncryptedPrefsStoreTest"`
Expected: PASS. (If no device is attached, this cannot run — note it in the report and ensure the JVM `TokenStoreTest` passed; the controller decides whether a device is available.)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/auth/SecureStore.kt \
        app/src/main/java/tech/whitewolf/app/auth/TokenStore.kt \
        app/src/main/java/tech/whitewolf/app/auth/EncryptedPrefsStore.kt \
        app/src/test/java/tech/whitewolf/app/auth/TokenStoreTest.kt \
        app/src/androidTest/java/tech/whitewolf/app/auth/EncryptedPrefsStoreTest.kt
git commit   # subject: "feat(auth): Keystore-backed token store with expiry"  + standard trailer
```

---

### Task 4: WebCookies interface + Set-Cookie extractor + Android impl

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/auth/WebCookies.kt`, `.../auth/AndroidWebCookies.kt`
- Test: `app/src/test/java/tech/whitewolf/app/auth/SetCookieTest.kt` (JVM)

**Interfaces:**
- Produces:
  - `interface WebCookies { fun seed(url: String, setCookieHeader: String); fun clear(url: String) }`
  - `fun sessionCookieFrom(setCookieHeaders: List<String>): String?` — returns the raw `Set-Cookie` value whose cookie name is `session`, or null. (pure; JVM-tested)
  - `class AndroidWebCookies : WebCookies` — over `android.webkit.CookieManager`.

- [ ] **Step 1: Write the failing JVM test for the extractor**

```kotlin
package tech.whitewolf.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetCookieTest {
    @Test fun picksTheSessionCookieHeader() {
        val headers = listOf(
            "other=abc; Path=/",
            "session=u.exp.sig; Path=/; HttpOnly; Secure; SameSite=Lax",
        )
        assertEquals(
            "session=u.exp.sig; Path=/; HttpOnly; Secure; SameSite=Lax",
            sessionCookieFrom(headers),
        )
    }

    @Test fun returnsNullWhenNoSessionCookie() {
        assertNull(sessionCookieFrom(listOf("foo=1; Path=/")))
        assertNull(sessionCookieFrom(emptyList()))
    }

    @Test fun matchesSessionNameNotSubstring() {
        // "sessionx=" must NOT be treated as the session cookie.
        assertNull(sessionCookieFrom(listOf("sessionx=nope; Path=/")))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SetCookieTest"`
Expected: FAIL — unresolved `sessionCookieFrom`.

- [ ] **Step 3: Implement `WebCookies.kt`**

```kotlin
package tech.whitewolf.app.auth

/** Seeds/clears the WebView's cookie jar so the hosted SPA is authenticated. */
interface WebCookies {
    fun seed(url: String, setCookieHeader: String)
    fun clear(url: String)
}

/**
 * Returns the raw Set-Cookie header value whose cookie name is exactly `session`,
 * or null. Matches the name token before '=' to avoid substring false positives.
 */
fun sessionCookieFrom(setCookieHeaders: List<String>): String? =
    setCookieHeaders.firstOrNull { header ->
        header.substringBefore('=', missingDelimiterValue = "").trim() == "session"
    }
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SetCookieTest"`
Expected: PASS.

- [ ] **Step 5: Implement `AndroidWebCookies.kt`**

```kotlin
package tech.whitewolf.app.auth

import android.webkit.CookieManager

/** WebCookies over the global WebView CookieManager. */
class AndroidWebCookies : WebCookies {
    override fun seed(url: String, setCookieHeader: String) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setCookie(url, setCookieHeader)
        cm.flush()
    }

    override fun clear(url: String) {
        // Remove all cookies; the only cookies we set are the backend session.
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/auth/WebCookies.kt \
        app/src/main/java/tech/whitewolf/app/auth/AndroidWebCookies.kt \
        app/src/test/java/tech/whitewolf/app/auth/SetCookieTest.kt
git commit   # subject: "feat(auth): session-cookie extractor + WebView cookie seeding"  + standard trailer
```

---

### Task 5: AuthRepository (login orchestration)

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/auth/AuthRepository.kt`
- Test: `app/src/test/java/tech/whitewolf/app/auth/AuthRepositoryTest.kt` (JVM, MockWebServer)

**Interfaces:**
- Consumes: `TokenStore` (Task 3), `WebCookies` + `sessionCookieFrom` (Task 4), OkHttp.
- Produces:
  - `sealed interface LoginResult { object Success; object InvalidCredentials; data class Error(val message: String) }`
  - `class AuthRepository(private val http: OkHttpClient, private val baseUrl: String, private val tokenStore: TokenStore, private val cookies: WebCookies)` with:
    - `fun login(email: String, password: String): LoginResult` — POSTs `baseUrl + "/api/login"`, on 200 parses `{token, expires}`, calls `tokenStore.save`, seeds the `session` cookie via `cookies.seed(baseUrl, …)`; 401 → `InvalidCredentials`; other/IO → `Error`.
    - `fun isLoggedIn(): Boolean = tokenStore.token() != null`
    - `fun logout()` — `tokenStore.clear()` + `cookies.clear(baseUrl)`.

- [ ] **Step 1: Write the failing JVM test (MockWebServer + fakes)**

```kotlin
package tech.whitewolf.app.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeCookies : WebCookies {
    var seededUrl: String? = null
    var seededHeader: String? = null
    var cleared = false
    override fun seed(url: String, setCookieHeader: String) { seededUrl = url; seededHeader = setCookieHeader }
    override fun clear(url: String) { cleared = true }
}

class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private fun repo(store: TokenStore, cookies: WebCookies) =
        AuthRepository(OkHttpClient(), server.url("/").toString().trimEnd('/'), store, cookies)

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun freshStore() = TokenStore(object : SecureStore {
        val m = mutableMapOf<String, String>()
        override fun getString(key: String) = m[key]
        override fun putString(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }, nowSeconds = { 1000L })

    @Test fun successStoresTokenAndSeedsCookie() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "session=u.9999.sig; Path=/; HttpOnly")
                .setBody("""{"ok":true,"token":"u.9999.sig","expires":9999}""")
        )
        val store = freshStore()
        val cookies = FakeCookies()
        val result = repo(store, cookies).login("a@x.tech", "pw")

        assertEquals(LoginResult.Success, result)
        assertEquals("u.9999.sig", store.token())
        assertEquals(9999L, store.expiresAt())
        assertTrue(cookies.seededHeader!!.startsWith("session=u.9999.sig"))

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/login", sent.path)
        assertTrue(sent.body.readUtf8().contains("\"email\":\"a@x.tech\""))
    }

    @Test fun unauthorizedReturnsInvalidCredentialsAndStoresNothing() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val store = freshStore()
        val cookies = FakeCookies()
        val result = repo(store, cookies).login("a@x.tech", "bad")
        assertEquals(LoginResult.InvalidCredentials, result)
        assertNull(store.token())
        assertNull(cookies.seededHeader)
    }

    @Test fun serverErrorReturnsError() {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = repo(freshStore(), FakeCookies()).login("a@x.tech", "pw")
        assertTrue(result is LoginResult.Error)
    }

    @Test fun logoutClearsTokenAndCookies() {
        val store = freshStore(); store.save("t", 9999L)
        val cookies = FakeCookies()
        val r = repo(store, cookies)
        r.logout()
        assertNull(store.token())
        assertTrue(cookies.cleared)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*AuthRepositoryTest"`
Expected: FAIL — unresolved `AuthRepository`/`LoginResult`.

- [ ] **Step 3: Implement `AuthRepository.kt`**

```kotlin
package tech.whitewolf.app.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface LoginResult {
    data object Success : LoginResult
    data object InvalidCredentials : LoginResult
    data class Error(val message: String) : LoginResult
}

class AuthRepository(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val tokenStore: TokenStore,
    private val cookies: WebCookies,
) {
    @Serializable private data class LoginReq(val email: String, val password: String)
    @Serializable private data class LoginResp(val ok: Boolean = false, val token: String = "", val expires: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun isLoggedIn(): Boolean = tokenStore.token() != null

    fun login(email: String, password: String): LoginResult {
        val body = json.encodeToString(LoginReq.serializer(), LoginReq(email, password))
            .toRequestBody(jsonMedia)
        val req = Request.Builder().url("$baseUrl/api/login").post(body).build()
        return try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 -> LoginResult.InvalidCredentials
                    !resp.isSuccessful -> LoginResult.Error("server returned ${resp.code}")
                    else -> {
                        val parsed = json.decodeFromString(
                            LoginResp.serializer(), resp.body?.string().orEmpty()
                        )
                        if (!parsed.ok || parsed.token.isBlank()) {
                            return LoginResult.Error("malformed login response")
                        }
                        tokenStore.save(parsed.token, parsed.expires)
                        sessionCookieFrom(resp.headers("Set-Cookie"))?.let {
                            cookies.seed(baseUrl, it)
                        }
                        LoginResult.Success
                    }
                }
            }
        } catch (e: IOException) {
            LoginResult.Error(e.message ?: "network error")
        }
    }

    fun logout() {
        tokenStore.clear()
        cookies.clear(baseUrl)
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*AuthRepositoryTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/auth/AuthRepository.kt \
        app/src/test/java/tech/whitewolf/app/auth/AuthRepositoryTest.kt
git commit   # subject: "feat(auth): native login orchestration (token + cookie seeding)"  + standard trailer
```

---

### Task 6: LoginViewModel

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/auth/LoginViewModel.kt`
- Test: `app/src/test/java/tech/whitewolf/app/auth/LoginViewModelTest.kt` (JVM, coroutines-test)

**Interfaces:**
- Consumes: `AuthRepository` + `LoginResult` (Task 5).
- Produces:
  - `data class LoginUiState(val email: String = "", val password: String = "", val loading: Boolean = false, val error: String? = null, val loggedIn: Boolean = false)`
  - `class LoginViewModel(private val auth: AuthRepository, private val io: CoroutineDispatcher = Dispatchers.IO)` with `val state: StateFlow<LoginUiState>`, `fun onEmail(s)`, `fun onPassword(s)`, `fun submit()`. `submit()` sets `loading`, calls `auth.login` off the main thread, then sets `loggedIn=true` on Success, `error="Incorrect email or password"` on InvalidCredentials, `error=<message>` on Error.

- [ ] **Step 1: Write the failing JVM test**

```kotlin
package tech.whitewolf.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(result: LoginResult) = LoginViewModel(
        auth = object {
            // minimal stand-in matching the methods VM calls
        }.let { _ ->
            FakeAuth(result)
        },
        io = dispatcher,
    )

    @Test fun successSetsLoggedIn() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth(LoginResult.Success), io = dispatcher)
        vm.onEmail("a@x.tech"); vm.onPassword("pw")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test fun invalidCredentialsSetsError() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth(LoginResult.InvalidCredentials), io = dispatcher)
        vm.submit(); advanceUntilIdle()
        assertFalse(vm.state.value.loggedIn)
        assertEquals("Incorrect email or password", vm.state.value.error)
    }

    @Test fun errorSurfacesMessage() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth(LoginResult.Error("network error")), io = dispatcher)
        vm.submit(); advanceUntilIdle()
        assertEquals("network error", vm.state.value.error)
    }
}
```

> Implementer note: `LoginViewModel` must depend on an interface it can fake, not the concrete `AuthRepository`. Introduce `interface Authenticator { fun login(email: String, password: String): LoginResult; fun isLoggedIn(): Boolean; fun logout() }`, make `AuthRepository : Authenticator` (add the interface to the Task 5 class — a one-line `: Authenticator` plus `override` keywords; if you cannot modify Task 5's file cleanly, report NEEDS_CONTEXT), and define `FakeAuth(private val result: LoginResult) : Authenticator` in the test file. Replace the stray `vm(...)` helper above with direct `LoginViewModel(FakeAuth(...), dispatcher)` construction (shown in the test bodies).

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LoginViewModelTest"`
Expected: FAIL — unresolved `LoginViewModel`/`Authenticator`/`FakeAuth`.

- [ ] **Step 3: Add the `Authenticator` interface to AuthRepository**

In `app/src/main/java/tech/whitewolf/app/auth/AuthRepository.kt`, add above the class:
```kotlin
interface Authenticator {
    fun login(email: String, password: String): LoginResult
    fun isLoggedIn(): Boolean
    fun logout()
}
```
and change the class declaration to `class AuthRepository(...) : Authenticator {` with `override fun login`, `override fun isLoggedIn`, `override fun logout`.

- [ ] **Step 4: Implement `LoginViewModel.kt`**

```kotlin
package tech.whitewolf.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
)

class LoginViewModel(
    private val auth: Authenticator,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmail(s: String) = _state.update { it.copy(email = s, error = null) }
    fun onPassword(s: String) = _state.update { it.copy(password = s, error = null) }

    fun submit() {
        val s = _state.value
        if (s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(io) { auth.login(s.email.trim(), s.password) }
            _state.update {
                when (result) {
                    is LoginResult.Success -> it.copy(loading = false, loggedIn = true)
                    is LoginResult.InvalidCredentials ->
                        it.copy(loading = false, error = "Incorrect email or password")
                    is LoginResult.Error -> it.copy(loading = false, error = result.message)
                }
            }
        }
    }
}
```

- [ ] **Step 5: Run the test to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*LoginViewModelTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/auth/LoginViewModel.kt \
        app/src/main/java/tech/whitewolf/app/auth/AuthRepository.kt \
        app/src/test/java/tech/whitewolf/app/auth/LoginViewModelTest.kt
git commit   # subject: "feat(auth): LoginViewModel state machine"  + standard trailer
```

---

### Task 7: Login screen (Compose)

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/ui/LoginScreen.kt`
- Test: `app/src/androidTest/java/tech/whitewolf/app/ui/LoginScreenTest.kt` (Compose UI test, instrumented)

**Interfaces:**
- Consumes: `LoginUiState` (Task 6).
- Produces: `@Composable fun LoginScreen(state: LoginUiState, onEmail: (String)->Unit, onPassword: (String)->Unit, onSubmit: ()->Unit)` — a stateless Composable driven by `state`; shows email + password fields, a "Sign in" button (disabled while `loading`), a progress indicator while `loading`, and the `error` text when non-null. Test tags: `"email"`, `"password"`, `"submit"`, `"error"`, `"progress"`.

- [ ] **Step 1: Write the failing Compose UI test**

```kotlin
package tech.whitewolf.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.whitewolf.app.auth.LoginUiState

class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsErrorAndFiresSubmit() {
        var submitted = false
        compose.setContent {
            LoginScreen(
                state = LoginUiState(error = "Incorrect email or password"),
                onEmail = {}, onPassword = {}, onSubmit = { submitted = true },
            )
        }
        compose.onNodeWithTag("error").assertIsDisplayed()
        compose.onNodeWithTag("submit").performClick()
        assertTrue(submitted)
    }

    @Test fun disablesSubmitWhileLoading() {
        compose.setContent {
            LoginScreen(LoginUiState(loading = true), {}, {}, {})
        }
        compose.onNodeWithTag("progress").assertIsDisplayed()
        compose.onNodeWithTag("submit").assertIsNotEnabled()
    }
}
```

- [ ] **Step 2: Run it to confirm it fails (needs a device/emulator)**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*LoginScreenTest"`
Expected: FAIL — unresolved `LoginScreen`. (If no device is available, note it; this task's gate is the implementation compiling + a device run when one is present.)

- [ ] **Step 3: Implement `LoginScreen.kt`**

```kotlin
package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.whitewolf.app.auth.LoginUiState

@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("White Wolf Technology", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = state.email, onValueChange = onEmail,
            label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).testTag("email"),
        )
        OutlinedTextField(
            value = state.password, onValueChange = onPassword,
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("password"),
        )
        if (state.error != null) {
            Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp).testTag("error"),
            )
        }
        Button(
            onClick = onSubmit,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("submit"),
        ) { Text("Sign in") }
        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp).testTag("progress"))
        }
    }
}
```

- [ ] **Step 4: Run the test (device) / confirm compile**

Run: `./gradlew :app:assembleDebug` (must compile) and, when a device is available, `./gradlew :app:connectedDebugAndroidTest --tests "*LoginScreenTest"`.
Expected: compile clean; UI test PASS on device.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/ui/LoginScreen.kt \
        app/src/androidTest/java/tech/whitewolf/app/ui/LoginScreenTest.kt
git commit   # subject: "feat(ui): native login screen"  + standard trailer
```

---

### Task 8: NavPolicy + hardened SubAppWebView

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/web/NavPolicy.kt`, `app/src/main/java/tech/whitewolf/app/ui/SubAppWebView.kt`
- Test: `app/src/test/java/tech/whitewolf/app/web/NavPolicyTest.kt` (JVM)

**Interfaces:**
- Consumes: `SubApp` (Task 2).
- Produces:
  - `object NavPolicy { fun isInApp(url: String, allowedHost: String): Boolean }` — true iff the URL is HTTPS and its host equals or is a subdomain of `allowedHost`; false otherwise (external → open in system browser). (pure; JVM-tested)
  - `@Composable fun SubAppWebView(subApp: SubApp, onPageError: () -> Unit, onPageLoaded: () -> Unit)` — hardened WebView loading `subApp.url`; in-app navigation kept in the WebView, external links opened via an `ACTION_VIEW` intent; load errors call `onPageError`.

- [ ] **Step 1: Write the failing JVM test for NavPolicy**

```kotlin
package tech.whitewolf.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPolicyTest {
    private val host = "mail.whitewolf.tech"

    @Test fun sameHostHttpsIsInApp() {
        assertTrue(NavPolicy.isInApp("https://mail.whitewolf.tech/inbox", host))
    }
    @Test fun subdomainIsInApp() {
        assertTrue(NavPolicy.isInApp("https://sub.mail.whitewolf.tech/x", host))
    }
    @Test fun differentHostIsExternal() {
        assertFalse(NavPolicy.isInApp("https://evil.example.com/x", host))
    }
    @Test fun suffixTrickIsExternal() {
        assertFalse(NavPolicy.isInApp("https://mail.whitewolf.tech.evil.com/x", host))
    }
    @Test fun nonHttpsIsExternal() {
        assertFalse(NavPolicy.isInApp("http://mail.whitewolf.tech/x", host))
        assertFalse(NavPolicy.isInApp("mailto:a@b.com", host))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*NavPolicyTest"`
Expected: FAIL — unresolved `NavPolicy`.

- [ ] **Step 3: Implement `NavPolicy.kt`**

```kotlin
package tech.whitewolf.app.web

import java.net.URI

/** Decides whether a navigation target stays in the WebView or opens externally. */
object NavPolicy {
    fun isInApp(url: String, allowedHost: String): Boolean {
        val uri = try { URI(url) } catch (e: Exception) { return false }
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val allowed = allowedHost.lowercase()
        return host == allowed || host.endsWith(".$allowed")
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*NavPolicyTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Implement `SubAppWebView.kt`**

```kotlin
package tech.whitewolf.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import tech.whitewolf.app.subapp.SubApp
import tech.whitewolf.app.web.NavPolicy

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SubAppWebView(subApp: SubApp, onPageError: () -> Unit, onPageLoaded: () -> Unit) {
    val context = LocalContext.current
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            run {
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
            }
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    return if (NavPolicy.isInApp(url, subApp.host)) {
                        false // let the WebView load it
                    } else {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true // handled externally
                    }
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError,
                ) {
                    if (request.isForMainFrame) onPageError()
                }

                override fun onPageFinished(view: WebView, url: String) { onPageLoaded() }
            }
            loadUrl(subApp.url)
        }
    })
}
```

- [ ] **Step 6: Confirm it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/web/NavPolicy.kt \
        app/src/main/java/tech/whitewolf/app/ui/SubAppWebView.kt \
        app/src/test/java/tech/whitewolf/app/web/NavPolicyTest.kt
git commit   # subject: "feat(web): hardened sub-app WebView + same-host nav policy"  + standard trailer
```

---

### Task 9: Shell wiring (MainActivity + nav + loading/error/retry + sign-out)

**Files:**
- Modify: `app/src/main/java/tech/whitewolf/app/MainActivity.kt` (replace the placeholder)
- Create: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt`, `app/src/main/java/tech/whitewolf/app/AppContainer.kt`
- Test: `app/src/androidTest/java/tech/whitewolf/app/ShellFlowTest.kt` (instrumented, optional gate)

**Interfaces:**
- Consumes: everything above — `AuthRepository`, `LoginViewModel`, `LoginScreen`, `SubAppWebView`, `SubAppRegistry`, `EncryptedPrefsStore`, `AndroidWebCookies`.
- Produces:
  - `class AppContainer(context: Context)` — wires the real dependencies: `OkHttpClient`, `EncryptedPrefsStore`→`TokenStore`, `AndroidWebCookies`, `AuthRepository(http, BuildConfig.MAIL_BASE_URL-based baseUrl, …)`. Exposes `val auth: AuthRepository`.
  - `@Composable fun ShellScreen(container: AppContainer)` — if `auth.isLoggedIn()` shows the sub-app, else the login flow; on login success switches to the sub-app; provides sign-out; shows loading/error/retry around the WebView.
  - `MainActivity` sets `ShellScreen` as content.

- [ ] **Step 1: Implement `AppContainer.kt`**

```kotlin
package tech.whitewolf.app

import android.content.Context
import okhttp3.OkHttpClient
import tech.whitewolf.app.auth.AndroidWebCookies
import tech.whitewolf.app.auth.AuthRepository
import tech.whitewolf.app.auth.EncryptedPrefsStore
import tech.whitewolf.app.auth.TokenStore
import tech.whitewolf.app.subapp.SubAppRegistry

/** Manual DI: builds the real dependency graph for the shell. */
class AppContainer(context: Context) {
    private val http = OkHttpClient()
    private val tokenStore = TokenStore(EncryptedPrefsStore(context.applicationContext))
    private val cookies = AndroidWebCookies()

    // Auth base URL is the mail sub-app's origin (scheme://host) for now.
    private val baseUrl: String = SubAppRegistry.default().let {
        val u = java.net.URI(it.url); "${u.scheme}://${u.host}"
    }

    val auth = AuthRepository(http, baseUrl, tokenStore, cookies)
}
```

- [ ] **Step 2: Implement `ShellScreen.kt`**

```kotlin
package tech.whitewolf.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tech.whitewolf.app.AppContainer
import tech.whitewolf.app.auth.LoginViewModel
import tech.whitewolf.app.subapp.SubAppRegistry

@Composable
fun ShellScreen(container: AppContainer) {
    var loggedIn by remember { mutableStateOf(container.auth.isLoggedIn()) }

    if (!loggedIn) {
        val vm = remember { LoginViewModel(container.auth) }
        val state by vm.state.collectAsState()
        if (state.loggedIn) loggedIn = true
        LoginScreen(state, vm::onEmail, vm::onPassword, vm::submit)
        return
    }

    val subApp = remember { SubAppRegistry.default() }
    var loading by remember { mutableStateOf(true) }
    var errored by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            errored -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Couldn't reach ${subApp.title}.")
                Button(
                    onClick = { errored = false; loading = true; reloadKey++ },
                    modifier = Modifier.padding(top = 12.dp).testTag("retry"),
                ) { Text("Retry") }
                Button(
                    onClick = { container.auth.logout(); loggedIn = false },
                    modifier = Modifier.padding(top = 8.dp).testTag("signout"),
                ) { Text("Sign out") }
            }
            else -> {
                key(reloadKey) {
                    SubAppWebView(
                        subApp = subApp,
                        onPageError = { errored = true; loading = false },
                        onPageLoaded = { loading = false },
                    )
                }
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).testTag("progress"),
                    )
                }
            }
        }
    }
}
```
> Implementer note: add `import androidx.compose.runtime.key` for the `key(reloadKey)` block.

- [ ] **Step 3: Replace `MainActivity.kt`**

```kotlin
package tech.whitewolf.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import tech.whitewolf.app.ui.ShellScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainer(this)
        setContent {
            MaterialTheme {
                Surface { ShellScreen(container) }
            }
        }
    }
}
```

- [ ] **Step 4: Build the app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Write the instrumented shell smoke test (optional gate; needs device)**

`app/src/androidTest/java/tech/whitewolf/app/ShellFlowTest.kt`:
```kotlin
package tech.whitewolf.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class ShellFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun showsLoginWhenLoggedOut() {
        // Fresh install → not logged in → the login submit button is present.
        compose.onNodeWithTag("submit").assertIsDisplayed()
    }
}
```

- [ ] **Step 6: Run unit + (if device) instrumented suites**

Run:
```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
# when a device/emulator is attached:
./gradlew :app:connectedDebugAndroidTest
```
Expected: all JVM unit tests pass; app assembles; instrumented tests pass on device.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/MainActivity.kt \
        app/src/main/java/tech/whitewolf/app/AppContainer.kt \
        app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt \
        app/src/androidTest/java/tech/whitewolf/app/ShellFlowTest.kt
git commit   # subject: "feat(shell): nav by auth state + loading/error/retry + sign-out"  + standard trailer
```

---

## Self-Review

**Spec coverage (against `2026-06-30-android-shell-auth-email-design.md`):**
- §2 sub-app registry (mail only) → Task 2. ✓ · native login gating shell → Tasks 6,7,9. ✓ · token in Keystore → Task 3. ✓ · seed WebView cookie → Tasks 4,5. ✓ · hardened WebView hosting sub-app → Task 8. ✓ · shell auto-opens sole sub-app → Task 9. ✓ · loading/error/retry + sign-out → Task 9. ✓ · no launcher UI / no JS bridge → respected (none built). ✓
- §3 native-driven login, cookie for SPA + token for native → Tasks 5,9. ✓
- §4 components (`SubApp`/`SubAppRegistry`, `TokenStore`, `SessionCookie`/`WebCookies`, `AuthRepository`, `LoginViewModel`, login screen, `SubAppWebView`, shell) → Tasks 2–9. ✓ (`SessionCookie` is realized as `WebCookies`/`AndroidWebCookies` — same responsibility, renamed for the interface/impl split; noted so the reviewer isn't surprised.)
- §5 application id `tech.whitewolf.app`, label WWT, minSdk29/targetSdk36, `MAIL_BASE_URL`, INTERNET-only → Task 1. ✓
- §6 error handling (bad creds inline, network error+retry, valid token skips login, expired→login, WebView failure→retry screen) → Tasks 6 (creds/error), 3 (expiry), 9 (startup skip + WebView retry). ✓ The spec's explicitly-deferred SPA-side 401 re-login is NOT built (correct — out of scope).
- §8 testing: JVM unit (TokenStore, SetCookie/WebCookies, AuthRepository via MockWebServer, LoginViewModel, NavPolicy) + instrumented (EncryptedPrefsStore, LoginScreen, Shell) → Tasks 3–9. ✓

**Placeholder scan:** No TBD/TODO. Two `> Implementer note:` blocks (Task 6 `Authenticator` interface; Task 9 `key` import) are concrete instructions, not deferred work. The Task 6 test's stray `vm(...)` helper is explicitly replaced by the note — the canonical construction is in the test bodies.

**Type consistency:** `LoginResult` (Success/InvalidCredentials/Error(message)) is identical across Tasks 5, 6. `Authenticator`/`AuthRepository` methods (`login`, `isLoggedIn`, `logout`) match across Tasks 5, 6, 9. `TokenStore(save/token/expiresAt/clear)` consistent across 3, 5. `WebCookies(seed(url,header)/clear(url))` + `sessionCookieFrom(List<String>)` consistent across 4, 5. `SubApp(id,title,url,host)` / `SubAppRegistry(all/default)` consistent across 2, 8, 9. `NavPolicy.isInApp(url, allowedHost)` consistent across 8 (impl) and 8 (WebView use). `BuildConfig.MAIL_BASE_URL` consistent across 1, 2, 9. ✓

**Note for the executor (environment prerequisite):** building/testing requires a JDK 17 + the Android SDK (and a device/emulator for the `connectedDebugAndroidTest` instrumented tasks). The JVM unit tests (Tasks 2–6, 8) are the primary per-task gates and run without a device; instrumented tests (Tasks 3,7,9) are secondary gates that the controller runs when a device is available, otherwise deferred and noted.
