-- Credentials for HTTP authentication integration tests.
-- BCrypt hash for the literal password "password". Keeping it in SQL makes the
-- fixture importable without invoking repositories or application services.
INSERT INTO platform_admins (id, email, password_hash, name, status, created_at, updated_at)
VALUES
    ('20000000-0000-0000-0000-000000000001', 'admin@platform.test', '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'Fixture Platform Admin', 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('20000000-0000-0000-0000-000000000002', 'admin2@platform.test', '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'Fixture Platform Admin Two', 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('20000000-0000-0000-0000-000000000003', 'admin3@platform.test', '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'Fixture Platform Admin Three', 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('20000000-0000-0000-0000-000000000004', 'admin4@platform.test', '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'Fixture Platform Admin Four', 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('20000000-0000-0000-0000-000000000005', 'admin5@platform.test', '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'Fixture Platform Admin Five', 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z');

INSERT INTO users (
    id, school_id, role, name, email, phone, phone_hash, password_hash, status, created_at, updated_at
) VALUES (
    '20000000-0000-0000-0000-000000000010', '10000000-0000-0000-0000-000000000001',
    'TEACHER', 'Fixture Teacher', 'teacher@x.test', NULL, NULL,
    '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'ACTIVE',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);

-- Tenant principals used by the academic-structure HTTP suites.  Parent phone_hash values are
-- deliberately deterministic fixture-only blind-index placeholders: these tests authenticate with
-- bearer tokens and never use a phone lookup.
INSERT INTO users (
    id, school_id, role, name, email, phone, phone_hash, password_hash, status, created_at, updated_at
) VALUES
(
    '20000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000001',
    'SCHOOL_ADMIN', 'Fixture School Admin', 'school-admin@fixture.test', NULL, NULL,
    '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'ACTIVE',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
),
(
    '20000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000001',
    'SCHOOL_ADMIN', 'Fixture School Admin Two', 'school-admin-two@fixture.test', NULL, NULL,
    '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'ACTIVE',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
),
(
    '20000000-0000-0000-0000-000000000013', '10000000-0000-0000-0000-000000000001',
    'PARENT', 'Fixture Linked Parent', NULL, '+201090000201', 'fixture-parent-linked', NULL, 'ACTIVE',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
),
(
    '20000000-0000-0000-0000-000000000014', '10000000-0000-0000-0000-000000000001',
    'PARENT', 'Fixture Unlinked Parent', NULL, '+201090000202', 'fixture-parent-unlinked', NULL, 'ACTIVE',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
);
