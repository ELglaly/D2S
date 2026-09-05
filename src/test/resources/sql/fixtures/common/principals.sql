-- Shared fixture identities. Every credential uses the BCrypt hash below for the literal password
-- "password": $2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq.
INSERT INTO platform_admins (id, email, password_hash, name, status, created_at, updated_at)
VALUES
    ('20000000-0000-0000-0000-000000000001', 'admin@platform.test', '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'Fixture Platform Admin', 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('20000000-0000-0000-0000-000000000002', 'admin2@platform.test', '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'Fixture Platform Admin Two', 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z');

INSERT INTO users (id, school_id, role, name, email, phone, phone_hash, password_hash, status, created_at, updated_at)
VALUES
    ('20000000-0000-0000-0000-000000000010', '10000000-0000-0000-0000-000000000001', 'TEACHER', 'Fixture Teacher', 'teacher@fixture.test', NULL, NULL, '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('20000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000001', 'SCHOOL_ADMIN', 'Fixture School Admin', 'school-admin@fixture.test', NULL, NULL, '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('20000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000002', 'SCHOOL_ADMIN', 'Fixture School Two Admin', 'school-two-admin@fixture.test', NULL, NULL, '$2a$10$KFlNgrfjuSjgaMFYvK2A1eXsQcEJTqCV0Oqg1il5ZSC9prGl1Y7kq', 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('20000000-0000-0000-0000-000000000013', '10000000-0000-0000-0000-000000000001', 'PARENT', 'Fixture Linked Parent', NULL, '+201090000201', 'ZzRnpmVpGY0zQiC47FEwUCb3Qin67Xq3s0B/3fGpcTg=', NULL, 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('20000000-0000-0000-0000-000000000014', '10000000-0000-0000-0000-000000000001', 'PARENT', 'Fixture Unlinked Parent', NULL, '+201090000202', '1Q4cI+HUnox0z1FhuWhtGAS8j9HYCKmrlZ4dD9qBU5M=', NULL, 'ACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z');
