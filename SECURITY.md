# Security policy

## Reporting a vulnerability

Please report security issues **privately**, not as a public issue.

Use GitHub's private vulnerability reporting: go to the repository's
**Security** tab → **Report a vulnerability**. That opens a private thread
visible only to the maintainer, and carries a fix-and-advisory workflow.

> **Note:** GitHub only offers private vulnerability reporting on public
> repositories, and it must be switched on once at
> *Settings → Security → Private vulnerability reporting*. While this repository
> is private, treat this policy as a statement of intent — the channel becomes
> live when the repository does.

## Scope

This repository holds the **Android shell** (`tech.whitewolf.app`) — the native
app, its auth handling, and its WebView hosting.

Findings that touch the hosted services it talks to (`mail.whitewolf.tech`,
`auth.whitewolf.tech`, `ntfy.whitewolf.tech`) are also welcome through the same
channel. Those are deployed separately, but the boundary isn't visible from
outside, so please don't hold a report back on that basis.

Things worth knowing before reporting:

- The OIDC client is **public by design** (`maileroo-mobile`) and ships no client
  secret. The endpoint hostnames, client ID and redirect URI are compiled into
  the published APK; their disclosure is expected, not a finding on its own.
- The app pins no certificates and relies on standard public CA trust.

## Supported versions

Only the **latest release** receives fixes. This is a single-maintainer project
and installs auto-update through Obtainium, so there are no maintained branches
for older versions.

## What to expect

There's no bug bounty. Reports are handled on a best-effort basis by one person;
you'll get an acknowledgement and, where a fix is warranted, a release and an
advisory crediting you unless you'd rather stay anonymous.
