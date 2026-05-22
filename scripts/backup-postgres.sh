#!/usr/bin/env bash
# Daily Postgres backup for the ticketing stack. Dumps every database
# in the innbucks-postgres container to a single gzipped SQL file,
# rotates anything older than the retention window.
#
# Cron setup (one time, on the EC2):
#   chmod +x ~/superApp/scripts/backup-postgres.sh
#   crontab -e
#   # add:  0 2 * * *  /home/<EC2_USER>/superApp/scripts/backup-postgres.sh >> /var/log/innbucks-backup.log 2>&1
#
# Restore (when you need it):
#   gunzip -c <backup>.sql.gz | docker exec -i innbucks-postgres psql -U postgres
#
# Trade-offs worth knowing:
# - Backups live on the same EC2 disk as the source data. If the EC2
#   itself is lost, the backups are too. Adequate for STAGING; before
#   prod, swap this for an S3 upload (or AWS RDS with managed
#   snapshots).
# - pg_dumpall holds an ACCESS SHARE lock during the dump. Concurrent
#   writes are fine; DDL (schema changes from Flyway) will wait. At
#   02:00 UTC the stack is idle so this is invisible.

set -euo pipefail

CONTAINER="${PG_CONTAINER:-innbucks-postgres}"
PG_USER="${PG_USER:-postgres}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/ticketing}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"

mkdir -p "$BACKUP_DIR"

TIMESTAMP=$(date -u +%Y%m%d-%H%M%S)
# Atomic-write: dump to a .partial name, mv into place once the gzip
# closes cleanly. A failure mid-dump never leaves a half-written file
# under the canonical name (which restore tooling might then pick up).
TMPFILE="$BACKUP_DIR/.partial-$TIMESTAMP.sql.gz"
FINALFILE="$BACKUP_DIR/postgres-$TIMESTAMP.sql.gz"

if ! docker exec "$CONTAINER" pg_dumpall -U "$PG_USER" --clean --if-exists \
        | gzip > "$TMPFILE"; then
    rm -f "$TMPFILE"
    echo "[$(date -u +%FT%TZ)] backup FAILED container=$CONTAINER" >&2
    exit 1
fi

mv "$TMPFILE" "$FINALFILE"
SIZE=$(du -h "$FINALFILE" | cut -f1)
echo "[$(date -u +%FT%TZ)] backup ok size=$SIZE file=$FINALFILE"

# Rotate: delete plain backups older than the retention window. The
# .partial- prefix is excluded so a backup running concurrently with
# rotation doesn't lose its in-flight file.
find "$BACKUP_DIR" -maxdepth 1 -name 'postgres-*.sql.gz' -mtime "+$RETENTION_DAYS" -print -delete
