# Sprint 4 — V1 ship

**Goal:** Kliniq V1 runs on a real Hetzner VPS behind a real domain with a real TLS cert. Lighthouse ≥90 on /login and home. Playwright suite green on Chromium + Firefox + WebKit. The four `~` items from Sprint 3's sanity checklist (strict after-commit publish, long-idle SSE, SSE reconnect, two-instance pub/sub fan-out) close out. After this sprint we have a deployed product ready for actual user testing — M4 done.

The Sprint-3 retro called the four sanity items "untested rather than known-broken"; this sprint moves them to "asserted in CI", which is the bar V1 ship needs.

**Definition of done:**

- [ ] V1 deployed to Hetzner CPX21 via Coolify; reachable at a real https://… URL with a Let's Encrypt cert
- [ ] Production Dockerfiles for both apps: Spring API (multi-stage, JRE alpine, non-root user, healthcheck) and SvelteKit (Node adapter, non-root user, exposed `/healthz`)
- [ ] `application-prod.yml` profile reads every secret from env vars; no credentials checked into the repo
- [ ] Lighthouse score ≥90 on /login and the authenticated home dashboard (Performance + Accessibility + Best Practices + SEO)
- [ ] Playwright suite green on Chromium **and** Firefox **and** WebKit; the workers=1 sequencing carries across browsers
- [ ] `PATCH /api/v1/auth/me` updates the user's display name; small `/(app)/settings/profile` page calls it
- [ ] **Sanity #1 — strict after-commit publish.** Booking use cases run inside one `@Transactional` boundary; `BookingEventPublisher` switched to `@TransactionalEventListener(phase = AFTER_COMMIT)` so a rolled-back transaction never emits an SSE event. Regression test asserts a deliberately-rolled-back booking insert publishes nothing.
- [ ] **Sanity #2 — SSE long-idle.** Integration test subscribes to `/api/v1/events` over real HTTP for 90 seconds, asserts at least 5 heartbeat comment lines arrived (15-second cadence × 6, with one-message slack).
- [ ] **Sanity #3 — SSE reconnect.** Playwright test bounces the backend connection mid-test (route-intercept-and-abort the EventSource), waits for the SPA's auto-reconnect, asserts state reconciles via `onConnect` refetch.
- [ ] **Sanity #4 — two-instance pub/sub fan-out.** Testcontainers test stands up two Spring contexts against one Redis container, publishes from instance A's `BookingEventPublisher`, asserts instance B's `LocalEmitterRegistry` receives the event.
- [ ] CI green on the Sprint 4 surface; >70% line coverage holds across the new code
- [ ] Smoke `--target https://prod-url` runs end-to-end against the deployed instance (auth flow + booking flow); same script that works locally
- [ ] `README.md` updated: prod URL, "report bugs here" link, brief "how it's hosted" paragraph
- [ ] Sprint 4 sanity checklist (below) all green

**Estimated effort:** 11 days at chaotic pace.

**Prereq:** Sprint 3 done.

---

## Decisions locked (no debate this sprint)

