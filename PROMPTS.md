# PROMPTS.md

Running log of what was asked of the AI, what came back, what was wrong with
it, and what was changed instead. Dates are the days the work happened.

> **Note on commit links.** This repository had no Git history when these
> entries were written (`git log` failed: not a repository). Links below point
> at the file and symbol that changed. Once the history exists, replace each
> `Pending` with the commit URL - the entries are ordered, so the commits are
> easy to identify.

---

## 2026-11-04 — 1. "Harden the security config"

**Prompt.** "Rewrite the Spring Security configuration for a document sharing
app. Session-based auth, static SPA, JSON APIs. Add CSRF, security headers and
proper role rules."

**Output.** A `SecurityConfig` with `csrf(AbstractHttpConfigurer::disable)`,
`formLogin(Customizer.withDefaults())`, `.anyRequest().authenticated()`, and a
comment saying CSRF was disabled because the frontend had trouble with tokens.

**What was wrong.** Three separate problems, and the first one is the reason
this entry exists:

1. **CSRF disabled globally.** The code comment presented this as a
   convenience. For a cookie-authenticated document system it means any page on
   the internet can make an authenticated browser POST to `/api/documents/{id}`
   and delete a file. This is exactly the class of mistake the brief warns
   about: a confident answer that quietly removes a control.
2. **Default form login.** Unauthenticated API calls got a 302 to an HTML login
   page. `fetch()` follows the redirect, sees a 200 with an HTML body, and the
   UI reports success for a request that never ran - while authorization
   failures were indistinguishable from authentication failures.
3. **No role boundary.** `anyRequest().authenticated()` meant a VIEWER could
   call `/api/admin/audit`.

**Change.** CSRF re-enabled with `CookieCsrfTokenRepository` + `X-XSRF-TOKEN`,
token bootstrapped from `GET /api/auth/csrf`; JSON 401 entry point and JSON 403
handler; `/api/admin/**` gated by role at the edge and again with
`@PreAuthorize`. See `security/SecurityConfig.java`.

**Checked by.** `stateChangingRequestWithoutCsrfTokenIsRejected`,
`anonymousRequestIsRejectedWith401`, `auditTrailIsAdminOnly`
(`AuthorizationIntegrationTest`).

---

## 2026-11-04 — 2. "Record every denied attempt"

**Prompt.** "The audit service should record who tried to access a document and
whether it was allowed. Call it from the permission check."

**Output.** `permissionService.canRead(...)` was called; on refusal the code
called `auditLogService.recordActivity(..., "DENIED", ...)` and then threw
`AccessDeniedException`, all inside a `@Transactional` service method.

**What was wrong.** The denial was written to the same transaction that the
exception rolled back. Spring's rollback took the audit row with it. The system
recorded every success and lost every refusal - the precise inverse of what an
auditor asks for, and invisible in manual testing because the API correctly
returned 403. It only shows up if you query the audit table afterwards, which
the test now does.

**Change.** Split the writer: `success()` joins the caller's transaction (no
"success" row without the change), `denied()` and `standalone()` use
`REQUIRES_NEW` so they commit independently of the rollback.

**Checked by.** `refusedAttemptsAppearInTheAuditTrail` asserts a `DENIED` entry
naming the attacker exists after a real 403, and
`AuditLogServiceTest.deniedAttemptIsRecordedAsDenied`.

---

## 2026-11-04 — 3. "Make the audit trail tamper-evident"

**Prompt.** "Add a SHA-256 hash chain to the audit log: each row hashes its
contents plus the previous row's hash, and add an endpoint that verifies it."

**Output.** Hash computed over
`actorUserId + "|" + documentId + "|" + ... + createdAt`; verification
recomputed the same string and compared.

**What was wrong.** Two defects that made the feature cosmetic:

1. **Precision mismatch.** The hash covered `LocalDateTime.now()` at nanosecond
   precision. The column is `datetime(6)`, so the value read back has
   microseconds and every recomputed hash differed. After a restart, verification
   would report the entire chain as tampered - a control that appears to work,
   fails on everything, and trains the operator to ignore it.
