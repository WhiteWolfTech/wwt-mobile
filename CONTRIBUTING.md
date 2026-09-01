# Contributing

Thanks for taking an interest. This is a small, single-maintainer project, so
the process here is light — but there is one thing worth knowing before you
start, because it shapes what you can usefully work on.

## This app talks to one private deployment

WWT Mobile is a **shell for a specific installation**. Out of the box it points
at `mail.whitewolf.tech`, `auth.whitewolf.tech` and `ntfy.whitewolf.tech`, and
there are no public accounts on any of them.

**What you can do with no setup at all:**

- Build the app (`./gradlew :app:assembleDebug`) — the debug build signs itself
  and needs no secrets.
- Run the unit tests (`./gradlew :app:testDebugUnitTest`) — ~99 tests, entirely
  hermetic, no network.
- Run the instrumented tests (`./gradlew :app:connectedDebugAndroidTest`) on any
  emulator — also hermetic; they cover the Compose UI and the encrypted store.

**What you can't do without your own backend:** sign in. That means the login
flow, WebView session seeding, native SSO and push can't be exercised
end-to-end. If you have your own mail backend and OIDC provider, point a build
at them — see *Pointing a build at another backend* in the [README](README.md).
Register `tech.whitewolf.app:/oauth2redirect` with your provider; that redirect
URI is not overridable, as it is tied to `applicationId`.

**So:** if your change touches auth or the WebView, say in the PR what you were
and weren't able to verify. An honest "couldn't test SSO end-to-end" is far more
useful than silence — the maintainer can run it against the real deployment.

## Before you start

For anything substantial, please open an issue first so we can agree the
approach — it saves you building something that turns out not to fit. Small
fixes (a bug, a typo, a clear one-liner) can go straight to a pull request.

## Tests

Changes are expected to come with tests. The pattern throughout this codebase is
to put logic **behind a seam** so it can be tested on the JVM without an Android
device — see `SecureStore`, `SsoLogin` and `WebCookies` for examples. Anything
that genuinely needs a device (the Keystore, Compose UI) goes in
`app/src/androidTest`.

Prefer a JVM unit test where the logic allows it. They run in seconds and are
what CI gates on.

## Commits and branches

- **Conventional Commits** for the subject: `fix(auth): ...`, `feat(build): ...`,
  `docs: ...`. Imperative mood, no trailing full stop.
- **Explain *why* in the body**, not just what changed — the diff already shows
  what. Recent history has examples of the level of detail that's useful.
- **Branch names:** `feat/`, `fix/` or `docs/` plus a short description. You'll
  see `peter/wwt-NN-*` branches in the history; those map to a private issue
  tracker you won't have access to, so use the plain prefixes.
- The `Co-Authored-By: Claude` and `Claude-Session:` trailers on some commits are
  an artefact of the maintainer's tooling. Please don't add them to yours.

## Pull requests

CI runs the unit tests and a debug build on every PR, and needs to be green.
Beyond that, describe what you changed, why, and how you verified it.

Design notes and implementation plans for past features live in
`docs/superpowers/`. They aren't a process you need to follow, but they're often
the quickest way to understand why something is built the way it is.

## Security

Please **don't** open a public issue for a security problem. See
[SECURITY.md](SECURITY.md) for how to report one privately.

## Licensing

By submitting a contribution you agree it is provided under the
[Apache License 2.0](LICENSE), the licence this project is under — see section 5
of that licence. There is no CLA and nothing to sign.
