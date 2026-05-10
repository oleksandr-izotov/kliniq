# Sprint 5 — V1.1 post-ship hardening + UX polish

**Goal:** Move Kliniq from "V1 shipped" to "prod-grade V1.1 — reliable infrastructure plus UX that doesn't feel raw to a first-time visitor". This sprint bundles the two deferred items from Sprint 4 (DB backup validation + scripted prod smoke), the hardening items the Sprint 4 retro flagged for V1.1 (offsite backups, Sentry, uptime monitoring, SSH key-only, Coolify proxy lockdown, CSP header, GHA cache), and the UX-sparseness items surfaced the day after shipping (admin sub-nav, demo data seed, onboarding wizard for the first admin, polished README, branded 404).

Two reasons to bundle: (1) infrastructure-only leaves the product feeling sparse to anyone we send the URL to; UX-polish-only leaves the product fragile in failure modes — neither alone moves V1 forward in a meaningful way. (2) Roughly 70% of the "feels raw" feedback maps to "admin nav missing + schedule is empty on fresh login + README is a stub" — none of which needs a separate B2B-go-to-market detour to fix.

**Definition of done:**

Reliability / hardening:

- [ ] Daily `pg_dump` cron on the Hetzner box; retention keeps the last 14 days locally
- [ ] ~~Offsite backup to Backblaze B2 via Restic; one manual restore-test confirms the dump round-trips through B2 back into a working Postgres~~ — **deferred:** Day 52 punted out of V1.1 on 2026-05-09. Rationale: no paying clinic on the box means the only data at risk is the demo deploy's own dummy bookings; local 14-day retention is enough to cover that. Picks up again the day we onboard a real tenant.
- [ ] Sentry hookup on both api (Spring Boot) and web (SvelteKit); error events flow to one project with `environment=prod` tag; source maps attached on the web side
- [ ] UptimeRobot (or equivalent) pinging `/actuator/health/liveness` every 5 minutes; email alert verified by stopping the web container briefly
- [ ] SSH hardening: `PasswordAuthentication no` in `/etc/ssh/sshd_config`, key-only login; fail2ban jail config verified
- [ ] Coolify built-in proxy permanently disabled: `docker update --restart=no coolify-proxy` + verified across one daemon restart
- [ ] CSP header added with a reasonable initial policy; verified no SPA console errors after rollout
- [ ] GHA `api-image` job adds buildkit cache for Gradle deps + Docker layers; warm build time target < 90 seconds (currently ~5 minutes cold)

UX polish:

- [ ] `/(app)/admin/+layout.svelte` with a Tabs sub-nav (Users · Invitations · Audit)
- [ ] Flyway migration `V5__demo_seed.sql` seeds 3 OR + 4 surgeons (mixed specialties) + 8–12 sample bookings spread across the next two weeks; gated behind `APP_DEMO_SEED=true` so prod doesn't seed without explicit opt-in
- [ ] Onboarding wizard for the first admin signing up: clinic name + timezone, optional first OR, optional first invitation; one-shot, never shows again after the wizard's "Done"
- [ ] `README.md` hero section rewritten: live URL, hero screenshot of `/schedule`, demo credentials, "Engineering deep dive" linking the load-bearing tests / migrations / ADRs
- [ ] Branded 404 page replacing the SvelteKit default
- [ ] Sprint 5 sanity checklist (below) all green

**Estimated effort:** 10 days at chaotic pace.

**Prereq:** Sprint 4 done — V1 shipped at `https://kliniq.izotov.dev` with the v1.0.0 tag.

---

## Decisions locked (no debate this sprint)

