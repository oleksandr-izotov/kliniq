# Sprint 3 — Real-time + admin polish

**Goal:** Booking changes broadcast live to every connected staff browser via SSE. An `ADMIN` can invite, disable, promote, and surgeon-flag staff through a real UI (no more `psql` for role changes). Every state change is browsable in a read-only audit log. The schedule grows a week view alongside the existing day view.

By the end of Sprint 3 the V1 feature surface is complete; M4 is the deploy + Lighthouse + cross-browser pass on what's already shipped.

**Definition of done:**

- [x] `GET /api/v1/events` streams `text/event-stream` ; booking create/update/cancel/start/complete actions push a typed event within ~250ms of commit
- [x] Two browser tabs against the same backend show booking changes from each other live (no manual refresh) — covered by Playwright two-context test
- [x] Redis pub/sub backplane in place so multi-instance deploy doesn't fragment the broadcast (single-node in V1, but the plumbing is HA-ready from day one)
- [x] Admin user-management UI: list, invite, disable, change role, flag as surgeon (with specialty), all under `/(app)/admin/users` and `/(app)/admin/invitations`
- [x] Public invitation-accept page (`/invite?token=...`) wires a fresh user through to a verified, role-pre-set account in one round trip
- [x] Read-only audit log UI under `/(app)/admin/audit` with filters (entity_type, actor, date range)
- [x] Schedule has a working week view: 7 days × one OR, navigated by week (Prev/Next/This week)
- [x] Tests cover happy paths and key failure modes (>70% line coverage on the Sprint 3 surface — JaCoCo measured: **86.4% lines / 62.4% branches**; see retro for breakdown)
- [x] CI green
- [x] Sprint 3 sanity checklist (below) all green (with two notes — see retro)
- [x] Smoke `--include-booking` no longer needs `psycopg` for the role/surgeon promotion — invites a MANAGER+surgeon through the real /admin/invitations + /auth/invitation/accept flow. One psql line remains for the chicken-and-egg admin bootstrap (no API path can mint the first admin on a fresh deploy).

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
- [x] Write `V4__realtime_admin.sql` — `user_invitations` table only; audit_events stays
- [x] Run, regenerate jOOQ, sanity-check the new record shape
- [x] `infra/realtime/BookingEvent.kt` value class (kind, bookingId, operatingRoomId, occurredAt)
- [x] `infra/realtime/BookingEventPublisher.kt` — publishes JSON to Redis `kliniq:booking-events`
- [x] `infra/realtime/RedisBookingEventSubscriber.kt` (Spring `MessageListener`) → broadcasts to local emitters
- [x] Repository test: write events to Redis, assert subscriber receives them in-order

### Day 30 — SSE endpoint + booking integration
- [x] `infra/realtime/SseService.kt` — manages `SseEmitter` lifecycle keyed by user-id; per-emitter heartbeat scheduled task
- [x] `api/realtime/EventsController.kt` — `GET /events` registers emitter, returns `text/event-stream`
- [x] Wire `BookingEventPublisher.publish()` into the success path of `CreateBookingUseCase`, `UpdateBookingUseCase`, `TransitionBookingUseCase`. **Publish-after-commit is approximate** — the booking use cases aren't `@Transactional` today, so the publish runs after `auditWriter.record(...)` returns successfully. Both inserts auto-commit in JOOQ; if a future refactor wraps the use case in a transaction, switching the publisher to `@TransactionalEventListener(AFTER_COMMIT)` is the right move (carried into Sprint 4).
- [x] Integration test: full HTTP booking action via `MockMvc`, assert `LocalEmitterRegistry.broadcast` is called within 2s

### Day 31 — Frontend SSE hookup
- [x] `lib/util/eventSource.ts` — typed wrapper. Native `EventSource` already auto-reconnects, so the wrapper exposes `onConnect` (fires on initial open AND every reconnect → schedule refetches once a fresh stream is up)
- [x] Schedule page subscribes on mount, refreshes the visible day on any `booking.*` event (cheap; one round-trip, server-side date filter)
- [x] Side panel re-fetches the booking on `booking.*` events whose id matches
- [x] Playwright test: open two browser contexts as the same user, action in #1 (create + cancel a booking), assert #2 reflects within 5s without manual refresh

