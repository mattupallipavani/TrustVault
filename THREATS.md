# THREATS.md — TrustVault Security & Threat Model

Comprehensive adversarial analysis of TrustVault's architecture, threat mitigations, and accepted residual risks.

---

## 1. Core Security Invariants

1. **Authorization Choke Point:** `PermissionService` owns all access decisions (`requireRead`, `requireEdit`, `requireOwner`). Document binary streams cannot be retrieved by ID alone without passing permission checks.
2. **Immediate Revocation:** Zero caching of authorization decisions. A revoked grant takes effect on the very next HTTP request.
3. **Non-Repudiation of Denied Attempts:** Unauthorized access attempts trigger `AuditLogService.denied()` executed in an independent `REQUIRES_NEW` transaction, ensuring audit evidence is permanently recorded even when the outer request transaction rolls back.
4. **Append-Only Tamper-Evident Audit:** Every audit entry computes a SHA-256 hash across canonicalized fields delimited by `\u001F` (ASCII Unit Separator) chained to the prior entry's hash.
5. **Separation of Duties:** Administrators read the audit trail and manage users but cannot read document contents.

---

## 2. Threat Analysis & Mitigations

### 2.1 Horizontal Privilege Escalation (IDOR)
* **Attack:** Guess or enumerate document IDs on `/api/documents/{id}/download` or metadata endpoints.
* **Mitigation:** Every document retrieval invokes `PermissionService.requireRead`. Authenticated user context is resolved strictly from the server-managed session (`CurrentUserService`), never from client input.
* **Mutation Guard:** If `requireRead` is removed from `DocumentDownloadService`, test `otherUserCannotDownloadDocument` in `AuthorizationIntegrationTest` fails immediately.

### 2.2 Vertical Privilege Escalation
* **Attack:** Self-registering as `ADMIN` or invoking `/api/admin/**` endpoints as an `OWNER` or `VIEWER`.
* **Mitigation:** `RegisterRequest` accepts no role parameter; `UserService` hardcodes `Role.OWNER` on public registration. The `/api/admin/**` route is gated twice: at the filter edge via `SecurityConfig` and method-level via `@PreAuthorize("hasRole('ADMIN')")`.

### 2.3 Brute-Force & Credential Stuffing Attacks (Previously Accepted Risk §3.3)
* **Attack:** Automated password spraying or dictionary attacks against `/api/auth/login`.
* **Mitigation:** Closed using Redis-backed sliding-window rate limiting (`RateLimitingFilter`). IP addresses exceeding 15 login requests per minute or 5 password reset requests per minute are throttled with HTTP 429 and audited as `ACCESS_REFUSED`.

### 2.4 Multi-Instance Audit Chain Race Condition (Previously Accepted Risk §2.15)
* **Attack:** Two concurrent appends in a multi-pod cluster reading the same head hash simultaneously, branching the audit chain.
* **Mitigation:** Closed via dedicated table `audit_chain_anchor` (`anchor_id = 1`). Every audit append executes `SELECT ... FOR UPDATE` on this anchor row inside its database transaction. This serializes chain appends across all application pods in the cluster.

### 2.5 Empty-Table Genesis Race Condition (Previously Accepted Risk §2.15)
* **Attack:** Concurrent appends on a completely empty audit table racing for genesis hash initialization.
* **Mitigation:** Closed by seeding the anchor row with `head_hash = 'GENESIS'` in Flyway migration `V1__initial_schema.sql`. The anchor row always exists, eliminating empty-table lock contention.

### 2.6 Heap Exhaustion via Large File Buffering
* **Attack:** Submitting concurrent downloads of maximum-size files (20MB) to exhaust JVM heap memory.
* **Mitigation:** Replaced all `Files.readAllBytes()` with streaming `DigestInputStream` with 8 KB chunk buffers. Memory overhead per download is reduced from $O(N)$ to $O(1)$.

### 2.7 Decompression / ZIP Bombs in DOCX Files (Previously Accepted Risk §3.4)
* **Attack:** Uploading a malicious `.docx` archive with a massive compression ratio (e.g., 10MB expanding to 100GB).
* **Mitigation:** `FileValidationService` inspects the archive structure using `ZipInputStream`, enforcing strict bounds: maximum 1,000 entries, maximum 50 MB total uncompressed bytes, and maximum 100:1 compression ratio. Traversal entries (`..`) are rejected.

### 2.8 Cross-Site Request Forgery (CSRF)
* **Attack:** Forging state-mutating requests from a third-party site using ambient session cookies.
* **Mitigation:** Cookie-to-header token verification (`X-XSRF-TOKEN` matched against `CookieCsrfTokenRepository`). Session cookies are configured with `SameSite=Lax` and `HttpOnly`.

### 2.9 Administrative Temporary Password Impersonation
* **Attack:** An administrator issues a temporary password for an account and retains access indefinitely, or the temporary password leaks during out-of-band communication.
* **Mitigation:** Closed via `must_change_password` flag (`V3__session_and_password_policy.sql`). Issuing a temporary password sets `mustChangePassword = true`. The user is prompted to set their own secret via `POST /api/users/me/change-password`, which verifies the temporary password, re-hashes the new secret, clears the flag, and emits an audit event.

### 2.10 Concurrent Session Hijacking & Stale Sessions
* **Attack:** An attacker captures an active session while the legitimate user is also logged in, maintaining simultaneous access.
* **Mitigation:** Closed via `SessionRegistry` and `maximumSessions(1)` configured in `SecurityConfig`. New logins supersede prior sessions (`migrateSession`), preventing multiple concurrent active sessions under a single user identity.

---

## 3. Residual Accepted Risks

1. **Privileged Database Operator Manipulation (§3.2):**
   * *Risk:* A database superuser with direct SQL console access can rewrite table contents and recompute the SHA-256 chain.
   * *Mitigation:* The application database user (`trustvault_app`) is granted `SELECT, INSERT` on `audit_logs` and cannot update or delete rows.
   * *Residual:* Defending against a compromised database root requires external HMAC anchoring to an immutable external ledger or write-once cloud vault (AWS S3 Object Lock / GCP Cloud KMS).
2. **Malware Payload Detection (§3.4):**
   * *Risk:* Validly formatted files (e.g., a genuine PDF containing an exploit) pass magic byte and extension checks.
   * *Mitigation:* `MalwareScanner` interface provides a clean plug-in point for enterprise antivirus daemons (ClamAV via TCP `INSTREAM`).
