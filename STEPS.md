# TrustVault — Development Status

`[x]` means implemented **and covered by a test**, not merely written.
`[ ]` means outstanding. Anything unfinished is described in `THREATS.md`
rather than quietly omitted.

## 1. Project setup

- [x] Spring Boot 4.1.2 / Java 21 Maven project
- [x] MySQL configuration with the password read from `DB_PASSWORD`
- [x] Health endpoint
- [x] Secrets excluded from Git (`.gitignore`: `.env`, storage, logs)
- [x] Reviewable schema in `db/schema.sql`
- [ ] Initialise Git and commit across the week
- [ ] Deploy and record the live URL

## 2. Roles and authentication

- [x] Three roles: `ADMIN`, `OWNER`, `VIEWER`, with behaviour documented in README and API.md
- [x] Registration cannot choose its own role (hard-coded `OWNER`)
- [x] Session login with JSON responses, no redirect to an HTML login page
- [x] Sign-in, sign-out and failed sign-in are audited
- [x] Session fixation protection, `HttpOnly` + `SameSite` cookies, server-side logout
- [x] Admin-only role changes, audited, and an admin cannot change their own role
- [ ] Rate limiting / lockout on sign-in (`THREATS.md` §3.3)
- [ ] MFA

## 3. Document management

- [x] Upload with size, extension, magic-byte and filename validation
- [x] Content type derived from the validated extension, never from the client
- [x] Storage outside the web root, UUID keys, path traversal impossible
- [x] SHA-256 of the uploaded bytes stored and re-checked at download
- [x] Listing scoped to owned or granted documents in SQL
- [x] Search by name, category, owner, date range, owned/shared
- [x] Rename / recategorise with `EDIT`; content is not mutable
- [x] Owner-only delete that cleans permission rows and removes the file after commit
- [ ] Versioning, soft delete and restore (`THREATS.md` §4)

## 4. Permissions

- [x] Per-document `READ` / `EDIT` grants, one row per (document, user)
- [x] Owner-only grant, revoke and permission listing
- [x] `EDIT` implies `READ`; unknown stored values fail closed
- [x] Denied attempts audited in a separate transaction
- [x] Immediate revocation, proven by an end-to-end test
- [x] IDOR covered on every route (list, search, metadata, download, update, delete, grant, listing)
- [ ] Groups and folder inheritance (`THREATS.md` §4)

## 5. Audit trail

- [x] Upload, download, update, delete, grant, revoke, role change, login, logout, refusals
- [x] Append-only: no update or delete path in the application
- [x] SHA-256 chain with a shared hasher used by writer and verifier
- [x] Timestamp truncated so stored precision cannot break verification
- [x] Chain appends serialised with a row lock
- [x] `GET /api/admin/audit/verify` reports valid / broken, at which row, and why
- [x] Snapshots of actor email and document name so records survive deletion
- [x] Admin filters by action, outcome, actor, document and date
- [ ] External signed checkpoint (`THREATS.md` §3.2)
- [ ] Detect truncation of the newest rows

## 6. Admin

- [x] Audit view restricted to admins, enforced by URL rule and method annotation
- [x] Document history endpoint, including deleted documents
- [x] Denied-event counters for the dashboard
- [x] Admin cannot read document content (separation of duties)

## 7. Error handling

- [x] Global handler with consistent status codes
- [x] No stack traces, SQL or class names in responses
- [x] Single generic message for denials, so the reason cannot be probed
- [x] Validation errors return field-level messages

## 8. Front end

- [x] Login and registration
- [x] CSRF bootstrap and header on every mutation
- [x] Document list with access level, upload, search, download, rename, delete
- [x] Sharing panel with grant, revoke and exact-email lookup
- [x] Admin audit table with filters and chain verification
- [x] All user-supplied text rendered with `textContent` (no `innerHTML`)
- [x] CSP-compatible: no inline scripts or styles
- [ ] Pagination for large audit result sets

## 9. Automated tests — 46, all passing

- [x] `AuthorizationIntegrationTest` — 18 end-to-end tests over real HTTP
- [x] `PermissionServiceTest` — 8
- [x] `FileValidationServiceTest` — 9
- [x] `AuditLogServiceTest` — 5
- [x] `AuditLogIntegrityServiceTest` — 5
- [x] `TrustvaultApplicationTests` — context loads
- [ ] Same suite against MySQL in CI

## 10. Documentation

- [x] `README.md` — setup, roles, security model, deployment
- [x] `API.md` — endpoint, caller, checks performed
- [x] `THREATS.md` — attacks, mitigations, accepted risks, deliberate omissions
- [x] `PROMPTS.md` — AI usage log with defects found and corrected
- [x] `db/schema.sql` — schema plus least-privilege grants
- [x] Dockerfile and docker-compose
- [ ] Demo video

## 11. Commands

```bash
export DB_PASSWORD='your-database-password'

./mvnw test              # all tests, H2, no MySQL needed
./mvnw clean test        # from scratch
./mvnw spring-boot:run   # http://localhost:8080
mysql -u root -p < db/schema.sql
docker compose up --build
```

## 12. Rules

- `[x]` means tested. Do not tick a box because the code exists.
- Update this file when behaviour changes, not when a file is added.
- Never commit secrets, uploads or log files.
- Every security claim needs a test that fails if the control is removed.
