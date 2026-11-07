#!/usr/bin/env bash
# Consistent backup of TrustVault: PostgreSQL dump + MinIO object volume +
# a manifest proving what the backup should contain.
#
# The two halves only mean anything together: PostgreSQL holds the document
# rows and audit chain, MinIO holds the bytes those rows point at. Backing up
# one without the other gives a restore that looks fine until someone clicks
# download.
#
# Usage: ./db/backup.sh [destination-directory]
set -euo pipefail
cd "$(dirname "$0")/.."

DEST="${1:-backups/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$DEST"

env_value() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2-; }

DB_USER=$(env_value DB_USERNAME); DB_USER=${DB_USER:-trustvault_app}
DB_NAME=$(env_value POSTGRES_DB); DB_NAME=${DB_NAME:-trustvault_db}

PROJECT=$(docker inspect trustvault-minio --format \
  '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)
[ -n "$PROJECT" ] || { echo "Cannot find the running stack; start it first." >&2; exit 1; }
VOLUME="${PROJECT}_minio-data"

echo "==> stopping the application so the two halves are captured at one moment"
docker compose stop app >/dev/null
trap 'echo "==> application restarted"; docker compose start app >/dev/null 2>&1 || true' EXIT

echo "==> dumping PostgreSQL -> $DEST/db.dump"
docker compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$DEST/db.dump"

echo "==> recording what the backup should contain"
{
  echo "created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "database=$DB_NAME"
  docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tA -c \
    "SELECT 'users=' || COUNT(*) FROM users UNION ALL
     SELECT 'documents=' || COUNT(*) FROM documents UNION ALL
     SELECT 'document_permissions=' || COUNT(*) FROM document_permissions UNION ALL
     SELECT 'audit_logs=' || COUNT(*) FROM audit_logs UNION ALL
     SELECT 'flyway_version=' || MAX(version) FROM flyway_schema_history UNION ALL
     SELECT 'audit_head=' || COALESCE(MAX(head_hash), 'none') FROM audit_chain_anchor;"
} > "$DEST/manifest.txt"

echo "==> archiving MinIO volume $VOLUME -> $DEST/minio.tar.gz"
docker run --rm -v "$VOLUME":/data:ro -v "$PWD/$DEST":/backup alpine \
  tar czf /backup/minio.tar.gz -C /data .

echo "==> recording checksums"
( cd "$DEST" && shasum -a 256 db.dump minio.tar.gz > SHA256SUMS )

cat "$DEST/manifest.txt"
ls -lh "$DEST"
echo
echo "Backup complete: $DEST"
echo "Restore with: ./db/restore.sh $DEST"
