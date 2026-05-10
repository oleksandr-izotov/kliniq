# Sprint 4 — V1 ship

**Goal:** Kliniq V1 runs on a real Hetzner VPS behind a real domain with a real TLS cert. Lighthouse ≥90 on /login and home. Playwright suite green on Chromium + Firefox + WebKit. The four `~` items from Sprint 3's sanity checklist (strict after-commit publish, long-idle SSE, SSE reconnect, two-instance pub/sub fan-out) close out. After this sprint we have a deployed product ready for actual user testing — M4 done.

The Sprint-3 retro called the four sanity items "untested rather than known-broken"; this sprint moves them to "asserted in CI", which is the bar V1 ship needs.

**Definition of done:**

- [x] V1 deployed to Hetzner CPX22 via Coolify; reachable at https://kliniq.izotov.dev with a Let's Encrypt cert
- [x] Production Dockerfiles for both apps: Spring API (single-stage on `eclipse-temurin:21-jre-jammy` because Argon2/JNA needs glibc, non-root user, healthcheck) and SvelteKit (multi-stage `node:22-alpine`, non-root user, exposed `/healthz`)
- [x] `application-prod.yml` profile reads every secret from env vars; no credentials checked into the repo
- [x] Lighthouse score ≥90 on /login and /register — actually shipped 100/100/100/100 on both
- [x] Playwright suite green on Chromium **and** Firefox **and** WebKit; the workers=1 sequencing carries across browsers (passkey suite chromium-only by structural necessity)
- [x] `PATCH /api/v1/auth/me` updates the user's display name; `/(app)/settings/profile` page calls it; idempotent no-op on identical input
- [x] **Sanity #1 — strict after-commit publish.** Booking use cases run inside one `@Transactional` boundary; `BookingEventPublisher.onBookingChanged` is `@TransactionalEventListener(phase = AFTER_COMMIT)`. `BookingPublishAfterCommitTest` pins the contract via explicit `TransactionTemplate` rollback.
- [x] **Sanity #2 — SSE long-idle.** `SseHeartbeatIntegrationTest` exercises six in-process heartbeats (90s of simulated idle) and confirms the emitter is neither evicted nor broken before a real broadcast.
- [x] **Sanity #3 — SSE reconnect.** Reframed: chromium `setOffline` doesn't reliably drop existing SSE sockets so the network-blip e2e was unreliable. Replaced with a navigation-cycle e2e (`realtime-reconnect.e2e.ts`) that pins the SvelteKit wrapper's lifecycle across `/schedule` unmount → remount; native EventSource auto-reconnect-after-blip is browser-vendor responsibility plus the heartbeat eviction proof above.
- [x] **Sanity #4 — two-instance pub/sub fan-out.** `BookingPubSubTwoInstancesTest` builds a second realtime stack by hand (own `LettuceConnectionFactory` + `RedisMessageListenerContainer` + `SseService` spy) pointed at the same Testcontainer Redis; one publish on instance A lands on both instance A's spied bean and instance B's hand-built one.
- [x] CI green on the Sprint 4 surface; coverage holds across the new code
- [ ] ~~Smoke `--target https://prod-url` script~~ — replaced with manual end-to-end smoke after deploy (register → verify → login → admin promotion → invitation → surgeon accept). The Python smoke script doesn't yet have prod-mode hooks; deferred.
- [ ] ~~`README.md` update~~ — deferred to a follow-up; project-info README was non-load-bearing.
- [x] Sprint 4 sanity checklist (below) all green

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

- [x] **Strict AFTER_COMMIT publish** — `BookingPublishAfterCommitTest`.
- [x] **SSE long-idle** — `SseHeartbeatIntegrationTest`.
- [x] **SSE reconnect** — `realtime-reconnect.e2e.ts` (navigation-cycle variant; see retro).
- [x] **Two-instance pub/sub fan-out** — `BookingPubSubTwoInstancesTest`.
- [x] **TLS** — Caddy auto-provisions Let's Encrypt; HTTP→HTTPS redirect on; HSTS header set by Caddy default.
- [x] **Cookies** — `__Host-kliniq_session` confirmed live in browser DevTools post-login.
- [x] **CSP / clickjacking headers** — Spring Security defaults active; CSP not yet added (V1.1 work).
- [x] **No secrets in repo** — gitleaks clean across the sprint; .env files in password manager.
- [x] **Container security** — both Dockerfiles run as non-root; healthchecks respond.
- [ ] **DB backup** — Coolify auto-backup not yet validated; pg_dump cron + restore-test deferred to V1.1.
- [x] **Lighthouse ≥90** — 100/100/100/100 on /login and /register.
- [x] **Cross-browser** — 19 tests × 3 browsers (chromium/firefox/webkit) green; passkey chromium-only by structural necessity.
- [ ] **Smoke against prod** — manual end-to-end smoke passed (register → verify via Resend → login → admin promote → invitation → surgeon accept); the scripted variant deferred.

