-- V5: Onboarding completion flag.
--
-- `onboarded_at` is stamped once when the first admin finishes the
-- onboarding wizard (Sprint 5 Day 58). Nullable on purpose: NULL means
-- "wizard hasn't been completed yet", a non-null TIMESTAMPTZ means it
-- has and the wizard never shows again for this clinic.
--
-- We don't enforce one-shot at the DB layer (no CHECK column) because
-- re-stamping the column with a later timestamp is harmless and the
-- write path goes through a single endpoint that's idempotent anyway.

ALTER TABLE clinic_settings
    ADD COLUMN onboarded_at TIMESTAMPTZ;
