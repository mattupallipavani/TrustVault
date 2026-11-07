# TrustVault — Enterprise Deployment & Operations Guide

This guide covers production deployment, environment provisioning, backup/restore procedures, and operational verification for TrustVault.

---

## 1. System Requirements

* **Compute:** 2 vCPU, 4 GB RAM minimum (Production: 4 vCPU, 8 GB RAM).
* **Runtime:** Docker Engine 24.0+ and Docker Compose v2.20+.
* **Operating System:** Linux (Ubuntu 22.04 LTS, Debian 12, RHEL 9), or macOS/Windows for development.
* **Storage:** Dedicated persistent volumes for PostgreSQL (`/var/lib/postgresql/data`), Redis (`/data`), and MinIO (`/data`).

---

## 2. Infrastructure Architecture

The deployment topology consists of 4 isolated containerized services connected over an internal Docker bridge network (`trustvault-net`):

1. **`trustvault-app`**: Spring Boot 4.1.x application running as non-root user `trustvault` (UID 10001) on port `8080`.
2. **`trustvault-postgres`**: PostgreSQL 16 Alpine with Flyway schema migration lifecycle.
3. **`trustvault-redis`**: Redis 7.4 Alpine for sliding-window rate limiting and distributed audit lock coordination.
4. **`trustvault-minio`**: MinIO S3-compatible private object store (API on `9000`, Admin Console on `9001`).

**Only the application (8080) and the MinIO console (9001) are published, and
the console is bound to `127.0.0.1`. PostgreSQL, Redis and the MinIO S3 API are
reachable only from containers on `trustvault-net`.** Use `docker compose exec`
for administrative access instead of exposing those ports. For a host-run JVM
against containerised dependencies, use `docker-compose.dev.yml`, which
publishes them on loopback only.

---

## 3. Environment Configuration

1. Copy the environment template:
   ```bash
   cp .env.example .env
   ```

2. Configure strong secrets in `.env`:
   ```dotenv
   POSTGRES_DB=trustvault_db
   DB_USERNAME=trustvault_app
   DB_PASSWORD=YOUR_STRONG_POSTGRES_PASSWORD

   REDIS_PASSWORD=YOUR_STRONG_REDIS_PASSWORD

   MINIO_ACCESS_KEY=trustvault_admin
   MINIO_SECRET_KEY=YOUR_STRONG_MINIO_SECRET_KEY
   MINIO_BUCKET=trustvault-documents
   STORAGE_TYPE=minio

   # Set to true behind reverse proxy with TLS termination
   SESSION_COOKIE_SECURE=true
   DDL_AUTO=validate
   ```

---

## 3a. Migrating existing MySQL data (one-time cutover)

Older deployments of TrustVault stored data in MySQL 8. PostgreSQL is the
supported database from Phase 2 onward, and `db/migrate-mysql-to-postgres.sh`
performs the cutover without losing users, permissions, documents or audit
history.

1. Start PostgreSQL and let Flyway build the schema:
   ```bash
   createdb -O trustvault_app trustvault_db
   DB_URL=jdbc:postgresql://localhost:5432/trustvault_db ./mvnw spring-boot:run
   ```
   Stop the application once `Successfully applied ... migrations` is logged.

2. Run the migration (MySQL is only read, never modified):
   ```bash
   MYSQL_PWD='<mysql-root-password>' PGPASSWORD='<pg-password>' \
     ./db/migrate-mysql-to-postgres.sh
   ```

   The script refuses to run against a non-empty target, refuses if any value is
the literal string `NULL`, imports with primary keys intact, advances every
identity sequence, points `audit_chain_anchor` at the imported chain head, and
aborts unless every table compares byte-for-byte after import.

3. Start the application against PostgreSQL and verify:
   ```bash
   curl -s localhost:8080/api/health
   # signed in as ADMIN:
   curl -s -b cookies localhost:8080/api/admin/audit/verify
   # -> {"valid":true,...}
   ```

4. Keep the MySQL database untouched until the audit chain verifies and a
document download succeeds for every owner. It is the rollback.

**What is deliberately not migrated:** `document_share_restrictions` (zero rows,
no entity, no endpoint - an unimplemented feature) and
`document_permissions.share_privilege` (empty in every row, referenced nowhere;
dropped by `V2__reconcile_schema_drift.sql`).

Document bytes are **not** in the database. Point `STORAGE_TYPE=local` at the
existing `trustvault-storage` directory, or upload the files into MinIO, and
confirm `documents.storage_key` resolves for each row - the download path
verifies the stored SHA-256 against `documents.content_hash`.

---

## 4. Deployment Execution

Start the complete infrastructure with health-checked dependency ordering:

```bash
docker compose up -d --build
```

### Inspect Container Readiness
```bash
docker compose ps
```

All 4 services should report `Up (healthy)`.

### View Live Application Logs
```bash
docker compose logs -f app
```

---

## 5. Verification & Health Probes

### 1. Actuator Health Probe
```bash
curl -i http://localhost:8080/actuator/health
```
Expected output:
```json
{"status":"UP","components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},"redis":{"status":"UP"},"storage":{"status":"UP","details":{"storageType":"MinioStorageService"}}}}
```

