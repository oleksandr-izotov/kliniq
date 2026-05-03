# Architecture

High-level overview of how Kliniq is built and why. For per-decision rationale see [DECISIONS.md](DECISIONS.md). For domain entities see [DOMAIN.md](DOMAIN.md).

---

## System diagram (V1)

```
┌──────────────────────────────────────────────────────────┐
│                     Browser                              │
│   SvelteKit SSR + hydrated SPA, https://localhost:5173   │
└────────────────────┬──────────────────────┬──────────────┘
                     │ JSON over HTTPS      │ SSE stream
                     │ (cookie session)     │ /api/v1/events
                     ▼                      ▼
        ┌────────────────────────────────────────────┐
        │   Spring Boot API (apps/api), https:8443    │
        │                                            │
        │  ┌──────────────────────────────────────┐  │
        │  │  api/         (controllers, DTOs)    │  │
        │  ├──────────────────────────────────────┤  │
        │  │  usecase/     (orchestration)        │  │
        │  ├──────────────────────────────────────┤  │
        │  │  domain/      (entities, rules)      │  │
        │  ├──────────────────────────────────────┤  │
        │  │  persistence/ (jOOQ repositories)    │  │
        │  └──────────────────────────────────────┘  │
        │                                            │
        │  Cross-cutting: auth, audit, sse, errors   │
        └─────────┬────────────────────┬─────────────┘
                  │                    │
              ┌───▼───┐            ┌───▼───┐
              │  PG   │            │ Redis │
              │  16   │            │  7.4  │
              └───────┘            └───────┘
              (truth)             (sessions, SSE pubsub)

      ┌─────────────┐  (dev only — emails go here, no real SMTP)
      │  Mailpit    │
      │  :8025 UI   │
      └─────────────┘
```

In V2, a `gateway` Spring service is added in front of the API for the customer portal — handles rate limiting, allowlist, and forwards to the API. In V1 we don't need it.

---

## Tech stack with rationale

### Backend

| Tech | Version | Why |
|---|---|---|
| **Kotlin** | 2.1+ | Less boilerplate vs Java; null-safety; sealed classes for state machines; coroutines available if we need them |
| **Java toolchain** | 21 LTS | Stable, supported until 2031, ZGC, virtual threads |
| **Spring Boot** | 3.5.x | Industry standard; biggest ecosystem; we use Web, Security, Validation, Session |
| **Gradle (Kotlin DSL)** | 8.x | Build tool; `build.gradle.kts` for type-safe configs |
| **PostgreSQL** | 16 | Default RDBMS choice; JSONB for flexible payloads; range types ideal for booking time intervals |
| **jOOQ** | 3.20.x | Type-safe SQL, code-generated from schema; avoids ORM pain (N+1, lazy-loading); shines for scheduling queries |
| **Flyway** | 10.x | DB migrations; versioned `V__*.sql` + repeatable `R__*.sql` for seeds |
| **Redis** | 7.4 | Spring Session backend; SSE pubsub between API instances (future); rate-limit counters |
| **Spring Security** | 6.x | Auth filter chain, CSRF, password encoder; we configure session-based auth manually |
| **WebAuthn4J** | 0.x | Passkey (FIDO2) support; integrates with Spring Security |
| **Argon2** | (jvm-libsodium) | Password hashing; resistant to GPU attacks |
| **Resilience4j** | 2.x | Rate limiter, retry, circuit breaker — used on auth endpoints |
| **Micrometer** | 1.x | Metrics; OpenTelemetry-compatible (deferred to deploy phase) |

### Frontend

| Tech | Version | Why |
|---|---|---|
| **SvelteKit** | 2.x | Modern, fast, SSR + hydration, file-based routing, smaller bundle than React/Vue |
| **TypeScript** | 5.x | Required, no `any` policy except where unavoidable |
| **Tailwind CSS** | 4.x | Utility-first, no separate CSS files, design system encoded in `tailwind.config.ts` |
| **shadcn-svelte** | latest | Component library — copy-paste source into the repo, fully customizable |
| **TanStack Query (Svelte)** | 5.x | Server-state caching, automatic refetch/invalidation |
| **Zod** | 3.x | Schema validation; can derive types; share with backend via OpenAPI later |
| **Lucide Icons** | latest | Icon set used by shadcn — clean, free, MIT |
| **vite** | 5.x | Comes with SvelteKit |
| **Vitest** | 1.x | Unit tests |
| **Playwright** | 1.x | E2E tests |

