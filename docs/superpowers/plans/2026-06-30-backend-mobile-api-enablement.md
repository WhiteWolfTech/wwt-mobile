# Backend Mobile-API Enablement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the two backend capabilities the WWT mobile app needs — bearer-token auth and UnifiedPush wake-up notifications — to the existing `email-client-maileroo` Go server, without disrupting the web SPA.

**Architecture:** Reuse the existing signed `userID.exp.sig` token: keep issuing it as a cookie *and* return it in the login body, and teach the auth middleware to also accept it via `Authorization: Bearer`. Add a `push_endpoints` table, two authenticated registration endpoints (with fail-closed SSRF host pinning on the client-supplied endpoint URL), and a `push.Notifier` that POSTs a data-light wake-up to a user's registered endpoints when new inbound mail is durably stored.

**Tech Stack:** Go 1.25+, `net/http` (stdlib mux with method+path patterns), pure-Go `modernc.org/sqlite` (no CGO), `golang.org/x/crypto/bcrypt`, `httptest` for tests.

**Target repo:** This plan is implemented in the **`email-client-maileroo`** repository (Go module `maileroo-mail`), not in `mobile-app`. All paths below are relative to that repo's root. Have it checked out as a working tree before starting.

## Global Constraints

- Go 1.25+; no CGO; do not add heavy dependencies — fan-out uses stdlib `net/http`. (verbatim from spec §2 / repo CLAUDE.md)
- Token format is unchanged: the existing `auth.Sign`/`auth.Parse` `userID.exp.sig` scheme is reused as-is (spec §2, §5).
- Pushes are **data-light**: the wake-up body carries no email content — only `{"type":"new_mail"}`. The app fetches mail over the authenticated API (spec §6).
- Any client-supplied URL the server later requests MUST be host-pinned fail-closed, mirroring `internal/inbound/handler.go`'s `validationHostAllowed` (spec §6; repo's existing SSRF posture).
- Run tests with `go test ./internal/...` (no Node needed for backend-only work). Full build is `make build`.
- Company name in any user-facing string or comment is **White Wolf Technology** or **WWT** — never "White Wolf" alone.
- Every commit message ends with these two trailer lines:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg
  ```

---

### Task 1: Bearer-token auth (accept `Authorization: Bearer`; return token on login)

**Files:**
- Modify: `internal/auth/session.go` — add `TokenFromRequest`; use it in `Middleware`
- Test: `internal/auth/session_test.go`
- Modify: `internal/api/server.go` — add `issueToken`; refactor `setSession` to use it
- Modify: `internal/api/handlers.go` — `handleLogin` returns token in body
- Test: `internal/api/handlers_test.go`

**Interfaces:**
- Consumes: `auth.Sign(secret []byte, userID, expiresUnix int64) string`, `auth.Parse(secret []byte, value string, nowUnix int64) (int64, bool)`, `auth.CookieName` (all exist).
- Produces:
  - `auth.TokenFromRequest(r *http.Request) (string, bool)` — returns the bearer token if the `Authorization` header is `Bearer <t>`, else the `session` cookie value, else `("", false)`.
  - `(*api.Server).issueToken(userID int64) (token string, expUnix int64)` — signs a 7-day token.
  - Login response JSON gains fields: `{"ok":true,"token":"<userID.exp.sig>","expires":<unix>}`.

- [ ] **Step 1: Write the failing test for `TokenFromRequest`**

Add to `internal/auth/session_test.go`:

```go
func TestTokenFromRequest(t *testing.T) {
	t.Run("bearer header", func(t *testing.T) {
		r := httptest.NewRequest("GET", "/api/me", nil)
		r.Header.Set("Authorization", "Bearer abc.def.ghi")
		got, ok := TokenFromRequest(r)
		if !ok || got != "abc.def.ghi" {
			t.Fatalf("got (%q,%v), want (abc.def.ghi,true)", got, ok)
		}
	})
	t.Run("cookie fallback", func(t *testing.T) {
		r := httptest.NewRequest("GET", "/api/me", nil)
		r.AddCookie(&http.Cookie{Name: CookieName, Value: "ck.tok.en"})
		got, ok := TokenFromRequest(r)
		if !ok || got != "ck.tok.en" {
			t.Fatalf("got (%q,%v), want (ck.tok.en,true)", got, ok)
		}
	})
	t.Run("bearer beats cookie", func(t *testing.T) {
		r := httptest.NewRequest("GET", "/api/me", nil)
		r.Header.Set("Authorization", "Bearer hdr.tok.en")
		r.AddCookie(&http.Cookie{Name: CookieName, Value: "ck.tok.en"})
		got, _ := TokenFromRequest(r)
		if got != "hdr.tok.en" {
			t.Fatalf("got %q, want header token to win", got)
		}
	})
	t.Run("none", func(t *testing.T) {
		r := httptest.NewRequest("GET", "/api/me", nil)
		if _, ok := TokenFromRequest(r); ok {
			t.Fatal("expected ok=false with no auth")
		}
	})
}
```

Ensure the test file imports `net/http`, `net/http/httptest`, `testing`.

- [ ] **Step 2: Run it to confirm it fails**

Run: `go test ./internal/auth/ -run TestTokenFromRequest -v`
Expected: FAIL — `undefined: TokenFromRequest`.

- [ ] **Step 3: Implement `TokenFromRequest` and use it in `Middleware`**

In `internal/auth/session.go`, add after the `Parse` function:

```go
// TokenFromRequest extracts the session token from an Authorization: Bearer
// header if present, otherwise from the session cookie. The bearer header takes
// precedence so an explicit API caller is never shadowed by a stale cookie.
func TokenFromRequest(r *http.Request) (string, bool) {
	if h := r.Header.Get("Authorization"); h != "" {
		const p = "Bearer "
		if len(h) > len(p) && strings.EqualFold(h[:len(p)], p) {
			return strings.TrimSpace(h[len(p):]), true
		}
	}
	if c, err := r.Cookie(CookieName); err == nil {
		return c.Value, true
	}
	return "", false
}
```

Then change `Middleware` to use it. Replace the cookie-reading block:

```go
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(CookieName)
		if err != nil {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		id, ok := Parse(secret, c.Value, time.Now().Unix())
		if !ok {
```

with:

```go
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok, ok := TokenFromRequest(r)
		if !ok {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		id, ok := Parse(secret, tok, time.Now().Unix())
		if !ok {
```

(The `time` import is already present; `strings` is already imported in this file.)

- [ ] **Step 4: Run the auth tests to confirm they pass**

Run: `go test ./internal/auth/ -v`
Expected: PASS (including the existing `session_test.go` cases and the new ones).

- [ ] **Step 5: Write the failing API test for token-in-body + bearer access**

Add to `internal/api/handlers_test.go`:

```go
func TestLoginReturnsTokenAndBearerWorks(t *testing.T) {
	srv := newTestServer(t)
	h := srv.Routes()
	hash, _ := hashPassword("pw1234")
	if _, err := srv.Store.CreateUser("bearer@x.tech", "B", hash, "user", 1); err != nil {
		t.Fatalf("create user: %v", err)
	}

	rec := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/api/login",
		strings.NewReader(`{"email":"bearer@x.tech","password":"pw1234"}`))
	h.ServeHTTP(rec, req)
	if rec.Code != 200 {
		t.Fatalf("login status = %d, body=%s", rec.Code, rec.Body.String())
	}
	var resp struct {
		OK      bool   `json:"ok"`
		Token   string `json:"token"`
		Expires int64  `json:"expires"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if !resp.OK || resp.Token == "" || resp.Expires == 0 {
		t.Fatalf("unexpected login body: %+v", resp)
	}

	// The returned token authenticates a protected route via the bearer header,
	// with NO cookie attached.
	rec2 := httptest.NewRecorder()
	req2 := httptest.NewRequest("GET", "/api/me", nil)
	req2.Header.Set("Authorization", "Bearer "+resp.Token)
	h.ServeHTTP(rec2, req2)
	if rec2.Code != 200 {
		t.Fatalf("bearer /api/me status = %d, body=%s", rec2.Code, rec2.Body.String())
	}
}
```

- [ ] **Step 6: Run it to confirm it fails**

Run: `go test ./internal/api/ -run TestLoginReturnsTokenAndBearerWorks -v`
Expected: FAIL — `resp.Token` is empty (login currently returns only `{"ok":true}`).

- [ ] **Step 7: Add `issueToken`, refactor `setSession`, update `handleLogin`**

In `internal/api/server.go`, replace `setSession`:

```go
// issueToken signs a 7-day session token for a user and returns it with its
// expiry (unix seconds). It is the single source of truth for token lifetime,
// used by both the session cookie and the JSON login response.
func (s *Server) issueToken(userID int64) (string, int64) {
	exp := time.Now().Add(7 * 24 * time.Hour).Unix()
	return auth.Sign(s.Cfg.SessionSecret, userID, exp), exp
}

// setSession writes a signed session cookie to the response.
func (s *Server) setSession(w http.ResponseWriter, userID int64) {
	tok, exp := s.issueToken(userID)
	http.SetCookie(w, &http.Cookie{
		Name:     auth.CookieName,
		Value:    tok,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   strings.HasPrefix(s.Cfg.PublicBaseURL, "https://"),
		Expires:  time.Unix(exp, 0),
	})
}
```

In `internal/api/handlers.go`, change the tail of `handleLogin`:

```go
	s.setSession(w, u.ID)
	writeJSON(w, map[string]bool{"ok": true})
```

to:

```go
	s.setSession(w, u.ID)
	tok, exp := s.issueToken(u.ID)
	writeJSON(w, map[string]any{"ok": true, "token": tok, "expires": exp})
```

- [ ] **Step 8: Run API tests to confirm pass**

Run: `go test ./internal/api/ -v`
Expected: PASS (new test and all existing handler/admin/setup tests).

- [ ] **Step 9: Commit**

```bash
git add internal/auth/session.go internal/auth/session_test.go internal/api/server.go internal/api/handlers.go internal/api/handlers_test.go
git commit -m "feat(auth): accept Authorization: Bearer and return token on login

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg"
```

---

### Task 2: `push_endpoints` table + store methods

**Files:**
- Modify: `internal/store/schema.sql`
- Create: `internal/store/push.go`
- Test: `internal/store/push_test.go`

**Interfaces:**
- Consumes: `store.Open(path string) (*Store, error)` (applies `schema.sql` on every open).
- Produces (methods on `*store.Store`):
  - `AddPushEndpoint(userID int64, endpoint string, at int64) error` — idempotent upsert on `(user_id, endpoint)`.
  - `DeletePushEndpoint(userID int64, endpoint string) error`
  - `ListPushEndpoints(userID int64) ([]string, error)` — returns `[]string{}` (never nil) when none.

- [ ] **Step 1: Add the table to the schema**

Append to `internal/store/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS push_endpoints (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id),
    endpoint    TEXT NOT NULL,
    created_at  INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_push_user_endpoint ON push_endpoints(user_id, endpoint);
```

(No migration function is needed: `schema.sql` runs with `IF NOT EXISTS` on every `store.Open`, matching how the existing tables are created in `internal/store/store.go`.)

- [ ] **Step 2: Write the failing store test**

Create `internal/store/push_test.go`:

```go
package store

import "testing"

func newPushTestStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(":memory:")
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { _ = s.Close() })
	if _, err := s.CreateUser("u@x.tech", "U", "h", "user", 1); err != nil {
		t.Fatalf("create user: %v", err)
	}
	return s
}

func TestPushEndpointsRoundTrip(t *testing.T) {
	s := newPushTestStore(t)

	if got, err := s.ListPushEndpoints(1); err != nil || len(got) != 0 {
		t.Fatalf("empty list: got %v err %v; want [] nil", got, err)
	}

	if err := s.AddPushEndpoint(1, "https://ntfy.whitewolf.tech/topicA", 100); err != nil {
		t.Fatalf("add: %v", err)
	}
	// Re-adding the same endpoint is idempotent (no duplicate, no error).
	if err := s.AddPushEndpoint(1, "https://ntfy.whitewolf.tech/topicA", 200); err != nil {
		t.Fatalf("re-add: %v", err)
	}
	if err := s.AddPushEndpoint(1, "https://ntfy.whitewolf.tech/topicB", 100); err != nil {
		t.Fatalf("add B: %v", err)
	}

	got, err := s.ListPushEndpoints(1)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d endpoints, want 2: %v", len(got), got)
	}

	if err := s.DeletePushEndpoint(1, "https://ntfy.whitewolf.tech/topicA"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	got, _ = s.ListPushEndpoints(1)
	if len(got) != 1 || got[0] != "https://ntfy.whitewolf.tech/topicB" {
		t.Fatalf("after delete got %v, want only topicB", got)
	}
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `go test ./internal/store/ -run TestPushEndpointsRoundTrip -v`
Expected: FAIL — `undefined: (*Store).ListPushEndpoints` etc.

- [ ] **Step 4: Implement the store methods**

Create `internal/store/push.go`:

```go
package store

// AddPushEndpoint registers a UnifiedPush endpoint URL for a user. It is
// idempotent: re-registering the same (user, endpoint) refreshes created_at
// rather than creating a duplicate.
func (s *Store) AddPushEndpoint(userID int64, endpoint string, at int64) error {
	_, err := s.DB.Exec(
		`INSERT INTO push_endpoints (user_id, endpoint, created_at) VALUES (?,?,?)
		 ON CONFLICT(user_id, endpoint) DO UPDATE SET created_at = excluded.created_at`,
		userID, endpoint, at)
	return err
}

// DeletePushEndpoint removes a single endpoint for a user. Deleting a
// non-existent endpoint is not an error.
func (s *Store) DeletePushEndpoint(userID int64, endpoint string) error {
	_, err := s.DB.Exec(
		`DELETE FROM push_endpoints WHERE user_id = ? AND endpoint = ?`, userID, endpoint)
	return err
}

// ListPushEndpoints returns all registered endpoint URLs for a user, oldest
// first. It returns a non-nil empty slice when the user has none.
func (s *Store) ListPushEndpoints(userID int64) ([]string, error) {
	rows, err := s.DB.Query(
		`SELECT endpoint FROM push_endpoints WHERE user_id = ? ORDER BY id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var e string
		if err := rows.Scan(&e); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}
```

- [ ] **Step 5: Run it to confirm it passes**

Run: `go test ./internal/store/ -run TestPushEndpointsRoundTrip -v`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add internal/store/schema.sql internal/store/push.go internal/store/push_test.go
git commit -m "feat(store): add push_endpoints table and CRUD methods

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg"
```

---

### Task 3: Endpoint host pinning + push registration API

**Files:**
- Create: `internal/push/host.go`
- Test: `internal/push/host_test.go`
- Modify: `internal/config/config.go` — add `PushEndpointHosts []string`
- Test: `internal/config/config_test.go` (add a case; create the file if absent)
- Create: `internal/api/push.go`
- Modify: `internal/api/server.go` — register the two routes on the authed `api` mux
- Test: `internal/api/push_test.go`

**Interfaces:**
- Consumes: `auth.Middleware` (already wraps `/api/`), `(*Server).currentUser(r) *store.User`, `(*Server).Store`, `(*Server).Cfg`, the Task 2 store methods.
- Produces:
  - `push.HostAllowed(rawURL string, allowed []string) bool` — true only when `rawURL` is HTTPS and its host equals or is a subdomain of an entry in `allowed`; false if `allowed` is empty (fail-closed).
  - `config.Config.PushEndpointHosts []string` — lowercased registrable domains from `PUSH_ENDPOINT_HOSTS` (comma-separated; empty when unset).
  - Routes `POST /api/push/register` and `POST /api/push/unregister`, body `{"endpoint":"<url>"}`, returning `{"ok":true}`.

- [ ] **Step 1: Write the failing host-pinning test**

Create `internal/push/host_test.go`:

```go
package push

import "testing"

func TestHostAllowed(t *testing.T) {
	allowed := []string{"ntfy.whitewolf.tech"}
	cases := []struct {
		url  string
		want bool
	}{
		{"https://ntfy.whitewolf.tech/abc?up=1", true},
		{"https://sub.ntfy.whitewolf.tech/abc", true},
		{"http://ntfy.whitewolf.tech/abc", false},  // not https
		{"https://evil.example.com/abc", false},    // wrong host
		{"https://ntfy.whitewolf.tech.evil.com/x", false}, // suffix trick
		{"::not a url::", false},
	}
	for _, c := range cases {
		if got := HostAllowed(c.url, allowed); got != c.want {
			t.Errorf("HostAllowed(%q) = %v, want %v", c.url, got, c.want)
		}
	}
	if HostAllowed("https://ntfy.whitewolf.tech/x", nil) {
		t.Error("empty allow-list must reject (fail-closed)")
	}
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `go test ./internal/push/ -run TestHostAllowed -v`
Expected: FAIL — `undefined: HostAllowed` (package may not exist yet).

- [ ] **Step 3: Implement `HostAllowed`**

Create `internal/push/host.go`:

```go
// Package push registers UnifiedPush endpoints and delivers data-light wake-up
// notifications to them. Endpoint URLs are client-supplied, so they are pinned
// fail-closed to an operator-configured set of hosts before the server ever
// requests them — mirroring the inbound webhook's validation-URL pinning.
package push

import (
	"net/url"
	"strings"
)

// HostAllowed reports whether rawURL is an HTTPS URL whose host equals, or is a
// subdomain of, one of the allowed registrable domains. An empty allow-list
// rejects everything (fail-closed).
func HostAllowed(rawURL string, allowed []string) bool {
	if len(allowed) == 0 {
		return false
	}
	u, err := url.Parse(rawURL)
	if err != nil || u.Scheme != "https" {
		return false
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	if host == "" {
		return false
	}
	for _, a := range allowed {
		a = strings.ToLower(strings.TrimSpace(a))
		if a == "" {
			continue
		}
		if host == a || strings.HasSuffix(host, "."+a) {
			return true
		}
	}
	return false
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `go test ./internal/push/ -run TestHostAllowed -v`
Expected: PASS.

- [ ] **Step 5: Write the failing config test**

Add to `internal/config/config_test.go` (create the file with this package clause if it does not exist):

```go
func TestLoadParsesPushEndpointHosts(t *testing.T) {
	env := map[string]string{
		"MAIL_DOMAIN":         "whitewolf.tech",
		"MAILEROO_API_KEY":    "k",
		"SESSION_SECRET":      "s",
		"PUSH_ENDPOINT_HOSTS": " ntfy.whitewolf.tech , Push.WhiteWolf.Tech ",
	}
	c, err := Load(func(k string) string { return env[k] })
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	want := []string{"ntfy.whitewolf.tech", "push.whitewolf.tech"}
	if len(c.PushEndpointHosts) != len(want) {
		t.Fatalf("got %v, want %v", c.PushEndpointHosts, want)
	}
	for i := range want {
		if c.PushEndpointHosts[i] != want[i] {
			t.Fatalf("got %v, want %v", c.PushEndpointHosts, want)
		}
	}
}
```

Ensure the file imports `testing`.

- [ ] **Step 6: Run it to confirm it fails**

Run: `go test ./internal/config/ -run TestLoadParsesPushEndpointHosts -v`
Expected: FAIL — `c.PushEndpointHosts undefined`.

- [ ] **Step 7: Add the config field and parsing**

In `internal/config/config.go`, add to the `Config` struct (after `ListenAddr`):

```go
	// PushEndpointHosts is the allow-list of registrable domains that a
	// client-supplied UnifiedPush endpoint URL may belong to. Empty disables
	// push registration (fail-closed). From PUSH_ENDPOINT_HOSTS (comma list).
	PushEndpointHosts []string
```

In `Load`, after the `c := &Config{...}` literal is assigned (before the `missing` checks), add:

```go
	for _, h := range strings.Split(getenv("PUSH_ENDPOINT_HOSTS"), ",") {
		if h = strings.ToLower(strings.TrimSpace(h)); h != "" {
			c.PushEndpointHosts = append(c.PushEndpointHosts, h)
		}
	}
```

(`strings` is already imported.)

- [ ] **Step 8: Run it to confirm it passes**

Run: `go test ./internal/config/ -v`
Expected: PASS.

- [ ] **Step 9: Write the failing registration-API test**

Create `internal/api/push_test.go`:

```go
package api

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPushRegisterAndUnregister(t *testing.T) {
	srv := newTestServer(t)
	srv.Cfg.PushEndpointHosts = []string{"ntfy.whitewolf.tech"}
	h := srv.Routes()
	cookie, uid := login(t, srv, h)

	post := func(path, body string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("POST", path, strings.NewReader(body))
		req.AddCookie(cookie)
		h.ServeHTTP(rec, req)
		return rec
	}

	// Reject an endpoint on a non-allowed host.
	if rec := post("/api/push/register", `{"endpoint":"https://evil.example.com/x"}`); rec.Code != http.StatusBadRequest {
		t.Fatalf("evil host: status = %d, want 400", rec.Code)
	}

	// Accept an allowed endpoint and persist it.
	if rec := post("/api/push/register", `{"endpoint":"https://ntfy.whitewolf.tech/topicA"}`); rec.Code != 200 {
		t.Fatalf("register: status = %d, body=%s", rec.Code, rec.Body.String())
	}
	if eps, _ := srv.Store.ListPushEndpoints(uid); len(eps) != 1 {
		t.Fatalf("after register, stored endpoints = %v, want 1", eps)
	}

	// Unregister removes it.
	if rec := post("/api/push/unregister", `{"endpoint":"https://ntfy.whitewolf.tech/topicA"}`); rec.Code != 200 {
		t.Fatalf("unregister: status = %d", rec.Code)
	}
	if eps, _ := srv.Store.ListPushEndpoints(uid); len(eps) != 0 {
		t.Fatalf("after unregister, stored endpoints = %v, want 0", eps)
	}
}

func TestPushRegisterRequiresAuth(t *testing.T) {
	srv := newTestServer(t)
	srv.Cfg.PushEndpointHosts = []string{"ntfy.whitewolf.tech"}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/api/push/register",
		strings.NewReader(`{"endpoint":"https://ntfy.whitewolf.tech/x"}`))
	srv.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}
```

- [ ] **Step 10: Run it to confirm it fails**

Run: `go test ./internal/api/ -run TestPushRegister -v`
Expected: FAIL — 404 (routes not registered) rather than the expected codes.

- [ ] **Step 11: Implement the handlers and register routes**

Create `internal/api/push.go`:

```go
package api

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"maileroo-mail/internal/push"
)

// handleRegisterPush stores a UnifiedPush endpoint URL for the current user.
// The URL is host-pinned (fail-closed) against Cfg.PushEndpointHosts before it
// is ever stored or contacted, preventing SSRF via a client-chosen URL.
func (s *Server) handleRegisterPush(w http.ResponseWriter, r *http.Request) {
	u := s.currentUser(r)
	if u == nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var body struct {
		Endpoint string `json:"endpoint"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	endpoint := strings.TrimSpace(body.Endpoint)
	if !push.HostAllowed(endpoint, s.Cfg.PushEndpointHosts) {
		http.Error(w, "endpoint host not allowed", http.StatusBadRequest)
		return
	}
	if err := s.Store.AddPushEndpoint(u.ID, endpoint, time.Now().Unix()); err != nil {
		http.Error(w, "db error", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}

// handleUnregisterPush removes a previously registered endpoint for the user.
func (s *Server) handleUnregisterPush(w http.ResponseWriter, r *http.Request) {
	u := s.currentUser(r)
	if u == nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var body struct {
		Endpoint string `json:"endpoint"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if err := s.Store.DeletePushEndpoint(u.ID, strings.TrimSpace(body.Endpoint)); err != nil {
		http.Error(w, "db error", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}
```

In `internal/api/server.go`, in `Routes()`, add these two lines with the other `api.HandleFunc` registrations (e.g. just after the `PATCH /api/me` line):

```go
	api.HandleFunc("POST /api/push/register", s.handleRegisterPush)
	api.HandleFunc("POST /api/push/unregister", s.handleUnregisterPush)
```

- [ ] **Step 12: Run it to confirm it passes**

Run: `go test ./internal/api/ -run TestPushRegister -v`
Expected: PASS (both tests).

- [ ] **Step 13: Commit**

```bash
git add internal/push/host.go internal/push/host_test.go internal/config/config.go internal/config/config_test.go internal/api/push.go internal/api/push_test.go internal/api/server.go
git commit -m "feat(push): registration endpoints with fail-closed host pinning

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg"
```

---

### Task 4: `push.Notifier` HTTP fan-out

**Files:**
- Create: `internal/push/notifier.go`
- Test: `internal/push/notifier_test.go`

**Interfaces:**
- Consumes: an `EndpointStore` (satisfied by `*store.Store` via Task 2's methods); `*http.Client`.
- Produces:
  - `push.Notifier` interface: `NotifyNewMail(ctx context.Context, userID int64)`.
  - `push.EndpointStore` interface: `ListPushEndpoints(userID int64) ([]string, error)` and `DeletePushEndpoint(userID int64, endpoint string) error`.
  - `push.HTTPNotifier{Store EndpointStore, HTTP *http.Client}` implementing `Notifier`. POSTs `{"type":"new_mail"}` to each endpoint; prunes an endpoint on 404/410; logs and continues on any other error. Never blocks the caller on failures.

- [ ] **Step 1: Write the failing notifier test**

Create `internal/push/notifier_test.go`:

```go
package push

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
)

type stubStore struct {
	mu       sync.Mutex
	eps      map[int64][]string
	deleted  []string
}

func (s *stubStore) ListPushEndpoints(userID int64) ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string{}, s.eps[userID]...), nil
}
func (s *stubStore) DeletePushEndpoint(userID int64, endpoint string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deleted = append(s.deleted, endpoint)
	return nil
}

func TestNotifyNewMailPostsWakeUp(t *testing.T) {
	var gotBody string
	var gotCT string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		gotCT = r.Header.Get("Content-Type")
		w.WriteHeader(200)
	}))
	defer srv.Close()

	st := &stubStore{eps: map[int64][]string{7: {srv.URL}}}
	n := &HTTPNotifier{Store: st, HTTP: srv.Client()}
	n.NotifyNewMail(context.Background(), 7)

	if gotBody != `{"type":"new_mail"}` {
		t.Fatalf("body = %q, want data-light wake-up", gotBody)
	}
	if gotCT != "application/json" {
		t.Fatalf("content-type = %q", gotCT)
	}
}

func TestNotifyNewMailPrunesGoneEndpoint(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusGone) // 410
	}))
	defer srv.Close()

	st := &stubStore{eps: map[int64][]string{7: {srv.URL}}}
	n := &HTTPNotifier{Store: st, HTTP: srv.Client()}
	n.NotifyNewMail(context.Background(), 7)

	if len(st.deleted) != 1 || st.deleted[0] != srv.URL {
		t.Fatalf("expected the 410 endpoint to be pruned, deleted=%v", st.deleted)
	}
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `go test ./internal/push/ -run TestNotifyNewMail -v`
Expected: FAIL — `undefined: HTTPNotifier`.

