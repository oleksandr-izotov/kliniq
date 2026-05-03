# Sprint 0 — Foundation

**Goal:** Empty repo → both apps boot, talk to PG/Redis, run over HTTPS locally, CI is green.

**Definition of done:**
- [ ] `pnpm install && docker compose up -d` from a fresh clone gets infra running
- [ ] `cd apps/api && ./gradlew bootRun` starts API on https://localhost:8443/health → `{"status":"UP"}`
- [ ] `cd apps/web && pnpm dev` starts SvelteKit on https://localhost:5173 → renders "Hello Kliniq"
- [ ] Frontend `/` calls API `/health` and shows the response
- [ ] Push to GitHub → CI runs lint + tests + typecheck on both apps and passes
- [ ] Pre-commit hook blocks committing a `.env`
- [ ] `docs/` is fully populated (already done — you're reading them)

**Estimated effort:** 3-5 days at chaotic pace.

**No code yet for: auth, business logic, real UI.** Only scaffolding.

---

## Day 1 — Repo bootstrap + git hygiene

### 1.1 Init repo

```bash
cd c:/Users/Admin/Desktop/newPraxisProject/Kliniq
git init -b main
gh repo create oleksandr-izotov/kliniq --private --source=. --remote=origin
```

### 1.2 Create `.gitignore` (root)

Use the template from `docs/REPO_STRUCTURE.md` § Files always in `.gitignore`.

### 1.3 Create `LICENSE`

MIT. Single file at root, `Copyright (c) 2026 Oleksandr Izotov`.

### 1.4 Create `.editorconfig`

```
root = true

[*]
end_of_line = lf
insert_final_newline = true
charset = utf-8
indent_style = space
indent_size = 2
trim_trailing_whitespace = true

[*.{kt,kts,java}]
indent_size = 4

[*.md]
trim_trailing_whitespace = false
```

### 1.5 Create `.env.example`

```
# Local dev — copy to .env and fill in
APP_BASE_URL=https://localhost:5173
API_BASE_URL=https://localhost:8443

# Postgres (matches compose.yaml)
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=kliniq
POSTGRES_USER=kliniq
POSTGRES_PASSWORD=kliniq_dev_only

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# Mailpit SMTP
SMTP_HOST=localhost
SMTP_PORT=1025
SMTP_FROM=no-reply@kliniq.local
```

### 1.6 Setup pre-commit hooks

Install [lefthook](https://github.com/evilmartians/lefthook) and [gitleaks](https://github.com/gitleaks/gitleaks):

```bash
# Windows: scoop install lefthook gitleaks
# OR via npm
npm i -g lefthook
# gitleaks: download binary from releases, add to PATH
```

Create `lefthook.yml`:

```yaml
pre-commit:
  parallel: true
  commands:
    gitleaks:
      run: gitleaks protect --staged --redact --no-banner
    eslint:
      glob: "apps/web/**/*.{ts,svelte,js}"
      run: cd apps/web && pnpm exec eslint {staged_files}
    ktlint:
      glob: "apps/api/**/*.{kt,kts}"
      run: cd apps/api && ./gradlew ktlintCheck

commit-msg:
  commands:
    commitlint:
      run: pnpm exec commitlint --edit {1}

pre-push:
  commands:
    typecheck-web:
      run: cd apps/web && pnpm exec svelte-check
    test-api:
      run: cd apps/api && ./gradlew test
```

Then `lefthook install`.

### 1.7 Conventional Commits

Root `package.json`:
```json
{
  "name": "kliniq-monorepo",
  "private": true,
  "devDependencies": {
    "@commitlint/cli": "^19.0.0",
    "@commitlint/config-conventional": "^19.0.0"
  }
}
```

`commitlint.config.js`:
```js
export default { extends: ['@commitlint/config-conventional'] };
```

### 1.8 First commit

```bash
git add .
git commit -m "chore: initial repo scaffolding and docs"
git push -u origin main
```

✅ End of Day 1: Repo on GitHub with docs and hygiene.

---

## Day 2 — Local infra + HTTPS

### 2.1 mkcert for local TLS

```bash
# Windows
scoop install mkcert
mkcert -install
mkcert localhost 127.0.0.1 ::1
# Outputs: localhost+2.pem, localhost+2-key.pem
```

Move them to `apps/web/certs/` and `apps/api/certs/`. Add `*.pem`, `*-key.pem` to `.gitignore` (already there).

### 2.2 `compose.yaml` (root)

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: kliniq-pg
    environment:
      POSTGRES_DB: kliniq
      POSTGRES_USER: kliniq
      POSTGRES_PASSWORD: kliniq_dev_only
    ports: ["5432:5432"]
    volumes: ["pg-data:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U kliniq -d kliniq"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7.4-alpine
    container_name: kliniq-redis
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

  mailpit:
    image: axllent/mailpit:latest
    container_name: kliniq-mailpit
    ports:
      - "1025:1025"   # SMTP
      - "8025:8025"   # Web UI
    environment:
      MP_MAX_MESSAGES: 5000
      MP_SMTP_AUTH_ACCEPT_ANY: 1
      MP_SMTP_AUTH_ALLOW_INSECURE: 1

volumes:
  pg-data:
```

Test:
```bash
docker compose up -d
docker compose ps
# all healthy
# Visit http://localhost:8025 — Mailpit UI loads
```

✅ End of Day 2: Infra running, certs ready.

---

## Day 3 — Backend bootstrap (Spring Boot + Kotlin)

### 3.1 Generate skeleton via [Spring Initializr](https://start.spring.io)

Settings:
- Project: Gradle - Kotlin
- Language: Kotlin
- Spring Boot: 3.5.x latest
- Group: `com.kliniq`, Artifact: `api`, Name: `api`
- Package: `com.kliniq`
- Java: 21
- Dependencies: Web, Validation, Actuator, PostgreSQL Driver, Flyway, Spring Data Redis, Spring Session Data Redis, Spring Security, Lombok ❌ (we don't use Lombok, Kotlin doesn't need it)

Download → unzip → move contents into `apps/api/`.

### 3.2 Add jOOQ + tooling to `apps/api/build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.jpa") version "2.1.0"
    id("org.springframework.boot") version "3.5.10"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.jooq.jooq-codegen-gradle") version "3.20.0"
    id("org.flywaydb.flyway") version "10.20.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.session:spring-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}
```

(Full config: see Spring Boot 3 + Kotlin samples; this is a sketch.)

### 3.3 First Flyway migration

`apps/api/src/main/resources/db/migration/V1__init.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Placeholder; real schema comes in Sprint 1+
CREATE TABLE schema_marker (
  id INT PRIMARY KEY DEFAULT 1,
  initialized_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO schema_marker (id) VALUES (1);
```

### 3.4 `application-local.yml`

```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: classpath:certs/localhost.p12
    key-store-password: changeit
    key-store-type: PKCS12

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/kliniq
    username: kliniq
    password: kliniq_dev_only
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: localhost
      port: 6379
  session:
    store-type: redis
    timeout: 30d

management:
  endpoints:
    web:
      exposure:
        include: health, info
```

(Convert mkcert PEMs to PKCS12 with: `openssl pkcs12 -export -in localhost+2.pem -inkey localhost+2-key.pem -out localhost.p12 -name localhost -password pass:changeit` — place at `src/main/resources/certs/localhost.p12`.)

### 3.5 Health check works

```bash
cd apps/api
./gradlew bootRun
# In another shell:
curl -k https://localhost:8443/actuator/health
# {"status":"UP"}
```

✅ End of Day 3: Backend boots, connects to PG/Redis, serves health over HTTPS.

---

## Day 4 — Frontend bootstrap (SvelteKit + Tailwind + shadcn-svelte)

### 4.1 Init SvelteKit

```bash
cd apps
pnpm create svelte@latest web
# Choose: Skeleton project, TypeScript syntax, ESLint, Prettier, Playwright, Vitest
cd web
pnpm install
```

### 4.2 Tailwind CSS

```bash
pnpm add -D tailwindcss@latest @tailwindcss/vite postcss autoprefixer
# Tailwind v4 setup — see https://tailwindcss.com/docs/installation
```

Add to `vite.config.ts`:
```ts
import tailwindcss from '@tailwindcss/vite';
export default {
  plugins: [tailwindcss(), sveltekit()],
};
```

`src/app.css`:
```css
@import 'tailwindcss';
/* ... custom CSS variables from STYLE.md */
```

### 4.3 shadcn-svelte

```bash
pnpm dlx shadcn-svelte@latest init
# Choose: TypeScript yes, base color "slate", CSS variables yes
pnpm dlx shadcn-svelte@latest add button card input
```

### 4.4 Tailwind config — apply Kliniq Emerald

Replace `tailwind.config.ts` with the skeleton from `docs/STYLE.md`. Add CSS variables for light/dark.

### 4.5 HTTPS dev server

`vite.config.ts`:
```ts
import { defineConfig } from 'vite';
import fs from 'fs';

export default defineConfig({
  server: {
    https: {
      cert: fs.readFileSync('certs/localhost+2.pem'),
      key: fs.readFileSync('certs/localhost+2-key.pem'),
    },
    port: 5173,
    proxy: {
      '/api': {
        target: 'https://localhost:8443',
        changeOrigin: true,
        secure: false,
      },
    },
  },
  plugins: [tailwindcss(), sveltekit()],
});
```

### 4.6 Hello world page

`src/routes/+page.svelte`:
```svelte
<script lang="ts">
  let healthData = $state<unknown>(null);
  let error = $state<string | null>(null);

  async function checkHealth() {
    try {
      const res = await fetch('/api/actuator/health');
      healthData = await res.json();
    } catch (e) {
      error = String(e);
    }
  }
  $effect(() => { checkHealth(); });
</script>

<main class="min-h-screen flex items-center justify-center bg-bg text-text">
  <div class="space-y-4 text-center">
    <h1 class="text-4xl font-bold">Hello Kliniq</h1>
    <p class="text-text-muted">Sprint 0 boot test.</p>
    <pre class="text-sm bg-surface p-4 rounded-md">{JSON.stringify(healthData ?? error, null, 2)}</pre>
  </div>
</main>
```

Test:
```bash
pnpm dev
# Visit https://localhost:5173 → "Hello Kliniq" + {"status":"UP"}
```

✅ End of Day 4: Frontend talks to backend over HTTPS.

---

## Day 5 — CI + polish

### 5.1 `.github/workflows/ci.yml`

```yaml
name: CI
on:
  push: { branches: [main] }
  pull_request:

jobs:
  api:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_DB: kliniq
          POSTGRES_USER: kliniq
          POSTGRES_PASSWORD: kliniq_dev_only
        ports: ['5432:5432']
        options: --health-cmd "pg_isready -U kliniq" --health-interval 5s --health-timeout 5s --health-retries 5
      redis:
        image: redis:7.4-alpine
        ports: ['6379:6379']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - uses: gradle/actions/setup-gradle@v4
      - run: cd apps/api && ./gradlew ktlintCheck detekt test

  web:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: pnpm/action-setup@v4
        with: { version: 9 }
      - uses: actions/setup-node@v4
        with: { node-version: 20, cache: pnpm }
      - run: pnpm install --frozen-lockfile
      - run: cd apps/web && pnpm exec eslint .
      - run: cd apps/web && pnpm exec svelte-check
      - run: cd apps/web && pnpm test:unit -- --run

  gitleaks:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: gitleaks/gitleaks-action@v2
```

### 5.2 GitHub repo settings

- Enable branch protection on `main`: require PR, require CI passing
- Enable Dependabot security + version updates (`.github/dependabot.yml` with `npm` + `gradle` ecosystems)
- Enable CodeQL (free for public repos; works for private under free tier limits)

### 5.3 README polish

Add to root `README.md`:
- Status badges (CI, license)
- Screenshot of "Hello Kliniq" page (proof it runs)
- Link to live demo (later, when deployed)

### 5.4 Commit and push everything

```bash
git add .
git commit -m "feat: bootstrap api + web with hello-world over https"
git push
# CI should be green ✅
```

✅ **Sprint 0 done.** Move on to [SPRINT_1.md](SPRINT_1.md) — Auth.

---

## Troubleshooting

| Issue | Fix |
|---|---|
| `mkcert: command not found` | Windows: `scoop install mkcert` or download binary; Mac: `brew install mkcert` |
| Browser warns about self-signed cert | Run `mkcert -install` once; restart browser |
| `gradle bootRun` fails on jOOQ codegen | Make sure Postgres is up: `docker compose ps` |
| `pnpm dev` shows CORS errors | Check `vite.config.ts` proxy for `/api` |
| GitHub CI fails on `gradle test` | Use Postgres service in workflow (see § 5.1) |

---

## Out of scope for Sprint 0 (don't get distracted)

- Real DB schema → Sprint 1
- Authentication → Sprint 1
- jOOQ codegen wired in → Sprint 1 (after we have a real schema)
- Logging config → Sprint 1
- Coolify, Hetzner, deployment → after V1

If you find yourself adding any of these, stop and put it in a TODO file. Sprint 0 is _only_ scaffolding.
