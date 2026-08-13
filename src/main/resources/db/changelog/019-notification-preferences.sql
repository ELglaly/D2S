--liquibase formatted sql

--changeset schoolbridge:019-notification-settings
--comment: Per-user notification settings (P0 item 11, docs/PLAN_NOTIFICATION_PREFERENCES.md). One row per user, holding only the quiet-hours window, because a person means one thing by "do not message me at night" -- storing the window per category invites the categories to disagree and gives the UI three widgets for one intent. NULL start/end means "inherit the school window" from schools.quiet_hours_*, so a school that retimes its window does not leave every user pinned to the old one. Absence of a row is not "muted": it means defaults, so nothing needs backfilling for existing users and a failed write can never silently silence someone. Both FKs cascade per the FK-cascade rule -- existing integration tests tear down with userRepository.deleteAll() and would break on a new child table otherwise.
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
--comment: Per-(user, category) opt-out and ordered channel list. channels is a JSONB array whose ORDER is the meaning -- ["PUSH","WHATSAPP","SMS"] says try push, then WhatsApp, then SMS -- so it cannot be normalised into a set without losing the preference. category is a VARCHAR rather than an enum type: Liquibase forward-only migrations make adding a value to a Postgres enum a migration, and the application-side NotificationCategory is the authority. ATTENDANCE rows may exist and are deliberately ignored by the resolver: absence alerts carry the NFR-P2 five-minute SLA and a parent who muted them would not learn their child is missing, so the immutability is enforced in code where it can be tested, and the API rejects the attempt with 422 rather than writing a row that lies about what will happen.
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
--comment: Row-Level Security on both new tables, in the shape changelog 017 established for every tenant-owned table. nullif() is required, not decoration -- current_setting(k, true) yields '' rather than NULL once set_config has run on the connection, and ''::uuid raises, so without it the second unbound query on a pooled connection 500s instead of returning nothing. NOT FORCED, exactly as 014, 017 and 018: the owner bypasses (local dev, Testcontainers) while the policy activates for the least-privilege application role -- see docs/RUNBOOK.md and RlsStartupValidator.
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