- [ ] **Step 3: Implement the notifier**

Create `internal/push/notifier.go`:

```go
package push

import (
	"bytes"
	"context"
	"log"
	"net/http"
	"time"
)

// wakeBody is the entire push payload: a data-light signal with no email
// content. The app fetches new mail over the authenticated API when woken.
var wakeBody = []byte(`{"type":"new_mail"}`)

// Notifier wakes a user's registered devices after new mail is stored.
type Notifier interface {
	NotifyNewMail(ctx context.Context, userID int64)
}

// EndpointStore is the subset of the data store the notifier needs.
type EndpointStore interface {
	ListPushEndpoints(userID int64) ([]string, error)
	DeletePushEndpoint(userID int64, endpoint string) error
}

// HTTPNotifier delivers wake-ups by POSTing to each of a user's UnifiedPush
// endpoint URLs. Endpoints are pinned at registration time (see HostAllowed),
// so the URLs here are already trusted hosts.
type HTTPNotifier struct {
	Store EndpointStore
	HTTP  *http.Client
}

// NotifyNewMail POSTs a wake-up to every endpoint registered for userID. It
// never returns an error: delivery is best-effort, failures are logged, and an
// endpoint that reports 404/410 (gone) is pruned. A short per-request timeout
// bounds the work so a slow push server cannot stall the inbound webhook.
func (n *HTTPNotifier) NotifyNewMail(ctx context.Context, userID int64) {
	eps, err := n.Store.ListPushEndpoints(userID)
	if err != nil {
		log.Printf("[push] list endpoints for user %d: %v", userID, err)
		return
	}
	for _, ep := range eps {
		reqCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		req, err := http.NewRequestWithContext(reqCtx, http.MethodPost, ep, bytes.NewReader(wakeBody))
		if err != nil {
			cancel()
			log.Printf("[push] build request for user %d: %v", userID, err)
			continue
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := n.HTTP.Do(req)
		cancel()
		if err != nil {
			log.Printf("[push] POST %s (user %d): %v", ep, userID, err)
			continue
		}
		_ = resp.Body.Close()
		if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone {
			if err := n.Store.DeletePushEndpoint(userID, ep); err != nil {
				log.Printf("[push] prune dead endpoint %s (user %d): %v", ep, userID, err)
			} else {
				log.Printf("[push] pruned dead endpoint %s (user %d, status %d)", ep, userID, resp.StatusCode)
			}
		}
	}
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `go test ./internal/push/ -v`
Expected: PASS (host + notifier tests).

- [ ] **Step 5: Commit**

```bash
git add internal/push/notifier.go internal/push/notifier_test.go
git commit -m "feat(push): HTTP wake-up notifier with dead-endpoint pruning

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg"
```

---

### Task 5: Wire the notifier into the inbound webhook + server + main

**Files:**
- Modify: `internal/inbound/handler.go` — add `Notifier` to `Deps`; call it after a message is stored
- Test: `internal/inbound/handler_test.go`
- Modify: `internal/api/server.go` — add `Push push.Notifier` field to `Server`; pass it into `inbound.Deps`
- Modify: `cmd/server/main.go` — build an `*push.HTTPNotifier` and set `srv.Push`

**Interfaces:**
- Consumes: `push.Notifier` (Task 4), `push.HTTPNotifier` (Task 4), `proxy.SafeClient` (exists).
- Produces: `inbound.Deps.Notifier push.Notifier` (nil disables push); `api.Server.Push push.Notifier`.

- [ ] **Step 1: Write the failing inbound test**

Add to `internal/inbound/handler_test.go` (reuse the file's existing test helpers/imports; add imports `context`, `sync` if not already present):

```go
type recordingNotifier struct {
	mu      sync.Mutex
	userIDs []int64
}

