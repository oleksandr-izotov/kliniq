# Sprint 2 — Booking core

**Goal:** A `MANAGER` can create operating rooms, a `STAFF`+ user can create / edit / cancel bookings against those rooms with no overlap on the same room, and a `/schedule` day view shows the result. Audit log captures every state change. Clinic-wide settings (working hours, default duration, time zone) are editable by `ADMIN`.

**Definition of done:**

- [ ] All endpoints in [API endpoints](#api-endpoints) below behave correctly
- [ ] Tests cover happy paths and key failure modes (>70% line coverage on `domain/`, `usecase/`, `persistence/` for the booking surface — JaCoCo measured)
- [ ] OR + Booking + schedule UI exists, looks decent (using shadcn-svelte primitives)
- [ ] Postgres `EXCLUDE USING gist` constraint rejects overlapping bookings at the DB level — the application layer never opens a window where two conflicting bookings can both commit
- [ ] OpenAPI 3.1 spec served at `/v3/api-docs` ; `apps/web` types generated from it via `openapi-typescript`, hand-written DTO mirroring removed
- [ ] CI green
- [ ] DX: a single `pnpm dev:all` (or `make dev`) brings up compose + bootRun + vite with combined log streams
- [ ] README "Quick start" gets a fresh checkout to a running app in 5 commands
- [ ] Booking-domain sanity checklist (below) all green

**Estimated effort:** 2-3 weeks at chaotic pace.

**Prereq:** Sprint 1 done.

---

## Decisions locked (no debate this sprint)

- **Booking creation:** any `STAFF`+ user; in V1 staff are not restricted to bookings they own.
- **Time zone:** clinic-wide single zone stored in `clinic_settings.timezone` (IANA name, default `Europe/Berlin`). All `TIMESTAMPTZ` values stored as UTC; the SPA renders in the clinic zone. No per-user timezone in V1.
- **Cancellation:** sets `status='CANCELLED'`. No row deletes. Cancelled bookings stop blocking the OR slot (the `EXCLUDE` constraint filters by `status` predicate).
- **`patient_ref`:** opaque, application-generated string (pattern: `P-{yyyy}-{NNN}` per clinic per year). Stored in plaintext, never written to logs / metadata, never returned to non-staff callers.
- **OpenAPI:** introduced this sprint. `springdoc-openapi-starter-webmvc-ui` exposes `/v3/api-docs` (JSON) and `/swagger-ui.html` (browser). Frontend types are generated via `openapi-typescript` into `apps/web/src/lib/api/generated.ts` ; hand-written shapes in `lib/auth/api.ts` are migrated.

---

## Schema migration `V3__booking_core.sql`

```sql
-- ----------------------------------------------------------------------------
-- operating_rooms
-- ----------------------------------------------------------------------------
CREATE TABLE operating_rooms (
    id          UUID PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE CHECK (length(code) BETWEEN 1 AND 20),
    name        TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    status      TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','MAINTENANCE','RETIRED')),
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER operating_rooms_set_updated_at BEFORE UPDATE ON operating_rooms
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ----------------------------------------------------------------------------
-- bookings
-- ----------------------------------------------------------------------------
CREATE TABLE bookings (
    id                  UUID PRIMARY KEY,
    operating_room_id   UUID NOT NULL REFERENCES operating_rooms(id) ON DELETE RESTRICT,
    surgeon_id          UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_by_id       UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    starts_at           TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ NOT NULL,
    op_type             TEXT NOT NULL CHECK (length(op_type) BETWEEN 1 AND 200),
    patient_ref         TEXT NOT NULL CHECK (patient_ref ~ '^P-[0-9]{4}-[0-9]{3,}$'),
    status              TEXT NOT NULL DEFAULT 'SCHEDULED'
                        CHECK (status IN ('SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED')),
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT bookings_time_range_valid CHECK (ends_at > starts_at),
    -- Surgeon must actually be a surgeon. Enforced via CHECK reading users.is_surgeon
    -- isn't possible (CHECK can't reference other tables); enforced at app layer
    -- by the use case's validation.
    --
    -- The headline invariant: no two ACTIVE bookings overlap on the same OR.
    -- btree_gist (loaded in V1) lets us combine UUID equality with tstzrange
    -- overlap in a single GiST index — the constraint and the conflict-finding
    -- query share the same index, so writes and reads stay cheap.
    CONSTRAINT bookings_no_overlap EXCLUDE USING gist (
        operating_room_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('SCHEDULED','IN_PROGRESS'))
);
CREATE INDEX bookings_operating_room_starts_idx ON bookings(operating_room_id, starts_at);
CREATE INDEX bookings_surgeon_starts_idx        ON bookings(surgeon_id, starts_at);
CREATE INDEX bookings_status_idx                ON bookings(status);
CREATE TRIGGER bookings_set_updated_at BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ----------------------------------------------------------------------------
-- clinic_settings (single-row table; row id = 1 enforced by CHECK)
-- ----------------------------------------------------------------------------
CREATE TABLE clinic_settings (
    id                          INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    name                        TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    timezone                    TEXT NOT NULL DEFAULT 'Europe/Berlin',
    working_hours_start         TIME NOT NULL DEFAULT '08:00',
    working_hours_end           TIME NOT NULL DEFAULT '20:00',
    default_booking_minutes     INT  NOT NULL DEFAULT 60 CHECK (default_booking_minutes BETWEEN 5 AND 1440),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT clinic_settings_hours_valid CHECK (working_hours_end > working_hours_start)
);
INSERT INTO clinic_settings (id, name) VALUES (1, 'Kliniq');
CREATE TRIGGER clinic_settings_set_updated_at BEFORE UPDATE ON clinic_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

After migration: `cd apps/api && ./gradlew generateJooq` regenerates `com.kliniq.db` against the new schema.

---

## API endpoints

All under `/api/v1/*`, JSON in/out, standard `{ code, message, fieldErrors? }` envelope on errors, every state-changing call requires the CSRF header.

### Operating rooms

| Method | Path | Auth | Body / response |
|---|---|---|---|
| `GET`    | `/operating-rooms`           | any auth | `[OperatingRoomDto]` |
| `GET`    | `/operating-rooms/{id}`      | any auth | `OperatingRoomDto` or 404 |
| `POST`   | `/operating-rooms`           | `MANAGER`+ | `CreateOrRequest` → `OperatingRoomDto` |
| `PATCH`  | `/operating-rooms/{id}`      | `MANAGER`+ | `UpdateOrRequest` (partial) → `OperatingRoomDto` |
| `DELETE` | `/operating-rooms/{id}`      | `MANAGER`+ | 409 if any active bookings reference it; soft-archive via `status='RETIRED'` |

### Bookings

| Method | Path | Auth | Body / response |
|---|---|---|---|
| `GET`    | `/bookings`                  | `STAFF`+ | filters: `operatingRoomId`, `surgeonId`, `from`, `to`, `status`. Returns `[BookingDto]`. |
| `GET`    | `/bookings/{id}`             | `STAFF`+ | `BookingDto` or 404 |
| `POST`   | `/bookings`                  | `STAFF`+ | `CreateBookingRequest` → 201 `BookingDto` ; 409 `BOOKING_CONFLICT` with `conflictingBookingId` |
| `PATCH`  | `/bookings/{id}`             | `STAFF`+ | `UpdateBookingRequest` (partial: time / type / notes; surgeon / OR can change) → 200 `BookingDto` ; 409 on conflict |
| `POST`   | `/bookings/{id}/cancel`      | `STAFF`+ | `{ reason? }` → 200 `BookingDto` (status now CANCELLED) |
| `POST`   | `/bookings/{id}/start`       | `STAFF`+ | transitions SCHEDULED → IN_PROGRESS |
| `POST`   | `/bookings/{id}/complete`    | `STAFF`+ | transitions IN_PROGRESS → COMPLETED |

### Schedule

| Method | Path | Auth | Response |
|---|---|---|---|
| `GET`    | `/schedule?date=YYYY-MM-DD`  | `STAFF`+ | `{ date, timezone, operatingRooms: [{ id, code, name, bookings: [BookingDto] }] }` — pre-grouped per OR for the UI |

### Clinic settings

| Method | Path | Auth | Body / response |
|---|---|---|---|
| `GET`    | `/clinic/settings`           | any auth | `ClinicSettingsDto` |
| `PATCH`  | `/clinic/settings`           | `ADMIN`   | `UpdateClinicSettingsRequest` (partial) → `ClinicSettingsDto` |

### Audit feed

Out of scope for Sprint 2 — see `What's NOT in this sprint`. The audit table keeps growing per existing `AuditWriter` invocations.

### Rate limits (added to `RateLimitFilter.LIMITS`)

| Endpoint | Limit |
|---|---|
| `POST /bookings` | 60 / min / IP |
| `PATCH /bookings/*` | 60 / min / IP |
| `POST /operating-rooms` | 30 / min / IP |

---

## Frontend pages

- **`(app)/schedule/+page.svelte`** — day view. Date picker (defaults to today). One column per `ACTIVE` OR; bookings rendered as time-positioned blocks. Click empty slot → "New booking" modal. Click existing block → side-panel with edit / cancel / start / complete actions.
- **`(app)/operating-rooms/+page.svelte`** — list of ORs (manager+ only). Add / rename / change status / archive.
- **`(app)/settings/clinic/+page.svelte`** — clinic-wide settings (admin only). Reuses the `(app)/settings/security` chrome.

Promote home from "welcome card" placeholder to a small dashboard pointing at `/schedule`, `/settings/security`, and (manager+) `/operating-rooms` and `/settings/clinic`.

---

## Day-by-day plan

### Day 16 — Schema + DX kickoff
- [ ] Write `V3__booking_core.sql`
- [ ] Run, regenerate jOOQ, sanity-check generated `BookingsRecord` shape
- [ ] Add `pnpm dev:all` script at repo root that wraps compose + bootRun + vite (use `concurrently` or a 30-line bash script)
- [ ] Spring DevTools profile in `application-local.yml` for hot Kotlin reload

### Day 17 — Operating rooms
- [ ] `domain/or/OperatingRoom.kt`, `OperatingRoomStatus.kt`, `NewOperatingRoom.kt`
- [ ] `persistence/or/OperatingRoomRepository.kt` interface + `JooqOperatingRoomRepository.kt`
- [ ] Repository tests (CRUD + unique code)
- [ ] `usecase/or/{Create,Update,List,Archive}OperatingRoomUseCase.kt` + tests
- [ ] `api/or/OperatingRoomController.kt` + DTOs + integration tests

### Day 18 — Booking domain + repo
- [ ] `domain/booking/Booking.kt`, `BookingStatus.kt`, `NewBooking.kt`, `BookingTimeRange.kt` (value object)
- [ ] `persistence/booking/BookingRepository.kt` + `JooqBookingRepository.kt`
- [ ] Repository tests, especially around `findActiveOverlappingFor(operatingRoomId, range, excluding=...)`

### Day 19 — Booking creation + conflict detection
- [ ] `usecase/booking/CreateBookingUseCase.kt` (validates surgeon-is-surgeon, range valid, within working hours, fails fast on conflicts before INSERT)
- [ ] Catch Postgres `ExclusionViolation` to surface `BOOKING_CONFLICT` cleanly even under concurrent writes
- [ ] `api/booking/BookingController.kt` `POST /bookings` + tests

### Day 20 — Booking lifecycle
- [ ] `usecase/booking/{Update,Cancel,Start,Complete}BookingUseCase.kt` + lifecycle invariants
- [ ] PATCH + POST cancel/start/complete endpoints + tests
- [ ] Audit events: `booking.created/updated/cancelled/started/completed`

### Day 21 — Schedule view
- [ ] `usecase/schedule/DayScheduleUseCase.kt` returning `DaySchedule(date, timezone, ors=[OrSchedule(or, bookings)])`
- [ ] `GET /schedule?date=...` endpoint + tests
- [ ] Edge cases: empty day, OR with no bookings, OR with status=MAINTENANCE excluded by default

### Day 22 — Clinic settings
- [ ] `clinic_settings` repository + use case + endpoints + tests
- [ ] Working-hours validation feeds into `CreateBookingUseCase`

### Day 23 — OpenAPI emit + frontend codegen
- [ ] Add `springdoc-openapi-starter-webmvc-ui` dep, configure `/v3/api-docs`
- [ ] Annotate controllers + DTOs (only what's necessary; springdoc auto-discovers most)
- [ ] Add `apps/web` script `pnpm gen:api` running `openapi-typescript https://localhost:8443/v3/api-docs -o src/lib/api/generated.ts`
- [ ] Migrate `lib/auth/api.ts` to use generated types; remove hand-written ones
- [ ] Document the gen step in README quick-start

### Day 24 — Frontend: ORs + clinic settings
- [ ] `(app)/operating-rooms/+page.svelte` — list, add (modal), edit, archive
- [ ] `(app)/settings/clinic/+page.svelte` — admin form

### Day 25-26 — Frontend: schedule + booking modal
- [ ] `(app)/schedule/+page.svelte` — date picker, time grid, OR columns, booking blocks
- [ ] `BookingFormDialog.svelte` (create + edit modes, conflict-aware: shows the occluding booking inline)
- [ ] Side panel for selected booking (edit / cancel / start / complete)
- [ ] Promote home to a small landing dashboard

### Day 27 — Polish + Playwright
- [ ] Dark mode toggle (we already have CSS vars)
- [ ] Mobile breakpoint sanity (login / schedule / OR list at 375px)
- [ ] `e2e/booking.e2e.ts` Playwright: create OR → create booking → conflict on overlap → cancel → schedule view shows expected state
- [ ] Update `scripts/smoke_test.py` with a `--include-booking` flag covering the happy path

### Day 28 — Sprint close
- [ ] Run `./gradlew check` + measured JaCoCo coverage on booking surface ≥70%
- [ ] Tick DoD boxes
- [ ] Write retro
- [ ] Commit + push, last commit titled `docs(sprint2): close out`

---

## Booking-domain sanity checklist (Sprint-2 analog of the Sprint-1 ASVS list)

- [ ] **No-overlap invariant** holds under concurrent writes (rely on `EXCLUDE`, don't TOCTOU-check in app code)
- [ ] **Cancelled bookings free the slot** — `EXCLUDE` predicate filters on `status IN ('SCHEDULED','IN_PROGRESS')`
- [ ] **End > start** enforced both at app and DB layers
- [ ] **Range respects clinic working hours** — soft validation in app layer (not DB)
- [ ] **Surgeon must be `is_surgeon = true`** — checked in CreateBookingUseCase against UserRepository
- [ ] **Operating room must be `status = ACTIVE`** to accept new bookings
- [ ] **PII discipline** — `patient_ref` never appears in slf4j logs, audit metadata, or non-staff API responses
- [ ] **Lifecycle transitions** are state-machine-checked (can't COMPLETE a CANCELLED booking, etc.)
- [ ] **Authorization** — every `STAFF`+ endpoint checks the SecurityContext authority; `MANAGER`+/`ADMIN` endpoints are gated in `SecurityConfig`
- [ ] **Rate limits** in place for booking write endpoints (60/min/IP for POST/PATCH bookings, 30/min for OR writes)
- [ ] **Audit chain** — every state change writes an `AuditEntry` in the same transaction
- [ ] **Time zone correctness** — booking times round-trip without drift; assertion in integration test using `Europe/Berlin` clinic TZ vs UTC storage

---

## What's NOT in this sprint

- **SSE / real-time** — Sprint 3 (M3 milestone)
- **Week view, drag-drop schedule** — Sprint 3 (week) / V2 (drag-drop)
- **Recurring routines** ("every Monday 9-11am") — V2
- **Customer portal** — V2
- **Email notifications on booking events** — V2
- **Audit log UI** — Sprint 3 (queries via psql for now)
- **Admin user-management UI** (invite/disable staff) — Sprint 3 (seed admins via migration in V1)
- **Multi-tenant** — never (V1 = single clinic)
- **Patient PII** — never (anonymous `patient_ref` only)

If something on this list looks tempting mid-sprint, write it on a TODO and move on.

---

## Sprint 2 retro

_(filled at sprint close)_

**What went well**
-

**What was harder than expected**
-

**Time spent vs estimate:** ___ days vs estimated 13

**What I'd carry into Sprint 3 planning**
-

**Booking-domain sanity review:** all green? ___

**Did the openapi-typescript generated types remove enough hand work to justify the codegen step?** yes / no

→ Then move on to Sprint 3 — Real-time + admin polish.
