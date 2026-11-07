# TrustVault API Specification

Every endpoint below states who is allowed to call it and what authorization checks are performed. "Authenticated" means a valid session cookie (`JSESSIONID`).

---

## Conventions

- **Auth:** Cookie session (`JSESSIONID`). Sign in with `POST /api/auth/login` (`application/x-www-form-urlencoded`, fields `username` and `password`).
- **CSRF:** Every `POST`, `PUT`, `PATCH`, `DELETE` must send the `X-XSRF-TOKEN` header, matched against the `XSRF-TOKEN` cookie issued by `GET /api/auth/csrf`.
- **Rate Limiting:** Monitored via Redis. Login paths throttled to 15 attempts/minute; password reset to 5 attempts/minute. Exceeding thresholds returns `429 Too Many Requests`.
- **Correlation ID:** Every request receives an `X-Correlation-ID` header, correlated across application logs.
- **Errors:** Uniform JSON response: `{ timestamp, status, error, message, path, fieldErrors? }`. Internal database and system errors are never leaked.
- **Status Codes:** `200` OK, `201` Created, `202` Accepted, `400` Bad Request, `401` Unauthorized, `403` Forbidden (or CSRF failure), `404` Not Found, `409` Conflict, `413` Payload Too Large, `429` Too Many Requests, `500` Internal Server Error.

---

## Roles

| Role | Permissions |
| :--- | :--- |
| `ADMIN` | Read audit trail, verify audit chain integrity, view users, change roles, trigger reset passwords. **Cannot read document contents.** |
| `OWNER` | Upload documents, own files, grant/revoke read and edit access, rename metadata, delete owned files. |
| `VIEWER` | Read and download documents explicitly granted to the user. Cannot upload or manage grants. |

---

## Endpoints

### 1. Public & Authentication

| Method | Path | Access | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/health` | Public | Application liveness verification string. |
| `GET` | `/actuator/health` | Public | Detailed health status of PostgreSQL, Redis, Disk, and Storage. |
| `GET` | `/api/auth/csrf` | Public | Generates and sets `XSRF-TOKEN` cookie; returns CSRF token metadata. |
| `POST` | `/api/auth/register` | Public | Registers a new `OWNER` account. Returns `409` if email is already taken. |
| `POST` | `/api/auth/login` | Public | Form login (`username`, `password`). Rate-limited to 15 req/min. Audited. |
| `POST` | `/api/auth/forgot-password` | Public | Body `{ email }`. Rate-limited to 5 req/min. Returns `202 Accepted`. Audited. |
| `GET` | `/swagger-ui/index.html` | Public | Interactive OpenAPI / Swagger UI documentation. |
| `GET` | `/v3/api-docs` | Public | OpenAPI 3.1 JSON schema document. |

### 2. Session & User Management

| Method | Path | Access | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/logout` | Authenticated | Server-side session invalidation and cookie clearing. Requires CSRF. |
| `GET` | `/api/users/me` | Authenticated | Returns current user details: `id`, `fullName`, `email`, `role`, `mustChangePassword`. |
| `POST` | `/api/users/me/change-password` | Authenticated | Body `{ currentPassword, newPassword }`. Self-service password change; clears `mustChangePassword`. Audited. |
| `GET` | `/api/users/lookup?email=` | Authenticated | Exact email lookup. Returns `UserResponse` or `404`. |

### 3. Document Management

All routes invoke `PermissionService` before retrieving metadata or content.

| Method | Path | Access | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/documents` | Authenticated | Returns list of documents caller owns or was granted. |
| `GET` | `/api/documents/paged?page=0&size=20` | Authenticated | Paginated list of documents accessible by caller. |
| `GET` | `/api/documents/search` | Authenticated | Filters accessible documents by `keyword`, `category`, `owner`, `from`, `to`, `scope`. |
| `GET` | `/api/documents/{id}` | Authenticated | `requireRead`. Returns metadata. Refused with `403` without a grant. |
| `GET` | `/api/documents/{id}/download` | Authenticated | `requireRead`. Streams content from storage while verifying SHA-256 hash. |
| `POST` | `/api/documents/upload` | `OWNER` or `ADMIN` | Multipart `file` + `category`. Magic-bytes, size (20MB), and ZIP bomb validated. |
| `PUT` | `/api/documents/{id}` | Owner or `EDIT` grant | `requireEdit`. Updates `fileName` and `category`. File bytes immutable. |
| `DELETE` | `/api/documents/{id}` | Owner only | `requireOwner`. Deletes permissions, document row, then unlinks physical object after commit. |

### 4. Permission Management

| Method | Path | Access | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/permissions/grant/{documentId}` | Owner only | Body `{ targetUserId, permissionType: "READ"\|"EDIT" }`. Upserts grant. |
| `DELETE` | `/api/permissions/revoke/{documentId}/{targetUserId}` | Owner only | Revokes grant immediately on next request. |
| `GET` | `/api/permissions/{documentId}` | Owner only | Lists all current grants for the document. |

### 5. Administration (`ADMIN` Only)

| Method | Path | Description |
| :--- | :--- | :--- |
| `GET` | `/api/admin/audit` | Filter audit logs by `action`, `outcome`, `documentId`, `actorEmail`, `from`, `to`. |
| `GET` | `/api/admin/audit/paged?page=0&size=50` | Paginated audit trail query. |
| `GET` | `/api/admin/audit/verify` | Recomputes SHA-256 chain. Returns `{ valid, checkedEntries, brokenAtId, idGaps, message }`. |
| `GET` | `/api/admin/audit/stats` | Returns aggregate statistics (denied events count, chain status). |
| `GET` | `/api/admin/audit/document/{documentId}` | Complete chronological history for a document. |
| `GET` | `/api/admin/users` | List of all system user accounts. |
| `PATCH` | `/api/admin/users/{id}/role` | Updates user role. Admins cannot change their own role. |
| `POST` | `/api/admin/users/{id}/reset-password` | Generates a single-use 16-character temporary password. |
