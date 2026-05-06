# Sprint 3 — Real-time + admin polish

**Goal:** Booking changes broadcast live to every connected staff browser via SSE. An `ADMIN` can invite, disable, promote, and surgeon-flag staff through a real UI (no more `psql` for role changes). Every state change is browsable in a read-only audit log. The schedule grows a week view alongside the existing day view.

By the end of Sprint 3 the V1 feature surface is complete; M4 is the deploy + Lighthouse + cross-browser pass on what's already shipped.

**Definition of done:**

- [ ] `GET /api/v1/events` streams `text/event-stream` ; booking create/update/cancel/start/complete actions push a typed event within ~250ms of commit
- [ ] Two browser tabs against the same backend show booking changes from each other live (no manual refresh) — covered by Playwright two-context test
- [ ] Redis pub/sub backplane in place so multi-instance deploy doesn't fragment the broadcast (single-node in V1, but the plumbing is HA-ready from day one)
- [ ] Admin user-management UI: list, invite, disable, change role, flag as surgeon (with specialty), all under `/(app)/admin/users` and `/(app)/admin/invitations`
- [ ] Public invitation-accept page (`/invite?token=...`) wires a fresh user through to a verified, role-pre-set account in one round trip
- [ ] Read-only audit log UI under `/(app)/admin/audit` with filters (entity_type, actor, date range)
- [ ] Schedule has a working week view: 7 days × one OR, navigated by week (Prev/Next/This week)
- [ ] Tests cover happy paths and key failure modes (>70% line coverage on the Sprint 3 surface — JaCoCo measured)
- [ ] CI green
- [ ] Sprint 3 sanity checklist (below) all green
- [ ] Smoke `--include-booking` no longer needs `psycopg` for the role/surgeon promotion (uses the new admin endpoint instead)

**Estimated effort:** 11 days at chaotic pace.

**Prereq:** Sprint 2 done.

---

## Decisions locked (no debate this sprint)

- **Transport:** SSE, not WebSocket. We need one-way server→client for booking changes; SSE is simpler to operate (plain HTTP, native EventSource auto-reconnects, traverses proxies that throttle WS), and HTTP/2 multiplexing means the per-tab connection cost is negligible. WebSocket buys nothing here.
- **Auth on `/events`:** the same `__Host-kliniq_session` cookie as every other endpoint. EventSource sends cookies automatically. No bearer tokens.
- **Backplane:** Redis pub/sub. Each Spring instance subscribes once to a global `kliniq:booking-events` channel; per-tab `SseEmitter`s fan out from the local subscriber. We're single-node in V1 deploy, but designing the publish path through Redis from day one means horizontal scale is a config change, not a rewrite.
- **Heartbeat:** server pings the channel every 15s with a comment line (`: ping`). Common reverse proxies (Traefik, nginx) drop idle TCP at 30–60s; 15s is comfortably under that without flooding the wire.
- **Invitation flow:** admin → POST invitation → token-link emailed to invitee → invitee accepts at `/invite?token=...` → form for password + display name → account created with role + is_surgeon + specialty pre-set, email auto-marked verified (the token-bearer proved control of the inbox by clicking it). No "admin sets a temporary password" path — that creates a shared-secret window.
- **Audit log read API:** ADMIN-only. Read-only — no PATCH/DELETE endpoints exist on audit rows ever. Audit history is append-only by definition.
- **Audit row payloads:** `before` / `after` JSON columns already redact `patient_ref` per Sprint 1's PII discipline; the read API and UI surface them as-is and don't widen the projection.
- **Week view layout:** OR-centric, not date-centric. User picks an operating room → 7 day columns side-by-side, vertical time grid identical to the day view. The alternative (all-ORs × 7-days as a single grid) is too dense to be useful at any reasonable viewport. Day view stays the default landing tab.
- **`/users/surgeons` stays.** It's read-only, narrow, and powers the booking-modal picker without dragging admin scope through it. The admin UI consumes the richer `GET /api/v1/admin/users?isSurgeon=true` for management.

