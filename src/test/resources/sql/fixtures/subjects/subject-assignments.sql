-- Subject-domain fixture. Import after common/academic-structure.sql. It supplies both
-- tenant-owned records and one enrolled student for relationship and resolution scenarios.
INSERT INTO enrollments (id, school_id, student_id, class_id, created_at, updated_at)
VALUES (
    '30000000-0000-0000-0000-000000000021', '10000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000011', '30000000-0000-0000-0000-000000000001',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);

INSERT INTO subjects (id, school_id, name, code, description, status, created_at, updated_at)
VALUES
(
    '40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
    'Fixture Mathematics', 'MATH-3', 'Fixture primary mathematics', 'ACTIVE',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
),
(
    '40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
    'Other Tenant Science', 'SCI-OTHER', 'Other tenant subject', 'ACTIVE',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);

INSERT INTO class_subjects (id, school_id, class_id, subject_id, created_at, updated_at)
VALUES (
    '40000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);

INSERT INTO teacher_subject_assignments (
    id, school_id, teacher_user_id, class_id, subject_id, created_at, updated_at
) VALUES (
    '40000000-0000-0000-0000-000000000021', '10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000010', '30000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);
