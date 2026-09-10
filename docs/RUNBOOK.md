# Kliniq prod runbook

Operational reference for the live deploy at `https://kliniq.izotov.dev`.
Stable enough to be useful when something breaks at 2 AM; living document
otherwise.

> **Where it runs (since 2026-09-09).** A self-managed VPS in Reykjavík, shared
> with the owner's personal site and a WireGuard hub. Docker Compose brings up
> postgres, redis, mailpit, api and web; the host's nginx is the ingress and
> terminates TLS with a Cloudflare Origin certificate for `*.izotov.dev`.
> There is no Coolify and no Caddy on this host — nginx already owns 80/443.
>
> Sections below that mention Coolify, Caddy or the Hetzner box describe the
> **previous** deployment (V1.0, May–September 2026). They are kept because the
> procedures — backups, incident triage, promoting a user to admin — carry over
> unchanged; only the ingress and orchestration differ.

## Current deployment

**Host:** `$KLINIQ_HOST` (Reykjavík) · 2 vCPU · 3.8 GB RAM — the address is
deliberately not in this repository. The site sits behind Cloudflare, and
publishing the origin would let anyone reach it directly, past the edge. Export
it in your shell before running the commands below:

```bash
export KLINIQ_HOST=<origin address>
```

**Project directory:** `/srv/kliniq` — sources, `compose.izotov.yaml`, `.env`
**Compose project:** `kliniq` (containers `kliniq-api-1`, `kliniq-web-1`, …)

```bash
ssh root@$KLINIQ_HOST

cd /srv/kliniq
docker compose -f compose.izotov.yaml ps         # state
docker compose -f compose.izotov.yaml logs -f api
docker compose -f compose.izotov.yaml restart api
```

**Ports.** Nothing from this stack is exposed to the internet directly. The web
container publishes on `127.0.0.1:3100`, the api on `127.0.0.1:3200`, and nginx
proxies to both. The firewall opens 80/443 only to Cloudflare's ranges.

**Rebuilding the api image.** jOOQ generates code from a live database at compile
time, and this host has no JDK. Build on a machine that has one, then load:

```bash
# on the build machine, in the repo root
docker compose -f compose.yaml up -d postgres          # codegen database on :55432
(cd apps/api && ./gradlew flywayMigrate generateJooq bootJar)
docker build -t kliniq-api:latest -f apps/api/Dockerfile apps/api/
docker save kliniq-api:latest | gzip -1 | ssh root@$KLINIQ_HOST 'gunzip | docker load'

# on the host
cd /srv/kliniq && docker compose -f compose.izotov.yaml up -d api
```

**Rebuilding the web image** happens on the host — it needs no JDK:

```bash
cd /srv/kliniq && docker compose -f compose.izotov.yaml up -d --build web
```

**Passkeys are origin-bound.** `APP_WEBAUTHN_RP_ID` and `APP_WEBAUTHN_ORIGINS_0`
in `compose.izotov.yaml` must match the address bar exactly. Change the domain
and every passkey ceremony fails with an opaque browser error, while password
login keeps working — which makes it an easy fault to misdiagnose.

**Demo data.** `APP_DEMO_SEED=true` seeds 3 operating rooms, 4 surgeons and ~10
bookings on first start, then does nothing on later starts. The seeder lays
bookings out from the day after seeding, so a freshly seeded demo shows an empty
board on day one; `scripts/shift-demo-day.sql` moves the first seeded day onto
today.

## Previous deployment (Hetzner + Coolify)

## Common access

**SSH to the host:**

```powershell
ssh root@$KLINIQ_OLD_HOST
```

Keys only — password auth is disabled (Sprint 5, Day 55). SSH key is the ed25519 at `~/.ssh/id_ed25519` on the maintainer's laptop.

**Coolify UI (via SSH tunnel):**

```powershell
ssh -L 8000:localhost:8000 root@$KLINIQ_OLD_HOST
```

Then open `http://localhost:8000` in the browser. The 8000 port is firewalled off externally on purpose — only reachable through the tunnel.

