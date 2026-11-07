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

## 5c. Scheduled Maintenance

Two jobs keep the archive trustworthy without anyone remembering to run them.
Both are recorded in the audit log, alerted on failure, and reflected in
`/actuator/health`.

| Job | Default schedule | What it does | Cost |
| :--- | :--- | :--- | :--- |
| Integrity sweep | `0 0 3 * * *` (daily 03:00) | Compares every document against its recorded size and SHA-256 using object metadata | One stat per document |
| Deep integrity sweep | `0 0 4 * * SUN` (weekly, off by default) | Re-reads every object and recomputes its SHA-256 | A full read of the archive |
| Version retention | `0 30 3 * * *` (daily 03:30) | Removes superseded object versions older than `MAINTENANCE_RETENTION_DAYS` (30) | One listing per object |

### Retention semantics

Only **superseded** versions are removed. Two things are never touched:

- **The current version** — that is the live object.
- **Delete markers** — removing one makes the previous version current again, so
a deleted document would silently reappear. Markers are tiny; keeping them is
the safe choice.

### Configuration

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `MAINTENANCE_ENABLED` | `true` | Master switch for all scheduled jobs |
| `MAINTENANCE_INTEGRITY_ENABLED` | `true` | Daily sweep on/off |
| `MAINTENANCE_INTEGRITY_CRON` | `0 0 3 * * *` | Spring 6-field cron |
| `MAINTENANCE_DEEP_ENABLED` | `false` | Enable the weekly full re-hash |
| `MAINTENANCE_RETENTION_ENABLED` | `true` | Retention on/off |
| `MAINTENANCE_RETENTION_CRON` | `0 30 3 * * *` | Retention cron |
| `MAINTENANCE_RETENTION_DAYS` | `30` | Age at which a superseded version is removed |
| `MAINTENANCE_STALE_AFTER_HOURS` | `48` | Age at which "no recent sweep" turns the health component DOWN |
| `MAINTENANCE_ALERT_WEBHOOK` | *(empty)* | Slack-compatible incoming webhook for failures |

### Alerting

A failure fans out three ways, because each covers a different failure mode:

1. **Audit row** — `STORAGE_VERIFIED` or `STORAGE_RETENTION` with a failure
   outcome, in the tamper-evident chain.
2. **Webhook** — POSTs `{"text": "..."}` to `MAINTENANCE_ALERT_WEBHOOK` when set.
   Delivery failure is logged and swallowed: alerting must never break the job
   it is reporting on.
3. **Health** — `/actuator/health` exposes a `storageMaintenance` component that
   goes **DOWN** on a failed sweep, and also when the last sweep is older than
   `MAINTENANCE_STALE_AFTER_HOURS`. A scheduler that silently stopped is
   otherwise indistinguishable from one that keeps finding nothing wrong.

Note that `/actuator/**` requires the `ADMIN` role in this deployment, so a
external monitor needs admin credentials (or its own exposure rule) to read it.

### Manual runs

```bash
# sweep now (also updates health and alerts, exactly like the scheduled run)
curl -s -X POST -b cookies -H "X-XSRF-TOKEN: $TOKEN" \
  http://localhost:8080/api/admin/storage/verify

# retention now; days=0 expires every superseded version
curl -s -X POST -b cookies -H "X-XSRF-TOKEN: $TOKEN" \
  "http://localhost:8080/api/admin/storage/retention?days=0"

# what ran, when, and with which trigger
curl -s -b cookies http://localhost:8080/api/admin/storage/maintenance/status
```

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

---

## 8. Zero-Cost Cloud Deployment (Render + Supabase)

This architecture achieves **100% free hosting (₹0.00)** with no credit cards, no trial traps, and zero risk of unexpected charges.

### Architecture Overview

* **Application Host:** Render Free Web Service (Docker runtime, 512 MB RAM, 0.1 vCPU).
* **Database:** Supabase Free PostgreSQL (500 MB limit, IPv4 Session Connection Pooler).
* **Document Storage:** Supabase Free Storage (1 GB private bucket, authenticated REST API).
* **Frontend:** Embedded Single-Page Application (SPA) served directly from the Spring Boot JAR on Render.

---