- **Backup target: Backblaze B2.** $0.005/GB/month storage vs S3's $0.023 — for ~1 GB of compressed pg_dumps over 12 months we're talking under $1/year. Restic on top gives encrypted de-duped snapshots and `restic restore` for any point in the retention window. Wasabi / Cloudflare R2 are alternates if B2 ever breaks; not worth thinking about now. **Deferred from Sprint 5 on 2026-05-09** — picks up the day we onboard a paying tenant.
- **Sentry plan:** free tier (5k events/month, 7-day retention). Plenty for V1.1 traffic. Upgrade only when we actually hit the limit.
- **Uptime monitoring: UptimeRobot free tier.** 50 monitors, 5-minute interval. Single liveness probe covers us. Better-Uptime / StatusGator are nicer UIs but cost money we don't need to spend yet.
- **CSP shape:** start with a permissive-but-mostly-locked-down policy — `frame-ancestors 'none'`, `img-src 'self' data:`, `script-src 'self'`, `style-src 'self' 'unsafe-inline'` (shadcn-svelte runtime needs inline styles). Strict-dynamic + per-request nonces is a Sprint 6 nice-to-have; the looser policy still defeats every classic XSS vector.
- **Demo data is opt-in.** Pre-seeding `:demo data on every install` is great for portfolio screenshots and wrong for a real paying clinic that wants a clean slate. `APP_DEMO_SEED=true` is set in Coolify env for the demo deploy; an actual clinical deploy leaves it unset.
- **Onboarding wizard runs once per admin.** A `clinic_settings.onboarded_at` timestamp records completion; the wizard never re-appears for that clinic. No "skip and never see this again" toggle — finishing the wizard IS that signal.
- **No multi-tenancy.** Per-tenant deploy stays the model for V1.x. (Multi-tenancy is V2+ planning, not this sprint.)
- **No billing.** Same.
- **No B2B landing page or marketing site.** If real demand surfaces, side-quest sprint. For now `/` for unauthenticated visitors continues to redirect to `/login` — same as it does for V1.
- **README rewrite, not delete-and-rewrite-from-scratch.** Existing structure stays; the hero and engineering-deep-dive sections are insertions. Avoids losing institutional context.

---

## What we are NOT building this sprint

- Customer-facing booking portal — V2.
- Drag-drop schedule, recurring routines — V2.
- Multi-tenancy — V2+ planning.
- Billing — V3.
- Mobile-optimised layouts — V3.
- Audit log UI extensions (CSV export, full-text search) — V3.
- File uploads / consent forms — V3.
- Marketing landing page on `/` — side quest if real demand surfaces.
- HIPAA-equivalent compliance docs — separate workstream involving legal, not engineering-only.
- Strict-dynamic CSP with nonces — Sprint 6 if needed.

If anything on this list looks tempting mid-sprint, write it on a TODO and move on.

---

## Day-by-day plan

### Day 51 — DB backup: pg_dump cron + retention
- [ ] `/opt/kliniq/backup-pg.sh` script that runs `docker exec postgres-… pg_dump …` inside the container, pipes through gzip, writes `/opt/kliniq/backups/pg-YYYYMMDDHHMM.sql.gz`.
- [ ] Root crontab entry: daily at 03:00 UTC.
- [ ] Retention: `find /opt/kliniq/backups -name 'pg-*.sql.gz' -mtime +14 -delete` in the same script.
- [ ] One-time restore-test: load the latest dump into a sidecar Postgres container, `SELECT count(*) FROM users / bookings / operating_rooms`, verify counts match prod.
- [ ] Document the runbook in `docs/RUNBOOK.md` (new file) — exact commands for backup / restore / list.

### Day 52 — Offsite backups: Restic + Backblaze B2 — **DEFERRED**

Punted out of Sprint 5 on 2026-05-09. The only data on the box is the demo deploy's own dummy bookings; local 14-day pg_dump retention from Day 51 is sufficient until a real tenant exists. Picks up again the day we onboard a paying clinic — at which point the full scope below applies as written.

- [ ] ~~Backblaze B2 account; bucket `kliniq-prod-backups`; app key with write-only scope to that bucket.~~
- [ ] ~~`apt install restic` on the host.~~
- [ ] ~~`/opt/kliniq/backup-restic.sh` wraps `restic backup /opt/kliniq/backups/` — encrypted with a passphrase stored in the user's password manager.~~
- [ ] ~~Cron at 04:00 UTC (after pg_dump completes).~~
- [ ] ~~Retention: `restic forget --keep-daily 7 --keep-weekly 4 --keep-monthly 12 --prune`.~~
- [ ] ~~Manual restore-test: `restic restore latest --target /tmp/restore`, verify `.sql.gz` files match the local dumps byte-for-byte.~~