**Postgres `psql` shell:**

```bash
docker exec -it $(docker ps --format '{{.Names}}' | grep -m1 '^postgres-') psql -U kliniq -d kliniq
```

The container name has a Coolify UUID suffix that changes per deploy; the grep finds it dynamically.

## Backups

### Daily local pg_dump

Script: `/opt/kliniq/backup-pg.sh` (source in repo at `scripts/backup-pg.sh`).

Cron entry on the box (`crontab -e` as root):

```cron
0 3 * * * /opt/kliniq/backup-pg.sh >> /var/log/kliniq-backup.log 2>&1
```

Runs daily at 03:00 UTC. Writes `pg-YYYYMMDD-HHMMSS.sql.gz` into `/opt/kliniq/backups/`. Retention is 14 days — older snapshots are deleted on every run.

**Manual backup:**

```bash
/opt/kliniq/backup-pg.sh
```

The script logs OK/ERROR with a timestamp to stdout; cron captures both stdout and stderr into `/var/log/kliniq-backup.log`.

**Inspect what's there:**

```bash
ls -lah /opt/kliniq/backups/
```

### Restoring a dump

Restore is an out-of-band operation — never run against the live Postgres without a downtime window, since `--clean` drops every table before re-inserting.

**Smoke-restore into a sidecar Postgres** (safe, doesn't touch prod):

```bash
# Start a one-off Postgres 16
docker run -d --name pg-restore-smoke -e POSTGRES_PASSWORD=test postgres:16-alpine

# Wait until it's ready
until docker exec pg-restore-smoke pg_isready -U postgres; do sleep 1; done

# Create an empty kliniq DB to restore into
docker exec pg-restore-smoke psql -U postgres -c "CREATE DATABASE kliniq;"

# Pick a dump (most recent shown):
LATEST=$(ls -t /opt/kliniq/backups/pg-*.sql.gz | head -1)
echo "Restoring from $LATEST"

# Pipe the dump into psql inside the sidecar
zcat "$LATEST" | docker exec -i pg-restore-smoke psql -U postgres -d kliniq

# Verify counts
docker exec pg-restore-smoke psql -U postgres -d kliniq -c "
    SELECT 'users' AS tbl, count(*) FROM users
    UNION ALL SELECT 'bookings', count(*) FROM bookings
    UNION ALL SELECT 'operating_rooms', count(*) FROM operating_rooms;
"

# Tear down
docker rm -f pg-restore-smoke
```

**Real-restore into prod Postgres** (downtime; only when actually recovering):

```bash
PG_CONTAINER=$(docker ps --format '{{.Names}}' | grep -m1 '^postgres-')
LATEST=$(ls -t /opt/kliniq/backups/pg-*.sql.gz | head -1)
zcat "$LATEST" | docker exec -i "$PG_CONTAINER" psql -U kliniq -d kliniq
```

The dump's `--clean --if-exists` flags handle existing tables — they're dropped and recreated as part of the load. Make sure the api container is stopped first (`docker stop api-…`) so Flyway doesn't race against the restore.

### Offsite backups

_Deferred (2026-05-09) — picks up the day a paying tenant is onboarded. The plan when we revisit: Restic snapshots of `/opt/kliniq/backups/` pushed to Backblaze B2 at 04:00 UTC, retention `--keep-daily 7 --keep-weekly 4 --keep-monthly 12`, passphrase in the user's password manager. Until then the only data on the box is demo-deploy noise; the local 14-day rotation above is enough._

## Container operations

**See what's running:**

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

**Tail logs from a service:**

```bash
docker logs --tail 200 -f $(docker ps --format '{{.Names}}' | grep -m1 '^api-')
docker logs --tail 200 -f $(docker ps --format '{{.Names}}' | grep -m1 '^web-')
docker logs --tail 200 -f $(docker ps --format '{{.Names}}' | grep -m1 '^caddy-')
```

**Restart one service without dropping the rest** (Coolify will lose track of which version is running; only use during incident response):

```bash
docker restart $(docker ps --format '{{.Names}}' | grep -m1 '^api-')
```

For routine redeploys, use the Coolify UI's "Redeploy" button — it pulls the latest image and rolls the service cleanly.

## Common incidents

### "Site is down"

1. From your laptop: `curl -I https://kliniq.izotov.dev` — does it respond at all?
2. SSH in. `docker ps` — all six service containers Up?
   - If anything is "Restarting" or "Exited": `docker logs <container> --tail 100`
3. If everything is Up but the site is down: `docker logs caddy-…` — TLS-related issues (Let's Encrypt rate limit, ACME challenge fail) show here.

### "Login doesn't work — `Welcome back` toast fires but I'm stuck on /login"

Almost always means SvelteKit's SSR can't reach the api over the private compose network. Check `BACKEND_URL` env var on the web container:

```bash
docker exec $(docker ps --format '{{.Names}}' | grep -m1 '^web-') env | grep BACKEND_URL
```

Should be `BACKEND_URL=http://api:8080`. If it isn't, set it in Coolify and redeploy.

### "Mail isn't being sent"

Run path is the Resend HTTPS API, not SMTP. Hetzner blocks outbound SMTP so don't try to debug via `telnet smtp.resend.com 465`.

1. Check api logs for `MailDeliveryException` or 4xx from `api.resend.com`.
2. Verify `APP_MAIL_PROVIDER=resend` is set in Coolify env vars.
3. Verify `APP_MAIL_RESEND_API_KEY` is set and not expired (Resend dashboard → API Keys).
4. Resend dashboard → Logs has end-to-end delivery state including bounces.

### "GHA built but the new image isn't running"

Coolify caches `:latest` by tag and doesn't always pull on every deploy. Manual force-pull then redeploy:

```bash
docker pull ghcr.io/oleksandr-izotov/kliniq/api:latest
```

Then click Deploy in Coolify.

## SSH hardening

Key-only access; password auth disabled and root login restricted to key login.

**Current `/etc/ssh/sshd_config` overrides:**

```
PasswordAuthentication no
PermitRootLogin prohibit-password
```

**To verify state** (from the host):

```bash
grep -E '^(PasswordAuthentication|PermitRootLogin)' /etc/ssh/sshd_config
```

Should print exactly those two lines. Anything else (commented out, set to `yes`) means the box is back in a less-locked state.

**To re-apply if it drifts** (e.g. after a Ubuntu kernel upgrade rewrote sshd_config):

```bash
# DANGER: edit + reload only — keep the current session open through the
# whole procedure, open a second terminal to verify the change, only close
# the first session once the second one works.
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
sshd -t            # validate config syntax before applying
systemctl reload ssh   # graceful — existing sessions keep working
```

**fail2ban** monitors SSH auth failures and bans source IPs that try to brute-force:

```bash
fail2ban-client status sshd
```

Should show `Currently banned`, `Total banned`, etc. If fail2ban isn't installed:

```bash
apt update && apt install -y fail2ban
systemctl enable --now fail2ban
```

The shipped Debian/Ubuntu defaults already include an `sshd` jail with sane thresholds (5 failures → 10-minute ban), so no jail.local editing needed for V1.1.

## Coolify proxy lockdown

Coolify ships with its own `coolify-proxy` (Traefik) container that tries to own ports 80/443. We use our own Caddy in `compose.prod.yaml` as the sole ingress, so the Coolify-proxy must stay dead. It was deleted manually back in Sprint 4 Day 49; the steps below verify it doesn't resurrect.

**Check current state:**

```bash
docker ps -a --filter 'name=coolify-proxy' --format 'table {{.Names}}\t{{.Status}}'
```

Empty output ⇒ no container exists. Good.

If a `coolify-proxy` row exists (any status — Up, Exited, Created):

```bash
docker stop coolify-proxy
docker rm coolify-proxy
```

**Persistent disable** so Coolify doesn't recreate it on its own restart:

In the Coolify UI → top-right menu → Servers → localhost → toggle off "Coolify Proxy" if it's still on. Coolify will stop trying to spawn it.

**Reboot test** (only do this during a planned downtime window):

```bash
reboot
# wait ~30 s, then SSH back in
docker ps --filter 'name=coolify-proxy'   # should still be empty
docker ps --format 'table {{.Names}}\t{{.Status}}'   # caddy-… should be Up
```

If `coolify-proxy` re-appeared, the Coolify UI toggle didn't stick — escalate via Coolify GitHub issues or pin `coolify-proxy`'s restart policy manually: `docker update --restart=no coolify-proxy && docker stop coolify-proxy`.

## Security headers

CSP is configured in `apps/web/svelte.config.js` (`kit.csp` block). SvelteKit's adapter-node SSRs everything, and it injects inline scripts for hydration and theme-detection that need to be hash- or nonce-signed — that allowlisting only the framework can compute, so CSP must live at the framework layer. SvelteKit emits a `<meta http-equiv="content-security-policy">` tag per rendered page. **Do not also set `Content-Security-Policy` in Caddy** — the browser intersects header CSP with meta CSP using most-restrictive wins, which would invalidate the SvelteKit-computed hashes and break hydration.

The remaining four security headers live in `compose.prod.yaml`'s `configs.caddyfile.content` block under `header { ... }`: `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `Strict-Transport-Security`. Plus `-Server` to strip Caddy's default `Server: Caddy` identifier.

**To verify in production:**

```bash
# The four Caddy-level headers come back on every response:
curl -sI https://kliniq.izotov.dev | grep -iE '(x-content|referrer|permissions|strict-transport)'

# CSP is on the rendered HTML, not the headers — look inside the page:
curl -s https://kliniq.izotov.dev/login | grep -o 'content-security-policy[^>]*'
```

The CSP `<meta>` line should contain `default-src 'self'`, `script-src 'self' 'sha256-…'` (one or more hashes), `style-src 'self' 'unsafe-inline'`, `connect-src 'self' https://*.sentry.io https://*.ingest.sentry.io https://*.ingest.de.sentry.io`, `frame-ancestors 'none'`, etc.

**If a SPA route breaks with CSP errors** (DevTools → Console → `Refused to load ... because it violates the following Content Security Policy directive`):

1. Identify which directive blocked it (the error names the specific directive).
2. Either fix the code to stop loading the offending resource, OR — if the resource is load-bearing third-party — add the origin to the matching directive's list in `apps/web/svelte.config.js`. Connect-src already widens for Sentry's ingest endpoints; add the same way for any new third party.
3. Local build + redeploy via Coolify. Don't loosen to `'unsafe-inline'` / `'unsafe-eval'` unless absolutely necessary; those defeat the point of CSP. SvelteKit's hash/nonce mechanism handles its own inline scripts automatically — don't allowlist `unsafe-inline` for script-src to "fix" the framework's own inline scripts.

## Sentry

Errors land at `https://oleksandrs-firma.sentry.io` (free hosted tier, 5k events / 7-day retention) split across two projects:

- **`kliniq-api`** — Spring Boot api errors. DSN in Coolify env var `SENTRY_DSN`. Wired via `sentry-spring-boot-starter-jakarta` + logback appender (see Day 53 commit).
- **`kliniq-web`** — SvelteKit web errors (both client- and server-side). DSN in Coolify env var `PUBLIC_SENTRY_DSN` (the `PUBLIC_` prefix is mandatory for SvelteKit to expose the var to the client bundle via `$env/dynamic/public`). Wired via `@sentry/sveltekit` SDK in `hooks.client.ts` + `hooks.server.ts`.

Source-map upload for `kliniq-web` runs at Docker build time inside Coolify's deploy host. The `SENTRY_AUTH_TOKEN` env var (org-scoped Sentry auth token, `project:releases` scope only) is passed as a Docker build arg via `compose.prod.yaml` and reaches the Sentry vite plugin in `apps/web/vite.config.ts`. The runtime image does NOT carry the token — it's scoped to the build stage only. Without the token (e.g. running `docker compose -f compose.prod.yaml build` locally without the env var), the plugin skips upload and emits a warning; the build itself still succeeds.

**DSN formats:**
- `kliniq-api`: `https://<public>@oXXXX.ingest.sentry.io/<project>`
- `kliniq-web`: same format

**Release tag** (optional): set `SENTRY_RELEASE` to the deployed Git SHA so the dashboard groups errors by build. Coolify doesn't pass the SHA automatically; leave unset if you don't care.

**What's captured automatically:**

- Anything throwing past `GlobalExceptionHandler.onUnexpected` → `log.error("Unhandled exception in controller", e)` → Sentry's logback appender forwards it.
- Any `logger.error(…)` site anywhere in the codebase (minimum-event-level = error in `application-prod.yml`).
- INFO+ log statements ride along as breadcrumbs on each event.

**What's NOT captured:**

- 4xx responses produced by `GlobalExceptionHandler.onValidation` / `onMalformedBody` — those are client mistakes, not server errors.
- Anything logged at WARN or below.
- PII: `send-default-pii=false` keeps user emails / IPs off events. Flip only if you actually need user-scoped grouping.

**Verifying the pipeline (one-time after first deploy):**

1. In Coolify env vars: set `APP_SENTRY_SMOKE_ENABLED=true` alongside `SENTRY_DSN`. Redeploy.
2. From a logged-in admin browser session: `GET https://kliniq.izotov.dev/api/v1/dev/sentry-smoke`.
3. Server returns 500 with the standard `INTERNAL_ERROR` envelope.
4. In Sentry dashboard: event appears within ~60 s, tagged `environment=prod`, with full stack trace.
5. Remove `APP_SENTRY_SMOKE_ENABLED` from Coolify env (or delete `SentrySmokeController.kt`) and redeploy. The route 404s when the flag is off.

**Day-to-day:** Sentry dashboard sends an email on the first occurrence of a new error fingerprint. Reply to that thread when you've fixed it — Sentry keeps the resolved state and reopens the issue if the same fingerprint fires post-fix.

## UptimeRobot

External liveness monitor: `https://uptimerobot.com/dashboard` → monitor `kliniq-api liveness` pings `https://kliniq.izotov.dev/actuator/health/liveness` every 5 minutes. Alert fires after 2 consecutive failures (~10 min real downtime) to the maintainer's email.

The endpoint is Spring Boot's `LivenessStateHealthIndicator` and returns `{"status":"UP"}` once the api JVM has finished starting; it does NOT check Postgres / Redis reachability (that's `/actuator/health/readiness`, which we deliberately don't expose externally — Caddy already gates traffic to `/actuator/health/*` paths and readiness would flap during deploys).

**If you get a DOWN alert:**

1. `curl -I https://kliniq.izotov.dev/actuator/health/liveness` from your laptop — does it 200?
2. If yes, false alarm (transient network blip from UptimeRobot's probe nodes); resolve in dashboard.
3. If no, SSH in and walk the "Site is down" runbook above.

**To pause the monitor during planned maintenance:** dashboard → monitor row → toggle off. Don't forget to flip it back on — UptimeRobot doesn't auto-resume.

## Promoting a user to ADMIN

After self-registration a user is `STAFF` by default. To promote to ADMIN:

```bash
docker exec -it $(docker ps --format '{{.Names}}' | grep -m1 '^postgres-') psql -U kliniq -d kliniq
```

```sql
UPDATE users SET role = 'ADMIN' WHERE email_normalized = '<lowercase-email>';
\q
```

Refresh the page; the home grid will now show every admin tile.

## Deleting an orphan user

If a test registration created a row with a typo'd email, delete it so the real email can register fresh:

```sql
DELETE FROM users WHERE email_normalized IN ('<typo-email-1>', '<typo-email-2>');
```

Email-verification + password-reset tokens cascade-delete via FK.
