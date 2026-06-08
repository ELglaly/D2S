--liquibase formatted sql

--changeset schoolbridge:005-school-classes
--comment: Classes within a school; optionally linked to a homeroom teacher from users.
CREATE TABLE school_classes (
    id                   UUID          PRIMARY KEY,
    school_id            UUID          NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    name                 VARCHAR(255)  NOT NULL,
    grade_level          VARCHAR(100)  NOT NULL,
    academic_year        VARCHAR(20)   NOT NULL,
    homeroom_teacher_id  UUID          REFERENCES users(id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_school_classes_school ON school_classes (school_id);
--rollback DROP TABLE school_classes;

--changeset schoolbridge:005-students
--comment: Tenant-scoped student profiles. full_name is AES-GCM encrypted at rest.
CREATE TABLE students (
    id             UUID           PRIMARY KEY,
    school_id      UUID           NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    full_name      VARCHAR(1024)  NOT NULL,
    date_of_birth  DATE,
    external_id    VARCHAR(100),
    status         VARCHAR(20)    NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL
);
CREATE UNIQUE INDEX uk_students_school_external_id
    ON students (school_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX idx_students_school ON students (school_id);
--rollback DROP TABLE students;

--changeset schoolbridge:005-enrollments
--comment: M:N bridge between students and classes; supports multi-class tutoring centres.
CREATE TABLE enrollments (
    id          UUID        PRIMARY KEY,
    school_id   UUID        NOT NULL REFERENCES schools(id)       ON DELETE CASCADE,
    student_id  UUID        NOT NULL REFERENCES students(id)      ON DELETE CASCADE,
    class_id    UUID        NOT NULL REFERENCES school_classes(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uk_enrollment_student_class ON enrollments (student_id, class_id);
CREATE INDEX idx_enrollment_school ON enrollments (school_id);
CREATE INDEX idx_enrollment_class  ON enrollments (class_id);
--rollback DROP TABLE enrollments;

--changeset schoolbridge:005-teacher-assignments
--comment: Drives "teacher sees only their classes" row-level authorization.
CREATE TABLE teacher_assignments (
    id               UUID        PRIMARY KEY,
    school_id        UUID        NOT NULL REFERENCES schools(id)       ON DELETE CASCADE,
    teacher_user_id  UUID        NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    class_id         UUID        NOT NULL REFERENCES school_classes(id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uk_teacher_assignment
    ON teacher_assignments (teacher_user_id, class_id);
CREATE INDEX idx_teacher_assignment_school ON teacher_assignments (school_id);
CREATE INDEX idx_teacher_assignment_class  ON teacher_assignments (class_id);
--rollback DROP TABLE teacher_assignments;

--changeset schoolbridge:005-parent-student-links
--comment: Links parent users to their children; primaryContact determines who receives alerts.
CREATE TABLE parent_student_links (
    id               UUID        PRIMARY KEY,
    school_id        UUID        NOT NULL REFERENCES schools(id)  ON DELETE CASCADE,
    parent_user_id   UUID        NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    student_id       UUID        NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    relationship     VARCHAR(20) NOT NULL,
    primary_contact  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uk_parent_student_link
    ON parent_student_links (parent_user_id, student_id);
CREATE INDEX idx_parent_student_school   ON parent_student_links (school_id);
CREATE INDEX idx_parent_student_student  ON parent_student_links (student_id);
--rollback DROP TABLE parent_student_links;