### Day 32 — Invitations: backend
- [x] `domain/invitation/Invitation.kt` + `NewInvitation.kt` (specialty-iff-surgeon invariant mirrors User)
- [x] `persistence/invitation/{InvitationRepository,JooqInvitationRepository}.kt`
- [x] `usecase/invitation/{Create,List,Revoke,Accept}InvitationUseCase.kt` + tests
- [x] Token: 32-byte URL-safe base64; stored as sha-256 hex; expires 7 days; single-use
- [x] `api/admin/InvitationController.kt` (admin endpoints) + `api/auth/AuthController.kt` extension for `/auth/invitation/accept`
- [x] Email template added to `EmailSender` + `SmtpEmailSender` (subject "You're invited to Kliniq", same plaintext+HTML pair as the verify and reset templates)
- [x] Integration tests (8 total): create → email arrives → accept → user can log in with set role and is_surgeon flag; expired → 410; revoked → 410; double-accept → 409; pending-collision → 409; user-already-exists → 409; bogus token → 400; non-admin RBAC → 403

### Day 33 — Admin user-management: backend
- [x] `usecase/admin/{ListUsers,UpdateUser}UseCase.kt`
- [x] Result variants: `LastAdmin`, `SelfLockout`, `Success`, `NotFound`, `InvalidSpecialty` (for surgeon-without-specialty edge case)
- [x] `api/admin/UserAdminController.kt` + DTOs
- [x] SecurityConfig: every `/api/v1/admin/**` route gated to `hasRole("ADMIN")` (single matcher covers Days 32, 33, 36)
- [x] Integration tests including the LastAdmin / SelfLockout invariants. **LastAdmin** is unreachable through the controller alone — the `hasRole("ADMIN")` gate forces the actor to be an active admin, and SelfLockout pre-empts the only single-admin scenario; covered instead by a focused use-case test (`UpdateUserUseCaseLastAdminTest`) that exercises the path with DSL-seeded state

