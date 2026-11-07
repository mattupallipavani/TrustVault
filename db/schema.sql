-- ---------------------------------------------------------------------------
-- TrustVault schema (MySQL 8)
--
-- Hibernate runs with ddl-auto=update in development, which will create these
-- tables for you. This file exists so the schema is reviewable, so a fresh
-- environment can be built without starting the app, and because migrations
-- belong in version control rather than in an ORM's guesswork.
--
-- Usage:
--   mysql -u root -p < db/schema.sql
--
-- Create the application user separately; do not reuse root:
--   CREATE USER 'trustvault_app'@'localhost' IDENTIFIED BY '<password>';
--   GRANT SELECT, INSERT, UPDATE, DELETE ON trustvault_db.* TO 'trustvault_app'@'localhost';
--
-- Note: no UPDATE/DELETE grant is needed on audit_logs for the app to work,
-- which is one more reason to keep the app user least-privileged. The
-- verification endpoint only reads.
-- ---------------------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS trustvault_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE trustvault_db;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    full_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'OWNER', 'VIEWER'))
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- documents
--
-- owner_email is a denormalised snapshot: listings and audit stay readable
-- after the owning account is removed, and search by owner cannot be used to
-- widen a query. storage_key is an opaque UUID; the user-supplied file name is
-- metadata only and never becomes a path.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS documents (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    file_name    VARCHAR(255) NOT NULL,
    file_type    VARCHAR(150) NOT NULL,
    file_size    BIGINT       NOT NULL,
    owner_id     BIGINT       NOT NULL,
    owner_email  VARCHAR(150) NOT NULL,
    category     VARCHAR(50)  NOT NULL,
    storage_key  VARCHAR(100) NOT NULL,
    content_hash VARCHAR(64)  NULL,
    uploaded_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_documents_storage_key (storage_key),
    KEY idx_documents_owner (owner_id),
    KEY idx_documents_uploaded_at (uploaded_at),
    KEY idx_documents_category (category),
    CONSTRAINT fk_documents_owner FOREIGN KEY (owner_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- document_permissions
--
-- One row per (document, user). The unique key is what makes a duplicate grant
-- an update rather than a second row that revoke would miss.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS document_permissions (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    document_id BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    permission  VARCHAR(20) NOT NULL,
    granted_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_document_user (document_id, user_id),
    KEY idx_permission_user (user_id),
    CONSTRAINT fk_permission_document FOREIGN KEY (document_id)
        REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_permission_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_permission_level CHECK (permission IN ('READ', 'EDIT'))
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- audit_logs
--
-- Append-only by convention and by code: no application path updates or
-- deletes a row, and the application user does not need UPDATE/DELETE on this
-- table. document_name, actor_email and target_email are snapshots so the
-- record survives deletion of the document or the account.
--
-- previous_hash / entry_hash form the SHA-256 chain verified by
-- GET /api/admin/audit/verify. See THREATS.md §3.2 for what the chain does and
-- does not prove.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_logs (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    actor_user_id  BIGINT        NULL,
    actor_email    VARCHAR(150)  NULL,
    document_id    BIGINT        NULL,
    document_name  VARCHAR(255)  NULL,
    target_user_id BIGINT        NULL,
    target_email   VARCHAR(150)  NULL,
    action         VARCHAR(50)   NOT NULL,
    outcome        VARCHAR(20)   NOT NULL,
    details        VARCHAR(1000) NULL,
    ip_address     VARCHAR(64)   NULL,
    user_agent     VARCHAR(255)  NULL,
    created_at     DATETIME(6)   NOT NULL,
    previous_hash  VARCHAR(64)   NOT NULL,
    entry_hash     VARCHAR(64)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_document (document_id),
    KEY idx_audit_actor (actor_user_id),
    KEY idx_audit_created_at (created_at),
    KEY idx_audit_outcome (outcome),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE'))
) ENGINE = InnoDB;

-- Note: there is deliberately no foreign key from audit_logs to documents or
-- users. An audit record must outlive what it refers to; a cascade here would
-- erase the evidence when the evidence matters most.

-- ---------------------------------------------------------------------------
-- Least privilege for the application account
-- ---------------------------------------------------------------------------
-- GRANT SELECT, INSERT, UPDATE, DELETE ON trustvault_db.users                TO 'trustvault_app'@'localhost';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON trustvault_db.documents            TO 'trustvault_app'@'localhost';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON trustvault_db.document_permissions TO 'trustvault_app'@'localhost';
-- GRANT SELECT, INSERT                 ON trustvault_db.audit_logs           TO 'trustvault_app'@'localhost';
