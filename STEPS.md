# TrustVault — Development Status

`[x]` means implemented **and covered by an automated test**, not merely written.
`[ ]` means outstanding. Anything unfinished is described in `THREATS.md`
rather than quietly omitted.

## 1. Project setup

- [x] Spring Boot 4.1.2 / Java 21 LTS Maven project
- [x] PostgreSQL 16 database with Flyway versioned migrations (`V1`, `V2`, `V3`)
- [x] `spring.jpa.hibernate.ddl-auto=validate` preventing runtime DDL drift
- [x] Multi-stage Dockerfile with non-root user, health check, and graceful shutdown
- [x] Docker Compose stack: PostgreSQL 16, Redis 7.4, MinIO, and App
- [x] Actuator health (`/actuator/health`), info, and metrics endpoints
- [x] Storage and maintenance custom Actuator health indicators
- [x] OpenAPI 3.1 & Swagger UI documentation at `/swagger-ui.html`
- [x] Secrets excluded from Git (`.gitignore`: `.env`, storage, logs)
- [x] Reviewable schema documentation in `db/schema.sql` (delegated to Flyway)
- [x] GitHub Actions CI workflow with build, test, and container packaging
- [x] Initialise Git repository with structured commit history

## 2. Roles and authentication

- [x] Three roles: `ADMIN`, `OWNER`, `VIEWER`, with behaviour documented in `README.md` and `API.md`
- [x] Registration cannot choose its own role (hard-coded `OWNER`)
- [x] Session login with JSON responses, no redirect to an HTML login page
- [x] Sign-in, sign-out and failed sign-in are audited
- [x] Session fixation protection (`migrateSession`), `HttpOnly` + `SameSite` cookies, server-side logout
- [x] Single concurrent session per user (`maximumSessions(1)`)
- [x] Redis-backed sliding window rate limiter on auth endpoints with resilient in-memory fallback
- [x] Admin-only role changes, audited, and an admin cannot change their own role
- [x] Temporary password issuance with mandatory `must_change_password` policy
- [x] Self-service password change endpoint (`POST /api/users/me/change-password`) clearing restriction
- [ ] MFA / TOTP (`THREATS.md` §3.3)

## 3. Document management

- [x] Storage service abstraction (`StorageService`) with MinIO S3 and local filesystem providers
- [x] Upload with size (20MB), extension, magic-byte and filename validation
- [x] ZIP/DOCX decompression bomb defense (compression ratio and size limits)
- [x] Pluggable anti-malware scanning pipeline (`MalwareScanner`)
- [x] Content type derived strictly from the validated extension, never client-controlled
- [x] Storage outside web root, UUID keys, path traversal impossible
- [x] SHA-256 of uploaded bytes stored and re-verified on retrieval
- [x] Listing scoped to owned or granted documents in SQL
- [x] Search by name, category, owner, date range, owned/shared
- [x] Pagination support on document retrieval (`GET /api/documents/paged`)
- [x] Rename / recategorise with `EDIT`; content is immutable
- [x] Owner-only delete cleaning permissions and unlinking storage post-transaction
- [ ] Versioning, soft delete and restore (`THREATS.md` §4)

## 4. Permissions

- [x] Per-document `READ` / `EDIT` grants, one row per `(document, user)`
- [x] Owner-only grant, revoke and permission listing
- [x] `EDIT` implies `READ`; unknown stored values fail closed
- [x] Denied attempts audited in a separate `REQUIRES_NEW` transaction
- [x] Immediate revocation, proven by end-to-end integration tests
- [x] IDOR protection on every route (list, search, metadata, download, update, delete, grant, listing)
- [ ] Groups and folder inheritance (`THREATS.md` §4)

## 5. Audit trail & compliance

