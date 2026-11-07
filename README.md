# TrustVault Enterprise

**Secure document sharing platform where the server evaluates authorization on every request, permissions take effect instantaneously, and every attempt is captured in a tamper-evident audit trail.**

Upload confidential contracts, employee records, financial reports, and sensitive business documents. Grant granular `READ` or `EDIT` permissions to named individuals, revoke them instantly, and prove who accessed what — including attempts that were refused.

**Technology Stack:** Java 21 LTS · Spring Boot 4.1.x · Spring Security 7 · Spring Data JPA · PostgreSQL 16 · Flyway Migrations · MinIO (S3) Object Storage · Redis 7 (Rate Limiting & Locking) · SpringDoc OpenAPI · Vanilla JS SPA.

---

## Capabilities & Architectural Highlights

- **Centralized Authorization Choke Point:** All access decisions route through `PermissionService.requireRead`, `requireEdit`, and `requireOwner`. Document binary streams cannot be retrieved by ID alone without passing permission checks.
- **Immediate Revocation Guarantee:** No authorization decisions or bearer tokens are cached. A revoked permission takes effect on the very next HTTP request.
- **Non-Repudiation of Denied Attempts:** Unauthorized access attempts trigger `AuditLogService.denied()` executed in an independent `REQUIRES_NEW` transaction, ensuring audit evidence is permanently recorded even when the outer request transaction rolls back.
- **Tamper-Evident SHA-256 Audit Chain:** Append-only cryptographic hash chaining linking each event to its predecessor (`\u001F` unit separator delimiter).
- **Cluster-Safe Multi-Instance Serialization:** Audit appends obtain a pessimistic row lock (`SELECT ... FOR UPDATE`) on the `audit_chain_anchor` table, eliminating hash-chain forks across distributed container pods.
- **S3 / MinIO Object Storage Abstraction:** Decoupled storage port (`StorageService`) supporting MinIO and AWS S3 with zero-heap-buffering streaming verification via `DigestInputStream`.
- **Redis-Backed Rate Limiting:** Sliding-window rate limiter protecting `/api/auth/login` and `/api/auth/forgot-password` against brute-force attacks and credential stuffing.
- **Content & Archive Verification:** Magic byte headers checked for PDF, PNG, JPEG, and DOCX formats. Includes decompression-bomb (ZIP bomb) defense and malware scanning integration hooks.
- **Separation of Duties:** Administrators inspect audit logs and manage user accounts but **cannot** open or download document content.

---

## Roles & Permission Model

| Role | Permitted Actions | Prohibited Actions |
| :--- | :--- | :--- |
| `ADMIN` | Read audit trail, verify hash chain integrity, list users, change roles, trigger temporary password resets. | Read document content, grant or revoke document access. |
| `OWNER` | Upload documents, own files, grant/revoke permissions, edit metadata, delete owned files. | Read ungranted documents belonging to another user. |
| `VIEWER` | Read and download documents explicitly granted to the account. | Upload documents, grant access, access unshared documents. |

Access permissions are enforced per document:
```
Document Owner      -> Full READ and EDIT
Granted EDIT        -> READ and EDIT
Granted READ        -> READ only
No grant            -> Zero access on every endpoint (including metadata)
```

---

## Quick Start with Docker Compose

Requirements: Docker Engine 24+ and Docker Compose.

```bash
# 1. Clone & copy environment configuration
cp .env.example .env

# 2. Launch PostgreSQL, Redis, MinIO, and TrustVault
docker compose up -d --build
```

Access the application:
* Web UI: [http://localhost:8080](http://localhost:8080)
* Swagger UI: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
* Actuator Health: [http://localhost:8080/api/health](http://localhost:8080/api/health)
* MinIO Console: [http://localhost:9001](http://localhost:9001) (loopback only)

Only the application and the MinIO console are reachable from the host.
PostgreSQL, Redis and the MinIO S3 API stay on the internal Docker network;
reach them with `docker compose exec` rather than a published port.

Verify the whole stack end to end (login, upload to MinIO, download with
SHA-256 check, authorization denials, audit chain, storage integrity sweep,
presigned links, tamper detection, Redis rate limiting):
```bash
./db/verify-docker-stack.sh
```

Back up and restore the database and document store together:
```bash
./db/backup.sh                              # -> backups/<timestamp>/
./db/restore.sh backups/<timestamp>         # checksums verified first
```

Stored objects are verified against their recorded SHA-256 on upload and on
download; `POST /api/admin/storage/verify` sweeps the whole archive. An
integrity sweep runs daily and superseded object versions expire after 30 days,
both audited and alerted on failure — see DEPLOYMENT.md §5b and §5c.

### Demo Accounts
Demo accounts are seeded automatically on an empty database. Their password is
whatever `DEMO_PASSWORD` is set to in `.env` — the values below are the
`.env.example` default, intended for local demonstration only:

| Email | Role | Password |
| :--- | :--- | :--- |
| `admin@trustvault.local` | `ADMIN` | `TrustVault#2026` |
| `owner@trustvault.local` | `OWNER` | `TrustVault#2026` |
| `viewer@trustvault.local` | `VIEWER` | `TrustVault#2026` |

---

## Local Development

Requirements: Java 21 LTS, Maven Wrapper.

Run the dependencies in Docker and the application on the host. The base
Compose file publishes no dependency ports, so use the development override,
which binds them to loopback only:

```bash
# 1. Start PostgreSQL, Redis and MinIO (ports published on 127.0.0.1)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres redis minio

# 2. Run the Spring Boot application against them
export DB_URL="jdbc:postgresql://127.0.0.1:5432/trustvault_db"
export DB_USERNAME="trustvault_app"
export DB_PASSWORD="$(grep -E '^DB_PASSWORD=' .env | cut -d= -f2-)"
export REDIS_HOST="127.0.0.1"
export REDIS_PASSWORD="$(grep -E '^REDIS_PASSWORD=' .env | cut -d= -f2-)"
export MINIO_ENDPOINT="http://127.0.0.1:9000"
export MINIO_ACCESS_KEY="$(grep -E '^MINIO_ACCESS_KEY=' .env | cut -d= -f2-)"
export MINIO_SECRET_KEY="$(grep -E '^MINIO_SECRET_KEY=' .env | cut -d= -f2-)"
export STORAGE_TYPE="minio"
./mvnw spring-boot:run
```

---

## Testing & Quality Assurance

```bash
# Run unit and integration tests
./mvnw test
```

49 automated tests covering:
* `AuthorizationIntegrationTest`: 18 end-to-end tests exercising real HTTP sessions, CSRF token validation, IDOR attack prevention, and immediate revocation.
* `PermissionServiceTest`: Access control matrix and fail-closed validation.
* `FileValidationServiceTest`: Magic bytes, filename traversal sanitization, and DOCX archive validation.
* `AuditLogServiceTest`: SHA-256 hash chaining, genesis links, and `REQUIRES_NEW` denial persistence.
* `AuditLogIntegrityServiceTest`: Tampering detection, reordered chains, and missing hash links.
