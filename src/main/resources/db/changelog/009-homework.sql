--liquibase formatted sql

--changeset schoolbridge:009-homework-items
--comment: Homework items composed by TEACHER or SCHOOL_ADMIN; description is AES-GCM encrypted at rest.
CREATE TABLE homework_items (
    id               UUID          PRIMARY KEY,
    school_id        UUID          NOT NULL REFERENCES schools(id)        ON DELETE CASCADE,
    class_id         UUID          NOT NULL REFERENCES school_classes(id) ON DELETE CASCADE,
    teacher_id       UUID          NOT NULL REFERENCES users(id)          ON DELETE CASCADE,
    subject          VARCHAR(200)  NOT NULL,
    description      VARCHAR(8192) NOT NULL,
    attachment_key   VARCHAR(512),
    due_date         DATE          NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    requires_ack     BOOLEAN       NOT NULL DEFAULT FALSE,
    reminder_sent_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_homework_items_class_due
    ON homework_items (school_id, class_id, due_date);
CREATE INDEX idx_homework_items_status_due
    ON homework_items (school_id, status, due_date);
CREATE INDEX idx_homework_items_teacher_created
    ON homework_items (school_id, teacher_id, created_at);
--rollback DROP TABLE homework_items;

--changeset schoolbridge:009-homework-recipients
--comment: Per-(parent, student) materialized recipient row for a homework item. Created at publish time; mirrors announcement_recipients with quiet-hours deferral support.
CREATE TABLE homework_recipients (
    id               UUID         PRIMARY KEY,
    school_id        UUID         NOT NULL REFERENCES schools(id)       ON DELETE CASCADE,
    homework_id      UUID         NOT NULL REFERENCES homework_items(id) ON DELETE CASCADE,
    parent_user_id   UUID         NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    student_id       UUID         NOT NULL REFERENCES students(id)      ON DELETE CASCADE,
    delivery_status  VARCHAR(20)  NOT NULL,
    message_id       VARCHAR(255),
    last_error       VARCHAR(256),
    deferred_until   TIMESTAMPTZ,
    acknowledged_at  TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX uk_homework_recipient
    ON homework_recipients (homework_id, parent_user_id, student_id);
CREATE INDEX idx_homework_recipients_homework
    ON homework_recipients (homework_id);
CREATE INDEX idx_homework_recipients_parent_feed
    ON homework_recipients (school_id, parent_user_id, created_at DESC);
CREATE INDEX idx_homework_recipients_deferred
    ON homework_recipients (school_id, deferred_until)
    WHERE deferred_until IS NOT NULL AND message_id IS NULL;
CREATE INDEX idx_homework_recipients_message_id
    ON homework_recipients (message_id)
    WHERE message_id IS NOT NULL;
--rollback DROP TABLE homework_recipients;
