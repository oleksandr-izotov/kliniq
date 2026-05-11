# Sprint 5 — V1.1 post-ship hardening + UX polish

**Goal:** Move Kliniq from "V1 shipped" to "prod-grade V1.1 — reliable infrastructure plus UX that doesn't feel raw to a first-time visitor". This sprint bundles the two deferred items from Sprint 4 (DB backup validation + scripted prod smoke), the hardening items the Sprint 4 retro flagged for V1.1 (offsite backups, Sentry, uptime monitoring, SSH key-only, Coolify proxy lockdown, CSP header, GHA cache), and the UX-sparseness items surfaced the day after shipping (admin sub-nav, demo data seed, onboarding wizard for the first admin, polished README, branded 404).

Two reasons to bundle: (1) infrastructure-only leaves the product feeling sparse to anyone we send the URL to; UX-polish-only leaves the product fragile in failure modes — neither alone moves V1 forward in a meaningful way. (2) Roughly 70% of the "feels raw" feedback maps to "admin nav missing + schedule is empty on fresh login + README is a stub" — none of which needs a separate B2B-go-to-market detour to fix.

**Definition of done:**

Reliability / hardening:

- [x] Daily `pg_dump` cron on the Hetzner box; retention keeps the last 14 days locally
- [ ] ~~Offsite backup to Backblaze B2 via Restic; one manual restore-test confirms the dump round-trips through B2 back into a working Postgres~~ — **deferred:** Day 52 punted out of V1.1 on 2026-05-09. Rationale: no paying clinic on the box means the only data at risk is the demo deploy's own dummy bookings; local 14-day retention is enough to cover that. Picks up again the day we onboard a real tenant.
- [x] Sentry hookup on both api (Spring Boot) and web (SvelteKit); error events flow to one project with `environment=prod` tag; source maps attached on the web side
- [x] UptimeRobot (or equivalent) pinging `/actuator/health/liveness` every 5 minutes; email alert verified by stopping the web container briefly
- [x] SSH hardening: `PasswordAuthentication no` in `/etc/ssh/sshd_config`, key-only login; fail2ban jail config verified
- [x] Coolify built-in proxy permanently disabled: `docker update --restart=no coolify-proxy` + verified across one daemon restart
- [x] CSP header added with a reasonable initial policy; verified no SPA console errors after rollout — implemented in `apps/web/svelte.config.js` (`kit.csp`) rather than Caddy after a Day 55 retro: SvelteKit's SSR'd inline scripts need per-page hash/nonce signing that only the framework can compute
- [x] GHA `api-image` job adds buildkit cache for Gradle deps + Docker layers — `cache-from: type=gha` + `cache-to: type=gha,mode=max` on the buildx step, `gradle/actions/setup-gradle@v4` handles Gradle deps cache. **Warm-run baseline: 2 m 03 s** (best) / 2-3 m typical, not the aspirational 90 s; bottleneck is `flywayMigrate` + `generateJooq` against a real Postgres service container, not Docker layers

UX polish:

- [x] `/(app)/admin/+layout.svelte` with a Tabs sub-nav (Users · Invitations · Audit)
- [x] ~~Flyway migration `V5__demo_seed.sql`~~ — implemented as `DemoDataSeeder` Spring `ApplicationRunner` bean gated behind `@ConditionalOnProperty(name = "app.demo.seed")` instead. Seeds 3 OR + 4 surgeons (mixed specialties) + 10 bookings spread across the next two weeks; idempotent (skips when rooms already exist). Reason for the swap: keeps `flyway_schema_history` clean of data-only entries and lets the seed toggle on/off without burning a migration slot
- [x] Onboarding wizard for the first admin signing up: clinic name + timezone, optional first OR, optional first invitation; one-shot, never shows again after the wizard's "Done"
- [x] `README.md` hero section rewritten: live URL, hero screenshot of `/schedule`, demo credentials, "Engineering deep dive" linking the load-bearing tests / migrations / ADRs
- [x] Branded 404 page replacing the SvelteKit default
- [x] Sprint 5 sanity checklist (below) all green

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

