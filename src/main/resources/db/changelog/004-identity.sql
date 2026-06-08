--liquibase formatted sql

--changeset schoolbridge:004-platform-admins
--comment: Super-admin accounts. Separate table so the tenant-scoped User table never holds a null school_id.
CREATE TABLE platform_admins (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);
--rollback DROP TABLE platform_admins;

--changeset schoolbridge:004-users
--comment: Tenant-scoped principals. Sensitive columns stored AES-GCM encrypted; phone_hash is a blind index for equality lookup.
CREATE TABLE users (
    id              UUID PRIMARY KEY,
    school_id       UUID NOT NULL REFERENCES schools(id),
    role            VARCHAR(20)   NOT NULL,
    name            VARCHAR(1024) NOT NULL,
    email           VARCHAR(255),
    phone           VARCHAR(1024),
    phone_hash      VARCHAR(64),
    password_hash   VARCHAR(255),
    status          VARCHAR(20)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL
);
-- Staff email is platform-unique (login is cross-tenant since the user doesn't know their schoolId at login).
CREATE UNIQUE INDEX uk_users_email             ON users (email)                 WHERE email IS NOT NULL;
-- Parent phone_hash is unique per school so the same parent can appear in multiple schools — though M4 still uses single-school per parent.
CREATE UNIQUE INDEX uk_users_school_phone_hash ON users (school_id, phone_hash) WHERE phone_hash IS NOT NULL;
CREATE INDEX        idx_users_phone_hash       ON users (phone_hash)            WHERE phone_hash IS NOT NULL;
--rollback DROP TABLE users;

--changeset schoolbridge:004-refresh-tokens
--comment: Opaque refresh tokens stored as SHA-256 hashes; rotated on each refresh.
CREATE TABLE refresh_tokens (
    id                       UUID PRIMARY KEY,
    subject_kind             VARCHAR(20)  NOT NULL,
    subject_id               UUID         NOT NULL,
    token_hash               VARCHAR(64)  NOT NULL UNIQUE,
    expires_at               TIMESTAMPTZ  NOT NULL,
    revoked_at               TIMESTAMPTZ,
    replaced_by_token_hash   VARCHAR(64),
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_refresh_tokens_subject ON refresh_tokens (subject_kind, subject_id);
--rollback DROP TABLE refresh_tokens;
