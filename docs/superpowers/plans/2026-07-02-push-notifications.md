# Push Notifications (UnifiedPush) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** New-mail push notifications on the WWT Android app via self-hosted ntfy + UnifiedPush, showing a generic "New mail" notification with no Google dependency.

**Architecture:** Self-host ntfy at `ntfy.whitewolf.tech` and point the mail backend at it (`PUSH_ENDPOINT_HOSTS`); the Android app registers a UnifiedPush endpoint with the backend (`POST /api/push/register`, Bearer token), and a `MessagingReceiver` posts a "New mail" notification on each data-light wake-up. Backend push endpoints + fan-out already exist (PR #24) — no backend code change.

**Tech Stack:** Kotlin/Jetpack Compose (existing app), `org.unifiedpush.android:connector` (pinned 2.4.0), OkHttp + MockWebServer, AndroidX notifications, ntfy server (systemd + Caddy).

**Repo/branch:** `github.com/PeterRounce/wwt-mobile`, branch `feat/push-notifications`. Android paths relative to repo root `/home/dev/mobile-app`. Task 1 (infra) runs on the mail host (`project-mail`, `/home/dev/email-client-maileroo` + `/opt/maileroo`).

## Global Constraints

- Notification is a **generic "New mail"** — no native fetch of mail content. (spec §3)
- Push server is self-hosted **ntfy at `https://ntfy.whitewolf.tech`**; backend `PUSH_ENDPOINT_HOSTS=ntfy.whitewolf.tech`. (spec §3, §4)
- Distributor = **standard** UnifiedPush (ntfy app via Obtainium); **no embedded distributor**, **no foreground service**. (spec §2, §3, §8)
- Push payload is data-light `{"type":"new_mail"}`; **no backend code change** (reuse PR #24). (spec §2)
- Permissions after this: `INTERNET`, `POST_NOTIFICATIONS` only. (spec §8)
- UnifiedPush connector pinned to **`org.unifiedpush.android:connector:2.4.0`** (v2 `MessagingReceiver` API). If a different connector major is used, the receiver/registration API differs (v3 uses a `PushService`) — Task 5 says to verify against the pinned version.
- Company name in any string/comment: "White Wolf Technology" or "WWT" — never "White Wolf" alone (`whitewolf.tech` domain literals fine).
- Install instructions use **Obtainium**, never F-Droid as the primary path.
- Gradle runs need the toolchain env; set `dangerouslyDisableSandbox: true` on Bash calls that run Gradle:
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  export ANDROID_HOME=/home/dev/android-sdk
  export PATH="$JAVA_HOME/bin:$PATH"
  cd /home/dev/mobile-app
  ```
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg
  ```

## File Structure

- Android (`app/src/main/java/tech/whitewolf/app/`):
  - `push/PushApiClient.kt` — authenticated register/unregister calls (JVM-testable).
  - `push/Notifications.kt` — notification channel + post "New mail".
  - `push/PushReceiver.kt` — UnifiedPush `MessagingReceiver`.
  - `push/PushManager.kt` — enable/disable/hasDistributor lifecycle.
  - `AppContainer.kt` — expose `pushApiClient` + base URL (modify).
  - `ui/ShellScreen.kt` — enable on login, disable on sign-out, no-distributor hint (modify).
  - `MainActivity.kt` — request `POST_NOTIFICATIONS` (modify).
  - `AndroidManifest.xml` — `POST_NOTIFICATIONS`, receiver registration (modify).
  - `gradle/libs.versions.toml`, `app/build.gradle.kts` — connector dependency (modify).
- Tests: `app/src/test/java/tech/whitewolf/app/push/PushApiClientTest.kt` (JVM); instrumented under `app/src/androidTest/...` (deferred).
- Docs: `docs/PUSH.md` — onboarding runbook.
- Infra (mail host): ntfy install + Caddy vhost + `maileroo.env` (`PUSH_ENDPOINT_HOSTS`).

---

### Task 1: Infra — deploy ntfy + point the backend at it

> **Controller/operator task, not a code subagent.** Runs on the mail host (`project-mail`). The DNS record is the user's action.

**Files:** ntfy config + Caddyfile on host; `/opt/maileroo/maileroo.env`.

**Interfaces:**
- Produces: a working UnifiedPush provider at `https://ntfy.whitewolf.tech`; the mail backend accepting `POST /api/push/register` for `ntfy.whitewolf.tech` endpoints.

- [ ] **Step 1: DNS (user)** — add an A record `ntfy.whitewolf.tech` → the `project-mail` host's public IP (same IP as `mail.whitewolf.tech`). Confirm: `dig +short ntfy.whitewolf.tech` returns that IP.

- [ ] **Step 2: Install ntfy on the host**

```bash
# Debian/Ubuntu host:
curl -fsSL https://archive.ntfy.sh/apt/keyring.gpg | sudo gpg --dearmor -o /usr/share/keyrings/ntfy.gpg
echo "deb [signed-by=/usr/share/keyrings/ntfy.gpg] https://archive.ntfy.sh/apt stable main" | sudo tee /etc/apt/sources.list.d/ntfy.list
sudo apt-get update && sudo apt-get install -y ntfy
```

- [ ] **Step 3: Configure ntfy** — write `/etc/ntfy/server.yml` with at least:

```yaml
base-url: "https://ntfy.whitewolf.tech"
listen-http: "127.0.0.1:2586"          # behind Caddy; not public directly
behind-proxy: true
# v1: open server (no auth) — UnifiedPush endpoints are random, content-free (spec §6)
auth-default-access: "read-write"
```
Then: `sudo systemctl enable --now ntfy` and `curl -s http://127.0.0.1:2586/v1/health` → `{"healthy":true}`.

- [ ] **Step 4: Caddy vhost** — add to the host Caddyfile (same file that serves `mail.whitewolf.tech`):

```
ntfy.whitewolf.tech {
	reverse_proxy 127.0.0.1:2586
}
```
Reload Caddy (`sudo systemctl reload caddy`), then `curl -s https://ntfy.whitewolf.tech/v1/health` → `{"healthy":true}`.

- [ ] **Step 5: Point the backend at ntfy** — in `/opt/maileroo/maileroo.env` add/set:

```
PUSH_ENDPOINT_HOSTS=ntfy.whitewolf.tech
```
Redeploy: `cd /home/dev/email-client-maileroo && git checkout master && git pull && make deploy` (atomic swap + health check).

- [ ] **Step 6: Verify host-pinning end to end**

```bash
# Unauthenticated register must be 401 (auth required):
curl -s -o /dev/null -w "%{http_code}\n" -X POST https://mail.whitewolf.tech/api/push/register \
  -H 'Content-Type: application/json' -d '{"endpoint":"https://ntfy.whitewolf.tech/UPxxxx?up=1"}'
```
Expected: `401` (endpoint requires auth). Full accept/reject is exercised by the app in later tasks. Record results in the report.

---

### Task 2: Add the UnifiedPush connector dependency

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

**Interfaces:**
- Produces: `org.unifiedpush.android:connector` available to the app.

- [ ] **Step 1: Add the version + library to the catalog**

In `gradle/libs.versions.toml`, add under `[versions]`:
```toml
unifiedpush = "2.4.0"
```
under `[libraries]`:
```toml
unifiedpush-connector = { group = "org.unifiedpush.android", name = "connector", version.ref = "unifiedpush" }
```

- [ ] **Step 2: Add the dependency to the app module**

In `app/build.gradle.kts`, in `dependencies { }`, add:
```kotlin
    implementation(libs.unifiedpush.connector)
```

- [ ] **Step 3: Verify it resolves + builds**

Run (sandbox off): `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (the connector artifact downloads and links).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit   # subject: "build(push): add UnifiedPush connector dependency"  + standard trailer
```

---

### Task 3: PushApiClient (authenticated register/unregister)

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/push/PushApiClient.kt`
- Test: `app/src/test/java/tech/whitewolf/app/push/PushApiClientTest.kt`

**Interfaces:**
- Consumes: OkHttp; a token provider `() -> String?` (from `TokenStore.token()`).
- Produces: `class PushApiClient(http: OkHttpClient, baseUrl: String, token: () -> String?)` with `fun register(endpoint: String): Boolean` (POST `/api/push/register`, `{"endpoint":…}`, `Authorization: Bearer <token>`; true iff HTTP 2xx) and `fun unregister(endpoint: String): Boolean` (POST `/api/push/unregister`). Never throws; returns false on no-token/IO/non-2xx.

- [ ] **Step 1: Write the failing JVM test**

```kotlin
package tech.whitewolf.app.push

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PushApiClientTest {
    private lateinit var server: MockWebServer
    private fun client(token: String?) =
        PushApiClient(OkHttpClient(), server.url("/").toString().trimEnd('/'), { token })

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun registerPostsEndpointWithBearer() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val ok = client("u.9999.sig").register("https://ntfy.whitewolf.tech/UPabc?up=1")
        assertTrue(ok)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/push/register", req.path)
        assertEquals("Bearer u.9999.sig", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("https://ntfy.whitewolf.tech/UPabc?up=1"))
    }

    @Test fun unregisterHitsUnregisterPath() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        assertTrue(client("t").unregister("https://ntfy.whitewolf.tech/UPabc?up=1"))
        assertEquals("/api/push/unregister", server.takeRequest().path)
    }

    @Test fun noTokenReturnsFalseAndSendsNothing() {
        val ok = client(null).register("https://ntfy.whitewolf.tech/UPabc?up=1")
        assertFalse(ok)
        assertEquals(0, server.requestCount)
    }

    @Test fun non2xxReturnsFalse() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertFalse(client("t").register("https://ntfy.whitewolf.tech/UPabc?up=1"))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PushApiClientTest"`
Expected: FAIL — unresolved `PushApiClient`.

- [ ] **Step 3: Implement `PushApiClient.kt`**

```kotlin
package tech.whitewolf.app.push

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Authenticated calls to the backend push registry. The endpoint is the
 * UnifiedPush endpoint URL the distributor issued. Never throws; returns false
 * when there is no token or the request fails.
 */
class PushApiClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val token: () -> String?,
) {
    @Serializable private data class EndpointBody(val endpoint: String)
    private val json = Json
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun register(endpoint: String): Boolean = post("/api/push/register", endpoint)
    fun unregister(endpoint: String): Boolean = post("/api/push/unregister", endpoint)

    private fun post(path: String, endpoint: String): Boolean {
        val t = token() ?: return false
        val body = json.encodeToString(EndpointBody.serializer(), EndpointBody(endpoint))
            .toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $t")
            .post(body)
            .build()
        return try {
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PushApiClientTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/PushApiClient.kt app/src/test/java/tech/whitewolf/app/push/PushApiClientTest.kt
git commit   # subject: "feat(push): authenticated register/unregister API client"  + standard trailer
```

---

### Task 4: Notifications (channel + "New mail")

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/push/Notifications.kt`

**Interfaces:**
- Produces: `object Notifications { const val CHANNEL_ID = "mail"; fun ensureChannel(context: Context); fun showNewMail(context: Context) }`. `showNewMail` posts a notification titled "New mail" whose tap `PendingIntent` opens `MainActivity`. No-ops safely if the notification permission is not granted.

- [ ] **Step 1: Implement `Notifications.kt`**

```kotlin
package tech.whitewolf.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tech.whitewolf.app.MainActivity
import tech.whitewolf.app.R

object Notifications {
    const val CHANNEL_ID = "mail"
    private const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Mail", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "New mail notifications" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun showNewMail(context: Context) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return  // no permission → skip silently

        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("New mail")
            .setContentText("You have new mail in WWT")
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
```

> If `R.mipmap.ic_launcher` does not exist in this project, use the launcher icon resource that does (check `app/src/main/res/mipmap*`); the scaffold's default is `ic_launcher`. Add `androidx.core:core-ktx` is already a dependency (Task 1 scaffold), which provides `NotificationCompat`.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Instrumented verification of an actually-posted notification is deferred — no emulator.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/Notifications.kt
git commit   # subject: "feat(push): mail notification channel + New mail notification"  + standard trailer
```

---

### Task 5: PushReceiver (UnifiedPush MessagingReceiver) + manifest + permission

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `AppContainer.kt`

**Interfaces:**
- Consumes: `PushApiClient` (Task 3), `Notifications` (Task 4), the connector's `MessagingReceiver`.
- Produces: `class PushReceiver : MessagingReceiver()` wired in the manifest; `AppContainer.pushApiClient: PushApiClient`.

> **Connector-API note (verify against the pinned 2.4.0):** the v2 `MessagingReceiver` callbacks are `onNewEndpoint(context, endpoint: String, instance: String)`, `onMessage(context, message: ByteArray, instance: String)`, `onRegistrationFailed(context, instance: String)`, `onUnregistered(context, instance: String)`, and the manifest actions are the `org.unifiedpush.android.connector.*` set below. Before implementing, confirm these signatures/action names against `org.unifiedpush.android:connector:2.4.0` (the artifact's classes or its example app). If they differ, adjust the overrides/manifest to match — the behavior (register endpoint / show notification) is unchanged.

- [ ] **Step 1: Expose `pushApiClient` on `AppContainer`**

In `AppContainer.kt`, add (reusing the existing `http`, `baseUrl`, and `tokenStore`):
```kotlin
    val pushApiClient = tech.whitewolf.app.push.PushApiClient(http, baseUrl) { tokenStore.token() }
```
(If `http`/`tokenStore`/`baseUrl` are `private`, keep them private — `pushApiClient` is constructed inside the class so it can read them.)

- [ ] **Step 2: Implement `PushReceiver.kt`**

```kotlin
package tech.whitewolf.app.push

import android.content.Context
import org.unifiedpush.android.connector.MessagingReceiver
import tech.whitewolf.app.AppContainer

/**
 * Receives UnifiedPush events. Network calls run off the main thread via
 * goAsync()+Thread so the broadcast isn't blocked. A new endpoint is sent to the
 * backend; each wake-up posts a generic "New mail" notification.
 */
class PushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val app = context.applicationContext
        runOffMain { AppContainer(app).pushApiClient.register(endpoint) }
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        Notifications.showNewMail(context.applicationContext)
    }

    override fun onUnregistered(context: Context, instance: String) {
        // Endpoint already gone at the distributor; backend prunes on 404/410 too.
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        android.util.Log.w("PushReceiver", "UnifiedPush registration failed for $instance")
    }

    private fun runOffMain(block: () -> Unit) {
        Thread { runCatching(block) }.start()
    }
}
```

- [ ] **Step 3: Manifest — permission + receiver**

In `app/src/main/AndroidManifest.xml`, add the permission (next to `INTERNET`):
```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
and inside `<application>`:
```xml
        <receiver
            android:name=".push.PushReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="org.unifiedpush.android.connector.MESSAGE" />
                <action android:name="org.unifiedpush.android.connector.UNREGISTERED" />
                <action android:name="org.unifiedpush.android.connector.NEW_ENDPOINT" />
                <action android:name="org.unifiedpush.android.connector.REGISTRATION_FAILED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Instrumented receiver test deferred — no emulator.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/PushReceiver.kt app/src/main/AndroidManifest.xml app/src/main/java/tech/whitewolf/app/AppContainer.kt
git commit   # subject: "feat(push): UnifiedPush receiver + POST_NOTIFICATIONS + manifest"  + standard trailer
```

---

### Task 6: PushManager lifecycle + shell wiring + permission request

**Files:**
- Create: `app/src/main/java/tech/whitewolf/app/push/PushManager.kt`
- Modify: `app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt`, `app/src/main/java/tech/whitewolf/app/MainActivity.kt`

**Interfaces:**
- Consumes: the connector's `UnifiedPush` API, `AppContainer.pushApiClient`.
- Produces: `class PushManager(context)` with `fun hasDistributor(): Boolean`, `fun enable()` (pick a saved/only distributor and register), `fun disable()` (unregister at the distributor). `ShellScreen` calls `enable()` when logged-in and `disable()` + backend-unregister on sign-out, and shows a hint when `!hasDistributor()`.

> **Connector-API note (verify against 2.4.0):** v2 registration is `UnifiedPush.getDistributors(context): List<String>`, `UnifiedPush.saveDistributor(context, distributor)`, `UnifiedPush.registerApp(context)`, `UnifiedPush.unregisterApp(context)`. Confirm names against the pinned artifact and adjust if needed.

- [ ] **Step 1: Implement `PushManager.kt`**

```kotlin
package tech.whitewolf.app.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

/** Thin lifecycle wrapper over the UnifiedPush connector. */
class PushManager(private val context: Context) {
    fun hasDistributor(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /** Register with the saved distributor (or the only one available). Safe to call repeatedly. */
    fun enable() {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isEmpty()) return
        // Use an already-saved distributor if the user picked one; otherwise, if
        // there is exactly one, use it. (Multiple + none-saved → leave to the hint.)
        if (distributors.size == 1) UnifiedPush.saveDistributor(context, distributors.first())
        UnifiedPush.registerApp(context)
    }

    fun disable() {
        UnifiedPush.unregisterApp(context)
    }
}
```

- [ ] **Step 2: Request `POST_NOTIFICATIONS` in `MainActivity`**

In `MainActivity.kt`, register a permission launcher and request it on create (Android 13+). Add:
```kotlin
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
```
and in `onCreate` before `setContent`:
```kotlin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
```

- [ ] **Step 3: Wire enable/disable + hint into `ShellScreen`**

In `ShellScreen.kt`, at the top of the logged-in branch (where `subApp` is remembered), add a `PushManager` and enable push once:
```kotlin
    val context = androidx.compose.ui.platform.LocalContext.current
    val pushManager = remember { PushManager(context.applicationContext) }
    var showPushHint by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (pushManager.hasDistributor()) pushManager.enable() else showPushHint = true
    }
```
In the sign-out handlers (both the top-bar `TextButton` and the error-branch `Button`), before `container.auth.logout(); loggedIn = false`, add:
```kotlin
                        pushManager.disable()
                        container.auth.currentToken()?.let { /* endpoint already known to backend; it prunes on next dead POST */ }
```
And render the hint when `showPushHint` is true — a dismissible line above/below the WebView:
```kotlin
            if (showPushHint) {
                androidx.compose.material3.Text(
                    "For notifications, install the ntfy app via Obtainium and set its server to ntfy.whitewolf.tech.",
                    modifier = Modifier.padding(8.dp).testTag("pushHint"),
                )
            }
```

> Implementer note: keep the change minimal and compiling; the hint's exact placement inside the existing `Scaffold`/`Box` is your call, but it must not break the WebView layout. Do not add a JS bridge.

- [ ] **Step 4: Verify full unit suite + build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: unit suite PASS (incl. `PushApiClientTest`); assembleDebug SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/tech/whitewolf/app/push/PushManager.kt \
        app/src/main/java/tech/whitewolf/app/ui/ShellScreen.kt \
        app/src/main/java/tech/whitewolf/app/MainActivity.kt
git commit   # subject: "feat(push): enable/disable lifecycle + permission + no-distributor hint"  + standard trailer
```

---

### Task 7: Onboarding runbook

**Files:**
- Create: `docs/PUSH.md`

- [ ] **Step 1: Write `docs/PUSH.md`**

````markdown
# Push notifications (UnifiedPush) — setup

WWT uses UnifiedPush with a self-hosted **ntfy** server (`ntfy.whitewolf.tech`) —
no Google/Firebase. You install one small "distributor" app once; it wakes the WWT
app when new mail arrives.

## One-time device setup
1. Install the **ntfy app via Obtainium** — add source
   `https://github.com/binwiederhier/ntfy` (GitHub) and install.
2. Open the ntfy app → **Settings → Default server** → set to
   `https://ntfy.whitewolf.tech`.
3. Install/open the **WWT app** (Obtainium) and **allow notifications** when asked.

## Verify
Send yourself an email → a **"New mail"** notification should appear within a few
seconds; tapping it opens the mailbox.

## Notes
- The push carries no email content — only a "new mail" signal; the app shows the
  actual mail when opened.
- If you didn't install a distributor, the app shows a hint and works fine without
  notifications until you do.
- No Google Play Services or foreground service is used.
````

- [ ] **Step 2: Commit**

```bash
git add docs/PUSH.md
git commit   # subject: "docs(push): UnifiedPush onboarding runbook"  + standard trailer
```

---

## Self-Review

**Spec coverage (against `2026-07-02-push-notifications-design.md`):**
- §2/§3 infra (ntfy + `PUSH_ENDPOINT_HOSTS` + redeploy) → Task 1. ✓
- §2 register/unregister with Bearer → Task 3. ✓ · receive wake-up → notification → Tasks 4,5. ✓ · unregister on sign-out → Task 6. ✓ · `POST_NOTIFICATIONS` → Tasks 5 (declare) + 6 (request). ✓
- §3 generic "New mail", ntfy self-host, standard distributor via Obtainium, data-light → Tasks 4 (generic), 1 (ntfy), 7 (Obtainium), backend unchanged. ✓
- §4 components (`PushApiClient`, `PushReceiver`, `PushManager`, `Notifications`, no-distributor hint, manifest) → Tasks 3–6. ✓
- §6 security (host-pin, authed register) → reused PR #24 + Task 1 Step 6 verify. ✓
- §7 error handling (no distributor → hint; register failure self-heals on restart; token expiry → false; permission denied → skip) → Tasks 3 (false paths), 6 (hint), 4 (permission skip). ✓
- §8 permissions (INTERNET + POST_NOTIFICATIONS, no foreground service) → Task 5. ✓
- §9 testing (JVM PushApiClient; instrumented deferred) → Task 3 + notes on 4/5. ✓
- §10 onboarding via Obtainium → Task 7. ✓

**Placeholder scan:** No TBD/TODO. The two "Connector-API note (verify against 2.4.0)" blocks are deliberate: they pin a version and give the exact v2 API to use, instructing verification of the one version-sensitive integration point — concrete guidance, not deferred work. The `R.mipmap.ic_launcher` note names the concrete fallback to check.

**Type/identifier consistency:** `PushApiClient(http, baseUrl, token: () -> String?)` with `register/unregister(endpoint): Boolean` is identical across Tasks 3, 5 (AppContainer), 6. `Notifications.showNewMail(context)`/`CHANNEL_ID` consistent across 4, 5. `PushManager(context)` with `hasDistributor/enable/disable` consistent across 6. `AppContainer.pushApiClient` used by PushReceiver (5) matches its definition (5 Step 1). `POST_NOTIFICATIONS` declared (5) and requested (6). ✓

**Execution note:** Task 1 is controller/operator-run on the mail host (this environment is `project-mail`), with the DNS record a user action. Tasks 2–7 are code (subagent-driven); the JVM gate is `PushApiClientTest`, and the connector-dependent receiver/manager have compile gates with instrumented tests deferred (no emulator), consistent with the rest of the app.