### Step 1: Provision Free Supabase Database & Storage

1. Create a free account at [supabase.com](https://supabase.com).
2. Click **New Project**:
   * Name: `trustvault-prod`
   * Set a strong database password and save it.
   * Region: Choose the closest region (e.g. Singapore, Frankfurt, US East).
   * Plan: **Free** (₹0).
3. **Get the Session Pooler Database URL**:
   * Go to **Project Settings** (gear icon) $\to$ **Database**.
   * Scroll down to **Connection string** and select the **Connection Pooling** tab.
   * Set **Mode** to **Session** (Port `5432`). **Do not use Transaction mode (6543)** because Flyway migration locks require session mode.
   * Note the connection details:
     * Host: `aws-0-[region].pooler.supabase.com`
     * Port: `5432`
     * Database: `postgres`
     * Username: `postgres.[project-ref]`
4. **Create the Private Storage Bucket**:
   * In the left navigation, click **Storage**.
   * Click **New Bucket**.
   * Name: `trustvault-documents`.
   * **Leave "Public bucket" toggled OFF** (must remain private).
   * Click **Save bucket**.
5. **Get the API Credentials**:
   * Go to **Project Settings** $\to$ **API**.
   * Copy the **Project URL** (`https://[project-ref].supabase.co`).
   * Copy the **`service_role` secret key** (click the eye icon). *Keep this secret; it is used only by the Spring Boot backend to access the private bucket.*

---

### Step 2: Deploy to Render Free Web Service

1. Create a free account at [render.com](https://render.com) using your GitHub account (`mattupallipavani`).
2. Click **New +** $\to$ **Web Service**.
3. Select **Build and deploy from a Git repository** and pick `mattupallipavani/TrustVault`.
4. Configure service settings:
   * **Name:** `trustvault`
   * **Region:** Same region as your Supabase project (e.g. Oregon, Frankfurt, Singapore).
   * **Branch:** `main`
   * **Runtime:** **Docker**
   * **Instance Type:** **Free** (512 MB RAM, 0.1 CPU, ₹0/month)
5. Add the following **Environment Variables** in Render:

| Key | Value | Notes |
| :--- | :--- | :--- |
| `DB_URL` | `jdbc:postgresql://aws-0-[region].pooler.supabase.com:5432/postgres?sslmode=require` | Supabase IPv4 Session Pooler |
| `DB_USERNAME` | `postgres.[project-ref]` | Supabase pooler username |
| `DB_PASSWORD` | `[YOUR_SUPABASE_PASSWORD]` | Database password |
| `STORAGE_TYPE` | `supabase` | Activates Supabase Storage adapter |
| `SUPABASE_URL` | `https://[project-ref].supabase.co` | Supabase API endpoint |
| `SUPABASE_SERVICE_KEY` | `[YOUR_SERVICE_ROLE_KEY]` | Backend-only storage credentials |
| `SUPABASE_BUCKET` | `trustvault-documents` | Private bucket name |
| `DEMO_PASSWORD` | `[A-Strong-Initial-Password]` | Initial seed account password |
| `SESSION_COOKIE_SECURE` | `true` | Enforces HTTPS session cookies on Render |
| `MANAGEMENT_HEALTH_REDIS_ENABLED` | `false` | Keeps Actuator healthy without Redis |

6. Under **Advanced Settings**, set **Health Check Path** to:
   `/actuator/health`
7. Click **Create Web Service**.

Render will pull the repository, build the multi-stage Docker image, bind to Render's dynamic `$PORT`, execute Flyway migrations against Supabase, and expose your live HTTPS application at `https://trustvault-xxxx.onrender.com`.

---

### Free Tier Limits & Avoiding Charges

* **Render Inactivity Spin-Down:** Free instances spin down after 15 minutes of inactivity. The first request after idling takes ~50 seconds (cold start). Once awake, requests are fast.
* **Supabase 7-Day Inactivity Pause:** If the database receives no queries for 7 consecutive days, Supabase pauses it. To resume, log into the Supabase dashboard and click **Restore project** (free, takes ~1 minute, zero data loss).
* **Storage Cap:** The free tier includes 1 GB storage. Uploads exceeding 1 GB are rejected with an error; no overage fees are ever charged.

