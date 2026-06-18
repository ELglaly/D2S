--liquibase formatted sql

--changeset schoolbridge:015-permissions-table
--comment: Global permission catalog. NOT tenant-scoped: permission names are platform-wide and the same across all schools. Mirrors the Permission enum; the admin endpoints assign these to roles at runtime.
CREATE TABLE permissions (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_permissions_name ON permissions (name);
--rollback DROP TABLE permissions;

--changeset schoolbridge:015-role-permissions-table
--comment: Many-to-many between the fixed UserRole enum (stored as text) and permissions. A user has a single role, so their effective permissions = the rows for that role. FK to permissions(id) is ON DELETE CASCADE per the FK-cascade rule.
CREATE TABLE role_permissions (
    id            UUID        PRIMARY KEY,
    role          VARCHAR(20) NOT NULL,
    permission_id UUID        NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_role_permissions ON role_permissions (role, permission_id);
CREATE INDEX idx_role_permissions_role ON role_permissions (role);
--rollback DROP TABLE role_permissions;

--changeset schoolbridge:015-seed-permissions
--comment: Seed the permission catalog from the Permission enum. gen_random_uuid() is built into PostgreSQL 16.
INSERT INTO permissions (id, name) VALUES
  (gen_random_uuid(), 'GRADE_CREATE'),
  (gen_random_uuid(), 'GRADE_READ'),
  (gen_random_uuid(), 'GRADE_UPDATE'),
  (gen_random_uuid(), 'GRADE_DELETE'),
  (gen_random_uuid(), 'HOMEWORK_CREATE'),
  (gen_random_uuid(), 'HOMEWORK_PUBLISH'),
  (gen_random_uuid(), 'HOMEWORK_READ'),
  (gen_random_uuid(), 'HOMEWORK_UPDATE'),
  (gen_random_uuid(), 'HOMEWORK_DELETE'),
  (gen_random_uuid(), 'HOMEWORK_ACK'),
  (gen_random_uuid(), 'ATTENDANCE_RECORD'),
  (gen_random_uuid(), 'ATTENDANCE_READ'),
  (gen_random_uuid(), 'CLASS_MANAGE'),
  (gen_random_uuid(), 'CLASS_READ'),
  (gen_random_uuid(), 'SUBJECT_MANAGE'),
  (gen_random_uuid(), 'SUBJECT_READ'),
  (gen_random_uuid(), 'ENROLLMENT_MANAGE'),
  (gen_random_uuid(), 'STUDENT_MANAGE'),
  (gen_random_uuid(), 'STUDENT_READ'),
  (gen_random_uuid(), 'PARENT_LINK_MANAGE'),
  (gen_random_uuid(), 'ANNOUNCEMENT_SEND'),
  (gen_random_uuid(), 'ANNOUNCEMENT_READ'),
  (gen_random_uuid(), 'ANNOUNCEMENT_MANAGE'),
  (gen_random_uuid(), 'ASSISTANT_SETTINGS_MANAGE'),
  (gen_random_uuid(), 'DOCUMENT_MANAGE'),
  (gen_random_uuid(), 'USER_MANAGE'),
  (gen_random_uuid(), 'SCHOOL_MANAGE'),
  (gen_random_uuid(), 'WHATSAPP_DIAGNOSTICS'),
  (gen_random_uuid(), 'MANAGE_ROLES'),
  (gen_random_uuid(), 'MANAGE_PERMISSIONS');
--rollback DELETE FROM permissions;

--changeset schoolbridge:015-seed-role-permissions
--comment: Default role->permission grants. SUPER_ADMIN gets EVERY permission. MANAGE_ROLES and MANAGE_PERMISSIONS are granted ONLY to SUPER_ADMIN, so no other role can alter authz mappings. Row-level ownership (e.g. a teacher's own class) stays enforced by the @perms SpEL helper, not by these coarse grants.
INSERT INTO role_permissions (id, role, permission_id)
  SELECT gen_random_uuid(), 'SUPER_ADMIN', p.id FROM permissions p;

INSERT INTO role_permissions (id, role, permission_id)
  SELECT gen_random_uuid(), 'SCHOOL_ADMIN', p.id FROM permissions p
  WHERE p.name IN (
    'GRADE_CREATE','GRADE_READ','GRADE_UPDATE','GRADE_DELETE',
    'HOMEWORK_CREATE','HOMEWORK_PUBLISH','HOMEWORK_READ','HOMEWORK_UPDATE','HOMEWORK_DELETE',
    'ATTENDANCE_RECORD','ATTENDANCE_READ',
    'CLASS_MANAGE','CLASS_READ','SUBJECT_MANAGE','SUBJECT_READ','ENROLLMENT_MANAGE',
    'STUDENT_MANAGE','STUDENT_READ','PARENT_LINK_MANAGE',
    'ANNOUNCEMENT_SEND','ANNOUNCEMENT_READ','ANNOUNCEMENT_MANAGE',
    'ASSISTANT_SETTINGS_MANAGE','DOCUMENT_MANAGE');

INSERT INTO role_permissions (id, role, permission_id)
  SELECT gen_random_uuid(), 'TEACHER', p.id FROM permissions p
  WHERE p.name IN (
    'GRADE_CREATE','GRADE_READ','GRADE_UPDATE',
    'HOMEWORK_CREATE','HOMEWORK_PUBLISH','HOMEWORK_READ','HOMEWORK_UPDATE','HOMEWORK_DELETE',
    'ATTENDANCE_RECORD','ATTENDANCE_READ',
    'CLASS_READ','SUBJECT_READ','STUDENT_READ',
    'ANNOUNCEMENT_SEND','ANNOUNCEMENT_READ');

INSERT INTO role_permissions (id, role, permission_id)
  SELECT gen_random_uuid(), 'PARENT', p.id FROM permissions p
  WHERE p.name IN ('GRADE_READ','HOMEWORK_READ','HOMEWORK_ACK','ANNOUNCEMENT_READ');
--rollback DELETE FROM role_permissions;

--changeset schoolbridge:015-add-user-read-permission
--comment: USER_READ — read/list users (e.g. the assistant's list_teachers tool) without the broader USER_MANAGE. Added as a new changeset so the original seed stays immutable (forward-only).
INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), 'USER_READ');
--rollback DELETE FROM permissions WHERE name = 'USER_READ';

--changeset schoolbridge:015-grant-user-read
--comment: Grant USER_READ to SUPER_ADMIN and SCHOOL_ADMIN (the roles that may enumerate staff).
INSERT INTO role_permissions (id, role, permission_id)
  SELECT gen_random_uuid(), r.role, p.id
  FROM permissions p
  CROSS JOIN (VALUES ('SUPER_ADMIN'), ('SCHOOL_ADMIN')) AS r(role)
  WHERE p.name = 'USER_READ';
--rollback DELETE FROM role_permissions WHERE permission_id = (SELECT id FROM permissions WHERE name = 'USER_READ');
