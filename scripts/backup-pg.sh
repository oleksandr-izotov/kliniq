#!/usr/bin/env bash
# Snapshots the production Postgres into $BACKUP_DIR/pg-<UTC>.sql.gz.
#
# The container name is resolved through the compose project label rather than
# a name prefix: the previous deploy ran under Coolify, which appended a UUID
# per deploy, and the grep that solved that problem would silently match the
# wrong container anywhere else. A label lookup is exact.
#
# Cron entry on the host:
#     0 3 * * * /srv/kliniq/scripts/backup-pg.sh >> /var/log/kliniq-backup.log 2>&1
#
# Retention: last 14 days kept locally; older dumps pruned on every run.

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/srv/kliniq/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-kliniq}"
DB_USER=kliniq
DB_NAME=kliniq

container=$(docker ps \
  --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}" \
  --filter "label=com.docker.compose.service=postgres" \
  --format '{{.Names}}' | head -1)

if [ -z "$container" ]; then
  echo "$(date -u +%FT%TZ) FAIL: no running postgres container in compose project '${COMPOSE_PROJECT}'" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="${BACKUP_DIR}/pg-${stamp}.sql.gz"

# Dump to a temporary name first: a crash mid-write would otherwise leave a
# truncated file that looks like a valid backup until the day you need it.
tmp="${target}.partial"
if docker exec "$container" pg_dump -U "$DB_USER" -d "$DB_NAME" --clean --if-exists | gzip -9 > "$tmp"; then
  mv "$tmp" "$target"
  size=$(du -h "$target" | cut -f1)
  echo "$(date -u +%FT%TZ) OK: ${target} (${size})"
else
  rm -f "$tmp"
  echo "$(date -u +%FT%TZ) FAIL: pg_dump returned non-zero" >&2
  exit 1
fi

# A dump that cannot be read back is not a backup. Verifying the gzip stream is
# cheap and catches truncation and disk-full corruption.
if ! gzip -t "$target"; then
  echo "$(date -u +%FT%TZ) FAIL: ${target} is not a readable gzip stream" >&2
  exit 1
fi

deleted=$(find "$BACKUP_DIR" -name 'pg-*.sql.gz' -mtime "+${RETENTION_DAYS}" -print -delete | wc -l)
[ "$deleted" -gt 0 ] && echo "$(date -u +%FT%TZ) pruned ${deleted} dump(s) older than ${RETENTION_DAYS} days"

exit 0
