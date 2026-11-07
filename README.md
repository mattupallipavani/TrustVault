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
* Actuator Health: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
* MinIO Console: [http://localhost:9001](http://localhost:9001)

### Demo Accounts
Demo accounts are seeded automatically on an empty database:

| Email | Role | Password |
| :--- | :--- | :--- |
| `admin@trustvault.local` | `ADMIN` | `TrustVault#2026` |
| `owner@trustvault.local` | `OWNER` | `TrustVault#2026` |
| `viewer@trustvault.local` | `VIEWER` | `TrustVault#2026` |

---

## Local Development

Requirements: Java 21 LTS, Maven Wrapper.

```bash
# 1. Run PostgreSQL and Redis locally or via Docker
docker compose up -d postgres redis minio

# 2. Run Spring Boot application
export DB_URL="jdbc:postgresql://localhost:5432/trustvault_db"
export DB_PASSWORD="your-strong-password"
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
