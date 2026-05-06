# Sprint 2 — Booking core

**Goal:** A `MANAGER` can create operating rooms, a `STAFF`+ user can create / edit / cancel bookings against those rooms with no overlap on the same room, and a `/schedule` day view shows the result. Audit log captures every state change. Clinic-wide settings (working hours, default duration, time zone) are editable by `ADMIN`.

**Definition of done:**

- [x] All endpoints in [API endpoints](#api-endpoints) below behave correctly
- [x] Tests cover happy paths and key failure modes (>70% line coverage on `domain/`, `usecase/`, `persistence/` for the booking surface — JaCoCo measured: **91.8% lines / 62.9% branches** across the Sprint 2 surface; see retro for breakdown)
- [x] OR + Booking + schedule UI exists, looks decent (using shadcn-svelte primitives)
- [x] Postgres `EXCLUDE USING gist` constraint rejects overlapping bookings at the DB level — the application layer never opens a window where two conflicting bookings can both commit
- [x] OpenAPI 3.1 spec served at `/v3/api-docs` ; `apps/web` types generated from it via `openapi-typescript`, hand-written DTO mirroring removed
- [x] CI green
- [x] DX: a single `pnpm dev:all` (or `make dev`) brings up compose + bootRun + vite with combined log streams
- [x] README "Quick start" gets a fresh checkout to a running app in 5 commands
- [x] Booking-domain sanity checklist (below) all green

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
- [x] Write `V3__booking_core.sql`
- [x] Run, regenerate jOOQ, sanity-check generated `BookingsRecord` shape
- [x] Add `pnpm dev:all` script at repo root that wraps compose + bootRun + vite (use `concurrently` or a 30-line bash script)
- [x] Spring DevTools profile in `application-local.yml` for hot Kotlin reload

### Day 17 — Operating rooms
- [x] `domain/or/OperatingRoom.kt`, `OperatingRoomStatus.kt`, `NewOperatingRoom.kt`
- [x] `persistence/or/OperatingRoomRepository.kt` interface + `JooqOperatingRoomRepository.kt`
- [x] Repository tests (CRUD + unique code)
- [x] `usecase/or/{Create,Update,List,Archive}OperatingRoomUseCase.kt` + tests
- [x] `api/or/OperatingRoomController.kt` + DTOs + integration tests

### Day 18 — Booking domain + repo
- [x] `domain/booking/Booking.kt`, `BookingStatus.kt`, `NewBooking.kt`, `BookingTimeRange.kt` (value object)
- [x] `persistence/booking/BookingRepository.kt` + `JooqBookingRepository.kt`
- [x] Repository tests, especially around `findActiveOverlappingFor(operatingRoomId, range, excluding=...)`

### Day 19 — Booking creation + conflict detection
- [x] `usecase/booking/CreateBookingUseCase.kt` (validates surgeon-is-surgeon, range valid, within working hours, fails fast on conflicts before INSERT)
- [x] Catch Postgres `ExclusionViolation` to surface `BOOKING_CONFLICT` cleanly even under concurrent writes
- [x] `api/booking/BookingController.kt` `POST /bookings` + tests

### Day 20 — Booking lifecycle
- [x] `usecase/booking/{Update,Cancel,Start,Complete}BookingUseCase.kt` + lifecycle invariants (single `TransitionBookingUseCase` dispatches cancel/start/complete via `BookingStatus.canTransitionTo`)
- [x] PATCH + POST cancel/start/complete endpoints + tests
- [x] Audit events: `booking.created/updated/cancelled/started/completed`

### Day 21 — Schedule view
- [x] `usecase/schedule/DayScheduleUseCase.kt` returning `DaySchedule(date, timezone, ors=[OrSchedule(or, bookings)])`
- [x] `GET /schedule?date=...` endpoint + tests
- [x] Edge cases: empty day, OR with no bookings, OR with status=MAINTENANCE excluded by default

### Day 22 — Clinic settings
- [x] `clinic_settings` repository + use case + endpoints + tests
- [x] Working-hours validation feeds into `CreateBookingUseCase`

### Day 23 — OpenAPI emit + frontend codegen
- [x] Add `springdoc-openapi-starter-webmvc-ui` dep, configure `/v3/api-docs`
- [x] Annotate controllers + DTOs (only what's necessary; springdoc auto-discovers most)
- [x] Add `apps/web` script `pnpm gen:api` running `openapi-typescript https://localhost:8443/v3/api-docs -o src/lib/api/generated.ts`
- [x] Migrate `lib/auth/api.ts` to use generated types; remove hand-written ones
- [x] Document the gen step in README quick-start

### Day 24 — Frontend: ORs + clinic settings
- [x] `(app)/operating-rooms/+page.svelte` — list, add (modal), edit, archive
- [x] `(app)/settings/clinic/+page.svelte` — admin form

### Day 25-26 — Frontend: schedule + booking modal
- [x] `(app)/schedule/+page.svelte` — date picker, time grid, OR columns, booking blocks
- [x] `BookingFormDialog.svelte` (create + edit modes, conflict-aware: shows the occluding booking inline)
- [x] Side panel for selected booking (edit / cancel / start / complete)
- [x] Promote home to a small landing dashboard
- [x] **Out-of-plan addition:** `GET /api/v1/users/surgeons` endpoint — the booking modal's surgeon picker can't function without it; admin user-management is Sprint 3 so this is the minimum to make Day 25 actually work end-to-end.

### Day 27 — Polish + Playwright
- [x] Dark mode toggle (we already have CSS vars)
- [x] Mobile breakpoint sanity (login / schedule / OR list at 375px) — automated via `e2e/mobile.e2e.ts` with body-overflow assertion
- [x] `e2e/booking.e2e.ts` Playwright: create OR → create booking → conflict on overlap → side-panel → edit → cancel → schedule view shows expected state (pulled forward to Day 25 because the dialog logic was non-trivial enough that compile-clean was not proof of working)
- [x] Update `scripts/smoke_test.py` with a `--include-booking` flag covering the happy path (uses `psycopg` to seed MANAGER+surgeon since there's no public role-promotion endpoint)

### Day 28 — Sprint close
- [x] Run `./gradlew check` + measured JaCoCo coverage on booking surface ≥70%
- [x] Tick DoD boxes
- [x] Write retro
- [x] Commit + push, last commit titled `docs(sprint2): close out`

---

## Booking-domain sanity checklist (Sprint-2 analog of the Sprint-1 ASVS list)

- [x] **No-overlap invariant** holds under concurrent writes (rely on `EXCLUDE`, don't TOCTOU-check in app code) — `bookings_no_overlap` constraint, `ExclusionViolation` mapped to `BOOKING_CONFLICT` in `CreateBookingUseCase`
- [x] **Cancelled bookings free the slot** — `EXCLUDE` predicate filters on `status IN ('SCHEDULED','IN_PROGRESS')` ; smoke `--include-booking` re-books the same slot after cancel as a regression
- [x] **End > start** enforced both at app and DB layers (`BookingTimeRange.init` and `bookings_time_range_valid` CHECK)
- [x] **Range respects clinic working hours** — `CreateBookingUseCase` rejects with `OUTSIDE_WORKING_HOURS` after converting both endpoints into the clinic zone
- [x] **Surgeon must be `is_surgeon = true`** — checked in `CreateBookingUseCase` and `UpdateBookingUseCase` against `UserRepository`
- [x] **Operating room must be `status = ACTIVE`** to accept new bookings — `OR_INACTIVE` result variant in both create and update use cases
- [x] **PII discipline** — `patient_ref` never appears in slf4j logs, audit metadata, or non-staff API responses (V1 only ships staff endpoints; V2 customer portal will add a redacted projection)
- [x] **Lifecycle transitions** are state-machine-checked (`BookingStatus.canTransitionTo`) — `TransitionBookingUseCase` rejects illegal transitions with 409 `ILLEGAL_TRANSITION`
- [x] **Authorization** — every `STAFF`+ endpoint checks the SecurityContext authority; `MANAGER`+/`ADMIN` endpoints gated in `SecurityConfig` (`hasAnyRole` matchers per HTTP method)
- [x] **Rate limits** in place for booking write endpoints (60/min/IP for POST/PATCH bookings, 30/min for OR writes)
- [x] **Audit chain** — every state change writes an `AuditEntry` in the same transaction (booking.created/updated/cancelled/started/completed; operating_room.created/updated/archived; clinic_settings.updated)
- [x] **Time zone correctness** — booking times round-trip without drift; integration test under `Europe/Berlin` clinic TZ vs UTC storage; SPA round-trips through `@internationalized/date`'s `toZoned` so the browser never has to do its own DST math

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

**Coverage on the booking surface (JaCoCo, `gradlew check`)**

| Package | Lines | Branches |
|---|---|---|
| `api/booking` | 94.4% | 71.4% |
| `api/clinic` | 100.0% | 66.7% |
| `api/or` | 89.2% | 64.3% |
| `api/schedule` | 100.0% | 100.0% |
| `api/user` | 92.3% | 50.0% |
| `usecase/booking` | 86.7% | 59.8% |
| `usecase/clinic` | 93.3% | 64.3% |
| `usecase/or` | 90.3% | 66.7% |
| `usecase/schedule` | 88.9% | 85.7% |
| `usecase/user` | 100.0% | 100.0% |
| `persistence/booking` | 98.4% | 69.4% |
| `persistence/clinic` | 95.7% | 57.9% |
| `persistence/or` | 96.9% | 78.6% |
| `domain/booking` | 85.0% | 50.7% |
| `domain/clinic` | 80.0% | 50.0% |
| `domain/or` | 82.5% | 55.8% |
| `domain/schedule` | 100.0% | 100.0% |
| **Sprint 2 overall** | **91.8%** | **62.9%** |

Lines: 1038/1131 covered, 93 missed. Branches: 331/526. Comfortably above the 70% line target. Branch coverage stays in the 60s on use cases because the result-variant switches (one branch per failure mode) are wider than the integration tests realistically reach — covering every `Result.X` arm via app-layer tests would be redundant with the controller tests that already exercise the same code paths from the outside.

**What went well**

- The Postgres `EXCLUDE USING gist` constraint paid off as designed. The use case still does a pre-check (so the SPA gets a usable `occludingBookingId` for the conflict UI) but the constraint is the actual no-overlap guarantee — under a Playwright race or smoke `--include-booking` the second writer just gets `BOOKING_CONFLICT` with no application-layer locking. The partial predicate `WHERE status IN ('SCHEDULED','IN_PROGRESS')` quietly carries the cancellation semantics: cancelling a booking *frees the slot* with zero application code, and the smoke now asserts that by re-booking the same range right after a cancel.
- OpenAPI codegen did pull its weight. The booking domain has 7 wire types and 4 path parameters; hand-mirroring those would have been a maintenance liability the first time a backend field renamed. After Day 23, every new endpoint (e.g. `GET /users/surgeons` on Day 25) was a one-liner: regenerate, import the new schema, write the typed wrapper. `Schemas['BookingDto']` reads cleanly enough at call sites that nobody misses the bespoke interfaces.
- Pulling Day 27's Playwright e2e forward to Day 25 caught a real bug. The y-coordinate I picked for the second create-dialog click (testing the conflict UI) landed *inside* the existing booking block, so the second click opened the dialog in edit mode and `#bf-patient` was disabled — the test froze waiting to fill a disabled input. Without that test the bug would only have surfaced on a user actually reaching for that gesture; the screenshot's geometry was visually fine.
- DX from Sprint 1 carried over cleanly. `pnpm dev:all` still does the right thing; lefthook + commitlint kept history tidy; Spring DevTools reload meant no full restart between Kotlin edits.

**What was harder than expected**

- Time-zone plumbing on the SPA side. Building an absolute ISO instant from a date input + two time inputs interpreted in the clinic's IANA zone is a *three*-line problem if you accept `@internationalized/date`, and a half-day problem if you try to do it with `Date` + `Intl.DateTimeFormat` alone. I started with the latter and rewrote it. Worth noting in DECISIONS.md as a place where adding a focused dep was clearly correct.
- The booking modal's surgeon picker had no backing endpoint until Day 25. Sprint 3 plans the admin user-management UI, but the picker can't render with no data — so I added `GET /api/v1/users/surgeons` mid-sprint as the minimum viable read. Recording it as an out-of-plan addition (Day 25 list) so the Sprint 3 spec doesn't accidentally re-design it.
- The `<dialog>` element is great for stacking (the BookingFormDialog can co-exist with the side panel), but its open/close lifecycle is imperative — `dlg.showModal()` / `dlg.close()` from a Svelte 5 `$effect` keyed on a prop. Workable but not idiomatic; I'll know to reach for a `<dialog>` again, with eyes open.
- Sequential vs parallel Playwright. With 4 workers, all four files fire `register` concurrently and the per-IP rate limiter started rejecting the fourth occasionally. `workers: 1` made the suite reliable at the cost of ~10s. Acceptable for now; raising it to 2 would probably also work but the risk-vs-reward isn't there for a solo project.

**Time spent vs estimate:** 13 days vs estimated 13. Stayed exactly on plan even with the Day 25 backend addition (`/users/surgeons`) and the e2e being pulled forward — those substituted for, rather than added to, scope.

**What I'd carry into Sprint 3 planning**

- The Sprint 3 admin user-management UI should subsume `/users/surgeons` rather than parallel it: the booking modal already consumes a typed wrapper (`usersApi.listSurgeons`), so the admin endpoint can grow alongside without rewriting the SPA call site.
- The smoke script's `--include-booking` flag works but requires a side-channel (`psycopg`) for role/surgeon promotion. Once Sprint 3 ships an admin endpoint for `is_surgeon` + role changes, the smoke can drop the psycopg dep and become pure HTTP again. Worth tracking that as a Sprint 3 cleanup line item.
- The schedule grid is `position: absolute`-based with hard-coded `1.2 PIXELS_PER_MINUTE`. Day-view is fine; week-view in Sprint 3 will want the same coords reused with a different x-axis. Refactoring the math into a small module before the week-view lands would prevent copy-paste drift.
- `BookingFormDialog` and `BookingSidePanel` both render booking detail (room, surgeon, time). When Sprint 3 grows the audit-log UI a third caller will need the same projection — a small `BookingDetail.svelte` snippet would pay off. Not worth doing pre-emptively.

**Booking-domain sanity review:** all green ✓ (12/12).

**Did the `openapi-typescript` generated types remove enough hand work to justify the codegen step?** yes — see "What went well" above. The generated file is committed so SPA builds without a backend, the codegen runs on demand (`pnpm gen:api`), and the round-trip caught one real wire-type drift mid-sprint.

→ Then move on to Sprint 3 — Real-time + admin polish.