→ Day 53 starts immediately after Day 51 in the actual execution timeline.

### Day 53 — Sentry hookup (api)
- [ ] Add `io.sentry:sentry-spring-boot-starter-jakarta` to `apps/api/build.gradle.kts`.
- [ ] DSN via `SENTRY_DSN` env var; `environment=prod` tag set automatically through Spring profile.
- [ ] Sample rate 1.0 (capture everything) until volume becomes a concern.
- [ ] Deliberate-throw smoke: dummy controller throws on `/test-sentry`; verify event lands in Sentry dashboard with stack trace + JSON request context (no PII).
- [ ] Remove the dummy endpoint before the day closes.

### Day 54 — Sentry hookup (web) + UptimeRobot
- [ ] `@sentry/sveltekit` per their docs; `PUBLIC_SENTRY_DSN` env var so client and server-side errors both land in Sentry.
- [ ] Source maps uploaded on each web build through a CI step (Sentry's `sentry-cli sourcemaps upload`).
- [ ] UptimeRobot free account; HTTP monitor for `https://kliniq.izotov.dev/actuator/health/liveness`; 5-minute interval; email alert to the user; trigger threshold "2 consecutive failures".
- [ ] Verify the alert fires by `docker stop web-…` briefly; restore.

### Day 55 — SSH hardening + Coolify proxy lockdown + CSP
- [ ] Edit `/etc/ssh/sshd_config`: `PasswordAuthentication no`, `PermitRootLogin prohibit-password`.
- [ ] `systemctl reload ssh`. Test key login from a **fresh terminal** before closing the existing one.
- [ ] `docker update --restart=no coolify-proxy`. Reboot the box, verify `coolify-proxy` doesn't come back, then bring the kliniq stack back up.
- [ ] Verify `fail2ban` SSH jail is active: `fail2ban-client status sshd`.
- [ ] CSP header in Spring Security config: `Content-Security-Policy: default-src 'self'; img-src 'self' data:; script-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'self'`.
- [ ] Click through every SPA route; assert zero CSP-related console errors.

### Day 56 — Admin sub-nav (Users · Invitations · Audit)
- [ ] `/(app)/admin/+layout.svelte` wraps the admin pages with a shadcn Tabs component.
- [ ] Three tabs link to `/admin/users` (default), `/admin/invitations`, `/admin/audit`.
- [ ] Active tab matches the current `$page.url.pathname`.
- [ ] Smoke each tab loads without error against the live deploy.

### Day 57 — Demo data seed migration V5
- [ ] `apps/api/src/main/resources/db/migration/V5__demo_seed.sql` — runs only when `APP_DEMO_SEED=true` (Flyway placeholder + conditional `DO $$ … END $$` block, or a startup-time Spring bean if Flyway placeholders prove fiddly).
- [ ] Seed contents: 3 operating rooms (`OR-1` general, `OR-2` cardiology-friendly, `OR-3` orthopedics), 4 surgeons with display names + specialty assignment + pre-verified email-verified-at, 8–12 bookings spread across the next two weeks in working hours.
- [ ] `clinic_settings` row pre-populated: working hours 09:00–18:00, timezone `Europe/Berlin`.
- [ ] Verify on a fresh prod-style redeploy with the env var set: schedule view is populated.

### Day 58 — Onboarding wizard for first admin
- [ ] Detection: `/(app)/+page.svelte` runs a load function that checks `clinic_settings.onboarded_at IS NULL` AND `auth.user.role === 'ADMIN'`. If true, render the wizard modal.
- [ ] Step 1 — Clinic name + timezone (from `Intl.supportedValuesOf('timeZone')` ⇒ a Select), working hours (from / to time inputs).
- [ ] Step 2 — Add first operating room (Code + Name); "Skip" allowed.
- [ ] Step 3 — Invite team (email + role + surgeon flag); "Skip" allowed.
- [ ] On "Done" the API stamps `clinic_settings.onboarded_at = now()`. The wizard never re-appears for this clinic.
- [ ] Visual style matches existing shadcn pattern (Dialog + Stepper / Tabs).

### Day 59 — README polish + branded 404
- [ ] `README.md` hero: badge row (Live · Build · Lighthouse · License), hero screenshot of `/schedule` (placeholder if we don't have one yet), "Live at https://kliniq.izotov.dev — try it with demo credentials: …", a one-paragraph product pitch in plain English.
- [ ] "Engineering deep dive" section linking the load-bearing tests (`BookingPubSubTwoInstancesTest`, `BookingPublishAfterCommitTest`), the EXCLUDE-overlap migration, the FSM enforcement, the AFTER_COMMIT publishing pattern, the GHA → GHCR pipeline.
- [ ] Mermaid architecture diagram (services, network flow, Caddy / Coolify / Resend / Backblaze position).
- [ ] Stack list with pinned versions.
- [ ] "Run locally" instructions still working (re-verify) plus a "Deploy your own" pointer to `docs/RUNBOOK.md`.
- [ ] Branded 404: `/(app)/+error.svelte` plus `/(auth)/+error.svelte` rendering a Kliniq-branded "Page not found" with a link back to `/`.

### Day 60 — GHA cache + Sprint 5 close + retro
- [ ] In the `api-image` workflow: `cache-from: type=gha` and `cache-to: type=gha,mode=max` on the buildx-build step; `actions/cache@v4` keyed on `build.gradle.kts` hash for Gradle dependencies.
- [ ] Cold-run check: total `api-image` job time still acceptable.
- [ ] Warm-run check: trigger a second push, target ≤90 seconds total.
- [ ] `./gradlew check`, JaCoCo report, tick DoD boxes.
- [ ] Write Sprint 5 retro section in this file.
- [ ] Tag: `git tag v1.1.0 && git push --tags`.
- [ ] Commit `docs(sprint5): close out — V1.1 shipped`.
- [ ] Close Sprint 5.

---

## Sprint 5 sanity checklist

- [ ] **Backups round-trip** — local pg_dump → sidecar restore-test verified; counts match. (Offsite B2 leg deferred — see Day 52.)
- [ ] **Sentry events flow** — api crash + web crash both visible in the Sentry dashboard within 1 minute of being thrown.
- [ ] **UptimeRobot pings** — green ticks every 5 minutes; alert email tested by stopping a container briefly.
- [ ] **SSH key-only** — password auth disabled, key auth verified across one reboot.
- [ ] **Coolify proxy locked** — `coolify-proxy` doesn't restart after a docker daemon restart.
- [ ] **CSP** — header set, no SPA breakage.
- [ ] **Admin sub-nav** — navigating between Users / Invitations / Audit works without typing URLs.
- [ ] **Demo seed** — `APP_DEMO_SEED=true` on a fresh deploy populates the schedule.
- [ ] **Onboarding wizard** — first admin login triggers the wizard; second login doesn't.
- [ ] **README polished** — hero + screenshot + demo creds + engineering deep-dive sections present.
- [ ] **404 branded** — `https://kliniq.izotov.dev/does-not-exist` renders the Kliniq-branded page, not the SvelteKit default.

---

## Sprint 5 retro

_(filled at sprint close)_

**Coverage on the Sprint 5 surface (JaCoCo, `gradlew check`)**

_(table filled at close — same shape as Sprint 2 / 3 / 4)_

**What went well**
-

**What was harder than expected**
-

**Time spent vs estimate:** ___ days vs estimated 10

**What I'd carry into Sprint 6 planning** (V2 — customer portal)
-

**Sprint 5 sanity review:** all green? ___

**First user encounter under V1.1:** when did you send the URL to a real human and what did they break? ___

→ Then **V2 — customer portal** (Sprint 6 planning). The pitch: patient-facing booking widget, self-service appointment booking, recurring routines, drag-drop schedule. Multi-month block of work and the first time we'd seriously think about multi-tenancy, billing, and "actual paying customers".