---

## Schema migration `V4__realtime_admin.sql`

```sql
-- ----------------------------------------------------------------------------
-- user_invitations — admin-issued one-shot tokens that pre-set role + surgeon
-- flags. The token itself is hashed (sha-256, no salt — single-use, short-lived,
-- already 256 bits of entropy at issue time so rainbow tables aren't a threat).
-- ----------------------------------------------------------------------------
CREATE TABLE user_invitations (
    id              UUID PRIMARY KEY,
    email           TEXT NOT NULL CHECK (length(email) BETWEEN 3 AND 254),
    email_normalized TEXT GENERATED ALWAYS AS (lower(email)) STORED,
    role            TEXT NOT NULL CHECK (role IN ('ADMIN','MANAGER','STAFF')),
    is_surgeon      BOOLEAN NOT NULL DEFAULT FALSE,
    specialty       TEXT CHECK (
                        specialty IN ('CARDIOLOGY','ORTHOPEDICS','GENERAL','NEUROSURGERY','OPHTHALMOLOGY')
                    ),
    token_hash      TEXT NOT NULL UNIQUE,
    issued_by_id    UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    accepted_at     TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    -- An invitation row is "active" iff: not yet accepted, not revoked,
    -- not expired. The unique-pending-per-email constraint is enforced by
    -- the partial index below — duplicates after acceptance/revocation
    -- are fine (they're history).
    CONSTRAINT user_invitations_specialty_iff_surgeon
        CHECK ((is_surgeon AND specialty IS NOT NULL) OR (NOT is_surgeon AND specialty IS NULL))
);
CREATE UNIQUE INDEX user_invitations_pending_email_idx
    ON user_invitations (email_normalized)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX user_invitations_token_hash_idx ON user_invitations (token_hash);

-- ----------------------------------------------------------------------------
-- audit_events — already exists from Sprint 1 (V1__base.sql). We don't add new
-- columns here; this sprint only adds *read* paths over the existing data.
-- ----------------------------------------------------------------------------
```

After migration: `cd apps/api && ./gradlew generateJooq`.

---

## API endpoints

All under `/api/v1/*`, JSON in/out, standard `{ code, message, fieldErrors? }` envelope on errors.

### Real-time

| Method | Path | Auth | Body / response |
|---|---|---|---|
| `GET` | `/events` | `STAFF`+ | `text/event-stream`. Events: `booking.created`, `booking.updated`, `booking.cancelled`, `booking.started`, `booking.completed`, each with the new `BookingDto` as `data:`. Every 15s a `: ping` comment line keeps the connection alive. |

### Admin: users

| Method | Path | Auth | Body / response |
|---|---|---|---|
| `GET` | `/admin/users` | `ADMIN` | filters: `q` (substring on display_name/email), `role`, `isSurgeon`, `status`. Returns `{ items: [AdminUserDto], page, pageSize, total }` — paginated, default 50. |
| `PATCH` | `/admin/users/{id}` | `ADMIN` | partial: `role`, `isSurgeon` + `specialty` (must be set together iff true), `status` (`ACTIVE`/`DISABLED`). 409 `LAST_ADMIN` if the change would leave zero active admins. 409 `SELF_LOCKOUT` if an admin tries to demote/disable themselves. |

### Admin: invitations

| Method | Path | Auth | Body / response |
|---|---|---|---|
| `GET` | `/admin/invitations` | `ADMIN` | `[InvitationDto]` — pending only by default; `?includeHistory=true` for accepted/revoked rows |
| `POST` | `/admin/invitations` | `ADMIN` | `{ email, role, isSurgeon, specialty? }` → 201 `InvitationDto`. 409 `INVITATION_PENDING` if a pending invite already exists for that email. Sends invitation email. |
| `DELETE` | `/admin/invitations/{id}` | `ADMIN` | revoke → 204. Idempotent. |
| `POST` | `/auth/invitation/accept` | public | `{ token, password, displayName }` → 200 `UserResponse` (logged in, session cookie set). Marks the invitation accepted, creates the user pre-verified. |

