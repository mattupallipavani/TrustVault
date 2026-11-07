#!/usr/bin/env bash
# Restore TrustVault from a backup produced by db/backup.sh.
#
# This REPLACES the current database schema and the object bucket contents.
# Checksums are verified before anything is touched, so a truncated or
# corrupted backup fails safely instead of half-applying.
#
# Usage: ./db/restore.sh backups/20261108-120000
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="${1:-}"
[ -n "$SRC" ] || { echo "Usage: $0 <backup-directory>" >&2; exit 1; }
[ -f "$SRC/db.dump" ] && [ -f "$SRC/minio.tar.gz" ] || {
  echo "Backup directory $SRC is missing db.dump or minio.tar.gz" >&2; exit 1; }

env_value() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2-; }
DB_USER=$(env_value DB_USERNAME); DB_USER=${DB_USER:-trustvault_app}
DB_NAME=$(env_value POSTGRES_DB); DB_NAME=${DB_NAME:-trustvault_db}

echo "==> verifying backup checksums"
( cd "$SRC" && shasum -a 256 -c SHA256SUMS )

PROJECT=$(docker inspect trustvault-minio --format \
  '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)
[ -n "$PROJECT" ] || { echo "Cannot find the running stack; start it first." >&2; exit 1; }
VOLUME="${PROJECT}_minio-data"

echo "==> stopping the application and object store"
docker compose stop app minio >/dev/null

echo "==> restoring PostgreSQL"
# Recreating the schema (not dropping objects one by one) makes the restore
# independent of whatever state the database is currently in.
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -q -c \
  "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
docker compose exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner --no-privileges < "$SRC/db.dump"

echo "==> restoring MinIO objects into $VOLUME"
docker run --rm -v "$VOLUME":/data -v "$PWD/$SRC":/backup alpine \
  sh -c 'rm -rf /data/* && tar xzf /backup/minio.tar.gz -C /data'

# `up -d` rather than `start`: after a volume-level disaster the containers
# may not exist at all, and `start` cannot create them.
echo "==> starting the stack"
docker compose up -d minio >/dev/null
for i in $(seq 1 40); do
  st=$(docker inspect trustvault-minio --format '{{.State.Health.Status}}' 2>/dev/null || echo starting)
  [ "$st" = "healthy" ] && break; sleep 2
done
docker compose up -d app >/dev/null

echo "==> restored contents"
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tA -c \
  "SELECT 'users=' || COUNT(*) FROM users UNION ALL
   SELECT 'documents=' || COUNT(*) FROM documents UNION ALL
   SELECT 'audit_logs=' || COUNT(*) FROM audit_logs;"

echo "Verify with ./db/verify-docker-stack.sh (integrity sweep must be allHealthy)."
