# Architecture Decision Records (ADRs)

Each ADR is a snapshot of "we decided X because Y, alternatives were Z." Don't edit historical ones; add a new one if a decision is reversed.

Format per record: status, context, decision, consequences.

---

## ADR-001 · Kotlin over Java for backend

**Status:** Accepted · 2026-05-03

**Context.** Original team project used Java 25. Kotlin is the JetBrains-blessed JVM language with excellent Spring Boot support since 5.x. Tradeoffs: less boilerplate, null-safety, sealed classes, data classes, coroutines vs slightly slower compilation, smaller (but still huge) hiring pool, learning curve.

**Decision.** Use Kotlin 2.1+ on Java 21 LTS toolchain.

**Consequences.**
- We get data classes for DTOs (no Lombok needed) and sealed classes for state machines.
- Kotlin DSL for Gradle (`build.gradle.kts`) — type-safe build files.
- `ktlint` + `detekt` for linting (replace Checkstyle/SpotBugs).
- Spring's Kotlin support is first-class: `@Configuration` works, kotlin-spring plugin opens classes for proxying.

---

## ADR-002 · SvelteKit over Next.js / Nuxt

**Status:** Accepted · 2026-05-03

**Context.** Need a modern frontend framework with SSR, file-based routing, and a strong UI ecosystem. Candidates: Next.js 15 (React), Nuxt 4 (Vue), SvelteKit 2 (Svelte), SolidStart, Remix.

**Decision.** SvelteKit 2 + shadcn-svelte + Tailwind.

**Consequences.**
- Smaller hiring market than React, but distinctive in a portfolio.
- Smaller bundles, faster dev iteration, less boilerplate.
- Component model is intuitive (no JSX, less hooks ceremony).
- Risk: smaller ecosystem; fewer ready-made libs (mitigated: shadcn-svelte covers UI; rest is small).

---

## ADR-003 · Roll our own session-based auth on Spring Security (no Auth0/Keycloak/Clerk)

**Status:** Accepted · 2026-05-03

**Context.** Auth options: managed (Auth0 / Clerk / Logto), self-hosted (Keycloak / Authentik / Ory), or DIY on Spring Security. The original team project used Auth0 with two audiences. For a portfolio piece, owning auth shows depth; for a real product, managed services are usually cheaper time-wise.

**Decision.** Build session-based auth on Spring Security with WebAuthn4J for passkeys. Sessions in Redis. No JWT.

**Consequences.**
- Learning value: high — real understanding of auth internals.
- Time cost: ~2-3 weeks for V1 auth (Sprint 1).
- Security: we follow OWASP ASVS L2; relevant items are tracked in Sprint 1.
- Operational simplicity: no third-party API dependency, no monthly cost.
- We do this RIGHT or not at all — no half-baked auth in production.

---

## ADR-004 · Sessions in cookies + Redis, not JWT

**Status:** Accepted · 2026-05-03

**Context.** SPAs often default to JWT in localStorage or memory. JWT has known issues for browser auth: token revocation requires a deny list (defeating "stateless" pitch); XSS exposure if in localStorage; refresh-token dance is complex.

**Decision.** Server-side sessions, opaque IDs in `__Host-session` cookie (HttpOnly, Secure, SameSite=Lax), state in Redis. CSRF via double-submit cookie.

**Consequences.**
- Logout works instantly (delete from Redis).
- No token-handling code on the frontend.
- Cookies are scoped to our domain; can't be sent to third-party APIs (good).
- Need Redis available. We use it anyway for SSE pubsub and rate limiting.

---

## ADR-005 · jOOQ over JPA/Hibernate

**Status:** Accepted · 2026-05-03

**Context.** Scheduling involves overlap queries (`tstzrange &&`), aggregates, and PostgreSQL-specific features (range types, GIST exclusion constraints). Hibernate would force fighting it for these; jOOQ generates type-safe Kotlin code from the schema.

**Decision.** jOOQ 3.20+ with code generation triggered by Gradle task post-Flyway-migration.

**Consequences.**
- Schema-first development: write Flyway migration, generate jOOQ code, write repository.
- More upfront SQL knowledge required (which we want anyway).
- No accidental N+1 queries.
- Better fit for Kotlin (jOOQ has Kotlin-DSL extensions).

---

## ADR-006 · Single SvelteKit app with route groups (not two separate apps)

**Status:** Accepted · 2026-05-03

**Context.** Splitting the internal and customer UIs into two separate frontend apps doubles the maintenance burden. SvelteKit's route groups (`(internal)`, `(portal)`) let us split layouts/auth without splitting deployments.

**Decision.** One `apps/web` SvelteKit app. V1 has only `(app)` and `(auth)` and `(errors)` route groups. V2 adds `(portal)` for external surgeons.

