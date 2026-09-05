--liquibase formatted sql

--changeset schoolbridge:019-notification-settings
--comment: Per-user quiet-hours settings. Null values inherit the school window; no row preserves notification defaults.
CREATE TABLE notification_settings (
    id                  UUID        PRIMARY KEY,
    school_id           UUID        NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    user_id             UUID        NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    respect_quiet_hours BOOLEAN     NOT NULL,
    quiet_hours_start   TIME,
    quiet_hours_end     TIME,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uk_notification_settings_user ON notification_settings (school_id, user_id);
--rollback DROP TABLE notification_settings;

--changeset schoolbridge:019-notification-preferences
--comment: Per-category delivery preferences. Channel order defines fallback order; attendance alerts remain mandatory.
CREATE TABLE notification_preferences (
    id         UUID        PRIMARY KEY,
    school_id  UUID        NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    category   VARCHAR(20) NOT NULL,
    enabled    BOOLEAN     NOT NULL,
    channels   JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uk_notification_preferences_user_category
    ON notification_preferences (school_id, user_id, category);
--rollback DROP TABLE notification_preferences;

--changeset schoolbridge:019-notification-rls
--comment: Enforce tenant isolation. nullif() makes an unbound pooled connection fail closed; production uses a non-owner role.
ALTER TABLE notification_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY notification_settings_tenant_isolation ON notification_settings
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE notification_preferences ENABLE ROW LEVEL SECURITY;
CREATE POLICY notification_preferences_tenant_isolation ON notification_preferences
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');
--rollback DROP POLICY notification_preferences_tenant_isolation ON notification_preferences; ALTER TABLE notification_preferences DISABLE ROW LEVEL SECURITY; DROP POLICY notification_settings_tenant_isolation ON notification_settings; ALTER TABLE notification_settings DISABLE ROW LEVEL SECURITY;

--changeset schoolbridge:019-announcement-recipient-deferral
--comment: Announcements gain the deferral column homework_recipients and attendance_alert_recipients already have. Until now an announcement had no deferral path at all, which is why announcements -- the highest-volume parent-facing message -- were the one channel that could still fire at 22:00. The partial index is what the sweeper's release scan reads; partial on status so the every-minute scan does not walk every SENT row in the largest table in the schema.
ALTER TABLE announcement_recipients ADD COLUMN deferred_until TIMESTAMPTZ;
CREATE INDEX idx_announcement_recipients_deferred ON announcement_recipients (deferred_until)
    WHERE delivery_status = 'DEFERRED';
--rollback DROP INDEX idx_announcement_recipients_deferred; ALTER TABLE announcement_recipients DROP COLUMN deferred_until;
