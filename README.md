# Kliniq

[![CI](https://github.com/oleksandr-izotov/kliniq/actions/workflows/ci.yml/badge.svg)](https://github.com/oleksandr-izotov/kliniq/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-emerald.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-3.5-6DB33F.svg?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![SvelteKit](https://img.shields.io/badge/sveltekit-2-FF3E00.svg?logo=svelte&logoColor=white)](https://svelte.dev)
[![Tailwind v4](https://img.shields.io/badge/tailwind-v4-06B6D4.svg?logo=tailwindcss&logoColor=white)](https://tailwindcss.com)

Operating room scheduling platform for medical clinics. Single-tenant SaaS that helps clinic staff manage operating rooms, surgeons, and bookings; later extends to a customer portal where surgeons can self-book available slots.

**Status:** Sprint 1 done — auth (password + passkey + reset + change-password), CSRF, rate limit + exponential backoff, HIBP breach checks, audit log. Sprint 2 (booking core) in progress.
**Author:** Oleksandr Izotov ([@oleksandr-izotov](https://github.com/oleksandr-izotov))
**License:** MIT

---

## Quick start

Prereqs (Windows / macOS / Linux):

- [Node.js 20+](https://nodejs.org), [pnpm 10+](https://pnpm.io)
- JDK 21 ([Temurin](https://adoptium.net/) recommended)
- [Docker Desktop](https://www.docker.com/products/docker-desktop/)
- [mkcert](https://github.com/FiloSottile/mkcert) (`winget install FiloSottile.mkcert` or `brew install mkcert`)

```bash
# 1. Trust the local certificate authority once
mkcert -install

# 2. Generate dev TLS certs (gitignored)
cd certs
mkcert -cert-file localhost.pem -key-file localhost-key.pem localhost 127.0.0.1 ::1
openssl pkcs12 -export -in localhost.pem -inkey localhost-key.pem -out localhost.p12 -name kliniq-api -password pass:changeit
cd ..

# 3. Install monorepo dependencies (lefthook hooks auto-install)
pnpm install

# 4. One command brings up infra + backend + frontend with combined logs
pnpm dev:all
```

`pnpm dev:all` brings up the docker stack, waits for postgres / redis / mailpit
to be ready, then runs `gradle bootRun` (the Spring app), `gradle --continuous build`
(re-compiles on save so Spring DevTools can hot-restart), and `vite dev` together
under [`concurrently`](https://www.npmjs.com/package/concurrently). One Ctrl-C
tears everything down.

Then visit:

- **<https://localhost:5173>** — frontend (login + register + passkey-protected app)
- **<https://localhost:8443/actuator/health>** — backend health
- **<https://localhost:8443/swagger-ui.html>** — interactive Swagger UI (OpenAPI 3.1 spec at `/v3/api-docs`)
- **<http://localhost:8025>** — Mailpit web UI (catches outgoing dev mail)

If port 5432 is in use by a native postgres, our compose maps host port **55432** instead — config already accounts for this.

If you'd rather run pieces by hand: `pnpm infra:up`, `pnpm dev:api`, `pnpm dev:web`,
`pnpm infra:down`. See `package.json` for the full script list.

### Regenerating the SPA's API types

The frontend's wire types live in [`apps/web/src/lib/api/generated.ts`](apps/web/src/lib/api/generated.ts),
produced from the backend's OpenAPI spec. Whenever a controller or DTO changes:

```bash
# Backend must be running at https://localhost:8443
cd apps/web
pnpm gen:api
```

`generated.ts` is committed to git so the SPA builds without a live
backend; CI catches drift via `svelte-check`.

---

## Repository tour

| Path | What lives here |
|---|---|
| [`apps/api/`](apps/api/) | Kotlin + Spring Boot 3.5 backend. Flyway migrations, Spring Security baseline, jOOQ joining in Sprint 1. |
| [`apps/web/`](apps/web/) | SvelteKit 2 frontend with Tailwind v4 and the shadcn-svelte primitives, HTTPS dev server proxying to the API. |
| [`brand-assets/`](brand-assets/) | Logo, app icon, illustrations, OG image. See [`brand-assets/README.md`](brand-assets/README.md). |
| [`certs/`](certs/) | mkcert-issued local TLS material (gitignored). [`certs/README.md`](certs/README.md) explains regeneration. |
| [`docs/`](docs/) | Architecture, domain, ADRs, sprint plans, design system. Source of truth — start here. |
| [`compose.yaml`](compose.yaml) | Local infra: postgres 16, redis 7.4, mailpit. |
| [`lefthook.yml`](lefthook.yml) | Pre-commit (gitleaks + lint) and pre-push (typecheck) hooks. |
| [`.github/workflows/ci.yml`](.github/workflows/ci.yml) | CI: lint, typecheck, test, gitleaks. |

## Documentation

Read in this order to onboard:

1. [`docs/ROADMAP.md`](docs/ROADMAP.md) — V1 / V2 / V3 scope and acceptance criteria
2. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — system diagram, layers, tech rationale
3. [`docs/DOMAIN.md`](docs/DOMAIN.md) — entities, ER model, business rules
4. [`docs/REPO_STRUCTURE.md`](docs/REPO_STRUCTURE.md) — directory layout
5. [`docs/DECISIONS.md`](docs/DECISIONS.md) — architecture decision records
6. [`docs/STYLE.md`](docs/STYLE.md) — Kliniq Emerald design system
7. [`docs/VISUALS.md`](docs/VISUALS.md) — visual style + AI prompts used to generate brand assets

Executable sprint plans:

- [`docs/sprints/SPRINT_0.md`](docs/sprints/SPRINT_0.md) — foundation **✓ done**
- [`docs/sprints/SPRINT_1.md`](docs/sprints/SPRINT_1.md) — authentication **✓ done**
- [`docs/sprints/SPRINT_2.md`](docs/sprints/SPRINT_2.md) — booking core (in progress)

## Tech stack (V1)

- **Backend:** Kotlin 2.1 · Spring Boot 3.5 · Java 21 LTS · Flyway · PostgreSQL 16 · Redis 7.4
- **Frontend:** SvelteKit 2 · Svelte 5 · TypeScript 5 · Tailwind CSS v4 · shadcn-svelte
- **Auth:** Spring Security + cookie sessions in Redis + WebAuthn4J passkeys (no JWT) + HIBP breach checks + per-IP exponential backoff
- **Tests:** JUnit 5 (60+ integration tests, 88.9% line coverage on the auth surface) · Vitest · Playwright (auth + passkey ceremonies via virtual authenticator)
- **Local infra:** Docker Compose · Mailpit · mkcert
- **CI:** GitHub Actions · gitleaks · commitlint · dependabot
- **Deploy (deferred until V1 works locally):** Coolify on Hetzner CPX21

## Working principles

- **Every line of code is mine.** No copy-paste from other projects, no matter how similar.
- **Local-first.** Get V1 running on a laptop before touching cloud infra.
- **Secrets never in git.** `.env.example` only. Pre-commit hook with gitleaks blocks accidents.
- **Tests on critical paths from day one** — auth, booking, billing. Not "we'll add tests later."
- **Conventional Commits.** `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`.
- **Docs are source of truth.** If a decision isn't in `docs/`, it doesn't exist.

## If you (or an AI) pick this up later

The docs in `docs/` plus the README are designed to be self-contained. To resume work, point a new agent at:

1. This README
2. The current sprint file (`docs/sprints/SPRINT_*.md`)
3. The relevant ADRs (`docs/DECISIONS.md`)
4. The latest commits on `main` so it sees what's actually built vs planned
