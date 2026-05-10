# Kliniq prod runbook

Operational reference for the Hetzner CPX22 box at `$KLINIQ_OLD_HOST` that hosts the live Kliniq deploy at `https://kliniq.izotov.dev`. Stable enough to be useful when something breaks at 2 AM; living document otherwise.

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

_Pending Sprint 5 Day 52 — Restic + Backblaze B2._

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
docker pull ghcr.io/oleksandr-izotov/kliniq-api:latest
```

Then click Deploy in Coolify.

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
