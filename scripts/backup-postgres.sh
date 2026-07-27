#!/usr/bin/env bash
# Daily Postgres backup for the ticketing k3s cell. Dumps every database in
# the `postgres` StatefulSet (namespace `ticketing`) to a gzipped SQL file,
# uploads it OFF-BOX to S3, then rotates local copies older than the
# retention window.
#
# k3s NOTE: the stack no longer runs under docker-compose on the box — the
# old `docker exec innbucks-postgres` form of this script silently stopped
# working at the k3s migration (the container doesn't exist under
# containerd). This version execs into the pod via kubectl instead.
#
# Cron setup (one time, on the EC2):
#   chmod +x ~/ticketing-system/scripts/backup-postgres.sh
#   crontab -e
#   # add:  0 2 * * *  BACKUP_S3_URI=s3://<bucket>/ticketing-pg /home/<EC2_USER>/ticketing-system/scripts/backup-postgres.sh >> /var/log/innbucks-backup.log 2>&1
#
# Restore (test this BEFORE you need it — an untested restore is not a backup):
#   # from local:  gunzip -c <backup>.sql.gz | kubectl -n ticketing exec -i postgres-0 -- psql -U postgres
#   # from S3:     aws s3 cp "$BACKUP_S3_URI/<backup>.sql.gz" - | gunzip -c | kubectl -n ticketing exec -i postgres-0 -- psql -U postgres
#   # then restart the app fleet so connection pools re-establish cleanly:
#   #   kubectl -n ticketing get deploy -o name | xargs kubectl -n ticketing rollout restart
#
# Trade-offs worth knowing:
# - The OFF-BOX copy is the actual backup. Local dumps on the same EC2 disk
#   as the data protect against a bad migration or fat-fingered delete, but
#   not against instance/volume loss — that's what BACKUP_S3_URI is for, and
#   the script FAILS (exit 1, cron mails/logs it) when the upload can't
#   happen, so a misconfigured bucket can't silently degrade to same-disk-only.
#   Set ALLOW_LOCAL_ONLY=1 to opt out explicitly (dev boxes).
# - pg_dumpall holds an ACCESS SHARE lock during the dump. Concurrent writes
#   are fine; DDL (schema changes from Flyway) will wait. At 02:00 UTC the
#   stack is idle so this is invisible.
# - Scope is Postgres only, and that is deliberate: Redis holds re-derivable
#   state (rate-limit buckets, session denylist — a restore logs everyone's
#   revocations back in, acceptable) and Kafka topics have no in-tree
#   consumers yet. The system of record is entirely in Postgres.

set -euo pipefail

NAMESPACE="${PG_NAMESPACE:-ticketing}"
POD="${PG_POD:-postgres-0}"
PG_USER="${PG_USER:-postgres}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/ticketing}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
# Off-box destination, e.g. s3://my-bucket/ticketing-pg (no trailing slash).
BACKUP_S3_URI="${BACKUP_S3_URI:-}"
ALLOW_LOCAL_ONLY="${ALLOW_LOCAL_ONLY:-0}"

if [[ -z "$BACKUP_S3_URI" && "$ALLOW_LOCAL_ONLY" != "1" ]]; then
    echo "[$(date -u +%FT%TZ)] backup FAILED: BACKUP_S3_URI is not set." >&2
    echo "A backup that lives only on the same disk as the database is not a backup." >&2
    echo "Set BACKUP_S3_URI=s3://<bucket>/<prefix> (or ALLOW_LOCAL_ONLY=1 to accept same-disk-only, e.g. on a dev box)." >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"

TIMESTAMP=$(date -u +%Y%m%d-%H%M%S)
# Atomic-write: dump to a .partial name, mv into place once the gzip
# closes cleanly. A failure mid-dump never leaves a half-written file
# under the canonical name (which restore tooling might then pick up).
TMPFILE="$BACKUP_DIR/.partial-$TIMESTAMP.sql.gz"
FINALFILE="$BACKUP_DIR/postgres-$TIMESTAMP.sql.gz"

if ! kubectl -n "$NAMESPACE" exec "$POD" -- pg_dumpall -U "$PG_USER" --clean --if-exists \
        | gzip > "$TMPFILE"; then
    rm -f "$TMPFILE"
    echo "[$(date -u +%FT%TZ)] backup FAILED pod=$NAMESPACE/$POD" >&2
    exit 1
fi

mv "$TMPFILE" "$FINALFILE"
SIZE=$(du -h "$FINALFILE" | cut -f1)
echo "[$(date -u +%FT%TZ)] dump ok size=$SIZE file=$FINALFILE"

if [[ -n "$BACKUP_S3_URI" ]]; then
    # Fail loudly if the upload fails — the local copy alone does not count
    # as a completed backup (same-disk, see header).
    if ! aws s3 cp "$FINALFILE" "$BACKUP_S3_URI/$(basename "$FINALFILE")" --only-show-errors; then
        echo "[$(date -u +%FT%TZ)] backup FAILED: off-box upload to $BACKUP_S3_URI did not succeed (local dump kept at $FINALFILE)" >&2
        exit 1
    fi
    echo "[$(date -u +%FT%TZ)] backup ok (off-box) uri=$BACKUP_S3_URI/$(basename "$FINALFILE")"
else
    echo "[$(date -u +%FT%TZ)] backup ok (LOCAL ONLY — ALLOW_LOCAL_ONLY=1)"
fi

# Rotate: delete plain local backups older than the retention window. The
# .partial- prefix is excluded so a backup running concurrently with
# rotation doesn't lose its in-flight file. S3 retention is governed by a
# bucket lifecycle rule, not by this script.
find "$BACKUP_DIR" -maxdepth 1 -name 'postgres-*.sql.gz' -mtime "+$RETENTION_DAYS" -print -delete
