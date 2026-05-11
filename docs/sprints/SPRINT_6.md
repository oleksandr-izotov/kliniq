# Sprint 6 — V1.2 mobile + drag-drop + feedback round

**Goal:** Move Kliniq from "V1.1 shipped, looks clean on a 1440-wide monitor" to "V1.2 — a real human can use it from a phone on a Saturday, drag a booking to a different slot, and not feel like the product was built for empty rooms." The two narrative beats of this sprint: (1) mobile-responsive every existing screen so the demo URL works on the device most reviewers will actually click from, (2) close the 0/10 score on "real users" by sending the link to 2–3 humans, recording what they break, and fixing the top three issues before the sprint closes.

This is the **last polish sprint before V2 multi-tenancy + customer portal work begins**. Anything technical-debt-shaped that we want gone before V2 starts lives here: source-map upload moves into CI (out of the Coolify on-host build), branch coverage on guard-clauses tightens, the schedule and admin tables show branded empty states instead of bare "no data" lines. After Sprint 6 closes, the next architectural decision is a multi-tenancy ADR — V2 work begins with a clean slate, not a debt list.

**Definition of done:**

UX / mobile:

- [ ] Mobile responsive on every existing route: `/login`, `/register`, `/verify`, `/reset`, `/invite`, `/(app)/`, `/schedule`, `/operating-rooms`, `/admin/users`, `/admin/invitations`, `/admin/audit`, `/settings/profile`, `/settings/security`, `/settings/clinic`. Tested at 375 × 667 (iPhone SE), 390 × 844 (iPhone 14), 768 × 1024 (iPad portrait)
- [ ] Schedule day-view stacks operating rooms vertically below `lg` (1024 px) — no horizontal scroll-of-shame on phones
- [ ] Drag-drop reschedule in `/schedule`: drag an active booking to a different slot, the booking moves with optimistic-UI + roll-back on 409 conflict. Cancelled and completed bookings are not draggable
- [ ] Empty-state illustrations replace bare "no data" lines on the schedule (no bookings today), operating-rooms list, users list, invitations list, audit log. Uses the existing `brand-assets/illustrations/empty-*.png` set
- [ ] Lighthouse mobile score ≥ 90 on `/login` and `/schedule` (demo-login) on the live site

Feedback loop (the 0/10 gap):

- [ ] Send `https://kliniq.izotov.dev` + demo creds to **at least 2 humans** who aren't the author. Bias the ask towards "click around for 5 minutes, tell me what felt off or broken." Capture verbatim quotes in a `docs/FEEDBACK_V1_1.md` log
- [ ] Pick the top 3 issues from that feedback round (highest hit count + most painful) and ship fixes for each in the same sprint. Document the chosen 3 + the fix in `docs/FEEDBACK_V1_1.md`

Technical debt / pre-V2 cleanup:

