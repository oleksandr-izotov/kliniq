# Plan — next stretch

**Written:** 2026-09-09, right after the redeploy to the Reykjavík box.
**Status:** not started. This is the document to open when there is time again.

The goal of this stretch is a Kliniq that is *demonstrably* stronger — more
capability, and every bit of it covered. Those two halves are deliberately in
that order and cannot swap places: the features below touch scheduling, which is
exactly the code that has no automated UI coverage today. Building on that is how
a project quietly turns into something nobody dares change.

So: one evening of foundation, then three features, then the sprint closes.

---

## Where things stand

| | |
|---|---|
| Live | `https://kliniq.izotov.dev` — V1.1, seeded demo clinic |
| Code | ~16k Kotlin + ~13k Svelte/TS |
| Tests | 187 backend, 8 Playwright e2e (**e2e not in CI**) |
| Branch coverage | ~60.4% overall, target was 75% on `usecase`/`domain` |
| API surface | 12 controllers, 43 endpoints, 11 tables |
| Open from Sprint 6 | days 66–70 |

Carried over from the pause, still true:

- Frontend tests are thin — one web test file against 24 backend ones.
- No real users have ever touched it.
- Single-tenant by design; multi-tenancy needs its own ADR before any code.

New since the move off the old host:

- **Sentry has no DSN configured.** Both SDKs no-op. Errors go nowhere.
- **No uptime monitor.** The old one watched an address that no longer exists.
- Spring Security logs `Using generated security password` at startup — its
  autoconfiguration is still active even though authentication is ours. Harmless,
  but it is the kind of line an interviewer notices.

Already closed on 2026-09-09: nightly `pg_dump` at 03:00 UTC with 14-day
retention, restore path verified against a scratch database; docs brought in line
with the current deployment.

---

## Stage 0 — foundation (one evening)

Nothing here is new capability. It is the floor the rest stands on.

- [ ] **Playwright e2e in CI.** The 8 specs exist and pass locally; wire them into
      `ci.yml` as a job that boots the compose stack, waits for
      `/actuator/health`, runs the suite, and uploads the HTML report on failure.
      Until this lands, "the tests pass" means "the backend tests pass."
- [ ] **Branch coverage ≥ 75%** on `com.kliniq.usecase.**` and
      `com.kliniq.domain.booking.BookingStatus`, up from ~60.4%. Aim at guard
      clauses and `canTransitionTo` edges — the paths that only run when something
      goes wrong, which is when you least want a surprise.
- [ ] **Sentry DSN** for api and web, set through compose env. Verify by throwing
      a test exception in each and confirming it lands.
- [ ] **Uptime check** against `/actuator/health/liveness`, alerting somewhere
      that reaches a phone.
- [ ] **Silence the Spring Security autoconfiguration warning** — exclude
      `UserDetailsServiceAutoConfiguration` or supply an explicit
      `UserDetailsService`, whichever reads cleaner next to the existing filter.

**Done when:** a red e2e test blocks a merge, coverage gate passes at 75%, a
deliberately thrown exception appears in Sentry from both sides, and killing the
api container produces an alert.

---

## Stage 1 — recurring series

The highest-value feature in this plan, and the only one whose difficulty is real
rather than incidental. Everything below is a decision to make, not a widget to
place.

**The shape of it:** "every Monday 09:00–11:00 with Dr. Smith, for 8 weeks."

**Decide first — this belongs in an ADR before any code:**

- **Materialise or expand?** Write all 8 bookings into `bookings` at creation
  time, or store one rule and expand it when a range is queried? Materialising
  makes the existing `EXCLUDE` constraint do all the conflict work for free and
  keeps the schedule query untouched; expansion keeps the series editable as one
  object but means overlap checking has to be re-implemented outside the database
  — where it is no longer airtight. **Leaning materialise**, precisely because the
  guarantee currently lives in Postgres and should stay there.
- **What happens when one occurrence collides** with a booking that already
  exists? Reject the whole series, or create the rest and report which dates were
  skipped? The second is friendlier and much harder to express in an API response
  that stays honest.
