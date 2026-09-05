-- Adds the parent relationship needed for published-homework fan-out. Import after the subject
-- fixture, which supplies the enrolled student and class.
INSERT INTO parent_student_links (
    id, school_id, parent_user_id, student_id, relationship, primary_contact, created_at, updated_at
) VALUES (
    '50000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000013', '30000000-0000-0000-0000-000000000011', 'MOTHER',
    TRUE, '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);