- [x] **Backups round-trip** — local pg_dump → sidecar restore-test verified; counts match. (Offsite B2 leg deferred — see Day 52.)
- [x] **Sentry events flow** — api crash (`/api/v1/dev/sentry-smoke`) + web crash (`setTimeout(() => { throw … })`) both visible on the Sentry dashboard within ~60 s.
- [x] **UptimeRobot pings** — `kliniq-api liveness` monitor green at 5-min interval; alert tested manually by stopping the api container briefly.
- [x] **SSH key-only** — `PasswordAuthentication no` honoured (override snippet `00-kliniq-hardening.conf` beats cloud-init's `50-cloud-init.conf` alphabetically); password-auth probe from a fresh terminal returns `Permission denied (publickey).` without password prompt.
- [x] **Coolify proxy locked** — `docker ps -a --filter 'name=coolify-proxy'` returns empty; removed manually in Sprint 4 Day 49 and the Coolify UI toggle is off so Coolify doesn't recreate it.
- [x] **CSP** — set via SvelteKit `kit.csp` (per-page `<meta>` with sha256 hashes); no SPA console violations across `/schedule`, `/operating-rooms`, `/admin/*`, `/settings/*`.
- [x] **Admin sub-nav** — Tabs (Users · Invitations · Audit log) on every `/admin/*` page with active-tab styling.
- [x] **Demo seed** — `APP_DEMO_SEED=true` on a fresh restart populated 3 OR + 4 surgeons + 10 bookings; idempotent re-run skipped cleanly with "operating rooms already exist" log line.
- [x] **Onboarding wizard** — first admin login triggers the wizard (3 steps, "Skip" allowed on 2 + 3); second login no longer shows it (`clinic_settings.onboarded_at` stamped).
- [x] **README polished** — hero (live URL + demo creds table) + Mermaid architecture diagram + Engineering deep-dive section with file-path links + Tech stack (V1.1) + Repository tour all present.
- [x] **404 branded** — `https://kliniq.izotov.dev/does-not-exist` renders the Kliniq-branded full-bleed illustration with a frosted-glass text card; SvelteKit default is gone.

---

## Sprint 5 retro

_Closed 2026-05-11._

**Coverage on the api codebase (JaCoCo, full repo, `./gradlew test jacocoTestReport`)**

| Counter | Covered | Missed | Coverage |
| --- | ---:| ---:| ---:|
| Lines | 3325 | 617 | **84.3 %** |
| Methods | 949 | 106 | 89.9 % |
| Classes | 233 | 19 | 92.5 % |
| Branches | 859 | 563 | 60.4 % |
| Instructions | 16 560 | 4 543 | 78.5 % |

Line coverage is the headline number; the branch number is lower because guard-clauses on auth-tokens and FSM transitions contain a lot of "wrong state" branches that aren't currently driven by integration tests. Worth tightening in Sprint 6.

**What went well**
- The two infrastructure surprises (Caddy CSP header invalidating SvelteKit's nonce-based CSP, Alpine `localhost` resolving to IPv6 before the IPv4 listener) both turned into one-line fixes once diagnosed. The split-the-problem-in-two-direction approach (header vs document, container-vs-loopback) was faster than reading docs cover-to-cover.
- `DemoDataSeeder` as an `ApplicationRunner` + `@ConditionalOnProperty` beats a Flyway V5 seed migration: opt-in via env var without touching `flyway_schema_history`, idempotent against partial runs, and the demo can be re-seeded by wiping rooms + restarting the api rather than burning a fresh migration number.
- A `00-` snippet prefix in `/etc/ssh/sshd_config.d/` is a clean override pattern — survives any cloud-init regeneration and beats editing the cloud-init file directly, because OpenSSH's first-match-wins rule means the alphabetically-earliest snippet wins.
- README split between hero (live demo + creds table) and "Engineering deep dive" (file-linked load-bearing decisions) ends up serving two readers well: a recruiter can click straight to the site; a reviewer can scroll one section down and skim the actual interesting code.

**What was harder than expected**
- CSP turned into three commits, not one. First version put it on Caddy and broke hydration; second moved it to SvelteKit with `mode: 'auto'` (nonces); third added an explicit sha256 hash for the `mode-watcher` inline theme-detect script that the framework's hash collector misses because of its `{@html}` injection path. Trade-off accepted: any future `mode-watcher` version bump that changes the body of that script will reintroduce the violation and need a one-line hash update.
- The Hetzner cloud-init snippet (`/etc/ssh/sshd_config.d/50-cloud-init.conf`) silently wins over the main `sshd_config` because of OpenSSH's "first match wins" rule + the Include directive ordering. Editing the main file alone did nothing visible until the snippet was either replaced or beaten alphabetically by a `00-` prefix.
- Sentry's "Allowed Domains" project-level CORS list defaulted to including localhost only on the kliniq-web project (not on kliniq-api), so the first browser event got 403'd from Sentry's edge despite a fully-wired SDK. Diagnosable by inspecting the POST response (CORS error has a recognisable shape vs a network failure), but a `Refused to connect` from CSP and a 403 from Sentry's allowlist look identical at first glance.
- The web container's `/healthz` looked broken on the first prod deploy of Day 54 because BusyBox `wget` resolves `localhost` to `::1` first and adapter-node only binds the IPv4 wildcard. Fix is one literal change (`http://localhost:3000` → `http://127.0.0.1:3000`) but discovering it required `netstat -tln` + `wget` from inside the container, since the site itself worked fine through Caddy.

**Time spent vs estimate:** ~3 days of execution wall time vs estimated 10 days. The compression came from (a) deferring Day 52 (Restic + B2) out of scope until a real paying tenant exists, and (b) keeping each day's commit tight rather than batching cross-cutting changes.

**Sprint 5 sanity review:** 10 of 11 green; offsite-backup leg deferred as documented.

**What I'd carry into Sprint 6 planning** (V2 — customer portal)
- **Multi-tenancy is the next big surface area.** V1 single-tenant means every Coolify deploy is per-clinic; V2 with patient portals needs tenant isolation in the data model + scoped sessions. Plan an ADR for it before any code.
- **Source-map upload pipeline for web should move into CI** rather than the Coolify on-host build. Right now `SENTRY_AUTH_TOKEN` lives on the deploy host and the upload happens on every Coolify Deploy; cleaner to bake the web image in GHA the same way api is, and let Coolify pull both from GHCR.
- **Branch CSP into strict-dynamic + nonces** so we can drop `'unsafe-inline'` from `style-src`. Requires shadcn-svelte to stop runtime-injecting per-component `<style>` blocks, or for SvelteKit's CSP hashing to be extended to cover them.
- **CI warm `api-image` time is currently 2-3 minutes**, not the aspirational 90 s. Bottleneck is `flywayMigrate` + `generateJooq` against a real Postgres service container, not Docker layers. If we ever care, options are (1) commit generated jOOQ sources (bigger repo, smaller CI), (2) split codegen out as a separately-cached step.

**First user encounter under V1.1:** _(deferred — no real-human demo session held yet; send link to one trusted reviewer for honest feedback during Sprint 6 planning.)_

→ Then **V2 — customer portal** (Sprint 6 planning). The pitch: patient-facing booking widget, self-service appointment booking, recurring routines, drag-drop schedule. Multi-month block of work and the first time we'd seriously think about multi-tenancy, billing, and "actual paying customers".
