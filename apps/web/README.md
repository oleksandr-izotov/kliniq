# `apps/web` — SvelteKit SPA

Svelte 5 (runes mode) + Tailwind v4 + bits-ui / shadcn-svelte. Talks to
the Spring API at `/api/v1/*` via a session cookie. Type-safe wire
shapes are codegen'd from the API's OpenAPI spec via
`pnpm gen:api` into `src/lib/api/generated.ts`.

## Local development

See the [repo root README](../../README.md) for one-command setup.
Quick reference:

```bash
# From the repo root, brings up dev infra + the JVM + this dev server
pnpm dev:all

# Just the SPA (assuming the API is on https://localhost:8443)
cd apps/web && pnpm dev
```

The dev server runs on `https://localhost:5173` with a mkcert cert,
and proxies `/api/*` + `/actuator/*` to the JVM on the same host.

## Production profile

The prod build uses `@sveltejs/adapter-node` — output is a standalone
Node app at `build/`, run with `node build`. Required env vars:

| Variable   | Required?                      | What it's for                                                                                      |
| ---------- | ------------------------------ | -------------------------------------------------------------------------------------------------- |
| `NODE_ENV` | required (set to `production`) | Default fast-path + log noise reduction                                                            |
| `PORT`     | optional (default `3000`)      | Port the SvelteKit Node server listens on                                                          |
| `HOST`     | optional (default `0.0.0.0`)   | Bind address                                                                                       |
| `ORIGIN`   | optional in V1                 | Set to the public origin (e.g. `https://kliniq.example.com`) when the SPA needs absolute redirects |

The SPA does NOT carry any backend secrets — those all live on the
API side. The browser hits the API at the same origin as the SPA
(routed by Caddy in prod), so no `PUBLIC_API_URL` env var is needed
for V1.

## Build for the Docker image

```bash
docker build -f apps/web/Dockerfile -t kliniq-web .
```

The Dockerfile multi-stage builds with pnpm + adapter-node and runs
on `node:22-alpine`. See `apps/web/Dockerfile` for the inline
commentary on why pnpm uses `--config.node-linker=hoisted`, why the
runtime stage installs prod deps separately, and why vitest config
moved to `vitest.config.ts`.

## Health endpoint

| Path       | Returns               | Use                          |
| ---------- | --------------------- | ---------------------------- |
| `/healthz` | `200 {"status":"ok"}` | Coolify / k8s liveness probe |

The API has its own `/actuator/health` (plus `/actuator/health/liveness`
and `/actuator/health/readiness` for split probes) for the backend's
view. Caddy in `compose.prod.yaml` (and Coolify in real prod) routes
`/actuator/*` to the API container; everything else lands on the SPA.

## Tests

```bash
pnpm check       # svelte-check (TypeScript + Svelte)
pnpm lint        # prettier + eslint
pnpm test:unit   # vitest
pnpm test:e2e    # Playwright
```
