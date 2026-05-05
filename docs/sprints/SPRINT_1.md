# Sprint 1 — Authentication

**Goal:** A user can register with email + password, verify their email, log in (password OR passkey), reset their password, and log out. Sessions are server-side in Redis with secure cookies. CSRF works. Rate limiting protects against brute force.

**Definition of done:**
- [x] All routes in [Acceptance criteria](#acceptance-criteria) below behave correctly
- [x] Tests cover happy paths and key failure modes (>70% coverage on `auth/` packages — JaCoCo measures **88.9% line coverage** across the auth surface; HIBP HTTP client lives at 29% on purpose, the network call is stubbed in tests)
- [x] Login + register UI exists, looks decent (using shadcn-svelte, Kliniq Emerald theme)
- [x] Passkey registration + assertion works end-to-end in Chrome on macOS/Windows
- [x] OWASP ASVS L2 auth checklist (below) all green
- [x] CI green

**Estimated effort:** 2-3 weeks at chaotic pace.

**Prereq:** Sprint 0 done.

---

## Decisions made (locked-in for this sprint)

- Cookie name: `__Host-kliniq_session` (HttpOnly, Secure, SameSite=Lax, Path=/)
- Session TTL: 30 days sliding (refreshed on every request)
- Password hashing: Argon2id, m=64MB i=3 p=4
- CSRF: double-submit cookie. Cookie name: `kliniq_csrf` (Secure, SameSite=Lax, NOT HttpOnly so JS can read). Header: `X-CSRF-Token`.
- Email verification token: 32-byte random, stored as SHA-256 hash, expires in 24h
- Password reset token: same as above, expires in 15min
- WebAuthn library: WebAuthn4J 0.x
- Relying Party ID: `localhost` in dev, real domain later

---

## Schema migrations

`V2__auth.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL,
    email_normalized TEXT GENERATED ALWAYS AS (lower(email)) STORED UNIQUE,
    email_verified_at TIMESTAMPTZ,
    password_hash TEXT,
    display_name TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('ADMIN','MANAGER','STAFF')),
    is_surgeon BOOLEAN NOT NULL DEFAULT false,
    specialty TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_has_credential CHECK (password_hash IS NOT NULL OR EXISTS (
        -- can't reference passkeys table here; enforced at app layer too
        true
    ))
);

CREATE TABLE passkeys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id BYTEA NOT NULL UNIQUE,
    public_key BYTEA NOT NULL,
    signature_counter BIGINT NOT NULL DEFAULT 0,
    aaguid UUID,
    device_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_passkeys_user ON passkeys(user_id);

CREATE TABLE email_verification_tokens (
    token_hash BYTEA PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE password_reset_tokens (
    token_hash BYTEA PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_user_id UUID,
    action TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id UUID,
    before JSONB,
    after JSONB,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_actor ON audit_events(actor_user_id);
CREATE INDEX idx_audit_entity ON audit_events(entity_type, entity_id);
CREATE INDEX idx_audit_created ON audit_events(created_at DESC);
```

After this migration, run jOOQ codegen so we have type-safe SQL access:
```bash
cd apps/api && ./gradlew jooqCodegen
```

---

## API endpoints

All under `/api/v1/auth/*`. All return JSON. All errors use the standard `{ "code", "message", "traceId" }` envelope.

### `POST /api/v1/auth/register`

**Body:** `{ email, password, displayName }`
**Validation:** valid email, password >= 12 chars + has digit + has letter, displayName 1-100 chars
**Behavior:**
1. Normalize email
2. If email taken → return 200 with neutral message ("If this email isn't taken, you'll get a verification link") — DO NOT reveal user existence
3. Else: create user with `role=STAFF`, `email_verified_at=NULL`
4. Generate verification token, store SHA-256 hash, send email via Mailpit
5. Audit `user.registered`
6. Return 200 with neutral message

**Rate limit:** 5/min/IP

### `POST /api/v1/auth/verify`

**Body:** `{ token }`
**Behavior:**
1. SHA-256 the token → look up
2. If not found / expired / consumed → return 400 generic error
3. Set `email_verified_at = now()`, mark token consumed
4. Audit `user.email_verified`
5. Return 200

### `POST /api/v1/auth/login`

**Body:** `{ email, password }`
**Validation:** as above
**Behavior:**
1. Look up user by email_normalized
2. If not found → fake-verify a dummy hash to prevent timing oracle, return 401
3. If found but `email_verified_at IS NULL` → return 403 with code `EMAIL_NOT_VERIFIED`
4. If `status = DISABLED` → return 403 with code `ACCOUNT_DISABLED`
5. Verify password (Argon2id)
6. If wrong → return 401 with code `INVALID_CREDENTIALS`
7. Else: create session, set cookie, audit `user.login`, return user profile

**Rate limit:** 10/min/IP, exponential backoff on repeated failures (Resilience4j)

### `POST /api/v1/auth/logout`

**Behavior:**
1. Delete session from Redis
2. Clear cookie
3. Audit `user.logout`
4. Return 200

### `POST /api/v1/auth/password/forgot`

**Body:** `{ email }`
**Behavior:**
1. Look up user
2. ALWAYS return 200 with neutral message ("If this account exists, you'll get a reset link")
3. If user exists and verified: generate token, store hash, send email
4. Audit `user.password_reset_requested`

**Rate limit:** 3/min/IP

### `POST /api/v1/auth/password/reset`

**Body:** `{ token, newPassword }`
**Behavior:**
1. SHA-256 token → look up
2. Validate not expired, not consumed
3. Update password_hash, mark token consumed, **invalidate all existing sessions for user**
4. Audit `user.password_reset_completed`
5. Return 200

### `GET /api/v1/auth/me`

**Behavior:** returns current user profile if session valid, 401 else.

### Passkey endpoints

#### `POST /api/v1/auth/passkey/register/start` (auth required)
Returns WebAuthn registration challenge.

#### `POST /api/v1/auth/passkey/register/finish` (auth required)
Body: WebAuthn registration response. Verifies, stores in `passkeys` table.

#### `POST /api/v1/auth/passkey/login/start`
Body: `{ email }` (optional — for usernameless passkey, no email).
Returns WebAuthn assertion challenge.

#### `POST /api/v1/auth/passkey/login/finish`
Body: WebAuthn assertion response. Verifies, creates session, returns user profile.

---

## Frontend pages

### `(auth)/login/+page.svelte`

- Split-screen layout (illustration on left, form on right)
- Email + password fields
- "Sign in with passkey" button (uses `navigator.credentials.get`)
- "Forgot password?" link
- "Don't have an account? Sign up" link

### `(auth)/register/+page.svelte`

- Same split-screen
- Email, password, displayName fields
- Password strength indicator
- After submit: "Check your email" message

### `(auth)/verify/+page.svelte`

- Reads `?token=...` from URL
- Calls `/auth/verify`
- Shows success or error
- On success: redirect to `/login` after 2s

### `(auth)/reset/+page.svelte`

- Two modes:
  - Without token: email input → "send link"
  - With `?token=...`: new-password input → submit

### `(app)/settings/security/+page.svelte`

- Lists registered passkeys (device name, last used, created)
- "Add passkey" button → calls register/start, register/finish
- Delete passkey button (with confirmation)
- Change password section

---

## Week 1 — Backend foundation

### Day 6 — Schema + repositories
- [ ] Write V2 migration above
- [ ] Run, regenerate jOOQ
- [ ] Create `domain/user/User.kt`, `domain/user/Role.kt`, etc. (data classes)
- [ ] Create `persistence/user/UserRepository.kt` (interface) + `JooqUserRepository.kt`
- [ ] Tests on UserRepository with Testcontainers

### Day 7 — Password hashing + register endpoint
- [ ] Add `de.mkammerer:argon2-jvm` dep
- [ ] `infra/security/PasswordHasher.kt` with Argon2id
- [ ] `usecase/auth/RegisterUseCase.kt`
- [ ] `api/auth/AuthController.kt` with `/register` endpoint
- [ ] `infra/mail/EmailSender.kt` (Spring `JavaMailSender` configured for Mailpit)
- [ ] Email template (HTML + text fallback) for verification
- [ ] `usecase/auth/EmailVerificationTokenService.kt`
- [ ] Test register happy path + email taken (neutral response)
- [ ] Manually: register, see email in Mailpit UI

### Day 8 — Verify + login endpoints
- [ ] `/verify` endpoint
- [ ] `infra/security/SessionService.kt` — create/get/invalidate sessions in Redis
- [ ] `infra/security/SessionFilter.kt` — Spring Security filter that loads user from cookie
- [ ] Configure Spring Security: cookie config, filter chain
- [ ] `/login` endpoint with all the failure modes (timing-safe)
- [ ] `/logout` endpoint
- [ ] `/me` endpoint
- [ ] Tests: login flow, wrong password, unverified email, disabled

### Day 9 — Password reset + CSRF + rate limiting
- [ ] `/password/forgot` + `/password/reset` endpoints
- [ ] CSRF filter (double-submit cookie)
- [ ] Resilience4j rate limiters on register/login/forgot
- [ ] Audit event writer (`infra/audit/AuditWriter.kt`) writes in same TX
- [ ] Tests for all of the above

---

## Week 2 — Frontend forms + passkeys

### Day 10 — Auth pages scaffolding
- [ ] `(auth)/+layout.svelte` — split-screen with illustration placeholder (use SVG from VISUALS.md when ready)
- [ ] `lib/auth/api.ts` — typed wrappers around `/api/v1/auth/*`
- [ ] `lib/auth/csrf.ts` — read CSRF cookie, attach `X-CSRF-Token` header
- [ ] Login page (form, error states, loading)
- [ ] Register page

### Day 11 — Verify + reset pages
- [ ] Verify page (token from URL)
- [ ] Reset request page
- [ ] Reset complete page
- [ ] Logout flow (button in placeholder topbar)
- [ ] `hooks.server.ts` — load session, redirect unauth → `/login`

### Day 12-13 — Passkey backend
- [ ] Add `com.webauthn4j:webauthn4j-core` dep
- [ ] `infra/webauthn/WebAuthnService.kt` — wraps WebAuthn4J for register + assert
- [ ] Endpoints `/passkey/register/start|finish` + `/passkey/login/start|finish`
- [ ] `passkeys` table writer/reader
- [ ] Tests with WebAuthn4J's `RegistrationData` / `AuthenticationData` mocks

### Day 14 — Passkey frontend
- [ ] `lib/auth/passkey.ts` — wraps `navigator.credentials.create` and `.get` with base64url encode/decode
- [ ] "Sign in with passkey" button on login page
- [ ] `(app)/settings/security/+page.svelte` — list + add + delete passkeys
- [ ] Manual test in Chrome with built-in authenticator

### Day 15 — Polish + tests
- [ ] Toast notifications on all auth actions (using shadcn-svelte's Sonner)
- [ ] Empty/loading/error states everywhere
- [ ] E2E Playwright spec: register → verify (intercept Mailpit API for token) → login → logout
- [ ] E2E spec: passkey register + login

---

## OWASP ASVS L2 checklist (must all be green)

- [x] V2.1.1 Passwords ≥ 12 chars — `AuthDtos.kt` MIN_PASSWORD = 12
- [x] V2.1.2 Allow long passphrases (max 128 chars enforced) — MAX_PASSWORD = 128
- [x] V2.1.7 Block top-1000 common passwords — HIBP k-anonymity via `HibpBreachedPasswordChecker`, applied on register / reset / change
- [x] V2.2.1 Anti-automation (rate limiting on auth endpoints) — `RateLimitFilter` per-IP Redis INCR
- [ ] V2.2.3 Notify user of authentications from new devices (V2 — deferred)
- [x] V2.4.1 Argon2id with parameters from ADR-010 — `PasswordHasher.kt` (m=64MB, i=3, p=4)
- [x] V2.5.4 Don't reveal whether user exists on register/forgot — neutral envelopes; `LoginUseCase` runs Argon2 against a dummy hash for unknown emails
- [x] V2.7.1 OOB tokens (email tokens) at least 20 bits entropy → we use 256 — 32 random bytes
- [x] V2.7.2 OOB tokens single-use (consumed_at) — `markConsumed` atomically gates re-use
- [x] V2.7.3 OOB tokens time-limited (15min reset, 24h verify) — `PasswordResetTokenService` / `EmailVerificationTokenService`
- [x] V3.2.1 Sessions invalidated on logout — `LogoutUseCase`
- [x] V3.2.2 Session ID generated by framework using CSPRNG — `SecureRandom`, 32 bytes (256 bit)
- [x] V3.4.1 Cookies have Secure + HttpOnly + SameSite=Lax + __Host- prefix — `SessionCookieService`
- [x] V3.5.1 Sessions terminate on logout — current session via `LogoutUseCase`; primitive `invalidateAllForUser` exists for password reset (admin "log-out everywhere" UI deferred to V2 per below)
- [x] V3.7.1 Re-auth before sensitive changes — `POST /password/change` verifies `currentPassword` against stored hash before applying the new one; other sessions invalidated
- [x] V13.1.1 CSRF tokens on state-changing requests — `CookieCsrfTokenRepository.withHttpOnlyFalse()` with eager-generation handler
- [x] V13.2.6 Rate limiting + exponential backoff on login failures — `LoginAttemptTracker` per-IP doubling window (2s → 60s cap, 1h sliding TTL)

---

## What's NOT in this sprint

- Google OAuth — V2
- 2FA via TOTP — V2 (passkeys cover the strong-auth need)
- Account recovery via security questions — never (industry agrees these suck)
- "Remember me" beyond default 30d sliding — not needed
- Admin "log out all sessions" UI — V2

---

## Sprint 1 retro

**What went well**

- The use-case + repository split paid off. Every endpoint reads as a thin
  controller delegating to one named use case with a `sealed Result`,
  which made it trivial to add new failure modes (PASSWORD_BREACHED,
  LOGIN_BACKOFF, SAME_PASSWORD) without rewriting controllers.
- `webauthn4j-test`'s `ClientPlatform` + `WebAuthnAuthenticatorAdaptor(PackedAuthenticator())`
  drives full registration + assertion ceremonies in MockMvc, so
  passkeys are covered by 16 real integration tests, not mocks.
- Audit table with DB-level append-only triggers caught a couple of
  early bugs where I was trying to update events from outside the use
  case. The DB rejection forced the use case to own the writes.
- Lefthook + commitlint + gitleaks + ktlint + detekt + svelte-check
  on every commit/push kept main green throughout — saved at least one
  CI cycle per push.

**What was harder than expected**

- WebAuthn4J 0.29 API is not consistently documented. `AuthenticatorImpl`
  vs the abstract `WebAuthnModelAuthenticator` vs the `AuthenticatorAdaptor`
  wrapping for `ClientPlatform` cost a couple of compile-fix loops.
- Spring Security 6's deferred CSRF token caused a real production
  bug: the cookie wasn't written on 401 responses, so `primeCsrf()`
  silently returned nothing. Fixed by `setCsrfRequestAttributeName(null)`
  to force eager generation.
- SvelteKit hydration timing under Playwright: `page.click()` outraces
  `onMount` so form submits go through the native handler. Solved with
  `gotoHydrated()` (waits for `networkidle`) plus a lazy CSRF prime in
  `apiRequest` for any state-changing call without a cookie.
- Mailpit + Postgres + Redis + bootRun + vite dev across three terminals
  is fiddly. `compose.yaml` covers three of them but the JVM lifecycle
  is still manual; that's a Sprint 2 ergonomics fix.

**Time spent vs estimate**

Estimated 10 days; actual ≈11 calendar days (chaotic pace, nights and
weekends, ~6 effective working days). The bulk of the overrun was on
WebAuthn library quirks and the Sprint-1 polish pass that closed out
HIBP, exponential backoff, change-password, and Playwright after the
initial DoD audit revealed they were missing.

**What I'd change for Sprint 2 planning**

- Bake a `make dev` (or `pnpm dev:all`) that brings up compose stack +
  bootRun + vite dev with consistent log streams. Hand-managing three
  terminals each session is the daily friction.
- Add a Spring Boot devtools profile so the JVM hot-reloads on Kotlin
  changes — currently every `usecase/*.kt` touch needs a manual
  bootRun restart.
- Keep the "ASVS audit" as an explicit subtask before the sprint's
  closing commit, not an after-the-fact discovery. Saved time at the
  end if it lives in Day 0 of every sprint with a security surface.

**Audit log review**

Sampled 10 rows across `user.registered`, `user.email_verified`,
`user.login`, `user.password_reset_completed`, `user.password_changed`,
`passkey.registered`, `passkey.revoked`, `user.login.passkey`. Each
carries `actor_user_id == entity_id` for the user, sane metadata,
and append-only invariants hold (the V2 trigger rejects UPDATE/DELETE).
Looks consistent.

**Security review — anything cheated on?**

- HIBP fail-open on transient outages is a pragmatic choice but worth
  flagging: a HIBP outage temporarily lowers the bar. Mitigated by
  the rate-limit + Argon2 cost + breach-check log alerts when fail-open
  fires.
- "Sign out everywhere" is wired at the primitive level
  (`SessionStore.invalidateAllForUserExcept`) and exercised by password
  reset / change-password, but no user-facing button. Plan said V2;
  honoured.
- TLS still uses mkcert in dev. Production cert chain wiring is a
  Sprint-3 deployment concern.

→ Sprint 2 planning next.
