#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# TrustVault: migrate existing data from MySQL to PostgreSQL.
#
# This is the procedure that was actually executed for the Phase 2 cutover.
# It is idempotent only in the sense that it refuses to run against a target
# that already holds rows - re-running requires an explicit TRUNCATE first.
#
# What it preserves:
#   * primary keys (so audit document_id / actor_user_id references stay valid)
#   * password hashes (users sign in with their existing passwords)
#   * audit_logs.previous_hash / entry_hash, i.e. the SHA-256 chain itself
#   * the audit_chain_anchor head, so the next append continues the chain
#
# What it does NOT do:
#   * touch the MySQL database (it stays intact as the rollback)
#   * copy document_share_restrictions (empty, unimplemented - see V2 migration)
#   * copy document_permissions.share_privilege (dropped in V2)
#
# Usage:
#   MYSQL_PWD=... PGPASSWORD=... ./db/migrate-mysql-to-postgres.sh
#
# Requires: mysql client, psql, and a PostgreSQL database that has already had
# Flyway migrations applied (start the application once against it).
# ---------------------------------------------------------------------------
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_DB="${MYSQL_DB:-trustvault_db}"

PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-trustvault_app}"
PGDATABASE="${PGDATABASE:-trustvault_db}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

MYSQL=(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" --batch --skip-column-names "$MYSQL_DB")
PSQL=(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -q)

echo "==> checking the target is empty"
existing=$("${PSQL[@]}" -tAc "SELECT (SELECT COUNT(*) FROM users) + (SELECT COUNT(*) FROM documents) + (SELECT COUNT(*) FROM audit_logs)")
if [ "$existing" != "0" ]; then
  echo "REFUSING: target already contains $existing rows. Truncate it first if this is intentional." >&2
  exit 1
fi

echo "==> checking no literal 'NULL' text would be mistaken for a NULL marker"
literal=$("${MYSQL[@]}" -e "SELECT
 (SELECT COUNT(*) FROM users WHERE full_name='NULL' OR email='NULL' OR password_hash='NULL' OR role='NULL')
+(SELECT COUNT(*) FROM documents WHERE file_name='NULL' OR file_type='NULL' OR owner_email='NULL' OR category='NULL' OR storage_key='NULL' OR content_hash='NULL')
+(SELECT COUNT(*) FROM document_permissions WHERE permission='NULL')
+(SELECT COUNT(*) FROM audit_logs WHERE actor_email='NULL' OR document_name='NULL' OR target_email='NULL' OR action='NULL' OR outcome='NULL' OR details='NULL' OR ip_address='NULL' OR user_agent='NULL' OR previous_hash='NULL' OR entry_hash='NULL')")
if [ "$literal" != "0" ]; then
  echo "REFUSING: $literal values are the literal string 'NULL'; choose a different NULL marker." >&2
  exit 1
fi

echo "==> exporting from MySQL"
"${MYSQL[@]}" -e "SELECT id, full_name, email, password_hash, role, created_at FROM users ORDER BY id" > "$WORKDIR/users.tsv"
"${MYSQL[@]}" -e "SELECT id, file_name, file_type, file_size, owner_id, owner_email, category, storage_key, content_hash, uploaded_at FROM documents ORDER BY id" > "$WORKDIR/documents.tsv"
"${MYSQL[@]}" -e "SELECT id, document_id, user_id, permission, granted_at FROM document_permissions ORDER BY id" > "$WORKDIR/permissions.tsv"
"${MYSQL[@]}" -e "SELECT id, actor_user_id, actor_email, document_id, document_name, target_user_id, target_email, action, outcome, details, ip_address, user_agent, created_at, previous_hash, entry_hash FROM audit_logs ORDER BY id" > "$WORKDIR/audit.tsv"

echo "==> importing into PostgreSQL"
# MySQL batch mode writes NULL as the bare token NULL and escapes \t, \n, \\ the
# same way PostgreSQL's text format does, so the files can be copied verbatim.
"${PSQL[@]}" -c "\copy users (id, full_name, email, password_hash, role, created_at) FROM '$WORKDIR/users.tsv' WITH (FORMAT text, NULL 'NULL')"
"${PSQL[@]}" -c "\copy documents (id, file_name, file_type, file_size, owner_id, owner_email, category, storage_key, content_hash, uploaded_at) FROM '$WORKDIR/documents.tsv' WITH (FORMAT text, NULL 'NULL')"
"${PSQL[@]}" -c "\copy document_permissions (id, document_id, user_id, permission, granted_at) FROM '$WORKDIR/permissions.tsv' WITH (FORMAT text, NULL 'NULL')"
"${PSQL[@]}" -c "\copy audit_logs (id, actor_user_id, actor_email, document_id, document_name, target_user_id, target_email, action, outcome, details, ip_address, user_agent, created_at, previous_hash, entry_hash) FROM '$WORKDIR/audit.tsv' WITH (FORMAT text, NULL 'NULL')"

echo "==> advancing identity sequences past the imported ids"
"${PSQL[@]}" -c "SELECT setval(pg_get_serial_sequence('users','id'), (SELECT MAX(id) FROM users), true);
                  SELECT setval(pg_get_serial_sequence('documents','id'), (SELECT MAX(id) FROM documents), true);
                  SELECT setval(pg_get_serial_sequence('document_permissions','id'), (SELECT MAX(id) FROM document_permissions), true);
                  SELECT setval(pg_get_serial_sequence('audit_logs','id'), (SELECT MAX(id) FROM audit_logs), true);" >/dev/null

echo "==> pointing the audit anchor at the imported chain head"
"${PSQL[@]}" -c "UPDATE audit_chain_anchor a
                  SET last_audit_id = l.id, head_hash = l.entry_hash, updated_at = l.created_at
                  FROM (SELECT id, entry_hash, created_at FROM audit_logs ORDER BY id DESC LIMIT 1) l
                  WHERE a.anchor_id = 1;"

echo "==> verifying row-for-row equality (timestamps normalised to 6 digits)"
"${PSQL[@]}" -c "\copy (SELECT id, full_name, email, password_hash, role, to_char(created_at,'YYYY-MM-DD HH24:MI:SS.US') FROM users ORDER BY id) TO STDOUT WITH (FORMAT text, NULL 'NULL')" > "$WORKDIR/pg_users.tsv"
"${PSQL[@]}" -c "\copy (SELECT id, document_id, user_id, permission, to_char(granted_at,'YYYY-MM-DD HH24:MI:SS.US') FROM document_permissions ORDER BY id) TO STDOUT WITH (FORMAT text, NULL 'NULL')" > "$WORKDIR/pg_permissions.tsv"
"${PSQL[@]}" -c "\copy (SELECT id, actor_user_id, actor_email, document_id, document_name, target_user_id, target_email, action, outcome, details, ip_address, user_agent, to_char(created_at,'YYYY-MM-DD HH24:MI:SS.US'), previous_hash, entry_hash FROM audit_logs ORDER BY id) TO STDOUT WITH (FORMAT text, NULL 'NULL')" > "$WORKDIR/pg_audit.tsv"

for table in users permissions audit; do
  if ! cmp -s "$WORKDIR/$table.tsv" "$WORKDIR/pg_$table.tsv"; then
    echo "MISMATCH in $table - do not cut over" >&2
    diff "$WORKDIR/$table.tsv" "$WORKDIR/pg_$table.tsv" | head -5 >&2
    exit 1
  fi
  echo "    $table: identical"
done

echo "==> done. Start the application against PostgreSQL and confirm"
echo "    GET /api/admin/audit/verify returns valid=true"
