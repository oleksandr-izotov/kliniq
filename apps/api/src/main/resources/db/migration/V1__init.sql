-- V1: Database initialisation. Schema entities arrive in Sprint 1.

-- btree_gist is required by Sprint 1 to enforce non-overlapping bookings on
-- the same operating room via an EXCLUDE constraint over a tstzrange column.
-- Created here so the prerequisite is in place before Sprint 1 migrations run.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- pgcrypto provides gen_random_uuid() and digest() helpers used by auth
-- machinery in Sprint 1 (e.g. token hashing). Cheap to enable, no downside.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- A schema_marker row gives us a single, idempotent place to verify Flyway
-- ran. Will be replaced by real domain tables in Sprint 1.
CREATE TABLE schema_marker (
    id              INT PRIMARY KEY DEFAULT 1,
    initialised_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    note            TEXT NOT NULL DEFAULT 'sprint-0-foundation'
);

INSERT INTO schema_marker (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