### 2. Swagger / OpenAPI Documentation
Open Swagger UI in your browser:
**`http://localhost:8080/swagger-ui/index.html`**

### 3. End-to-End Audit Verification
Execute audit chain recomputation:
```bash
curl -s http://localhost:8080/api/admin/audit/verify
```

### 4. Storage Integrity Sweep

Checks that every stored object still matches the database: size, and the
SHA-256 recorded at upload. This is the only check that covers documents nobody
has downloaded recently, so it is what catches silent corruption.

```bash
# Shallow: one stat per document (metadata hash). Use in scheduled jobs.
curl -s -X POST -b cookies -H "X-XSRF-TOKEN: $TOKEN" \
  http://localhost:8080/api/admin/storage/verify

# Deep: re-reads every object and recomputes its SHA-256. Costs a full read of
# the archive; run it during maintenance windows, not on a timer.
curl -s -X POST -b cookies -H "X-XSRF-TOKEN: $TOKEN" \
  "http://localhost:8080/api/admin/storage/verify?deep=true"
```

Both return `checked`, `healthy`, `missing`, `sizeMismatched`, `hashMismatched`
and up to 25 problem entries. The run itself is written to the audit log as
`STORAGE_VERIFIED`, with a failure outcome if anything is wrong.

### 5. Full Stack Verification
```bash
./db/verify-docker-stack.sh
```
Runs login for all three roles, upload, download with SHA-256 comparison,
authorization denials, audit chain, integrity sweep, presigned link, tamper
detection and Redis rate limiting. Exits non-zero on any failure.

---

## 5b. Object Storage Guarantees

| Guarantee | How it is implemented |
| :--- | :--- |
| Content addressing | SHA-256 computed on upload, stored in `documents.content_hash` **and** as object metadata (`x-amz-meta-sha256`) |
| Upload verified | Every PUT is followed by a `stat`; if size or hash differ the object is deleted and the upload fails |
| Download verified | One `stat` compares size and metadata hash before streaming; a mismatch aborts with 500 and a failure audit row |
| Archive sweep | `POST /api/admin/storage/verify` (shallow/deep) audits the whole archive |
| Private bucket | No anonymous policy is ever set; only the application's credentials can read objects |
| Versioning | Enabled on startup, so an overwrite or delete leaves the previous version recoverable |
| Presigned links | `POST /api/documents/{id}/download-url` after a permission check; lifetime capped at 300s (default 60s) and the issuance is audited |

The bucket is private, so a presigned URL is the only way out of the object
store without the application proxying the bytes. Two consequences worth
stating plainly:

1. `MINIO_PUBLIC_ENDPOINT` must be the address the **client** can reach for
   links to work (behind TLS, the public HTTPS origin). It is used only for
   signing; the application still talks to the internal endpoint.
2. A presigned URL is a bearer credential until it expires. It cannot be
   revoked early and the fetch is not individually audited — only the issuance
   is. Keep the lifetime short.

---

## 6. Backup & Disaster Recovery

PostgreSQL holds the document rows and the audit chain; MinIO holds the bytes
they point at. A backup of one without the other restores to a system that
looks healthy until someone downloads a file, so both are captured together by
one script.

### Backup
```bash
./db/backup.sh                       # -> backups/<timestamp>/
./db/backup.sh /secure/backups/tv1   # explicit destination
```

The application is stopped while the two halves are captured, so the database
rows and the objects cannot disagree. The script writes:

| File | Contents |
| :--- | :--- |
| `db.dump` | PostgreSQL custom-format dump (rows, schema, audit chain) |
| `minio.tar.gz` | the object volume |
| `manifest.txt` | row counts, Flyway version and audit chain head |
| `SHA256SUMS` | checksums of both archives |

`backups/` is gitignored: it contains real document bytes and user data.

### Restore
```bash
./db/restore.sh backups/20261108-031413
```

Verifies the checksums first, recreates the schema, restores the dump, replaces
the object volume, and restarts the stack. It then prints the restored row
counts; finish with `./db/verify-docker-stack.sh`, whose integrity sweep must
report `allHealthy`.

This path is tested, not assumed: the volumes were destroyed with
`docker compose down -v` and rebuilt from a backup, after which a restored
document downloaded to the exact SHA-256 it had before, the audit chain verified
and the sweep reported every object healthy.

---

## 7. Troubleshooting

| Issue | Probable Cause | Remediation |
| :--- | :--- | :--- |
| `FlywayValidateException` on startup | Database schema manually modified out-of-band | Run Flyway repair or revert conflicting column changes. |
| `pull access denied for minio/minio` | MinIO removed its public images (Docker Hub 2026-09-12, quay.io now 401 for all tags) | The stack pins `cgr.dev/chainguard/minio` by digest instead. Do not re-point it at `minio/minio`. |
| `MinIO connection refused` | `trustvault-minio` container still initializing | Ensure `depends_on: { minio: { condition: service_healthy } }` is enforced. |
| HTTP 429 on `/api/auth/login` | Exceeded 15 attempts in 1-minute sliding window | Wait 60 seconds for Redis sliding window counter expiration. |
| `AccessDeniedException` on `/api/admin/**` | Logged in user does not hold `ADMIN` role | Authenticate with an administrator account (`admin@trustvault.local`). |

