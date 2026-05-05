-- V3: Booking core — operating rooms, bookings (with overlap-prevention
-- enforced at the DB layer), and clinic-wide settings.
--
-- Key design choice: bookings overlap protection rides on a Postgres
-- EXCLUDE USING gist constraint over (operating_room_id, tstzrange) so the
-- application layer cannot open a TOCTOU window where two requests both
-- pass an "is the slot free?" check and both INSERT. Cancelled bookings
-- stop blocking the slot via a partial-index predicate on status.

----------------------------------------------------------------------------
-- operating_rooms
----------------------------------------------------------------------------
CREATE TABLE operating_rooms (
    id          UUID PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE CHECK (length(code) BETWEEN 1 AND 20),
    name        TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    -- ACTIVE: usable.  MAINTENANCE: hidden from new-booking pickers but
    -- existing bookings remain.  RETIRED: archived; replaces DELETE so
    -- audit history stays intact.
    status      TEXT NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'RETIRED')),
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER operating_rooms_set_updated_at
    BEFORE UPDATE ON operating_rooms
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

----------------------------------------------------------------------------
-- bookings
----------------------------------------------------------------------------
CREATE TABLE bookings (
    id                  UUID PRIMARY KEY,
    operating_room_id   UUID NOT NULL REFERENCES operating_rooms(id) ON DELETE RESTRICT,
    surgeon_id          UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    -- Who created the booking — different from the surgeon who'll perform
    -- the operation.  Useful for audit and for the schedule "added by" hint.
    created_by_id       UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    starts_at           TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ NOT NULL,
    op_type             TEXT NOT NULL CHECK (length(op_type) BETWEEN 1 AND 200),
    -- Anonymous patient reference, e.g. P-2026-001. NEVER store PII here.
    patient_ref         TEXT NOT NULL CHECK (patient_ref ~ '^P-[0-9]{4}-[0-9]{3,}$'),
    status              TEXT NOT NULL DEFAULT 'SCHEDULED'
                        CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- end strictly after start: zero- or negative-length bookings make no sense.
    CONSTRAINT bookings_time_range_valid CHECK (ends_at > starts_at),

    -- The headline invariant: no two ACTIVE bookings overlap on the same OR.
    -- btree_gist (loaded in V1) lets us combine UUID equality with tstzrange
    -- overlap in a single GiST index. Adjacent bookings don't conflict
    -- because we use the half-open '[)' bound — a 09:00–10:00 booking
    -- and a 10:00–11:00 booking back-to-back are fine.
    -- Cancelled / completed bookings drop out of the predicate, so freeing
    -- a slot is a status update, not a delete.
    CONSTRAINT bookings_no_overlap EXCLUDE USING gist (
        operating_room_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('SCHEDULED', 'IN_PROGRESS'))
);

-- Day-view query plan: filter by date range on starts_at, scoped to one OR.
CREATE INDEX bookings_operating_room_starts_idx ON bookings(operating_room_id, starts_at);
-- Surgeon-centric views (e.g. "Dr. Smith's week") will land in V2.
CREATE INDEX bookings_surgeon_starts_idx        ON bookings(surgeon_id, starts_at);
-- Status filter for "upcoming bookings" / "today's cancellations" reports.
CREATE INDEX bookings_status_idx                ON bookings(status);

CREATE TRIGGER bookings_set_updated_at
    BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

----------------------------------------------------------------------------
-- clinic_settings (single-row table)
----------------------------------------------------------------------------
-- Modeled as a one-row table rather than a key/value store so each setting
-- is type-checked at the DB level and we don't reinvent typed access in code.
-- The `id = 1` CHECK plus the seeded INSERT below make the singleton
-- explicit; row insertion is forbidden by the PRIMARY KEY collision.
CREATE TABLE clinic_settings (
    id                          INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    name                        TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    -- IANA zone, e.g. "Europe/Berlin". Validated at the application layer
    -- via java.time.ZoneId.of(); Postgres can't easily check IANA names.
    timezone                    TEXT NOT NULL DEFAULT 'Europe/Berlin',
    working_hours_start         TIME NOT NULL DEFAULT '08:00',
    working_hours_end           TIME NOT NULL DEFAULT '20:00',
    default_booking_minutes     INT  NOT NULL DEFAULT 60
                                CHECK (default_booking_minutes BETWEEN 5 AND 1440),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT clinic_settings_hours_valid CHECK (working_hours_end > working_hours_start)
);

INSERT INTO clinic_settings (id, name) VALUES (1, 'Kliniq');

CREATE TRIGGER clinic_settings_set_updated_at
    BEFORE UPDATE ON clinic_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