func (r *recordingNotifier) NotifyNewMail(_ context.Context, userID int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.userIDs = append(r.userIDs, userID)
}

func TestInboundNotifiesOnNewMessage(t *testing.T) {
	st := newInboundTestStore(t) // existing helper that returns a *store.Store
	uid, err := st.CreateUser("dest@x.tech", "Dest", "h", "user", 1)
	if err != nil {
		t.Fatalf("create user: %v", err)
	}
	notifier := &recordingNotifier{}
	h := Handler(Deps{
		Store:    st,
		Validate: func(context.Context, *http.Client, string) (bool, error) { return true, nil },
		Notifier: notifier,
	})

	body := inboundPayloadJSON(t, "dest@x.tech", "<msg-1@ext>", "Hello") // existing/payload helper
	rec := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/webhooks/inbound", strings.NewReader(body))
	h.ServeHTTP(rec, req)
	if rec.Code != 200 {
		t.Fatalf("status = %d, body=%s", rec.Code, rec.Body.String())
	}

	notifier.mu.Lock()
	defer notifier.mu.Unlock()
	if len(notifier.userIDs) != 1 || notifier.userIDs[0] != uid {
		t.Fatalf("notified userIDs = %v, want [%d]", notifier.userIDs, uid)
	}

	// A duplicate delivery of the same message_id must NOT re-notify.
	rec2 := httptest.NewRecorder()
	req2 := httptest.NewRequest("POST", "/webhooks/inbound", strings.NewReader(body))
	h.ServeHTTP(rec2, req2)
	if len(notifier.userIDs) != 1 {
		t.Fatalf("after duplicate, userIDs = %v, want still 1", notifier.userIDs)
	}
}
```

> Implementer note: match the names of the existing inbound test helpers. Inspect the current `internal/inbound/handler_test.go` and `payload_test.go` for the established store-setup and payload-building helpers (the file already constructs `Deps` and posts to the handler). If a helper with a different name already does the equivalent of `newInboundTestStore`/`inboundPayloadJSON`, use that name instead of inventing a new one — do not duplicate setup code.

- [ ] **Step 2: Run it to confirm it fails**

Run: `go test ./internal/inbound/ -run TestInboundNotifiesOnNewMessage -v`
Expected: FAIL — `unknown field 'Notifier' in struct literal`.

- [ ] **Step 3: Add the `Notifier` field and call site**

In `internal/inbound/handler.go`, add the import `"maileroo-mail/internal/push"`, then add to the `Deps` struct (after `ValidationHost`):

```go
	// Notifier, if non-nil, is woken once after each new inbound message is
	// durably stored for a user, so the user's devices can sync. Email content
	// is never sent on the push path. Nil disables push (e.g. in tests).
	Notifier push.Notifier