### Tooling

- **pnpm** workspace for monorepo (frontend pkgs)
- **lefthook** for git hooks (pre-commit, pre-push)
- **gitleaks** to block secrets
- **prettier + eslint** for JS/TS
- **ktlint + detekt** for Kotlin
- **dependabot** for dependency updates
- **conventional-commits** + **commitlint**

---

## Layer architecture (backend)

We follow **clean-ish architecture** without going overboard:

```
api/         <- HTTP layer, REST controllers, request/response DTOs, validation
usecase/     <- Application services, orchestration of multiple domain calls
domain/      <- Entities (data classes), value objects, domain services, business rules
persistence/ <- jOOQ-based repositories; only this layer touches the DB
infra/       <- Cross-cutting: security, sse, audit, mail, config
```

**Rules:**
- `domain/` knows nothing about Spring, jOOQ, HTTP. Pure Kotlin.
- `usecase/` orchestrates `domain/` and `persistence/`. Spring beans, can use `@Transactional`.
- `api/` only calls `usecase/`. Never `persistence/` directly.
- `persistence/` exposes repository interfaces returning domain types, not jOOQ records.
- DTOs and domain entities are **separate**. Mapping happens at `api/` boundary.

This is a bit more code than putting everything in services, but pays off for testability and prevents domain bleed.

---

## Auth architecture

See [DECISIONS.md ADR-003](DECISIONS.md) for why we roll our own. Summary:

```
Browser  ──login──▶  POST /api/v1/auth/login (email + password OR passkey assertion)
Browser  ◀─Set-Cookie SESSION_ID (HttpOnly, Secure, SameSite=Lax)
Browser  ──any request──▶  Cookie sent automatically
                           ↓
                     SessionFilter
                           ↓
                     Look up SESSION_ID in Redis
                           ↓
                     Load User → set in SecurityContext
                           ↓
                     @PreAuthorize on controllers
```

**Key properties:**
- Sessions live in Redis with TTL = 30 days sliding
- Session ID is an opaque random 32-byte token, base64url
- Cookie: `__Host-session`, `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`
- CSRF: double-submit cookie pattern for state-changing requests
- Logout invalidates session in Redis
- Passkeys stored per-user, multiple allowed per user
- Email verification required before login is allowed
- Password reset uses signed time-limited tokens (15 min) sent to email

---

## Real-time (V1)

Server-Sent Events — simpler than WebSockets, plenty for our use case (server pushes booking changes, browser shows them).

```
GET /api/v1/events       (Accept: text/event-stream, cookie auth)
   ──▶  Spring streams: data: {"type":"booking.created","payload":{...}}
```

Backed by Redis pubsub so multiple API instances can broadcast.

---

## Error handling

**Backend:**
- All exceptions caught in `@RestControllerAdvice` → standardized error response:
  ```json
  { "code": "BOOKING_OVERLAP", "message": "...", "traceId": "..." }
  ```
- Domain exceptions are sealed Kotlin classes per module
- Validation errors → 400 with field-level details
- Auth failures → 401 / 403, never leak whether user exists

**Frontend:**
- TanStack Query mutations show toast on error via shadcn-svelte's `Toaster`
- Form errors mapped from API error codes to user-friendly messages
- Error boundary at `+error.svelte` for unexpected crashes

---

## Logging

- **Structured JSON** via Logback config (one event per line)
- Fields: `timestamp, level, logger, message, traceId, userId?, requestId?`
- No PII (no email, no patient code in logs)
- In dev: pretty console output. In prod (later): JSON only.

---

## What's deferred to deployment phase

These exist in the architecture but we don't wire them until V1 works locally:

- Coolify on Hetzner CPX21
- Caddy reverse proxy with Let's Encrypt
- Cloudflare DNS + DDoS
- Resend for production email
- Sentry for error tracking
- OpenTelemetry + Grafana Cloud
- Backblaze B2 for backups
- Restic for backup encryption + retention
