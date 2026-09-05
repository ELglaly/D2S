-- Minimal tenant-one class used by bulk-import scenarios. No students are seeded so row counts
-- assert only records created by the CSV request/service call.
INSERT INTO school_classes (
    id, school_id, name, grade_level, academic_year, homeroom_teacher_id, created_at, updated_at
) VALUES (
    '30000000-0000-0000-0000-000000000041', '10000000-0000-0000-0000-000000000001',
    'Fixture Import 3A', 'Grade 3', '2025-2026', '20000000-0000-0000-0000-000000000010',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);
