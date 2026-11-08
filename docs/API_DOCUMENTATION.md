# TrustVault REST API Documentation

## 1. API Architecture & Standards

The TrustVault REST API adheres to strict enterprise standards:
- **Transport Security**: HTTPS / TLS 1.3 only.
- **Data Exchange**: JSON (`application/json`) for metadata and control commands; `multipart/form-data` for file uploads; `application/octet-stream` for secure binary downloads.
- **Authentication**: Stateful session cookie (`JSESSIONID`) protected by `HttpOnly`, `SameSite=Lax`, and `Secure` attributes.
- **CSRF Defense**: Double-submit cookie pattern. All mutating HTTP methods (`POST`, `PUT`, `PATCH`, `DELETE`) require the `X-XSRF-TOKEN` header matching the client's `XSRF-TOKEN` cookie.
- **HTTP Status Codes**:
  - `200 OK`: Request succeeded.
  - `400 Bad Request`: Validation failure or malformed payload.
  - `401 Unauthorized`: Authentication missing, expired, or invalid.
  - `403 Forbidden`: Insufficient role or access permissions denied.
  - `404 Not Found`: Resource does not exist.
  - `409 Conflict`: Unique constraint violation (e.g. user already exists).
  - `413 Payload Too Large`: Upload exceeds maximum permissible size (10 MB).
  - `415 Unsupported Media Type`: File type rejected by magic byte inspection.
  - `429 Too Many Requests`: Rate limiter triggered on auth routes.

---

## 2. Authentication & Session Endpoints

### 2.1 Fetch CSRF Token
Prime the client session and retrieve a valid CSRF token.
- **Endpoint**: `GET /api/auth/csrf`
- **Access**: Public / Unauthenticated
- **Response**: `200 OK`
  - Sets cookie: `XSRF-TOKEN=<token>; Path=/; SameSite=Lax`

---

### 2.2 Sign In (Authenticate)
Submit verified credentials to initiate an enterprise session.
- **Endpoint**: `POST /api/auth/login`
- **Access**: Public / Unauthenticated
- **Content-Type**: `application/x-www-form-urlencoded`
- **Request Parameters**:
  - `username` (string, required): Registered user email address.
  - `password` (string, required): Account password.
- **Response**: `200 OK` (Rotates session ID, issues fresh CSRF token).
- **Error Responses**:
  - `401 Unauthorized`: `{"message": "Bad credentials"}`

---

### 2.3 Sign Out (Terminate Session)
Invalidates current session and purges server-side session registry.
- **Endpoint**: `POST /api/auth/logout`
- **Access**: Authenticated
- **Headers**: `X-XSRF-TOKEN: <token>`
- **Response**: `200 OK`

---

### 2.4 User Registration
Register a new enterprise account.
- **Endpoint**: `POST /api/auth/register`
- **Access**: Public / Unauthenticated
- **Headers**: `X-XSRF-TOKEN: <token>`
- **Request Body**:
```json
{
  "fullName": "Priya Sharma",
  "email": "priya@company.com",
  "password": "CorrectHorseBatteryStaple2026!"
}
```
- **Validation**: Full name (non-blank), Email (valid RFC format), Password (min 12 characters).
- **Response**: `200 OK`
```json
{
  "id": 4,
  "fullName": "Priya Sharma",
  "email": "priya@company.com",
  "role": "OWNER",
  "mustChangePassword": false
}
```
- **Error Responses**:
  - `409 Conflict`: `{"message": "An account with that email already exists"}`

---

### 2.5 Request Password Reset
Requests administrative issuance of a temporary one-time password.
- **Endpoint**: `POST /api/auth/forgot-password`
- **Access**: Public
- **Request Body**:
```json
{
  "email": "user@company.com"
}
```
- **Response**: `200 OK` (Uniform response to prevent user enumeration).
```json
{
  "message": "If that address is registered, an administrator will issue a temporary password."
}
```

---

## 3. User & Identity Management Endpoints

### 3.1 Get Current User Profile
- **Endpoint**: `GET /api/users/me`
- **Access**: Authenticated (Any role)
- **Response**: `200 OK`
```json
{
  "id": 2,
  "fullName": "Enterprise Owner",
  "email": "owner@trustvault.local",
  "role": "OWNER",
  "mustChangePassword": false
}
```

---

### 3.2 Exact-Match User Lookup
Used by Owners and Admins to find a recipient user ID for document sharing.
- **Endpoint**: `GET /api/users/lookup?email={email}`
- **Access**: Authenticated
- **Query Parameter**: `email` (string, required)
- **Response**: `200 OK`
```json
{
  "id": 3,
  "fullName": "Security Viewer",
  "email": "viewer@trustvault.local",
  "role": "VIEWER",
  "mustChangePassword": false
}
```
- **Error Responses**:
  - `404 Not Found`: `{"message": "User not found"}`

---