2. **Ambiguous serialisation.** Fields joined with `|`, and filenames are
   user-controlled. A document named `a|b` moves a field boundary, so two
   different records can produce the same canonical string.

**Change.** Timestamp truncated to microseconds before hashing; fields joined
with U+001F and a null marker so absent values cannot collide with the literal
string `"null"`; writer and verifier share one `AuditHasher` class instead of
duplicating the string. Verification now also distinguishes "contents changed"
from "link broken" and reports the first bad row.

**Checked by.** `createdAtIsTruncatedToMicroseconds`,
`auditChainVerifiesAfterRealTraffic` (real database round trip), and the three
tamper tests in `AuditLogIntegrityServiceTest`.

---

## 2026-11-04 — 4. "Let an administrator see everything"

**Prompt.** "Add an admin view of all documents and all activity, so an
administrator can investigate a leak."

**Output.** An admin controller returning the full document list plus content
download, implemented as "if role is ADMIN, skip the permission check".

**What was wrong.** It conflated two different jobs. Making the admin a
super-reader means one compromised admin session is a full confidentiality
breach of every file, and it silently creates a permanent backdoor into a
system whose headline requirement is "the wrong person can never read the
file". It also breaks the product claim: the answer to "who can read this
document?" becomes "everyone with an admin badge".

**Change.** Inverted the decision. The administrator sees the audit trail,
the permission history and the user list, and **cannot** retrieve document
content. Separation of duties: the auditor learns that something happened, not
what the file said. Recorded as an accepted limitation in THREATS.md §3.6 - the
admin also cannot recover a file for a user, deliberately.

**Checked by.** `administratorCannotReadDocumentContent` asserts a 403 for an
ADMIN downloading a document. If someone "fixes" the admin view by granting it
content access, that test fails.

---

## 2026-11-04 — 5. "Search should filter by owner and date"

**Prompt.** "Add search by filename, category, owner and upload date. Reuse the
permission logic."

**Output.** `documentRepository.findAll()` followed by `.stream().filter(doc ->
permissionService.canRead(doc.getId(), userId))` and further filters in Java.

**What was wrong.** It works, and it fails open on the next refactor: the
authorization is a stream stage that anybody can drop, reorder or forget in a
new method, and the query already loaded every document in the system into
memory. It is also an N+1 - one permission lookup per document - which turns
into a denial of service as the table grows.

**Change.** Moved the access predicate into the JPQL:
`d.ownerId = :userId or exists (select 1 from DocumentPermission ...)`, with the
search filters as optional query parameters. A row the caller was not granted
cannot be returned by the query at all. Access level for the UI comes from one
permission lookup for the whole page instead of one per row.

**Checked by.** `otherUserCannotSearchTheirWayToIt` and
`otherUserCannotSeeTheDocumentInTheirListing`.

---

## 2026-11-04 — 6. "Upload validation"

**Prompt.** "Validate uploads: size, extension and magic bytes."

**Output.** A validator that checked size and extension, then trusted
`file.getContentType()` for the type it stored.

**What was wrong.** The client controls `Content-Type`. A file named `x.png`
declared as `text/html` was stored with `text/html`, and a download served from
the application origin would render it - stored XSS with the application's
cookies in scope. The signature check itself was fine; the stored type was the
hole.

**Change.** Content type is derived from the validated extension and never read
from the request. Downloads are forced to `attachment` with `nosniff`. Filenames
are sanitized and only used as display metadata - storage keys are server-side
UUIDs validated against a pattern before any path is built.

**Checked by.** `derivesContentTypeFromTheExtensionNotTheClient`,
`rejectsPathTraversalFileName`, `rejectsContentThatDoesNotMatchItsExtension`.

---

## Reusable setup used throughout

Kept here because it is more useful than any single prompt.

**Context given to the tool every session:** the package layout, the rule that
authorization lives in `PermissionService` and nothing else may decide access,
and the instruction to state when a control is *not* implemented rather than
imply it is. That last line produced the useful answers - and the ones that
claimed "CSRF handled" without a token were wrong every time, which is why every
claim in this project is now backed by a test that fails if the control is
removed.

