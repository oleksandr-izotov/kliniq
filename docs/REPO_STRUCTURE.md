# Repository Structure

Monorepo, pnpm workspace for Node packages, Gradle multi-module for the JVM side.

```
Kliniq/
├── README.md
├── LICENSE
├── .gitignore
├── .gitleaks.toml
├── .editorconfig
├── lefthook.yml                 # git hooks config
├── compose.yaml                 # local dev: postgres, redis, mailpit, minio
├── compose.override.example.yaml
├── pnpm-workspace.yaml
├── package.json                 # root: dev tools only (lint, format, commitlint)
├── .env.example                 # template, real .env in .gitignore
│
├── docs/                        # YOU ARE HERE
│   ├── ROADMAP.md
│   ├── ARCHITECTURE.md
│   ├── DOMAIN.md
│   ├── REPO_STRUCTURE.md
│   ├── DECISIONS.md
│   ├── STYLE.md
│   ├── VISUALS.md
│   └── sprints/
│       ├── SPRINT_0.md
│       ├── SPRINT_1.md
│       └── ...
│
├── apps/
│   ├── api/                     # Spring Boot + Kotlin backend
│   │   ├── build.gradle.kts
│   │   ├── settings.gradle.kts
│   │   ├── gradle.properties
│   │   ├── gradle/wrapper/
│   │   ├── gradlew, gradlew.bat
│   │   ├── Dockerfile
│   │   └── src/
│   │       ├── main/
│   │       │   ├── kotlin/com/kliniq/
│   │       │   │   ├── KliniqApplication.kt
│   │       │   │   ├── api/
│   │       │   │   │   ├── auth/                # /auth/* controllers + DTOs
│   │       │   │   │   ├── booking/             # /api/v1/bookings
│   │       │   │   │   ├── operatingroom/       # /api/v1/operating-rooms
│   │       │   │   │   ├── user/                # /api/v1/users
│   │       │   │   │   └── error/               # error response, global handler
│   │       │   │   ├── usecase/                 # application services
│   │       │   │   ├── domain/                  # entities, value objects, rules
│   │       │   │   │   ├── booking/
│   │       │   │   │   ├── user/
│   │       │   │   │   └── shared/
│   │       │   │   ├── persistence/
│   │       │   │   │   ├── booking/
│   │       │   │   │   ├── user/
│   │       │   │   │   └── jooq/                # generated jOOQ classes
│   │       │   │   ├── infra/
│   │       │   │   │   ├── security/            # filters, session config, csrf
│   │       │   │   │   ├── webauthn/            # passkey adapter
│   │       │   │   │   ├── audit/               # audit interceptor / writer
│   │       │   │   │   ├── sse/                 # SSE broadcasting
│   │       │   │   │   ├── mail/                # email sender (mailpit/resend)
│   │       │   │   │   └── ratelimit/           # resilience4j config
│   │       │   │   └── config/                  # @Configuration classes
│   │       │   └── resources/
│   │       │       ├── application.yml
│   │       │       ├── application-local.yml
│   │       │       ├── logback-spring.xml
│   │       │       └── db/migration/            # Flyway V__*.sql
│   │       └── test/
│   │           ├── kotlin/com/kliniq/
│   │           │   ├── api/                     # controller tests with MockMvc
│   │           │   ├── usecase/
│   │           │   ├── domain/                  # pure unit tests
│   │           │   ├── persistence/             # Testcontainers
│   │           │   └── support/                 # test fixtures, Testcontainers base
│   │           └── resources/
│   │
│   └── web/                     # SvelteKit frontend
│       ├── package.json
│       ├── svelte.config.js
│       ├── vite.config.ts
│       ├── tailwind.config.ts
│       ├── tsconfig.json
│       ├── playwright.config.ts
│       ├── Dockerfile
│       ├── public/              # static assets, favicons, og images
│       └── src/
│           ├── app.html
│           ├── app.css
│           ├── app.d.ts
│           ├── hooks.server.ts          # session check, csrf
│           ├── lib/
│           │   ├── api/                 # generated client + wrappers
│           │   ├── auth/                # login/logout/passkey helpers
│           │   ├── components/
│           │   │   ├── ui/              # shadcn-svelte primitives (copied)
│           │   │   ├── layout/          # AppShell, Sidebar, Topbar
│           │   │   ├── booking/         # BookingCard, BookingForm, ScheduleGrid
│           │   │   └── empty-states/    # EmptyState components with illustrations
│           │   ├── stores/              # writable stores (theme, user, etc.)
│           │   ├── utils/
│           │   └── i18n/                # message catalogs (EN only V1)
│           ├── routes/
│           │   ├── +layout.svelte       # root layout (theme, toaster)
│           │   ├── +page.svelte         # landing redirect
│           │   ├── (auth)/              # login, register, verify, reset
│           │   │   ├── +layout.svelte   # split-screen auth layout
│           │   │   ├── login/+page.svelte
│           │   │   ├── register/+page.svelte
│           │   │   ├── verify/+page.svelte
│           │   │   └── reset/+page.svelte
│           │   ├── (app)/               # authenticated routes
│           │   │   ├── +layout.svelte   # AppShell with sidebar
│           │   │   ├── +layout.server.ts   # require session
│           │   │   ├── dashboard/+page.svelte
│           │   │   ├── schedule/+page.svelte
│           │   │   ├── bookings/...
│           │   │   ├── operating-rooms/...
│           │   │   ├── users/...
│           │   │   └── settings/...
│           │   └── (errors)/
│           │       ├── +error.svelte
│           │       ├── 404/+page.svelte
│           │       └── 500/+page.svelte
│           └── tests/                   # Playwright specs go here OR adjacent
│
├── packages/                    # shared frontend packages
│   ├── api-client/              # @kliniq/api-client (generated from OpenAPI)
│   │   ├── package.json
│   │   ├── src/
│   │   └── tsconfig.json
│   └── ui-tokens/               # shared design tokens (colors, spacing as TS exports)
│       ├── package.json
│       └── src/
│
├── infra/                       # infra-as-code (deferred until deploy phase)
│   └── README.md                # placeholder
│
├── scripts/                     # dev scripts
│   ├── codegen-openapi.sh       # regenerate frontend types
│   ├── seed-db.sh               # local seed
│   └── reset-db.sh              # nuke + recreate
│
└── .github/
    └── workflows/
        ├── ci.yml               # backend + frontend test/lint/typecheck
        └── codeql.yml           # security scan (free)
```

