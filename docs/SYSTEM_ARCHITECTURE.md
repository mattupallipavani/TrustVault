# TrustVault System Architecture Documentation

## 1. Architectural Overview

TrustVault is architected as an **Enterprise Zero-Trust Document Management and Cryptographic Audit System**. It enforces strict separation of duties, defense-in-depth security, and provable immutability across multi-tier enterprise deployments.

The system is designed around four foundational architectural principles:

1. **Centralized Decision Enforcement (Single Point of Authorization)**:
   Every inbound read, write, update, share, or download request is funneled through a centralized authorization gateway (`PermissionService`). Controllers contain zero standalone permission logic, preventing authorization bypasses and logic drift.
2. **Cryptographic Blindness (Separation of Duties)**:
   System Administrators possess broad administrative oversight over user accounts, role allocations, sharing restrictions, and audit ledgers, but are **cryptographically and architecturally barred** from accessing, opening, or downloading raw document payloads.
3. **Provable Ledger Immutability (Chained Merkle-Style Ledger)**:
   All security-critical actions are committed into an append-only audit trail anchored by an immutable SHA-256 hash chain. Any retrofitted tampering or deletion breaks the hash sequence and is instantly detected by automated verification algorithms.
4. **Decoupled Binary Storage Layer**:
   Document metadata, access matrices, and file binaries are decoupled. The database stores metadata and SHA-256 content hashes, while raw binaries are stored in an abstract storage provider (`StorageService`) using randomized UUID v4 storage keys, eliminating directory traversal vulnerabilities.

---

## 2. High-Level Component Architecture

```mermaid
graph TD
    subgraph Client_Layer ["Client Tier (Browser SPA)"]
        UI["Modern Enterprise Vanilla JS SPA\n(HTML5, CSS3, Inter Typography)"]
        CSRF["Cookie-based CSRF Handler\n(XSRF-TOKEN / X-XSRF-TOKEN)"]
        AuthView["Authentication Shell\n(Login / Register / Password Reset)"]
        DashView["Role Dashboards\n(Admin / Owner / Viewer)"]
    end

    subgraph Security_Perimeter ["Edge & Security Gateway"]
        HTTPS["TLS 1.3 Termination"]
        RateLimit["RateLimitingFilter\n(Redis Token Bucket / Local Fallback)"]
        SecHeaders["Security Headers\n(CSP, HSTS, X-Frame-Options: DENY)"]
        AuthFilter["Spring Security Filter Chain\n(Form Login, BCrypt, Session Fixation)"]
    end

    subgraph Application_Layer ["Spring Boot Core Application Service"]
        Controllers["REST API Controllers\n(@RestController, @PreAuthorize)"]
        AuthGateway["PermissionService\n(Centralized Authorization Authority)"]
        
        subgraph Domain_Services ["Business Logic Services"]
            DocUpload["DocumentUploadService"]
            DocDownload["DocumentDownloadService"]
            DocPerm["PermissionService"]
            DocRestrict["SharingRestrictionService"]
            AuditService["AuditLogService"]
            IntegrityService["AuditLogIntegrityService"]
            StorageIntegrity["StorageIntegrityService"]
            ValidationService["FileValidationService\n(Magic Bytes & ZIP-Bomb Defense)"]
        end
    end

    subgraph Persistence_Layer ["Data Tier"]
        JPA["Spring Data JPA / Hibernate 7"]
        Flyway["Flyway Migration Engine\n(V1 - V4 Baseline Migrations)"]
        NeonDB[("PostgreSQL 16 / Neon Serverless\n(Users, Documents, Permissions, Ledger)")]
        AnchorTable[("audit_chain_anchor\n(Row-Locked Head Hash)")]
    end

    subgraph Storage_Tier ["Pluggable Storage Layer (StorageService)"]
        LocalStorage["LocalStorageService\n(Local Filesystem Container /data)"]
        SupabaseStorage["SupabaseStorageService\n(Supabase Storage REST API / TLS)"]
        MinioStorage["MinioStorageService\n(S3 / MinIO Object Store)"]
    end

    %% Wiring
    UI --> HTTPS
    HTTPS --> SecHeaders
    SecHeaders --> RateLimit
    RateLimit --> AuthFilter
    AuthFilter --> Controllers
    Controllers --> AuthGateway
    AuthGateway --> Domain_Services

    Domain_Services --> JPA
    Domain_Services --> StorageIntegrity
    JPA --> Flyway
    Flyway --> NeonDB
    NeonDB --- AnchorTable

    DocUpload --> ValidationService
    DocUpload --> Storage_Tier
    DocDownload --> Storage_Tier
    StorageIntegrity --> Storage_Tier
```

---

## 3. Data Flow Diagrams (DFD)

### 3.1 Level 0 Context Diagram