### 3.3 List All Users (Admin Only)
- **Endpoint**: `GET /api/admin/users`
- **Access**: `ADMIN` only
- **Response**: `200 OK`
```json
[
  {
    "id": 1,
    "fullName": "System Administrator",
    "email": "admin@trustvault.local",
    "role": "ADMIN",
    "mustChangePassword": false
  },
  {
    "id": 2,
    "fullName": "Enterprise Owner",
    "email": "owner@trustvault.local",
    "role": "OWNER",
    "mustChangePassword": false
  }
]
```

---

### 3.4 Change User Role (Admin Only)
- **Endpoint**: `PATCH /api/admin/users/{userId}/role`
- **Access**: `ADMIN` only
- **Request Body**:
```json
{
  "role": "OWNER"
}
```
- **Response**: `200 OK`
- **Constraints**: Administrators cannot change their own role.

---

### 3.5 Issue Temporary Password (Admin Only)
Generates a cryptographically strong one-time temporary password.
- **Endpoint**: `POST /api/admin/users/{userId}/reset-password`
- **Access**: `ADMIN` only
- **Headers**: `X-XSRF-TOKEN: <token>`, `Cache-Control: no-store`
- **Response**: `200 OK`
```json
{
  "userId": 3,
  "email": "viewer@trustvault.local",
  "temporaryPassword": "tmp_a8F9#kL2!pQx7"
}
```

---

## 4. Document Operations Endpoints

### 4.1 List Accessible Documents
Returns documents owned by the caller or explicitly shared with them. For `ADMIN`, returns all vaulted documents with `accessLevel: "ADMIN"` (metadata only).
- **Endpoint**: `GET /api/documents`
- **Access**: Authenticated
- **Response**: `200 OK`
```json
[
  {
    "id": 10,
    "fileName": "Annual_Financial_Audit_2026.pdf",
    "category": "Finance",
    "ownerEmail": "owner@trustvault.local",
    "fileSize": 1048576,
    "uploadedAt": "2026-11-08T07:15:30",
    "accessLevel": "OWNER"
  }
]
```

---

### 4.2 Search Documents
- **Endpoint**: `GET /api/documents/search`
- **Access**: Authenticated
- **Query Parameters**:
  - `keyword` (string, optional): Matches filename or category substring.
  - `category` (string, optional): Exact category match.
  - `owner` (string, optional): Owner email substring.
  - `from` (ISO-8601, optional): Uploaded on or after date (`2026-01-01T00:00:00`).
  - `to` (ISO-8601, optional): Uploaded on or before date (`2026-12-31T23:59:59`).
  - `scope` (string, optional): `all`, `owned`, or `shared`.
- **Response**: `200 OK` (Array of DocumentListResponse objects).

---

### 4.3 Upload Document (Owner Only)
Uploads, hashes, scans, and vaults a confidential document.
- **Endpoint**: `POST /api/documents/upload`
- **Access**: `OWNER` only
- **Content-Type**: `multipart/form-data`
- **Form Fields**:
  - `file` (binary file part, required): Document bytes (PDF, DOCX, PNG, JPG, TXT up to 10 MB).
  - `category` (string, required): Security classification label (e.g., `Legal`, `HR`).
- **Response**: `200 OK`
```json
{
  "id": 12,
  "fileName": "Employment_Contract.pdf",
  "category": "HR",
  "ownerEmail": "owner@trustvault.local",
  "fileSize": 254890,
  "uploadedAt": "2026-11-08T08:30:15",
  "accessLevel": "OWNER"
}
```
- **Error Responses**:
  - `403 Forbidden`: User role is `ADMIN` or `VIEWER` (only `OWNER` can upload).
  - `413 Payload Too Large`: File exceeds 10 MB.
  - `415 Unsupported Media Type`: Magic byte inspection failed.

---

### 4.4 Download Document
Streams the binary content of a verified file.
- **Endpoint**: `GET /api/documents/{id}/download`
- **Access**: Document Owner or User with active `READ` or `EDIT` permission grant.
- **Security Rule**: Administrators are explicitly barred from downloading (`403 Forbidden`).
- **Response**: `200 OK`
  - `Content-Type`: `application/pdf`, `image/png`, etc.
  - `Content-Disposition`: `attachment; filename="filename.pdf"`
  - Body: Binary stream.
- **Error Responses**:
  - `403 Forbidden`: `{"message": "Access denied"}`

---

### 4.5 Update Document Metadata
Renames a file or changes its category.
- **Endpoint**: `PUT /api/documents/{id}`
- **Access**: Document Owner or User with `EDIT` grant.
- **Request Body**:
```json
{
  "fileName": "Updated_Contract_v2.pdf",
  "category": "Legal"
}
```
- **Response**: `200 OK`

---

### 4.6 Delete Document (Owner Only)
Permanently deletes the document record, permissions, restrictions, and physical storage binary.
- **Endpoint**: `DELETE /api/documents/{id}`
- **Access**: Document Owner only.
- **Response**: `200 OK`
```json
{
  "message": "Document deleted successfully"
}
```

---

## 5. Permission & Access Control Endpoints

