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

1. **`trustvault-app`**: Spring Boot 3.4.x application running as non-root user `trustvault` (UID 10001) on port `8080`.
2. **`trustvault-postgres`**: PostgreSQL 16 Alpine with Flyway schema migration lifecycle.
3. **`trustvault-redis`**: Redis 7.4 Alpine for sliding-window rate limiting and distributed audit lock coordination.
4. **`trustvault-minio`**: MinIO S3-compatible private object store (API on `9000`, Admin Console on `9001`).

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

   MINIO_ROOT_USER=trustvault_admin
   MINIO_ROOT_PASSWORD=YOUR_STRONG_MINIO_SECRET_KEY
   MINIO_BUCKET=trustvault-documents
   STORAGE_TYPE=minio

   # Set to true behind reverse proxy with TLS termination
   SESSION_COOKIE_SECURE=true
   DDL_AUTO=validate
   ```

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

---

## 6. Backup & Disaster Recovery

### PostgreSQL Backup
```bash
docker compose exec postgres pg_dump -U trustvault_app -d trustvault_db -F c -b -v -f /tmp/backup.dump
docker cp trustvault-postgres:/tmp/backup.dump ./backup-$(date +%F).dump
```

### PostgreSQL Restore
```bash
docker cp ./backup-2026-11-08.dump trustvault-postgres:/tmp/backup.dump
docker compose exec postgres pg_restore -U trustvault_app -d trustvault_db -v -c /tmp/backup.dump
```

### MinIO Document Backup
Sync document bucket to an encrypted backup volume:
```bash
docker run --rm --network trustvault_trustvault-net \
  -e MINIO_ACCESS_KEY=trustvault_admin \
  -e MINIO_SECRET_KEY=YOUR_STRONG_MINIO_SECRET_KEY \
  minio/mc mirror http://minio:9000/trustvault-documents /backup/documents
```

---

## 7. Troubleshooting

| Issue | Probable Cause | Remediation |
| :--- | :--- | :--- |
| `FlywayValidateException` on startup | Database schema manually modified out-of-band | Run Flyway repair or revert conflicting column changes. |
| `MinIO connection refused` | `trustvault-minio` container still initializing | Ensure `depends_on: { minio: { condition: service_healthy } }` is enforced. |
| HTTP 429 on `/api/auth/login` | Exceeded 15 attempts in 1-minute sliding window | Wait 60 seconds for Redis sliding window counter expiration. |
| `AccessDeniedException` on `/api/admin/**` | Logged in user does not hold `ADMIN` role | Authenticate with an administrator account (`admin@trustvault.local`). |