```mermaid
flowchart TD
    User([Enterprise User\nOwner / Viewer / Admin])
    
    subgraph TrustVault_System ["TrustVault System Boundary"]
        System((TrustVault Application))
    end
    
    Database[(PostgreSQL Database)]
    StorageBucket[(Binary Object Store)]
    RedisCache[(Redis Cache / Rate Limiter)]

    User -->|1. Credentials / Session Request| System
    System -->|2. Authenticated Session / UI Assets| User
    User -->|3. File Upload / Metadata| System
    System -->|4. File Hash & Storage Key| StorageBucket
    System -->|5. Metadata, Permissions & Audit Hash| Database
    System -->|6. Rate Limit Counter Increment| RedisCache
    User -->|7. Document Download Request| System
    System -->|8. Verify Permission & Integrity| Database
    System -->|9. Fetch Binary Stream| StorageBucket
    System -->|10. Stream Verified Document| User
```

### 3.2 Level 1 Detailed Data Flow Diagram

```mermaid
flowchart LR
    subgraph External_Entities ["Entities"]
        U_Owner([Document Owner])
        U_Viewer([Document Viewer])
        U_Admin([Administrator])
    end

    subgraph Processes ["Application Processes"]
        P_Auth["1.0 Authenticate &\nVerify Session"]
        P_Validate["2.0 Validate Binary\n(Magic Bytes / Size)"]
        P_Upload["3.0 Persist Document &\nRegister Content Hash"]
        P_Share["4.0 Evaluate Sharing &\nRestriction Status"]
        P_Authorize["5.0 Check Read/Edit\nPermissions"]
        P_Audit["6.0 Commit Chained\nAudit Event"]
        P_Verify["7.0 Cryptographic\nLedger Verification"]
    end

    subgraph Stores ["Data Stores"]
        DS_Users[("D1: Users")]
        DS_Docs[("D2: Documents")]
        DS_Perms[("D3: Permissions")]
        DS_Restrict[("D4: Sharing Restrictions")]
        DS_Audit[("D5: Audit Ledger & Anchor")]
        DS_Binary[("D6: Binary Store")]
    end

    U_Owner -->|Submit Credentials| P_Auth
    P_Auth <-->|Query Hash / Roles| DS_Users
    
    U_Owner -->|Upload File Stream| P_Validate
    P_Validate -->|Valid Stream| P_Upload
    P_Upload -->|Write Encrypted Payload| DS_Binary
    P_Upload -->|Insert Document Record| DS_Docs
    P_Upload -->|Trigger Event| P_Audit

    U_Owner -->|Grant Access| P_Share
    P_Share <-->|Check Restriction| DS_Restrict
    P_Share -->|Store Grant| DS_Perms
    P_Share -->|Log Grant| P_Audit

    U_Admin -->|Restrict Sharing| DS_Restrict
    U_Admin -->|Log Action| P_Audit
    U_Admin -->|Request Ledger Verification| P_Verify
    P_Verify <-->|Sequential Hash Recalculation| DS_Audit

    U_Viewer -->|Request File| P_Authorize
    P_Authorize <-->|Evaluate Grant| DS_Perms
    P_Authorize -->|Stream Content| DS_Binary
    P_Authorize -->|Log Access / Refusal| P_Audit

    P_Audit -->|Row Lock & Append| DS_Audit
```

---

## 4. Use Case Analysis by Actor Role

```mermaid
graph LR
    subgraph Actors ["User Roles"]
        Owner((Document Owner))
        Viewer((Viewer))
        Admin((Administrator))
    end

    subgraph UseCases ["System Use Cases"]
        UC_Auth[Sign In / Sign Out / Password Reset]
        UC_Upload[Upload Document & Category]
        UC_ViewDocs[Search & List Documents]
        UC_Download[Download Authorized File]
        UC_Share[Grant Permission to User]
        UC_Revoke[Revoke Document Access]
        UC_Rename[Rename / Edit Metadata]
        UC_Delete[Delete Document]
        UC_Restrict[Apply Sharing Restriction]
        UC_LiftRestrict[Lift Sharing Restriction]
        UC_RemovePerson[Admin Remove Person from Doc]
        UC_ViewAudit[Inspect Audit Ledger]
        UC_VerifyChain[Verify Cryptographic Hash Chain]
        UC_ManageUsers[List Users & Modify Roles]
        UC_ResetPass[Issue Temporary Password]
        UC_StorageCheck[Run Storage Integrity Sweep]
    end

    %% Owner Connections
    Owner --> UC_Auth
    Owner --> UC_Upload
    Owner --> UC_ViewDocs
    Owner --> UC_Download
    Owner --> UC_Share
    Owner --> UC_Revoke
    Owner --> UC_Rename
    Owner --> UC_Delete

    %% Viewer Connections
    Viewer --> UC_Auth
    Viewer --> UC_ViewDocs
    Viewer --> UC_Download

    %% Admin Connections
    Admin --> UC_Auth
    Admin --> UC_ViewDocs
    Admin --> UC_Restrict
    Admin --> UC_LiftRestrict
    Admin --> UC_RemovePerson
    Admin --> UC_ViewAudit
    Admin --> UC_VerifyChain
    Admin --> UC_ManageUsers
    Admin --> UC_ResetPass
    Admin --> UC_StorageCheck
```