```

In the per-recipient loop, immediately after the attachments `for` loop and before `d.forward(ctx, u, ex)`, add:

```go
			// Wake the user's devices to sync the newly stored message.
			if d.Notifier != nil {
				d.Notifier.NotifyNewMail(ctx, uid)
			}
```

Because this sits after the `MessageExistsForUser` duplicate-skip `continue` and after a successful `InsertMessage`, a duplicate delivery never re-notifies.

- [ ] **Step 4: Wire the notifier through the server**

In `internal/api/server.go`, add to the `Server` struct (after `ProxyHTTP`):

```go
	// Push, if non-nil, wakes a user's devices after new inbound mail is stored.
	Push push.Notifier
```

Add the import `"maileroo-mail/internal/push"`. Then in `Routes()`, pass it into the inbound handler — change the `inbound.Handler(inbound.Deps{...})` literal to include:

```go
		mux.Handle("POST /webhooks/inbound", inbound.Handler(inbound.Deps{
			Store: s.Store, HTTP: s.HTTP, AttachmentsDir: s.Cfg.AttachmentsDir,
			Sender: s.Sender, FromName: s.Cfg.MailFromName,
			Notifier: s.Push,
		}))
```

- [ ] **Step 5: Build the notifier in main**

In `cmd/server/main.go`, add the import `"maileroo-mail/internal/push"`, and set the field on the `srv` literal (after `Static: static,`):

```go
		// Wakes registered devices on new inbound mail. A separate short-timeout
		// SafeClient keeps push fan-out from blocking on a slow endpoint and adds
		// SSRF rechecking even though endpoints are host-pinned at registration.
		Push: &push.HTTPNotifier{Store: st, HTTP: proxy.SafeClient(10 * time.Second)},
