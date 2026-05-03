# Domain Model

The "what is Kliniq about" document. Every entity, attribute, and rule lives here. Updates to this doc precede schema migrations.

---

## Bounded contexts (V1)

We have one context for V1 — `scheduling`. V3 adds `billing`. We keep code organised by context inside the same monolith.

---

## Entities

### `User`

Represents anyone with login access — staff and (in V2) external surgeons.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID v7 | PK, time-sortable |
| `email` | text, unique | normalized lowercase |
| `email_verified_at` | timestamptz, nullable | null until verified |
| `password_hash` | text, nullable | Argon2id; null if user only uses passkeys |
| `display_name` | text | first + last as user typed |
| `role` | enum `ADMIN`, `MANAGER`, `STAFF` | role-based authorization |
| `is_surgeon` | bool | for STAFF only |
| `specialty` | enum / nullable | only for surgeons; e.g. `CARDIOLOGY`, `ORTHOPEDICS`, `GENERAL` |
| `status` | enum `ACTIVE`, `DISABLED` | DISABLED users cannot log in |
| `created_at` | timestamptz | |
| `updated_at` | timestamptz | |

**Rules:**
- One of `password_hash` or at least one passkey must exist for the user to log in.
- Email change requires re-verification.
- Role changes are an admin-only action and create an audit event.
- A `STAFF` user cannot delete bookings created by others (only own); `MANAGER`+ can.

### `Passkey`

WebAuthn credential bound to a user.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID v7 | PK |
| `user_id` | UUID | FK → User |
| `credential_id` | bytea, unique | from authenticator |
| `public_key` | bytea | from authenticator |
| `signature_counter` | bigint | for replay protection |
| `aaguid` | uuid, nullable | authenticator type |
| `device_name` | text | user-given name, e.g. "MacBook TouchID" |
| `created_at` | timestamptz | |
| `last_used_at` | timestamptz, nullable | |

### `Session`

Lives in Redis (NOT in PG). One row in PG only as fallback / audit reference.

Redis key: `session:{id}` → JSON `{ userId, createdAt, lastSeenAt, ip, userAgent }`, TTL 30d sliding.

### `OperatingRoom`

| Field | Type | Notes |
|---|---|---|
| `id` | UUID v7 | PK |
| `code` | text, unique | short identifier, e.g. "OR-1" |
| `name` | text | human label, e.g. "Cardiology Suite A" |
| `status` | enum `ACTIVE`, `MAINTENANCE` | bookings blocked when MAINTENANCE |
| `notes` | text, nullable | |
| `created_at` | timestamptz | |
| `updated_at` | timestamptz | |

### `Booking`

The core entity.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID v7 | PK |
| `operating_room_id` | UUID | FK |
| `surgeon_id` | UUID | FK → User (must be `is_surgeon = true`) |
| `time_range` | tstzrange | PostgreSQL range type, **exclusive on upper** |
| `operation_type` | text | free text in V1; enum in V2 |
| `patient_code` | text | clinic-internal anonymous code, NEVER name/dob |
| `status` | enum `SCHEDULED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` | |
| `notes` | text, nullable | |
| `created_by` | UUID | FK → User |
| `created_at` | timestamptz | |
| `updated_at` | timestamptz | |
| `cancelled_reason` | text, nullable | required if status = CANCELLED |

**Constraints (DB-level):**
- `EXCLUDE USING gist (operating_room_id WITH =, time_range WITH &&) WHERE (status IN ('SCHEDULED', 'IN_PROGRESS'))` — prevents overlap on the same OR for active bookings. PostgreSQL enforces this atomically; no race conditions.
- `time_range` upper > lower (CHECK).

**Rules (domain-level):**
- A booking must be at least 15 minutes long.
- A booking can only be created for a surgeon whose specialty matches an OR allow-list (V2).
- Status transitions:
  - `SCHEDULED` → `IN_PROGRESS` (manual or auto-on-start-time)
  - `SCHEDULED` → `CANCELLED`
  - `IN_PROGRESS` → `COMPLETED`
  - `IN_PROGRESS` → `CANCELLED`
  - All others rejected.
- Cancelling requires `cancelled_reason`.
- Past bookings cannot be edited (only `notes`).
- Bookings cannot start in the past at creation time.

### `AuditEvent`

Immutable log of who did what.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID v7 | PK |
| `actor_user_id` | UUID, nullable | null for system events |
| `action` | text | e.g. `booking.created`, `user.role_changed` |
| `entity_type` | text | e.g. `booking`, `user` |
| `entity_id` | UUID, nullable | |
| `before` | JSONB, nullable | snapshot before change |
| `after` | JSONB, nullable | snapshot after change |
| `metadata` | JSONB, nullable | request id, IP, etc. |
| `created_at` | timestamptz | |

**Rules:**
- Append-only. No updates, no deletes.
- Written in the same DB transaction as the modifying operation (so it's atomic).
- PII fields excluded from snapshots (e.g. password hashes, no patient names — we don't store any).

### `ClinicSettings`

Singleton config row.

| Field | Type | Notes |
|---|---|---|
| `id` | int | always 1 |
| `clinic_name` | text | |
| `working_hours_start` | time | e.g. 06:00 |
| `working_hours_end` | time | e.g. 22:00 |
| `default_booking_minutes` | int | e.g. 60 |
| `timezone` | text | IANA, e.g. "Europe/Berlin" |
| `updated_at` | timestamptz | |

---

## ER overview

```
ClinicSettings (singleton)

User ──< Passkey
User ──< Booking (as surgeon)
User ──< Booking (as created_by)
User ──< AuditEvent (as actor)

OperatingRoom ──< Booking

Booking — uses time_range (tstzrange) with GIST exclusion
```

---

## Naming conventions

- Table names: snake_case, plural — `users`, `bookings`, `operating_rooms`, `audit_events`, `clinic_settings`
- Column names: snake_case
- Primary keys: always `id` (UUID v7)
- Foreign keys: `<entity>_id` (e.g. `surgeon_id`, `operating_room_id`)
- Timestamps: `created_at`, `updated_at`, `<verb>_at` for events (e.g. `email_verified_at`)
- Booleans: `is_*` or `has_*` (e.g. `is_surgeon`)

---

## Why UUID v7

- Time-sortable (the first 48 bits are millisecond timestamp), so PG B-tree inserts stay clustered → less index bloat than v4.
- Globally unique, generatable client-side (frontend can pre-allocate IDs for offline-friendly UX in V2).
- Spring/Kotlin libraries available (e.g. `com.github.f4b6a3:uuid-creator`).

---

## Future entities (not in V1)

Documented now to avoid schema retrofits:

- **Routine** (V2) — recurring booking template (every Monday 9-11 with Dr. X for 8 weeks)
- **RoutineInstance** (V2) — generated bookings from a routine
- **CustomerInvite** (V2) — pending invitation for external surgeon
- **BillingPolicy** (V3) — per-OR / per-customer rate cards
- **Invoice** (V3)
- **InvoiceLine** (V3)
- **Payment** (V3)
- **FileUpload** (V3) — consent forms, scans

When V2/V3 starts, this doc gets the next round of detail.
