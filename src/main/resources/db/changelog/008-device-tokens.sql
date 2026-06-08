--liquibase formatted sql

--changeset schoolbridge:008-device-tokens
--comment: Per-user push notification device tokens for FCM/APNs; one row per (user, device) pair.
CREATE TABLE device_tokens (
    id          UUID         PRIMARY KEY,
    school_id   UUID         NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform    VARCHAR(10)  NOT NULL,
    fcm_token   VARCHAR(256) NOT NULL,
    device_id   VARCHAR(128) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);
-- Upsert key: registering the same device again updates the token rather than inserting a duplicate.
CREATE UNIQUE INDEX uk_device_tokens_user_device ON device_tokens (user_id, device_id);
-- Fan-out lookup: find all active tokens for a user within a tenant.
CREATE INDEX idx_device_tokens_school_user_active ON device_tokens (school_id, user_id) WHERE active = TRUE;
--rollback DROP TABLE device_tokens;