### 5.1 List Document Permissions
Lists all users with explicit read/edit access grants on a document.
- **Endpoint**: `GET /api/permissions/{documentId}`
- **Access**: Document Owner or `ADMIN`.
- **Response**: `200 OK`
```json
[
  {
    "documentId": 10,
    "userId": 3,
    "userEmail": "viewer@trustvault.local",
    "userFullName": "Security Viewer",
    "permissionType": "READ",
    "grantedAt": "2026-11-08T07:20:00"
  }
]
```

---

### 5.2 Grant Document Permission
Grants `READ` or `EDIT` access to another user.
- **Endpoint**: `POST /api/permissions/grant/{documentId}`
- **Access**: Document Owner (if not under sharing restriction) or `ADMIN`.
- **Request Body**:
```json
{
  "targetUserId": 3,
  "permissionType": "READ"
}
```
- **Response**: `200 OK`
```json
{
  "message": "Permission granted successfully"
}
```
- **Error Responses**:
  - `403 Forbidden`: User is under an active Administrative Sharing Restriction.

---

### 5.3 Revoke Document Permission
Revokes a user's access grant. Immediate effect.
- **Endpoint**: `DELETE /api/permissions/revoke/{documentId}/{targetUserId}`
- **Access**: Document Owner or `ADMIN`.
- **Response**: `200 OK`
```json
{
  "message": "Permission revoked successfully"
}
```

---

## 6. Administrative Sharing Restrictions Endpoints

### 6.1 List Sharing Restrictions (Admin Only)
- **Endpoint**: `GET /api/admin/restrictions/{documentId}`
- **Access**: `ADMIN` only
- **Response**: `200 OK`
```json
[
  {
    "documentId": 10,
    "userId": 2,
    "userEmail": "owner@trustvault.local",
    "userFullName": "Enterprise Owner",
    "restrictedByEmail": "admin@trustvault.local",
    "restrictedAt": "2026-11-08T08:00:00"
  }
]
```

---

### 6.2 Apply Sharing Restriction (Admin Only)
Blocks a specific user from sharing or granting permissions on a document.
- **Endpoint**: `POST /api/admin/restrictions/{documentId}/{targetUserId}`
- **Access**: `ADMIN` only
- **Response**: `200 OK`
```json
{
  "message": "Sharing restriction applied successfully"
}
```

---

### 6.3 Remove Sharing Restriction (Admin Only)
Lifts the restriction, restoring the user's ability to share.
- **Endpoint**: `DELETE /api/admin/restrictions/{documentId}/{targetUserId}`
- **Access**: `ADMIN` only
- **Response**: `200 OK`
```json
{
  "message": "Sharing restriction removed successfully"
}
```

---

## 7. Audit Trail & Cryptographic Verification Endpoints

### 7.1 Query Audit Logs (Admin Only)
- **Endpoint**: `GET /api/admin/audit`
- **Access**: `ADMIN` only
- **Query Parameters**:
  - `action` (string, optional): e.g. `LOGIN`, `UPLOAD`, `DOWNLOAD`, `GRANT_PERMISSION`, `SHARING_RESTRICTED`.
  - `outcome` (string, optional): `SUCCESS`, `DENIED`, `FAILURE`.
  - `actorEmail` (string, optional): Filter by actor email.
- **Response**: `200 OK`
```json
[
  {
    "id": 52,
    "createdAt": "2026-11-08T08:35:10",
    "actorUserId": 1,
    "actorEmail": "admin@trustvault.local",
    "action": "SHARING_RESTRICTED",
    "outcome": "SUCCESS",
    "documentId": 10,
    "documentName": "Financial_Report.pdf",
    "targetUserId": 2,
    "targetEmail": "owner@trustvault.local",
    "details": "Administrator restricted owner@trustvault.local from sharing document \"Financial_Report.pdf\"",
    "ipAddress": "127.0.0.1"
  }
]
```

---

### 7.2 Verify Ledger Hash Chain (Admin Only)
Executes end-to-end cryptographic verification of the SHA-256 chain from Genesis to Head.
- **Endpoint**: `GET /api/admin/audit/verify`
- **Access**: `ADMIN` only
- **Response**: `200 OK`
```json
{
  "valid": true,
  "checkedEntries": 52,
  "headHash": "d8c01c4af6c84514e05de72253a98dc6d56037a67e8e6d818af5c8486b7ad940",
  "message": "Cryptographic hash chain verified successfully across 52 entries."
}
```
- **Response on Tampering Detected**:
```json
{
  "valid": false,
  "checkedEntries": 52,
  "brokenAtId": 14,
  "message": "Chain verification FAILED: hash mismatch at entry #14."
}
```

---

## 8. System Diagnostics & Health Endpoints

### 8.1 Basic Health Check
- **Endpoint**: `GET /api/health`
- **Access**: Public
- **Response**: `200 OK`
```json
{
  "status": "UP",
  "timestamp": "2026-11-08T09:00:00Z"
}
```

---

### 8.2 Actuator Detailed Health
- **Endpoint**: `GET /actuator/health`
- **Access**: Public (Detailed component statuses shown to authenticated admins).
- **Response**: `200 OK`
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP"
    }
  }
}
```
