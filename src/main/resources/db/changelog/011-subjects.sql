--liquibase formatted sql

--changeset schoolbridge:011-subjects
--comment: Subjects per school, class_subjects junction, and teacher_subject_assignments replacing teacher_assignments.

CREATE TABLE subjects (
    id           UUID          PRIMARY KEY,
    school_id    UUID          NOT NULL REFERENCES schools(id)        ON DELETE CASCADE,
    name         VARCHAR(255)  NOT NULL,
    code         VARCHAR(50),
    description  VARCHAR(1024),
    status       VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL
);
CREATE UNIQUE INDEX uk_subject_school_name ON subjects (school_id, name);
CREATE INDEX idx_subject_school ON subjects (school_id);

CREATE TABLE class_subjects (
    id         UUID        PRIMARY KEY,
    school_id  UUID        NOT NULL REFERENCES schools(id)        ON DELETE CASCADE,
    class_id   UUID        NOT NULL REFERENCES school_classes(id) ON DELETE CASCADE,
    subject_id UUID        NOT NULL REFERENCES subjects(id)       ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uk_class_subject ON class_subjects (class_id, subject_id);
CREATE INDEX idx_cs_school_class ON class_subjects (school_id, class_id);

CREATE TABLE teacher_subject_assignments (
    id               UUID        PRIMARY KEY,
    school_id        UUID        NOT NULL REFERENCES schools(id)        ON DELETE CASCADE,
    teacher_user_id  UUID        NOT NULL REFERENCES users(id)          ON DELETE CASCADE,
    class_id         UUID        NOT NULL REFERENCES school_classes(id)  ON DELETE CASCADE,
    subject_id       UUID        NOT NULL REFERENCES subjects(id)        ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (class_id, subject_id) REFERENCES class_subjects (class_id, subject_id)
);
CREATE UNIQUE INDEX uk_tsa ON teacher_subject_assignments (teacher_user_id, class_id, subject_id);
CREATE INDEX idx_tsa_school_class ON teacher_subject_assignments (school_id, class_id);
CREATE INDEX idx_tsa_teacher ON teacher_subject_assignments (teacher_user_id);

DROP TABLE teacher_assignments;

--rollback DROP TABLE teacher_subject_assignments;
--rollback DROP TABLE class_subjects;
--rollback DROP TABLE subjects;
--rollback CREATE TABLE teacher_assignments (id UUID PRIMARY KEY, school_id UUID NOT NULL REFERENCES schools(id) ON DELETE CASCADE, teacher_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, class_id UUID NOT NULL REFERENCES school_classes(id) ON DELETE CASCADE, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL);
--rollback CREATE UNIQUE INDEX uk_teacher_assignment ON teacher_assignments (teacher_user_id, class_id);
--rollback CREATE INDEX idx_ta_school_class ON teacher_assignments (school_id, class_id);