- [ ] Web image moves to a GHA-built artifact pushed to GHCR (same pattern as `api-image`). Coolify pulls `ghcr.io/oleksandr-izotov/kliniq-web:latest` instead of building on the host. Source-map upload to Sentry happens in CI where `SENTRY_AUTH_TOKEN` is a proper secret, not a Coolify on-host env var
- [ ] Branch coverage on `com.kliniq.usecase.*` and `com.kliniq.domain.booking.BookingStatus` ≥ 75 % (up from V1.1's overall 60.4 %). Focus on guard-clauses and FSM `canTransitionTo` edge cases
- [ ] Sprint 6 sanity checklist (below) all green

**Estimated effort:** 10 days at chaotic pace, plus 2-7 calendar days of "humans being slow" overhead on the feedback round.

**Prereq:** Sprint 5 done — `v1.1.0` tagged, prod live at `https://kliniq.izotov.dev`, all infra observability in place (Sentry both sides, UptimeRobot, daily pg_dump).

---

## Decisions locked (no debate this sprint)

- **Drag-drop is HTML5 native + Svelte 5 actions.** No new dependency. The list of bookings on a schedule day is small (rarely > 30 rows); the perf wins of a virtualisation-aware DnD lib are zero. `draggable={true}` + `ondragstart` / `ondragover` / `ondrop` directly on the booking element. Roll-back path: optimistic state update on `drop`, fire PATCH, on 409 (booking conflict) revert state + show toast. The existing `BookingTimeRange` overlap test in [`Booking.kt`](../../apps/api/src/main/kotlin/com/kliniq/domain/booking/Booking.kt) is the source of truth for "is this slot free" — frontend doesn't pre-check.
- **Mobile breakpoints: Tailwind defaults.** `sm` 640 px / `md` 768 px / `lg` 1024 px / `xl` 1280 px. Layouts switch from desktop-grid to mobile-stack at `lg` (matches the existing `(auth)/+layout.svelte` convention). Below `sm` is "small phone" territory — should still work, won't be designed-for.
- **Empty-state illustrations are the existing brand assets.** `empty-bookings.png`, `empty-rooms.png`, `empty-users.png` already live in `brand-assets/illustrations/`; copy into `apps/web/static/illustrations/` (same pattern Sprint 5 used for the 404 art). Don't add new illustrations this sprint.
- **Lighthouse score target: 90 mobile**, not 100. The remaining 10 points are usually third-party-script or `mode-watcher` inline-script flags that we don't have a clean way to fix without rewriting CSP or removing FOUC protection. 90 is "looks healthy in a screenshot." Pursue 100 in a later sprint only if a specific job demands it.
- **Feedback reviewers: 2–3 people, structured-but-loose.** Email template asks them to (a) walk through demo-login → /schedule → create a booking → drag it; (b) note anything that felt confusing or broken; (c) say what would stop them showing this to a friend. No formal survey form, no scoring rubric — verbatim quotes are more useful than averaged numbers at this volume.
- **Web → GHCR moves the source-map upload boundary, not the Sentry config itself.** The Sentry vite plugin's `sourceMapsUploadOptions` stays the same; the difference is that `SENTRY_AUTH_TOKEN` becomes a GitHub Actions secret instead of a Coolify build-time env var. Coolify's `APP_DEMO_SEED` / `PUBLIC_SENTRY_DSN` / `BACKEND_URL` / etc. stay as runtime env vars on the web container.
- **Branch coverage target = 75 % on `usecase` + `domain.booking`**, not on the full repo. Generated jOOQ classes, controller adapters, and Spring boilerplate are excluded — they don't have meaningful branches.

---

## What we are NOT building this sprint

- Customer-facing booking portal — V2.
- Recurring bookings / routines — V2 (the data model and UX both belong with the customer portal).
- Multi-tenancy — V2 starts with the ADR.
- Billing / Stripe — V2+ once a real tenant exists.
- File uploads, consent forms, patient PII fields — V2+ once legal scoping is done.
- HIPAA / GDPR formal docs — separate compliance workstream involving legal, not engineering.
- Native mobile app — never (the responsive web SPA covers the phone use case).
- WebSocket upgrade from SSE — premature; SSE has held up under realtime testing.
- Strict-dynamic CSP with nonces — depends on `shadcn-svelte` dropping runtime style injection; Sprint 7+ candidate.
- Offsite backups — still deferred from Sprint 5 Day 52; picks up the day a paying tenant exists.

If anything on this list looks tempting mid-sprint, write it on a TODO and move on.

---

## Day-by-day plan

### Day 61 — Sprint 6 plan + Sprint 5 retro carry-over
- [ ] Write this file (you're reading it).
- [ ] Walk the Sprint 5 retro's "carry into Sprint 6" list and confirm each item appears below:
    - Multi-tenancy ADR → explicitly deferred to V2 (no day allocated here).
    - Source-map upload into CI → Day 68.
    - Strict-dynamic CSP → not this sprint (locked above).
    - Faster `api-image` warm builds → not this sprint (already at ~2 min, acceptable for V1.2).
- [ ] Commit `chore(sprint6): plan write-up`.

### Day 62 — Mobile: schedule day-view
- [ ] `/schedule` currently lays out 3 OR columns side-by-side; below `lg` (1024 px) stack vertically. Each OR becomes a full-width section with its own day-strip below.
- [ ] Booking-form Dialog goes full-screen on mobile (`sm:max-w-md` → `max-h-screen w-full` below `sm`).
- [ ] Test at 375 × 667 (iPhone SE), 390 × 844, 768 × 1024 in DevTools device emulation.
- [ ] Manual smoke: log in as `drsmith@kliniq-demo.local` on a real phone (or via DevTools touch emulation) and run through view-schedule + create-booking.

### Day 63 — Mobile: admin + settings + auth
- [ ] `/admin/users` table currently overflows below `md`. Replace with stacked-card layout on small screens (one card per user, action buttons inline at the bottom).
- [ ] `/admin/invitations` — narrower table, same stacked-card pattern below `sm`.
- [ ] `/admin/audit` — preserve table on `md+`, swap to card list below.
- [ ] `/settings/profile`, `/settings/security`, `/settings/clinic` — already mostly OK, double-check input sizing and button targets (44 × 44 px minimum hit area).
- [ ] `(auth)/+layout.svelte` — already responsive (single-col below `lg`); verify form inputs aren't cramped on small phones.

### Day 64 — Drag-drop reschedule in /schedule
- [ ] Each active booking element gets `draggable={true}` plus `ondragstart` writing its id to `dataTransfer`.
- [ ] Each empty slot in the day strip gets `ondragover` (preventDefault) + `ondrop` reading the booking id and calling `bookingsApi.update(id, { startsAt, endsAt })` with the new slot's time range.
- [ ] Optimistic UI: the booking visually moves on `drop` before the PATCH completes; if PATCH returns 409 BOOKING_CONFLICT (the DB's EXCLUDE-overlap constraint), revert the local move and toast the error.
- [ ] Mobile fallback: long-press on a booking opens the existing edit Dialog with the start-time field focused. Touch DnD across HTML5 is uneven; keep the desktop-only DnD as progressive enhancement.
- [ ] Visual feedback during drag: source booking semi-transparent (`opacity-50`), valid drop targets get a `ring-2 ring-primary/40`.
- [ ] No backend changes — the existing PATCH `/api/v1/bookings/{id}` endpoint already handles time changes and the overlap check is enforced at the DB.

### Day 65 — Empty-state illustrations
- [ ] Copy `brand-assets/illustrations/empty-bookings.png`, `empty-rooms.png`, `empty-users.png` into `apps/web/static/illustrations/` (`cp` like Sprint 5 Day 59 did with `error-404.png`).
- [ ] Schedule: when the day has no bookings, replace the bare empty grid with a centred illustration + "No bookings on {date}. Drag a card here or click an empty slot to book." copy.
- [ ] `/operating-rooms`: when the list is empty, illustration + "Add your first operating room — go to admin → /operating-rooms → New OR."
- [ ] `/admin/users`: empty filter result → illustration + "No users match these filters. Reset filters or invite someone via the Invitations tab."
- [ ] `/admin/invitations`: empty → illustration + "Invite your first teammate to Kliniq. Pick role + surgeon flag, send the email."
- [ ] `/admin/audit`: empty → smaller illustration + "No audit events match these filters."

### Day 66 — Reviewer round: outreach + collect
- [ ] Pick 2–3 humans. Bias towards "developers I respect" plus "someone non-technical" if possible. Email template lives in `docs/FEEDBACK_V1_1.md` (drafted same day).
- [ ] Email each: live URL + demo creds + the 3 things to walk through (sign in → /schedule → create-or-edit a booking → drag a booking → poke admin/users if curious).
- [ ] Ask them: (a) what felt confusing, (b) what looked broken, (c) what would stop them showing it to a friend. Open-ended, ~5 minutes of their time.
- [ ] Collect verbatim quotes in `docs/FEEDBACK_V1_1.md` as they come in. Don't filter, don't average — raw text.
- [ ] If a reviewer surfaces a critical bug (crash, broken auth, exposed data), fix it same day before the next reviewer hits it.

### Day 67 — Apply top-3 feedback
- [ ] Pick the top 3 items from `FEEDBACK_V1_1.md` by (hit-count × severity). Document the picks at the top of that file.
- [ ] Ship one commit per fix. Each commit body cites the verbatim quote that drove it.
- [ ] If a fix balloons past a day-of-work, write a TODO + defer to Sprint 7 — V1.2 polish should ship, not snowball into V1.3.

### Day 68 — Web image → GHCR via CI
- [ ] Add `web-image` job to `.github/workflows/ci.yml`, mirroring the existing `api-image` pattern: `pnpm install` + `pnpm --filter ./apps/web build` (running with `SENTRY_AUTH_TOKEN` as a GHA secret), then `docker buildx build` + push to `ghcr.io/oleksandr-izotov/kliniq-web:latest`.
- [ ] `apps/web/Dockerfile`: drop the `ARG SENTRY_AUTH_TOKEN` build arg (moved to the CI build step). Keep the runtime stage identical — same `pnpm install --prod` flow, same `node build` CMD.
- [ ] `compose.prod.yaml` web service: `image: ghcr.io/oleksandr-izotov/kliniq-web:latest` instead of `build: { context, dockerfile }`. Coolify pulls it instead of building.
- [ ] Remove `SENTRY_AUTH_TOKEN` from the Coolify env-var inventory after the new pipeline is verified end-to-end (one full deploy with a successful source-map upload visible in the Sentry dashboard).
- [ ] Document the new pipeline in `docs/RUNBOOK.md` — replace the "Coolify builds web on-host" section with "Coolify pulls both api and web from GHCR".

### Day 69 — Branch coverage push
- [ ] Run JaCoCo, sort packages by branch-missed count. Focus the highest-leverage gaps:
    - `com.kliniq.usecase.booking.*` — uncovered `else` branches on `canTransitionTo` and overlap-check paths
    - `com.kliniq.usecase.auth.*` — token expiry / consumed / revoked branches
    - `com.kliniq.domain.booking.BookingStatus.canTransitionTo` — explicit unit tests for every FROM × TO combination
- [ ] Target: `com.kliniq.usecase.**` ≥ 75 % branch coverage; `com.kliniq.domain.booking.BookingStatus` 100 % branch.
- [ ] Don't aim for 75 % on the whole repo — generated jOOQ classes drag the number down without adding signal.

### Day 70 — V1.2 retro + tag v1.2.0
- [ ] `./gradlew check`, JaCoCo report, tick DoD boxes including the new branch-coverage target.
- [ ] Lighthouse run on `/login` and `/schedule` (logged in as demo surgeon) on the live site; record the mobile + desktop scores in the retro.
- [ ] Write Sprint 6 retro: feedback round outcome (who reviewed, top quotes), which DoD items slipped vs landed, what carries into the V2 sprint.
- [ ] Tag: `git tag v1.2.0 && git push --tags`. Cut a GitHub Release pointing at the tag with the retro highlights as the body.
- [ ] Commit `docs(sprint6): close out — V1.2 shipped`.
- [ ] Close Sprint 6.

---

## Sprint 6 sanity checklist

- [ ] **Phone test** — open `https://kliniq.izotov.dev` on a real phone (or DevTools 375 × 667 emulation) and run through login → schedule → booking creation → drag without horizontal scrolling, cropped controls, or unreachable buttons.
- [ ] **Drag-drop** — drag an active booking to a free slot; UI moves before the PATCH responds; a deliberate conflict (drop on top of another booking) toasts cleanly and reverts.
- [ ] **Empty states** — every list / table with zero rows shows a branded illustration + a one-line "what to do next" hint, not a blank grid.
- [ ] **Lighthouse mobile** — `/login` ≥ 90 and `/schedule` ≥ 90 on mobile. Desktop should be higher.
- [ ] **Feedback log** — `docs/FEEDBACK_V1_1.md` exists with ≥ 2 reviewer quote sets + the top-3 issues + the commits that fixed them.
- [ ] **Web image in GHCR** — `docker pull ghcr.io/oleksandr-izotov/kliniq-web:latest` succeeds on the deploy host; Coolify Deploy uses the pulled image, doesn't rebuild on-host.
- [ ] **Sentry source maps still working** — trigger a deliberate web error after the CI-driven image deploy; the Sentry dashboard event resolves to real `.svelte` / `.ts` file names, not minified chunk paths.
- [ ] **Branch coverage** — JaCoCo XML reports `com.kliniq.usecase.**` ≥ 75 % branch, `BookingStatus.canTransitionTo` 100 % branch.

---

## Sprint 6 retro

_(filled at sprint close)_

**Coverage on the Sprint 6 surface (JaCoCo, `gradlew check`)**

_(table filled at close — same shape as Sprint 2 / 3 / 4 / 5)_

**Lighthouse scores**

| Route | Mobile | Desktop |
| --- | ---:| ---:|
| `/login` | ___ | ___ |
| `/schedule` (demo surgeon) | ___ | ___ |

**Reviewer round summary**

_Who reviewed:_ ___
_Top quotes:_ ___
_Top 3 issues picked + commits that fixed them:_ ___

**What went well**
-

**What was harder than expected**
-

**Time spent vs estimate:** ___ days vs estimated 10

**What I'd carry into V2 planning** (customer portal + multi-tenancy)
-

**Sprint 6 sanity review:** all green? ___

**First "this is a real product" moment:** when did the first reviewer's feedback land that made it feel like a product and not a demo? ___

→ Then **V2 — customer portal** (Sprint 7 planning). First task: write the multi-tenancy ADR — per-tenant deploy vs row-level isolation vs schema-per-tenant — before any code touches `users`, `bookings`, or `clinic_settings`. Multi-month block of work and the first time we'd seriously think about billing, GDPR data-export/delete flows, and "actual paying customers."
