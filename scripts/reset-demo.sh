#!/usr/bin/env bash
# Puts the public demo back to its seeded state.
#
# kliniq.izotov.dev hands out its login credentials in the README, so anyone
# can sign in as a manager and cancel every booking on the board. The seeder
# is idempotent by design -- it does nothing when rooms already exist -- so it
# will never repair a schedule someone has emptied. This drops the schema
# instead and lets the seeder run against an empty database.
#
# Cron entry on the host (an hour after the nightly backup, so a bad day is
# still recoverable from the dump taken at 03:00):
#     0 4 * * * /srv/kliniq/scripts/reset-demo.sh >> /var/log/kliniq-reset.log 2>&1
#
# Roughly a minute of API downtime while Flyway migrates and the seeder runs.
# The web container is left up: it serves errors for that minute rather than
# nothing at all, and recovers on its own.

set -euo pipefail

COMPOSE_PROJECT="${COMPOSE_PROJECT:-kliniq}"
COMPOSE_FILE="${COMPOSE_FILE:-/srv/kliniq/compose.izotov.yaml}"
SQL_DIR="${SQL_DIR:-/srv/kliniq/scripts}"
SAFETY_DIR="${SAFETY_DIR:-/srv/kliniq/backups}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:3200/actuator/health/readiness}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"
DB_USER=kliniq
DB_NAME=kliniq

log() { echo "$(date -u +%FT%TZ) $*"; }
fail() { log "FAIL: $*" >&2; exit 1; }

container_for() {
  docker ps --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}" \
            --filter "label=com.docker.compose.service=$1" \
            --format '{{.Names}}' | head -1
}

pg=$(container_for postgres)
api=$(container_for api)
redis=$(container_for redis)
[ -n "$pg" ]  || fail "no running postgres container in compose project '${COMPOSE_PROJECT}'"
[ -n "$api" ] || fail "no running api container in compose project '${COMPOSE_PROJECT}'"

# Checked here, before anything is destroyed, rather than where it is used:
# this file is applied after the wipe, so a missing one there aborts the run
# with the database already empty and today's board blank -- which is the
# exact state the reset exists to prevent.
[ -f "${SQL_DIR}/shift-demo-day.sql" ] || fail "missing ${SQL_DIR}/shift-demo-day.sql -- refusing to start a reset it cannot finish"

# The guard that makes this safe to keep in cron. This script destroys every
# row in the database, so it must never run against an instance holding real
# data. APP_DEMO_SEED=true is exactly the marker of a demo instance -- a real
# clinical deploy does not set it (that is the whole reason the seed is
# opt-in), so an operator who copies this stack for real use gets a refusal
# here rather than an empty database one morning.
if ! docker inspect "$api" --format '{{range .Config.Env}}{{println .}}{{end}}' \
     | grep -qx 'APP_DEMO_SEED=true'; then
  fail "api container ${api} does not have APP_DEMO_SEED=true -- refusing to wipe a non-demo instance"
fi

# A dump before the wipe, kept briefly. The nightly backup already covers the
# normal case; this is for the morning someone asks what the demo looked like
# before it was reset.
mkdir -p "$SAFETY_DIR"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
safety="${SAFETY_DIR}/pre-reset-${stamp}.sql.gz"
if docker exec "$pg" pg_dump -U "$DB_USER" -d "$DB_NAME" --clean --if-exists \
   | gzip -9 > "${safety}.partial"; then
  mv "${safety}.partial" "$safety"
  log "safety dump: ${safety} ($(du -h "$safety" | cut -f1))"
else
  rm -f "${safety}.partial"
  fail "pre-reset dump failed -- not wiping anything"
fi
find "$SAFETY_DIR" -name 'pre-reset-*.sql.gz' -mtime +3 -delete

# Stop the api before touching the schema: Flyway's history table and the
# app's open connections both live in `public`, and DROP SCHEMA blocks on
# them. Stopping is also what makes the restart below re-run the seeder.
log "stopping api"
docker compose -f "$COMPOSE_FILE" stop api >/dev/null

log "dropping and recreating the schema"
docker exec -i "$pg" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
DROP SCHEMA public CASCADE;
CREATE SCHEMA public AUTHORIZATION kliniq;
GRANT ALL ON SCHEMA public TO public;
SQL

# Sessions live in Redis and key on user ids the seeder is about to reissue.
# Left alone, a visitor who was signed in before the reset keeps a cookie
# pointing at a user that no longer exists.
if [ -n "$redis" ]; then
  docker exec "$redis" redis-cli FLUSHALL >/dev/null
  log "redis flushed (sessions dropped)"
fi

log "starting api -- flyway migrates, then the seeder runs"
docker compose -f "$COMPOSE_FILE" start api >/dev/null

deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
until curl -sf "$HEALTH_URL" | grep -q '"status":"UP"'; do
  [ "$(date +%s)" -lt "$deadline" ] || fail "api did not become ready within ${HEALTH_TIMEOUT}s -- see: docker compose -f ${COMPOSE_FILE} logs api"
  sleep 3
done
log "api ready"

# The seeder lays bookings out from tomorrow, which leaves today's board empty
# -- the first thing a visitor sees. Pull the first seeded day onto today.
docker exec -i "$pg" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
  < "${SQL_DIR}/shift-demo-day.sql" >/dev/null

rooms=$(docker exec "$pg" psql -U "$DB_USER" -d "$DB_NAME" -tAc 'SELECT count(*) FROM operating_rooms;')
users=$(docker exec "$pg" psql -U "$DB_USER" -d "$DB_NAME" -tAc 'SELECT count(*) FROM users;')
today=$(docker exec "$pg" psql -U "$DB_USER" -d "$DB_NAME" -tAc \
  "SELECT count(*) FROM bookings WHERE starts_at::date = CURRENT_DATE;")
total=$(docker exec "$pg" psql -U "$DB_USER" -d "$DB_NAME" -tAc 'SELECT count(*) FROM bookings;')

# A reset that leaves an empty board is worse than no reset: the demo looks
# broken and nothing says so. Check the seed actually landed.
[ "$rooms" -gt 0 ] || fail "reset finished but there are no operating rooms -- the seeder did not run"
[ "$total" -gt 0 ] || fail "reset finished but there are no bookings -- the seeder did not run"

log "OK: ${rooms} rooms, ${users} users, ${total} bookings (${today} today)"