### Admin: audit log

| Method | Path | Auth | Body / response |
|---|---|---|---|
| `GET` | `/admin/audit` | `ADMIN` | filters: `entityType`, `actorUserId`, `action`, `from`, `to`. Returns `{ items: [AuditEventDto], page, pageSize, total }` paginated by 50. Ordered by `created_at DESC`. |

### Schedule (extended)

| Method | Path | Auth | Body / response |
|---|---|---|---|
| `GET` | `/schedule/week?from=YYYY-MM-DD&operatingRoomId=...` | `STAFF`+ | `{ operatingRoom, timezone, days: [{ date, bookings }] }` — exactly 7 days starting at `from`, in clinic-local zone |

### Rate limits (added to `RateLimitFilter.LIMITS`)

| Endpoint | Limit |
|---|---|
| `GET /events` | 5 / min / IP — opening many SSE streams isn't a normal pattern |
| `POST /admin/invitations` | 30 / min / IP |
| `POST /auth/invitation/accept` | 10 / min / IP |

---

## Frontend pages

- **`(app)/admin/users/+page.svelte`** — paginated, filterable table. Inline switches for role / is_surgeon / status. Confirm-dialog on disable. ADMIN-only at the layout level.
- **`(app)/admin/invitations/+page.svelte`** — pending invitations list + "invite a new user" form (email, role, surgeon flag + specialty). Revoke button per row.
- **`(app)/admin/audit/+page.svelte`** — table view with filter bar; clicking a row opens a side panel with `before` / `after` JSON pretty-printed.
- **`(auth)/invite/+page.svelte`** — public token-accept form. Validates the token via `GET /auth/invitation/{token}` (gives back email + pre-set role for display) then `POST /auth/invitation/accept` to finalize.
- **`(app)/schedule/+page.svelte`** — view toggle (Day | Week). Week view picks an OR + week-of, renders 7 columns.
- **`(app)/+page.svelte`** — home dashboard gains the **Admin** tile (admin-only, links to `/admin/users`).
- **`lib/util/eventSource.ts`** — typed wrapper over `EventSource` with auto-reconnect-with-backoff. Schedule + side-panel subscribe on mount, refresh on `booking.*`.

---

## Day-by-day plan

### Day 29 — V4 migration + Redis pub/sub backplane
- [ ] Write `V4__realtime_admin.sql` — `user_invitations` table only; audit_events stays
- [ ] Run, regenerate jOOQ, sanity-check the new record shape
- [ ] `infra/realtime/BookingEvent.kt` value class (kind, bookingId, operatingRoomId, occurredAt)
- [ ] `infra/realtime/BookingEventPublisher.kt` — publishes JSON to Redis `kliniq:booking-events`
- [ ] `infra/realtime/RedisBookingEventSubscriber.kt` (Spring `MessageListener`) → broadcasts to local emitters
- [ ] Repository test: write events to Redis, assert subscriber receives them in-order

### Day 30 — SSE endpoint + booking integration
- [ ] `infra/realtime/SseService.kt` — manages `SseEmitter` lifecycle keyed by user-id; per-emitter heartbeat scheduled task
- [ ] `api/realtime/EventsController.kt` — `GET /events` registers emitter, returns `text/event-stream`
- [ ] Wire `BookingEventPublisher.publish()` into the success path of `CreateBookingUseCase`, `UpdateBookingUseCase`, `TransitionBookingUseCase` — publish *after* the transaction commits to avoid leaking events for rolled-back writes
- [ ] Integration test: open SSE via `MockMvc` async, perform a booking action, assert event arrives within 1s