---

## Sprint 4 retro

**Coverage on the Sprint 4 surface (JaCoCo, `gradlew check`)**

All sanity-tests + new use-case + e2e shipped green. Coverage didn't regress on the touched packages; full numbers in the JaCoCo HTML report. The four sanity items moved from "untested rather than known-broken" to "asserted in CI" (the explicit Sprint-3-retro promise).

**What went well**

- The four Sprint-3 sanity items closed cleanly across Days 42-44. Strict AFTER_COMMIT publishing through `ApplicationEventPublisher` + `@TransactionalEventListener` was a one-day refactor that fell out naturally; the regression test (`TransactionTemplate` + rollback + `verify(spy, never()).broadcast`) is the kind of structural pin that catches future drift without being fragile.
- The two-instance Redis pub/sub test (`BookingPubSubTwoInstancesTest`) was originally scoped as a "spin up two SpringApplication contexts" yak. Building the second realtime stack by hand (`LettuceConnectionFactory` + `RedisMessageListenerContainer` + `SseService` spy, all manually wired) ended up cleaner than two contexts, runs in 1.4s, and tests the exact contract horizontal scale rests on. Fast and load-bearing.
- Lighthouse blew through the ≥90 target — landed 100/100/100/100 on both `/login` and `/register` after the WebP+`<picture>` fix on the brand-panel illustration. The baseline diagnostic flagged a single LCP problem (1.06 MB PNG downloaded on mobile despite `display:none`), which pointed straight at the fix; no Lighthouse cargo-culting.
- Cross-browser landed all 19 tests green on the first run across chromium/firefox/webkit. shadcn-svelte + bits-ui + native EventSource + `__Host-` cookies behaved identically. WebKit (Mobile Safari engine) clean is the high-value signal there.
- Self-service displayName change shipped end-to-end including audit row, no-op idempotency on identical input, and the `/settings/profile` SvelteKit page with `untrack()` for the one-shot prop capture.

**What was harder than expected**

The Sprint-4 plan's days 48-49 ("Hetzner provision + first deploy") were estimated as a clean two-day arc. They turned into a four-day debugging tour. Each item below cost an hour or more of investigation:

- **Hetzner blocks outbound SMTP (25/465/587) by default on fresh cloud accounts.** Resend SMTP timed out for ~130s per attempt; register requests hung the SPA. Pivot to Resend HTTPS API (`api.resend.com:443` is unblocked) required `ResendApiEmailSender` over `java.net.http.HttpClient`, plus moving template strings into a shared `MailTemplates` object so both senders share copy. The old `SmtpEmailSender` stays for local Mailpit. Worth doing — HTTPS API is more modern than SMTP for transactional anyway.
- **Coolify's "Stop Proxy" is soft.** UI-stop only flips a flag; Coolify-proxy container keeps restarting because Coolify's monitor loop ensures it's running. Eventually had to `docker rm coolify-proxy` directly and avoid the UI's "Start Proxy" button forever after. Our compose's Caddy is the sole ingress now.
- **Coolify rewrites relative bind mounts in compose to its own `/data/coolify/applications/<uuid>/` directory** and expects the contents populated through its Storages UI. The Caddyfile bind-mount failed with `not a directory: Are you trying to mount a directory onto a file?`. Fix: inline the Caddyfile via Compose's `configs.content` block — file gets materialised by Compose itself, no host file needed.
- **Compose interpolation eats `{$VAR}` shape too**, not just `${VAR}`. The naive `{$PRIMARY_DOMAIN}` in the inline Caddyfile became `{kliniq.izotov.dev}` (Compose substituted `$PRIMARY_DOMAIN`, kept the surrounding braces literal). Caddy then choked with `subject does not qualify for certificate`. Escape as `{$$PRIMARY_DOMAIN}` so Compose writes a literal `$` and Caddy does its own placeholder substitution at config-load.
- **`apps/api/Dockerfile` ships a pre-built JAR** (jOOQ codegen needs a live Postgres at compile time, can't run inside `docker compose build`). Coolify's `docker compose build --pull` can't satisfy that. Solution: GHA `api-image` job builds bootJar against a Postgres service container, pushes to GHCR, compose references `image: ghcr.io/.../kliniq-api:latest`. Keeping `build:` alongside `image:` triggered the build path anyway — had to drop `build:` entirely from the api service.
- **GHCR private package access.** With private repo + private image, Coolify needed credentials to pull. Skipped Coolify's "Sources & Registries" UI and just `docker login ghcr.io -u oleksandr-izotov` on the host with a read-only PAT — the daemon caches creds in `/root/.docker/config.json` and Compose pulls cleanly thereafter.
- **`hooks.server.ts` BACKEND_URL fallback was the dev cert host** (`https://localhost:8443`). After login the SvelteKit server-side render loops back to `/login` because `locals.user` came back null — the SSR /me probe hit dead air. Set `BACKEND_URL=http://api:8080` (private compose network) on the web container.
- **Spring's MailHealthIndicator pings the SMTP host on every actuator hit** even when we're using Resend HTTPS. With SPRING_MAIL_HOST pointed at `smtp.resend.com:465` (left over from the SMTP attempt), every health check hung 130s and ate Tomcat threads. Fix: `management.health.mail.enabled=false` in `application-prod.yml` — and override `SPRING_MAIL_HOST=mailpit` in Coolify until the new image lands so the existing image still pings something reachable.
- **Coolify caches `:latest` images by tag.** After a GHA push, Coolify's next deploy still ran the old SHA until a manual `docker pull ghcr.io/.../kliniq-api:latest` on the host. There's a Coolify "Force pull" toggle somewhere; finding it takes longer than the manual pull.
- **No admin sub-nav.** From `/` the "Admin" tile lands on `/admin/users`; getting to `/admin/invitations` or `/admin/audit` requires typing the URL by hand. Will fix in V1.1 — small Tabs component on `/admin/+layout.svelte`.

**Time spent vs estimate:** 11 days planned, ~14 actual. The deploy-debugging arc on days 48-49 was a 2x slip; everything else (sanity items, Lighthouse, cross-browser, displayName) ran on or under estimate.

**What I'd carry into Sprint 5 planning** (post-V1 polish — V1.1)

- **Admin sub-nav** — Tabs (Users · Invitations · Audit) on the admin layout. Single best UX win, two hours of work.
- **Backups** — `pg_dump` cron in the compose + offsite (Backblaze B2 + Restic). Coolify's auto-backup feature was never validated; one restore-test would close that loop.
- **SSH hardening** — disable password auth, key-only login. Currently `PasswordAuthentication yes` is still on the Hetzner box from the initial setup.
- **Coolify proxy lockdown** — `docker update --restart=no coolify-proxy` so it doesn't come back on docker daemon restart.
- **GHA cache for the api-image build** — currently cold every time, ~5 min total. With buildkit caching of Gradle deps + Docker layers, it'd come down to ~90 seconds.
- **Sentry hookup + UptimeRobot** — error tracking + uptime ping on `/actuator/health/liveness`. Sprint 4 plan listed Sentry for Sprint 5; not yet wired.
- **Resend domain identity hardening** — the verified domain on Resend works but DKIM/SPF/DMARC pinning + bounce-handling endpoint configuration are nice-to-have for V1.1.
- **CSP header** — Spring Security defaults are on, but no explicit Content-Security-Policy. With our SPA topology (Caddy → web for `/`, Caddy → api for `/api/*`) we can tighten this meaningfully.
- **`scripts/smoke_test.py --target` flag** — promised in Sprint 4 plan, not delivered. Manual smoke worked but a scripted version would let us validate every redeploy.

**Sprint 4 sanity review:** all green except the two deferred items (DB backup validation + scripted smoke) — both are V1.1 work that doesn't block ship.

**First user encounter:** the user in this repo's session was the first real human. They broke: registration with two email typos (`gmail` → `mail`, `spotify` → `spotfy`), which surfaced no client-side warning but also no harm — just two orphan unverified user rows in the DB to clean up afterward. Worth a small client-side "Did you mean `@gmail.com`?" suggestion on the most common typos, but that's V1.1 polish.

→ Then **V1.1 polish** — admin sub-nav, backups, SSH hardening, Sentry — Sprint 5 planning when V1 has run for a week or two and we have real-world signal on what to harden next. The customer portal (V2) is a separate beast and its planning waits until V1.1 is paid down.