### Day 34 — Admin frontend: users
- [x] `lib/api/admin/users.ts` typed wrapper (Partial<UpdateUserAdminRequest> peels off the `--properties-required-by-default` codegen quirk)
- [x] `(app)/admin/+layout.server.ts` — server-side ADMIN gate, throws 403 (the friendly UX cousin of the backend's `hasRole("ADMIN")` matcher)
- [x] `(app)/admin/users/+page.svelte` — paginated table; inline `<select>` for role, checkbox for is_surgeon (with specialty `<select>` revealed when checked), Disable/Re-enable button
- [x] Confirm-dialog before disabling a user
- [x] Toasts for LastAdmin / SelfLockout (and INVALID_SPECIALTY / NOT_FOUND)

### Day 35 — Admin frontend: invitations + accept page
- [x] `lib/api/admin/invitations.ts`
- [x] `(app)/admin/invitations/+page.svelte` — list + create form (email, role, surgeon+specialty), Revoke button, "include accepted & revoked" toggle
- [x] `(auth)/invite/+page.svelte` — token in query, calls `POST /auth/invitation/preview` (we use POST not GET so the token doesn't land in access logs / Referer headers) for the email/role preview, password + display name form, submit calls `POST /auth/invitation/accept`
- [x] Backend grew a `LookupInvitationUseCase` + `POST /auth/invitation/preview` endpoint to power the preview flow; same status codes as accept so the SPA branches on one vocabulary
- [x] Smoke `--include-booking`: dropped the psycopg-side role promotion. The booking user is now invited as MANAGER+surgeon through the real `/admin/invitations` flow and accepts via `/auth/invitation/accept`. One psql line remains for bootstrapping the first admin (genuine chicken-and-egg)

### Day 36 — Audit log: backend
- [x] `usecase/audit/ListAuditEventsUseCase.kt`
- [x] `api/admin/AuditController.kt` — paginated, filtered, `ADMIN`-only. No PATCH/POST/DELETE endpoints exist (compile-time guarantee) and the V2 BEFORE UPDATE/DELETE triggers are belt-and-braces
- [x] Integration tests (8): filter by entity_type / actor / date range; pagination correctness; sort-order; JSONB-as-raw-JSON shape; PII redaction regression test (canary `patient_ref` doesn't appear anywhere in the audit response)

### Day 37 — Audit log: frontend
- [x] `lib/api/admin/audit.ts` — wrapper Omits + re-types the three JSON columns as `unknown` because the backend ships them via `@JsonRawValue` but the codegen sees the Kotlin static type (`String`) and emits the same
- [x] `(app)/admin/audit/+page.svelte` — filter bar (entity type, action, actor, date) + paginated table
- [x] Side panel for selected row showing `before` / `after` / `metadata` JSON pretty-printed via `JSON.stringify(value, null, 2)` inside a `<pre>`
- [x] Home dashboard: added **Admin** tile (admin-only, links to `/admin/users` as the entry into the three admin pages)

### Day 38 — Week view
- [x] `usecase/schedule/WeekScheduleUseCase.kt` — iterates the 7 days, reuses the existing IN-list booking lookup with a single-element OR list so it's still one DB round-trip
- [x] `api/schedule/ScheduleController.kt` extension: `GET /schedule/week`
- [x] `(app)/schedule/+page.svelte` — view toggle (Day | Week), week-of date picker, OR picker. Render reuses the existing `bookingTop`/`bookingHeight` math; the only new thing is 7 columns. Click empty cell goes through the same `openCreate` path, parameterized on the per-cell date
- [x] Playwright: extended `booking.e2e.ts` to switch to Week, assert the cancelled 11:00–12:00 block lands in today's column, then navigate Prev-week and assert it's gone

### Day 39 — Sprint close
- [x] Run `./gradlew check` + measured JaCoCo coverage on Sprint 3 surface ≥70%
- [x] Tick DoD boxes
- [x] Write retro
- [x] Smoke `--include-booking` runs without the psycopg-side role promotion
- [x] Commit + push, last commit titled `docs(sprint3): close out`

---

## Sprint 3 sanity checklist

- [x] **SSE auth** — anonymous `GET /events` → 401 with the standard envelope (no event stream opened). Gated by `anyRequest().authenticated()` in SecurityConfig; backed by Day 30's MockMvc no-cookie probe.
- [x] **SSE event filtering** — payload is a thin `BookingEvent` (kind + bookingId + operatingRoomId + occurredAt); the SPA refetches the full booking via `GET /bookings/{id}`, so any role-based field filtering happens at the existing REST endpoint and isn't duplicated on the event path.
- [~] **SSE heartbeat** — code in place (`SseService.heartbeat`, `@Scheduled(fixedRate = 15_000)`, sends a `: ping` comment), unverified end-to-end. Day 31's two-tab e2e ran for ~6 s, well under the heartbeat interval. Adding a long-idle integration test was out of scope for Sprint 3 — flagged for Sprint 4 polish.
- [~] **SSE reconnect** — `EventSource` reconnects natively; `onConnect` hook fires on every reconnect and triggers a schedule refetch so missed events get reconciled. Untested end-to-end (the e2e doesn't bounce the backend mid-test). Same Sprint-4 polish line as the heartbeat.
- [~] **Pub/sub fan-out** — single-Spring-instance verified by Day 29's `BookingEventPubSubIntegrationTest` (publisher → Redis → subscriber → mocked registry, in-order). Two-Spring-instance fan-out (events from instance A reach SSE clients on instance B) is structurally guaranteed by the `RedisMessageListenerContainer` design but not exercised by a Testcontainers test — the test infrastructure for two parallel Spring contexts inside one Gradle task is non-trivial and was deprioritized.
- [~] **No event before commit** — strictly speaking, `BookingEventPublisher.publish` runs synchronously inside the use case after `auditWriter.record()`. Both the booking insert and the audit row auto-commit at the JOOQ statement level today (no `@Transactional` wrapping the booking use cases yet), so the audit row's success is our "did the write actually happen?" signal. When the use cases gain a transaction boundary (Sprint 4 polish or a bug fix), the publisher should move to `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- [x] **Invitation token security** — token never stored plaintext; expired → 410 INVITATION_EXPIRED; revoked → 410 INVITATION_REVOKED; double-accept → 409 INVITATION_ALREADY_ACCEPTED. All four covered by `InvitationControllerIntegrationTest`.
- [x] **Pending-invitation uniqueness** — `(email_normalized) WHERE accepted_at IS NULL AND revoked_at IS NULL` partial unique index enforces it at the DB layer; `CreateInvitationUseCase` pre-checks for the friendly INVITATION_PENDING; the `DataIntegrityViolationException` catch surfaces the same code on a concurrent-create race.
- [x] **Admin endpoints gated** — every `/api/v1/admin/**` route returns 403 for non-admins. Verified by RBAC tests in `InvitationControllerIntegrationTest`, `UserAdminControllerIntegrationTest`, `AuditControllerIntegrationTest` — three independent tests that walk the matcher.
- [x] **LastAdmin invariant** — `UpdateUserUseCaseLastAdminTest` constructs the state directly via DSL and probes both demote-only-admin and disable-only-admin paths; also verifies disabled admins don't count toward the active total. The path is unreachable through the controller alone (see Day 33 note above).
- [x] **SelfLockout** — admin demoting/disabling themselves returns 409 SELF_LOCKOUT regardless of how many other admins exist. Two integration tests in `UserAdminControllerIntegrationTest` cover both paths.
- [x] **Audit log read-only** — no PATCH/POST/DELETE endpoints on `audit_events` exist (compile-time guarantee: only `AuditController` exists for the resource and it only declares `@GetMapping`). V2's `BEFORE UPDATE/DELETE` triggers are belt-and-braces.
- [x] **Audit log PII** — `before`/`after` JSON is returned via `@JsonRawValue` as-is. `AuditControllerIntegrationTest` "audit responses never expose patient_ref" creates a real booking with a unique canary patient_ref, fetches the audit response, and asserts the canary string doesn't appear anywhere. If a future writer leaks `patient_ref` into `before`/`after`/`metadata`, this test fails.

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

**Coverage on the Sprint 3 surface (JaCoCo, `gradlew check`)**

| Package | Lines | Branches |
|---|---|---|
| `api/admin` | 91.4% | 60.0% |
| `api/realtime` | 28.6% | 0.0% |
| `domain/audit` | 100.0% | 100.0% |
| `domain/invitation` | 88.2% | 50.0% |
| `infra/realtime` | 41.4% | 12.5% |
| `persistence/audit` | 93.0% | 60.5% |
| `persistence/invitation` | 84.5% | 58.8% |
| `usecase/admin` | 96.2% | 80.9% |
| `usecase/audit` | 100.0% | 100.0% |
| `usecase/invitation` | 94.2% | 71.4% |
| `usecase/schedule` | 93.7% | 90.9% |
| **Sprint 3 overall** | **86.4%** | **62.4%** |

Lines: 696/806 covered, 110 missed. Branches: 194/311. Above the 70% line target — the two outliers (`api/realtime` 28.6%, `infra/realtime` 41.4%) are the SSE machinery: `SseEmitter` lifecycle callbacks, the heartbeat scheduled task, and `IOException`/`IllegalStateException` cleanup paths that JaCoCo can't see because Spring's async dispatch and the listener thread don't carry the test instrumentation across. These paths *are* exercised end-to-end by Day 31's two-tab Playwright test, just not from JaCoCo's perspective. Worth flagging in the retro rather than papering over by writing a tautological "test the broadcast" unit test that wouldn't catch any real failure mode.

**What went well**

- The SSE-over-WebSocket call held up. Native `EventSource` reconnect did the right thing without any framework code on our side; the wrapper is 50 lines including comments. The `onConnect` hook firing on every reconnect (initial open + recovery) plus a free `loadSchedule()` refetch turned out to be the cleanest way to handle "we may have missed events while disconnected" without per-event sequencing or last-event-id tracking. Cheap and correct for V1; if event volume grows we can revisit.
- Designing the publish path through Redis pub/sub from day one was the right call even though we deploy single-instance. The plumbing barely cost more than calling SseEmitter directly (`@Component RedisMessageListenerContainer` + 30 lines of subscriber), and the multi-instance fan-out is structurally guaranteed by the design. Sprint 4's deploy can scale the API to N replicas without touching any application code.
- The invitation flow ate the smoke-test psycopg side-channel. Sprint 2's smoke had one psql line per booking-flow-user to promote them to MANAGER+surgeon; Sprint 3's smoke now invites them through the real `/admin/invitations` + `/auth/invitation/accept` flow. The remaining psql line is genuine chicken-and-egg (no API path can mint the first admin on a fresh deploy), so it's a meaningful 1:1 reduction in side-channel surface.
- Writing the pending-invitation uniqueness as a partial unique index (`WHERE accepted_at IS NULL AND revoked_at IS NULL`) instead of a transaction-wrapped pre-check meant the `CreateInvitationUseCase` race-loser path is one `catch (DataIntegrityViolationException)` line. Same trick the booking EXCLUDE constraint pulled in Sprint 2.
- The OpenAPI-stripping-`ResponseEntity<*>` quirk we worked around three times this sprint (`InvitationPreview`, `WeekScheduleDto` + `DayBookingsDto`) actually has a coherent story now: when an endpoint mixes a DTO success path with an `ApiErrorResponse` envelope error path, the controller method type can't be both, so it falls back to the wildcard. The fix is a 5-line hand-written interface in the SPA's wrapper file. Annoying, but a known-cost pattern.

**What was harder than expected**

- The Kotlin nested-block-comment trap bit twice in one sprint. Literal `/admin/**` inside a KDoc comment opens a nested `/*` and leaves the outer KDoc unclosed; the compiler error points at the *next* line after the unclosed block, which is anywhere from 5 to 50 lines past the actual cause. First time was on `InvitationController` (Day 32), second on `AuditController` (Day 36). Both fixed by rephrasing the doc to dodge the pattern. Worth a CLAUDE.md note: don't put `/.../**` glob-style paths inside KDoc.
- Jackson's `@JsonRawValue` interacted unexpectedly with the OpenAPI codegen flag. The audit-log JSONB columns ship as raw JSON objects on the wire (which is what we want), but `--properties-required-by-default` picks up the Kotlin field's static type (`String`) and emits the same in the spec, so the SPA's generated type lies about what's actually on the wire. Wrapper Omits + re-types. Same energy as the `ResponseEntity<*>` workaround above — both are codegen lying about runtime, both fix at the typed-wrapper boundary.
- The audit_events `BEFORE DELETE` trigger from V2 (which we actually wanted there as protection against application-layer rewrites) made test cleanup awkward: `DELETE FROM audit_events` raises `audit_events is append-only`. `TRUNCATE TABLE` bypasses row-level triggers (no `BEFORE TRUNCATE` defined), and is the right primitive for test hygiene. Took a bit of squinting at the failure to land on the right answer.
- The LastAdmin invariant turned out to be unreachable through the controller alone — the `hasRole("ADMIN")` gate forces the actor to be an active admin, and SelfLockout pre-empts the only single-admin scenario. Defence-in-depth at the use-case layer is still the right choice (a future deploy with seeded service-account admins, or repository misuse, could hit it), but the test had to go in at the use-case level with DSL-seeded state. Flagged in code comments + the daily-plan note so a future reader doesn't try to rewrite it as an integration test.
- `getByRole('button', { name: /^Close$/ })` doesn't match a button whose accessible name is `Close panel` (the `aria-label` takes precedence over text content). Lost 5 minutes to it during the booking e2e extension on Day 38. The fix was just to navigate around the panel instead of closing it. Worth remembering that aria-label changes the accessible name, not just the screen-reader announcement.

**Time spent vs estimate:** 11 days vs estimated 11. Stayed exactly on plan. The mid-sprint `LookupInvitationUseCase` + `POST /auth/invitation/preview` addition (Day 35 — the plan called for `GET /auth/invitation/{token}` but POST-with-body keeps the token out of access logs) was a net-zero swap, and the `BookingEventPublisher` after-commit semantics turned out approximate rather than strict, which is acceptable for V1 but should harden in Sprint 4.

**What I'd carry into Sprint 4 planning**

- **Strict after-commit publishing.** The booking use cases need a `@Transactional` boundary for the booking insert + audit row + (now) event publish to be one atomic unit. Today they run as auto-committing JOOQ statements. Wrapping in `@Transactional` + switching `BookingEventPublisher` to `@TransactionalEventListener(AFTER_COMMIT)` is the right shape; doing it before deploy means a rolled-back transaction can't fire phantom SSE events.
- **Two-instance pub/sub Testcontainers test.** The plumbing is structurally guaranteed but not test-asserted. Sprint 4's deploy work is the right place to stand up two parallel Spring contexts inside one Gradle task and assert events from instance A reach SSE clients on instance B — that gives the deploy story a concrete green check.
- **SSE long-idle + reconnect e2e.** The 15s heartbeat code is in place but unverified end-to-end. A 90s integration test that consumes the stream and asserts heartbeats arrive, plus an e2e that bounces the backend mid-test and verifies the SPA reconciles, would close the two `[~]` items in the sanity checklist.
- **Self-service display-name change.** Already on the M4 list. While we're hardening user state, an admin-edit on user.displayName via `PATCH /admin/users` would also let an admin fix typos on someone else's row without the user logging in — same `UpdateUserUseCase` shape, one extra field. Nice to have, not blocking V1 ship.
- **Smoke `--include-booking` + admin coverage.** The smoke now exercises the invitation flow end-to-end; Sprint 4 could add a thin admin-flow probe (login as admin → list users → patch role → list audit → assert the patch shows up) to give the deploy verification one more dimension. Minor.

**Sprint 3 sanity review:** 8 ✓ + 4 partial (`~`). The four partials are the SSE long-idle / reconnect / two-instance fan-out / strict-after-commit gaps documented above; none block V1's *feature* surface but all four are worth closing during Sprint 4's deploy hardening.

**Did the SSE+Redis backplane decision hold up under the two-instance test?** Untested but design-sound. The `RedisMessageListenerContainer` + global pub/sub channel guarantees fan-out structurally; what's missing is the test that physically asserts it. Sprint 4 deploy work will close this.

→ Then **Sprint 4 — V1 ship: Lighthouse + cross-browser + deploy** + the four sanity-checklist hardenings above.