### Day 31 — Frontend SSE hookup
- [ ] `lib/util/eventSource.ts` — typed wrapper. Connects on demand, reconnects with exponential backoff (max 30s), exposes a `$state` subscription store
- [ ] Schedule page subscribes on mount, refreshes the day on any `booking.*` event whose `occurredAt` falls in the visible date
- [ ] Side panel re-fetches the booking on `booking.updated` / `booking.cancelled` / `booking.started` / `booking.completed` if its id matches
- [ ] Playwright test: open two browser contexts as the same user, action in #1 (cancel a booking), assert #2 reflects within 2s without manual refresh

### Day 32 — Invitations: backend
- [ ] `domain/invitation/Invitation.kt` + `NewInvitation.kt` (specialty-iff-surgeon invariant mirrors User)
- [ ] `persistence/invitation/{InvitationRepository,JooqInvitationRepository}.kt`
- [ ] `usecase/invitation/{Create,List,Revoke,Accept}InvitationUseCase.kt` + tests
- [ ] Token: 32-byte URL-safe base64; stored as sha-256 hash; expires 7 days; single-use
- [ ] `api/invitation/InvitationController.kt` (admin endpoints) + `api/auth/AuthController.kt` extension for `/auth/invitation/accept`
- [ ] Email template `invitation.html` with the accept link
- [ ] Integration tests: create → email arrives → accept → user can log in with set role and is_surgeon flag; expired token → 410; double-accept → 409

### Day 33 — Admin user-management: backend
- [ ] `usecase/admin/{ListUsers,UpdateUser}UseCase.kt`
- [ ] Result variants: `LastAdmin`, `SelfLockout`, `Success`, `NotFound`, `InvalidSpecialty` (for surgeon-without-specialty edge case)
- [ ] `api/admin/UserAdminController.kt` + DTOs
- [ ] SecurityConfig: every `/api/v1/admin/**` route gated to `hasRole("ADMIN")`
- [ ] Integration tests including the LastAdmin / SelfLockout invariants under concurrent admin demotions

### Day 34 — Admin frontend: users
- [ ] `lib/api/admin/users.ts` typed wrapper
- [ ] `(app)/admin/+layout.svelte` — guard at the load function level (`data.user.role !== 'ADMIN'` → 403 or redirect to home)
- [ ] `(app)/admin/users/+page.svelte` — paginated table; inline `<select>` for role, checkbox for is_surgeon (with specialty `<select>` revealed when checked), Disable/Re-enable button
- [ ] Confirm-dialog before disabling a user
- [ ] Toasts for LastAdmin / SelfLockout

### Day 35 — Admin frontend: invitations + accept page
- [ ] `lib/api/admin/invitations.ts`
- [ ] `(app)/admin/invitations/+page.svelte` — list + create form (email, role, surgeon+specialty), Revoke button
- [ ] `(auth)/invite/+page.svelte` — token in query, calls `GET /auth/invitation/{token}` for the email/role preview, password + display name form, submit calls `POST /auth/invitation/accept`
- [ ] Smoke `--include-booking`: drop the psycopg seed, use the new invitation flow + admin user PATCH instead

### Day 36 — Audit log: backend
- [ ] `usecase/audit/ListAuditEventsUseCase.kt`
- [ ] `api/admin/AuditController.kt` — paginated, filtered, `ADMIN`-only
- [ ] Integration tests: filter by entity_type, by actor, by date range; pagination correctness; PII redaction stays intact (no `patient_ref` leak)

### Day 37 — Audit log: frontend
- [ ] `lib/api/admin/audit.ts`
- [ ] `(app)/admin/audit/+page.svelte` — filter bar (entity type, actor, date) + paginated table
- [ ] Side panel for selected row showing `before` / `after` JSON pretty-printed via `<pre>` (no extra dep — JSON already comes well-formed)
- [ ] Home dashboard: add **Admin** tile (admin-only, links to `/admin/users`)

