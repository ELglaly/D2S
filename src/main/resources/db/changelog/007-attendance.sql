--liquibase formatted sql

--changeset schoolbridge:007-schools-attendance-settings
--comment: Per-school knobs for the attendance alert path — quiet-hours opt-in and roster due-by time. School.timezone (already on 003-tenant.sql) is reused for local-time interpretation.
ALTER TABLE schools
    ADD COLUMN alerts_respect_quiet_hours BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE schools
    ADD COLUMN roster_due_by_local_time TIME NOT NULL DEFAULT '09:00';
--rollback ALTER TABLE schools DROP COLUMN alerts_respect_quiet_hours;
--rollback ALTER TABLE schools DROP COLUMN roster_due_by_local_time;

--changeset schoolbridge:007-attendance-records
--comment: Per-(student, class, date) attendance row. parent_response is AES-GCM encrypted at rest.
CREATE TABLE attendance_records (
    id                   UUID           PRIMARY KEY,
    school_id            UUID           NOT NULL REFERENCES schools(id)        ON DELETE CASCADE,
    student_id           UUID           NOT NULL REFERENCES students(id)       ON DELETE CASCADE,
    class_id             UUID           NOT NULL REFERENCES school_classes(id) ON DELETE CASCADE,
    date                 DATE           NOT NULL,
    status               VARCHAR(20)    NOT NULL,
    marked_by_user_id    UUID           NOT NULL REFERENCES users(id)          ON DELETE CASCADE,
    marked_at            TIMESTAMPTZ    NOT NULL,
    parent_response      VARCHAR(1024),
    parent_responded_at  TIMESTAMPTZ,
    alert_sent_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ    NOT NULL,
    updated_at           TIMESTAMPTZ    NOT NULL
);
CREATE UNIQUE INDEX uk_attendance_record_student_class_date
    ON attendance_records (school_id, student_id, class_id, date);
CREATE INDEX idx_attendance_records_class_date
    ON attendance_records (school_id, class_id, date);
CREATE INDEX idx_attendance_records_student_date
    ON attendance_records (school_id, student_id, date);
--rollback DROP TABLE attendance_records;

--changeset schoolbridge:007-attendance-alert-recipients
--comment: Per-parent outbound row materialized when an AttendanceRecord transitions to a non-PRESENT status that triggers an alert. Mirrors announcement_recipients; deferred_until carries quiet-hours holds.
CREATE TABLE attendance_alert_recipients (
    id                     UUID         PRIMARY KEY,
    school_id              UUID         NOT NULL REFERENCES schools(id)             ON DELETE CASCADE,
    attendance_record_id   UUID         NOT NULL REFERENCES attendance_records(id)  ON DELETE CASCADE,
    parent_user_id         UUID         NOT NULL REFERENCES users(id)               ON DELETE CASCADE,
    student_id             UUID         NOT NULL REFERENCES students(id)            ON DELETE CASCADE,
    delivery_status        VARCHAR(20)  NOT NULL,
    message_id             VARCHAR(255),
    last_error             VARCHAR(256),
    deferred_until         TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX uk_attendance_alert_recipient
    ON attendance_alert_recipients (attendance_record_id, parent_user_id);
CREATE INDEX idx_attendance_alert_recipients_record
    ON attendance_alert_recipients (attendance_record_id);
CREATE INDEX idx_attendance_alert_recipients_school_parent
    ON attendance_alert_recipients (school_id, parent_user_id);
CREATE INDEX idx_attendance_alert_recipients_deferred
    ON attendance_alert_recipients (school_id, deferred_until)
    WHERE deferred_until IS NOT NULL AND message_id IS NULL;
CREATE INDEX idx_attendance_alert_recipients_message_id
    ON attendance_alert_recipients (message_id)
    WHERE message_id IS NOT NULL;
--rollback DROP TABLE attendance_alert_recipients;
