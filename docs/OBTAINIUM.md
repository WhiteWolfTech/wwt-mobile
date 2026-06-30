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