### Day 38 — Week view
- [ ] `usecase/schedule/WeekScheduleUseCase.kt` — iterate the 7 days, reuse the existing IN-list booking lookup so it's still one round-trip
- [ ] `api/schedule/ScheduleController.kt` extension: `GET /schedule/week`
- [ ] `(app)/schedule/+page.svelte` — view toggle (Day | Week), week-of date picker, OR picker. Render reuses the existing `bookingTop`/`bookingHeight` math; the only new thing is 7 columns
- [ ] Playwright: switch to week, see bookings tile across days, navigate Prev/Next week

### Day 39 — Sprint close
- [ ] Run `./gradlew check` + measured JaCoCo coverage on Sprint 3 surface ≥70%
- [ ] Tick DoD boxes
- [ ] Write retro
- [ ] Smoke `--include-booking` runs without psycopg
- [ ] Commit + push, last commit titled `docs(sprint3): close out`

---

## Sprint 3 sanity checklist

- [ ] **SSE auth** — anonymous `GET /events` → 401 with the standard envelope (no event stream opened)
- [ ] **SSE event filtering** — every event payload is the same `BookingDto` that `GET /bookings/{id}` would return; no fields the recipient's role couldn't see via the REST API
- [ ] **SSE heartbeat** — connection survives a 60s idle window (verified by integration test that consumes the stream for 90s)
- [ ] **SSE reconnect** — client reconnects within 30s after a backend bounce; e2e covers this
- [ ] **Pub/sub fan-out** — events published from one Spring instance reach SSE clients connected to a *different* instance (verified with two-instance Testcontainers test)
- [ ] **No event before commit** — `BookingEventPublisher.publish` runs in a `@TransactionalEventListener(phase = AFTER_COMMIT)` ; rolled-back writes never emit
- [ ] **Invitation token security** — token never stored plaintext; expired tokens return 410; revoked tokens return 410; double-accept returns 409 even within the TTL
- [ ] **Pending-invitation uniqueness** — two pending invites for the same email cannot both exist (partial unique index enforces this at the DB layer; CreateInvitation pre-checks for the friendly error code)
- [ ] **Admin endpoints gated** — every `/api/v1/admin/**` PATCH/POST/DELETE returns 403 for non-admins; verified in SecurityConfig integration test that walks the route list
- [ ] **LastAdmin invariant** — concurrent demotion of two admins (one each in two threads) leaves at least one admin standing; integration test under `Testcontainers`
- [ ] **SelfLockout** — admin demoting/disabling themselves returns 409 even if other admins exist (we want them to use a colleague's session for that, not foot-gun their way out of the system)
- [ ] **Audit log read-only** — no PATCH/DELETE endpoints on `audit_events` exist (compile-time guarantee: there's no controller for it)
- [ ] **Audit log PII** — `before`/`after` JSON is returned as-is and `patient_ref` redaction from Sprint 1 holds; integration test asserts the field doesn't appear in audit response bodies

---

## What's NOT in this sprint

- **Drag-drop schedule reorganization** — V2
- **Customer portal** — V2
- **Recurring routines** ("every Monday 9–11am") — V2
- **Email notifications on booking events** (only verify/reset/invitation emails) — V2
- **Google OAuth as alternative login** — V2
- **Multi-tenant** — never (V1 = single clinic)
- **Lighthouse measurement, cross-browser smoke, deploy to Hetzner** — Sprint 4 (M4 ship)
- **Self-service display name change** — Sprint 4
- **WebSocket transport** — never (SSE is the right tool)

If something on this list looks tempting mid-sprint, write it on a TODO and move on.

---

## Sprint 3 retro

_(filled at sprint close)_

**Coverage on the Sprint 3 surface (JaCoCo, `gradlew check`)**

_(table filled at close — same shape as Sprint 2)_

**What went well**
-

**What was harder than expected**
-

**Time spent vs estimate:** ___ days vs estimated 11

**What I'd carry into Sprint 4 planning**
-

**Sprint 3 sanity review:** all green? ___

**Did the SSE+Redis backplane decision hold up under the two-instance test?** yes / no

→ Then Sprint 4 — V1 ship: Lighthouse + cross-browser + deploy.