- **Editing semantics.** "This occurrence", "this and all following", "the whole
  series" — the three options every calendar offers. Which are in scope, and what
  does each do to a series that is already half in the past?
- **Cancellation.** Does cancelling the series cancel occurrences that already
  happened? Almost certainly not — but say so explicitly.

**Build:**

- [ ] ADR in `docs/DECISIONS.md` capturing the four decisions above with their
      alternatives, in the existing format.
- [ ] Migration: `booking_series` table, plus a nullable `series_id` on
      `bookings`. Deleting a series must not orphan its bookings.
- [ ] Domain: a series generator that turns (weekday, time range, count | until)
      into a list of concrete slots in clinic-local time. **Watch the DST
      boundary** — Europe/Berlin shifts in the middle of a long series, and 09:00
      must stay 09:00 for the humans involved.
- [ ] Use case: create the series, letting the `EXCLUDE` constraint reject
      collisions individually; return created and skipped dates separately.
- [ ] Endpoints: create, edit with scope, cancel with scope.
- [ ] UI: series creation in the booking dialog, a visual marker on recurring
      bookings, and the three-way choice on edit and cancel.
- [ ] Tests: generator including a DST crossing, partial collision, each edit
      scope, cancelling a half-past series. E2E: create a series, move one
      occurrence, confirm the rest are untouched.

---

## Stage 2 — audit log UI

The cheapest real feature in the plan: the data is already being written.
`audit_events` fills up on every meaningful action; there is simply no screen.

- [ ] Endpoint: paginated, filterable by actor, entity type, and date range.
      Admin-only.
- [ ] UI at `/admin/audit`: table, filters, an expandable row for the metadata.
- [ ] Retention: decide whether events are kept forever and say so in
      `DOMAIN.md`. In a clinical context "forever" is a defensible answer, but it
      should be a decision rather than an oversight.
- [ ] Tests: an admin sees entries, a non-admin gets 403, filters narrow the set,
      and — importantly — `patient_ref` never appears, matching the discipline the
      write path already keeps.

---

## Stage 3 — utilisation reports

The part of the plan nearest to data work, and the one this codebase has nothing
of yet.

- [ ] Endpoints returning aggregates over a date range: utilised hours per
      operating room, bookings and cancellations per surgeon, cancellation rate,
      busiest hours of the week.
- [ ] Write them as real SQL aggregates through jOOQ, not by pulling rows into
      Kotlin and folding them there. The point of the exercise is the query.
- [ ] UI at `/admin/reports`: range picker, a bar chart for utilisation per room,
      a line for bookings over time, plain numbers for the rest. Hand-rolled SVG
      or a small Svelte chart library — decide once, note it in `DECISIONS.md`.
- [ ] Empty and sparse states: a clinic with three bookings must not render a
      chart that implies statistical weight it does not have.
- [ ] Tests: aggregates against a seeded fixture with known answers, including a
      range that crosses a month boundary and one with no data at all.

---

## Deliberately not in this stretch

- **Customer portal.** The largest item in V2 and mostly interface work. It adds
  breadth, not depth; the three stages above show more per hour spent.
- **Billing.** Weeks of work, and the reporting stage already demonstrates the
  same skills on the data side.
- **Multi-tenancy.** Needs its own ADR — per-tenant deploy vs row-level isolation
  vs schema-per-tenant — decided before a line of code touches `users`,
  `bookings` or `clinic_settings`. That is a stretch of its own, not a task.

---

## What "done" looks like

- Roughly 60–65 endpoints, up from 43.
- Branch coverage above 75%, e2e running on every push.
- Three features that are each harder than a CRUD screen: a scheduling generator
  that survives DST and partial conflicts, an audit trail with a privacy rule
  enforced in tests, and reporting built on real aggregate queries.
- Errors visible in Sentry, downtime visible before a visitor finds it.
- `v1.2.0` tagged, retro written, `PAUSE.md` updated so the next pause is as tidy
  as the last one.

**Effort:** one evening for stage 0, then roughly two to three weeks of evenings
for the three features at the pace the earlier sprints ran.
