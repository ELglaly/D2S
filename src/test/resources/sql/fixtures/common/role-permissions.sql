-- Permission IDs belong to Liquibase's preserved catalog. The fixed fixture ID is used only when
-- the default grant is absent, so the script remains compatible with the seeded role catalog.
INSERT INTO role_permissions (id, role, permission_id, created_at)
SELECT '40000000-0000-0000-0000-000000000001', 'SCHOOL_ADMIN', id, '2025-01-01T00:00:00Z'
FROM permissions
WHERE name = 'USER_READ'
ON CONFLICT (role, permission_id) DO NOTHING;
