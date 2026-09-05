-- Builds on academic-roster.sql and supplies the parent-facing relationship and enrolment.
INSERT INTO enrollments (id, school_id, student_id, class_id, created_at, updated_at)
VALUES (
    '30000000-0000-0000-0000-000000000021', '10000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000011', '30000000-0000-0000-0000-000000000001',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);

INSERT INTO parent_student_links (
    id, school_id, parent_user_id, student_id, relationship, primary_contact, created_at, updated_at
) VALUES (
    '30000000-0000-0000-0000-000000000031', '10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000013', '30000000-0000-0000-0000-000000000011',
    'MOTHER', TRUE, '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);
