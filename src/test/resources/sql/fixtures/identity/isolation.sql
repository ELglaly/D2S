-- Identity rows deliberately span both fixture tenants. Used by repository/RLS integration tests;
-- no test mutates prerequisites through repositories.
INSERT INTO users (id, school_id, role, name, email, phone, phone_hash, password_hash, status, created_at, updated_at)
VALUES
    ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'PARENT', 'Isolation Parent A', NULL, '+201080000001', '2EFXAwdITL21zrtIuqCH9B5Bpxd+PrYdKrguMC8K3EA=', NULL, 'ACTIVE', '2025-01-04T00:00:00Z', '2025-01-04T00:00:00Z'),
    ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'PARENT', 'Isolation Parent B', NULL, '+201080000002', '1Uvo+6NlbiecuvY7JRzD12iwOFrg5571kuQMWeX68xo=', NULL, 'ACTIVE', '2025-01-04T00:00:00Z', '2025-01-04T00:00:00Z');

INSERT INTO device_tokens (id, school_id, user_id, platform, fcm_token, device_id, active, created_at, updated_at)
VALUES
    ('31000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'ANDROID', 'isolation-token-a', 'isolation-device-a', TRUE, '2025-01-04T00:00:00Z', '2025-01-04T00:00:00Z'),
    ('31000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', 'IOS', 'isolation-token-b', 'isolation-device-b', TRUE, '2025-01-04T00:00:00Z', '2025-01-04T00:00:00Z');