- **Hosting:** Hetzner CPX21 (€5.83/mo, 2 vCPU, 4 GB RAM, 80 GB SSD, 20 TB traffic) — the smallest tier where Coolify + Postgres + Redis + Spring + SvelteKit fit comfortably with headroom for V1 traffic. CX22 (€4.59) is one rung cheaper at 2 GB RAM but JVM heap + Postgres shared_buffers + Redis allocations get tight. The €1.24/mo extra is not worth thinking about.
- **Orchestrator:** Coolify (ADR-012). Single-VPS, git-push-to-main → autodeploy, Caddy-managed TLS, UI for env vars + logs. Migration paths to managed Postgres / k3s / full enterprise are documented and stay open (see the "future migrations" thread in Sprint 3).
- **Deploy trigger:** push to `main`. Coolify polls/webhooks GitHub. No separate staging env for V1 — the entire V1 user base is one clinic, downtime cost is "the admin tries again in 30 seconds". When growth justifies a staging slot, we add a Coolify project on the same VPS.
- **Postgres:** Coolify-managed Postgres 16 container (auto-backup to local volume daily; offsite backup deferred). Migration to managed Postgres (Neon / Hetzner Managed) is an explicit Sprint-5+ knob, not V1.
- **Redis:** Coolify-managed Redis 7.4 container.
- **Email:** Resend (free tier — 3000/mo, plenty for V1's verify + reset + invitation traffic). Smtp creds go into Coolify env vars; backend already speaks plain SMTP via JavaMailSender.
- **DNS:** Cloudflare. Free tier, includes DDoS shielding, fast global resolution.
- **Domain:** TBD by user — registered through whichever registrar feels right; DNS NS records pointed at Cloudflare.
- **Monitoring:** Sentry for error tracking (free tier — 5k events/mo). Lighter-touch than Grafana Cloud for V1; the structured logs in Coolify UI cover the day-to-day. Grafana + Prometheus stays a Sprint-5 upgrade if it's needed.
- **Backups:** Coolify automatic Postgres dump → local volume daily. Offsite (Backblaze B2 + Restic, per ARCHITECTURE.md "deferred") is **not in V1 scope** — added in the first post-V1 polish sprint. The risk window is the time between dump-on-disk and a hardware failure, which is small for V1.
- **Single-instance deploy.** No multi-replica today even though the code is HA-ready. When clinic count > 1 or load justifies it, Coolify's replica slider goes up; nothing in the application changes.
- **Cross-browser policy.** Chromium + Firefox + WebKit on the latest two versions. No IE / no Safari < 17 / no mobile Safari (the SPA-on-mobile is "doesn't break", not "we ship and support it" — that's a V3 mobile-optimized layouts line).

---

## What we are NOT building this sprint

- Customer portal, drag-drop schedule, recurring routines — V2.
- Audit log UI extensions (export-to-CSV, full-text search) — V3.
- File uploads / consent forms — V3.
- Billing — V3.
- Resend → self-hosted SMTP migration — never (Resend is fine).
- Grafana Cloud / OpenTelemetry — Sprint 5 if needed.
- Backblaze B2 offsite backup — Sprint 5.
- Multi-region failover — never (V1 single-clinic).
- Staging environment — Sprint 5 if needed.

If anything from this list looks tempting mid-sprint, write it on a TODO and move on.

---

## Day-by-day plan

### Day 40 — Production Dockerfiles + compose-prod
- [ ] `apps/api/Dockerfile` — multi-stage build: `gradle :bootJar` in a JDK image, runtime in `eclipse-temurin:21-jre-alpine` as a non-root user. JRE not JDK; trims image by ~150 MB.
- [ ] `apps/web/Dockerfile` — multi-stage: `pnpm install --frozen-lockfile` + `pnpm build` in a `node:22-alpine` builder, runtime running the SvelteKit Node adapter under a non-root user.
- [ ] `compose.prod.yaml` — same five services as dev compose but pointed at the prod images, env-var-driven, and with `restart: unless-stopped`. Used for **local prod-image testing** before the Hetzner provision.
- [ ] `.dockerignore` for both apps — exclude `node_modules`, `build/`, `.gradle`, `e2e/`, `*.test.kts`. Trims build context, keeps secrets-by-mistake out.
- [ ] Verify locally: `docker compose -f compose.prod.yaml up` brings the stack up, login + booking flow works against the prod-image set.

### Day 41 — Prod healthchecks + structured logging
- [ ] SvelteKit `/(app)/healthz/+server.ts` (or top-level) returning `200 OK` + minimal JSON. Coolify hits it for liveness.
- [ ] Spring `application-prod.yml`: every secret is `${ENV_VAR}`-injected. Validate the prod profile boots with no `application-prod.yml` overrides committed.
- [ ] Logback config: prod profile writes JSON-per-line to stdout (Coolify aggregates), dev keeps the colored console. Standard fields: `timestamp, level, logger, message, traceId, requestId`. No PII.
- [ ] Spring Actuator: confirm `/actuator/health` is exposed (it is from Sprint 0). Add Coolify-friendly liveness/readiness endpoints if not already separate.
- [ ] Document the env-var contract in `apps/api/README.md` and `apps/web/README.md` — every required `ENV_VAR_NAME` listed with what it's for. Coolify config follows this list verbatim.

### Day 42 — Sanity #1: strict after-commit publish
- [ ] `BookingChangedEvent` Spring `ApplicationEvent` carrying the `BookingEvent` payload.
- [ ] `CreateBookingUseCase`, `UpdateBookingUseCase`, `TransitionBookingUseCase` annotated `@Transactional`. Their existing `eventPublisher.publish(...)` direct calls become `applicationEventPublisher.publishEvent(BookingChangedEvent(...))` — synchronous inside the transaction.
- [ ] `BookingEventPublisher.onBookingChanged(@TransactionalEventListener(phase = AFTER_COMMIT))` — fires only after the surrounding transaction commits, calls the existing Redis publish path.
- [ ] Regression test: `BookingPublishAfterCommitTest` — spin up a use case inside a `TransactionTemplate` that explicitly rolls back, assert no Redis message arrived (via `@MockitoSpyBean SseService` capturing zero `broadcast` calls).

### Day 43 — Sanity #2 + #3: long-idle SSE + reconnect e2e
- [ ] `SseHeartbeatIntegrationTest` — `@SpringBootTest(webEnvironment = RANDOM_PORT)`, opens an HTTP connection to `/api/v1/events` with a real session cookie via `RestTemplate` or `WebClient`-streaming, consumes the response body for 90 seconds, asserts at least 5 `: ping` comment lines arrived (allows one-message slack on either side of the cadence).
- [ ] `apps/web/e2e/realtime-reconnect.e2e.ts` — Playwright test. Opens `/schedule`, captures the active EventSource, uses `page.route('**/api/v1/events', r => r.abort())` to kill the connection mid-flight, waits ~5 seconds, removes the route override so reconnect succeeds, then triggers a booking change in a second context and asserts the first context picks it up via `onConnect`'s `loadSchedule()`.

### Day 44 — Sanity #4: two-instance pub/sub fan-out
- [ ] `TwoInstancePubSubTest` — programmatic `SpringApplication.run(...)` twice in `@BeforeAll` against the same Redis container (Testcontainers). Both contexts get their own `RedisMessageListenerContainer` + their own `SseService` spy. Publish from instance A's `BookingEventPublisher`, assert instance B's `SseService.broadcast` was called within 2 seconds.
- [ ] If the second-context spin-up turns out to be a substantial yak: shave the test down to a single-context test that publishes raw to Redis and verifies the existing subscriber's behaviour matches what a peer publish would look like — not as load-bearing as the two-context case, but still a regression catch.

### Day 45 — Self-service displayName change
- [ ] `usecase/auth/UpdateDisplayNameUseCase.kt` — single field, validates length `1..100`, audits `user.display_name_changed` with before/after.
- [ ] `PATCH /api/v1/auth/me` — `{ "displayName": "..." }`. Same auth gate as `/me`. Returns updated `UserResponse`.
- [ ] SPA: extend `/(app)/settings/security/+page.svelte` with a small "Display name" input + "Save" button (or fold into a new `/(app)/settings/profile/+page.svelte` if the security page gets crowded). Toast on success.
- [ ] Integration test: change display name, `/me` reflects, audit row written.
- [ ] e2e: hits the new field on the security page, asserts the home dashboard's "Welcome back, X" updates.

### Day 46 — Lighthouse pass + tuning
- [ ] Local Lighthouse run against the dev `pnpm build && pnpm preview` (closer to prod than `pnpm dev`) on `/login` and home. Capture the baseline.
- [ ] Likely fixes (each only applied if Lighthouse flags it):
  - `font-display: swap` on any web font import.
  - Image lazy-load attribute on the login illustration.
  - Code-split route-level chunks if the initial bundle is heavy (SvelteKit does this by default for routes; verify the `(auth)` and `(app)` groups split cleanly).
  - Remove unused dependencies if any (`pnpm-deduplicate`).
  - HTML preload hints for the login illustration (LCP candidate).
- [ ] Re-run Lighthouse; assert ≥90 on Performance, Accessibility, Best Practices, SEO for both routes.
- [ ] Add a CI-friendly Lighthouse-CI step OR document the manual measurement procedure in `README.md`. CI integration is nice but not blocking — the score itself is what V1 acceptance demands.

### Day 47 — Cross-browser smoke
- [ ] Extend `playwright.config.ts` with two more `projects`: Firefox (`devices['Desktop Firefox']`) and WebKit (`devices['Desktop Safari']`). Keep `workers: 1` per project.
- [ ] `npx playwright install firefox webkit` adds the runtimes locally.
- [ ] Run the suite on all three projects. Catalogue and fix what falls — likely `<dialog>` polyfill differences (WebKit handles `showModal` differently), date-input UI variances (the booking dialog's date / time inputs render wildly across browsers), CSS oklch fallbacks (mode-watcher dark theme).
- [ ] Lock CI to all three projects. Suite runtime stays under ~2 minutes total.

### Day 48 — Hetzner + Coolify provision (joint day)
- [ ] **User:** buy Hetzner CPX21, get the IP address. Choose a region (Germany Falkenstein for EU, Helsinki/Ashburn for elsewhere).
- [ ] **User:** point a fresh domain (or subdomain) at the VPS via Cloudflare; A record IPv4, AAAA IPv6.
- [ ] **User:** SSH in once, install Coolify with the official one-line script. Open Coolify on `https://{ip}:8000`, set admin password.
- [ ] **Me:** prepare a deploy checklist (`docs/DEPLOY.md` or section in `README.md`) — exact steps to wire Coolify to the GitHub repo, exact env vars to set, exact ports to expose.
- [ ] **User:** follow the checklist — connect the GitHub repo, create two Coolify "applications" (`api` + `web`) pointing at the respective Dockerfiles, plus `postgres` + `redis` managed services. Set every env var from the Day-41 contract list.

### Day 49 — First prod deploy + smoke
- [ ] `git push origin main` triggers Coolify auto-build for both apps.
- [ ] Coolify Caddy auto-provisions the Let's Encrypt cert. Domain serves https://… within 5 minutes.
- [ ] Run `python scripts/smoke_test.py --target https://your-prod-url --include-booking` — same script, new `--target` flag (one-line addition). Should be green end-to-end.
- [ ] Hit `/actuator/health`, `/healthz`, `/v3/api-docs` from outside — assert 200 / 200 / 200 (or 401 on `/v3/api-docs` if we lock it down for prod).
- [ ] Browser-test the live URL: register → verify (real email lands in inbox via Resend) → login → create booking → see SSE updates.
- [ ] Catalogue every prod-vs-dev divergence. Common ones: cookie `Secure` flag (must be true behind HTTPS), CORS not pointing at the right origin, CSP blocking the SPA bundle.

### Day 50 — Sprint close + V1 ship 🎉
- [ ] Run `./gradlew check` + measured JaCoCo coverage on Sprint 4 surface ≥70%.
- [ ] Tick DoD boxes.
- [ ] Write retro.
- [ ] Tag the commit: `git tag v1.0.0` + `git push --tags`.
- [ ] Update `README.md` with the prod URL + a "Report bugs at github.com/.../issues" line.
- [ ] Commit + push, last commit titled `docs(sprint4): close out — V1 shipped`.
- [ ] Close Sprint 4. M4 — V1 ship — done.

---

## Sprint 4 sanity checklist

- [ ] **Strict AFTER_COMMIT publish** — booking use cases run inside one `@Transactional`; `BookingEventPublisher` is `@TransactionalEventListener(AFTER_COMMIT)`; rolled-back transactions emit no SSE event (regression test asserts this).
- [ ] **SSE long-idle** — connection survives a 90-second idle window; heartbeat comment lines arrive at ~15-second cadence.
- [ ] **SSE reconnect** — client drops the EventSource and the SPA reconnects within `EventSource`'s native retry window; `onConnect` refetches `loadSchedule()` so missed events get reconciled.
- [ ] **Two-instance pub/sub fan-out** — events published from one Spring instance reach SSE subscribers connected to a different instance, asserted in a Testcontainers test.
- [ ] **TLS** — every prod URL is https only, http redirects to https. HSTS header set.
- [ ] **Cookies** — session cookie has `Secure`, `HttpOnly`, `SameSite=Lax`, `__Host-` prefix in prod (Sprint 1 already wired this; verify the prod flag is on).
- [ ] **CSP / clickjacking headers** — Spring Security defaults are on (`X-Frame-Options DENY`, `X-Content-Type-Options nosniff`); CSP is documented if added.
- [ ] **No secrets in repo** — env vars only, double-checked with `gitleaks` (lefthook already runs it).
- [ ] **Container security** — both Dockerfiles run as non-root, healthcheck endpoints respond.
- [ ] **DB backup** — Coolify is taking daily Postgres dumps; one manual restore-test confirms the dump round-trips.
- [ ] **Lighthouse ≥90** — measured on /login and home, all four categories.
- [ ] **Cross-browser** — Chromium + Firefox + WebKit suites all green.
- [ ] **Smoke against prod** — `scripts/smoke_test.py --target https://… --include-booking` passes against the deployed instance.

---

## Sprint 4 retro

_(filled at sprint close)_

**Coverage on the Sprint 4 surface (JaCoCo, `gradlew check`)**

_(table filled at close — same shape as Sprint 2 / 3)_

**What went well**
-

**What was harder than expected**
-

**Time spent vs estimate:** ___ days vs estimated 11

**What I'd carry into Sprint 5 planning** (post-V1 polish — backups offsite, observability, staging env, multi-replica)
-

**Sprint 4 sanity review:** all green? ___

**First user encounter:** when did you give the URL to a real human and what did they break? ___

→ Then **post-V1 polish + V2 customer portal** — Sprint 5 planning when V1 has run for a week or two and we have real-world signal on what to harden next.