---

## Why this layout

**Monorepo over polyrepo** — one place to track work, easier refactors across stack. Solo dev = no team-coordination cost from monorepo.

**`apps/` and `packages/`** — modern convention (used by Turborepo, Nx). `apps/` are deployable units, `packages/` are libraries.

**`docs/` at root, not in apps** — docs describe the system, not a single app.

**Backend in single Gradle project (not multi-module)** for V1 — splitting `booking-core` / `billing-core` is premature. We split when one of them grows past ~3000 lines and has clear boundaries. V3 likely.

**Frontend route groups** `(auth)`, `(app)`, `(errors)` — SvelteKit's parenthesized folders don't appear in URL. Lets us share layouts per role/section.

**`packages/api-client/`** — generated from OpenAPI spec, consumed by `apps/web`. Versioned together with backend, regenerated by `scripts/codegen-openapi.sh`.

**No `frontend/common/` shared UI package** — V1 has only one frontend app. We add a shared package only when V2 introduces customer portal AND we discover meaningful duplication. Don't pre-extract.

---

## Files always in `.gitignore`

```
# Env
.env
.env.local
.env.*.local
!.env.example

# Node
node_modules/
.pnpm-store/
.svelte-kit/
build/
dist/

# JVM
.gradle/
build/
out/
*.class

# IDE
.idea/
.vscode/
!.vscode/settings.json.example
*.iml

# OS
.DS_Store
Thumbs.db

# Logs
*.log
logs/

# Local certs
*.pem
*.key
*.crt
mkcert-*
```
