# Kliniq

Operating room scheduling platform for medical clinics. Single-tenant SaaS that helps clinic staff manage operating rooms, surgeons, and bookings; later extends to a customer portal where surgeons can self-book available slots.

**Status:** Planning phase — Sprint 0 not yet started.
**Author:** Oleksandr Izotov ([@oleksandr-izotov](https://github.com/oleksandr-izotov))
**License:** MIT (TBD on first commit)

---

## How to use this repository

Everything is in [docs/](docs/). Read in this order:

1. **[docs/ROADMAP.md](docs/ROADMAP.md)** — what we are building (V1 / V2 / V3)
2. **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — system architecture and tech stack
3. **[docs/DOMAIN.md](docs/DOMAIN.md)** — domain model, entities, business rules
4. **[docs/REPO_STRUCTURE.md](docs/REPO_STRUCTURE.md)** — directory layout
5. **[docs/DECISIONS.md](docs/DECISIONS.md)** — architecture decision records (ADRs)
6. **[docs/VISUALS.md](docs/VISUALS.md)** — visuals/images needed and AI prompts
7. **[docs/STYLE.md](docs/STYLE.md)** — design system, colors, typography

Sprint plans (executable, day-by-day):

- **[docs/sprints/SPRINT_0.md](docs/sprints/SPRINT_0.md)** — foundation (3-5 days)
- **[docs/sprints/SPRINT_1.md](docs/sprints/SPRINT_1.md)** — auth (2-3 weeks)
- More sprint plans drafted as we approach them.

Brand work:

- **[brand-assets/](brand-assets/)** — logo, app icon, illustrations, OG image. See [brand-assets/README.md](brand-assets/README.md) for the full map and how each asset is used.

## Tech stack (V1)

- **Backend:** Kotlin 2.1 · Spring Boot 3.5 · Java 21 LTS · jOOQ · PostgreSQL 16 · Redis · Flyway
- **Frontend:** SvelteKit 2 · TypeScript 5 · Tailwind CSS · shadcn-svelte · TanStack Query
- **Auth:** Spring Security + custom session-based + WebAuthn4J (passkeys)
- **Tests:** Testcontainers · JUnit 5 · Vitest · Playwright
- **Local infra:** Docker Compose · Mailpit · mkcert
- **CI:** GitHub Actions
- **Deploy:** Coolify on Hetzner CPX21 (deferred until V1 works locally)

## Quick start (when Sprint 0 is done)

```bash
# Generate local TLS certs
mkcert -install
mkcert localhost

# Start infra
docker compose up -d

# Backend
cd apps/api && ./gradlew bootRun

# Frontend
cd apps/web && pnpm dev
```

Visit https://localhost:5173 (frontend) and https://localhost:8443 (API).

## Working principles

- **Every line of code is mine.** No copy-paste from other projects, no matter how similar.
- **Local-first.** Get V1 running on a laptop before touching cloud infra.
- **Secrets never in git.** `.env.example` only. Pre-commit hook with gitleaks blocks accidents.
- **Tests on critical paths from day one** — auth, booking, billing. Not "we'll add tests later."
- **Conventional Commits.** `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`.
- **Docs are source of truth.** If a decision isn't in `docs/`, it doesn't exist.

## If this chat / AI session disappears

You can continue from these docs alone. The sprint plans are detailed enough for a fresh AI (or yourself) to pick up. When starting a new conversation, hand the AI:

1. This README
2. The current sprint file
3. The relevant ADRs from `docs/DECISIONS.md`
