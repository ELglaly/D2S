--liquibase formatted sql

--changeset schoolbridge:020-authorization-normalization
--comment: Complete the permission catalog/grants required by the @RequirePermission migration while preserving existing endpoint access.
INSERT INTO permissions (id, name) VALUES
  (gen_random_uuid(), 'ATTENDANCE_RESPOND')
ON CONFLICT (name) DO NOTHING;

INSERT INTO role_permissions (id, role, permission_id)
  SELECT gen_random_uuid(), r.role, p.id
  FROM permissions p
  CROSS JOIN (VALUES ('PARENT')) AS r(role)
  WHERE p.name IN ('ATTENDANCE_READ', 'ATTENDANCE_RESPOND', 'STUDENT_READ', 'SUBJECT_READ')
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions existing
    WHERE existing.role = r.role AND existing.permission_id = p.id
  );

INSERT INTO role_permissions (id, role, permission_id)
  SELECT gen_random_uuid(), 'TEACHER', p.id
  FROM permissions p
  WHERE p.name = 'GRADE_DELETE'
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp WHERE rp.role = 'TEACHER' AND rp.permission_id = p.id
  );
--rollback DELETE FROM role_permissions WHERE role = 'PARENT' AND permission_id IN (SELECT id FROM permissions WHERE name IN ('ATTENDANCE_READ','ATTENDANCE_RESPOND','STUDENT_READ','SUBJECT_READ'));
--rollback DELETE FROM role_permissions WHERE role = 'TEACHER' AND permission_id = (SELECT id FROM permissions WHERE name = 'GRADE_DELETE');
--rollback DELETE FROM permissions WHERE name = 'ATTENDANCE_RESPOND';