```

- [ ] **Step 6: Run the full backend test suite**

Run: `go test ./internal/...`
Expected: PASS across `auth`, `api`, `store`, `push`, `inbound`, `config`.

- [ ] **Step 7: Build the binary to confirm wiring compiles**

Run: `go build ./...`
Expected: no errors.

- [ ] **Step 8: Commit**

```bash
git add internal/inbound/handler.go internal/inbound/handler_test.go internal/api/server.go cmd/server/main.go
git commit -m "feat(push): wake devices on new inbound mail

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg"
```

---

### Task 6: Document the new env var and endpoints

**Files:**
- Modify: `README.md` (config table + a short "Mobile API" note)
- Modify: `.env.example` and `deploy/maileroo.env.example` (add `PUSH_ENDPOINT_HOSTS`)

**Interfaces:** none (docs only).

- [ ] **Step 1: Update the README config table**

In `README.md`, add a row to the configuration table:

```
| `PUSH_ENDPOINT_HOSTS` | no | — | Comma-separated registrable domains a UnifiedPush endpoint URL may use (e.g. `ntfy.whitewolf.tech`). Empty disables push registration. |
```

Add a short section after "Security notes":

```markdown
## Mobile API

Native clients authenticate by POSTing credentials to `/api/login`; the response
includes a `token` (and `expires`). Send it as `Authorization: Bearer <token>`
on subsequent `/api/` requests (the session cookie still works for the web SPA).