**Consequences.**
- Single bundle for V1 — simpler.
- V2 must guard `(portal)` routes from internal data via API auth (which we do anyway).
- If bundle size becomes an issue (it won't for V2), we can split.

---

## ADR-007 · Server-Sent Events over WebSockets for real-time

**Status:** Accepted · 2026-05-03

**Context.** Real-time use case: server pushes booking changes to connected staff browsers. We don't need bidirectional comms (no chat).

**Decision.** SSE via Spring's `SseEmitter`, fan-out via Redis pubsub for horizontal scale (V2+).

**Consequences.**
- Simpler than WebSockets: just an HTTP stream, works with cookie auth, reconnects automatically in browsers.
- One-direction only — fine for our use.
- If we later need bidirectional (e.g. collaborative editing), we'd revisit.

---

## ADR-008 · pnpm workspace monorepo

**Status:** Accepted · 2026-05-03

**Context.** Solo project. Monorepo with pnpm workspaces is lower-friction than separate repos for `web` and shared `api-client` package.

**Decision.** Root `pnpm-workspace.yaml` with `apps/*` and `packages/*`. Backend lives alongside but uses Gradle (not pnpm).

**Consequences.**
- One install command bootstraps frontend.
- `pnpm` (not `npm`/`yarn`) — content-addressed cache, faster, smaller `node_modules`.
- We do not use Turborepo / Nx for V1 — overhead not justified at this scale.

---

## ADR-009 · UUID v7 for primary keys

**Status:** Accepted · 2026-05-03

**Context.** Sequential PG `bigserial` is fastest but leaks count and is monotonic only per-shard. UUID v4 is random — bad for B-tree clustering. UUID v7 has time-ordered prefix.

**Decision.** UUID v7 everywhere. Generated client-side or server-side via `com.github.f4b6a3:uuid-creator` (Kotlin/Java) or `uuidv7` package (TS).

**Consequences.**
- Inserts cluster well (within a millisecond).
- IDs sortable by time without an extra `created_at` index for many queries.
- Slightly larger than bigint (16 bytes vs 8). Negligible.

---

## ADR-010 · Argon2id for password hashing

**Status:** Accepted · 2026-05-03

**Context.** OWASP-recommended. bcrypt is acceptable but ages; scrypt has fewer libraries. Argon2id is the 2015 PHC winner.

**Decision.** Argon2id with parameters: memory 64 MB, iterations 3, parallelism 4. Re-evaluate annually.

**Consequences.**
- Slow login (~100ms per check). Fine for human-rate auth.
- Library: `de.mkammerer:argon2-jvm` or Spring Security's built-in.
- Parameters tuned for our hardware budget; check on actual server before V1 ship.

---

## ADR-011 · OpenAPI as source of truth for frontend types

**Status:** Accepted · 2026-05-03

**Context.** Two options: hand-write TS types, or generate from backend.

**Decision.** Backend exposes `/v3/api-docs` (springdoc-openapi). Generate TS types via `openapi-typescript` into `packages/api-client/`. CI fails if generated file is out of sync with checked-in version.

**Consequences.**
- Single source of truth (the controllers).
- Forces backend to declare DTOs cleanly.
- One extra script (`scripts/codegen-openapi.sh`) and CI step.

---

## ADR-012 · Coolify for deployment (over raw Compose / k8s)

**Status:** Accepted · 2026-05-03 (deferred until V1 works locally)

**Context.** Hetzner CPX21 will host the prod stack. Options: raw `docker compose up` + GitHub Actions deploy, Coolify (self-hosted Heroku/Vercel), Dokploy, K3s+ArgoCD.

**Decision.** Coolify. Git push → auto-deploy. Built-in HTTPS via Caddy. Good UI for logs/metrics.

**Consequences.**
- New skill to learn (~few hours).
- Single VPS dependency; if Coolify breaks, we fall back to manual `docker compose`.
- Saves writing CI/CD manually.

---

## ADR-013 · Single-tenant for V1 (no multi-clinic)

**Status:** Accepted · 2026-05-03

**Context.** Could design with multi-tenant from day one (`tenant_id` column on every table) but pays no portfolio dividend and adds complexity.

**Decision.** Single tenant. `clinic_settings` is a singleton row. If we ever go multi-tenant, we add `tenant_id` then.

**Consequences.**
- Simpler queries, simpler auth.
- Schema migration if multi-tenant is ever needed (not free, but acceptable).

---

## How to add a new ADR

1. Pick next number (ADR-014, etc.).
2. Use the same format.
3. Update the README's tech-stack section if relevant.
4. Don't edit accepted ADRs. Supersede with a new one (`Status: Superseded by ADR-NNN`).
