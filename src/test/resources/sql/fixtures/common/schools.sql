-- Stable tenants used by SQL-backed integration tests. Timestamps are deliberately fixed so
-- response mapping and sort assertions remain deterministic.
INSERT INTO schools (
    id, name, country, timezone, locale, subscription_tier, status,
    default_language, quiet_hours_start, quiet_hours_end,
    homework_reminder_enabled, homework_reminder_time, fee_reminder_offset_days,
    sms_fallback_enabled, alerts_respect_quiet_hours, roster_due_by_local_time,
    created_at, updated_at
) VALUES
(
    '10000000-0000-0000-0000-000000000001', 'Fixture School One', 'EG',
    'Africa/Cairo', 'en-EG', 'STANDARD', 'ACTIVE', 'EN', '21:00', '07:00',
    TRUE, '19:00', '[-7,-1,0,7]'::jsonb, FALSE, FALSE, '09:00',
    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'
),
(
    '10000000-0000-0000-0000-000000000002', 'Fixture School Two', 'EG',
    'Africa/Cairo', 'en-EG', 'STANDARD', 'ACTIVE', 'EN', '21:00', '07:00',
    TRUE, '19:00', '[-7,-1,0,7]'::jsonb, FALSE, FALSE, '09:00',
    '2025-01-02T00:00:00Z', '2025-01-02T00:00:00Z'
),
(
    '10000000-0000-0000-0000-000000000003', 'Fixture Suspended School', 'EG',
    'Africa/Cairo', 'en-EG', 'STANDARD', 'SUSPENDED', 'EN', '21:00', '07:00',
    TRUE, '19:00', '[-7,-1,0,7]'::jsonb, FALSE, FALSE, '09:00',
    '2025-01-03T00:00:00Z', '2025-01-03T00:00:00Z'
);
