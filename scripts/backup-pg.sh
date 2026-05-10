#!/usr/bin/env bash
# Snapshots the production Postgres into /opt/kliniq/backups/pg-<UTC>.sql.gz.
# Designed for the Hetzner box where the kliniq stack runs under Coolify; the
# Postgres container name carries a Coolify-generated UUID suffix that
# changes per deploy, so we grep it dynamically rather than hardcode.
#
# Companion to scripts/backup-restic.sh which pushes the local dumps offsite
# to Backblaze B2 — see docs/RUNBOOK.md for the full backup/restore story.
#
# Cron entry on the box:
#     0 3 * * * /opt/kliniq/backup-pg.sh >> /var/log/kliniq-backup.log 2>&1
#
# Retention: last 14 days kept locally; older dumps pruned on every run.

set -euo pipefail

BACKUP_DIR=/opt/kliniq/backups
RETENTION_DAYS=14
DB_USER=kliniq
DB_NAME=kliniq

# Coolify generates one postgres container per kliniq deploy, with a long
# UUID suffix. Filtering by the `postgres-` prefix matches our container
# while leaving Coolify's own `coolify-db` (its internal Postgres) alone.
PG_CONTAINER=$(docker ps --format '{{.Names}}' | grep -m1 '^postgres-' || true)
if [ -z "$PG_CONTAINER" ]; then
    echo "[$(date -u +%FT%TZ)] ERROR: no running container matches '^postgres-'" >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"

TIMESTAMP=$(date -u +%Y%m%d-%H%M%S)
OUTPUT="$BACKUP_DIR/pg-$TIMESTAMP.sql.gz"

# --clean --if-exists prepares the dump to drop existing objects before
# restoring; --no-owner --no-acl strips role-grant noise so the dump
# restores cleanly into any Postgres regardless of role layout.
docker exec "$PG_CONTAINER" pg_dump \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    --no-owner \
    --no-acl \
    --clean \
    --if-exists \
    | gzip > "$OUTPUT"

# A 0-byte file means the dump pipeline failed silently somewhere; fail
# loud so the cron log + Sentry (once Day 53 is done) catch it.
if [ ! -s "$OUTPUT" ]; then
    echo "[$(date -u +%FT%TZ)] ERROR: dump is empty at $OUTPUT" >&2
    rm -f "$OUTPUT"
    exit 1
fi

# Prune older dumps. -mtime +14 means "last modified more than 14 24-hour
# blocks ago" — comfortably covers the retention claim in SPRINT_5.md.
find "$BACKUP_DIR" -maxdepth 1 -name 'pg-*.sql.gz' -mtime "+$RETENTION_DAYS" -delete

SIZE=$(du -h "$OUTPUT" | cut -f1)
echo "[$(date -u +%FT%TZ)] OK: $OUTPUT ($SIZE)"
