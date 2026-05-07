-- V4: Real-time + admin polish.
--
-- This migration only adds user_invitations. The audit_events table from
-- V2 is read-only this sprint; the SSE backplane lives in Redis, not in
-- Postgres, so there's no schema for it here.
--
-- Invitations carry the role/surgeon flags the admin pre-set, so accept
-- can finalize a verified user in one round-trip without a separate
-- promotion endpoint. Token is stored as sha-256(token) — single-use,
-- short-lived (7 days), 32 random bytes of entropy at issue time, so
-- the salt-less hash is fine on its own.

----------------------------------------------------------------------------
-- user_invitations
----------------------------------------------------------------------------
CREATE TABLE user_invitations (
    id                UUID PRIMARY KEY,
    email             TEXT NOT NULL CHECK (length(email) BETWEEN 3 AND 254),
    -- Generated lowercase column lets us index by case-insensitive email
    -- the same way users.email_normalized works in V1.
    email_normalized  TEXT GENERATED ALWAYS AS (lower(email)) STORED,
    role              TEXT NOT NULL CHECK (role IN ('ADMIN','MANAGER','STAFF')),
    is_surgeon        BOOLEAN NOT NULL DEFAULT FALSE,
    specialty         TEXT CHECK (
                          specialty IN ('CARDIOLOGY','ORTHOPEDICS','GENERAL','NEUROSURGERY','OPHTHALMOLOGY')
                      ),
    token_hash        TEXT NOT NULL UNIQUE,
    issued_by_id      UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    issued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    accepted_at       TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    -- Specialty must be set iff is_surgeon=true. Same invariant as on the
    -- users table (V1__init.sql); keeping it here too means the accept
    -- flow can copy fields straight across without re-validating.
    CONSTRAINT user_invitations_specialty_iff_surgeon
        CHECK ((is_surgeon AND specialty IS NOT NULL) OR (NOT is_surgeon AND specialty IS NULL)),
    -- Belt-and-braces: an invitation can't be both accepted and revoked.
    CONSTRAINT user_invitations_terminal_exclusive
        CHECK (NOT (accepted_at IS NOT NULL AND revoked_at IS NOT NULL))
);

-- An invitation is "pending" iff not accepted and not revoked. At most one
-- pending invite per email at a time — duplicates after acceptance/revocation
-- are fine (they're history). Partial unique index gives us this at the DB
-- layer so concurrent CreateInvitation calls can't both win.
CREATE UNIQUE INDEX user_invitations_pending_email_idx
    ON user_invitations (email_normalized)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

-- Lookup by token hash on accept; token_hash is already UNIQUE so this
-- index is for the equality check during the accept flow.
CREATE INDEX user_invitations_token_hash_idx ON user_invitations (token_hash);
