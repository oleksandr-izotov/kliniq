# `apps/api` — Spring Boot REST API

Kotlin 2.1 + Spring Boot 3.5 (Java 21). Postgres + Redis + Spring
Security + jOOQ + Flyway. Endpoint surface lives under `/api/v1/*` and
is documented at `/v3/api-docs` (Swagger UI at `/swagger-ui.html` in
non-prod profiles).

## Local development

See the [repo root README](../../README.md) for the one-command
setup. Quick reference:

```bash
# From the repo root, brings up dev infra and starts the JVM
pnpm dev:all

# Just the API (assuming compose stack is up)
cd apps/api && ./gradlew bootRun
```

The dev profile (`local`) listens on `https://localhost:8443` with a
mkcert-issued self-signed cert.

## Production profile

The prod profile (`SPRING_PROFILES_ACTIVE=prod`) listens on plain
HTTP on port 8080 — TLS terminates at Coolify's Caddy proxy. JSON
logging on stdout via `logback-spring.xml`. Required env vars:

| Variable | Required? | What it's for |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | required (set to `prod`) | Selects the prod application config + JSON logger |
| `SPRING_DATASOURCE_URL` | required | JDBC URL for Postgres, e.g. `jdbc:postgresql://postgres:5432/kliniq` |
| `SPRING_DATASOURCE_USERNAME` | required | Postgres user |
| `SPRING_DATASOURCE_PASSWORD` | required | Postgres password — keep in Coolify env-var UI, never in repo |
| `SPRING_DATA_REDIS_HOST` | required | Redis hostname inside the docker network |
| `SPRING_DATA_REDIS_PORT` | optional (default `6379`) | Redis port |
| `SPRING_DATA_REDIS_PASSWORD` | conditional | Set when Redis has auth on; managed Redis services usually require it |
| `SPRING_MAIL_HOST` | required | SMTP relay (e.g. `smtp.resend.com`) |
| `SPRING_MAIL_PORT` | required | Typically `587` for STARTTLS |
| `SPRING_MAIL_USERNAME` | required | Resend uses literal `resend` here |
| `SPRING_MAIL_PASSWORD` | required | Resend API key |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | required | `true` for Resend |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | required | `true` for Resend |
| `APP_WEB_BASE_URL` | required | The public URL of the SPA, used to build verify / reset / invitation links — e.g. `https://kliniq.example.com` |
| `APP_MAIL_FROM` | required | The `From:` address on outbound mail — e.g. `no-reply@kliniq.example.com`. Resend requires the domain to be verified there first. |
| `APP_SECURITY_BREACH_CHECK_ENABLED` | optional (default `true`) | Set to `false` only if HIBP's k-anonymity API is reachability-broken; keeps password breach checks running. |

The matching `.env.example` lives at the repo root; copy it to
`.env.local` (which `.gitignore`'s) for local prod-image testing
through `compose.prod.yaml`.

## Build for the Docker image

The runtime image (`apps/api/Dockerfile`) is single-stage and expects
the JAR to be built before `docker build` runs:

```bash
./gradlew :bootJar       # produces build/libs/kliniq-api-0.0.1-SNAPSHOT.jar
docker build -f apps/api/Dockerfile -t kliniq-api .
```

This split exists because Gradle's jOOQ codegen needs a real Postgres
at compile time — easier to run that outside Docker than orchestrate
a sidecar inside the build layer. CI / Coolify hosts a Postgres
service container during the gradle step.

## Health endpoints

| Path | Returns | Use |
|---|---|---|
| `/actuator/health` | aggregated status | overall readiness |
| `/actuator/health/liveness` | liveness probe | "JVM alive" — restart if this 503s |
| `/actuator/health/readiness` | readiness probe | "ready to take traffic" — pull from LB if this 503s |
| `/actuator/info` | git SHA + version | release inspection |

`/v3/api-docs` and `/swagger-ui.html` stay open in prod for now —
the schema is already discoverable from the SPA bundle anyway, so
gating it doesn't buy meaningful security. Lock down later if the
deploy story changes.

## Tests

```bash
./gradlew check     # full build + tests + ktlint + detekt + JaCoCo
./gradlew test      # tests only (Testcontainers spins up PG + Redis)
```