---

## 5. Cryptographic Ledger & Audit Chain Architecture

TrustVault implements a tamper-evident audit ledger using sequential SHA-256 hash chaining anchored by a database row lock on `audit_chain_anchor`.

### 5.1 Ledger Structure

Each log entry $E_i$ incorporates:
1. Prior Hash: $H_{i-1}$ (retrieved from entry $E_{i-1}$, or `"GENESIS"` if $i=1$).
2. Event Attributes: Actor ID, Actor Email, Action, Outcome, Document ID, Document Name, Target User, Details, Timestamp ($T_i$).
3. Content Hash: Computed as:
   $$\text{EntryHash}_i = \text{SHA-256}(H_{i-1} \parallel \text{Actor} \parallel \text{Action} \parallel \text{Outcome} \parallel \text{DocID} \parallel T_i \parallel \text{Details})$$

```mermaid
sequenceDiagram
    autonumber
    participant Svc as AuditLogService
    participant Anchor as audit_chain_anchor Table
    participant Ledger as audit_logs Table

    Note over Svc,Anchor: Transaction Initiated (REQUIRES_NEW)
    Svc->>Anchor: SELECT ... FOR UPDATE (Row Lock on anchor_id = 1)
    Anchor-->>Svc: Current head_hash (H_{i-1})
    Svc->>Svc: Compute SHA-256(H_{i-1} + Event Data) -> H_i
    Svc->>Ledger: INSERT INTO audit_logs (previous_hash=H_{i-1}, entry_hash=H_i, ...)
    Svc->>Anchor: UPDATE audit_chain_anchor SET head_hash = H_i, last_audit_id = i
    Note over Svc,Anchor: Transaction Committed & Lock Released
```

### 5.2 Chain Verification Algorithm

When an Administrator clicks **Verify Chain**:
1. `AuditLogIntegrityService` loads all audit records ordered by ID ascending.
2. It initializes an expected hash variable to `"GENESIS"`.
3. For every record:
   - Verifies that `record.previousHash == expectedHash`.
   - Recomputes the SHA-256 digest of the record's payload combined with `record.previousHash`.
   - Compares the recomputed hash with `record.entryHash`.
   - Sets `expectedHash = record.entryHash`.
4. Finally, verifies that the final `expectedHash` matches `head_hash` in `audit_chain_anchor`.
5. If any record has been modified, deleted, or inserted out of band, verification halts and reports the exact broken record ID.

---

## 6. Storage Subsystem Abstraction

To ensure portability across on-premise servers, cloud VMs, and cloud-native object stores, physical file handling is isolated behind the `StorageService` interface.

```mermaid
classDiagram
    class StorageService {
        <<interface>>
        +store(storageKey, inputStream, size, contentType) void
        +store(storageKey, inputStream, size, contentType, sha256) void
        +retrieve(storageKey) InputStream
        +delete(storageKey) void
        +exists(storageKey) boolean
        +sizeOf(storageKey) long
        +hashOf(storageKey) String
        +storedHash(storageKey) String
    }

    class DocumentStorageService {
        -String rootLocation
        +store(...)
        +retrieve(...)
        +delete(...)
        +exists(...)
        +sizeOf(...)
        +hashOf(...)
    }

    class SupabaseStorageService {
        -String baseUrl
        -String serviceKey
        -String bucketName
        -HttpClient httpClient
        +store(...)
        +retrieve(...)
        +delete(...)
        +exists(...)
        +sizeOf(...)
        +hashOf(...)
    }

    class MinioStorageService {
        -MinioClient minioClient
        -String bucketName
        +store(...)
        +retrieve(...)
        +delete(...)
        +exists(...)
        +sizeOf(...)
        +hashOf(...)
    }

    StorageService <|.. DocumentStorageService : Local Filesystem
    StorageService <|.. SupabaseStorageService : Supabase Storage REST API
    StorageService <|.. MinioStorageService : S3 / MinIO SDK
```

- **UUID Storage Keys**: Storage keys are generated as random UUIDs (e.g., `4fa9c90b-689b-4395-827c-3f9b2d88a102`). The user-provided filename is never used to construct paths on disk or in the bucket, entirely preventing path traversal attacks (`../../etc/passwd`).
- **Integrity Validation on Read**: On download, the stored file's byte stream is verified against the cryptographic `content_hash` recorded at upload time before returning the payload to the user.
