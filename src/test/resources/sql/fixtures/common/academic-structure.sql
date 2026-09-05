-- Common academic prerequisites. Import after schools.sql and principals.sql. The row ownership
-- deliberately spans two tenants so HTTP tests can prove cross-tenant denial without Java setup.
INSERT INTO school_classes (
    id, school_id, name, grade_level, academic_year, homeroom_teacher_id, created_at, updated_at
) VALUES (
    '30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
    'Fixture 3A', 'Grade 3', '2025-2026', '20000000-0000-0000-0000-000000000010',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);

INSERT INTO students (id, school_id, full_name, date_of_birth, external_id, status, created_at, updated_at)
VALUES
    ('30000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000001',
     'Fixture Linked Student', DATE '2015-03-15', 'FIXTURE-STUDENT-1', 'ACTIVE',
     '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('30000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000002',
     'Fixture Other Tenant Student', DATE '2015-01-01', 'FIXTURE-STUDENT-2', 'ACTIVE',
     '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z');