For push, the client registers its UnifiedPush endpoint with
`POST /api/push/register` (`{"endpoint":"https://<PUSH_ENDPOINT_HOSTS>/..."}`)
and removes it with `POST /api/push/unregister`. On new inbound mail the server
POSTs a data-light `{"type":"new_mail"}` wake-up to each registered endpoint; the
app then syncs over the authenticated API. No email content is sent on the push path.
```

- [ ] **Step 2: Update env templates**

Add to both `.env.example` and `deploy/maileroo.env.example`:

```
# Allowed UnifiedPush endpoint hosts (comma-separated). Required to enable push.
PUSH_ENDPOINT_HOSTS=ntfy.whitewolf.tech
```

- [ ] **Step 3: Commit**

```bash
git add README.md .env.example deploy/maileroo.env.example
git commit -m "docs: document mobile bearer auth and push endpoints

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RQLX6oK8kWUd5ytmyyi1wg"
```

---

## Self-Review

**Spec coverage (against the foundation spec §3–§6):**
- §4(a) bearer-token auth → Task 1. ✓
- §4(b) push registration + fan-out on inbound → Tasks 2, 3, 5. ✓
- §5 token in Keystore / `Authorization: Bearer` (backend half) → Task 1. ✓
- §6 self-hosted ntfy, data-light wake-up, fetch-on-wake → Task 4 (`{"type":"new_mail"}` only) + Task 5. ✓
- §6 SSRF concern on client-supplied endpoint URLs → Task 3 host pinning (fail-closed). ✓
- Out of scope here (correct): the Kotlin shell, UnifiedPush client, distribution — those are the separate Android plan(s).

**Placeholder scan:** No TBD/TODO. The only deferred specificity is Task 5 Step 1's instruction to match existing inbound test-helper names — that is a deliberate "reuse, don't duplicate" directive with a concrete fallback, not a missing implementation.

**Type consistency:** `AddPushEndpoint`/`DeletePushEndpoint`/`ListPushEndpoints` signatures are identical across Tasks 2, 3 (api), 4 (`EndpointStore`), and the stub. `Notifier.NotifyNewMail(ctx, userID)` matches across Task 4, the inbound `Deps.Notifier` (Task 5), the recording stub, and the `Server.Push` field. `HostAllowed(rawURL, allowed)` and `PushEndpointHosts` are consistent across Tasks 3. Login response fields `token`/`expires` match between Task 1's implementation and its test. ✓