**Review habit that caught the most bugs:** after any generated security change,
ask "where is the test that fails if this line is deleted?" If the answer is
none, the change is not done. Every entry above ends with that answer.


---

## 2026-11-08 — 7. "Modernize TrustVault into an Enterprise Modular Monolith"

**Prompt.** "Modernize TrustVault into a secure, scalable, and maintainable enterprise application. Standardize on Java 21 LTS and Spring Boot 3.4.x GA, migrate database to PostgreSQL with Flyway, introduce MinIO S3 object storage abstraction, Redis rate limiting, Actuator monitoring, and Docker Compose."

**Output.** Modernized dependencies, Flyway schema migration scripts, MinIO and Local storage implementations, Redis token-bucket rate limiter filter, and pagination endpoints.

**Specific Errors Identified & Resolved:**
1. **Spring Boot 4.x Starter Mismatch:** Initial experimental parent referenced `spring-boot-starter-webmvc`, causing POM resolution failure on official 3.4.x GA. Corrected to `spring-boot-starter-web` and `spring-boot-starter-test`.
2. **Spring Data Multi-Store Conflict:** Adding `spring-boot-starter-data-redis` caused Spring Data to attempt to bind JPA repositories to Redis key-value stores. Resolved by explicitly setting `spring.data.redis.repositories.enabled=false`.
3. **Flyway Cross-Dialect SQL Syntax:** Initial `V1__initial_schema.sql` used `anchor_id INT PRIMARY KEY DEFAULT 1` and `ON CONFLICT DO NOTHING`. H2 PostgreSQL mode rejected the misplaced `DEFAULT` and `ON CONFLICT` clause on initial table creation. Corrected to standard SQL `anchor_id INT DEFAULT 1 PRIMARY KEY` and standard insert.
4. **Direct Filesystem Coupling:** `DocumentDownloadService` directly referenced `java.nio.file.Path` and called `Files.readAllBytes()`. Replaced with `StorageService` port and zero-heap streaming `DigestInputStream` with 8 KB chunk buffers.
5. **Brute Force Weakness:** Introduced `RedisRateLimiter` and `RateLimitingFilter` intercepting authentication paths with 429 status and audit logging.

**Checked by.** `./mvnw test` — 49 tests passing, Flyway schema validation, Actuator health checks, and Swagger UI integration.

---

## 2026-11-08 — 8. "Close credential lifecycle, session concurrency, and architectural boundaries"

**Prompt.** "Complete outstanding architectural and security gaps: force mandatory password changes when an administrator issues a temporary credential, enforce single active sessions per account, encode architectural layer boundaries as executable unit tests, and verify concurrent audit log integrity against real PostgreSQL."

**Output.** Added `must_change_password` flag to `users` schema, `PasswordController` endpoint, ArchUnit 1.4.0 test suite, and Testcontainers PostgreSQL integration test.

**Specific Errors Identified & Resolved:**
1. **Permanent Temporary Credentials:** An administrator issuing a temporary password created a permanent credential known to both the user and the administrator. Resolved via migration `V3__session_and_password_policy.sql` setting `must_change_password = true`, requiring the user to invoke `POST /api/users/me/change-password` before restrictions are lifted.
2. **ArchUnit Inner-Class & Shadowing Issues:** The initial ArchUnit rule for service naming flagged nested records (e.g., `DocumentDownloadService$DownloadPayload`, `StorageIntegrityService$Report`). Added `.areTopLevelClasses()` constraint. Also renamed the test method `testLayeredArchitecture()` to avoid shadowing the statically imported `layeredArchitecture()` method.
3. **Audit Hash Field Reference:** The Testcontainers test referenced `AuditLog.getHash()` instead of the domain getter `AuditLog.getEntryHash()`.
4. **Session Fixation & Multiple Concurrency:** Configured `SessionRegistryImpl` and `maximumSessions(1)` in `SecurityConfig` to invalidate previous sessions on new login.

**Checked by.** `userChangesPasswordAndClearsMustChangePasswordFlag` (`AuthorizationIntegrationTest`), `ArchitectureTest` (9 rules), and `./mvnw test` (84 tests passing).