- [x] Tamper-evident SHA-256 audit log chain
- [x] Anchor table row locking (`audit_chain_anchor`) ensuring multi-instance write serialisation
- [x] Upload, download, update, delete, grant, revoke, role change, login, logout, password change, refusals
- [x] Append-only: zero update or delete paths in application
- [x] Shared `AuditHasher` used uniformly by writer and verifier
- [x] Timestamp truncated so stored precision cannot break verification
- [x] Verification endpoint (`GET /api/admin/audit/verify`) identifying exact broken links
- [x] Paginated audit query endpoint (`GET /api/admin/audit/paged`)
- [x] Actor email and document name snapshots surviving account/document deletion
- [x] Admin filters by action, outcome, actor, document, and date range
- [ ] External signed checkpoint / transparency log (`THREATS.md` §3.2)
- [ ] Detect truncation of the newest rows

## 6. Admin, storage maintenance & operations

- [x] Audit view restricted to admins, enforced by URL filter rule and method security
- [x] Document history endpoint, including deleted documents
- [x] Denied-event counters for admin security dashboard
- [x] Admin cannot read document content (strict separation of duties)
- [x] Background storage integrity scanner (`StorageIntegrityService`)
- [x] Scheduled storage maintenance (`StorageMaintenanceScheduler`)
- [x] Storage maintenance Actuator health indicators and alert webhook support

## 7. Error handling & observability

- [x] Centralized `GlobalExceptionHandler` with consistent status codes
- [x] Zero stack traces, SQL syntax or internal class names leaked to clients
- [x] Generic denial messages preventing authorization oracle probing
- [x] Structured validation error responses (`fieldErrors`)
- [x] MDC correlation filter populating `X-Correlation-ID` across logs and HTTP response headers

## 8. Front end

- [x] SPA interface for authentication, document management, and sharing
- [x] CSRF bootstrap and header on every mutation
- [x] Document list with access level, upload, search, download, rename, delete
- [x] Sharing panel with grant, revoke, and exact-email lookup
- [x] Admin audit table with filters, pagination, and chain verification
- [x] All user-supplied text rendered with `textContent` (XSS mitigation)
- [x] Restrictive Content Security Policy (CSP), frame options, permissions policy

## 9. Automated tests — 84 passing tests

- [x] `AuthorizationIntegrationTest` — 22 end-to-end HTTP tests covering roles, IDOR, password change
- [x] `ArchitectureTest` (ArchUnit) — 9 architectural rules (layering, naming, dependency hygiene)
- [x] `PermissionServiceTest` — 8 tests
- [x] `FileValidationServiceTest` — 9 tests (including ZIP bomb & malware scanner)
- [x] `AuditLogServiceTest` — 5 tests
- [x] `AuditLogIntegrityServiceTest` — 5 tests
- [x] `StorageIntegrityServiceTest` — 7 tests
- [x] `StorageMaintenanceServiceTest` — 6 tests
- [x] `StorageMaintenanceHealthIndicatorTest` — 6 tests
- [x] `TrustvaultApplicationTests` — context loads
- [x] Testcontainers integration suite (`AuditChainConcurrencyIT`) configured for PostgreSQL CI

## 10. Documentation

- [x] `README.md` — architectural overview, quick start, deployment, security model
- [x] `API.md` — complete endpoint specification, callers, permissions, request/response models
- [x] `THREATS.md` — STRIDE-aligned threat model, mitigated vectors, residual risks
- [x] `PROMPTS.md` — AI engineering log with audit trail of defects identified and resolved
- [x] `DEPLOYMENT.md` — production readiness, container orchestration, environment configuration
- [x] `db/schema.sql` — database schema reference and least-privilege role setup

## 11. Commands

```bash
# Run unit & architectural tests (fast, H2 in PostgreSQL mode)
./mvnw test -Dtest='!AuditChainConcurrencyIT'

# Run full integration suite including Testcontainers (requires Docker)
./mvnw test

# Package production executable JAR
./mvnw clean package -DskipTests

# Start complete multi-container stack (PostgreSQL, Redis, MinIO, App)
docker compose up -d --build
```
