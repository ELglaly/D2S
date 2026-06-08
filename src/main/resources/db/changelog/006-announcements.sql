--liquibase formatted sql

--changeset schoolbridge:006-announcements
--comment: Announcements composed by SCHOOL_ADMIN or TEACHER; body is AES-GCM encrypted at rest.
CREATE TABLE announcements (
    id              UUID          PRIMARY KEY,
    school_id       UUID          NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    sender_id       UUID          NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    scope_type      VARCHAR(20)   NOT NULL,
    scope_value     VARCHAR(100),
    language        VARCHAR(8)    NOT NULL,
    body            VARCHAR(8192) NOT NULL,
    attachment_key  VARCHAR(512),
    requires_ack    BOOLEAN       NOT NULL DEFAULT FALSE,
    scheduled_for   TIMESTAMPTZ,
    status          VARCHAR(20)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_announcements_school        ON announcements (school_id);
CREATE INDEX idx_announcements_school_status ON announcements (school_id, status);
CREATE INDEX idx_announcements_sender        ON announcements (sender_id);
--rollback DROP TABLE announcements;

--changeset schoolbridge:006-announcement-recipients
--comment: Per-(parent, student) materialized recipient rows for an announcement; supports per-child acknowledgement.
CREATE TABLE announcement_recipients (
    id                UUID        PRIMARY KEY,
    school_id         UUID        NOT NULL REFERENCES schools(id)       ON DELETE CASCADE,
    announcement_id   UUID        NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
    parent_user_id    UUID        NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    student_id        UUID        NOT NULL REFERENCES students(id)      ON DELETE CASCADE,
    delivery_status   VARCHAR(20) NOT NULL,
    acknowledged_at   TIMESTAMPTZ,
    message_id        VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uk_announcement_recipient
    ON announcement_recipients (announcement_id, parent_user_id, student_id);
CREATE INDEX idx_announcement_recipients_announcement
    ON announcement_recipients (announcement_id);
CREATE INDEX idx_announcement_recipients_parent_inbox
    ON announcement_recipients (school_id, parent_user_id);
CREATE INDEX idx_announcement_recipients_unacked
    ON announcement_recipients (announcement_id)
    WHERE acknowledged_at IS NULL;
--rollback DROP TABLE announcement_recipients;
