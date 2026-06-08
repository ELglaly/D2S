--liquibase formatted sql

--changeset schoolbridge:003-schools
--comment: Tenant root. Settings are embedded as columns for MVP simplicity (1:1, hot-read path).
CREATE TABLE schools (
    id                          UUID PRIMARY KEY,
    name                        VARCHAR(120) NOT NULL,
    country                     VARCHAR(2) NOT NULL,
    timezone                    VARCHAR(64) NOT NULL,
    locale                      VARCHAR(35) NOT NULL,
    subscription_tier           VARCHAR(20) NOT NULL,
    status                      VARCHAR(20) NOT NULL,
    default_language            VARCHAR(2)  NOT NULL DEFAULT 'EN',
    quiet_hours_start           TIME        NOT NULL DEFAULT '21:00',
    quiet_hours_end             TIME        NOT NULL DEFAULT '07:00',
    homework_reminder_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
    homework_reminder_time      TIME        NOT NULL DEFAULT '19:00',
    fee_reminder_offset_days    JSONB       NOT NULL DEFAULT '[-7,-1,0,7]'::jsonb,
    waba_phone_number_id        VARCHAR(64),
    sms_fallback_enabled        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_schools_status ON schools (status);
--rollback DROP TABLE schools;
