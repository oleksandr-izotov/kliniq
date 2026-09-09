# Pause & resume notes

**Paused:** 2026-05-23, after Sprint 6 Day 65.
**Redeployed:** 2026-09-09 — moved off Hetzner/Coolify onto a self-managed VPS in
Reykjavík behind nginx. The demo is live again; see the deploy section below and
[RUNBOOK.md](./RUNBOOK.md) for the current procedure.
**Reason:** No time to invest right now; project isn't generating value yet. Parking it cleanly so it can be picked back up without re-learning the context.

This file is the single entry point for "I'm back, what now?". Read it first, then [ROADMAP.md](./ROADMAP.md) and the open sprint file [sprints/SPRINT_6.md](./sprints/SPRINT_6.md).

---

## Where we stopped

- **Live:** https://kliniq.izotov.dev — V1.1 shipped, V1.2 in progress.
- **Branch:** `main`, clean working tree, everything pushed. Last commit `794de85` (Day 65 empty states), deployed to prod and verified (webp assets serving, liveness 200).
- **Current sprint:** Sprint 6 (V1.2 — mobile + drag-drop + feedback). Days 61–65 done, **Days 66–70 remaining.**

### Sprint 6 — what's left

| Day | Task | State |
|---|---|---|
| 61–63 | Sprint plan + mobile-responsive every screen | ✅ done |
| 64 | Drag-drop reschedule in /schedule | ✅ done |
| 65 | Branded empty-state illustrations (5 screens) | ✅ done, deployed |
| **66** | **Feedback round** — email demo link to 2–3 humans, capture quotes in `docs/FEEDBACK_V1_1.md` | ⬜ **needs the user** (real reviewers) |
| **67** | Apply top-3 feedback fixes | ⬜ depends on 66 |
| **68** | Web image → built in GHA & pushed to GHCR (stop building it on the deploy host); move `SENTRY_AUTH_TOKEN` into a GHA secret | ⬜ tech debt, code-only |
| **69** | Branch coverage push: `com.kliniq.usecase.**` ≥ 75 %, `BookingStatus.canTransitionTo` 100 % (currently ~60.4 % overall) | ⬜ code-only |
| **70** | V1.2 retro + tag `v1.2.0` + GitHub Release | ⬜ closes sprint |

**Lowest-friction restart:** Day 68 or Day 69 — both are pure code, no waiting on humans, and leave the repo in a tidier state. Day 66 is the highest-value but needs other people's time, so it's the natural thing to kick off in parallel the day you return.

---

## How to resume work

**Run locally** — see [README.md](../README.md) Quick start and [RUNBOOK.md](./RUNBOOK.md). In short: `docker compose up` (postgres + redis + mailpit), then `./gradlew :apps:api:bootRun` and `pnpm --filter ./apps/web dev`. HTTPS dev cert on `:8443`.

**Deploy** — there is no Coolify any more. The stack runs from
`/srv/kliniq/compose.izotov.yaml` on `$KLINIQ_HOST`, with the host's nginx as
ingress. Web builds on the host (`docker compose -f compose.izotov.yaml up -d
--build web`); the api image is built on a machine with a JDK — jOOQ needs a live
database at compile time — and loaded over ssh. Full commands in
[RUNBOOK.md](./RUNBOOK.md) under "Current deployment".

**Gate before any commit** — `./gradlew check` (api) and `pnpm --filter ./apps/web check && pnpm --filter ./apps/web lint` (web). lefthook runs gitleaks + lint + commitlint on commit, svelte-check on push. Commits are conventional-commit format; **no Claude co-author** (portfolio = sole-author).

**Cost while paused** — none of its own: the demo shares a VPS already paid for
by other projects. Postgres is dumped nightly at 03:00 UTC by
`scripts/backup-pg.sh` with 14-day retention, and the restore path was verified
on 2026-09-09 by loading the latest dump into a scratch database and comparing
row counts.

---

## After Sprint 6 → V2

V2 is customer portal + multi-tenancy. **First task is an ADR**: per-tenant deploy vs row-level isolation vs schema-per-tenant — decided *before* any code touches `users`, `bookings`, or `clinic_settings`. That's a multi-month block and the first time billing / GDPR data-export-delete / real paying customers enter the picture. Don't start V2 code without that ADR.

---

## Honest audit (2026-05-23)

**Snapshot:** ~9.6k LOC Kotlin (api) + 6.4k LOC Kotlin tests + 6.7k LOC Svelte/TS (web). 96 commits across 7 sprints. 5 Flyway migrations. Clean layered architecture: `api → usecase → domain → persistence → infra`.

### What's genuinely strong

- **Architecture.** Proper layered/DDD separation, not a controller-soup. Domain logic (booking FSM, overlap rules) sits in `domain/`, isolated from web and persistence. This is above typical solo-project quality.
- **Backend test discipline.** 6.4k test LOC against 9.6k main — a ~0.67 ratio with 24 test files including Testcontainers integration. Rare for a solo project.
- **Auth, done the hard (right) way.** Self-hosted Argon2id + WebAuthn passkeys + Redis-backed sessions + CSRF + per-IP rate limiting. You built what most people outsource to Auth0, and it works in prod.
- **Real operations.** Deployed and running, with nightly verified pg_dump,
  fail2ban and hardened SSH. This is a running product, not a localhost demo.
  *(2026-09-09: after the move, Sentry has no DSN configured and there is no
  uptime monitor — both were tied to the old host and need re-wiring.)*
- **CI + hygiene.** ktlint, detekt, JaCoCo, gitleaks, commitlint, eslint, prettier, svelte-check, vitest — all wired. Conventional commits, lefthook hooks, ADRs in `DECISIONS.md`, scoped roadmap.

### Real gaps

- **Frontend tests are thin.** 1 web test file vs 24 backend. Playwright E2E exists but is **not in CI** (deferred). The booking/drag-drop/auth flows have no automated UI coverage — the biggest test risk.
- **Branch coverage ~60.4 %**, below the 75 % target on `usecase`/`domain`. (Sprint 6 Day 69 addresses this — still open.)
- **Zero real users.** The "0/10 on real users" gap (Sprint 6 Day 66) is unaddressed. The product has never been stress-tested by someone who isn't the author.
- **Single-tenant.** By design for V1, but it caps the product at "one clinic" until the V2 multi-tenancy ADR + work lands.
- **Web still builds on the deploy host** (Day 68 tech debt) — slower, less reproducible deploys than the api's GHCR pipeline.

### Bottom line

As an **engineering artifact / portfolio piece**: genuinely strong — senior-level discipline in architecture, security, and ops that stands out in a technical interview and backs up the "sole author" claim. As a **business**: it's a well-built, well-run MVP with no market validation — single-tenant, no users, no revenue. The instinct that "it isn't bringing anything yet" is commercially accurate. The value captured so far is the skills demonstrated and the codebase itself, not income. Resuming makes sense if the goal is portfolio depth or eventually finding a real first clinic; pausing is a reasonable call if neither is urgent right now.
