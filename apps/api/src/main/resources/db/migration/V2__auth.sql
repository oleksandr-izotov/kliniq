-- V2: Authentication and audit infrastructure.
--
-- Tables:
--   users                       — login identities + roles + soft-disable
--   passkeys                    — WebAuthn credentials, many per user
--   email_verification_tokens   — one-time tokens emailed on registration
--   password_reset_tokens       — one-time tokens emailed on reset request
--   audit_events                — append-only log of who did what when
--
-- Enums are encoded as TEXT + CHECK constraints rather than PG enum types,
-- because adding new variants to a PG enum requires migration ceremony that
-- isn't worth the marginal type-safety upside; the application layer carries
-- the canonical Kotlin enums.

----------------------------------------------------------------------------
-- users
----------------------------------------------------------------------------
CREATE TABLE users (
    id                  UUID PRIMARY KEY,
    email               TEXT NOT NULL,
    email_normalized    TEXT GENERATED ALWAYS AS (lower(email)) STORED UNIQUE,
    email_verified_at   TIMESTAMPTZ,
    password_hash       TEXT,
    display_name        TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 100),
    role                TEXT NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'STAFF')),
    is_surgeon          BOOLEAN NOT NULL DEFAULT false,
    specialty           TEXT CHECK (specialty IN ('CARDIOLOGY', 'ORTHOPEDICS', 'GENERAL', 'NEUROSURGERY', 'OPHTHALMOLOGY')),
    status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Surgeon flag implies a specialty must be set; non-surgeons have no specialty.
    CONSTRAINT users_specialty_iff_surgeon CHECK (
        (is_surgeon = true AND specialty IS NOT NULL) OR
        (is_surgeon = false AND specialty IS NULL)
    )
);

----------------------------------------------------------------------------
-- passkeys (WebAuthn credentials)
----------------------------------------------------------------------------
CREATE TABLE passkeys (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id       BYTEA NOT NULL UNIQUE,
    public_key          BYTEA NOT NULL,
    signature_counter   BIGINT NOT NULL DEFAULT 0,
    aaguid              UUID,
    device_name         TEXT NOT NULL CHECK (length(device_name) BETWEEN 1 AND 100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at        TIMESTAMPTZ
);
CREATE INDEX passkeys_user_id_idx ON passkeys(user_id);

----------------------------------------------------------------------------
-- email verification tokens (24h TTL, single-use)
----------------------------------------------------------------------------
CREATE TABLE email_verification_tokens (
    token_hash      BYTEA PRIMARY KEY,        -- SHA-256 of the random token
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX email_verification_tokens_user_id_idx ON email_verification_tokens(user_id);

----------------------------------------------------------------------------
-- password reset tokens (15min TTL, single-use)
----------------------------------------------------------------------------
CREATE TABLE password_reset_tokens (
    token_hash      BYTEA PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX password_reset_tokens_user_id_idx ON password_reset_tokens(user_id);

----------------------------------------------------------------------------
-- audit events (append-only)
----------------------------------------------------------------------------
CREATE TABLE audit_events (
    id              UUID PRIMARY KEY,
    actor_user_id   UUID,                     -- nullable: system events have no actor
    action          TEXT NOT NULL,            -- e.g. 'user.registered', 'booking.cancelled'
    entity_type     TEXT NOT NULL,
    entity_id       UUID,
    before          JSONB,
    after           JSONB,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_actor_idx       ON audit_events(actor_user_id);
CREATE INDEX audit_events_entity_idx      ON audit_events(entity_type, entity_id);
CREATE INDEX audit_events_created_at_idx  ON audit_events(created_at DESC);

-- Append-only: forbid UPDATE and DELETE at the DB level so a misbehaving
-- application layer cannot rewrite history.
CREATE OR REPLACE FUNCTION audit_events_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_no_update BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();
CREATE TRIGGER audit_events_no_delete BEFORE DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();

----------------------------------------------------------------------------
-- updated_at maintenance trigger for users
----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_set_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
