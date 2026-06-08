--liquibase formatted sql

--changeset schoolbridge:010-grades
--comment: Per-(student, class, subject, period) grade record with optional numeric score and letter grade.
CREATE TABLE grade_records (
    id                  UUID           PRIMARY KEY,
    school_id           UUID           NOT NULL REFERENCES schools(id)        ON DELETE CASCADE,
    student_id          UUID           NOT NULL REFERENCES students(id)       ON DELETE CASCADE,
    class_id            UUID           NOT NULL REFERENCES school_classes(id) ON DELETE CASCADE,
    subject             VARCHAR(200)   NOT NULL,
    period              VARCHAR(50)    NOT NULL,
    score               DECIMAL(5,2),
    grade_label         VARCHAR(10),
    graded_by_user_id   UUID           NOT NULL REFERENCES users(id)          ON DELETE CASCADE,
    graded_at           TIMESTAMPTZ    NOT NULL,
    notes               TEXT,
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL
);
CREATE UNIQUE INDEX uk_grade_record_student_class_subject_period
    ON grade_records (school_id, student_id, class_id, subject, period);
CREATE INDEX idx_grade_record_student ON grade_records (school_id, student_id);
CREATE INDEX idx_grade_record_class   ON grade_records (school_id, class_id);
--rollback DROP TABLE grade_records;
